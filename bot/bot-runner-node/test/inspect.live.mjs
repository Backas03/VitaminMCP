/**
 * The client view: menu contents, messages, boss bars and the sidebar.
 *
 * This one cannot build its own fixture — a chest, items with names and lore, a boss bar, a
 * scoreboard and a title all need the console. So it runs in two halves: it spawns a bot, prints
 * where the chest should go, and waits while somebody sets that up through the agent.
 *
 *   node test/inspect.live.mjs [host] [port] [botName] [setupSeconds]
 *
 * Print-then-wait rather than op-and-do-it-here on purpose: a bot has to be online before it can
 * be op'd, so a self-contained version would need op to already have happened, and taking op away
 * again is easy to forget.
 */
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';

import { RECORD_SEPARATOR, UNIT_SEPARATOR } from '../src/protocol.mjs';

const host = process.argv[2] ?? '127.0.0.1';
const port = process.argv[3] ?? '25565';
const bot = process.argv[4] ?? 'Stage3Bot';
const setupSeconds = Number(process.argv[5] ?? 45);

const runner = fileURLToPath(new URL('../runner.mjs', import.meta.url));
const child = spawn(process.execPath, [runner, host, port], { stdio: ['pipe', 'pipe', 'inherit'] });
const iterator = createInterface({ input: child.stdout, crlfDelay: Infinity })[Symbol.asyncIterator]();

const next = async () => {
  const { value, done } = await iterator.next();
  if (done) throw new Error('the runner closed its output before answering');
  return value;
};
const ask = async (...f) => {
  child.stdin.write(`${f.join('\t')}\n`);
  return (await next()).split('\t');
};

await next(); // ready

const spawned = await ask('spawn', bot, '');
if (spawned[0] !== 'ok') {
  console.error(`spawn failed: ${spawned.join(' ')}`);
  process.exit(1);
}

const at = { x: Number(spawned[2]), y: Number(spawned[3]), z: Number(spawned[4]) };
const chest = { x: Math.floor(at.x) + 1, y: Math.floor(at.y), z: Math.floor(at.z) };

console.log(`bot     ${bot} at ${at.x} ${at.y} ${at.z}`);
console.log(`chest   ${chest.x} ${chest.y} ${chest.z}`);
console.log('');
console.log('Run these through the agent now:');
console.log(`  setblock ${chest.x} ${chest.y} ${chest.z} minecraft:chest`);
console.log(`  item replace block ${chest.x} ${chest.y} ${chest.z} container.0 with minecraft:diamond_sword[custom_name='{"text":"Test Blade"}',lore=['{"text":"Line one"}','{"text":"Line two"}'],custom_model_data={strings:["alpha","beta"]}] 1`);
console.log(`  item replace block ${chest.x} ${chest.y} ${chest.z} container.4 with minecraft:cooked_beef 17`);
console.log('  bossbar add stage3 {"text":"Boss Test"}');
console.log(`  bossbar set stage3 players ${bot}`);
console.log('  bossbar set stage3 color red');
console.log('  bossbar set stage3 max 100');
console.log('  bossbar set stage3 value 35');
console.log('  scoreboard objectives add stage3 dummy {"text":"Sidebar Title"}');
console.log('  scoreboard objectives setdisplay sidebar stage3');
console.log('  scoreboard players set Alpha stage3 7');
console.log('  scoreboard players set Beta stage3 3');
console.log(`  title ${bot} title {"text":"Title Text"}`);
console.log(`  title ${bot} subtitle {"text":"Subtitle Text"}`);
console.log(`  title ${bot} actionbar {"text":"Action Bar Text"}`);
console.log('  say hello from the console');
console.log('');
console.log(`waiting ${setupSeconds}s…`);

await new Promise((resolve) => setTimeout(resolve, setupSeconds * 1000));

const opened = await ask('use', bot, String(chest.x), String(chest.y), String(chest.z));
console.log(`use     ${opened.join(' ')}`);
await new Promise((resolve) => setTimeout(resolve, 1500));

const menu = await ask('menu', bot);
console.log(`menu    id=${menu[2]} title=${JSON.stringify(menu[3])}`);

if (process.env.DUMP) {
  child.stdin.write(`__dump	${bot}
`);
  console.log(`dump    ${await next()}`);
}

const view = await ask('inspect', bot);
console.log('');
console.log('--- inspect ---');
console.log(`containerId  ${view[2]}`);
console.log(`title        ${JSON.stringify(view[3])}`);
console.log('items');
for (const record of split(view[4])) {
  const [slot, itemId, amount, name, model, lore] = record.split(UNIT_SEPARATOR);
  console.log(`  slot ${slot.padStart(2)}  ${itemId}  x${amount}  name=${JSON.stringify(name)}  model=${JSON.stringify(model)}  lore=${JSON.stringify(lore)}`);
}
console.log('messages');
for (const message of split(view[5])) console.log(`  ${JSON.stringify(message)}`);
console.log('bossBars');
for (const record of split(view[6])) {
  const [title, progress, color] = record.split(UNIT_SEPARATOR);
  console.log(`  ${JSON.stringify(title)}  progress=${progress}  colour=${color}`);
}
console.log(`scoreboard   ${JSON.stringify(view[7])}`);
for (const line of split(view[8])) console.log(`  ${JSON.stringify(line)}`);

console.log('');
console.log(`raw field count ${view.length} (java writes 9)`);

child.stdin.write('shutdown\n');
await new Promise((resolve) => child.once('exit', resolve));

function split(field) {
  return !field ? [] : field.split(RECORD_SEPARATOR);
}
