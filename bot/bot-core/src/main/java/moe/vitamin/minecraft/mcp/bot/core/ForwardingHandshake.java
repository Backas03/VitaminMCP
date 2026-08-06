package moe.vitamin.minecraft.mcp.bot.core;

import java.util.Objects;

/** Builds the server address field that carries a bot's identity to the backend. */
public final class ForwardingHandshake {

    /** Separator BungeeCord-style forwarding uses inside the address field. */
    static final char SEPARATOR = '\0';

    private ForwardingHandshake() {}

    /** Assembles the address field. */
    public static String addressField(String host, String clientIp, BotIdentity identity) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(clientIp, "clientIp");
        Objects.requireNonNull(identity, "identity");

        if (host.indexOf(SEPARATOR) >= 0 || clientIp.indexOf(SEPARATOR) >= 0) {

            throw new IllegalArgumentException("host and clientIp must not contain a NUL separator");
        }

        String field = host
                + SEPARATOR + clientIp
                + SEPARATOR + identity.undashedUuid();

        if (!identity.propertiesJson().equals("[]")) {
            field += SEPARATOR + identity.propertiesJson();
        }
        return field;
    }

    /** Splits an address field back into its parts. */
    public static String[] parse(String addressField) {
        Objects.requireNonNull(addressField, "addressField");
        return addressField.split(String.valueOf(SEPARATOR), -1);
    }
}
