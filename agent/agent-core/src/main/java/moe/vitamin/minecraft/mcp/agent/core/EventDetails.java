package moe.vitamin.minecraft.mcp.agent.core;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockEvent;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.plugin.Plugin;

/**
 * Pulls the small set of fields worth keeping out of a Bukkit event.
 *
 * <p>This runs on the server's main thread inside a MONITOR listener, so it has to stay cheap.
 * Two things keep it that way: the per-class reflection plan is computed once and cached, and
 * only flat, JSON-primitive values are read. Nothing here serializes — that is the query
 * thread's job (CLAUDE.md invariant 5).
 *
 * <p>Extraction cannot be deferred off-thread, which is worth stating because it looks like it
 * could be. A Bukkit event is a mutable object whose referenced entities keep changing after
 * the handler returns; holding one and reading it later yields whatever the world looks like
 * then, not what happened. So the event is flattened here and only the flat copy travels.
 *
 * <p><b>Cross-version safety.</b> Only {@link PlayerEvent}, {@link BlockEvent} and {@link
 * EntityEvent} are touched through direct calls — those three, and the interfaces they return,
 * have been stable across every supported version. Everything else goes through reflection.
 * That is not caution for its own sake: compiling a direct call against 1.13 bakes in
 * {@code invokevirtual}, and a type that later becomes an interface (as {@code InventoryView}
 * did in 1.21) turns that into a runtime {@code IncompatibleClassChangeError} on exactly the
 * newer servers this agent is supposed to support.
 */
final class EventDetails {

    /**
     * No-arg getters worth reading, in the order they should appear in a payload.
     *
     * <p>Kept to a short list on purpose: the payload competes for the same response budget as
     * every other record, so anything included here has to earn its place across many events.
     */
    private static final List<String> VALUE_GETTERS = List.of(
            "getMessage", "getCause", "getReason", "getAction", "getNewGameMode", "getResult",
            // Without this, PluginEnableEvent and PluginDisableEvent record an empty payload —
            // they report that *a* plugin changed state but not which one, which is the only
            // thing anyone reads them for.
            "getPlugin",
            // Effectively PlayerLoginEvent only, and the one thing that event says which is not
            // available anywhere later: the address the client claimed to have dialled. Servers
            // route on it (forced hosts), and for a bot it is injected through the forwarding
            // handshake, so without this there is no way to check the injection landed.
            "getHostname");

    /** Cached per-class plan: the reflective getters that turned out to be usable. */
    private final ConcurrentMap<Class<?>, List<ValueAccessor>> plans = new ConcurrentHashMap<>();

    /**
     * The name of the player this event concerns, or {@code null}.
     *
     * <p>Checked in order of how directly the event names a player, so a {@link PlayerEvent}
     * never falls through to the entity that happens to be involved.
     */
    String playerName(Event event) {
        if (event instanceof PlayerEvent playerEvent) {
            Player player = playerEvent.getPlayer();
            return player == null ? null : player.getName();
        }
        if (event instanceof EntityEvent entityEvent
                && entityEvent.getEntity() instanceof Player player) {
            return player.getName();
        }
        // Block break/place and inventory events name their player through a getter that has
        // no shared supertype, so they are reached reflectively.
        Object viaGetter = invokeIfPresent(event, "getPlayer");
        if (viaGetter instanceof Player player) {
            return player.getName();
        }
        Object clicker = invokeIfPresent(event, "getWhoClicked");
        if (clicker instanceof Player player) {
            return player.getName();
        }
        return null;
    }

    /** Whether the event had been cancelled by the time it reached MONITOR. */
    static boolean isCancelled(Event event) {
        return event instanceof Cancellable cancellable && cancellable.isCancelled();
    }

    /**
     * Flattens the event into JSON-primitive values.
     *
     * <p>Returns an empty map rather than null for events with nothing extra to say, which is
     * most of them.
     */
    Map<String, Object> payload(Event event) {
        Map<String, Object> payload = new LinkedHashMap<>(6);

        if (event instanceof BlockEvent blockEvent) {
            Block block = blockEvent.getBlock();
            if (block != null) {
                payload.put("block", block.getType().name());
                payload.put("world", block.getWorld().getName());
                payload.put("x", block.getX());
                payload.put("y", block.getY());
                payload.put("z", block.getZ());
            }
        }

        if (event instanceof EntityEvent entityEvent) {
            Entity entity = entityEvent.getEntity();
            if (entity != null) {
                payload.put("entityType", entity.getType().name());
            }
        }

        for (ValueAccessor accessor : plans.computeIfAbsent(event.getClass(), EventDetails::planFor)) {
            Object value = accessor.read(event);
            if (value != null) {
                payload.put(accessor.key(), value);
            }
        }

        return payload;
    }

    /**
     * Works out once, per event class, which of {@link #VALUE_GETTERS} are actually present and
     * return something worth keeping.
     */
    private static List<ValueAccessor> planFor(Class<?> eventClass) {
        List<ValueAccessor> accessors = new ArrayList<>(2);
        for (String getterName : VALUE_GETTERS) {
            Method method = findGetter(eventClass, getterName);
            if (method != null) {
                accessors.add(new ValueAccessor(keyFor(getterName), method));
            }
        }
        return List.copyOf(accessors);
    }

    private static Method findGetter(Class<?> eventClass, String name) {
        try {
            Method method = eventClass.getMethod(name);
            if (!Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0) {
                return method;
            }
        } catch (NoSuchMethodException ignored) {
            // Most events have most of these getters missing; that is the normal case.
        }
        return null;
    }

    /** {@code getMessage} becomes {@code message}. */
    private static String keyFor(String getterName) {
        String withoutPrefix = getterName.substring("get".length());
        return Character.toLowerCase(withoutPrefix.charAt(0)) + withoutPrefix.substring(1);
    }

    private Object invokeIfPresent(Event event, String getterName) {
        Method method = findGetter(event.getClass(), getterName);
        return method == null ? null : invoke(method, event);
    }

    private static Object invoke(Method method, Event event) {
        try {
            return method.invoke(event);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // A getter that throws must never take down the listener: the event still happened,
            // and losing one payload field is far better than breaking capture for that type.
            return null;
        }
    }

    /** One cached getter, already known to exist on the event class. */
    private record ValueAccessor(String key, Method method) {

        Object read(Event event) {
            Object value = invoke(method, event);
            if (value == null) {
                return null;
            }
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                return value;
            }
            if (value instanceof Enum<?> constant) {
                return constant.name();
            }
            if (value instanceof Plugin plugin) {
                return plugin.getName();
            }
            // Anything else would need a real serializer, which does not belong on the main
            // thread. Its type is still a useful hint.
            return value.getClass().getSimpleName();
        }
    }
}
