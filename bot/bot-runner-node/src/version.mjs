import minecraftData from 'minecraft-data';
import mc from 'minecraft-protocol';

/** Long enough for a server still finishing its first tick, short enough to fail. */
export const PING_TIMEOUT_MILLIS = 10_000;

/**
 * The version whose packet definitions a server-list ping is written with.
 *
 * A ping happens before anything knows what the server speaks, so minecraft-protocol otherwise
 * falls back to the newest version it has ever heard of and loads that entire data set — blocks,
 * items, the lot — to send a handshake and read a status string. Naming a version here skips that,
 * and it is safe because handshake and status have not changed shape since 1.7: the server answers
 * with its own protocol number whatever we send.
 *
 * It must be a version the SEA build bundles, so keep it on the supported line — see
 * `scripts/slim-minecraft-data.mjs`, which is what makes the others absent.
 */
export const PING_VERSION = '1.21';

/**
 * The protocol number a server speaks, asked without speaking it.
 *
 * The old launcher also pinged for exactly this reason: the protocol decides which data set to use,
 * and guessing it from a version string supplied by the caller is how a bot ends up failing at the
 * login packet instead of at startup.
 */
export function pingProtocol(host, port, timeoutMillis = PING_TIMEOUT_MILLIS) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(
      () => reject(new Error(`${host}:${port} did not answer a server-list ping within ${timeoutMillis}ms`)),
      timeoutMillis,
    );
    mc.ping({ host, port, closeTimeout: timeoutMillis, version: PING_VERSION }, (error, result) => {
      clearTimeout(timer);
      if (error) {
        reject(error);
        return;
      }
      const protocol = result?.version?.protocol;
      if (typeof protocol !== 'number') {
        reject(new Error(`${host}:${port} answered a ping without a protocol number`));
        return;
      }
      resolve(protocol);
    });
  });
}

/**
 * The version name to hand mineflayer for a protocol number.
 *
 * One protocol usually covers several Minecraft versions — 772 is both 1.21.7 and 1.21.8 — and
 * only some of them have data behind them. Pick a supported one rather than the first listed, or
 * the runner refuses a server it can in fact speak to.
 */
export function versionForProtocol(protocol) {
  const candidates = minecraftData.postNettyVersionsByProtocolVersion?.pc?.[protocol] ?? [];
  const supported = new Set(mc.supportedVersions);

  for (const candidate of candidates) {
    if (supported.has(candidate.minecraftVersion)) {
      return candidate.minecraftVersion;
    }
  }

  if (candidates.length === 0) {
    throw new Error(
      `This runner has no data for protocol ${protocol}. minecraft-data does not know it, `
        + 'which usually means the dependency is older than the server.',
    );
  }
  throw new Error(
    `This runner cannot speak protocol ${protocol} (${candidates
      .map((candidate) => candidate.minecraftVersion)
      .join(', ')}). It supports ${mc.supportedVersions.join(', ')}.`,
  );
}

/**
 * The protocol a Minecraft version speaks, from minecraft-data's table of every release it has
 * heard of. That table is wider than the versions it carries data for, so a release the runner
 * cannot speak yet still has a number here.
 */
export function protocolForVersion(minecraftVersion) {
  return minecraftData.versionsByMinecraftVersion?.pc?.[minecraftVersion]?.version;
}

/**
 * Which protocol to speak: the one the agent says its server runs, over the one the ping answered.
 *
 * A ping is answered by whatever holds the port, and two common things answer it with the
 * protocol the ping was written in rather than the server's own: a proxy that echoes it, and
 * ViaVersion with ViaBackwards, which rewrites the status reply to the client's protocol whenever
 * it can translate. The ping is written in PING_VERSION, so either one made the runner join as
 * 1.21.1 — on a 26.x server, through a chain of translations, without a word. The agent runs inside
 * the server, and nothing answers for it.
 *
 * Without a version from the agent the ping decides, as it always did. `pinged` is returned only
 * when the two disagree, so the caller can say so.
 */
export function selectProtocol(pinged, serverVersion) {
  if (!serverVersion) {
    return { protocol: pinged };
  }
  const declared = protocolForVersion(serverVersion);
  if (declared === undefined) {
    throw new Error(
      `The agent reports Minecraft ${serverVersion}, which this runner's minecraft-data does not `
        + 'know: the dependency is older than the server.',
    );
  }
  return declared === pinged ? { protocol: declared } : { protocol: declared, pinged };
}
