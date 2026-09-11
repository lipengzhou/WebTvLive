'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const root = path.resolve(__dirname, '..');
const source = fs.readFileSync(
  path.join(root, 'app/src/main/assets/webextension/player_adapter_core.js'),
  'utf8'
);
const context = { window: {} };
vm.runInNewContext(source, context);
const core = context.window.WebTvLivePlayerAdapterCore;

test('enhancementProfile returns null for original and unknown levels', () => {
  assert.equal(core.enhancementProfile('original'), null);
  assert.equal(core.enhancementProfile('bogus'), null);
});

test('enhancementProfile ramps contrast/saturation/brightness by level', () => {
  const light = core.enhancementProfile('light');
  assert.equal(light.contrast, 1.03);
  assert.equal(light.saturation, 1.02);
  assert.equal(light.brightness, 1.0);
  const standard = core.enhancementProfile('standard');
  const strong = core.enhancementProfile('strong');
  assert.ok(strong.contrast > standard.contrast);
  assert.ok(strong.saturation > standard.saturation);
});

test('normalizeEnhancementLevel keeps known levels and clamps the rest', () => {
  assert.equal(core.normalizeEnhancementLevel('strong'), 'strong');
  assert.equal(core.normalizeEnhancementLevel('original'), 'original');
  assert.equal(core.normalizeEnhancementLevel('nope'), 'original');
  assert.equal(core.normalizeEnhancementLevel(undefined), 'original');
  assert.equal(core.normalizeEnhancementLevel(null), 'original');
});

test('videoScore prefers a replacement candidate during a channel switch', () => {
  const tinyReplacement = core.videoScore({
    isReplacementCandidate: true,
    width: 1,
    height: 1,
    readyState: 0,
    paused: true,
  });
  const largePlayingIncumbent = core.videoScore({
    isReplacementCandidate: false,
    width: 1920,
    height: 1080,
    readyState: 4,
    paused: false,
  });
  assert.ok(tinyReplacement > largePlayingIncumbent);
});

test('videoScore rewards larger, ready, playing videos otherwise', () => {
  const readyPlaying = core.videoScore({
    isReplacementCandidate: false,
    width: 1280,
    height: 720,
    readyState: 4,
    paused: false,
  });
  const idleSmall = core.videoScore({
    isReplacementCandidate: false,
    width: 320,
    height: 180,
    readyState: 0,
    paused: true,
  });
  assert.ok(readyPlaying > idleSmall);
});

test('playerFrameScore boosts player-like iframe sources', () => {
  const playerFrame = core.playerFrameScore(200, 200, 'https://www.yangshipin.cn/player/x');
  const genericFrame = core.playerFrameScore(800, 800, 'https://ads.example.com/banner');
  assert.ok(playerFrame > genericFrame);
});
