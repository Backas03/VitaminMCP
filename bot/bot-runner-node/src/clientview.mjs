import { randomUUID } from 'node:crypto';

import { menu } from './actions.mjs';
import { plainText, stripLegacyCodes, untag } from './text.mjs';

/**
 * Everything the client knows that the server will not report.
 *
 * This is why the runner exists rather than the agent doing everything: a plugin that draws its
 * GUI with packets leaves no server-side inventory, and a refusal — "you lack permission" — reaches
 * the player and nothing else. From the agent's side, refused and silently ignored look the same.
 *
 * Boss bars and the sidebar are tracked from raw packets rather than through mineflayer's own
 * helpers, for two reasons. mineflayer has no handler at all for the action bar packet on modern
 * versions — it only raises `actionBar` for system chat at position 2, which is a different route
 * the server does not use for `/title … actionbar`. And its scoreboard tracking arrives empty here.
 * Reading the packets directly is also what the Java runner does, so the two stay comparable.
 */

/** How many messages to keep, matching the Java runner. */
const MAX_MESSAGES = 100;

const state = new WeakMap();

/**
 * The active message stream for each bot name.
 *
 * Each client connection gets a random id, so a cursor cannot be mistaken for one from another
 * session, runner process or same-named replacement bot.
 */
const messageStreamsByName = new Map();

/**
 * Starts collecting what the server tells this bot.
 *
 * Must be called as soon as the bot exists: this is all events, so anything said before the
 * listeners attach is gone, and a plugin greets or refuses within a tick of the bot arriving.
 */
export function collect(bot, name) {
  const previous = messageStreamsByName.get(name);
  if (previous) previous.activeCollector = null;

  const stream = { id: randomUUID(), nextSequence: 0, activeCollector: null };

  const own = {
    messages: [],
    bossBars: new Map(),
    objectiveTitles: new Map(),
    objectiveScores: new Map(),
    sidebar: null,
    messageStream: stream,
  };
  stream.activeCollector = own;
  messageStreamsByName.set(name, stream);
  state.set(bot, own);

  const remember = (text) => {
    // Closing a replaced bot is asynchronous. Ignore anything its socket delivers after the new
    // bot took over, otherwise an invisible message would consume a sequence and look like loss.
    if (stream.activeCollector !== own) {
      return;
    }
    if (text == null || String(text).trim() === '') {
      return;
    }
    const sequence = stream.nextSequence;
    stream.nextSequence += 1;
    own.messages.push({ sequence, timestamp: Date.now(), text: String(text) });
    while (own.messages.length > MAX_MESSAGES) {
      own.messages.shift();
    }
  };

  bot.on('messagestr', (message, position) => {
    // 'game_info' is the action bar arriving as system chat. It is handled below with the prefix
    // that says where it appeared, so taking it here too would double every action bar message.
    if (position !== 'game_info') {
      remember(message);
    }
  });

  // A plugin is as likely to refuse above the hotbar as in chat, so where the text appeared is
  // part of the message rather than something the caller has to infer.
  bot._client.on('action_bar', (packet) => remember(overlay('action bar', packet.text)));
  bot.on('actionBar', (message) => remember(overlay('action bar', message)));
  bot.on('title', (text, kind) => remember(overlay(kind, text)));

  bot._client.on('boss_bar', (packet) => trackBossBar(own, packet));
  bot._client.on('scoreboard_objective', (packet) => {
    // 0 add, 1 remove, 2 update.
    if (packet.action === 1) {
      own.objectiveTitles.delete(packet.name);
      own.objectiveScores.delete(packet.name);
      return;
    }
    own.objectiveTitles.set(packet.name, plainText(packet.displayText) || packet.name);
  });
  bot._client.on('scoreboard_display_objective', (packet) => {
    // Position 1 is the sidebar; 0 is the player list and 2 is under the name.
    own.sidebar = packet.position === 1 ? packet.name : (own.sidebar === packet.name ? null : own.sidebar);
  });
  bot._client.on('scoreboard_score', (packet) => {
    const scores = own.objectiveScores.get(packet.scoreName) ?? new Map();
    scores.set(packet.itemName, {
      value: packet.value,
      display: packet.display_name == null ? null : plainText(packet.display_name),
    });
    own.objectiveScores.set(packet.scoreName, scores);
  });
  bot._client.on('reset_score', (packet) => {
    own.objectiveScores.get(packet.scoreName)?.delete(packet.itemName);
  });
}

/** Everything the client was told, in the shape `RunnerDispatch` writes. */
export function inspect(bot, name) {
  const own = state.get(bot) ?? { messages: [], bossBars: new Map() };
  const stream = own.messageStream ?? messageStreamsByName.get(name);
  return {
    menu: menu(bot, name),
    items: menuItems(bot),
    messages: [...own.messages],
    nextMessageSequence: stream?.nextSequence ?? 0,
    messageStreamId: stream?.id ?? '',
    bossBars: [...own.bossBars.values()],
    scoreboard: sidebarOf(own),
    health: Number.isFinite(bot.health) ? bot.health : null,
    food: Number.isInteger(bot.food) ? bot.food : null,
    experienceLevel: Number.isInteger(bot.experience?.level) ? bot.experience.level : null,
    totalExperience: Number.isInteger(bot.experience?.points) ? bot.experience.points : null,
    experienceProgress: Number.isFinite(bot.experience?.progress) ? bot.experience.progress : null,
    effects: Object.values(bot.entity?.effects ?? {}).map((effect) => {
      const nameOf = bot.registry.effectsById?.[effect.id]?.name ?? String(effect.id);
      return `${nameOf}:${effect.amplifier}:${effect.duration}`;
    }),
  };
}

/**
 * The open menu's slots as the client received them, one entry per occupied slot.
 *
 * `itemId` is a namespaced name here — `minecraft:diamond_sword` — where the Java runner reported
 * the protocol's numeric id. Decided 2026-08-22; DIFFERENCES.md carries the reasoning. The numeric
 * id is a different number in every protocol version, so it was never something a scenario could
 * be written against in the first place.
 */
function menuItems(bot) {
  const window = bot.currentWindow;
  if (!window) {
    return [];
  }

  const items = [];
  for (let slot = 0; slot < window.slots.length; slot += 1) {
    const item = window.slots[slot];
    if (!item) {
      continue;
    }
    items.push({
      slot,
      itemId: identifierOf(item),
      amount: item.count,
      name: plainText(item.customName ?? ''),
      customModelData: modelDataOf(item),
      lore: loreOf(item),
    });
  }
  return items;
}

function trackBossBar(own, packet) {
  const uuid = packet.entityUUID;
  const existing = own.bossBars.get(uuid);

  switch (packet.action) {
    case 0:
      own.bossBars.set(uuid, {
        title: plainText(packet.title),
        progress: packet.health,
        color: colourName(packet.color),
      });
      break;
    case 1:
      own.bossBars.delete(uuid);
      break;
    case 2:
      if (existing) existing.progress = packet.health;
      break;
    case 3:
      if (existing) existing.title = plainText(packet.title);
      break;
    case 4:
      if (existing) existing.color = colourName(packet.color);
      break;
    default:
      break;
  }
}

/** The Java runner reports the protocol enum's own name, which is upper case. */
const BOSS_BAR_COLOURS = ['PINK', 'BLUE', 'RED', 'GREEN', 'YELLOW', 'PURPLE', 'WHITE'];

function colourName(index) {
  return BOSS_BAR_COLOURS[index] ?? '';
}

/** The sidebar scoreboard, or null when nothing is displayed there. */
function sidebarOf(own) {
  const objective = own.sidebar;
  if (!objective) {
    return null;
  }
  const scores = own.objectiveScores?.get(objective) ?? new Map();

  return {
    title: own.objectiveTitles?.get(objective) ?? objective,
    // Highest score first, which is the order the client draws them in.
    lines: [...scores.entries()]
      .sort((a, b) => b[1].value - a[1].value)
      .map(([entry, score]) => score.display ?? stripLegacyCodes(entry)),
  };
}

/** `minecraft:diamond_sword`, from whichever spelling prismarine-item offers. */
function identifierOf(item) {
  const name = item.name ?? '';
  return name.includes(':') ? name : `minecraft:${name}`;
}

/** The lore lines joined with ` | `, or empty — the Java runner's separator. */
function loreOf(item) {
  const lore = untag(item.customLore);
  if (lore == null) {
    return '';
  }
  const lines = Array.isArray(lore) ? lore : [lore];
  return lines.map(plainText).join(' | ');
}

/** The model data selector: string keys preferred over the first float, or empty. */
function modelDataOf(item) {
  const model = untag(componentValue(item, 'custom_model_data'));
  if (model == null) {
    return '';
  }
  if (typeof model === 'number') {
    // Before the component rewrite this was a bare number.
    return String(model);
  }
  if (Array.isArray(model.strings) && model.strings.length > 0) {
    return model.strings.join(',');
  }
  if (Array.isArray(model.floats) && model.floats.length > 0) {
    return String(model.floats[0]);
  }
  return '';
}

/** One data component off an item, whichever shape prismarine-item is carrying it in. */
function componentValue(item, type) {
  const components = item.components ?? item.componentPatch;
  if (!components) {
    return null;
  }
  if (Array.isArray(components)) {
    return components.find((component) => component?.type === type)?.data ?? null;
  }
  return components[type] ?? null;
}

/** `[action bar] …`, or null when there is nothing to report. */
function overlay(where, text) {
  const plain = plainText(text);
  return plain.trim() === '' ? null : `[${where}] ${plain}`;
}
