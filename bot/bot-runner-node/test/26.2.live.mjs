/**
 * Opt-in native 26.2 probe. Requires an isolated offline Paper 26.2 server.
 * No protocol override: accepting a translated 26.1 connection would hide the gap.
 *
 *   npm run test:26.2 -- 127.0.0.1 25575
 *
 * This is a prerequisite probe, not the CompatibilityLiveTest release gate.
 */
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { pingProtocol, versionForProtocol } from '../src/version.mjs';

const host = process.argv[2] ?? '127.0.0.1';
const port = Number(process.argv[3] ?? 25565);

try {
  const protocol = await pingProtocol(host, port);
  if (protocol !== 776) {
    throw new Error(`Expected a native Minecraft 26.2 server (protocol 776), got ${protocol}`);
  }
  console.log(`[26.2] server advertises protocol ${protocol}`);
  const version = versionForProtocol(protocol);
  console.log(`[26.2] runner selected ${version}`);

  const lifecycle = fileURLToPath(new URL('./lifecycle.live.mjs', import.meta.url));
  const child = spawn(process.execPath, [lifecycle, host, String(port), 'Native26Probe'], {
    stdio: 'inherit',
  });
  const code = await new Promise((resolve, reject) => {
    child.once('error', reject);
    child.once('exit', (status) => resolve(status ?? 1));
  });
  process.exitCode = code;
} catch (error) {
  console.error(`[26.2] native compatibility probe failed: ${error.message}`);
  process.exitCode = 1;
}
