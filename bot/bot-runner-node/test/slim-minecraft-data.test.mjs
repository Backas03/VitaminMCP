import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { keptFiles } from '../scripts/slim-minecraft-data.mjs';
import { PING_VERSION } from '../src/version.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const packageRoot = path.join(root, 'node_modules', 'minecraft-data');
const dataRoot = path.join(packageRoot, 'minecraft-data', 'data');

/**
 * These guard a build-time prune whose failure mode is silence: drop a file a supported version
 * borrows and the runner still builds, still starts, and dies the first time a bot touches a
 * recipe. minecraft-data decides which version borrows what, and it changes that between
 * releases, so the assertions below are about the shape of the answer rather than a fixed list.
 */

const FLOOR = '1.21';

test('the keep-set covers every file the supported versions ask for', () => {
  const keep = keptFiles(packageRoot, FLOOR);

  assert.ok(keep.size > 100, `only ${keep.size} files kept, which is too few to be right`);
  for (const file of keep) {
    assert.ok(fs.existsSync(file), `${file} is in the keep-set but not on disk`);
  }
});

test('it keeps the older directories the 1.21 line borrows from', () => {
  const directories = new Set(
    [...keptFiles(packageRoot, FLOOR)].map((file) => path.basename(path.dirname(file))),
  );

  // Not a wish-list: 1.21.x reads recipes, tints and more out of these. A prune by directory name
  // would drop them, the build would still succeed, and the runner would fail in the field.
  assert.ok(directories.size > 1, 'only one directory kept, so cross-version borrowing was missed');
  for (const borrowed of [...directories].filter((name) => !name.startsWith(FLOOR))) {
    assert.ok(
      fs.existsSync(path.join(dataRoot, 'pc', borrowed)),
      `${borrowed} was kept but does not exist, so data.js is being parsed wrongly`,
    );
  }
});

test('it drops Bedrock and the versions below the floor', () => {
  const keep = keptFiles(packageRoot, FLOOR);

  for (const file of keep) {
    assert.ok(!file.includes(`${path.sep}bedrock${path.sep}`), `${file} is Bedrock data`);
  }

  const dropped = path.join(dataRoot, 'pc', '1.8', 'blocks.json');
  if (fs.existsSync(dropped)) {
    assert.ok(!keep.has(dropped), '1.8 block data is bundled, so the prune did nothing');
  }
});

test('the version the ping is written with is one the build keeps', () => {
  const kept = [...keptFiles(packageRoot, FLOOR)].some(
    (file) => path.basename(path.dirname(file)) === PING_VERSION,
  );

  // Otherwise every run dies at startup: the ping happens before anything knows what the server
  // speaks, so its data has to be in the bundle unconditionally.
  assert.ok(kept, `the ping uses ${PING_VERSION}, which this build does not bundle`);
});

test('a floor minecraft-data has never heard of fails the build', () => {
  assert.throws(() => keptFiles(packageRoot, '0.1'), /No minecraft-data entries matched/);
});
