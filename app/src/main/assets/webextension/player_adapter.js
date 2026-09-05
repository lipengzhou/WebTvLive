(function () {
  var MARK = 'data-webtvlive-fs';
  var KEEP = 'data-webtvlive-keep';
  var CLEARED = 'data-webtvlive-cleared';
  var playingNotified = false;

  function send(type, message) {
    try {
      browser.runtime.sendNativeMessage('webtvlive', { type: type, message: message || '' });
    } catch (e) {}
  }

  function setImp(el, key, value) {
    try { el.style.setProperty(key, value, 'important'); } catch (e) {}
  }

  send('diagnostic', 'Gecko adapter injected: ' + location.href);

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
    var video = pickVideo();
    var target = video || pickPlayerFrame();
    if (!target) return;

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

    if (!playingNotified && !video.paused && !video.ended && video.readyState >= 3 && video.videoWidth > 0 && video.currentTime > 0) {
      playingNotified = true;
      send('playing');
      var rect = video.getBoundingClientRect();
      send('diagnostic', 'Gecko video playing: ' + video.videoWidth + 'x' + video.videoHeight + ', rect=' + Math.round(rect.width) + 'x' + Math.round(rect.height) + ', viewport=' + window.innerWidth + 'x' + window.innerHeight + ', url=' + location.href);
    }
    video.setAttribute(MARK, '1');
  }

  maintain();
  setInterval(maintain, 1000);
})();
