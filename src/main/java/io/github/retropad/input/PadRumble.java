package io.github.retropad.input;

import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;
import io.github.retropad.RetroPad;

import java.util.Arrays;
import java.util.List;

/**
 * Vibration, asked for from Windows rather than from the input library.
 *
 * <p>JInput reports zero rumblers for an XInput pad — checked on the hardware, not assumed — so
 * the motors are unreachable through the same path the buttons come down. XInput itself does
 * expose them, and it is part of Windows, so no driver has to be shipped: the only cost is the
 * call into it.
 *
 * <p>Effects are short and one at a time. A running effect is only interrupted by one at least
 * as strong, so the tap for tidying a chest cannot cut off the thump for being hit.
 */
public final class PadRumble {

    /** What a moment in the game feels like. Left motor is the heavy one, right is the buzz. */
    public enum Effect {
        /** Taking a hit: the one thing worth feeling through the whole pad. */
        HURT(0.90F, 0.55F, 260L),
        /** Landing a hit on something. */
        HIT(0.00F, 0.55F, 90L),
        /** A block finally giving way. */
        BREAK(0.28F, 0.22F, 55L),
        /** Something made. */
        CRAFT(0.00F, 0.45F, 70L),
        /** A menu did something bigger than move a cursor. */
        UI(0.00F, 0.30F, 45L),
        /** A pad has just been found. */
        CONNECT(0.40F, 0.40F, 180L),
        /** Tool against stone, repeated for as long as the block holds out. */
        MINE(0.10F, 0.13F, 70L),
        /** A block going down. Lighter than breaking one, the way it is quieter. */
        PLACE(0.18F, 0.08F, 45L),
        /** A bite, a swallow. Repeated while the trigger is held. */
        USE(0.00F, 0.16F, 60L),
        /** One beat of your own heart, felt when there is little of it left. */
        HEARTBEAT(0.60F, 0.00F, 110L),
        /** Breath dragged in after a run, all weight and no buzz. */
        BREATH(0.34F, 0.00F, 230L);

        private final float low;
        private final float high;
        private final long duration;

        Effect(float low, float high, long duration) {
            this.low = low;
            this.high = high;
            this.duration = duration;
        }

        float strength() {
            return Math.max(this.low, this.high);
        }
    }

    /** How often the port is looked for again once nothing answers on it. */
    private static final long REDETECT_INTERVAL_MS = 2000L;
    private static final int MOTOR_MAX = 0xFFFF;
    /** What XInput returns when a call worked. */
    private static final int OK = 0;
    private static final int NO_PORT = -1;

    private static XInput library;
    private static boolean unavailable;
    private static int port = NO_PORT;
    private static long nextDetectAt;

    private static Effect current;
    private static long stopAt;
    private static boolean motorsOn;
    private static float currentScale = 1.0F;
    /** A second beat waiting its turn, which is what makes a heartbeat a heartbeat. */
    private static Effect pending;
    private static float pendingIntensity = 1.0F;
    private static long pendingAt;
    /**
     * When each repeating effect may fire again.
     *
     * <p>One shared timer would have mining, eating and breathing taking turns at each other's
     * expense — whichever asked first would silence the others until its gap ran out.
     */
    private static final long[] nextPulseAt = new long[Effect.values().length];

    private PadRumble() {
        throw new AssertionError();
    }

    /**
     * Starts an effect, unless something at least as strong is still running.
     *
     * <p>Never throws: a machine without XInput, or a pad on no port at all, simply does not
     * vibrate, and the game carries on.
     */
    public static void play(Effect effect) {
        play(effect, 1.0F);
    }

    /**
     * Starts an effect at a fraction of its strength, so a graze and a fall from a cliff are
     * not the same thump.
     */
    public static void play(Effect effect, float intensity) {
        if (effect == null || !RetroPad.CONFIG.rumble || unavailable) {
            return;
        }
        float wanted = clamp(intensity);
        if (current != null && System.currentTimeMillis() < stopAt
                && current.strength() * currentScale > effect.strength() * wanted) {
            return;
        }
        current = effect;
        currentScale = wanted;
        stopAt = System.currentTimeMillis() + effect.duration;
        float scale = clamp(RetroPad.CONFIG.rumbleStrength) * wanted;
        set(effect.low * scale, effect.high * scale);
    }

    /**
     * An effect for something that goes on rather than happens once — mining, eating — asked
     * for every frame but felt as a steady tick rather than one long buzz.
     */
    public static void pulse(Effect effect, long gapMs) {
        pulse(effect, gapMs, 1.0F);
    }

    /** The same, at a fraction of the effect's strength. */
    public static void pulse(Effect effect, long gapMs, float intensity) {
        long now = System.currentTimeMillis();
        int index = effect.ordinal();
        if (now < nextPulseAt[index]) {
            return;
        }
        nextPulseAt[index] = now + gapMs;
        play(effect, intensity);
    }

    /** Two beats, the second one following on its own. */
    public static void playTwice(Effect effect, long gapMs) {
        playTwice(effect, gapMs, 1.0F);
    }

    public static void playTwice(Effect effect, long gapMs, float intensity) {
        play(effect, intensity);
        pending = effect;
        pendingIntensity = intensity;
        pendingAt = System.currentTimeMillis() + gapMs;
    }

    /** A pair of beats, repeated on an interval: a heartbeat, or a breath. */
    public static void pulseTwice(Effect effect, long gapMs, long betweenMs) {
        long now = System.currentTimeMillis();
        int index = effect.ordinal();
        if (now < nextPulseAt[index]) {
            return;
        }
        nextPulseAt[index] = now + gapMs;
        playTwice(effect, betweenMs);
    }

    /** Called every frame. Ends the current effect when its time is up. */
    public static void update(long now) {
        if (pending != null && now >= pendingAt) {
            Effect second = pending;
            float intensity = pendingIntensity;
            pending = null;
            pendingIntensity = 1.0F;
            play(second, intensity);
            return;
        }
        if (!motorsOn) {
            return;
        }
        if (!RetroPad.CONFIG.rumble || now >= stopAt) {
            stop();
        }
    }

    /** Silences the motors. Safe to call at any time, including when there is no pad. */
    public static void stop() {
        current = null;
        pending = null;
        if (motorsOn) {
            set(0.0F, 0.0F);
            motorsOn = false;
        }
    }

    // ----------------------------------------------------------------- native

    private static void set(float low, float high) {
        XInput xinput = library();
        if (xinput == null) {
            return;
        }
        int index = port(xinput);
        if (index == NO_PORT) {
            return;
        }
        Vibration vibration = new Vibration();
        vibration.left = motor(low);
        vibration.right = motor(high);
        try {
            if (xinput.XInputSetState(index, vibration) != OK) {
                // The pad moved ports or went away; find it again on the next effect.
                port = NO_PORT;
                nextDetectAt = System.currentTimeMillis() + REDETECT_INTERVAL_MS;
                return;
            }
        } catch (Throwable throwable) {
            RetroPad.LOGGER.warn("Rumble failed; switching it off for this session", throwable);
            unavailable = true;
            return;
        }
        motorsOn = low > 0.0F || high > 0.0F;
    }

    /** Which XInput port the pad answers on. Windows numbers them itself; ours is whichever replies. */
    private static int port(XInput xinput) {
        if (port != NO_PORT) {
            return port;
        }
        long now = System.currentTimeMillis();
        if (now < nextDetectAt) {
            return NO_PORT;
        }
        nextDetectAt = now + REDETECT_INTERVAL_MS;
        for (int i = 0; i < 4; i++) {
            try {
                if (xinput.XInputGetState(i, new XInputState()) == OK) {
                    port = i;
                    return port;
                }
            } catch (Throwable throwable) {
                RetroPad.LOGGER.warn("XInput would not answer; rumble is off", throwable);
                unavailable = true;
                return NO_PORT;
            }
        }
        return NO_PORT;
    }

    private static XInput library() {
        if (library != null || unavailable) {
            return library;
        }
        // Newest first. 9_1_0 ships with every Windows since Vista and is the last resort.
        for (String name : new String[]{"XInput1_4", "XInput1_3", "XInput9_1_0"}) {
            try {
                library = Native.load(name, XInput.class);
                RetroPad.LOGGER.info("Rumble using " + name);
                // Whatever happens to the game, the motors must not be left running.
                Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                    @Override
                    public void run() {
                        stop();
                    }
                }, "retropad-rumble-off"));
                return library;
            } catch (Throwable ignored) {
                // Try the next one.
            }
        }
        RetroPad.LOGGER.info("No XInput on this machine; the pad will not vibrate");
        unavailable = true;
        return null;
    }

    private static short motor(float amount) {
        return (short) Math.round(clamp(amount) * MOTOR_MAX);
    }

    private static float clamp(float value) {
        if (value < 0.0F) {
            return 0.0F;
        }
        return value > 1.0F ? 1.0F : value;
    }

    // -------------------------------------------------------------- bindings

    /** The two XInput calls this needs, and nothing else. */
    public interface XInput extends StdCallLibrary {

        int XInputGetState(int userIndex, XInputState state);

        int XInputSetState(int userIndex, Vibration vibration);
    }

    /** XINPUT_VIBRATION: two motor speeds, 0 to 65535. */
    public static class Vibration extends Structure {

        public short left;
        public short right;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("left", "right");
        }
    }

    /** XINPUT_STATE, only ever used to ask whether a port has anything on it. */
    public static class XInputState extends Structure {

        public int packetNumber;
        public short buttons;
        public byte leftTrigger;
        public byte rightTrigger;
        public short thumbLX;
        public short thumbLY;
        public short thumbRX;
        public short thumbRY;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("packetNumber", "buttons", "leftTrigger", "rightTrigger",
                    "thumbLX", "thumbLY", "thumbRX", "thumbRY");
        }
    }
}
