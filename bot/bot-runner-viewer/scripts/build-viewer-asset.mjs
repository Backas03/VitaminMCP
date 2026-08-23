#!/usr/bin/env node

/** Builds the pinned, optional viewer sidecar that npm fetches on the first world-view request. */

import { spawnSync } from 'node:child_process';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { trimViewer } from './slim-viewer.mjs';

const sourceRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repository = path.resolve(sourceRoot, '..', '..');
const output = path.resolve(
  process.argv[2] ?? path.join(repository, 'build', 'dist', 'assets', 'bot-runner-viewer-win-x64.tgz'),
);
const floor = (await fs.readFile(
  path.join(repository, 'build-logic/src/main/kotlin/moe/vitamin/build/SupportedVersions.kt'),
  'utf8',
)).match(/const val FLOOR = "([^"]+)"/)?.[1];
if (!floor) throw new Error('Could not read FLOOR from SupportedVersions.kt.');

const temporary = await fs.mkdtemp(path.join(os.tmpdir(), 'vitaminmcp-viewer-'));
const staged = path.join(temporary, 'package');

try {
  await fs.cp(sourceRoot, staged, {
    recursive: true,
    filter: (entry) => entry !== path.join(sourceRoot, 'node_modules')
      && !entry.includes(`${path.sep}node_modules${path.sep}`),
  });
  await fs.cp(path.join(sourceRoot, 'node_modules'), path.join(staged, 'node_modules'), {
    recursive: true,
  });

  const viewerRoot = path.join(staged, 'node_modules', 'prismarine-viewer');
  const minecraftDataRoot = path.join(staged, 'node_modules', 'minecraft-data');
  const trimmed = trimViewer(viewerRoot, minecraftDataRoot, floor);

  const manifestFile = path.join(staged, 'package.json');
  const manifest = JSON.parse(await fs.readFile(manifestFile, 'utf8'));
  manifest.name = 'vitaminmcp-bot-runner-viewer';
  manifest.private = false;
  manifest.bundleDependencies = Object.keys(manifest.dependencies ?? {});
  manifest.files = ['node_modules', 'THIRD_PARTY_NOTICES.md'];
  await fs.writeFile(manifestFile, `${JSON.stringify(manifest, null, 2)}\n`);

  // npm's canvas package intentionally excludes its native build from its own packlist. This
  // asset is built for one host platform, so retain that already-installed binary in the bundle.
  const canvasManifestFile = path.join(staged, 'node_modules', 'canvas', 'package.json');
  const canvasManifest = JSON.parse(await fs.readFile(canvasManifestFile, 'utf8'));
  canvasManifest.files = [...new Set([...(canvasManifest.files ?? []), 'build/'])];
  await fs.writeFile(canvasManifestFile, `${JSON.stringify(canvasManifest, null, 2)}\n`);
  const nativeCanvas = path.join(staged, 'node_modules', 'canvas', 'build', 'Release', 'canvas.node');
  try {
    await fs.access(nativeCanvas);
  } catch {
    throw new Error(
      `No native canvas binary at ${nativeCanvas}. Run npm ci with canvas install scripts enabled `
        + 'before building the platform-specific viewer asset.',
    );
  }

  await fs.mkdir(path.dirname(output), { recursive: true });
  const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm';
  const result = spawnSync(
    npm,
    ['pack', '--ignore-scripts', '--pack-destination', temporary],
    { cwd: staged, stdio: 'inherit', shell: process.platform === 'win32' },
  );
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`npm pack exited with ${result.status}.`);

  const packed = (await fs.readdir(temporary)).find((entry) => entry.endsWith('.tgz'));
  if (!packed) throw new Error('npm pack produced no viewer asset.');
  await fs.copyFile(path.join(temporary, packed), output);

  const size = (await fs.stat(output)).size;
  const unpacked = await directorySize(staged);
  if (unpacked >= 200 * 1024 * 1024) {
    throw new Error(
      `Trimmed viewer asset unpacks to ${Math.round(unpacked / 1024 / 1024)}MB; expected under 200MB.`,
    );
  }
  process.stdout.write(
    `viewer archive ${Math.round(size / 1024 / 1024)}MB (unpacked `
      + `${Math.round(unpacked / 1024 / 1024)}MB); kept viewer ${trimmed.versions.join(', ')}; `
      + `minecraft-data ${trimmed.dataVersions.join(', ')}\n${output}\n`,
  );
} finally {
  await fs.rm(temporary, { recursive: true, force: true });
}

async function directorySize(root) {
  let total = 0;
  for (const entry of await fs.readdir(root, { withFileTypes: true })) {
    const file = path.join(root, entry.name);
    total += entry.isDirectory() ? await directorySize(file) : (await fs.stat(file)).size;
  }
  return total;
}
