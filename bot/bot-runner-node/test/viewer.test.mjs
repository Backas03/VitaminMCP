import assert from 'node:assert/strict';
import test from 'node:test';

import { resolveViewerVersion, viewerPackageSpecifier } from '../src/viewer.mjs';

test('viewer defaults to the declared sibling sidecar', () => {
  assert.match(
    viewerPackageSpecifier(),
    /bot-runner-viewer\/node_modules\/prismarine-viewer\/index\.js$/,
  );
});

test('viewer maps a newer patch to its latest supported major version', () => {
  assert.equal(
    resolveViewerVersion('1.21.8', ['1.20.1', '1.21.1', '1.21.4']),
    '1.21.4',
  );
});

test('viewer rejects a missing version before opening a broken browser page', () => {
  assert.throws(
    () => resolveViewerVersion(null, ['1.21.4']),
    /did not provide a Minecraft version/,
  );
});

test('viewer rejects an unsupported major version with the supported list', () => {
  assert.throws(
    () => resolveViewerVersion('1.22.0', ['1.21.4']),
    /does not support Minecraft 1\.22\.0; supported versions: 1\.21\.4/,
  );
});
