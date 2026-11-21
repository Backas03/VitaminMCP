import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import { VERSION } from '../src/release.mjs';

test('the runner reports the release it ships with', async () => {
  const conventions = await readFile(
    new URL('../../../build-logic/src/main/kotlin/vitaminmcp.java-conventions.gradle.kts', import.meta.url),
    'utf8',
  );
  const declared = conventions.match(/^version\s*=\s*"([^"]+)"/m)?.[1];
  assert.ok(declared, 'build-logic declares no version');
  assert.equal(VERSION, declared, 'run: node npm/scripts/stamp-checksums.mjs --sync');
});
