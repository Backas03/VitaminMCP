import { javaDouble } from './protocol.mjs';

/**
 * What a bot can do once it is in the world.
 *
 * A port of `BotActions.java`, and mostly at packet level rather than through mineflayer's
 * convenience methods. That is deliberate: `bot.dig` and `bot.activateEntity` look at the target
 * first and wait for the result, which is more correct than what the Java runner does and would
 * therefore be a behaviour change. Parity comes first; the better behaviour is a later decision,
 * taken on purpose and written down.
 */

/** Direction, in Minecraft's own order. */
const FACES = { down: 0, up: 1, north: 2, south: 3, west: 4, east: 5 };

/** PlayerAction. */
const START_DIGGING = 0;
const FINISH_DIGGING = 2;

/** Hand. */
const MAIN_HAND = 0;

/** InteractAction, as the `mouse` field spells it. */
const INTERACT = 0;
const INTERACT_AT = 2;

/** No entity matched. */
const NO_ENTITY = -1;

/** Block interactions sent by each bot, which the server uses to acknowledge them in order. */
const sequences = new WeakMap();

function nextSequence(bot) {
  const next = (sequences.get(bot) ?? 0) + 1;
  sequences.set(bot, next);
  return next;
}

/** The same guard `BotActions.require()` applies, and the same words. */
function requireInWorld(bot, name) {
  if (!bot.entity || !bot._client?.socket?.writable) {
    throw new Error(`Bot ${name} is not in the world`);
  }
}

export function breakBlock(bot, name, x, y, z) {
  requireInWorld(bot, name);
  const location = { x, y, z };

  bot._client.write('block_dig', {
    status: START_DIGGING,
    location,
    face: FACES.up,
    sequence: nextSequence(bot),
  });
  bot._client.write('block_dig', {
    status: FINISH_DIGGING,
    location,
    face: FACES.up,
    sequence: nextSequence(bot),
  });
}

/** Runs a command as the bot. The leading slash is not part of the packet. */
export function command(bot, name, line) {
  requireInWorld(bot, name);
  bot._client.write('chat_command', {
    command: line.startsWith('/') ? line.slice(1) : line,
    timestamp: BigInt(Date.now()),
    salt: 0n,
    argumentSignatures: [],
    messageCount: 0,
    acknowledged: Buffer.alloc(3),
    checksum: 0,
  });
}

/**
 * Says something in chat.
 *
 * Through `/say`, exactly as the Java runner does it. A bot cannot send a signed chat message
 * without a Mojang key, and an unsigned one is refused from 1.19 on, so the command is the only
 * route that reaches the same listeners.
 */
export function chat(bot, name, message) {
  command(bot, name, `say ${message}`);
}

/** Right-clicks a block — which is how a container or a plugin menu gets opened. */
export function useBlock(bot, name, x, y, z, face) {
  requireInWorld(bot, name);

  const direction = !face || !face.trim() ? FACES.up : FACES[face.trim().toLowerCase()];
  if (direction === undefined) {
    throw new Error(
      `Unknown face '${face}'. Use ${Object.keys(FACES).join(', ')}.`,
    );
  }

  bot._client.write('block_place', {
    hand: MAIN_HAND,
    location: { x, y, z },
    direction,
    cursorX: 0.5,
    cursorY: 0.5,
    cursorZ: 0.5,
    insideBlock: false,
    worldBorderHit: false,
    sequence: nextSequence(bot),
  });
}

/** Right-clicks the nearest entity to a point, and returns which one it was. */
export function useEntity(bot, name, x, y, z, radius, type) {
  requireInWorld(bot, name);

  const entityId = entityNear(bot, x, y, z, radius, type);
  if (entityId === NO_ENTITY) {
    const nearby = describeEntitiesNear(bot, x, y, z, radius);
    throw new Error(
      `no ${!type || !type.trim() ? 'entity' : type} within ${javaDouble(radius)} blocks of `
        + `${javaDouble(x)} ${javaDouble(y)} ${javaDouble(z)}`
        + (nearby === ''
          ? '. The bot has been told about no entities near there at all — check the coordinates,'
            + ' and that the bot is close enough to have them in view.'
          : `. Nearby: ${nearby}`),
    );
  }

  // Both packets, in this order, because that is what a real client sends and what the Java
  // runner reproduces: a plugin listening only for the second sees nothing without the first.
  bot._client.write('use_entity', {
    target: entityId,
    mouse: INTERACT_AT,
    x: 0.0,
    y: 1.0,
    z: 0.0,
    hand: MAIN_HAND,
    sneaking: false,
  });
  bot._client.write('use_entity', {
    target: entityId,
    mouse: INTERACT,
    hand: MAIN_HAND,
    sneaking: false,
  });
  return entityId;
}

/** Clicks a slot in the menu the server has open for this bot. */
export async function clickSlot(bot, name, slot, click) {
  requireInWorld(bot, name);

  if (!bot.currentWindow) {
    throw new Error(
      `Bot ${name} has no menu open, so slot ${slot} cannot be clicked. Wait for inventory_open `
        + 'first — a menu does not open synchronously with the command that causes it.',
    );
  }

  let mouseButton;
  let mode;
  switch ((click ?? 'left').toLowerCase()) {
    case 'left':
      [mouseButton, mode] = [0, 0];
      break;
    case 'right':
      [mouseButton, mode] = [1, 0];
      break;
    case 'shift_left':
      [mouseButton, mode] = [0, 1];
      break;
    case 'shift_right':
      [mouseButton, mode] = [1, 1];
      break;
    default:
      throw new Error(
        `Unknown click '${click}'. Use left, right, shift_left or shift_right.`,
      );
  }

  // Through mineflayer rather than by hand: the click packet carries a state id and hashed slot
  // contents whose shape moves between versions, which is the pain this runner exists to avoid.
  await bot.clickWindow(slot, mouseButton, mode);
}

/** Closes the open menu. Doing it when nothing is open is not an error. */
export function closeMenu(bot, name) {
  requireInWorld(bot, name);
  if (bot.currentWindow) {
    bot.closeWindow(bot.currentWindow);
  }
}

/** The menu the client was told about, or null when none is open. */
export function menu(bot, name) {
  requireInWorld(bot, name);
  const window = bot.currentWindow;
  return window ? { containerId: window.id, title: plainText(window.title) } : null;
}

/** The nearest tracked entity to a point, or {@link NO_ENTITY}. */
function entityNear(bot, x, y, z, radius, type) {
  let best = null;
  let bestDistance = Number.MAX_VALUE;

  for (const entity of Object.values(bot.entities)) {
    if (entity === bot.entity) {
      continue;
    }
    if (type && type.trim() && !matchesType(entity, type.trim())) {
      continue;
    }
    const distance = distanceTo(entity, x, y, z);
    if (distance <= radius && distance < bestDistance) {
      best = entity;
      bestDistance = distance;
    }
  }
  return best === null ? NO_ENTITY : best.id;
}

/** What is actually near a point, for when nothing matched. */
function describeEntitiesNear(bot, x, y, z, radius) {
  return Object.values(bot.entities)
    .filter((entity) => entity !== bot.entity && distanceTo(entity, x, y, z) <= Math.max(radius * 4, 16))
    .sort((a, b) => distanceTo(a, x, y, z) - distanceTo(b, x, y, z))
    .slice(0, 8)
    .map((entity) => {
      const at = entity.position;
      return `${typeOf(entity)} at ${fixed(at.x)} ${fixed(at.y)} ${fixed(at.z)} `
        + `(${fixed(distanceTo(entity, x, y, z))} away)`;
    })
    .join('; ');
}

function distanceTo(entity, x, y, z) {
  const at = entity.position;
  if (!at) {
    return Number.MAX_VALUE;
  }
  return Math.sqrt((at.x - x) ** 2 + (at.y - y) ** 2 + (at.z - z) ** 2);
}

/** The Java runner reports the protocol's entity type name; mineflayer spells it in lower case. */
function typeOf(entity) {
  return entity.name ?? entity.displayName ?? entity.entityType ?? '';
}

function matchesType(entity, type) {
  const wanted = type.toLowerCase();
  return [entity.name, entity.displayName, entity.entityType]
    .some((candidate) => typeof candidate === 'string' && candidate.toLowerCase() === wanted);
}

/** Java formats these with %.1f, which rounds half away from zero rather than to even. */
function fixed(value) {
  return value.toFixed(1);
}

/** A window title arrives as chat JSON as often as a string, and both have to end up readable. */
export function plainText(title) {
  if (title == null) {
    return '';
  }
  if (typeof title === 'string') {
    // prismarine-windows hands over the raw JSON string for a chat component.
    if (title.startsWith('{') || title.startsWith('[')) {
      try {
        return plainText(JSON.parse(title));
      } catch {
        return title;
      }
    }
    return title;
  }
  if (Array.isArray(title)) {
    return title.map(plainText).join('');
  }
  if (typeof title === 'object') {
    const own = typeof title.text === 'string' ? title.text : '';
    const translated = own === '' && typeof title.translate === 'string' ? title.translate : '';
    const extra = Array.isArray(title.extra) ? title.extra.map(plainText).join('') : '';
    return own + translated + extra;
  }
  return String(title);
}
