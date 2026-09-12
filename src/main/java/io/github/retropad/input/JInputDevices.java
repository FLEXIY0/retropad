package io.github.retropad.input;

import io.github.retropad.RetroPad;
import org.lwjgl.input.Controller;
import org.lwjgl.input.Controllers;

import java.lang.reflect.Field;
import java.util.Collection;

/**
 * Forces a fresh look at the hardware, so a pad plugged in mid-session is found.
 *
 * <p>Two separate caches stand in the way, and both had to be read out of the bytecode to
 * believe:
 *
 * <ul>
 *   <li>{@code Controllers.destroy()} in LWJGL 2 is an empty method — it compiles to a bare
 *       {@code return}. Its {@code created} flag therefore stays set, and {@code create()}
 *       begins with "if created, return", so rebuilding the subsystem the documented way does
 *       precisely nothing.</li>
 *   <li>JInput's default environment scans for devices only when its {@code controllers} field
 *       is <em>null</em>. Emptying the list is not enough: the check is {@code ifnonnull}, so a
 *       cleared-but-present list means the scan is skipped forever.</li>
 * </ul>
 *
 * <p>So both layers are reset by reflection, in that order, and only while no pad is connected —
 * a scan re-instantiates JInput's platform plugin, which is not free.
 */
public final class JInputDevices {

    private static boolean unavailable;

    private JInputDevices() {
        throw new AssertionError();
    }

    /**
     * Puts both layers back into their "never scanned" state, so that the next
     * {@link Controllers#create()} really enumerates.
     *
     * @return false if the reset could not be done, in which case a rescan is pointless
     */
    public static boolean prepareRescan() {
        if (unavailable) {
            return false;
        }
        try {
            resetLwjgl();
            resetJInput();
            return true;
        } catch (Throwable throwable) {
            RetroPad.LOGGER.warn("Cannot rescan for gamepads; one plugged in now will only be "
                    + "seen after a restart: " + throwable);
            unavailable = true;
            return false;
        }
    }

    /**
     * Whether a device is still answering.
     *
     * <p>JInput's own {@code poll()} returns false once a device has gone — that is the only
     * disconnect signal in the whole stack — but LWJGL's wrapper calls it and throws the answer
     * away (its {@code poll()} compiles to {@code invokeinterface poll; pop}). So the wrapped
     * device is reached directly and asked again. Anything unexpected counts as alive: dropping
     * a working pad because a field was renamed would be far worse than missing an unplug.
     */
    public static boolean isAlive(Controller controller) {
        if (controller == null) {
            return false;
        }
        try {
            Object target = readField(controller.getClass(), controller, "target");
            if (target == null) {
                return true;
            }
            // Through the interface, not the concrete class: JInput's device classes are
            // package-private, so a method looked up on one of them cannot be invoked.
            Class<?> device = Class.forName("net.java.games.input.Controller");
            Object alive = device.getMethod("poll").invoke(target);
            return !(alive instanceof Boolean) || (Boolean) alive;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** Clears LWJGL's own device list and the flag that makes create() a no-op. */
    private static void resetLwjgl() throws Exception {
        Object list = readField(Controllers.class, null, "controllers");
        if (list instanceof Collection) {
            // create() appends to this list rather than replacing it, so leaving the old
            // entries behind would show every device twice after a rescan.
            ((Collection<?>) list).clear();
        }
        Object events = readField(Controllers.class, null, "events");
        if (events instanceof Collection) {
            ((Collection<?>) events).clear();
        }
        writeField(Controllers.class, null, "controllerCount", 0);
        writeField(Controllers.class, null, "created", Boolean.FALSE);
    }

    /** Drops JInput's cached scan, including the plugin list that would skip a second one. */
    private static void resetJInput() throws Exception {
        Class<?> environmentClass = Class.forName("net.java.games.input.ControllerEnvironment");
        Object environment = environmentClass.getMethod("getDefaultEnvironment").invoke(null);
        if (environment == null) {
            return;
        }
        // Null, not empty: the scan is guarded by a null check on this very field.
        writeField(environment.getClass(), environment, "controllers", null);
        Object plugins = readField(environment.getClass(), environment, "loadedPlugins");
        if (plugins instanceof Collection) {
            ((Collection<?>) plugins).clear();
        }
    }

    private static Object readField(Class<?> owner, Object instance, String name) throws Exception {
        Field field = findField(owner, name);
        return field == null ? null : field.get(instance);
    }

    private static void writeField(Class<?> owner, Object instance, String name, Object value)
            throws Exception {
        Field field = findField(owner, name);
        if (field != null) {
            field.set(instance, value);
        }
    }

    private static Field findField(Class<?> owner, String name) throws Exception {
        for (Class<?> type = owner; type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Keep walking up; JInput's fields live on the concrete environment class.
            }
        }
        return null;
    }
}
