/**
 * The packet verbs, driven the way the MCP server drives them.
 *
 * Needs a running backend on `online-mode=false` with `settings.bungeecord: true`, and a stone
 * block at the coordinates below — put one there with `setblock` before running this.
 *
 *   node test/actions.live.mjs [host] [port] [botName] [x] [y] [z]
 *
 * What it can check here is the protocol: shapes, error text, and that nothing throws. Whether the
 * block actually broke is a question only the server can answer, so ask the agent afterwards.
 */
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';

const host = process.argv[2] ?? '127.0.0.1';
const port = process.argv[3] ?? '25565';
const bot = process.argv[4] ?? 'Stage2Bot';
const x = process.argv[5] ?? '-101';
const y = process.argv[6] ?? '71';
const z = process.argv[7] ?? '-121';

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

async function ask(...fields) {
  child.stdin.write(`${fields.join('\t')}\n`);
  return (await next()).split('\t');
}

await next(); // ready

const spawned = await ask('spawn', bot, '');
check('spawn', [spawned[0], spawned[1]], ['ok', 'spawn']);
console.log(`      at ${spawned.slice(2).join(', ')}`);

// The block the bot is standing on. Whatever the spawn point turns out to be, this one is solid
// and within reach, which a coordinate chosen in advance is not — and "did not break" and "was
// never in range" look identical from here.
const ground = {
  x: String(Math.floor(Number(spawned[2]))),
  y: String(Math.floor(Number(spawned[3])) - 1),
  z: String(Math.floor(Number(spawned[4]))),
};
console.log(`      ground block ${ground.x} ${ground.y} ${ground.z}`);

// The first seconds after joining are locked out: block interactions are dropped with no event,
// no log line and no refusal, so a dig inside the window is indistinguishable from one that was
// rejected. Measured on Paper 1.21.8 by digging at 1500 / 2500 / 3500 / 4500ms after spawn — the
// first two do nothing, the last two work — so the window is closer to three seconds than to the
// two the docs claim. This is the one case where a fixed wait is honest rather than a guess: the
// lockout is its own timer, not a consequence of anything observable.
await new Promise((resolve) => setTimeout(resolve, 4000));

check('menu reports no menu open as -1 and an empty title', await ask('menu', bot), ['ok', 'menu', '-1', '']);

check('close_menu with nothing open is not an error', await ask('close_menu', bot), ['ok', 'close_menu']);

check('clicking with no menu open says to wait for inventory_open', await ask('click', bot, '0', 'left'), [
  'err',
  'click',
  `Bot ${bot} has no menu open, so slot 0 cannot be clicked. Wait for inventory_open first — `
    + 'a menu does not open synchronously with the command that causes it.',
]);

// The menu check comes first, so this is what an unknown click reports while nothing is open —
// which is exactly what BotActions.clickSlot does, in that order. Validation of the click name
// itself needs a menu to be open, and that arrives with inspect in stage 3.
check('with no menu open, the menu is the complaint whatever the click is named', await ask('click', bot, '0', 'middle'), [
  'err',
  'click',
  `Bot ${bot} has no menu open, so slot 0 cannot be clicked. Wait for inventory_open first — `
    + 'a menu does not open synchronously with the command that causes it.',
]);

check('an unknown face is refused by name', await ask('use', bot, x, y, z, 'sideways'), [
  'err',
  'use',
  "Unknown face 'sideways'. Use down, up, north, south, west, east.",
]);

check('use right-clicks a block', await ask('use', bot, x, y, z), ['ok', 'use']);
check('use accepts a named face', await ask('use', bot, x, y, z, 'north'), ['ok', 'use']);

check('chat', await ask('chat', bot, 'stage two is speaking'), ['ok', 'chat']);
check('command', await ask('command', bot, '/me is running a command'), ['ok', 'command']);

// Nothing is spawned out here, so this exercises the diagnostic rather than the interaction.
const noEntity = await ask('use_entity', bot, '30000', '200', '30000', '2');
check('use_entity with nothing near explains what it did look at', [noEntity[0], noEntity[1]], ['err', 'use_entity']);
check(
  'and names the radius and point in Java-spelled doubles',
  noEntity[2].startsWith('no entity within 2.0 blocks of 30000.0 200.0 30000.0'),
  true,
);
console.log(`      ${noEntity[2]}`);

// Ask again rather than trusting the spawn reply: if the bot has moved since, the block it was
// standing on is no longer the block under it, and a dig that silently misses looks exactly like
// a dig that was refused.
const nowAt = await ask('position', bot);
console.log(`      position before break ${nowAt.slice(2).join(', ')}`);
check('the bot has not moved since spawn', nowAt.slice(2), spawned.slice(2));

check('break', await ask('break', bot, ground.x, ground.y, ground.z), ['ok', 'break']);

check('acting with an unknown bot says so', await ask('break', 'Nobody', x, y, z), [
  'err',
  'break',
  'no bot named Nobody — spawn it before acting with it',
]);

// Give the server a moment to apply the dig before anything asks it what the block is now.
await new Promise((resolve) => setTimeout(resolve, 1000));

child.stdin.write('shutdown\n');
const code = await new Promise((resolve) => child.once('exit', resolve));
check('shutdown exits cleanly', code, 0);

console.log('');
if (failures.length > 0) {
  console.error(`${failures.length} check(s) failed: ${failures.join('; ')}`);
  process.exit(1);
}
console.log(`protocol ok — now ask the server whether ${ground.x} ${ground.y} ${ground.z} is air`);
