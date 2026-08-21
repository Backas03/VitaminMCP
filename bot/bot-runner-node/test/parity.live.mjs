/**
 * Drives the Java runner and the Node runner through the same script and diffs their replies.
 *
 *   node test/parity.live.mjs <path-to-bot-runner.jar> [host] [port]
 *
 * Byte for byte, because the fields are separated by 0x09 and the records inside them by 0x1E and
 * 0x1F: a drift in separators, in field count, or in how a double is spelled is invisible when the
 * two lines are read side by side, and fatal to the Java side that parses them.
 *
 * Coordinates are the one thing that legitimately differs — the server picks a spawn point inside
 * a radius, so two bots do not stand in the same place. Those fields are compared for shape rather
 * than for value, and the shape is the part that has ever been wrong.
 */
import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';

const jar = process.argv[2];
const host = process.argv[3] ?? '127.0.0.1';
const port = process.argv[4] ?? '25565';

if (!jar) {
  console.error('usage: node test/parity.live.mjs <path-to-bot-runner.jar> [host] [port]');
  process.exit(2);
}

const java = process.env.JAVA_HOME ? `${process.env.JAVA_HOME}/bin/java` : 'java';
const nodeRunner = fileURLToPath(new URL('../runner.mjs', import.meta.url));

/** A double as Java's Double.toString spells it, which is what both runners must produce. */
const JAVA_DOUBLE = /^-?(\d+\.\d+|\d\.\d+E-?\d+)$/;

/** Replies whose coordinates are the bot's own position, and so cannot match between runners. */
const POSITIONAL = new Set(['spawn', 'position']);

/**
 * Differences that were decided on, each with its reasoning in DIFFERENCES.md.
 *
 * Listing them by name rather than relaxing the comparison is the point: a difference nobody chose
 * still fails here, which is the only way the list stays honest.
 */
const KNOWN_DIFFERENCES = [
  {
    what: "use with an unknown face",
    java: /^err\tuse\tNo enum constant .*Direction\.SIDEWAYS$/,
    node: /^err\tuse\tUnknown face 'sideways'\./,
  },
];

function isKnown(javaLine, nodeLine) {
  return KNOWN_DIFFERENCES.find(
    (known) => known.java.test(javaLine) && known.node.test(nodeLine),
  );
}

const script = (bot) => [
  ['spawn', bot, '203.0.113.11'],
  ['__wait', '4000'],
  ['position', bot],
  ['menu', bot],
  ['close_menu', bot],
  ['click', bot, '0', 'left'],
  ['click', bot, '0', 'middle'],
  ['use', bot, '0', '-64', '0', 'sideways'],
  ['use_entity', bot, '30000', '200', '30000', '2'],
  ['use_entity', bot, '30000', '200', '30000', '2', 'villager'],
  ['nonsense', bot],
  ['break', 'Nobody', '0', '0', '0'],
  ['despawn', bot],
  ['position', bot],
];

async function run(label, command, args, bot) {
  const child = spawn(command, args, { stdio: ['pipe', 'pipe', 'inherit'] });
  const iterator = createInterface({ input: child.stdout, crlfDelay: Infinity })[Symbol.asyncIterator]();

  const next = async () => {
    const { value, done } = await iterator.next();
    if (done) {
      throw new Error(`${label} closed its output before answering`);
    }
    return value;
  };

  const ready = await next();
  const replies = [];

  for (const step of script(bot)) {
    if (step[0] === '__wait') {
      await new Promise((resolve) => setTimeout(resolve, Number(step[1])));
      continue;
    }
    child.stdin.write(`${step.join('\t')}\n`);
    replies.push(await next());
  }

  child.stdin.write('shutdown\n');
  await new Promise((resolve) => child.once('exit', resolve));
  return { ready, replies };
}

console.log('running the Java runner…');
const fromJava = await run('java', java, ['-jar', jar, host, port], 'ParityJava');

console.log('running the Node runner…');
const fromNode = await run('node', process.execPath, [nodeRunner, host, port], 'ParityNode');

let mismatches = 0;

function report(what, java_, node_) {
  if (java_ === node_) {
    console.log(`ok    ${what}`);
    return;
  }
  const known = isKnown(java_, node_);
  if (known) {
    console.log(`known ${what} — ${known.what} (see DIFFERENCES.md)`);
    return;
  }
  mismatches += 1;
  console.log(`FAIL  ${what}`);
  console.log(`        java ${visible(java_)}`);
  console.log(`        node ${visible(node_)}`);
}

/** The separators are control characters, so show them rather than printing them. */
function visible(line) {
  return JSON.stringify(line)
    .replaceAll('\\t', '»')
    .replaceAll('', '␞')
    .replaceAll('', '␟');
}

report('ready', fromJava.ready, fromNode.ready);

const steps = script('bot').filter((step) => step[0] !== '__wait');

for (let i = 0; i < steps.length; i += 1) {
  const verb = steps[i][0];
  const javaFields = fromJava.replies[i].split('\t');
  const nodeFields = fromNode.replies[i].split('\t');
  const what = `${verb} — ${steps[i].slice(2).join(' ') || 'no arguments'}`;

  // The bot name is in every error message, and the two runs use different names.
  const normalise = (fields) => fields.map((f) => f.replace(/Parity(Java|Node)/g, '<bot>'));

  if (POSITIONAL.has(verb) && javaFields[0] === 'ok' && nodeFields[0] === 'ok') {
    report(`${what} — field count`, String(javaFields.length), String(nodeFields.length));
    for (let f = 2; f < javaFields.length; f += 1) {
      report(
        `${what} — field ${f} is a Java double`,
        `${JAVA_DOUBLE.test(javaFields[f])}`,
        `${JAVA_DOUBLE.test(nodeFields[f])}`,
      );
    }
    continue;
  }

  report(what, normalise(javaFields).join('\t'), normalise(nodeFields).join('\t'));
}

console.log('');
if (mismatches > 0) {
  console.error(`${mismatches} line(s) differ`);
  process.exit(1);
}
console.log('the two runners answer identically, apart from what DIFFERENCES.md accounts for');
