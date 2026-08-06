package moe.vitamin.minecraft.mcp.agent.core;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import moe.vitamin.minecraft.mcp.contract.EventRecord;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

/** Subscribes to every Bukkit event it can find and records them into the ring buffer. */
public final class EventCapture implements Listener {

    /** Packages scanned by default: Bukkit's own events plus Paper's additions. */
    public static final List<String> DEFAULT_SCAN_PACKAGES =
            List.of("org.bukkit.event", "io.papermc.paper.event", "com.destroystokyo.paper.event");

    /** Events that change how the server behaves merely by having a listener registered. */
    private static final Set<String> NEVER_REGISTER = Set.of("PlayerHandshakeEvent");

    private final Plugin plugin;
    private final SequencedRingBuffer<EventRecord> buffer;
    private final HighFrequencyEvents highFrequency;
    private final boolean captureHighFrequency;
    private final List<String> scanPackages;
    private final EventDetails details = new EventDetails();
    private final Logger logger;

    private final Set<String> registeredTypes = new TreeSet<>();
    private volatile boolean running;

    public EventCapture(
            Plugin plugin,
            SequencedRingBuffer<EventRecord> buffer,
            HighFrequencyEvents highFrequency,
            boolean captureHighFrequency,
            Collection<String> scanPackages) {
        this.plugin = plugin;
        this.buffer = buffer;
        this.highFrequency = highFrequency;
        this.captureHighFrequency = captureHighFrequency;
        this.scanPackages = scanPackages == null || scanPackages.isEmpty()
                ? DEFAULT_SCAN_PACKAGES
                : List.copyOf(scanPackages);
        this.logger = plugin.getLogger();
    }

    /** Scans for event classes and registers a listener for each. */
    public int start() {
        if (running) {
            throw new IllegalStateException("Capture is already running");
        }

        List<Class<? extends Event>> eventClasses = discoverEventClasses();
        PluginManager pluginManager = plugin.getServer().getPluginManager();

        int registered = 0;
        for (Class<? extends Event> eventClass : eventClasses) {
            String simpleName = eventClass.getSimpleName();

            if (NEVER_REGISTER.contains(simpleName)) {
                continue;
            }
            if (!captureHighFrequency && highFrequency.contains(simpleName)) {
                continue;
            }
            try {
                pluginManager.registerEvent(
                        eventClass,
                        this,
                        EventPriority.MONITOR,
                        (listener, event) -> record(event),
                        plugin,
                        false);
                registeredTypes.add(simpleName);
                registered++;
            } catch (RuntimeException e) {

                logger.log(Level.FINE, "Skipped event type " + eventClass.getName(), e);
            }
        }

        running = true;
        logger.info("Capturing " + registered + " event types"
                + (captureHighFrequency ? "" : ", excluding " + highFrequency.excluded().size()
                        + " high-frequency types"));
        return registered;
    }

    /** Unregisters every listener this capture installed. */
    public void stop() {
        HandlerList.unregisterAll(this);
        running = false;
    }

    /** Records one event. */
    private void record(Event event) {
        String type = event.getClass().getSimpleName();

        if (!captureHighFrequency && highFrequency.contains(type)) {
            return;
        }

        try {
            long timestamp = System.currentTimeMillis();
            String player = details.playerName(event);
            boolean cancelled = EventDetails.isCancelled(event);
            var payload = details.payload(event);

            buffer.append(sequence ->
                    new EventRecord(sequence, timestamp, type, player, cancelled, payload));
        } catch (RuntimeException e) {

            logger.log(Level.FINE, "Failed to record " + type, e);
        }
    }

    /** Event types currently registered, for reporting through {@code server_info}. */
    public Set<String> registeredTypes() {
        return Set.copyOf(registeredTypes);
    }

    public boolean isRunning() {
        return running;
    }

    /** Finds concrete event classes that own a handler list. */
    private List<Class<? extends Event>> discoverEventClasses() {
        long startedAt = System.nanoTime();
        List<Class<? extends Event>> found = new ArrayList<>();

        try (ScanResult scan = new ClassGraph()
                .enableClassInfo()
                .acceptPackages(scanPackages.toArray(String[]::new))
                .addClassLoader(Event.class.getClassLoader())
                .addClassLoader(getClass().getClassLoader())
                .scan()) {

            for (ClassInfo info : scan.getSubclasses(Event.class.getName())) {
                if (info.isAbstract() || info.isInterface()) {
                    continue;
                }
                Class<? extends Event> eventClass = loadEventClass(info);
                if (eventClass != null && declaresHandlerList(eventClass)) {
                    found.add(eventClass);
                }
            }
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Event scan failed; no events will be captured", e);
            return List.of();
        }

        long millis = (System.nanoTime() - startedAt) / 1_000_000;
        logger.info("Scanned " + scanPackages + " in " + millis + "ms, found " + found.size()
                + " registerable event types");
        return found;
    }

    private Class<? extends Event> loadEventClass(ClassInfo info) {
        try {
            return info.loadClass(Event.class,  true);
        } catch (RuntimeException | LinkageError e) {
            logger.log(Level.FINE, "Could not load event class " + info.getName(), e);
            return null;
        }
    }

    /** Whether the class owns its handler list rather than inheriting one. */
    private static boolean declaresHandlerList(Class<? extends Event> eventClass) {
        try {
            var method = eventClass.getDeclaredMethod("getHandlerList");
            return Modifier.isStatic(method.getModifiers())
                    && HandlerList.class.isAssignableFrom(method.getReturnType());
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
