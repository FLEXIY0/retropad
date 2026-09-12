package io.github.retropad.input;

import org.lwjgl.input.Controller;

import java.util.Arrays;

/**
 * A polled snapshot of one gamepad, plus the edge/repeat bookkeeping every consumer needs.
 *
 * <p>Menus want "pressed this frame" and a key-repeat while held; the world wants smooth
 * analog values. Both are served from the same poll so the two never disagree within a frame.
 */
public final class GamepadState {

    /** Delay before a held direction starts repeating, in milliseconds. */
    /** How far a stick has to be pushed before it counts as someone using the pad. */
    private static final float ACTIVITY_AXIS = 0.30F;

    private static final long REPEAT_DELAY_MS = 340L;
    /** Interval between repeats once they start, in milliseconds. */
    private static final long REPEAT_INTERVAL_MS = 85L;
    /** A trigger counts as "pressed" past this much travel. */
    private static final float TRIGGER_PRESS_POINT = 0.5F;
    /**
     * ...and stays pressed until it falls back below this. The gap matters: an analog trigger
     * held near the threshold jitters across it many times a second, and without hysteresis
     * that turns a steady hold into a burst of press/release pairs.
     */
    private static final float TRIGGER_RELEASE_POINT = 0.35F;
    /**
     * How many consecutive polls must agree that a trigger came up before it counts as
     * released. A single dropped sample from the driver would otherwise end a hold, and the
     * game treats the end of a hold as "stop eating".
     */
    private static final int TRIGGER_RELEASE_POLLS = 3;
    /**
     * How long a trigger keeps its highest recent reading before a lower one is believed.
     *
     * <p>Some pads — a Flydigi in its generic DirectInput mode among them — report a held
     * trigger as an alternating 1, 0, 1, 0 instead of a steady 1. Every 0 in that stream looks
     * exactly like "the player let go", which ends an item's use, so a held trigger could never
     * finish eating. Holding the peak briefly turns that stream back into the hold it really is,
     * at the cost of the trigger releasing this much later than it physically did.
     */
    private static final long TRIGGER_LATCH_MS = 150L;
    /** A stick pushed this far acts as a d-pad direction in menus. */
    private static final float STICK_DIGITAL_POINT = 0.6F;

    private final boolean[] held = new boolean[PadButton.VALUES.length];
    private final boolean[] heldLast = new boolean[PadButton.VALUES.length];
    private final long[] nextRepeatAt = new long[PadButton.VALUES.length];
    private final float[] axes = new float[PadAxis.VALUES.length];
    private final int[] releasePolls = new int[PadButton.VALUES.length];
    /** Raw readings before latching, kept so the debug overlay can show driver jitter. */
    private final float[] rawAxes = new float[PadAxis.VALUES.length];
    private final float[] latchedTrigger = new float[PadAxis.VALUES.length];
    private final long[] latchedAt = new long[PadAxis.VALUES.length];

    private PadLayout layout;
    private float stickDeadzone = 0.20F;
    private float triggerDeadzone = 0.10F;

    void setLayout(PadLayout layout) {
        this.layout = layout;
    }

    public PadLayout getLayout() {
        return this.layout;
    }

    public void setDeadzones(float stick, float trigger) {
        this.stickDeadzone = clamp01(stick);
        this.triggerDeadzone = clamp01(trigger);
    }

    /** Re-reads every button and axis from the device. */
    void poll(Controller controller, long now) {
        System.arraycopy(this.held, 0, this.heldLast, 0, this.held.length);

        for (PadAxis axis : PadAxis.VALUES) {
            float raw = this.layout.readAxis(controller, axis);
            boolean isTrigger = axis == PadAxis.TRIGGER_L || axis == PadAxis.TRIGGER_R;
            float value = isTrigger
                    ? applyTriggerDeadzone(raw, this.triggerDeadzone)
                    : applyStickDeadzone(raw, this.stickDeadzone);
            this.rawAxes[axis.ordinal()] = value;
            this.axes[axis.ordinal()] = isTrigger ? this.latchTrigger(axis, value, now) : value;
        }

        for (PadButton button : PadButton.VALUES) {
            this.held[button.ordinal()] = readButton(controller, button);
        }

        for (int i = 0; i < this.held.length; i++) {
            if (!this.held[i]) {
                this.nextRepeatAt[i] = 0L;
            } else if (!this.heldLast[i]) {
                this.nextRepeatAt[i] = now + REPEAT_DELAY_MS;
            }
        }
    }

    private boolean readButton(Controller controller, PadButton button) {
        switch (button) {
            case DPAD_UP:
                return controller.getPovY() < -0.5F;
            case DPAD_DOWN:
                return controller.getPovY() > 0.5F;
            case DPAD_LEFT:
                return controller.getPovX() < -0.5F;
            case DPAD_RIGHT:
                return controller.getPovX() > 0.5F;
            case NAV_UP:
                return controller.getPovY() < -0.5F || this.axes[PadAxis.LEFT_Y.ordinal()] < -STICK_DIGITAL_POINT;
            case NAV_DOWN:
                return controller.getPovY() > 0.5F || this.axes[PadAxis.LEFT_Y.ordinal()] > STICK_DIGITAL_POINT;
            case NAV_LEFT:
                return controller.getPovX() < -0.5F || this.axes[PadAxis.LEFT_X.ordinal()] < -STICK_DIGITAL_POINT;
            case NAV_RIGHT:
                return controller.getPovX() > 0.5F || this.axes[PadAxis.LEFT_X.ordinal()] > STICK_DIGITAL_POINT;
            case L2:
                // PlayStation pads report the triggers as buttons too; prefer that when present.
                return this.layout.hasButton(PadButton.L2)
                        ? controller.isButtonPressed(this.layout.buttonIndex(PadButton.L2))
                        : this.triggerHeld(PadButton.L2, PadAxis.TRIGGER_L);
            case R2:
                return this.layout.hasButton(PadButton.R2)
                        ? controller.isButtonPressed(this.layout.buttonIndex(PadButton.R2))
                        : this.triggerHeld(PadButton.R2, PadAxis.TRIGGER_R);
            default:
                int index = this.layout.buttonIndex(button);
                return index >= 0 && controller.isButtonPressed(index);
        }
    }

    /**
     * Analog trigger as an on/off button, with a wider press threshold than release one.
     * {@code held} still carries the previous frame's value here, because the caller fills it
     * in from this method's result.
     */
    private boolean triggerHeld(PadButton button, PadAxis axis) {
        float travel = this.axes[axis.ordinal()];
        int index = button.ordinal();
        if (this.held[index]) {
            if (travel > TRIGGER_RELEASE_POINT) {
                this.releasePolls[index] = 0;
                return true;
            }
            return ++this.releasePolls[index] < TRIGGER_RELEASE_POLLS;
        }
        boolean pressed = travel > TRIGGER_PRESS_POINT;
        if (pressed) {
            this.releasePolls[index] = 0;
        }
        return pressed;
    }

    /** Keeps the highest recent trigger reading for a moment, to ride out dropped samples. */
    private float latchTrigger(PadAxis axis, float value, long now) {
        int index = axis.ordinal();
        if (value >= this.latchedTrigger[index]) {
            this.latchedTrigger[index] = value;
            this.latchedAt[index] = now;
            return value;
        }
        if (now - this.latchedAt[index] < TRIGGER_LATCH_MS) {
            return this.latchedTrigger[index];
        }
        this.latchedTrigger[index] = value;
        this.latchedAt[index] = now;
        return value;
    }

    /** The reading as the driver gave it, before latching. For diagnostics only. */
    public float getRawAxis(PadAxis axis) {
        return this.rawAxes[axis.ordinal()];
    }

    public boolean isHeld(PadButton button) {
        return this.held[button.ordinal()];
    }

    /** True on the frame the button went down. */
    public boolean isPressed(PadButton button) {
        int i = button.ordinal();
        return this.held[i] && !this.heldLast[i];
    }

    /** True on the frame the button came up. */
    public boolean isReleased(PadButton button) {
        int i = button.ordinal();
        return !this.held[i] && this.heldLast[i];
    }

    /**
     * True on the initial press and then at a steady rate while held — what menu navigation
     * wants so a held stick keeps moving the selection. Consumes the repeat slot, so call it
     * once per frame per button.
     */
    public boolean isPressedOrRepeated(PadButton button, long now) {
        int i = button.ordinal();
        if (!this.held[i]) {
            return false;
        }
        if (!this.heldLast[i]) {
            return true;
        }
        if (this.nextRepeatAt[i] != 0L && now >= this.nextRepeatAt[i]) {
            this.nextRepeatAt[i] = now + REPEAT_INTERVAL_MS;
            return true;
        }
        return false;
    }

    /**
     * True when the pad is actually being used, which is how it claims the screen back from the
     * mouse.
     *
     * <p>Buttons count when they change rather than while they are down, and a stick has to be
     * pushed properly rather than merely resting off centre. A pad that answers "yes, something
     * is held" forever — a trigger reading stuck, a worn stick sitting at a fifth of its travel
     * — would otherwise mean the mouse never got a turn, which is exactly what happened.
     */
    public boolean isAnyActivity() {
        for (int i = 0; i < this.held.length; i++) {
            if (this.held[i] != this.heldLast[i]) {
                return true;
            }
        }
        for (float value : this.axes) {
            if (Math.abs(value) > ACTIVITY_AXIS) {
                return true;
            }
        }
        return false;
    }

    public float getAxis(PadAxis axis) {
        return this.axes[axis.ordinal()];
    }

    /** Clears everything; used when the device goes away so nothing stays stuck down. */
    void reset() {
        Arrays.fill(this.held, false);
        Arrays.fill(this.heldLast, false);
        Arrays.fill(this.nextRepeatAt, 0L);
        Arrays.fill(this.axes, 0.0F);
        Arrays.fill(this.releasePolls, 0);
        Arrays.fill(this.rawAxes, 0.0F);
        Arrays.fill(this.latchedTrigger, 0.0F);
        Arrays.fill(this.latchedAt, 0L);
    }

    /**
     * Rescales the live part of the range so there is no jump at the edge of the deadzone.
     */
    private static float applyStickDeadzone(float value, float deadzone) {
        float magnitude = Math.abs(value);
        if (magnitude <= deadzone) {
            return 0.0F;
        }
        float scaled = (magnitude - deadzone) / (1.0F - deadzone);
        return Math.signum(value) * Math.min(1.0F, scaled);
    }

    private static float applyTriggerDeadzone(float value, float deadzone) {
        if (value <= deadzone) {
            return 0.0F;
        }
        return Math.min(1.0F, (value - deadzone) / (1.0F - deadzone));
    }

    private static float clamp01(float value) {
        return value < 0.0F ? 0.0F : (value > 0.9F ? 0.9F : value);
    }
}
