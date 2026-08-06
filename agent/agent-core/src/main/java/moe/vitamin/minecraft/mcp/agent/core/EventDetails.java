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

/** Pulls the small set of fields worth keeping out of a Bukkit event. */
final class EventDetails {

    /** No-arg getters worth reading, in the order they should appear in a payload. */
    private static final List<String> VALUE_GETTERS = List.of(
            "getMessage", "getCause", "getReason", "getAction", "getNewGameMode", "getResult",

            "getPlugin",

            "getHostname");

    /** Cached per-class plan: the reflective getters that turned out to be usable. */
    private final ConcurrentMap<Class<?>, List<ValueAccessor>> plans = new ConcurrentHashMap<>();

    /** The name of the player this event concerns, or {@code null}. */
    String playerName(Event event) {
        if (event instanceof PlayerEvent playerEvent) {
            Player player = playerEvent.getPlayer();
            return player == null ? null : player.getName();
        }
        if (event instanceof EntityEvent entityEvent
                && entityEvent.getEntity() instanceof Player player) {
            return player.getName();
        }

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

    /** Flattens the event into JSON-primitive values. */
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

            return value.getClass().getSimpleName();
        }
    }
}
