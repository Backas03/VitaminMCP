package moe.vitamin.minecraft.mcp.bot.spi;

/** What one protocol version's bot implementation must provide. */
public interface BotBackend {

    /** The protocol number this backend speaks. */
    int protocol();

    /** Names the server every bot in this runner will connect to. */
    void start(String host, int port) throws Exception;

    /** Connects a bot and waits until it is standing in the world. */
    Position spawn(String name, String clientIp) throws Exception;

    void despawn(String name) throws Exception;

    void move(String name, double x, double y, double z) throws Exception;

    void breakBlock(String name, int x, int y, int z) throws Exception;

    void command(String name, String command) throws Exception;

    void chat(String name, String message) throws Exception;

    void useBlock(String name, int x, int y, int z, String face) throws Exception;

    /** Right-clicks the nearest entity to a point. */
    int useEntity(String name, double x, double y, double z, double radius, String type)
            throws Exception;

    void clickSlot(String name, int slot, String click) throws Exception;

    void closeMenu(String name) throws Exception;

    /** The menu the client was told about, or {@code null} when none is open. */
    OpenMenu menu(String name) throws Exception;

    /** Everything the client knows that the server will not report. */
    ClientView inspect(String name) throws Exception;

    Position position(String name) throws Exception;

    /** Disconnects every bot. */
    void shutdown();
}
