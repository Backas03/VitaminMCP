/**
 * The line protocol between the MCP server and this runner.
 *
 * A port of `RunnerProtocol.java`, and it has to stay one: the Java side is the only reader, and
 * an MCP tool's output is assembled from these fields. Nothing here may drift.
 */

/** Field separator. */
export const SEPARATOR = '\t';

/** Separates repeated records inside one field — menu slots, received messages. */
export const RECORD_SEPARATOR = '';

/** Separates the fields of one such record. */
export const UNIT_SEPARATOR = '';

export const SPAWN = 'spawn';
export const DESPAWN = 'despawn';
export const MOVE = 'move';
export const BREAK = 'break';
export const COMMAND = 'command';
export const CHAT = 'chat';
export const POSITION = 'position';
export const SHUTDOWN = 'shutdown';
export const USE = 'use';
export const USE_ENTITY = 'use_entity';
export const CLICK = 'click';
export const CLOSE_MENU = 'close_menu';
export const MENU = 'menu';
export const INSPECT = 'inspect';
export const ATTACK_ENTITY = 'attack_entity';
export const HOLD_ITEM = 'hold_item';
export const DROP_ITEM = 'drop_item';
export const PLACE_BLOCK = 'place_block';
export const JUMP = 'jump';
export const SNEAK = 'sneak';
export const SPRINT = 'sprint';
export const LOOK_AT = 'look_at';
export const ASSERT_REACHABLE = 'assert_reachable';

export const OK = 'ok';
export const ERROR = 'err';

/** Printed once the runner is ready to accept commands. */
export const READY = 'ready';

export function encode(...fields) {
  return fields.join(SEPARATOR);
}

export function decode(line) {
  return line.split(SEPARATOR);
}

/** Splits a field holding repeated records. */
export function records(field) {
  return !field ? [] : field.split(RECORD_SEPARATOR);
}

/** Splits one record into its fields. */
export function fields(record) {
  return record.split(UNIT_SEPARATOR);
}

/** Strips every character this protocol gives meaning to. */
export function sanitize(text) {
  return text == null
    ? ''
    : String(text).replace(/[\t\n\r]/g, ' ');
}

/**
 * A double spelled the way `Double.toString` spells it.
 *
 * The Java runner writes coordinates with `String.valueOf(double)`, which always leaves a decimal
 * point — `79.0`, never `79` — and switches to scientific notation outside
 * [1e-3, 1e7). JavaScript agrees on neither, so a bot standing at y=79 would be reported as `79`
 * by this runner and `79.0` by the other, and the two runners could not be diffed byte for byte.
 *
 * 1e7 is not a hypothetical here: the world border reaches 3e7, which Java prints as `3.0E7`.
 */
export function javaDouble(value) {
  return javaFloatingPoint(value, (candidate) => candidate);
}

/**
 * A float spelled the way `Float.toString` spells it.
 *
 * Boss bar progress is a `float` on the Java side, and `Float.toString` prints the shortest
 * decimal that round-trips through 32 bits — `0.35`. The same bits widened to a JavaScript number
 * are 0.3499999940395355, and printing that is both wrong and unreadable. `Math.fround` is what
 * makes the round trip test the right width.
 */
export function javaFloat(value) {
  return javaFloatingPoint(value, Math.fround);
}

function javaFloatingPoint(value, narrow) {
  if (Number.isNaN(value)) {
    return 'NaN';
  }
  if (!Number.isFinite(value)) {
    return value > 0 ? 'Infinity' : '-Infinity';
  }
  if (value === 0) {
    // Java keeps the sign of negative zero, and so does 1 / -0 here.
    return Object.is(value, -0) ? '-0.0' : '0.0';
  }

  const sign = value < 0 ? '-' : '';
  const magnitude = Math.abs(value);

  const [digits, exponent] = shortestDigits(magnitude, narrow);

  if (magnitude >= 1e-3 && magnitude < 1e7) {
    // Plain notation: exponent is the number of digits before the point.
    if (exponent <= 0) {
      return `${sign}0.${'0'.repeat(-exponent)}${digits}`;
    }
    if (exponent >= digits.length) {
      return `${sign}${digits}${'0'.repeat(exponent - digits.length)}.0`;
    }
    return `${sign}${digits.slice(0, exponent)}.${digits.slice(exponent)}`;
  }

  // Computerised scientific notation: one digit, a point, at least one more digit, then E.
  const mantissa = digits.length === 1 ? `${digits}.0` : `${digits[0]}.${digits.slice(1)}`;
  return `${sign}${mantissa}E${exponent - 1}`;
}

/**
 * The significant digits of a positive finite number, and the decimal exponent such that the
 * value is `0.<digits> * 10^exponent`.
 *
 * "Shortest that round-trips" is what both Java and JavaScript promise, but they promise it at
 * different widths, so the round trip has to be tested at the width being printed — `narrow` is
 * what makes that a float rather than a double.
 */
function shortestDigits(magnitude, narrow) {
  const target = narrow(magnitude);
  let exponential = magnitude.toExponential();

  for (let digits = 1; digits <= 17; digits += 1) {
    const candidate = magnitude.toExponential(digits - 1);
    if (narrow(Number(candidate)) === target) {
      exponential = candidate;
      break;
    }
  }

  const [mantissa, exponentPart] = exponential.split('e');
  const digits = mantissa.replace('.', '').replace(/0+$/, '') || '0';
  return [digits, Number(exponentPart) + 1];
}
