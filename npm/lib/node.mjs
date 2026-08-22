import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';

export const REQUIRED_NODE = '18.17';

/** Finds an explicitly configured Node first, then the executable on PATH. */
export function findNode() {
  const configured = process.env.VITAMINMCP_NODE;
  if (configured && (existsSync(configured) || path.basename(configured) === configured)) {
    return configured;
  }
  return process.platform === 'win32' ? 'node.exe' : 'node';
}

/** Checks Node before choosing the source runner path. */
export function checkNode(node) {
  const probe = spawnSync(node, ['--version'], { encoding: 'utf8' });
  if (probe.error) {
    return { ok: false, message: `No Node found. The hybrid runner needs Node ${REQUIRED_NODE} or later.` };
  }
  const match = `${probe.stdout || ''}${probe.stderr || ''}`.match(/v(\d+)(?:\.(\d+))?/);
  if (!match) return { ok: true, version: null };
  const major = Number(match[1]);
  const minor = Number(match[2] ?? 0);
  if (major < 18 || (major === 18 && minor < 17)) {
    return { ok: false, version: `${major}.${minor}`, message:
      `Node ${major}.${minor} is too old — the hybrid runner needs Node ${REQUIRED_NODE} or later.` };
  }
  return { ok: true, version: `${major}.${minor}` };
}

/** The release asset name for the current SEA target. */
export function runnerAssetName(platform = process.platform, arch = process.arch) {
  if (platform === 'win32' && arch === 'x64') return 'bot-runner-win-x64.exe';
  if (platform === 'linux' || platform === 'darwin') {
    throw new Error(
      `Native runner assets for ${platform}-${arch} are planned but not released yet. `
        + 'Install Node 18.17 or later to use the source runner.',
    );
  }
  throw new Error(`No VitaminMCP runner asset exists for ${platform}-${arch}.`);
}
