import { createHash } from 'node:crypto';

/** Separator BungeeCord-style forwarding uses inside the address field. */
const SEPARATOR = '\0';

/** Longest name the protocol allows. */
const MAX_NAME_LENGTH = 16;

/**
 * The UUID a server assigns an offline player of this name.
 *
 * Java's `UUID.nameUUIDFromBytes` with no algorithm named is a version 3 (MD5) name-based UUID,
 * so the version and variant nibbles have to be stamped by hand to match it byte for byte. The
 * whole point of deriving the UUID from the name is that the same name is the same player every
 * run, and that only holds if this agrees with the Java runner exactly.
 */
export function offlineUuid(name) {
  const digest = createHash('md5').update(`OfflinePlayer:${name}`, 'utf8').digest();
  digest[6] = (digest[6] & 0x0f) | 0x30;
  digest[8] = (digest[8] & 0x3f) | 0x80;

  const hex = digest.toString('hex');
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20),
  ].join('-');
}

/** Who a bot claims to be. */
export function identity(name, { uuid, propertiesJson } = {}) {
  if (!name || !name.trim()) {
    throw new Error('name must not be blank');
  }
  if (name.length > MAX_NAME_LENGTH) {
    throw new Error(`name must be at most ${MAX_NAME_LENGTH} characters but was: ${name}`);
  }
  const properties = propertiesJson?.trim() ? propertiesJson : '[]';
  return { name, uuid: uuid ?? offlineUuid(name), propertiesJson: properties };
}

/** The UUID without dashes, which is how BungeeCord writes it. */
export function undashed(uuid) {
  return uuid.replaceAll('-', '');
}

/**
 * Assembles the handshake's server address field, which is what carries a bot's identity.
 *
 * A backend on `online-mode=false` with `settings.bungeecord: true` reads the identity from here
 * rather than authenticating, which is what makes an arbitrary UUID and a chosen client address
 * possible at all (design.md 3.1).
 */
export function addressField(host, clientIp, id) {
  if (host.includes(SEPARATOR) || clientIp.includes(SEPARATOR)) {
    throw new Error('host and clientIp must not contain a NUL separator');
  }
  const field = [host, clientIp, undashed(id.uuid)].join(SEPARATOR);
  return id.propertiesJson === '[]' ? field : field + SEPARATOR + id.propertiesJson;
}
