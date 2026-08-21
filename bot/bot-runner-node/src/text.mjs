/**
 * Turning whatever the protocol handed over into the plain text a player would read.
 *
 * Three shapes arrive here, often nested inside one another, and none of them is documented in a
 * place worth linking:
 *
 * - **NBT tagged values** — `{ type: 'string', value: '…' }`. Since the 1.20.3 chat rewrite the
 *   protocol carries components as NBT, and prismarine hands them over tagged rather than plain.
 * - **JSON strings** — `'{"text":"Test Blade"}'`, because a command that sets `custom_name` stores
 *   the component as a string and the server passes it along untouched.
 * - **Chat components** — `{ text, extra, translate, with }`.
 *
 * An item's custom name is all three at once: an NBT string whose value is JSON describing a
 * component. Reading only the outermost layer returns an empty string and looks like a plugin that
 * set no name.
 */

/** NBT tag names, so a tagged value can be told from a component that happens to have a type. */
const NBT_TYPES = new Set([
  'compound', 'list', 'string', 'byte', 'short', 'int', 'long', 'float', 'double',
  'byteArray', 'intArray', 'longArray', 'shortArray', 'end',
]);

/** Strips prismarine's NBT tagging, however deeply it nests. */
export function untag(value) {
  if (value == null || typeof value !== 'object') {
    return value;
  }
  if (Array.isArray(value)) {
    return value.map(untag);
  }
  if (typeof value.type === 'string' && NBT_TYPES.has(value.type) && 'value' in value) {
    return untag(value.value);
  }

  const out = {};
  for (const [key, nested] of Object.entries(value)) {
    out[key] = untag(nested);
  }
  return out;
}

/**
 * The plain text of a chat component, an NBT tagged value, a JSON string, or a plain string.
 *
 * A translatable component renders as its key — `container.chest` — which is what Adventure's
 * plain-text serializer does on the Java side when no translations are registered, and so is what
 * the Java runner reports.
 */
export function plainText(value) {
  return render(untag(value));
}

function render(value) {
  if (value == null) {
    return '';
  }
  if (typeof value === 'string') {
    if (value.startsWith('{') || value.startsWith('[') || value.startsWith('"')) {
      try {
        return render(JSON.parse(value));
      } catch {
        return value;
      }
    }
    return value;
  }
  if (typeof value !== 'object') {
    return String(value);
  }
  if (Array.isArray(value)) {
    return value.map(render).join('');
  }

  let out = '';
  if (typeof value.text === 'string') {
    out += value.text;
  } else if (typeof value.translate === 'string') {
    out += value.translate;
  }
  if (Array.isArray(value.extra)) {
    out += value.extra.map(render).join('');
  }
  return out;
}

/** Legacy section-sign colour codes, which a scoreboard entry still carries. */
export function stripLegacyCodes(text) {
  return text.replace(/§[0-9a-fk-orA-FK-OR]/g, '');
}
