/**
 * The spike that decides the migration: can a mineflayer bot join a Paper backend carrying an
 * injected UUID and a chosen client address?
 *
 * design.md 3.1 has bots imitate a BungeeCord forwarding handshake, which is non-standard —
 * `minecraft-protocol` has to let the handshake's address field be written verbatim, NUL
 * separators and all, or the whole approach collapses back to plain offline mode and the bot
 * loses both its reproducible UUID and `clientIp`.
 *
 * Run it against a backend on `online-mode=false` with `settings.bungeecord: true`:
 *
 *   node spike/join.mjs [host] [port] [name] [clientIp]
 *
 * It prints what the client ended up believing. Ask the *server* what it recorded before calling
 * it a pass — the client believing its own claim proves nothing.
 */
import mineflayer from 'mineflayer';

import { addressField, identity, undashed } from '../src/identity.mjs';

const host = process.argv[2] ?? '127.0.0.1';
const port = Number(process.argv[3] ?? 25565);
const name = process.argv[4] ?? 'SpikeBot';
const clientIp = process.argv[5] ?? '127.0.0.1';

const id = identity(name);
const field = addressField(host, clientIp, id);

console.log(`name       ${id.name}`);
console.log(`uuid       ${id.uuid}  (undashed ${undashed(id.uuid)})`);
console.log(`clientIp   ${clientIp}`);
console.log(`handshake  ${field.replaceAll('\0', '\0')}`);
console.log('');

const bot = mineflayer.createBot({
  host,
  port,
  username: id.name,
  auth: 'offline',
  fakeHost: field,
  // The runner will negotiate this from a ping; pinning it keeps the spike's failures about the
  // handshake rather than about version detection.
  version: process.env.SPIKE_VERSION || false,
  checkTimeoutInterval: 30_000,
});

const deadline = setTimeout(() => {
  console.error('FAIL  no spawn within 30s');
  process.exit(1);
}, 30_000);

bot.once('spawn', () => {
  clearTimeout(deadline);
  const client = bot._client;
  console.log('SPAWNED');
  console.log(`  version     ${bot.version}`);
  console.log(`  client uuid ${client.uuid}`);
  console.log(`  entry uuid  ${bot.player?.uuid ?? '(no player list entry yet)'}`);
  console.log(`  position    ${bot.entity.position}`);
  console.log(`  gameMode    ${bot.game.gameMode}`);
  console.log('');
  console.log('Now ask the server what it recorded — its own UUID and address for this player.');

  // Stay connected so the server can be interrogated while the bot is online.
  setTimeout(() => {
    bot.quit('spike done');
    process.exit(0);
  }, Number(process.env.SPIKE_LINGER_MS || 20_000));
});

bot.on('kicked', (reason) => {
  clearTimeout(deadline);
  console.error(`FAIL  kicked: ${JSON.stringify(reason)}`);
  process.exit(1);
});

bot.on('error', (err) => {
  clearTimeout(deadline);
  console.error(`FAIL  ${err.message}`);
  process.exit(1);
});
