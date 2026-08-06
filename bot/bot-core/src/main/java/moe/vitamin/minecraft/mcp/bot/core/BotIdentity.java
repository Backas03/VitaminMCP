package moe.vitamin.minecraft.mcp.bot.core;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Who a bot claims to be. */
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

    /** The UUID a server assigns an offline player of this name. */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** The UUID without dashes. */
    public String undashedUuid() {
        return uuid.toString().replace("-", "");
    }
}
