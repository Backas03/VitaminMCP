import assert from 'node:assert/strict';
import test from 'node:test';

import minecraftData from 'minecraft-data';

import { Dispatch } from '../src/dispatch.mjs';

function chatBot() {
  const sent = [];
  const bot = {
    entity: { position: { x: 0, y: 64, z: 0 } },
    _client: {
      socket: { writable: true },
      write() { throw new Error('chat packets must go through the signing implementation'); },
    },
    chat(message) { sent.push(message); },
  };
  return { dispatch: new Dispatch({ require: () => bot }), sent };
}

test('chat sends ordinary player chat without say permission', async () => {
  const { dispatch, sent } = chatBot();
  assert.equal(await dispatch.handle('chat\tTester1\t코인'), 'ok\tchat');
  assert.deepEqual(sent, ['코인']);
});

test('commands use the library signing and acknowledgement state', async () => {
  const { dispatch, sent } = chatBot();
  assert.equal(await dispatch.handle('command\tTester1\tsay hello'), 'ok\tcommand');
  assert.equal(await dispatch.handle('command\tTester1\t/say again'), 'ok\tcommand');
  assert.deepEqual(sent, ['/say hello', '/say again']);
});

test('chat refuses a slash command instead of executing it', async () => {
  const { dispatch, sent } = chatBot();
  const reply = await dispatch.handle('chat\tTester1\t/op Tester1');
  assert.match(reply, /^err\tchat\tUse the command action/);
  assert.deepEqual(sent, []);
});

test('use waits until the client knows the target block', async () => {
  let known = false;
  const packets = [];
  const bot = {
    entity: { position: { x: 0, y: 64, z: 0 } },
    _client: {
      socket: { writable: true },
      write(type, packet) {
        packets.push({ type, packet });
      },
    },
    blockAt() {
      return known ? { name: 'chest' } : null;
    },
  };
  const bots = { require: () => bot };

  const pending = new Dispatch(bots).handle('use\tTester1\t4\t64\t-2\tup');
  setTimeout(() => { known = true; }, 30);

  assert.equal(await pending, 'ok\tuse');
  assert.equal(packets.length, 1);
  assert.equal(packets[0].type, 'block_place');
  assert.deepEqual(packets[0].packet.location, { x: 4, y: 64, z: -2 });
});

function entityBot(version) {
  const packets = [];
  const bot = {
    entity: { position: { x: 0, y: 64, z: 0 } },
    entities: {
      210: { id: 210, name: 'armor_stand', position: { x: 1, y: 64, z: 1 } },
    },
    registry: minecraftData(version),
    _client: {
      socket: { writable: true },
      write(type, packet) {
        packets.push({ type, packet });
      },
    },
  };
  return { bot, packets };
}

test('use_entity sends the two-packet interaction through 1.21.11', async () => {
  const { bot, packets } = entityBot('1.21.11');
  const reply = await new Dispatch({ require: () => bot }).handle('use_entity\tTester1\t1\t64\t1\t3.0\t');

  assert.equal(reply, 'ok\tuse_entity\t210');
  assert.deepEqual(packets.map((p) => p.type), ['use_entity', 'use_entity']);
  assert.equal(packets[0].packet.mouse, 2);
  assert.equal(packets[1].packet.mouse, 0);
});

test('use_entity sends the single located interaction 26.1 expects', async () => {
  const { bot, packets } = entityBot('26.1');
  const reply = await new Dispatch({ require: () => bot }).handle('use_entity\tTester1\t1\t64\t1\t3.0\t');

  assert.equal(reply, 'ok\tuse_entity\t210');
  assert.equal(packets.length, 1);
  assert.deepEqual(packets[0].packet, {
    target: 210, hand: 0, location: { x: 0, y: 1, z: 0 }, sneaking: false,
  });
});

test('an error message with newlines stays one line', async () => {
  const bots = {
    require() {
      throw new Error('first line\nsecond line\r\nthird');
    },
  };
  const reply = await new Dispatch(bots).handle('use_entity\tTester1\t1\t64\t1\t3.0\t');
  assert.equal(reply.split('\n').length, 1);
  assert.equal(reply, 'err\tuse_entity\tfirst line second line  third');
});
