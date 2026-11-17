package com.multicore.core;

import com.multicore.MultiCore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import net.neoforged.bus.ConsumerEventHandler;
import net.neoforged.bus.EventBus;
import net.neoforged.bus.IWrapperListener;
import net.neoforged.bus.SubscribeEventListener;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventListener;
import net.neoforged.bus.api.IEventBus;

/**
 * Wraps every event listener that belongs to a mod which is not known to be thread-safe, so that when an event is
 * fired on a dimension thread (entity ticks, block updates, spawns, chunk loads...) the listener holds the
 * {@link ModLock}. The same event fired on the main thread costs one extra check.
 * <p>
 * The NeoForge event bus cannot be patched with mixins (it is loaded before mods), so the listener lists are
 * rewritten with reflection. The bus module is declared {@code open}, which allows it. Wrapping runs after startup
 * and then periodically, so listeners registered later are covered too; each wrapper also replaces the original in
 * the bus's registration record, so {@code bus.unregister(owner)} still removes it.
 */
public final class ListenerSerializer {
    private static final String PREFIX = "@SubscribeEvent: ";
    private static Reflection reflection;
    /** GameTests only: also wrap MultiCore's own listeners, so the wrapping itself can be tested. */
    static volatile boolean wrapOwnListenersForTest;

    private ListenerSerializer() {
    }

    /** Main thread (or any single thread at a time). */
    /** GameTests only. */
    public static void setWrapOwnListenersForTest(boolean value) {
        wrapOwnListenersForTest = value;
    }

    public static synchronized void wrapAll(IEventBus bus) {
        if (!(bus instanceof EventBus eventBus)) {
            MultiCore.LOGGER.warn("[MultiCore] Unknown event bus implementation {}, listeners are not serialized", bus.getClass());
            return;
        }
        try {
            Reflection r = reflection();
            Map<?, ?> lists = (Map<?, ?>) r.getReadMap.invoke(r.listenerLists.get(eventBus));
            Map<EventListener, EventListener> replaced = new IdentityHashMap<>();
            for (Map.Entry<?, ?> entry : lists.entrySet()) {
                wrapList(r, entry.getValue(), eventName((Class<?>) entry.getKey()), replaced);
            }
            if (!replaced.isEmpty()) {
                updateRegistrations(r, eventBus, replaced);
                MultiCore.LOGGER.info("[MultiCore] {} more mod event listeners will hold the mod lock on dimension threads", replaced.size());
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            MultiCore.LOGGER.error("[MultiCore] Could not wrap event listeners; dimension threading is being turned off to stay safe", e);
            ParallelTicker.get().setEnabled(false);
        }
    }

    @SuppressWarnings("unchecked")
    private static void wrapList(Reflection r, Object listenerList, String eventName, Map<EventListener, EventListener> replaced)
            throws ReflectiveOperationException {
        ArrayList<ArrayList<EventListener>> priorities = (ArrayList<ArrayList<EventListener>>) r.priorities.get(listenerList);
        Semaphore lock = (Semaphore) r.writeLock.get(listenerList);
        boolean canUnwrap = r.canUnwrapListeners.getBoolean(listenerList);

        boolean changed = false;
        lock.acquireUninterruptibly();
        try {
            for (ArrayList<EventListener> list : priorities) {
                for (int i = 0; i < list.size(); i++) {
                    EventListener listener = list.get(i);
                    if (isWrapped(r, listener)) {
                        continue;
                    }
                    String modId = ownerMod(r, listener);
                    if (modId.equals("minecraft") || modId.equals("neoforge") || (modId.equals(MultiCore.MOD_ID) && !wrapOwnListenersForTest)) {
                        continue;
                    }
                    // The bus strips the cancellation check for events that cannot be cancelled; do the same.
                    EventListener target = canUnwrap && listener instanceof IWrapperListener w ? w.getWithoutCheck() : listener;
                    EventListener wrapper = new ConsumerEventHandler(new Serialized(target, modId, eventName));
                    list.set(i, wrapper);
                    replaced.put(listener, wrapper);
                    changed = true;
                }
            }
        } finally {
            lock.release();
        }
        if (changed) {
            r.forceRebuild.invoke(listenerList);
        }
    }

    /**
     * {@code EventBus.unregister(owner)} removes the listener objects it recorded at registration. Point those records
     * at the wrappers, or an unregistered listener would keep firing.
     */
    @SuppressWarnings("unchecked")
    private static void updateRegistrations(Reflection r, EventBus bus, Map<EventListener, EventListener> replaced)
            throws ReflectiveOperationException {
        Map<Object, List<EventListener>> registrations = (Map<Object, List<EventListener>>) r.listeners.get(bus);
        for (List<EventListener> list : registrations.values()) {
            synchronized (list) {
                for (int i = 0; i < list.size(); i++) {
                    EventListener wrapper = replaced.get(list.get(i));
                    if (wrapper != null) {
                        list.set(i, wrapper);
                    }
                }
            }
        }
    }

    /** "EntityTickEvent$Pre" rather than just "Pre". */
    private static String eventName(Class<?> type) {
        String name = type.getName();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static boolean isWrapped(Reflection r, EventListener listener) throws ReflectiveOperationException {
        return listener instanceof ConsumerEventHandler handler && r.consumer.get(handler) instanceof Serialized;
    }

    private static String ownerMod(Reflection r, EventListener listener) throws ReflectiveOperationException {
        Class<?> owner = null;
        if (listener instanceof ConsumerEventHandler handler) {
            owner = r.consumer.get(handler).getClass();
        } else if (listener instanceof SubscribeEventListener) {
            owner = classFromReadable(listener.toString());
        }
        return owner == null ? "?" : ModOwnership.modIdOf(owner);
    }

    /** SubscribeEventListener only keeps a readable description: "@SubscribeEvent: <target> <method><desc>". */
    private static Class<?> classFromReadable(String readable) {
        if (!readable.startsWith(PREFIX)) {
            return null;
        }
        String rest = readable.substring(PREFIX.length());
        String name;
        if (rest.startsWith("class ")) {
            rest = rest.substring("class ".length());
            int space = rest.indexOf(' ');
            name = space < 0 ? rest : rest.substring(0, space);
        } else {
            int end = rest.length();
            for (char c : new char[]{'@', ' '}) {
                int idx = rest.indexOf(c);
                if (idx >= 0 && idx < end) end = idx;
            }
            name = rest.substring(0, end);
        }
        try {
            return Class.forName(name, false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private static Reflection reflection() throws ReflectiveOperationException {
        if (reflection == null) {
            reflection = new Reflection();
        }
        return reflection;
    }

    /** Reflective handles into the event bus internals, looked up once. */
    private static final class Reflection {
        final Field listenerLists = open(EventBus.class.getDeclaredField("listenerLists"));
        final Field listeners = open(EventBus.class.getDeclaredField("listeners"));
        final Method getReadMap;
        final Field consumer = open(ConsumerEventHandler.class.getDeclaredField("consumer"));
        final Field priorities;
        final Field writeLock;
        final Field canUnwrapListeners;
        final Method forceRebuild;

        Reflection() throws ReflectiveOperationException {
            Class<?> lockHelper = Class.forName("net.neoforged.bus.LockHelper");
            this.getReadMap = open(lockHelper.getDeclaredMethod("getReadMap"));
            Class<?> listenerList = Class.forName("net.neoforged.bus.ListenerList");
            this.priorities = open(listenerList.getDeclaredField("priorities"));
            this.writeLock = open(listenerList.getDeclaredField("writeLock"));
            this.canUnwrapListeners = open(listenerList.getDeclaredField("canUnwrapListeners"));
            this.forceRebuild = open(listenerList.getDeclaredMethod("forceRebuild"));
        }

        private static <T extends java.lang.reflect.AccessibleObject> T open(T member) {
            member.setAccessible(true);
            return member;
        }
    }

    /** Runs a mod's listener under the mod lock when it is called on a dimension thread. */
    private static final class Serialized implements Consumer<Event> {
        private final EventListener target;
        private final String modId;
        private final String eventName;
        private final LockSource source;
        /** Cached {@link ContentPolicy#isSafeListener} result, valid for {@link #stamp}'s config generation. */
        private volatile boolean safe;
        private volatile int stamp;

        Serialized(EventListener target, String modId, String eventName) {
            this.target = target;
            this.modId = modId;
            this.eventName = eventName;
            this.source = LockSource.of("listener " + modId + ":" + eventName);
        }

        private boolean safe() {
            int gen = ContentPolicy.generation();
            if (this.stamp != gen) {
                this.safe = ContentPolicy.isSafeListener(this.modId, this.eventName);
                this.stamp = gen;
            }
            return this.safe;
        }

        @Override
        public void accept(Event event) {
            if (ContentPolicy.lockingActive() && !this.safe()) {
                ModLock.acquire(this.source);
                try {
                    this.target.invoke(event);
                } finally {
                    ModLock.release();
                }
            } else {
                this.target.invoke(event);
            }
        }

        @Override
        public String toString() {
            return "MultiCore(" + this.modId + ")[" + this.target + "]";
        }
    }
}
