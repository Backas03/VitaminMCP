import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';

import minecraftData from 'minecraft-data';
import { Vec3 } from 'vec3';

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

function fakeBot({ version = '1.21.8', entities = {}, blockAt = () => ({ name: 'stone' }) } = {}) {
  const timeline = [];
  const bot = new EventEmitter();
  bot.entity = { position: new Vec3(0, 64, 0), eyeHeight: 1.62 };
  bot.entities = entities;
  bot.registry = minecraftData(version);
  bot.controls = {};
  bot.getControlState = (control) => Boolean(bot.controls[control]);
  bot.blockAt = blockAt;
  bot.attack = (entity) => timeline.push({ type: 'attack', id: entity.id });
  bot.lookAt = async (point) => {
    timeline.push({ type: 'look', point });
    setTimeout(() => {
      timeline.push({ type: 'tick' });
      bot.emit('physicsTick');
    }, 5);
  };
  bot._client = {
    socket: { writable: true },
    write(type, packet) {
      timeline.push({ type, packet });
    },
  };
  return { bot, timeline };
}

function interaction(id, x, y, z, width, height, version = '1.21.8') {
  const keys = minecraftData(version).entitiesByName.interaction.metadataKeys;
  const metadata = [];
  metadata[keys.indexOf('width')] = width;
  metadata[keys.indexOf('height')] = height;
  return { id, name: 'interaction', position: new Vec3(x, y, z), width: 0, height: 0, metadata };
}

function furnitureAt(x, y, z) {
  return {
    403: { id: 403, name: 'item_display', position: new Vec3(x, y, z), width: 0, height: 0 },
    405: { id: 405, name: 'shulker', position: new Vec3(x, y, z), width: 1, height: 1 },
    406: interaction(406, x, y - 0.005, z, 1.005, 1.01),
  };
}

test('use waits until the client knows the target block', async () => {
  let known = false;
  const { bot, timeline } = fakeBot({ blockAt: () => (known ? { name: 'chest' } : null) });

  const pending = new Dispatch({ require: () => bot }).handle('use\tTester1\t4\t64\t-2\tup');
  setTimeout(() => { known = true; }, 30);

  assert.equal(await pending, 'ok\tuse');
  const places = timeline.filter((entry) => entry.type === 'block_place');
  assert.equal(places.length, 1);
  assert.deepEqual(places[0].packet.location, { x: 4, y: 64, z: -2 });
});

test('use clicks the middle of the named face, as a player aiming at it does', async () => {
  const cases = {
    up: [0.5, 1, 0.5],
    down: [0.5, 0, 0.5],
    north: [0.5, 0.5, 0],
    south: [0.5, 0.5, 1],
    west: [0, 0.5, 0.5],
    east: [1, 0.5, 0.5],
  };
  for (const [face, cursor] of Object.entries(cases)) {
    const { bot, timeline } = fakeBot();
    assert.equal(await new Dispatch({ require: () => bot }).handle(`use\tTester1\t4\t64\t-2\t${face}`), 'ok\tuse');
    const place = timeline.find((entry) => entry.type === 'block_place').packet;
    assert.deepEqual([place.cursorX, place.cursorY, place.cursorZ], cursor, face);
  }
});

test('use turns to the face and lets the turn go out before clicking', async () => {
  const { bot, timeline } = fakeBot();
  await new Dispatch({ require: () => bot }).handle('use\tTester1\t4\t64\t-2\tup');

  assert.deepEqual(timeline.map((entry) => entry.type), ['look', 'tick', 'block_place']);
  assert.deepEqual(timeline[0].point.toArray(), [4.5, 65, -1.5]);
});

test('use_entity clicks the furniture hitbox, not the display entity drawn at the same spot', async () => {
  const { bot, timeline } = fakeBot({ entities: furnitureAt(-1.5, 64, 2.5) });
  const reply = await new Dispatch({ require: () => bot }).handle('use_entity\tTester1\t-1.5\t64\t2.5\t2\t');

  assert.match(reply, /^ok\tuse_entity\t406\tinteraction, \d+\.\d blocks from the bot's eyes$/);
  assert.deepEqual(timeline.map((entry) => entry.type), ['look', 'tick', 'use_entity', 'use_entity']);
  assert.ok(timeline.slice(2).every((entry) => entry.packet.target === 406));
});

test('use_entity aims at the hitbox and sends where the aim lands on it', async () => {
  const { bot, timeline } = fakeBot({ entities: furnitureAt(-1.5, 64, 2.5) });
  await new Dispatch({ require: () => bot }).handle('use_entity\tTester1\t-1.5\t64\t2.5\t2\t');

  assert.deepEqual(timeline[0].point.toArray().map((v) => +v.toFixed(3)), [-1.5, 64.5, 2.5]);
  const at = timeline.find((entry) => entry.packet?.mouse === 2).packet;
  assert.ok(Math.abs(at.x) <= 0.5025 && Math.abs(at.z) <= 0.5025, JSON.stringify(at));
  assert.ok(at.y >= 0 && at.y <= 1.01, JSON.stringify(at));
});

test('use_entity carries the sneak the bot is holding', async () => {
  const { bot, timeline } = fakeBot({ entities: furnitureAt(-1.5, 64, 2.5) });
  bot.controls.sneak = true;
  await new Dispatch({ require: () => bot }).handle('use_entity\tTester1\t-1.5\t64\t2.5\t2\t');

  const packets = timeline.filter((entry) => entry.type === 'use_entity');
  assert.equal(packets.length, 2);
  assert.ok(packets.every((entry) => entry.packet.sneaking === true));
});

test('use_entity names what is there when only unclickable entities are', async () => {
  const { bot, timeline } = fakeBot({
    entities: { 403: { id: 403, name: 'item_display', position: new Vec3(1, 64, 1), width: 0, height: 0 } },
  });
  const reply = await new Dispatch({ require: () => bot }).handle('use_entity\tTester1\t1\t64\t1\t2\t');

  assert.match(reply, /^err\tuse_entity\tno clickable entity .*item_display, which a player's crosshair passes through/);
  assert.deepEqual(timeline, []);
});

test('use_entity sends the two-packet interaction through 1.21.11', async () => {
  const { bot, timeline } = fakeBot({
    version: '1.21.11',
    entities: { 210: { id: 210, name: 'armor_stand', position: new Vec3(1, 64, 1), width: 0.5, height: 1.975 } },
  });
  const reply = await new Dispatch({ require: () => bot }).handle('use_entity\tTester1\t1\t64\t1\t3.0\t');

  assert.match(reply, /^ok\tuse_entity\t210\t/);
  const packets = timeline.filter((entry) => entry.type === 'use_entity');
  assert.equal(packets.length, 2);
  assert.equal(packets[0].packet.mouse, 2);
  assert.equal(packets[1].packet.mouse, 0);
});

test('use_entity sends the single located interaction 26.1 expects', async () => {
  const { bot, timeline } = fakeBot({
    version: '26.1',
    entities: { 210: { id: 210, name: 'armor_stand', position: new Vec3(1, 64, 1), width: 0.5, height: 1.975 } },
  });
  const reply = await new Dispatch({ require: () => bot }).handle('use_entity\tTester1\t1\t64\t1\t3.0\t');

  assert.match(reply, /^ok\tuse_entity\t210\t/);
  const packets = timeline.filter((entry) => entry.type === 'use_entity');
  assert.equal(packets.length, 1);
  assert.deepEqual(Object.keys(packets[0].packet).sort(), ['hand', 'location', 'sneaking', 'target']);
  assert.equal(packets[0].packet.target, 210);
  assert.equal(packets[0].packet.sneaking, false);
  assert.ok(packets[0].packet.location.y > 0 && packets[0].packet.location.y < 1.975);
});

test('attack_entity turns to the hitbox before the attack goes out', async () => {
  const { bot, timeline } = fakeBot({ entities: furnitureAt(-1.5, 64, 2.5) });
  const reply = await new Dispatch({ require: () => bot }).handle('attack_entity\tTester1\t-1.5\t64\t2.5\t2\t');

  assert.match(reply, /^ok\tattack_entity\t406\t/);
  assert.deepEqual(timeline.map((entry) => entry.type), ['look', 'tick', 'attack']);
});

test("use_entity says when the target is beyond a survival player's reach", async () => {
  const { bot } = fakeBot({ entities: furnitureAt(0.5, 64, 5.5) });
  const reply = await new Dispatch({ require: () => bot }).handle('use_entity\tTester1\t0.5\t64\t5.5\t2\t');

  assert.match(reply, /farther than the 3\.0 blocks a survival player reaches/);
});

function holdingBot(item) {
  const { bot, timeline } = fakeBot();
  bot.QUICK_BAR_START = 36;
  bot.quickBarSlot = 2;
  bot.inventory = new EventEmitter();
  bot.inventory.slots = [];
  bot.inventory.slots[38] = item;
  bot.inventory.updateSlot = (slot, next) => {
    const old = bot.inventory.slots[slot];
    bot.inventory.slots[slot] = next;
    bot.inventory.emit('updateSlot', slot, old, next);
  };
  Object.defineProperty(bot, 'heldItem', { get: () => bot.inventory.slots[38] ?? null });
  return { bot, timeline };
}

test('drop_item presses drop-stack once for the whole stack and reports the item that came out', async () => {
  const { bot, timeline } = holdingBot({ name: 'cobblestone', count: 3 });
  const pending = new Dispatch({ require: () => bot }).handle('drop_item\tTester1\t');
  setTimeout(() => bot.emit('entitySpawn', { name: 'item', position: new Vec3(0, 65.3, 0) }), 10);

  assert.equal(await pending, 'ok\tdrop_item\tdropped 3 cobblestone');
  assert.deepEqual(timeline.filter((entry) => entry.type === 'block_dig').map((entry) => entry.packet.status), [3]);
  assert.equal(bot.heldItem, null);
});

test('drop_item presses drop-one once per item for part of a stack', async () => {
  const { bot, timeline } = holdingBot({ name: 'cobblestone', count: 3 });
  const pending = new Dispatch({ require: () => bot }).handle('drop_item\tTester1\t2');
  setTimeout(() => bot.emit('entitySpawn', { name: 'item', position: new Vec3(0, 65.3, 0) }), 10);

  assert.equal(await pending, 'ok\tdrop_item\tdropped 2 cobblestone');
  assert.deepEqual(timeline.filter((entry) => entry.type === 'block_dig').map((entry) => entry.packet.status), [4, 4]);
  assert.equal(bot.heldItem.count, 1);
});

test('drop_item says so when the server hands the item back', async () => {
  const { bot } = holdingBot({ name: 'cobblestone', count: 3 });
  const pending = new Dispatch({ require: () => bot }).handle('drop_item\tTester1\t');
  setTimeout(() => bot.inventory.updateSlot(38, { name: 'cobblestone', count: 3 }), 10);

  assert.match(await pending, /^ok\tdrop_item\tthe server gave the cobblestone back, so something refused the drop/);
  assert.equal(bot.heldItem.count, 3);
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
