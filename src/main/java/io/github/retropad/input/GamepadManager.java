package io.github.retropad.input;

import io.github.retropad.PadConfig;
import io.github.retropad.RetroPad;
import org.lwjgl.input.Controller;
import org.lwjgl.input.Controllers;

/**
 * Owns the connection to the physical gamepad.
 *
 * <p>LWJGL 2 enumerates devices exactly once, when {@link Controllers#create()} runs, and has
 * no hotplug notification of any kind. To let people plug a pad in mid-session we tear the
 * subsystem down on a timer whenever nothing is connected — and drop JInput's own device cache
 * first, without which the rebuild just hands back the same empty list (see
 * {@link JInputDevices}).
 */
public final class GamepadManager {

    /**
     * First wait between device rescans while no pad is connected, in milliseconds. Short
     * enough that plugging a pad in feels like it was noticed rather than eventually found.
     */
    private static final long RESCAN_INTERVAL_MS = 1200L;
    /**
     * Upper bound on that wait. Each rescan tears down and rebuilds the whole JInput
     * environment, which enumerates every HID on the machine and logs while it does it, so
     * backing off keeps a padless session from paying that every few seconds forever.
     */
    private static final long RESCAN_INTERVAL_MAX_MS = 5000L;
    /**
     * How often the connected pad is asked whether it is still there. Unplugging one does not
     * raise an error anywhere — LWJGL's poll simply keeps handing back the last values it saw,
     * which is why a pad had to be reconnected before the game was started to be noticed at all.
     */
    private static final long LIVENESS_INTERVAL_MS = 750L;
    /** A device needs at least this much to be plausibly a gamepad rather than a mouse or pedal. */
    private static final int MIN_GAMEPAD_BUTTONS = 4;
    private static final int MIN_GAMEPAD_AXES = 2;

    private final GamepadState state = new GamepadState();

    private boolean subsystemUp;
    private boolean unavailable;
    private Controller controller;
    private long nextRescanAt;
    private long nextLivenessCheckAt;
    private long rescanInterval = RESCAN_INTERVAL_MS;
    /** Set right after the subsystem is built, so the first pick skips a pointless rebuild. */
    private boolean deviceListFresh;

    public GamepadState getState() {
        return this.state;
    }

    public boolean isConnected() {
        return this.controller != null;
    }

    public String getConnectedName() {
        return this.controller == null ? null : this.controller.getName();
    }

    /**
     * Polls the pad. Safe to call every frame; cheap when no device is present.
     */
    public void update(PadConfig config, long now) {
        if (this.unavailable || !config.enabled) {
            return;
        }
        if (!this.subsystemUp && !this.startSubsystem()) {
            return;
        }
        if (this.controller == null) {
            if (now < this.nextRescanAt) {
                return;
            }
            this.nextRescanAt = now + this.rescanInterval;
            this.rescan(config);
            if (this.controller == null) {
                this.rescanInterval = Math.min(this.rescanInterval * 2L, RESCAN_INTERVAL_MAX_MS);
                return;
            }
            this.rescanInterval = RESCAN_INTERVAL_MS;
        }

        try {
            // Controllers.poll() already polls every device. Polling the same one again drains
            // an event queue that LWJGL fills from those events, and the values it caches come
            // from the queue rather than from the device state.
            Controllers.poll();
        } catch (Throwable throwable) {
            // A yanked USB cable surfaces here as a native error; drop the device and rescan.
            RetroPad.LOGGER.warn("Gamepad poll failed, dropping device", throwable);
            this.dropDevice(now);
            return;
        }
        if (now >= this.nextLivenessCheckAt) {
            this.nextLivenessCheckAt = now + LIVENESS_INTERVAL_MS;
            if (!JInputDevices.isAlive(this.controller)) {
                RetroPad.LOGGER.info("Gamepad disconnected: \"" + this.controller.getName()
                        + "\"; watching for it to come back");
                this.dropDevice(now);
                return;
            }
        }
        this.state.setDeadzones(config.stickDeadzone, config.triggerDeadzone);
        this.state.poll(this.controller, now);
    }

    /**
     * Points JInput at the folder the launcher unpacked its native libraries into.
     *
     * <p>LWJGL is told where its own DLLs live through {@code org.lwjgl.librarypath}, and some
     * launchers set only that — leaving JInput, which is a separate project with a separate
     * property, searching the default library path and finding nothing. The pad then never
     * appears and the only clue is an {@code UnsatisfiedLinkError} for {@code jinput-dx8_64}
     * buried in the log. Its own setting always wins; this only fills a gap.
     */
    private static void inheritNativesPath() {
        if (System.getProperty("net.java.games.input.librarypath") != null) {
            return;
        }
        String lwjglPath = System.getProperty("org.lwjgl.librarypath");
        if (lwjglPath != null && !lwjglPath.isEmpty()) {
            System.setProperty("net.java.games.input.librarypath", lwjglPath);
            RetroPad.LOGGER.info("Looking for controller drivers in " + lwjglPath);
        }
    }

    private boolean startSubsystem() {
        try {
            if (!Controllers.isCreated()) {
                inheritNativesPath();
                Controllers.create();
            }
            this.subsystemUp = true;
            this.deviceListFresh = true;
            return true;
        } catch (Throwable throwable) {
            // No JInput natives, no permission, headless: give up quietly for the session.
            RetroPad.LOGGER.warn("Controller support unavailable: " + throwable);
            this.unavailable = true;
            return false;
        }
    }

    /** Rebuilds the device list, unless it was just built, and picks a pad per the config. */
    private void rescan(PadConfig config) {
        if (!this.deviceListFresh) {
            // Not Controllers.destroy(): that method is empty in LWJGL 2, and create() returns
            // immediately while its "created" flag is still set. Both caches have to be reset
            // by hand or the rescan is a no-op.
            if (!JInputDevices.prepareRescan()) {
                return;
            }
            try {
                Controllers.create();
            } catch (Throwable throwable) {
                RetroPad.LOGGER.warn("Controller rescan failed: " + throwable);
                this.unavailable = true;
                return;
            }
        }
        this.deviceListFresh = false;

        int count = Controllers.getControllerCount();
        Controller chosen = null;
        if (config.deviceIndex >= 0 && config.deviceIndex < count) {
            chosen = Controllers.getController(config.deviceIndex);
        } else {
            for (int i = 0; i < count; i++) {
                Controller candidate = Controllers.getController(i);
                if (looksLikeGamepad(candidate)) {
                    chosen = candidate;
                    break;
                }
            }
        }
        if (chosen == null) {
            return;
        }

        this.controller = chosen;
        this.nextLivenessCheckAt = System.currentTimeMillis() + LIVENESS_INTERVAL_MS;
        PadLayout.Family forced = config.resolveForcedFamily();
        PadLayout layout = PadLayout.of(chosen, forced, config.swapTriggerAxis);
        this.state.setLayout(layout);
        this.state.reset();
        RetroPad.LOGGER.info("Gamepad connected: \"" + chosen.getName() + "\" as " + layout.getFamily()
                + " (" + chosen.getButtonCount() + " buttons, " + chosen.getAxisCount() + " axes)");
        if (layout.hasSharedTriggerAxis()) {
            RetroPad.LOGGER.info("This pad reports both triggers on one axis; "
                    + "they cannot be pressed independently.");
        }
    }

    private void dropDevice(long now) {
        this.controller = null;
        this.state.reset();
        // Start the backoff over: a pad that has just been unplugged is the most likely one to
        // be plugged back in a second later.
        this.rescanInterval = RESCAN_INTERVAL_MS;
        this.nextRescanAt = now + this.rescanInterval;
        this.deviceListFresh = false;
        // Leave the subsystem up: rescan() resets it properly, and Controllers.destroy() would
        // not have torn anything down anyway.
    }

    private static boolean looksLikeGamepad(Controller controller) {
        return controller.getButtonCount() >= MIN_GAMEPAD_BUTTONS
                && controller.getAxisCount() >= MIN_GAMEPAD_AXES;
    }

    /** Names of every enumerated device, for the options screen and for /pad diagnostics. */
    public String[] listDevices() {
        if (!this.subsystemUp || this.unavailable) {
            return new String[0];
        }
        int count = Controllers.getControllerCount();
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            names[i] = Controllers.getController(i).getName();
        }
        return names;
    }

    /** Forces the next update to re-pick a device, e.g. after the config changed. */
    public void invalidate() {
        this.controller = null;
        this.state.reset();
        this.nextRescanAt = 0L;
        this.rescanInterval = RESCAN_INTERVAL_MS;
    }
}
