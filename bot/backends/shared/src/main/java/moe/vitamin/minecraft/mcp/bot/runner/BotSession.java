package moe.vitamin.minecraft.mcp.bot.runner;

import moe.vitamin.minecraft.mcp.bot.core.BotIdentity;
import moe.vitamin.minecraft.mcp.bot.core.ForwardingHandshake;
import moe.vitamin.minecraft.mcp.bot.spi.BossBar;
import moe.vitamin.minecraft.mcp.bot.spi.MenuItem;
import moe.vitamin.minecraft.mcp.bot.spi.Position;
import moe.vitamin.minecraft.mcp.bot.spi.Scoreboard;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.handshake.serverbound.ClientIntentionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;

/** One bot's connection to a server. */
public final class BotSession implements AutoCloseable {

    /** Consecutive unchanged positions taken as "landed". */
    private static final int SETTLED_CHECKS = 5;

    /** Gap between settle checks — a server tick, since that is when position can change. */
    private static final Duration SETTLE_POLL = Duration.ofMillis(50);

    /** How long to wait for the whole handshake-login-join sequence. */
    public static final Duration DEFAULT_LOGIN_TIMEOUT = Duration.ofSeconds(30);

    private final BotIdentity identity;

    /** The connection, as the one type every version has. */
    private final Session session;
    private final CountDownLatch joined = new CountDownLatch(1);
    private final AtomicReference<String> disconnectReason = new AtomicReference<>();

    private volatile boolean inGame;

    /** Where the server last said this bot is. */
    private volatile Position position;
    private volatile java.util.concurrent.ScheduledExecutorService ticker;
    private volatile boolean loadedSent;

    /** The menu the server has opened for this bot, if any. */
    private volatile int containerId = NO_CONTAINER;

    /** The server's synchronisation counter for the open container. */
    private volatile int containerStateId;

    /** Title of the open menu, as plain text, for diagnostics and matching. */
    private volatile String containerTitle;

    /** The menu's contents as the client was told them. */
    private volatile org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack[] containerItems
            = new org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack[0];

    /** Most recent messages the server sent this bot, oldest first. */
    private final java.util.Deque<String> messages = new java.util.concurrent.ConcurrentLinkedDeque<>();

    /** How many messages to keep. */
    private static final int MAX_MESSAGES = 100;

    /** No menu open. */
    public static final int NO_CONTAINER = -1;

    /** No entity matched. */
    public static final int NO_ENTITY = -1;

    /** An entity this bot has been told about, and where it currently is. */
    private record TrackedEntity(int id, String type, double x, double y, double z) {

        TrackedEntity at(double x, double y, double z) {
            return new TrackedEntity(id, type, x, y, z);
        }

        TrackedEntity moveBy(double dx, double dy, double dz) {
            return new TrackedEntity(id, type, x + dx, y + dy, z + dz);
        }

        double distanceTo(double px, double py, double pz) {
            double dx = x - px;
            double dy = y - py;
            double dz = z - pz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /** Entities the server has spawned for this bot, by entity id. */
    private final java.util.Map<Integer, TrackedEntity> entities
            = new java.util.concurrent.ConcurrentHashMap<>();

    /** Boss bars currently shown, by the id the server addresses them with. */
    private final java.util.Map<java.util.UUID, BossBar> bossBars
            = new java.util.concurrent.ConcurrentHashMap<>();

    /** Objective name to the title the client would draw above the sidebar. */
    private final java.util.Map<String, String> objectiveTitles
            = new java.util.concurrent.ConcurrentHashMap<>();

    /** One scoreboard line: the number on the right, and the text the client draws. */
    private record ScoreLine(int value, String text) {}

    /** Objective name to its lines, keyed by the entry each line belongs to. */
    private final java.util.Map<String, java.util.Map<String, ScoreLine>> objectiveLines
            = new java.util.concurrent.ConcurrentHashMap<>();

    /** Which objective is in the sidebar slot, or null when none is. */
    private volatile String sidebarObjective;

    /** The text a team wraps its members' names in. */
    private record TeamText(String prefix, String suffix) {}

    /** Team name to its prefix and suffix. */
    private final java.util.Map<String, TeamText> teamText
            = new java.util.concurrent.ConcurrentHashMap<>();

    /** Scoreboard entry to the team it belongs to. */
    private final java.util.Map<String, String> entryTeam
            = new java.util.concurrent.ConcurrentHashMap<>();

    /** The nearest tracked entity to a point, or {@link #NO_ENTITY}. */
    public int entityNear(double x, double y, double z, double radius, String type) {
        TrackedEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (TrackedEntity candidate : entities.values()) {
            if (type != null && !type.isBlank()
                    && !candidate.type().equalsIgnoreCase(type.trim())) {
                continue;
            }
            double distance = candidate.distanceTo(x, y, z);
            if (distance <= radius && distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best == null ? NO_ENTITY : best.id();
    }

    /** What is actually near a point, for when {@link #entityNear} found nothing. */
    public String describeEntitiesNear(double x, double y, double z, double radius) {
        return entities.values().stream()
                .filter(e -> e.distanceTo(x, y, z) <= Math.max(radius * 4, 16))
                .sorted(java.util.Comparator.comparingDouble(e -> e.distanceTo(x, y, z)))
                .limit(8)
                .map(e -> String.format(java.util.Locale.ROOT, "%s at %.1f %.1f %.1f (%.1f away)",
                        e.type(), e.x(), e.y(), e.z(), e.distanceTo(x, y, z)))
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private BotSession(BotIdentity identity, Session session) {
        this.identity = identity;
        this.session = session;
    }

    /** Opens a session, presenting the connection as what it actually is. */
    public static BotSession open(String host, int port, BotIdentity identity) throws Exception {
        return open(host, port, identity, null, null);
    }

    /** Opens a session against a backend that trusts proxy forwarding. */
    public static BotSession open(
            String host, int port, BotIdentity identity, String claimedHost, String clientIp)
            throws Exception {

        Session session = SessionFactory.open(host, port, identity.name());

        BotSession bot = new BotSession(identity, session);
        session.addListener(bot.new LifecycleListener(
                isBlank(claimedHost) ? host : claimedHost,
                isBlank(clientIp) ? null : clientIp));
        return bot;
    }

    /** Absent and empty mean the same thing — the runner protocol sends an empty field. */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Connects and waits until the bot is in the world. */
    public BotSession connect(Duration timeout) throws InterruptedException {
        SessionFactory.connect(session);

        boolean released = joined.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        String reason = disconnectReason.get();

        if (reason != null || position == null) {
            close();
            throw new IllegalStateException(reason != null
                    ? "Bot " + identity.name() + " was rejected: " + reason + explain(reason)
                    : "Bot " + identity.name() + " did not reach the world within " + timeout
                            + (released ? " (connected, but no position ever arrived)" : ""));
        }
        startTicking();
        return this;
    }

    /**
     * Turns a rejection the server states as a translation key into something actionable.
     *
     * <p>A disconnect reason arrives as a component, and the vanilla ones carry no words at all —
     * only the key a client would look up, so {@code multiplayer.disconnect.duplicate_login}
     * reaches the caller exactly like that. The one worth explaining is that key, because its
     * cause here is not the one a reader assumes: a bot's UUID is derived from its name, so two
     * callers using the same name are the same player, and a server admits a player once. Two MCP
     * sessions driving one server hit it immediately, and nothing about the key says so.
     */
    private String explain(String reason) {
        if (reason.contains("duplicate_login")) {
            return " — a player with this name is already on the server. A bot's UUID is derived"
                    + " from its name, so another session using '" + identity.name() + "' is the"
                    + " same player, and the server admits it once. Give each session its own bot"
                    + " names.";
        }
        return "";
    }

    /** Starts behaving like a client. */
    private void startTicking() {
        java.util.concurrent.ScheduledExecutorService executor =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "bot-tick-" + identity.name());
                    thread.setDaemon(true);
                    return thread;
                });

        executor.scheduleAtFixedRate(() -> {
            try {
                Position current = position;
                if (current != null && session.isConnected()) {

                    PlayerSync.sendStanding(session, current);
                }
            } catch (RuntimeException ignored) {

            }
        }, 0, 50, TimeUnit.MILLISECONDS);

        this.ticker = executor;
    }

    /** Waits until the bot has stopped falling. */
    public BotSession awaitGrounded(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        double lastY = Double.NaN;
        int settledChecks = 0;

        while (System.nanoTime() < deadline) {
            Position current = position;
            if (current != null) {
                if (Math.abs(current.y() - lastY) < 1.0e-6) {

                    if (++settledChecks >= SETTLED_CHECKS) {
                        return this;
                    }
                } else {
                    settledChecks = 0;
                    lastY = current.y();
                }
            }
            Thread.sleep(SETTLE_POLL.toMillis());
        }
        throw new IllegalStateException("Bot " + identity.name()
                + " never settled within " + timeout + "; last position " + describePosition());
    }

    /** Whether the bot has joined the world, not merely opened a socket. */
    public boolean isInGame() {
        return inGame && session.isConnected();
    }

    public BotIdentity identity() {
        return identity;
    }

    /** The underlying session, for sending packets. */
    public Session session() {
        return session;
    }

    /** Where the server last said this bot is, or {@code null} before the first position. */
    public Position position() {
        return position;
    }

    public double x() {
        Position at = position;
        return at == null ? 0 : at.x();
    }

    public double y() {
        Position at = position;
        return at == null ? 0 : at.y();
    }

    public double z() {
        Position at = position;
        return at == null ? 0 : at.z();
    }

    /** The block position the bot is standing in. */
    public int blockX() {
        return (int) Math.floor(x());
    }

    public int blockY() {
        return (int) Math.floor(y());
    }

    public int blockZ() {
        return (int) Math.floor(z());
    }

    /** The open menu's container id, or {@link #NO_CONTAINER}. */
    public int containerId() {
        return containerId;
    }

    /** The server's current synchronisation counter for the open container. */
    public int containerStateId() {
        return containerStateId;
    }

    /** The open menu's title as plain text, or {@code null} if no menu is open. */
    public String containerTitle() {
        return containerTitle;
    }

    /** Whether the server has a menu open for this bot. */
    public boolean hasMenuOpen() {
        return containerId != NO_CONTAINER;
    }

    /** Forgets the open menu, because this client is the one closing it. */
    void forgetContainer() {
        containerId = NO_CONTAINER;
        containerTitle = null;
        containerItems = new org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack[0];
    }

    /** The open menu's slots as the client received them, one entry per occupied slot. */
    public List<MenuItem> clientMenuItems() {
        var items = containerItems;
        List<MenuItem> out = new ArrayList<>();
        for (int slot = 0; slot < items.length; slot++) {
            var item = items[slot];
            if (item == null) {
                continue;
            }
            out.add(new MenuItem(slot, item.getId(), item.getAmount(),
                    ItemText.nameOf(item), ItemText.modelDataOf(item), ItemText.loreOf(item)));
        }
        return out;
    }

    /** Messages the server sent this bot, oldest first. */
    public List<String> receivedMessages() {
        return List.copyOf(messages);
    }

    /** Boss bars on screen now. */
    public List<BossBar> clientBossBars() {
        return List.copyOf(bossBars.values());
    }

    /** The sidebar scoreboard, or {@code null} when nothing is displayed there. */
    public Scoreboard clientScoreboard() {
        String objective = sidebarObjective;
        if (objective == null) {
            return null;
        }
        var lines = objectiveLines.get(objective);
        List<String> rendered = lines == null ? List.of() : lines.entrySet().stream()
                .sorted(java.util.Comparator.comparingInt(
                        (java.util.Map.Entry<String, ScoreLine> e) -> e.getValue().value())
                        .reversed())
                .map(entry -> renderLine(entry.getKey(), entry.getValue()))
                .toList();
        return new Scoreboard(objectiveTitles.getOrDefault(objective, objective), rendered);
    }

    /** One sidebar line as the client would draw it. */
    private String renderLine(String entry, ScoreLine line) {
        if (!line.text().equals(entry)) {
            return line.text();
        }
        String team = entryTeam.get(entry);
        TeamText text = team == null ? null : teamText.get(team);
        if (text == null) {
            return stripLegacyCodes(entry);
        }
        return text.prefix() + stripLegacyCodes(entry) + text.suffix();
    }

    /** Drops legacy section-sign codes, which are formatting rather than text. */
    private static String stripLegacyCodes(String entry) {
        return entry.replaceAll("(?i)§[0-9a-fk-or]", "");
    }

    /** A readable position, for failure messages. */
    public String describePosition() {
        return position == null ? "unknown" : x() + ", " + y() + ", " + z();
    }

    /** Actions this bot can perform. */
    public BotActions actions() {
        return new BotActions(this);
    }

    /** Why the server disconnected this bot, if it did. */
    public String disconnectReason() {
        return disconnectReason.get();
    }

    @Override
    public void close() {
        java.util.concurrent.ScheduledExecutorService executor = ticker;
        if (executor != null) {

            executor.shutdownNow();
            ticker = null;
        }
        if (session.isConnected()) {
            session.disconnect("Bot session closed");
        }
        inGame = false;
    }

    /** Injects the forwarded identity, and tracks how far through login the bot has got. */
    private final class LifecycleListener extends SessionAdapter {

        private final String claimedHost;
        private final String clientIp;

        LifecycleListener(String claimedHost, String clientIp) {
            this.claimedHost = claimedHost;
            this.clientIp = clientIp;
        }

        /** Replaces the handshake's hostname with the forwarded identity. */
        @Override
        public void packetSending(
                org.geysermc.mcprotocollib.network.event.session.PacketSendingEvent event) {
            if (event.getPacket() instanceof ClientIntentionPacket intention) {
                String forwardedHost = ForwardingHandshake.addressField(
                        claimedHost, clientIp == null ? localAddress() : clientIp, identity);

                if (Boolean.getBoolean("vitaminmcp.debugHandshake")) {
                    System.err.println("[handshake] protocol=" + intention.getProtocolVersion()
                            + " intent=" + intention.getIntent()
                            + " originalHost=" + intention.getHostname()
                            + " fields=" + ForwardingHandshake.parse(forwardedHost).length
                            + " injected=" + forwardedHost.replace('\0', '|'));
                }
                event.setPacket(new ClientIntentionPacket(
                        intention.getProtocolVersion(),
                        forwardedHost,
                        intention.getPort(),
                        intention.getIntent()));
            }
        }

        /** The address this machine is connecting from. */
        private String localAddress() {
            if (session.getLocalAddress() instanceof InetSocketAddress local
                    && local.getAddress() != null) {
                return local.getAddress().getHostAddress();
            }

            return "127.0.0.1";
        }

        @Override
        public void packetReceived(org.geysermc.mcprotocollib.network.Session session, Packet packet) {

            answerResourcePack(session, packet);

            if (packet instanceof ClientboundLoginPacket) {
                inGame = true;

                entities.clear();
                bossBars.clear();
                objectiveTitles.clear();
                objectiveLines.clear();
                teamText.clear();
                entryTeam.clear();
                sidebarObjective = null;
            }

            trackContainer(packet);
            trackMessages(packet);
            trackEntities(packet);
            trackBossBars(packet);
            trackScoreboard(packet);

            Position arrived = PlayerSync.positionOf(packet);
            if (arrived != null) {
                BotSession.this.position = arrived;

                PlayerSync.confirmTeleport(session, packet);

                if (!loadedSent) {
                    loadedSent = true;
                    PlayerSync.sendLoaded(session);
                }

                joined.countDown();
            }
        }

        /** Declines a resource pack the server pushed, so the login can carry on. */
        private void answerResourcePack(Session session, Packet packet) {
            if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.common.clientbound
                    .ClientboundResourcePackPushPacket push) {
                session.send(new org.geysermc.mcprotocollib.protocol.packet.common.serverbound
                        .ServerboundResourcePackPacket(push.getId(),
                        org.geysermc.mcprotocollib.protocol.data.game.ResourcePackStatus.DECLINED));
            }
        }

        /** Follows the open menu and its synchronisation counter. */
        private void trackContainer(Packet packet) {
            if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound
                    .inventory.ClientboundOpenScreenPacket open) {
                containerId = open.getContainerId();
                containerTitle = PlainTextComponentSerializer.plainText()
                        .serialize(open.getTitle());
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.inventory.ClientboundContainerSetContentPacket content) {
                containerStateId = content.getStateId();
                containerItems = content.getItems();
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.inventory.ClientboundContainerSetSlotPacket slot) {
                containerStateId = slot.getStateId();

                var current = containerItems;
                if (slot.getSlot() >= 0 && slot.getSlot() < current.length) {
                    var updated = java.util.Arrays.copyOf(current, current.length);
                    updated[slot.getSlot()] = slot.getItem();
                    containerItems = updated;
                }
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.inventory.ClientboundContainerClosePacket) {

                containerId = NO_CONTAINER;
                containerTitle = null;
            }
        }

        /** A component as plain text, treating absent as empty. */
        private static String plain(net.kyori.adventure.text.Component component) {
            return component == null
                    ? ""
                    : PlainTextComponentSerializer.plainText().serialize(component);
        }

        /** Labels a non-chat message with where it appeared, or drops it if it says nothing. */
        private static String overlay(String where, net.kyori.adventure.text.Component component) {
            String plain = PlainTextComponentSerializer.plainText().serialize(component);
            return plain.isBlank() ? null : "[" + where + "] " + plain;
        }

        /** Follows the boss bars on this bot's screen. */
        private void trackBossBars(Packet packet) {
            if (!(packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound
                    .ClientboundBossEventPacket event)) {
                return;
            }
            switch (event.getAction()) {
                case ADD -> bossBars.put(event.getUuid(), new BossBar(
                        PlainTextComponentSerializer.plainText().serialize(event.getTitle()),
                        event.getHealth(),
                        event.getColor() == null ? "" : event.getColor().name()));
                case REMOVE -> bossBars.remove(event.getUuid());
                case UPDATE_TITLE -> bossBars.computeIfPresent(event.getUuid(), (id, bar) ->
                        new BossBar(
                                PlainTextComponentSerializer.plainText()
                                        .serialize(event.getTitle()),
                                bar.progress(), bar.color()));
                case UPDATE_HEALTH -> bossBars.computeIfPresent(event.getUuid(), (id, bar) ->
                        new BossBar(bar.title(), event.getHealth(), bar.color()));
                case UPDATE_STYLE -> bossBars.computeIfPresent(event.getUuid(), (id, bar) ->
                        new BossBar(bar.title(), bar.progress(),
                                event.getColor() == null ? "" : event.getColor().name()));

                default -> { }
            }
        }

        /** Follows the sidebar scoreboard. */
        private void trackScoreboard(Packet packet) {
            if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound
                    .scoreboard.ClientboundSetObjectivePacket objective) {
                switch (objective.getAction()) {
                    case ADD, UPDATE -> objectiveTitles.put(objective.getName(),
                            objective.getDisplayName() == null
                                    ? objective.getName()
                                    : PlainTextComponentSerializer.plainText()
                                            .serialize(objective.getDisplayName()));
                    case REMOVE -> {
                        objectiveTitles.remove(objective.getName());
                        objectiveLines.remove(objective.getName());
                    }
                    default -> { }
                }
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.scoreboard.ClientboundSetDisplayObjectivePacket display) {
                if (display.getPosition() == org.geysermc.mcprotocollib.protocol.data.game
                        .scoreboard.ScoreboardPosition.SIDEBAR) {

                    sidebarObjective = display.getName() == null || display.getName().isEmpty()
                            ? null
                            : display.getName();
                }
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.scoreboard.ClientboundSetScorePacket score) {

                String line = score.getDisplay() == null
                        ? score.getOwner()
                        : PlainTextComponentSerializer.plainText().serialize(score.getDisplay());
                objectiveLines
                        .computeIfAbsent(score.getObjective(),
                                key -> new java.util.concurrent.ConcurrentHashMap<>())
                        .put(score.getOwner(), new ScoreLine(score.getValue(), line));
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.scoreboard.ClientboundResetScorePacket reset) {
                var lines = objectiveLines.get(reset.getObjective());
                if (lines != null) {
                    lines.remove(reset.getOwner());
                }
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.scoreboard.ClientboundSetPlayerTeamPacket team) {
                switch (team.getAction()) {
                    case CREATE, UPDATE -> {
                        teamText.put(team.getTeamName(), new TeamText(
                                plain(team.getPrefix()), plain(team.getSuffix())));

                        if (team.getPlayers() != null) {
                            for (String entry : team.getPlayers()) {
                                entryTeam.put(entry, team.getTeamName());
                            }
                        }
                    }
                    case ADD_PLAYER -> {
                        for (String entry : team.getPlayers()) {
                            entryTeam.put(entry, team.getTeamName());
                        }
                    }
                    case REMOVE_PLAYER -> {
                        for (String entry : team.getPlayers()) {
                            entryTeam.remove(entry, team.getTeamName());
                        }
                    }
                    case REMOVE -> {
                        teamText.remove(team.getTeamName());
                        entryTeam.values().remove(team.getTeamName());
                    }
                    default -> { }
                }
            }
        }

        /** Follows the entities the server has told this bot about. */
        private void trackEntities(Packet packet) {
            EntitySync.Spawn spawn = EntitySync.spawnOf(packet);
            if (spawn != null) {
                entities.put(spawn.id(), new TrackedEntity(
                        spawn.id(), spawn.type(), spawn.x(), spawn.y(), spawn.z()));
                return;
            }
            EntitySync.Teleport teleport = EntitySync.teleportOf(packet);
            if (teleport != null) {
                entities.computeIfPresent(teleport.id(),
                        (id, known) -> known.at(teleport.x(), teleport.y(), teleport.z()));
                return;
            }

            if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.entity.ClientboundRemoveEntitiesPacket remove) {
                for (int id : remove.getEntityIds()) {
                    entities.remove(id);
                }
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.entity.ClientboundMoveEntityPosPacket move) {
                entities.computeIfPresent(move.getEntityId(), (id, known) -> known.moveBy(
                        move.getMoveX(), move.getMoveY(), move.getMoveZ()));
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.entity.ClientboundMoveEntityPosRotPacket move) {
                entities.computeIfPresent(move.getEntityId(), (id, known) -> known.moveBy(
                        move.getMoveX(), move.getMoveY(), move.getMoveZ()));
            }
        }

        /** Keeps what the server said to this bot. */
        private void trackMessages(Packet packet) {
            String text = null;
            if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound
                    .ClientboundSystemChatPacket system) {
                text = PlainTextComponentSerializer.plainText().serialize(system.getContent());
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.ClientboundDisguisedChatPacket disguised) {
                text = PlainTextComponentSerializer.plainText().serialize(disguised.getMessage());
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.ClientboundPlayerChatPacket chat) {
                text = chat.getContent();
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.title.ClientboundSetActionBarTextPacket actionBar) {
                text = overlay("action bar", actionBar.getText());
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.title.ClientboundSetTitleTextPacket title) {
                text = overlay("title", title.getText());
            } else if (packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .clientbound.title.ClientboundSetSubtitleTextPacket subtitle) {
                text = overlay("subtitle", subtitle.getText());
            }
            if (text == null) {
                return;
            }

            messages.addLast(text);
            while (messages.size() > MAX_MESSAGES) {
                messages.pollFirst();
            }
        }

        @Override
        public void disconnected(DisconnectedEvent event) {
            inGame = false;
            containerId = NO_CONTAINER;
            containerTitle = null;
            entities.clear();
            disconnectReason.set(describe(event));

            joined.countDown();
        }

        private String describe(DisconnectedEvent event) {
            String reason = event.getReason() == null
                    ? "no reason given"
                    : PlainTextComponentSerializer.plainText().serialize(event.getReason());
            Throwable cause = event.getCause();
            return cause == null ? reason : reason + " (" + cause + ")";
        }
    }
}
