#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const targetKey = process.argv[2] ?? `${process.platform}-${process.arch}`;
const targets = {
  'win32-x64': { asset: 'bot-runner-win-x64.exe', env: 'VITAMINMCP_SEA_NODE_WIN_X64' },
  'linux-x64': { asset: 'bot-runner-linux-x64', env: 'VITAMINMCP_SEA_NODE_LINUX_X64' },
  'linux-arm64': { asset: 'bot-runner-linux-arm64', env: 'VITAMINMCP_SEA_NODE_LINUX_ARM64' },
  'darwin-x64': { asset: 'bot-runner-darwin-x64', env: 'VITAMINMCP_SEA_NODE_DARWIN_X64' },
  'darwin-arm64': { asset: 'bot-runner-darwin-arm64', env: 'VITAMINMCP_SEA_NODE_DARWIN_ARM64' },
};
const target = targets[targetKey];
if (!target) throw new Error(`Unsupported SEA target ${targetKey}.`);

const nodeExecutable = process.env[target.env] ?? (
  targetKey === `${process.platform}-${process.arch}` ? process.execPath : null
);
if (!nodeExecutable) {
  throw new Error(
    `Cross-building ${targetKey} needs ${target.env} pointing at a matching Node executable. `
      + 'No fake cross-platform binary will be produced.',
  );
}

const outputDirectory = path.resolve(process.argv[3] ?? path.join(root, '..', '..', 'build', 'dist', 'runners'));
const temporary = await fs.mkdtemp(path.join(os.tmpdir(), 'vitaminmcp-sea-'));
const bundle = path.join(temporary, 'runner.cjs');
const config = path.join(temporary, 'sea-config.json');
const blob = path.join(temporary, 'sea-prep.blob');
const output = path.join(outputDirectory, target.asset);

try {
  await build({
    entryPoints: [path.join(root, 'runner.mjs')],
    bundle: true,
    format: 'cjs',
    platform: 'node',
    outfile: bundle,
    sourcemap: false,
    external: ['node:*'],
  });
  await fs.writeFile(config, JSON.stringify({
    main: bundle,
    output: blob,
    disableExperimentalSEAWarning: true,
    useSnapshot: false,
    useCodeCache: false,
  }));
  run(process.execPath, ['--experimental-sea-config', config]);
  await fs.mkdir(outputDirectory, { recursive: true });
  await fs.copyFile(nodeExecutable, output);
  if (targetKey !== 'win32-x64') await fs.chmod(output, 0o755);
  run(process.execPath, [
    path.join(root, 'node_modules', 'postject', 'dist', 'cli.js'),
    output,
    'NODE_SEA_BLOB',
    blob,
    '--sentinel-fuse',
    'NODE_SEA_FUSE_fce680ab2cc467b6e072b8b5df1996b2',
  ]);
  process.stdout.write(`built ${output}\n`);
} finally {
  await fs.rm(temporary, { recursive: true, force: true });
}

function run(command, args) {
  const result = spawnSync(command, args, { stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${command} ${args.join(' ')} exited with ${result.status}`);
}
