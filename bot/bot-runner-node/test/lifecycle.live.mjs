/**
 * Drives the runner the way the MCP server drives it: as a child process, one line at a time.
 *
 * Needs a running backend on `online-mode=false` with `settings.bungeecord: false`, so it is not
 * part of `npm test` — the same reason the Java live tests are gated behind a system property.
 *
 *   node test/lifecycle.live.mjs [host] [port] [botName]
 *
 * It checks the protocol. It cannot check identity: that only the server knows, so ask the agent
 * what it recorded once this passes.
 */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';

const host = process.argv[2] ?? '127.0.0.1';
const port = process.argv[3] ?? '25565';
const bot = process.argv[4] ?? 'Stage1Bot';

const runner = fileURLToPath(new URL('../runner.mjs', import.meta.url));
const child = spawn(process.execPath, [runner, host, port], { stdio: ['pipe', 'pipe', 'inherit'] });

const lines = createInterface({ input: child.stdout, crlfDelay: Infinity });
const iterator = lines[Symbol.asyncIterator]();

const failures = [];

function check(what, actual, expected) {
  try {
    assert.deepEqual(actual, expected);
    console.log(`ok    ${what}`);
  } catch {
    failures.push(what);
    console.log(`FAIL  ${what}\n        got      ${JSON.stringify(actual)}\n        expected ${JSON.stringify(expected)}`);
  }
}

async function next() {
  const { value, done } = await iterator.next();
  if (done) {
    throw new Error('the runner closed its output before answering');
  }
  return value;
}

function send(...fields) {
  child.stdin.write(`${fields.join('\t')}\n`);
}

async function ask(...fields) {
  send(...fields);
  return (await next()).split('\t');
}

const ready = (await next()).split('\t');
check('ready names the protocol it negotiated', ready[0], 'ready');
console.log(`      protocol ${ready[1]}`);

const spawned = await ask('spawn', bot);
check('spawn answers ok with three coordinates', [spawned[0], spawned[1], spawned.length], ['ok', 'spawn', 5]);
console.log(`      at ${spawned.slice(2).join(', ')}`);

check(
  'coordinates are spelled the way Double.toString spells them',
  spawned.slice(2).every((value) => /^-?\d+\.\d+(E-?\d+)?$/.test(value)),
  true,
);

// Only the coordinates: each reply names its own verb, so the fields before them differ by design.
const at = await ask('position', bot);
check('position agrees with the position spawn reported', at.slice(2), spawned.slice(2));

check('an unknown verb is refused by name', await ask('nonsense', bot), [
  'err',
  'nonsense',
  "unknown command 'nonsense'",
]);

check('despawn answers ok', await ask('despawn', bot), ['ok', 'despawn']);

check('acting with a despawned bot says so', await ask('position', bot), [
  'err',
  'position',
  `no bot named ${bot} — spawn it before acting with it`,
]);

send('shutdown');
const code = await new Promise((resolve) => child.once('exit', resolve));
check('shutdown exits cleanly', code, 0);

console.log('');
if (failures.length > 0) {
  console.error(`${failures.length} check(s) failed: ${failures.join('; ')}`);
  process.exit(1);
}
console.log('lifecycle ok — now ask the server what UUID and address it recorded');
