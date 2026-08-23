import assert from 'node:assert/strict';
import test from 'node:test';

import { Dispatch } from '../src/dispatch.mjs';

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
