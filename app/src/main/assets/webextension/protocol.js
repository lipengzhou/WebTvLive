(function (root) {
  'use strict';

  var VERSION = 1;

  function message(type, extra) {
    var payload = { protocolVersion: VERSION, type: type };
    if (extra) {
      for (var key in extra) {
        if (Object.prototype.hasOwnProperty.call(extra, key)) payload[key] = extra[key];
      }
    }
    return payload;
  }

  function isCommand(payload) {
    if (!payload || payload.protocolVersion !== VERSION || typeof payload.type !== 'string') {
      return false;
    }
    if (payload.type === 'setVideoEnhancement') {
      return /^(original|light|standard|strong)$/.test(String(payload.level));
    }
    if (payload.type === 'switchChannel') {
      return typeof payload.channel === 'string' && payload.channel.length > 0 &&
        typeof payload.pid === 'string' && payload.pid.length > 0 &&
        typeof payload.requestId === 'number' && payload.requestId > 0;
    }
    return false;
  }

  root.WebTvLiveProtocol = {
    version: VERSION,
    message: message,
    isCommand: isCommand,
  };
})(typeof window !== 'undefined' ? window : this);
