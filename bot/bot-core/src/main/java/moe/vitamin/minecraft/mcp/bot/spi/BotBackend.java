package moe.vitamin.minecraft.mcp.bot.spi;

/**
 * What one protocol version's bot implementation must provide.
 *
 * <p>This is the boundary between the runner's launcher and a backend loaded out of the bundle,
 * and it is the <b>only</b> package that crosses the class loader between them (docs/design.md
 * §4.2, docs/multi-version.md §2.1.1). Everything else on either side is loaded child-first and
 * the two copies never meet, which is what lets several MCProtocolLib builds live in one jar.
 *
 * <p><b>No signature here may name a protocol library type.</b> That is not a restriction to
 * design around: the line protocol already carries every one of these commands as text, which
 * proves a library-free signature exists for each. A method that needs a protocol type is a
 * method that belongs inside the backend.
 *
 * <p>Implementations are found with {@link java.util.ServiceLoader}, so each backend jar carries
 * a {@code META-INF/services} entry and a public no-argument constructor. {@link #start} is
 * called once before anything else.
 *
 * <p>Failure is an exception, and its message is what the caller sees. The launcher turns it into
 * an {@code err} line verbatim, so it should read as the reason rather than as a stack position —
 * "no villager within 2 blocks of …" rather than "NPE".
 */
public interface BotBackend {

    /**
     * The protocol number this backend speaks.
     *
     * <p>Declared by the backend rather than inferred from which file was loaded, so a bundle
     * that shipped a jar under the wrong name is caught at startup instead of arriving later as
     * {@code Outdated client!} from the server.
     */
    int protocol();

    /** Names the server every bot in this runner will connect to. Called once. */
    void start(String host, int port) throws Exception;

    /**
     * Connects a bot and waits until it is standing in the world.
     *
     * @param clientIp address the server should attribute the connection to, or empty for the
     *                 real one
     */
    Position spawn(String name, String clientIp) throws Exception;

    void despawn(String name) throws Exception;

    void move(String name, double x, double y, double z) throws Exception;

    void breakBlock(String name, int x, int y, int z) throws Exception;

    void command(String name, String command) throws Exception;

    void chat(String name, String message) throws Exception;

    /** @param face side of the block being clicked, or empty for the top */
    void useBlock(String name, int x, int y, int z, String face) throws Exception;

    /**
     * Right-clicks the nearest entity to a point.
     *
     * @return the entity id it settled on
     * @throws Exception if nothing matched, with a message saying what <em>is</em> nearby
     */
    int useEntity(String name, double x, double y, double z, double radius, String type)
            throws Exception;

    /** @param click {@code left}, {@code right}, {@code shift_left} or {@code shift_right} */
    void clickSlot(String name, int slot, String click) throws Exception;

    void closeMenu(String name) throws Exception;

    /** The menu the client was told about, or {@code null} when none is open. */
    OpenMenu menu(String name) throws Exception;

    /** Everything the client knows that the server will not report. */
    ClientView inspect(String name) throws Exception;

    Position position(String name) throws Exception;

    /** Disconnects every bot. The process exits after this. */
    void shutdown();
}
