(function (root) {
  'use strict';

  var ENHANCEMENT_LEVELS = { original: 1, light: 1, standard: 1, strong: 1 };

  /** CSS color-function profile for a video enhancement level, or null for "original". */
  function enhancementProfile(level) {
    switch (level) {
      case 'light': return { contrast: 1.03, saturation: 1.02, brightness: 1.00 };
      case 'standard': return { contrast: 1.06, saturation: 1.03, brightness: 1.01 };
      case 'strong': return { contrast: 1.10, saturation: 1.05, brightness: 1.02 };
      default: return null;
    }
  }

  /** Clamp an arbitrary value to a known enhancement level, defaulting to "original". */
  function normalizeEnhancementLevel(level) {
    var value = String(level);
    return Object.prototype.hasOwnProperty.call(ENHANCEMENT_LEVELS, value) ? value : 'original';
  }

  /**
   * Score a candidate <video> when picking the active player. During a channel switch, a node
   * different from the previous player is strongly preferred so playback tracks the new stream.
   */
  function videoScore(opts) {
    var replacementBonus = opts.isReplacementCandidate ? 10000000 : 0;
    var area = opts.width * opts.height;
    var readyBonus = opts.readyState >= 2 ? 2000000 : 0;
    var playingBonus = opts.paused ? 0 : 1000000;
    return replacementBonus + area + readyBonus + playingBonus;
  }

  /** Score a candidate <iframe> when no <video> is available; player-ish sources win. */
  function playerFrameScore(width, height, src) {
    var sourceBonus = /(player|live|cntv|cctv|yangshipin)/.test(String(src).toLowerCase())
      ? 10000000
      : 0;
    return width * height + sourceBonus;
  }

  root.WebTvLivePlayerAdapterCore = {
    enhancementProfile: enhancementProfile,
    normalizeEnhancementLevel: normalizeEnhancementLevel,
    videoScore: videoScore,
    playerFrameScore: playerFrameScore,
  };
})(typeof window !== 'undefined' ? window : this);
