import assert from 'node:assert/strict';
import test from 'node:test';

import { connectionOptions } from '../src/bots.mjs';
import { identity } from '../src/identity.mjs';

const botIdentity = identity('ReusableBot');

test('normal login does not require BungeeCord forwarding', () => {
  const options = connectionOptions('127.0.0.1', 25565, '1.21.8', botIdentity, null);

  assert.equal(options.fakeHost, undefined);
  assert.equal(options.username, 'ReusableBot');
});

test('clientIp explicitly opts into BungeeCord forwarding', () => {
  const options = connectionOptions('127.0.0.1', 25565, '1.21.8', botIdentity, '203.0.113.9');

  assert.equal(options.fakeHost.split('\0').length, 3);
  assert.equal(options.fakeHost.split('\0')[1], '203.0.113.9');
});
