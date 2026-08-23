import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';

import { collect } from '../src/clientview.mjs';
import { Dispatch } from '../src/dispatch.mjs';
import {
  fields,
  RECORD_SEPARATOR,
  records,
  SEPARATOR,
  UNIT_SEPARATOR,
} from '../src/protocol.mjs';

test('inspect timestamps and sequences messages in the exact wire format', async () => {
  const name = 'TimestampWireBot';
  const bot = fakeBot();
  collect(bot, name);

  const originalNow = Date.now;
  const timestamps = [1_720_000_000_123, 1_720_000_000_523];
  try {
    Date.now = () => timestamps.shift();
    bot.emit('messagestr', `first${RECORD_SEPARATOR}part`, 'chat');
    bot.emit('messagestr', `second${UNIT_SEPARATOR}\tline\n`, 'chat');
  } finally {
    Date.now = originalNow;
  }

  const reply = await inspectThroughDispatch(bot, name);
  assert.equal(reply.length, 17);
  assert.equal(reply[15], '2');
  assert.match(
    reply[16],
    /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
  );

  const expectedBytes = Buffer.concat([
    Buffer.from('0'),
    Buffer.from([0x1f]),
    Buffer.from('1720000000123'),
    Buffer.from([0x1f]),
    Buffer.from('first part'),
    Buffer.from([0x1e]),
    Buffer.from('1'),
    Buffer.from([0x1f]),
    Buffer.from('1720000000523'),
    Buffer.from([0x1f]),
    Buffer.from('second  line '),
  ]);
  assert.deepEqual(Buffer.from(reply[5]), expectedBytes);
});

test('inspect retains the newest one hundred messages without renumbering them', async () => {
  const name = 'RetentionBot';
  const bot = fakeBot();
  collect(bot, name);

  for (let index = 0; index < 101; index += 1) {
    bot.emit('messagestr', `message-${index}`, 'chat');
  }

  const reply = await inspectThroughDispatch(bot, name);
  const messages = records(reply[5]).map(fields);

  assert.equal(messages.length, 100);
  assert.equal(messages[0][0], '1');
  assert.equal(messages[0][2], 'message-1');
  assert.equal(messages.at(-1)[0], '100');
  assert.equal(messages.at(-1)[2], 'message-100');
  assert.equal(reply[15], '101');
});

test('a respawned bot starts a new stream and ignores late messages from the old bot', async () => {
  const name = 'RespawnCursorBot';
  const first = fakeBot();
  collect(first, name);
  first.emit('messagestr', 'before respawn', 'chat');

  const before = await inspectThroughDispatch(first, name);
  assert.equal(fields(records(before[5])[0])[0], '0');
  assert.equal(before[15], '1');
  const streamId = before[16];

  const second = fakeBot();
  collect(second, name);
  const empty = await inspectThroughDispatch(second, name);
  assert.equal(empty[5], '');
  assert.equal(empty[15], '0');
  assert.notEqual(empty[16], streamId);
  const replacementStreamId = empty[16];

  first.emit('messagestr', 'too late from the old bot', 'chat');
  const afterLateMessage = await inspectThroughDispatch(second, name);
  assert.equal(afterLateMessage[5], '');
  assert.equal(afterLateMessage[15], '0');
  assert.equal(afterLateMessage[16], replacementStreamId);
  const staleView = await inspectThroughDispatch(first, name);
  assert.equal(records(staleView[5]).length, 1);
  assert.equal(staleView[15], '1');
  assert.equal(staleView[16], streamId);

  second.emit('messagestr', 'after respawn', 'chat');
  const after = await inspectThroughDispatch(second, name);
  const message = fields(records(after[5])[0]);
  assert.equal(message[0], '0');
  assert.equal(message[2], 'after respawn');
  assert.equal(after[15], '1');
  assert.equal(after[16], replacementStreamId);

  const otherName = 'OtherStreamBot';
  const other = fakeBot();
  collect(other, otherName);
  const otherView = await inspectThroughDispatch(other, otherName);
  assert.notEqual(otherView[16], replacementStreamId);
});

function fakeBot() {
  const bot = new EventEmitter();
  bot._client = new EventEmitter();
  bot._client.socket = { writable: true };
  bot.currentWindow = null;
  bot.entity = { effects: {} };
  bot.registry = { effectsById: {} };
  return bot;
}

async function inspectThroughDispatch(bot, name) {
  const bots = {
    require(requested) {
      assert.equal(requested, name);
      return bot;
    },
  };
  const line = await new Dispatch(bots).handle(['inspect', name].join(SEPARATOR));
  return line.split(SEPARATOR);
}
