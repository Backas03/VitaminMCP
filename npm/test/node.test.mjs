import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import fs from 'node:fs/promises';
import test from 'node:test';
import os from 'node:os';
import path from 'node:path';

import { checkNode, runnerAssetName } from '../lib/node.mjs';
import { verifyFileHash } from '../lib/jars.mjs';

test('maps every supported platform to its release asset', () => {
  assert.equal(runnerAssetName('win32', 'x64'), 'bot-runner-win-x64.exe');
  assert.equal(runnerAssetName('linux', 'x64'), 'bot-runner-linux-x64');
  assert.equal(runnerAssetName('linux', 'arm64'), 'bot-runner-linux-arm64');
  assert.equal(runnerAssetName('darwin', 'x64'), 'bot-runner-darwin-x64');
  assert.equal(runnerAssetName('darwin', 'arm64'), 'bot-runner-darwin-arm64');
});

test('rejects an unsupported platform instead of guessing an asset', () => {
  assert.throws(() => runnerAssetName('freebsd', 'x64'), /No VitaminMCP runner asset exists/);
});

test('accepts the current Node runtime', () => {
  const result = checkNode(process.execPath);
  assert.equal(result.ok, true);
});

test('refuses bytes that do not match the pinned hash', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'vitaminmcp-checksum-'));
  const file = path.join(directory, 'asset.bin');
  const expected = createHash('sha256').update('trusted').digest('hex');
  try {
    await fs.writeFile(file, 'tampered');
    assert.equal(await verifyFileHash(file, expected), false);
  } finally {
    await fs.rm(directory, { recursive: true, force: true });
  }
});
