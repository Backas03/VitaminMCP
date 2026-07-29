package moe.vitamin.minecraft.mcp.bot.core;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Who a bot claims to be.
 *
 * <p>The UUID is derived from the name rather than generated, and that is the whole point.
 * Permissions, scoreboards, LuckPerms groups, homes and playtime are all keyed by UUID, so a
 * bot that gets a fresh one on every run starts every test from a different permission state
 * and any failure that depends on it is unreproducible. Deriving it means "Tester1" is the
 * same player on Tuesday as it was on Monday.
 *
 * <p>The derivation matches what a server computes for an offline player —
 * {@code OfflinePlayer:<name>} hashed — so a bot connecting through the forwarding handshake
 * lands on the same identity it would have had on a plain offline server. Tests written
 * against one work against the other.
 *
 * <p>An explicit UUID can still be supplied, which is what makes it possible to reproduce a
 * specific premium account's identity without owning it (docs/design.md §3.1).
 */
public record BotIdentity(String name, UUID uuid, String propertiesJson) {

    /** Longest name the protocol allows. */
    private static final int MAX_NAME_LENGTH = 16;

    public BotIdentity {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(uuid, "uuid");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "name must be at most " + MAX_NAME_LENGTH + " characters but was: " + name);
        }
        // An empty array is the correct "no skin" value; null would serialise as the string
        // "null" into the handshake and be rejected.
        propertiesJson = propertiesJson == null || propertiesJson.isBlank() ? "[]" : propertiesJson;
    }

    /** A bot whose UUID is derived from its name, with no skin properties. */
    public static BotIdentity of(String name) {
        return new BotIdentity(name, offlineUuid(name), "[]");
    }

    /** A bot with an explicitly chosen UUID — for reproducing a specific account's identity. */
    public static BotIdentity of(String name, UUID uuid) {
        return new BotIdentity(name, uuid, "[]");
    }

    /**
     * The UUID a server assigns an offline player of this name.
     *
     * <p>{@code UUID.nameUUIDFromBytes} is MD5-based (a version 3 UUID), which is exactly what
     * vanilla does here. It is not a security decision and must not be "upgraded" to something
     * stronger: matching the server's computation bit for bit is the requirement.
     */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The UUID without dashes.
     *
     * <p>The form BungeeCord-style forwarding puts on the wire. Sending the dashed form makes
     * the backend reject the handshake.
     */
    public String undashedUuid() {
        return uuid.toString().replace("-", "");
    }
}
