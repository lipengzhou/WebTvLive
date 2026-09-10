'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const root = path.resolve(__dirname, '..');
const source = fs.readFileSync(
  path.join(root, 'app/src/main/assets/webextension/protocol.js'),
  'utf8'
);
const context = { window: {} };
vm.runInNewContext(source, context);
const protocol = context.window.WebTvLiveProtocol;
const fixture = JSON.parse(fs.readFileSync(
  path.join(root, 'app/src/test/resources/browser_protocol_fixture.json'),
  'utf8'
));

test('creates versioned page events', () => {
  assert.deepEqual(
    JSON.parse(JSON.stringify(protocol.message('playing', { requestId: 9 }))),
    { protocolVersion: 1, type: 'playing', requestId: 9 }
  );
});

test('accepts only complete versioned native commands', () => {
  assert.equal(protocol.version, fixture.version);
  assert.equal(protocol.isCommand(fixture.validCommand), true);
  assert.equal(protocol.isCommand({
    protocolVersion: 1,
    type: 'setVideoEnhancement',
    level: 'standard',
  }), true);
  assert.equal(protocol.isCommand({
    protocolVersion: 2,
    type: 'switchChannel',
    channel: 'CCTV13',
    pid: '600001811',
    requestId: 9,
  }), false);
  assert.equal(protocol.isCommand({
    protocolVersion: 1,
    type: 'switchChannel',
    channel: 'CCTV13',
    pid: '600001811',
    requestId: 0,
  }), false);
});
