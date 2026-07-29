package moe.vitamin.minecraft.mcp.bot.core;

import java.util.Objects;

/**
 * Builds the server address field that carries a bot's identity to the backend.
 *
 * <p>This is the mechanism the whole online-mode strategy rests on (docs/design.md §3.1). A
 * backend running {@code online-mode=false} with {@code settings.bungeecord: true} trusts
 * whatever a proxy tells it about the connecting player, and it is told through the one field
 * the handshake already has room for: the address the client thinks it dialled. Packing
 * additional NUL-separated fields into it lets a bot present any UUID and any signed skin
 * without authenticating to Mojang at all.
 *
 * <pre>
 *   host \0 clientIp \0 uuid-without-dashes \0 properties-json
 * </pre>
 *
 * <p>That trust is also why a server configured this way must never be reachable from the
 * internet: anyone who can open a socket to it can claim to be anyone. It is a test-harness
 * configuration, not a deployment one.
 */
public final class ForwardingHandshake {

    /** Separator BungeeCord-style forwarding uses inside the address field. */
    static final char SEPARATOR = '\0';

    private ForwardingHandshake() {}

    /**
     * Assembles the address field.
     *
     * @param host     the hostname the bot is dialling, as the backend should see it
     * @param clientIp the address the backend should attribute the connection to
     * @param identity who the bot claims to be
     */
    public static String addressField(String host, String clientIp, BotIdentity identity) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(clientIp, "clientIp");
        Objects.requireNonNull(identity, "identity");

        if (host.indexOf(SEPARATOR) >= 0 || clientIp.indexOf(SEPARATOR) >= 0) {
            // A NUL in either half would be read by the backend as a field boundary and shift
            // every field after it, quietly producing a different player than intended.
            throw new IllegalArgumentException("host and clientIp must not contain a NUL separator");
        }

        String field = host
                + SEPARATOR + clientIp
                + SEPARATOR + identity.undashedUuid();

        // BungeeCord appends the properties field only when there are properties, and the
        // backend accepts either three or four. Sending an empty array where the real proxy
        // sends nothing is a gratuitous difference from the thing being imitated.
        if (!identity.propertiesJson().equals("[]")) {
            field += SEPARATOR + identity.propertiesJson();
        }
        return field;
    }

    /**
     * Splits an address field back into its parts.
     *
     * <p>Exists so tests can assert on what the backend will actually parse, rather than on the
     * string this class happened to build.
     */
    public static String[] parse(String addressField) {
        Objects.requireNonNull(addressField, "addressField");
        return addressField.split(String.valueOf(SEPARATOR), -1);
    }
}
