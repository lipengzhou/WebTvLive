/**
 * WebTvLive 默认全屏注入脚本（第三版）
 *
 * 目标：把网页里正在播放的主 <video> 稳定铺满整个 WebView。
 *
 * 演进：
 *  v1 用 <style> 铺满 —— 真机上被 video 祖先的 transform 破坏 fixed 定位而失效；
 *  v2 清了祖先 transform，video 变 fixed 到 0,0，但 CCTV 播放器每帧用「内联样式」把 video
 *     重新设回 1138x640，页面顶部导航仍压在上面（内联样式优先级高于 <style> 里的 !important）。
 *  v3 改为「每秒把 !important 内联样式直接写到 video 元素上」（内联 important 优先级最高，能压过站点脚本），
 *     并隐藏 video 之外的其它 body 顶层节点，只留播放器这一支 DOM。
 *
 * 每秒幂等维护 + 自愈；通过 JavascriptInterface `AndroidTV` 回传心跳/尺寸。
 */
(function () {
  var MARK = 'data-webtvlive-fs';
  var KEEP = 'data-webtvlive-keep';   // 标记“需要保留可见”的祖先链
  var CLEARED = 'data-webtvlive-cleared';

  function log(msg) { try { console.log('[WebTvLive] ' + msg); } catch (e) {} }
  function notifyPlaying() {
    try { window.AndroidTV && window.AndroidTV.notifyVideoPlaying && window.AndroidTV.notifyVideoPlaying(); } catch (e) {}
  }
  function reportSize(v) {
    try { window.AndroidTV && window.AndroidTV.setVideoSize && window.AndroidTV.setVideoSize(v.videoWidth || 0, v.videoHeight || 0); } catch (e) {}
  }

  function setImp(el, k, v) { try { el.style.setProperty(k, v, 'important'); } catch (e) {} }

  // 选“可见面积最大 / 正在播放”的 video
  function pickVideo() {
    var vs = document.querySelectorAll('video');
    var best = null, bestScore = -1;
    for (var i = 0; i < vs.length; i++) {
      var v = vs[i];
      var r = v.getBoundingClientRect();
      var area = r.width * r.height;
      var score = area + (v.readyState >= 2 ? 2e6 : 0) + (!v.paused ? 1e6 : 0);
      if (score > bestScore) { bestScore = score; best = v; }
    }
    return best || (vs.length ? vs[0] : null);
  }

  // 把 video 直到 body 的整条祖先链标记为 KEEP，并清掉会破坏 fixed 的 transform/filter/overflow
  function prepAncestors(v) {
    // 先清除上一轮的 KEEP 标记（换台后主播放器可能变化）
    var old = document.querySelectorAll('[' + KEEP + ']');
    for (var i = 0; i < old.length; i++) old[i].removeAttribute(KEEP);

    var n = v;
    while (n && n !== document.documentElement) {
      n.setAttribute(KEEP, '1');
      if (n !== v && n.nodeType === 1) {
        var cs = getComputedStyle(n);
        if (cs.transform !== 'none') setImp(n, 'transform', 'none');
        if (cs.filter !== 'none') setImp(n, 'filter', 'none');
        if (cs.perspective !== 'none') setImp(n, 'perspective', 'none');
        setImp(n, 'overflow', 'visible');
      }
      n = n.parentElement;
    }
  }

  // 隐藏 body 下不在 KEEP 链上的顶层兄弟节点（去掉网站导航/边栏等）
  function hideSiblings() {
    var body = document.body;
    if (!body) return;
    var kids = body.children;
    for (var i = 0; i < kids.length; i++) {
      var el = kids[i];
      if (el.tagName === 'SCRIPT' || el.tagName === 'STYLE') continue;
      if (el.hasAttribute(KEEP)) {
        setImp(el, 'visibility', 'visible');
      } else if (!el.hasAttribute(CLEARED)) {
        setImp(el, 'display', 'none');
        el.setAttribute(CLEARED, '1');
      }
    }
  }

  // 直接给 video 写 !important 内联样式（优先级最高，压过站点脚本）
  function styleVideo(v) {
    setImp(v, 'position', 'fixed');
    setImp(v, 'left', '0');
    setImp(v, 'top', '0');
    setImp(v, 'right', '0');
    setImp(v, 'bottom', '0');
    setImp(v, 'width', '100vw');
    setImp(v, 'height', '100vh');
    setImp(v, 'max-width', 'none');
    setImp(v, 'max-height', 'none');
    setImp(v, 'min-width', '0');
    setImp(v, 'min-height', '0');
    setImp(v, 'margin', '0');
    setImp(v, 'padding', '0');
    setImp(v, 'z-index', '2147483647');
    setImp(v, 'object-fit', 'contain');
    setImp(v, 'background', '#000');
    setImp(v, 'transform', 'none');
  }

  function ensureRootStyle() {
    var h = document.documentElement, b = document.body;
    if (h) { setImp(h, 'margin', '0'); setImp(h, 'padding', '0'); setImp(h, 'overflow', 'hidden'); setImp(h, 'background', '#000'); }
    if (b) { setImp(b, 'margin', '0'); setImp(b, 'padding', '0'); setImp(b, 'overflow', 'hidden'); setImp(b, 'background', '#000'); }
  }

  // 是否「真的在播放」：非暂停、非结束、已缓冲到可连续播放的数据、有画面尺寸、且时间在走动。
  // 只有满足这些才回传心跳，让原生撤掉加载遮罩——避免停在封面/播放按钮时就误判成已播放。
  function isReallyPlaying(v) {
    return !v.paused && !v.ended && v.readyState >= 3 && v.videoWidth > 0 && v.currentTime > 0;
  }

  var diagOnce = false;

  function maintain() {
    var v = pickVideo();
    if (!v) { return; }

    ensureRootStyle();
    prepAncestors(v);
    hideSiblings();

    var all = document.querySelectorAll('video[' + MARK + ']');
    for (var i = 0; i < all.length; i++) if (all[i] !== v) all[i].removeAttribute(MARK);
    if (!v.hasAttribute(MARK)) { v.setAttribute(MARK, '1'); reportSize(v); }

    styleVideo(v);

    // 强制播放
    v.autoplay = true;
    v.setAttribute('playsinline', '');
    v.setAttribute('webkit-playsinline', '');
    if (v.paused) {
      var p = v.play();
      if (p && p.catch) p.catch(function () { v.muted = true; var p2 = v.play(); if (p2 && p2.catch) p2.catch(function () {}); });
    }
    // 每帧都把视频本身音量拉到 100% 且取消静音（站点脚本/自动静音起播后会改回来，需持续压制）
    try {
      if (v.muted) v.muted = false;
      if (v.volume !== 1) v.volume = 1;
    } catch (e) {}
    // 画面已就绪且已铺满，才通知原生切到视频画面
    if (isReallyPlaying(v)) {
      notifyPlaying();
    }

    if (!diagOnce && v.videoWidth > 0) {
      diagOnce = true;
      setTimeout(function () {
        var r = v.getBoundingClientRect();
        log('DIAG rect=' + Math.round(r.x) + ',' + Math.round(r.y) + ' ' + Math.round(r.width) + 'x' + Math.round(r.height) +
          ' viewport=' + window.innerWidth + 'x' + window.innerHeight +
          ' pos=' + getComputedStyle(v).position + ' z=' + getComputedStyle(v).zIndex +
          ' volume=' + v.volume + ' muted=' + v.muted);
      }, 400);
    }
  }

  if (!window.__webtvlive_timer__) {
    log('injected v3.');
    maintain();
    window.__webtvlive_timer__ = setInterval(maintain, 1000);
  } else {
    maintain();
  }
})();
