(function () {
  var MARK = 'data-webtvlive-fs';
  var KEEP = 'data-webtvlive-keep';
  var CLEARED = 'data-webtvlive-cleared';
  var CHANNEL_SELECTOR =
    '.tv-main-con-r-list-left-imga, .tv-main-con-r-list-left-imgb';
  var SELECTED_CHANNEL_SELECTOR =
    '.tv-main-con-r-list-left-imga.tvSelect, .tv-main-con-r-list-left-imgb.tvSelect';

  var playingNotified = false;
  var configuredVideo = null;
  var fullscreenTarget = null;
  var pendingChannel = '';
  var pendingPid = '';
  var nativePort = null;
  var videoBeforeChannelSwitch = null;
  var waitingForReplacementVideo = false;
  var pendingRequestId = 0;
  var activePlaybackRequestId = 0;
  var directChannelToVerify = '';
  var channelCache = Object.create(null);
  var channelCacheDirty = true;
  var managedAncestors = [];
  var managedKept = [];
  var managedHidden = [];
  var reconcileScheduled = false;
  var needsPlayerDiscovery = true;
  var needsChannelWork = true;
  var needsStyleRepair = false;
  var needsStructureRebuild = false;
  var enforcingVideoPolicy = false;
  var lastReportedBlockedRequestCount = -1;
  var videoEnhancementLevel = 'original';

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

  /** 把后台请求过滤统计转发到 Android 日志，便于确认规则生效和排查播放回归。 */
  function reportResourceFilterStats() {
    try {
      var result = browser.runtime.sendMessage({ type: 'getResourceFilterStats' });
      if (!result || !result.then) return;
      result.then(function (stats) {
        if (!stats || !stats.enabled || stats.total === lastReportedBlockedRequestCount) return;
        lastReportedBlockedRequestCount = stats.total;
        var counts = stats.counts || {};
        send(
          'diagnostic',
          'Resource filter blocked ' + stats.total + ' requests' +
            ' (artwork=' + (counts.catalogArtwork || 0) +
            ', qr/footer=' + (counts.qrAndFooterArtwork || 0) +
            ', prefetch=' + (counts.routePrefetch || 0) + ')'
        );
      }).catch(function () {});
    } catch (e) {}
  }

  function scheduleReconcile(playerDiscovery, channelWork, styleRepair, structureRebuild) {
    needsPlayerDiscovery = needsPlayerDiscovery || !!playerDiscovery;
    needsChannelWork = needsChannelWork || !!channelWork;
    needsStyleRepair = needsStyleRepair || !!styleRepair;
    needsStructureRebuild = needsStructureRebuild || !!structureRebuild;
    if (reconcileScheduled) return;
    reconcileScheduled = true;
    requestAnimationFrame(reconcile);
  }

  /** 建立 App <-> 页面持久连接，通过 Port 接收页内换台请求。 */
  function connectNativePort() {
    try {
      nativePort = browser.runtime.connectNative('webtvlive');
      nativePort.onMessage.addListener(function (message) {
        if (!message) return;
        if (message.type === 'setVideoEnhancement') {
          setVideoEnhancement(message.level);
          return;
        }
        if (message.type !== 'switchChannel' || !message.channel) return;
        pendingChannel = String(message.channel);
        pendingPid = String(message.pid || '');
        pendingRequestId = Number(message.requestId) || 0;
        if (!activateDirectChannel()) selectYangshipinChannel();
        scheduleReconcile(true, true, false, false);
      });
      nativePort.onDisconnect.addListener(function () {
        nativePort = null;
      });
      sendPort('ready');
    } catch (e) {
      send('diagnostic', 'Unable to connect native port: ' + e);
    }
  }

  function getYangshipinChannelName(element) {
    var source = element.querySelector('span') || element;
    var text = '';
    for (var i = 0; i < source.childNodes.length; i++) {
      var child = source.childNodes[i];
      if (child.nodeType === Node.TEXT_NODE) {
        text += child.textContent || '';
      } else if (
        child.nodeType === Node.ELEMENT_NODE &&
        !child.classList.contains('tv-main-con-r-list-left-tag')
      ) {
        text += child.textContent || '';
      }
    }
    return text.trim();
  }

  function rebuildChannelCache() {
    channelCache = Object.create(null);
    var items = document.querySelectorAll(CHANNEL_SELECTOR);
    for (var i = 0; i < items.length; i++) {
      var name = getYangshipinChannelName(items[i]);
      if (name) channelCache[name] = items[i];
    }
    channelCacheDirty = false;
  }

  function findChannelElement(name) {
    if (channelCacheDirty) rebuildChannelCache();
    var item = channelCache[name];
    if (item && item.isConnected) return item;

    // 页面可能复用同一批 DOM 更新频道名；一次请求最多在这里补建一次缓存。
    channelCacheDirty = true;
    rebuildChannelCache();
    item = channelCache[name];
    return item && item.isConnected ? item : null;
  }

  /** 首次启动已通过官网 ?pid=... 直达目标频道，先绑定本次 requestId。 */
  function activateDirectChannel() {
    if (!pendingPid || location.hostname.indexOf('yangshipin.cn') < 0) return false;
    var currentPid = '';
    try { currentPid = new URLSearchParams(location.search).get('pid') || ''; } catch (e) {}
    if (currentPid !== pendingPid) return false;

    directChannelToVerify = pendingChannel;
    pendingChannel = '';
    pendingPid = '';
    activePlaybackRequestId = pendingRequestId;
    pendingRequestId = 0;
    playingNotified = false;
    videoBeforeChannelSwitch = null;
    waitingForReplacementVideo = false;
    send('diagnostic', 'Yangshipin direct channel requested: ' + directChannelToVerify);
    return true;
  }

  /** pid 可能被官网调整；频道列表出现后核对，不匹配则回退到按名称点击。 */
  function verifyDirectChannel() {
    if (!directChannelToVerify) return true;
    var selected = document.querySelector(SELECTED_CHANNEL_SELECTOR);
    if (!selected) return false;

    var expectedChannel = directChannelToVerify;
    directChannelToVerify = '';
    if (getYangshipinChannelName(selected) === expectedChannel) {
      sendPort('channelSelected', { channel: expectedChannel });
      send('diagnostic', 'Yangshipin direct channel verified: ' + expectedChannel);
      maybeNotifyPlaying();
      return true;
    }

    pendingChannel = expectedChannel;
    pendingRequestId = activePlaybackRequestId;
    send('diagnostic', 'Yangshipin pid mismatch; falling back to channel click: ' + expectedChannel);
    return selectYangshipinChannel();
  }

  function selectYangshipinChannel() {
    if (!pendingChannel || location.hostname.indexOf('yangshipin.cn') < 0) return false;
    var item = findChannelElement(pendingChannel);
    if (!item) return false;

    var selectedChannel = pendingChannel;
    pendingChannel = '';
    pendingPid = '';
    directChannelToVerify = '';
    activePlaybackRequestId = pendingRequestId;
    pendingRequestId = 0;
    playingNotified = false;

    if (item.classList.contains('tvSelect')) {
      videoBeforeChannelSwitch = null;
      waitingForReplacementVideo = false;
    } else {
      videoBeforeChannelSwitch = configuredVideo && configuredVideo.isConnected
        ? configuredVideo
        : pickVideo();
      waitingForReplacementVideo = videoBeforeChannelSwitch !== null;
      item.click();
    }
    sendPort('channelSelected', { channel: selectedChannel });
    send('diagnostic', 'Yangshipin in-page switch: ' + selectedChannel);
    return true;
  }

  function pickVideo() {
    var videos = document.querySelectorAll('video');
    if (!videos.length) return null;
    if (videos.length === 1) return videos[0];

    var best = null;
    var bestScore = -1;
    for (var i = 0; i < videos.length; i++) {
      var video = videos[i];
      // 换台期间只要出现不同于旧播放器的新节点，就优先选择新节点。
      var replacementBonus = waitingForReplacementVideo && video !== videoBeforeChannelSwitch
        ? 10000000
        : 0;
      var rect = video.getBoundingClientRect();
      var score = replacementBonus + rect.width * rect.height +
        (video.readyState >= 2 ? 2000000 : 0) + (!video.paused ? 1000000 : 0);
      if (score > bestScore) {
        bestScore = score;
        best = video;
      }
    }
    return best;
  }

  function pickPlayerFrame() {
    var frames = document.querySelectorAll('iframe');
    if (!frames.length) return null;
    if (frames.length === 1) return frames[0];

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

  function setImp(element, key, value) {
    try { element.style.setProperty(key, value, 'important'); } catch (e) {}
  }

  function enhancementProfile(level) {
    switch (level) {
      case 'light': return { contrast: 1.03, saturation: 1.02, brightness: 1.00 };
      case 'standard': return { contrast: 1.06, saturation: 1.03, brightness: 1.01 };
      case 'strong': return { contrast: 1.10, saturation: 1.05, brightness: 1.02 };
      default: return null;
    }
  }

  /**
   * 通过 GeckoView 可稳定走 GPU 合成的 CSS 色彩函数提升主观清晰度。
   * Android GeckoView 的硬件解码视频叠加 SVG 卷积会黑屏，因此这里不使用 feConvolveMatrix。
   */
  function applyVideoEnhancement(video) {
    if (!video) return;
    var previousLevel = video.__webTvAppliedEnhancement;
    if (video.__webTvOriginalFilter === undefined) {
      video.__webTvOriginalFilter = video.style.getPropertyValue('filter');
      video.__webTvOriginalFilterPriority = video.style.getPropertyPriority('filter');
    }

    var profile = enhancementProfile(videoEnhancementLevel);
    if (!profile) {
      if (video.__webTvOriginalFilter) {
        video.style.setProperty(
          'filter',
          video.__webTvOriginalFilter,
          video.__webTvOriginalFilterPriority || ''
        );
      } else {
        video.style.removeProperty('filter');
      }
      video.__webTvAppliedEnhancement = videoEnhancementLevel;
      if (previousLevel !== videoEnhancementLevel) {
        send('diagnostic', 'Video filter updated: original');
      }
      return;
    }

    setImp(
      video,
      'filter',
      'contrast(' + profile.contrast + ') ' +
        'saturate(' + profile.saturation + ') ' +
        'brightness(' + profile.brightness + ')'
    );
    video.__webTvAppliedEnhancement = videoEnhancementLevel;
    if (previousLevel !== videoEnhancementLevel) {
      send(
        'diagnostic',
        'Video filter updated: ' + videoEnhancementLevel +
          ', filter=' + video.style.getPropertyValue('filter')
      );
    }
  }

  function setVideoEnhancement(level) {
    videoEnhancementLevel = /^(original|light|standard|strong)$/.test(String(level))
      ? String(level)
      : 'original';
    if (configuredVideo && configuredVideo.isConnected) {
      applyVideoEnhancement(configuredVideo);
    }
    send('diagnostic', 'Video enhancement applied: ' + videoEnhancementLevel);
  }

  function styleFullscreen(element) {
    setImp(element, 'position', 'fixed');
    setImp(element, 'left', '0px');
    setImp(element, 'top', '0px');
    setImp(element, 'right', '0px');
    setImp(element, 'bottom', '0px');
    setImp(element, 'width', '100vw');
    setImp(element, 'height', '100vh');
    setImp(element, 'max-width', 'none');
    setImp(element, 'max-height', 'none');
    setImp(element, 'margin', '0px');
    setImp(element, 'padding', '0px');
    setImp(element, 'border', '0px');
    setImp(element, 'z-index', '2147483647');
    setImp(element, 'background', 'rgb(0, 0, 0)');
    setImp(element, 'transform', 'none');
  }

  function rebuildFullscreenState(target) {
    styleObserver.disconnect();
    var previous = document.querySelectorAll('[' + KEEP + ']');
    for (var i = 0; i < previous.length; i++) previous[i].removeAttribute(KEEP);

    managedAncestors = [];
    managedKept = [];
    var node = target;
    while (node && node !== document.documentElement) {
      node.setAttribute(KEEP, '1');
      managedKept.push(node);
      if (node !== target && node.nodeType === Node.ELEMENT_NODE) {
        managedAncestors.push(node);
      }
      node = node.parentElement;
    }
    var kept = document.querySelectorAll('[' + KEEP + ']');
    for (var k = 0; k < kept.length; k++) {
      var parent = kept[k].parentElement;
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
        } else if (!element.hasAttribute(CLEARED)) {
          element.setAttribute(CLEARED, '1');
        }
      }
    }
    managedHidden = Array.prototype.slice.call(
      document.querySelectorAll('[' + CLEARED + ']')
    );
    fullscreenTarget = target;
    applyManagedStyles();
  }

  /** 只修复已经锁定的少量节点，不再重新扫描整页。 */
  function applyManagedStyles() {
    if (!fullscreenTarget || !fullscreenTarget.isConnected) return;
    styleObserver.disconnect();

    setImp(document.documentElement, 'overflow', 'hidden');
    setImp(document.documentElement, 'background', 'rgb(0, 0, 0)');
    if (document.body) {
      setImp(document.body, 'overflow', 'hidden');
      setImp(document.body, 'background', 'rgb(0, 0, 0)');
    }
    for (var i = 0; i < managedAncestors.length; i++) {
      var ancestor = managedAncestors[i];
      if (!ancestor.isConnected) continue;
      var style = getComputedStyle(ancestor);
      if (style.transform !== 'none') setImp(ancestor, 'transform', 'none');
      if (style.filter !== 'none') setImp(ancestor, 'filter', 'none');
      if (style.perspective !== 'none') setImp(ancestor, 'perspective', 'none');
      setImp(ancestor, 'overflow', 'visible');
    }
    for (var k = 0; k < managedKept.length; k++) {
      if (managedKept[k].isConnected) setImp(managedKept[k], 'visibility', 'visible');
    }
    for (var j = 0; j < managedHidden.length; j++) {
      if (managedHidden[j].isConnected) setImp(managedHidden[j], 'display', 'none');
    }
    styleFullscreen(fullscreenTarget);
    if (configuredVideo && configuredVideo.isConnected) {
      setImp(configuredVideo, 'object-fit', 'contain');
      applyVideoEnhancement(configuredVideo);
    }
    observeManagedStyles();
  }

  function observeManagedStyles() {
    var nodes = [document.documentElement, document.body, fullscreenTarget]
      .concat(managedAncestors, managedKept, managedHidden);
    var unique = [];
    for (var i = 0; i < nodes.length; i++) {
      var node = nodes[i];
      if (!node || !node.isConnected || unique.indexOf(node) >= 0) continue;
      unique.push(node);
      styleObserver.observe(node, {
        attributes: true,
        attributeFilter: ['style', 'width', 'height'],
      });
    }
  }

  function applyVideoPolicy(video) {
    if (!video || enforcingVideoPolicy) return;
    enforcingVideoPolicy = true;
    try {
      video.autoplay = true;
      video.setAttribute('playsinline', '');
      if (video.paused && !waitingForReplacementVideo) {
        var promise = video.play();
        if (promise && promise.catch) promise.catch(function () {});
      }
      video.muted = false;
      video.volume = 1;
      applyVideoEnhancement(video);
    } catch (e) {}
    enforcingVideoPolicy = false;
  }

  function maybeNotifyPlaying() {
    var video = configuredVideo;
    if (
      !video || !video.isConnected || directChannelToVerify ||
      waitingForReplacementVideo || playingNotified || video.paused || video.ended ||
      video.readyState < 3 || video.videoWidth <= 0 || video.currentTime <= 0
    ) return;

    playingNotified = true;
    sendPort('playing', { requestId: activePlaybackRequestId });
    reportResourceFilterStats();
    var rect = video.getBoundingClientRect();
    var activeQuality = document.querySelector('.bei-list .item.active');
    send(
      'diagnostic',
      'Gecko video playing: ' + video.videoWidth + 'x' + video.videoHeight +
        ', rect=' + Math.round(rect.width) + 'x' + Math.round(rect.height) +
        ', viewport=' + window.innerWidth + 'x' + window.innerHeight +
        ', volume=' + Math.round(video.volume * 100) + '%, muted=' + video.muted +
        ', quality=' + (activeQuality ? activeQuality.textContent.trim() : 'auto') +
        ', url=' + location.href
    );
  }

  function onVideoPlaying(event) {
    if (event.currentTarget !== configuredVideo) return;
    if (waitingForReplacementVideo && configuredVideo === videoBeforeChannelSwitch) {
      waitingForReplacementVideo = false;
      videoBeforeChannelSwitch = null;
      send('diagnostic', 'Yangshipin reused video started playing');
    }
    applyVideoPolicy(configuredVideo);
    maybeNotifyPlaying();
  }

  function onVideoReady(event) {
    if (event.currentTarget !== configuredVideo) return;
    applyVideoPolicy(configuredVideo);
    maybeNotifyPlaying();
  }

  function onVideoReset(event) {
    if (event.currentTarget !== configuredVideo) return;
    playingNotified = false;
    scheduleReconcile(true, false, false, false);
  }

  function onVideoVolumeChange(event) {
    if (event.currentTarget !== configuredVideo || enforcingVideoPolicy) return;
    if (configuredVideo.muted || configuredVideo.volume !== 1) {
      applyVideoPolicy(configuredVideo);
    }
  }

  function onVideoPause(event) {
    if (event.currentTarget !== configuredVideo || waitingForReplacementVideo) return;
    applyVideoPolicy(configuredVideo);
  }

  function unbindVideo() {
    if (!configuredVideo) return;
    configuredVideo.removeEventListener('playing', onVideoPlaying);
    configuredVideo.removeEventListener('loadedmetadata', onVideoReady);
    configuredVideo.removeEventListener('canplay', onVideoReady);
    configuredVideo.removeEventListener('timeupdate', maybeNotifyPlaying);
    configuredVideo.removeEventListener('emptied', onVideoReset);
    configuredVideo.removeEventListener('abort', onVideoReset);
    configuredVideo.removeEventListener('resize', onVideoReady);
    configuredVideo.removeEventListener('volumechange', onVideoVolumeChange);
    configuredVideo.removeEventListener('pause', onVideoPause);
    configuredVideo = null;
  }

  function bindVideo(video) {
    if (configuredVideo === video) return;
    unbindVideo();
    configuredVideo = video;
    playingNotified = false;
    video.addEventListener('playing', onVideoPlaying);
    video.addEventListener('loadedmetadata', onVideoReady);
    video.addEventListener('canplay', onVideoReady);
    video.addEventListener('timeupdate', maybeNotifyPlaying);
    video.addEventListener('emptied', onVideoReset);
    video.addEventListener('abort', onVideoReset);
    video.addEventListener('resize', onVideoReady);
    video.addEventListener('volumechange', onVideoVolumeChange);
    video.addEventListener('pause', onVideoPause);
    video.setAttribute(MARK, '1');
    applyVideoPolicy(video);
  }

  function reconcile() {
    reconcileScheduled = false;
    var discoverPlayer = needsPlayerDiscovery;
    var handleChannels = needsChannelWork;
    var repairStyles = needsStyleRepair;
    var rebuildStructure = needsStructureRebuild;
    needsPlayerDiscovery = false;
    needsChannelWork = false;
    needsStyleRepair = false;
    needsStructureRebuild = false;

    if (handleChannels) {
      verifyDirectChannel();
      selectYangshipinChannel();
    }

    if (discoverPlayer) {
      var video = pickVideo();
      var target = video || pickPlayerFrame();
      if (target && (target !== fullscreenTarget || !fullscreenTarget.isConnected || rebuildStructure)) {
        rebuildFullscreenState(target);
      } else if (target && repairStyles) {
        applyManagedStyles();
      }

      if (video) {
        if (waitingForReplacementVideo && video !== videoBeforeChannelSwitch) {
          waitingForReplacementVideo = false;
          videoBeforeChannelSwitch = null;
          send('diagnostic', 'Yangshipin replacement video detected');
        }
        bindVideo(video);
        applyVideoPolicy(video);
        maybeNotifyPlaying();
      } else if (configuredVideo && !configuredVideo.isConnected) {
        unbindVideo();
      }
    } else if (repairStyles) {
      applyManagedStyles();
    }
  }

  function nodeMatchesOrContains(node, selector) {
    if (!node || node.nodeType !== Node.ELEMENT_NODE) return false;
    try { return node.matches(selector) || !!node.querySelector(selector); } catch (e) { return false; }
  }

  function nodeContainsCurrentTarget(node) {
    if (!node || node.nodeType !== Node.ELEMENT_NODE || !fullscreenTarget) return false;
    return node === fullscreenTarget || node.contains(fullscreenTarget);
  }

  var domObserver = new MutationObserver(function (mutations) {
    var discoverPlayer = false;
    var handleChannels = false;
    var repairStyles = false;
    var rebuildStructure = false;

    for (var i = 0; i < mutations.length; i++) {
      var mutation = mutations[i];
      if (mutation.type === 'attributes') {
        var target = mutation.target;
        if (target.matches && target.matches(CHANNEL_SELECTOR)) handleChannels = true;
        if (managedKept.indexOf(target) >= 0) repairStyles = true;
        continue;
      }
      if (mutation.type === 'characterData') {
        var parent = mutation.target.parentElement;
        if (parent && parent.closest(CHANNEL_SELECTOR)) {
          channelCacheDirty = true;
          handleChannels = true;
        }
        continue;
      }

      if (managedKept.indexOf(mutation.target) >= 0) {
        repairStyles = true;
        rebuildStructure = true;
      }
      for (var j = 0; j < mutation.addedNodes.length; j++) {
        var added = mutation.addedNodes[j];
        if (nodeMatchesOrContains(added, 'video, iframe')) discoverPlayer = true;
        if (nodeMatchesOrContains(added, CHANNEL_SELECTOR)) {
          channelCacheDirty = true;
          handleChannels = true;
        }
      }
      for (var k = 0; k < mutation.removedNodes.length; k++) {
        var removed = mutation.removedNodes[k];
        if (nodeContainsCurrentTarget(removed) || nodeMatchesOrContains(removed, 'video, iframe')) {
          discoverPlayer = true;
        }
        if (nodeMatchesOrContains(removed, CHANNEL_SELECTOR)) {
          channelCacheDirty = true;
          handleChannels = true;
        }
      }
    }
    if (discoverPlayer || handleChannels || repairStyles) {
      scheduleReconcile(
        discoverPlayer || rebuildStructure,
        handleChannels,
        repairStyles,
        rebuildStructure
      );
    }
  });

  var styleObserver = new MutationObserver(function () {
    scheduleReconcile(false, false, true, false);
  });

  send('diagnostic', 'Gecko event-driven adapter injected: ' + location.href);
  connectNativePort();
  setTimeout(reportResourceFilterStats, 5000);
  domObserver.observe(document.documentElement, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ['class'],
    characterData: true,
  });
  scheduleReconcile(true, true, false, false);
})();
