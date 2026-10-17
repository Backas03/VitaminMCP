import assert from 'node:assert/strict';
import test from 'node:test';

import { protocolForVersion, selectProtocol } from '../src/version.mjs';

test('a version is resolved to its protocol, including one the runner cannot speak yet', () => {
  assert.equal(protocolForVersion('1.21.8'), 772);
  assert.equal(protocolForVersion('26.1.2'), 775);
  assert.equal(protocolForVersion('26.2'), 776);
  assert.equal(protocolForVersion('99.9'), undefined);
});

test('without a version from the agent the ping decides, as it always did', () => {
  assert.deepEqual(selectProtocol(772, undefined), { protocol: 772 });
});

test('a ping that agrees with the agent is not reported as a mismatch', () => {
  assert.deepEqual(selectProtocol(772, '1.21.7'), { protocol: 772 });
});

test('a ping answered in the ping request\'s own protocol loses to the agent', () => {
  // ViaBackwards on a 26.1 server: the ping is written in 1.21 (767) and answered in it.
  assert.deepEqual(selectProtocol(767, '26.1.2'), { protocol: 775, pinged: 767 });
});

test('a server newer than the runner\'s minecraft-data is refused by name', () => {
  assert.throws(() => selectProtocol(767, '99.9'), /Minecraft 99\.9.*does not know/);
});
