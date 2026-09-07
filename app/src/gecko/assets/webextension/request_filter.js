(function () {
  'use strict';

  /*
   * 只拦截已由央视频电视页网络采样确认、且与直播播放无关的资源。
   *
   * 刻意不做“非白名单全部拒绝”：频道 API、播放器脚本、WASM、媒体、鉴权与
   * 登录资源默认全部放行。官网变更时，未知请求也会放行，优先保证能播放。
   */
  var RULES = [
    {
      id: 'catalogArtwork',
      urls: ['https://resources.yangshipin.cn/assets/oms/image/*'],
      types: ['image'],
    },
    {
      id: 'qrAndFooterArtwork',
      urls: [
        'https://sapi.yangshipin.cn/assets/2022/pcicon/qrcode/*',
        'https://sapi.yangshipin.cn/assets/2022/pcicon/0523/logo_bottom@2x.png*',
        'https://sapi.yangshipin.cn/assets/2022/pcicon/icon_bottom_*',
      ],
      types: ['image'],
    },
    {
      // 只取消路由 chunk 的推测性预取；电视页真正使用时会以 script/stylesheet 重新请求。
      id: 'routePrefetch',
      urls: [
        'https://www.yangshipin.cn/js/chunk-*',
        'https://www.yangshipin.cn/css/chunk-*',
      ],
      types: ['speculative'],
    },
  ];

  var counts = Object.create(null);
  var total = 0;

  RULES.forEach(function (rule) {
    counts[rule.id] = 0;
    var filter = { urls: rule.urls };
    if (rule.types) filter.types = rule.types;

    browser.webRequest.onBeforeRequest.addListener(
      function () {
        counts[rule.id] += 1;
        total += 1;
        return { cancel: true };
      },
      filter,
      ['blocking']
    );
  });

  browser.runtime.onMessage.addListener(function (message) {
    if (!message || message.type !== 'getResourceFilterStats') return undefined;
    return Promise.resolve({
      enabled: true,
      total: total,
      counts: Object.assign({}, counts),
    });
  });

  console.info('[WebTvLive] Conservative resource filter enabled');
})();
