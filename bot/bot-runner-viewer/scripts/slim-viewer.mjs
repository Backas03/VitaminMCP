/**
 * Trims the optional prismarine-viewer sidecar to the Minecraft line this project supports.
 *
 * The viewer does not name its assets in a manifest. Its own version table is the source of truth:
 * WorldRenderer requests `textures/<version>.png` and `blocksStates/<version>.json`, while the
 * viewer's model code has one literal minecraft-data request for the tint table from 1.16.2.
 * Keeping those references, rather than matching directory names by guess, is what makes a
 * version update fail at build time instead of at the first world render.
 */

import fs from 'node:fs';
import path from 'node:path';

import { keptFilesForVersions } from '../../bot-runner-node/scripts/slim-minecraft-data.mjs';

const VERSION_TABLE = /const supportedVersions = \[([^\]]*)\]/s;
const VERSION_LITERAL = /['"]([^'"]+)['"]/g;
const VERSION_NAME = /^\d+\.\d+(?:\.\d+)?$/;

/** The viewer data entries that can render the project's supported major/minor line. */
export function viewerVersions(viewerRoot, floor) {
  const source = fs.readFileSync(path.join(viewerRoot, 'viewer', 'lib', 'version.js'), 'utf8');
  const table = source.match(VERSION_TABLE);
  if (!table) {
    throw new Error('Could not find prismarine-viewer supportedVersions table.');
  }

  const line = floor.split('.').slice(0, 2).join('.');
  const versions = [...table[1].matchAll(VERSION_LITERAL)]
    .map((match) => match[1])
    .filter((version) => version.split('.').slice(0, 2).join('.') === line);
  if (versions.length === 0) {
    throw new Error(`prismarine-viewer has no data for supported line ${line}.`);
  }
  return versions;
}

/** Viewer source literals that ask minecraft-data for a specific version. */
export function viewerMinecraftDataVersions(viewerRoot, floor) {
  const versions = new Set(viewerVersions(viewerRoot, floor));
  const viewerSource = path.join(viewerRoot, 'viewer');

  for (const file of filesUnder(viewerSource)) {
    if (!file.endsWith('.js')) continue;
    const source = fs.readFileSync(file, 'utf8');
    for (const match of source.matchAll(/minecraft-data[\s'"\)]*\(\s*['"]([^'"]+)['"]/g)) {
      versions.add(match[1]);
    }
  }
  return [...versions];
}

/** Removes unreferenced viewer assets and minecraft-data JSON files in a staging tree. */
export function trimViewer(viewerRoot, minecraftDataRoot, floor) {
  const versions = viewerVersions(viewerRoot, floor);
  const dataVersions = viewerMinecraftDataVersions(viewerRoot, floor);
  const removed = { viewerBytes: 0, minecraftDataBytes: 0, viewerFiles: 0, minecraftDataFiles: 0 };

  removeViewerAssets(viewerRoot, versions, removed);
  patchVersionTable(viewerRoot, versions);
  removeMinecraftData(minecraftDataRoot, dataVersions, removed);

  return { versions, dataVersions, ...removed };
}

function removeViewerAssets(viewerRoot, versions, removed) {
  const keep = new Set(versions);
  const textures = path.join(viewerRoot, 'public', 'textures');
  for (const entry of fs.readdirSync(textures, { withFileTypes: true })) {
    const version = entry.isDirectory() ? entry.name : path.basename(entry.name, '.png');
    if (!VERSION_NAME.test(version) || keep.has(version)) continue;
    const target = path.join(textures, entry.name);
    removed.viewerBytes += sizeOf(target);
    removed.viewerFiles += entry.isDirectory() ? filesUnder(target).length : 1;
    fs.rmSync(target, { recursive: true, force: true });
  }

  const states = path.join(viewerRoot, 'public', 'blocksStates');
  for (const entry of fs.readdirSync(states, { withFileTypes: true })) {
    const version = path.basename(entry.name, '.json');
    if (!entry.isFile() || !VERSION_NAME.test(version) || keep.has(version)) continue;
    const target = path.join(states, entry.name);
    removed.viewerBytes += sizeOf(target);
    removed.viewerFiles++;
    fs.rmSync(target, { force: true });
  }
}

function patchVersionTable(viewerRoot, versions) {
  const file = path.join(viewerRoot, 'viewer', 'lib', 'version.js');
  const source = fs.readFileSync(file, 'utf8');
  const patched = source.replace(VERSION_TABLE, `const supportedVersions = ${JSON.stringify(versions)}`);
  if (patched === source) throw new Error('Could not rewrite prismarine-viewer version table.');
  fs.writeFileSync(file, patched);
}

function removeMinecraftData(minecraftDataRoot, versions, removed) {
  const dataRoot = path.join(minecraftDataRoot, 'minecraft-data', 'data');
  const keep = keptFilesForVersions(minecraftDataRoot, versions);

  for (const file of filesUnder(dataRoot)) {
    if (isCommonData(dataRoot, file) || keep.has(path.resolve(file))) {
      continue;
    }
    removed.minecraftDataBytes += sizeOf(file);
    removed.minecraftDataFiles++;
    fs.rmSync(file, { force: true });
  }
}

function isCommonData(dataRoot, file) {
  const relative = path.relative(dataRoot, file).split(path.sep);
  return relative.length > 1 && (relative[1] === 'common');
}

function filesUnder(root) {
  if (!fs.existsSync(root)) return [];
  const files = [];
  for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
    const target = path.join(root, entry.name);
    if (entry.isDirectory()) files.push(...filesUnder(target));
    else files.push(target);
  }
  return files;
}

function sizeOf(target) {
  return fs.statSync(target).isDirectory()
    ? filesUnder(target).reduce((total, file) => total + fs.statSync(file).size, 0)
    : fs.statSync(target).size;
}
