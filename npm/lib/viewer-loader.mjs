/**
 * Lazy bridge from the npm launcher's pinned asset fetcher to the Node runner's viewer import.
 *
 * This module is imported only when `bot_view` asks for a world view. The normal runner startup
 * therefore never downloads or extracts the optional viewer archive.
 */

import { spawn } from 'node:child_process';
import fs from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

import {
  assetCacheDirectory,
  ensureAsset,
} from './jars.mjs';

const version = process.env.VITAMINMCP_VERSION;
if (!version) throw new Error('VITAMINMCP_VERSION is required to load the optional viewer asset.');
const asset = process.env.VITAMINMCP_VIEWER_ASSET;
if (!asset) {
  throw new Error(
    'No packaged world viewer asset exists for this platform. '
      + 'Set VITAMINMCP_VIEWER_PATH to a local prismarine-viewer sidecar.',
  );
}

const archive = await ensureAsset(version, asset, {
  log: (message) => process.stderr.write(`[vitaminmcp] ${message}\n`),
});
const extractionRoot = path.join(assetCacheDirectory(version), 'viewer');
const entry = path.join(extractionRoot, 'package', 'node_modules', 'prismarine-viewer', 'index.js');
const marker = path.join(extractionRoot, '.ready');

if (!(await exists(marker)) || !(await exists(entry))) {
  await fs.rm(extractionRoot, { recursive: true, force: true });
  await fs.mkdir(extractionRoot, { recursive: true });
  await extract(archive, extractionRoot);
  if (!(await exists(entry))) {
    throw new Error(`The viewer asset did not contain ${entry}.`);
  }
  await fs.writeFile(marker, 'ready\n');
}

const viewerModule = await import(pathToFileURL(entry).href);
const viewer = viewerModule.default ?? viewerModule;

export default viewer;
export const mineflayer = viewer.mineflayer;
export const supportedVersions = viewer.supportedVersions;

async function exists(file) {
  try {
    await fs.access(file);
    return true;
  } catch {
    return false;
  }
}

function extract(file, directory) {
  return new Promise((resolve, reject) => {
    const child = spawn('tar', ['-xzf', file, '-C', directory], {
      stdio: ['ignore', 'ignore', 'pipe'],
    });
    let error = '';
    child.stderr.on('data', (chunk) => { error += chunk; });
    child.once('error', reject);
    child.once('close', (code) => {
      if (code === 0) resolve();
      else reject(new Error(`Could not extract viewer asset (tar exited ${code}): ${error}`));
    });
  });
}
