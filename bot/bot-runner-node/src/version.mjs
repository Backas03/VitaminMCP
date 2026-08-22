import minecraftData from 'minecraft-data';
import mc from 'minecraft-protocol';

/** Long enough for a server still finishing its first tick, short enough to fail. */
export const PING_TIMEOUT_MILLIS = 10_000;

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
    mc.ping({ host, port, closeTimeout: timeoutMillis }, (error, result) => {
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
