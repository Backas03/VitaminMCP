#!/usr/bin/env node

/** Copies the source runner into the npm package immediately before publish. */
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const npmRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = path.resolve(npmRoot, '..', 'bot', 'bot-runner-node');
const target = path.join(npmRoot, 'runner');

await fs.rm(target, { recursive: true, force: true });
await fs.mkdir(path.join(target, 'src'), { recursive: true });
await fs.copyFile(path.join(source, 'runner.mjs'), path.join(target, 'runner.mjs'));
for (const file of await fs.readdir(path.join(source, 'src'))) {
  if (file.endsWith('.mjs')) {
    await fs.copyFile(path.join(source, 'src', file), path.join(target, 'src', file));
  }
}
process.stdout.write(`staged Node runner in ${target}\n`);
