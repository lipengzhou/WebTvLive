(function () {
  var MARK = 'data-webtvlive-fs';
  var KEEP = 'data-webtvlive-keep';
  var CLEARED = 'data-webtvlive-cleared';
  var playingNotified = false;
  var configuredVideo = null;
  var pendingChannel = '';
  var nativePort = null;
  var videoBeforeChannelSwitch = null;
  var waitingForReplacementVideo = false;
  var pendingRequestId = 0;
  var activePlaybackRequestId = 0;

  function send(type, message) {
    try {
      browser.runtime.sendNativeMessage('webtvlive', { type: type, message: message || '' });
    } catch (e) {}
  }

  function sendPort(type, extra) {
    if (!nativePort) return;
    try {
      var payload = { type: type };
      if (extra) {
        for (var key in extra) payload[key] = extra[key];
      }
      nativePort.postMessage(payload);
    } catch (e) {}
  }

  /**
   * 建立 App <-> 页面持久连接。Android 端通过此 Port 下发频道名，页面直接点击央视频
   * 已渲染的频道项，让网站自己的 Vue 逻辑局部重建播放器，避免整页 loadUri。
   */
  function connectNativePort() {
    try {
      nativePort = browser.runtime.connectNative('webtvlive');
      nativePort.onMessage.addListener(function (message) {
        if (message && message.type === 'switchChannel' && message.channel) {
          pendingChannel = String(message.channel);
          pendingRequestId = Number(message.requestId) || 0;
          selectYangshipinChannel();
        }
      });
      nativePort.onDisconnect.addListener(function () {
        nativePort = null;
      });
      sendPort('ready');
    } catch (e) {
      send('diagnostic', 'Unable to connect native port: ' + e);
    }
  }

  function setImp(el, key, value) {
    try { el.style.setProperty(key, value, 'important'); } catch (e) {}
  }

  send('diagnostic', 'Gecko adapter injected: ' + location.href);
  connectNativePort();

  function getYangshipinChannelName(element) {
    var source = element.querySelector('span') || element;
    var clone = source.cloneNode(true);
    var tags = clone.querySelectorAll('.tv-main-con-r-list-left-tag');
    for (var i = 0; i < tags.length; i++) tags[i].remove();
    return (clone.textContent || '').trim();
  }

  function selectYangshipinChannel() {
    if (!pendingChannel || location.hostname.indexOf('yangshipin.cn') < 0) return false;

    var items = document.querySelectorAll(
      '.tv-main-con-r-list-left-imga, .tv-main-con-r-list-left-imgb'
    );
    for (var i = 0; i < items.length; i++) {
      if (getYangshipinChannelName(items[i]) !== pendingChannel) continue;

      var selectedChannel = pendingChannel;
      pendingChannel = '';
      activePlaybackRequestId = pendingRequestId;
      pendingRequestId = 0;
      playingNotified = false;
      configuredVideo = null;

      // 如果本来就是目标频道，不需要重建播放器；否则记住旧 video。央视频点击频道后会
      // 局部替换 video 节点，在新节点出现前绝不能用仍在播放的旧节点回报 playing。
      if (items[i].classList.contains('tvSelect')) {
        videoBeforeChannelSwitch = null;
        waitingForReplacementVideo = false;
      } else {
        videoBeforeChannelSwitch = pickVideo();
        waitingForReplacementVideo = videoBeforeChannelSwitch !== null;
        if (videoBeforeChannelSwitch) {
          // 央视频有两种实现：有时替换整个 video 节点，有时复用同一节点重新装载媒体。
          // 对复用节点的情况，下一次 playing 事件就是新频道真正有画面的可靠信号。
          var observedVideo = videoBeforeChannelSwitch;
          var observedRequestId = activePlaybackRequestId;
          observedVideo.addEventListener('playing', function () {
            if (
              waitingForReplacementVideo &&
              activePlaybackRequestId === observedRequestId &&
              pickVideo() === observedVideo
            ) {
              waitingForReplacementVideo = false;
              videoBeforeChannelSwitch = null;
              send('diagnostic', 'Yangshipin reused video started playing');
            }
          }, { once: true });
        }
        items[i].click();
      }
      sendPort('channelSelected', { channel: selectedChannel });
      send('diagnostic', 'Yangshipin in-page switch: ' + selectedChannel);
      return true;
    }

    // 首页内容异步渲染；保留 pendingChannel，由 maintain() 继续重试。
    return false;
  }

  function selectBlueLightQuality() {
    var items = document.querySelectorAll('.bei-list .item');
    for (var i = 0; i < items.length; i++) {
      if ((items[i].textContent || '').trim() !== '蓝光 1080P') continue;
      if (!items[i].classList.contains('active')) items[i].click();
      return true;
    }
    return false;
  }

  function pickVideo() {
    var videos = document.querySelectorAll('video');
    var best = null;
    var bestScore = -1;
    for (var i = 0; i < videos.length; i++) {
      var video = videos[i];
      var rect = video.getBoundingClientRect();
      var score = rect.width * rect.height + (video.readyState >= 2 ? 2000000 : 0) + (!video.paused ? 1000000 : 0);
      if (score > bestScore) {
        bestScore = score;
        best = video;
      }
    }
    return best || (videos.length ? videos[0] : null);
  }

  function pickPlayerFrame() {
    var frames = document.querySelectorAll('iframe');
    var best = null;
    var bestScore = -1;
    for (var i = 0; i < frames.length; i++) {
      var frame = frames[i];
      var rect = frame.getBoundingClientRect();
      var src = (frame.src || '').toLowerCase();
      var sourceBonus = /(player|live|cntv|cctv|yangshipin)/.test(src) ? 10000000 : 0;
      var score = rect.width * rect.height + sourceBonus;
      if (score > bestScore) {
        bestScore = score;
        best = frame;
      }
    }
    return best;
  }

  function prepareAncestors(video) {
    var previous = document.querySelectorAll('[' + KEEP + ']');
    for (var i = 0; i < previous.length; i++) previous[i].removeAttribute(KEEP);

    var node = video;
    while (node && node !== document.documentElement) {
      node.setAttribute(KEEP, '1');
      if (node !== video && node.nodeType === 1) {
        var style = getComputedStyle(node);
        if (style.transform !== 'none') setImp(node, 'transform', 'none');
        if (style.filter !== 'none') setImp(node, 'filter', 'none');
        if (style.perspective !== 'none') setImp(node, 'perspective', 'none');
        setImp(node, 'overflow', 'visible');
      }
      node = node.parentElement;
    }
  }

  function hideSiblings() {
    var kept = document.querySelectorAll('[' + KEEP + ']');
    for (var i = 0; i < kept.length; i++) {
      var parent = kept[i].parentElement;
      if (!parent) continue;
      var children = parent.children;
      for (var j = 0; j < children.length; j++) {
        var element = children[j];
        if (element.tagName === 'SCRIPT' || element.tagName === 'STYLE') continue;
        if (element.hasAttribute(KEEP)) {
          if (element.hasAttribute(CLEARED)) {
            element.style.removeProperty('display');
            element.removeAttribute(CLEARED);
          }
          setImp(element, 'visibility', 'visible');
        } else if (!element.hasAttribute(CLEARED)) {
          setImp(element, 'display', 'none');
          element.setAttribute(CLEARED, '1');
        }
      }
    }
  }

  function styleFullscreen(element) {
    setImp(element, 'position', 'fixed');
    setImp(element, 'left', '0');
    setImp(element, 'top', '0');
    setImp(element, 'right', '0');
    setImp(element, 'bottom', '0');
    setImp(element, 'width', '100vw');
    setImp(element, 'height', '100vh');
    setImp(element, 'max-width', 'none');
    setImp(element, 'max-height', 'none');
    setImp(element, 'margin', '0');
    setImp(element, 'padding', '0');
    setImp(element, 'border', '0');
    setImp(element, 'z-index', '2147483647');
    setImp(element, 'background', '#000');
    setImp(element, 'transform', 'none');
  }

  function maintain() {
    selectYangshipinChannel();

    var video = pickVideo();
    var target = video || pickPlayerFrame();
    if (!target) return;

    if (waitingForReplacementVideo && video && video !== videoBeforeChannelSwitch) {
      waitingForReplacementVideo = false;
      videoBeforeChannelSwitch = null;
      send('diagnostic', 'Yangshipin replacement video detected');
    }

    setImp(document.documentElement, 'overflow', 'hidden');
    setImp(document.documentElement, 'background', '#000');
    if (document.body) {
      setImp(document.body, 'overflow', 'hidden');
      setImp(document.body, 'background', '#000');
    }
    prepareAncestors(target);
    hideSiblings();
    styleFullscreen(target);

    if (!video) return;
    setImp(video, 'object-fit', 'contain');

    if (configuredVideo !== video) {
      configuredVideo = video;
      playingNotified = false;
      if (selectBlueLightQuality()) {
        send('diagnostic', 'Yangshipin quality selected: 蓝光 1080P');
      }
    } else {
      // 画质控件可能晚于 video 节点出现；未选中时重复检查是幂等的。
      selectBlueLightQuality();
    }

    video.autoplay = true;
    video.setAttribute('playsinline', '');
    if (video.paused) {
      var promise = video.play();
      if (promise && promise.catch) promise.catch(function () {});
    }
    try {
      video.muted = false;
      video.volume = 1;
    } catch (e) {}

    if (!waitingForReplacementVideo && !playingNotified && !video.paused && !video.ended && video.readyState >= 3 && video.videoWidth > 0 && video.currentTime > 0) {
      playingNotified = true;
      sendPort('playing', { requestId: activePlaybackRequestId });
      var rect = video.getBoundingClientRect();
      var activeQuality = document.querySelector('.bei-list .item.active');
      send('diagnostic', 'Gecko video playing: ' + video.videoWidth + 'x' + video.videoHeight + ', rect=' + Math.round(rect.width) + 'x' + Math.round(rect.height) + ', viewport=' + window.innerWidth + 'x' + window.innerHeight + ', volume=' + Math.round(video.volume * 100) + '%, muted=' + video.muted + ', quality=' + (activeQuality ? activeQuality.textContent.trim() : 'unknown') + ', url=' + location.href);
    }
    video.setAttribute(MARK, '1');
  }

  maintain();
  setInterval(maintain, 500);
})();
