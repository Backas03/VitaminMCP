package moe.vitamin.minecraft.mcp.bot.core.ping;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks a server what protocol it speaks, without speaking it.
 *
 * <p>This is the server list ping: a handshake with next-state 1, then a status request. The
 * shape of those two packets has not changed since 1.7, and the reply carries the server's own
 * protocol number — so one implementation covers every version the project will ever support,
 * and it needs no protocol library at all. That is what lets a single runner jar choose which of
 * its backends to load (docs/multi-version.md §2.1).
 *
 * <p>Written against the wire rather than against a library on purpose. Bringing MCProtocolLib in
 * here would put a protocol library on the launcher's classpath, which is the one thing the
 * bundle exists to avoid.
 *
 * <p>No compression and no encryption are involved: the server enables both during login, and
 * status never gets there.
 */
public final class ServerPing {

    /**
     * Protocol claimed in the handshake.
     *
     * <p>-1 is the convention for "I am only asking". A server answers a status request with its
     * own version whatever this says, and claiming a real number here would make a mismatched
     * server look reachable in the one situation this call exists to detect.
     */
    private static final int ASKING = -1;

    /** Status replies are small; anything larger is not a Minecraft server answering. */
    private static final int MAX_REPLY = 1 << 20;

    private static final Pattern VERSION_BLOCK =
            Pattern.compile("\"version\"\\s*:\\s*\\{(.*?)}", Pattern.DOTALL);
    private static final Pattern PROTOCOL_FIELD =
            Pattern.compile("\"protocol\"\\s*:\\s*(-?\\d+)");

    private ServerPing() {}

    /**
     * The protocol number {@code host:port} speaks.
     *
     * @throws IOException if the server cannot be reached, does not answer a status request, or
     *                     answers with something that carries no protocol number
     */
    public static int protocol(String host, int port, int timeoutMillis) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            writePacket(out, handshake(host, port));
            writePacket(out, new byte[] {0x00});
            out.flush();

            return protocolOf(statusJson(in));
        }
    }

    /** Handshake: packet 0x00, protocol, address, port, next state 1 (status). */
    private static byte[] handshake(String host, int port) throws IOException {
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(body);

        packet.writeByte(0x00);
        writeVarInt(packet, ASKING);
        writeString(packet, host);
        packet.writeShort(port);
        writeVarInt(packet, 1);

        return body.toByteArray();
    }

    private static String statusJson(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        if (length <= 0 || length > MAX_REPLY) {
            throw new IOException("The server answered a status request with " + length
                    + " bytes, which is not a status reply.");
        }
        int id = readVarInt(in);
        if (id != 0x00) {
            throw new IOException("Expected a status reply, got packet 0x"
                    + Integer.toHexString(id) + ".");
        }
        return readString(in);
    }

    /**
     * Pulls the protocol number out of the status JSON.
     *
     * <p>By pattern, not by a JSON parser, because bot-core has no external dependencies and
     * acquiring one for a single integer would be a poor trade. The {@code version} object is
     * located first so that a plugin listing its own "protocol" somewhere else in the reply
     * cannot be read instead.
     */
    static int protocolOf(String json) throws IOException {
        Matcher version = VERSION_BLOCK.matcher(json);
        Matcher protocol = version.find()
                ? PROTOCOL_FIELD.matcher(version.group(1))
                : PROTOCOL_FIELD.matcher(json);

        if (!protocol.find()) {
            throw new IOException("The server's status reply carries no protocol number: "
                    + json.substring(0, Math.min(json.length(), 200)));
        }
        return Integer.parseInt(protocol.group(1));
    }

    private static void writePacket(DataOutputStream out, byte[] body) throws IOException {
        writeVarInt(out, body.length);
        out.write(body);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        if (length < 0 || length > MAX_REPLY) {
            throw new IOException("Status reply claims a " + length + " byte string.");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        int remaining = value;
        do {
            int part = remaining & 0x7F;
            remaining >>>= 7;
            out.writeByte(remaining == 0 ? part : part | 0x80);
        } while (remaining != 0);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int read = in.readUnsignedByte();
            result |= (read & 0x7F) << shift;
            if ((read & 0x80) == 0) {
                return result;
            }
        }
        throw new IOException("A varint in the status reply never ended.");
    }
}
