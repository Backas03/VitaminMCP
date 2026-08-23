import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import { trimViewer, viewerMinecraftDataVersions, viewerVersions } from '../../bot-runner-viewer/scripts/slim-viewer.mjs';

test('viewer keep-set follows its supported version table and literal data references', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'vitaminmcp-viewer-'));
  try {
    const viewer = path.join(root, 'viewer');
    const data = path.join(root, 'minecraft-data');
    fixture(viewer, data);

    assert.deepEqual(viewerVersions(viewer, '1.21.8'), ['1.21.1', '1.21.4']);
    assert.deepEqual(
      viewerMinecraftDataVersions(viewer, '1.21.8'),
      ['1.21.1', '1.21.4', '1.16.2'],
    );
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('trimming removes old textures, block states and data while preserving borrowed data', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'vitaminmcp-viewer-trim-'));
  try {
    const viewer = path.join(root, 'viewer');
    const data = path.join(root, 'minecraft-data');
    fixture(viewer, data);

    const result = trimViewer(viewer, data, '1.21.8');

    assert.deepEqual(result.versions, ['1.21.1', '1.21.4']);
    assert.ok(result.viewerBytes > 0);
    assert.ok(result.minecraftDataBytes > 0);
    assert.ok(fs.existsSync(path.join(viewer, 'public', 'textures', '1.21.1.png')));
    assert.ok(fs.existsSync(path.join(viewer, 'public', 'textures', '1.21.4')));
    assert.ok(!fs.existsSync(path.join(viewer, 'public', 'textures', '1.20.1.png')));
    assert.ok(!fs.existsSync(path.join(viewer, 'public', 'blocksStates', '1.20.1.json')));
    assert.match(
      fs.readFileSync(path.join(viewer, 'viewer', 'lib', 'version.js'), 'utf8'),
      /const supportedVersions = \["1\.21\.1","1\.21\.4"\]/,
    );

    assert.ok(fs.existsSync(path.join(data, 'minecraft-data', 'data', 'pc', '1.16.2', 'blocks.json')));
    assert.ok(fs.existsSync(path.join(data, 'minecraft-data', 'data', 'pc', '1.21.4', 'blocks.json')));
    assert.ok(fs.existsSync(path.join(data, 'minecraft-data', 'data', 'pc', 'common', 'blocks.json')));
    assert.ok(!fs.existsSync(path.join(data, 'minecraft-data', 'data', 'pc', '1.20.1', 'blocks.json')));
    assert.ok(!fs.existsSync(path.join(data, 'minecraft-data', 'data', 'bedrock', '1.21.1', 'blocks.json')));
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

function fixture(viewer, data) {
  write(viewer, 'viewer/lib/version.js',
    "const supportedVersions = ['1.20.1', '1.21.1', '1.21.4']\nmodule.exports = { supportedVersions }\n");
  write(viewer, 'viewer/lib/models.js', "require('minecraft-data')('1.16.2')\n");
  write(viewer, 'viewer/lib/worldrenderer.js', 'load(`textures/${this.version}.png`)\nload(`blocksStates/${this.version}.json`)\n');
  for (const version of ['1.20.1', '1.21.1', '1.21.4']) {
    write(viewer, `public/textures/${version}.png`, 'texture');
    write(viewer, `public/textures/${version}/blocks.json`, 'texture');
    write(viewer, `public/blocksStates/${version}.json`, 'states');
  }
  write(viewer, 'public/textures/missing_texture.png', 'keep');

  write(data, 'data.js', [
    "  'pc': {",
    "    '1.16.2': { blocks: require('./minecraft-data/data/pc/1.16.2/blocks.json') },",
    "    '1.20.1': { blocks: require('./minecraft-data/data/pc/1.20.1/blocks.json') },",
    "    '1.21.1': { blocks: require('./minecraft-data/data/pc/1.21.1/blocks.json') },",
    "    '1.21.4': { blocks: require('./minecraft-data/data/pc/1.21.4/blocks.json') },",
    "  'bedrock': {",
    "    '1.21.1': { blocks: require('./minecraft-data/data/bedrock/1.21.1/blocks.json') },",
  ].join('\n'));
  for (const version of ['1.16.2', '1.20.1', '1.21.1', '1.21.4']) {
    write(data, `minecraft-data/data/pc/${version}/blocks.json`, 'data');
  }
  write(data, 'minecraft-data/data/pc/common/blocks.json', 'common');
  write(data, 'minecraft-data/data/bedrock/1.21.1/blocks.json', 'bedrock');
}

function write(root, relative, contents) {
  const file = path.join(root, relative);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, contents);
}
