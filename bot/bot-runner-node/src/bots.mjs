import mineflayer from 'mineflayer';
import { configurePathfinder, loadPathfinder, moveTo } from './movement.mjs';
import { reachable } from './movement.mjs';
import { stopAllViews, stopView as stopBotView, view as startView } from './viewer.mjs';

import { collect, forget } from './clientview.mjs';
import { addressField, identity } from './identity.mjs';
import { answerResourcePacks, describeProgress, traceProgress } from './join.mjs';

/** How long a bot has to get from a socket to standing in the world. */
const LOGIN_TIMEOUT_MILLIS = 30_000;

/** How long it then has to stop falling. */
const SETTLE_TIMEOUT_MILLIS = 15_000;

/** Matching the Java runner: five polls 50ms apart with no change in Y is "landed". */
const SETTLED_CHECKS = 5;
const SETTLE_POLL_MILLIS = 50;

/** How long the client gets to be sent the chunk it is standing in. */
const WORLD_TIMEOUT_MILLIS = 15_000;

/** The bots this runner holds, and the server they all connect to. */
export class BotRegistry {
  #host;

  #port;

  #version;

  #bots = new Map();

  constructor(host, port, version) {
    this.#host = host;
    this.#port = port;
    this.#version = version;
  }

  /** Connects a bot and waits until it is standing in the world. */
  async spawn(name, clientIp) {
    // The Java runner overwrites the map entry, so a repeated spawn is not an error there and must
    // not become one here. Closing the old socket first is the only difference: leaving it open
    // would hold a player slot under a name this runner no longer tracks.
    const existing = this.#bots.get(name);
    if (existing) {
      this.#bots.delete(name);
      quietly(() => existing.quit());
    }

    const id = identity(name);
    // Normal logins use the server's ordinary offline UUID for this name, which stays stable when
    // a test reuses the same bot. A clientIp is an explicit request for the opt-in BungeeCord
    // forwarding handshake used by tests that need a spoofed address or UUID.
    const bot = mineflayer.createBot(
      connectionOptions(this.#host, this.#port, this.#version, id, clientIp),
    );
    loadPathfinder(bot);

    // Both before anything is awaited. A server can push a resource pack the moment login
    // succeeds, and a request that arrives before its listener does is a connection that
    // hangs in configuration until the timeout below gives up on it.
    answerResourcePacks(bot);
    const progress = traceProgress(bot);

    // Before waiting to join, not after: messages are events, and a plugin that greets or refuses
    // on join says so within a tick of the bot arriving. Attaching afterwards loses exactly the
    // messages most worth having.
    collect(bot, name);

    try {
      await joined(bot, name, progress);
    } catch (failure) {
      quietly(() => bot.end());
      throw failure;
    }

    // From here the bot outlives this call, so it needs an owner for its failures. An unhandled
    // 'error' is fatal to the process in Node, which would turn one kicked bot into a dead runner
    // and lose every other bot with it.
    bot.on('error', () => {});
    bot.on('kicked', () => {});

    this.#bots.set(name, bot);
    configurePathfinder(bot);
    await settle(bot, name);
    await worldKnown(bot, name);
    return position(bot);
  }

  async move(name, x, y, z, mode, timeoutMillis) {
    await moveTo(this.require(name), name, x, y, z, mode, timeoutMillis);
  }

  async assertReachable(name, x, y, z, timeoutMillis) {
    const bot = name && name.trim()
      ? this.require(name)
      : this.#bots.values().next().value;
    if (!bot) {
      throw new Error('no bot is available to inspect the loaded world for reachability');
    }
    return reachable(bot, x, y, z, timeoutMillis);
  }

  async view(name, what, mode) {
    return startView(this.require(name), name, what, mode, this.#version);
  }

  stopView(name) {
    stopBotView(this.require(name));
  }

  despawn(name) {
    const bot = this.#bots.get(name);
    if (!bot) {
      return;
    }
    this.#bots.delete(name);
    forget(name);
    stopBotView(bot);
    quietly(() => bot.quit());
  }

  position(name) {
    return position(this.require(name));
  }

  /** Disconnects every bot. */
  shutdown() {
    stopAllViews();
    for (const [name, bot] of this.#bots) {
      forget(name);
      quietly(() => bot.quit());
    }
    this.#bots.clear();
  }

  /** The bot, or the error the Java runner raises for the same mistake. */
  require(name) {
    const bot = this.#bots.get(name);
    if (!bot) {
      throw new Error(`no bot named ${name} — spawn it before acting with it`);
    }
    return bot;
  }
}

/** Builds a normal login, adding BungeeCord forwarding only for an explicit clientIp test. */
export function connectionOptions(host, port, version, id, clientIp) {
  const options = {
    host,
    port,
    username: id.name,
    auth: 'offline',
    version,
    checkTimeoutInterval: LOGIN_TIMEOUT_MILLIS,
    // mineflayer's default console.logs bot errors, and stdout is the protocol channel.
    logErrors: false,
  };
  if (clientIp && clientIp.trim()) {
    options.fakeHost = addressField(host, clientIp.trim(), id);
  }
  return options;
}

function position(bot) {
  const at = bot.entity?.position;
  return at ? { x: at.x, y: at.y, z: at.z } : { x: 0, y: 0, z: 0 };
}

/**
 * Waits until the bot has stopped falling.
 *
 * A bot that answers `spawn` mid-fall reports a position it is about to leave, and every
 * coordinate assertion downstream inherits the error. The Java runner settles the same way, with
 * the same numbers.
 */
async function settle(bot, name) {
  const deadline = Date.now() + SETTLE_TIMEOUT_MILLIS;
  let lastY = Number.NaN;
  let settledChecks = 0;

  while (Date.now() < deadline) {
    const at = bot.entity?.position;
    if (at) {
      if (Math.abs(at.y - lastY) < 1.0e-6) {
        if (++settledChecks >= SETTLED_CHECKS) {
          return;
        }
      } else {
        settledChecks = 0;
        lastY = at.y;
      }
    }
    await delay(SETTLE_POLL_MILLIS);
  }

  const at = bot.entity?.position;
  throw new Error(
    `Bot ${name} never settled within ${SETTLE_TIMEOUT_MILLIS}ms; last position `
      + (at ? `${at.x}, ${at.y}, ${at.z}` : 'unknown'),
  );
}

/**
 * Waits until the bot's client knows the world it is standing in.
 *
 * `spawn` fires on the position packet, which can arrive before the chunk does. A bot that acts in
 * that gap sends block actions against blocks it has never been told about, and the server answers
 * with nothing at all — indistinguishable, from the caller's side, from a plugin cancelling the
 * action silently. A dogfooding round spent most of itself on that ambiguity
 * (dogfood/JOURNAL.md, 2026-08-23).
 *
 * This closes the client half. The server half — a plugin, or Paper itself, dropping interactions
 * from a player who has only just joined — cannot be waited out from here, and is why
 * `breakBlock` reports whether the server acknowledged the dig.
 */
async function worldKnown(bot, name) {
  const deadline = Date.now() + WORLD_TIMEOUT_MILLIS;

  while (Date.now() < deadline) {
    const at = bot.entity?.position;
    if (at && bot.blockAt(at.offset(0, -1, 0))) {
      return;
    }
    await delay(SETTLE_POLL_MILLIS);
  }

  throw new Error(
    `Bot ${name} joined but its client was never sent the world around it within `
      + `${WORLD_TIMEOUT_MILLIS}ms`,
  );
}

/** Resolves when the bot is in the world; rejects on a kick, an error, or the timeout. */
function joined(bot, name, progress) {
  return new Promise((resolve, reject) => {
    const finish = (settleFn, value) => {
      clearTimeout(timer);
      bot.removeListener('spawn', onSpawn);
      bot.removeListener('kicked', onKicked);
      bot.removeListener('error', onError);
      settleFn(value);
    };

    const onSpawn = () => finish(resolve);
    const onKicked = (reason) => finish(reject, new Error(`Bot ${name} was kicked: ${describe(reason)}`));
    const onError = (error) => finish(reject, error);

    const timer = setTimeout(
      () => finish(reject, new Error(
        `Bot ${name} did not join within ${LOGIN_TIMEOUT_MILLIS}ms `
          + `(${describeProgress(bot, progress)})`,
      )),
      LOGIN_TIMEOUT_MILLIS,
    );

    bot.once('spawn', onSpawn);
    bot.once('kicked', onKicked);
    bot.once('error', onError);
  });
}

/** A kick reason is chat JSON as often as a string, and both have to end up readable. */
function describe(reason) {
  if (typeof reason === 'string') {
    return reason;
  }
  try {
    return JSON.stringify(reason);
  } catch {
    return String(reason);
  }
}

/** Closing an already-closed socket must not be the reason a shutdown fails. */
function quietly(action) {
  try {
    action();
  } catch {
    // Intentionally ignored.
  }
}

function delay(millis) {
  return new Promise((resolve) => setTimeout(resolve, millis));
}
