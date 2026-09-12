package io.github.retropad.input;

import org.lwjgl.input.Controller;

import java.util.Locale;

/**
 * Maps a physical controller onto {@link PadButton} / {@link PadAxis}.
 *
 * <p>JInput talks DirectInput on Windows, so button and axis numbering is vendor specific.
 * Two families cover everything people actually plug in: Xbox pads (360, One, Series) and
 * PlayStation pads (DualShock 3/4, DualSense). Anything else falls back to the Xbox-ish
 * ordering DirectInput drivers converged on.
 */
public final class PadLayout {

    public enum Family {
        XBOX,
        PLAYSTATION,
        GENERIC
    }

    /**
     * Where an analog value comes from. LWJGL 2 names the six DirectInput axes after their
     * JInput identifiers, so we address them by role instead of by index.
     *
     * <p>Xbox pads under DirectInput merge both triggers onto a single Z axis: one trigger
     * pushes it positive, the other negative, and pressing both cancels out. That is a driver
     * limitation, not something the mod can work around, hence {@link #Z_POSITIVE} /
     * {@link #Z_NEGATIVE}.
     */
    private enum Source {
        X,
        Y,
        Z,
        RX,
        RY,
        RZ,
        Z_POSITIVE,
        Z_NEGATIVE,
        NONE
    }

    private static final int NO_BUTTON = -1;

    private final Family family;
    private final String deviceName;
    private final int[] buttons = new int[PadButton.VALUES.length];
    private final Source[] axes = new Source[PadAxis.VALUES.length];
    private final boolean[] invert = new boolean[PadAxis.VALUES.length];

    private PadLayout(Family family, String deviceName) {
        this.family = family;
        this.deviceName = deviceName;
        java.util.Arrays.fill(this.buttons, NO_BUTTON);
        java.util.Arrays.fill(this.axes, Source.NONE);
    }

    public Family getFamily() {
        return this.family;
    }

    public String getDeviceName() {
        return this.deviceName;
    }

    /** Physical button index for a logical button, or -1 when the pad has no such button. */
    public int buttonIndex(PadButton button) {
        return this.buttons[button.ordinal()];
    }

    public boolean hasButton(PadButton button) {
        return this.buttons[button.ordinal()] != NO_BUTTON;
    }

    /**
     * Reads a logical axis in [-1, 1]. Triggers are normalised to [0, 1].
     * Deadzones are not applied here — {@link GamepadState} owns that.
     */
    public float readAxis(Controller controller, PadAxis axis) {
        Source source = this.axes[axis.ordinal()];
        float value = read(controller, source);
        if (axis == PadAxis.TRIGGER_L || axis == PadAxis.TRIGGER_R) {
            // The split-Z sources already come out as a positive magnitude.
            return source == Source.Z_POSITIVE || source == Source.Z_NEGATIVE
                    ? value : this.normalizeTrigger(value);
        }
        return this.invert[axis.ordinal()] ? -value : value;
    }

    private static float read(Controller controller, Source source) {
        switch (source) {
            case X:  return controller.getXAxisValue();
            case Y:  return controller.getYAxisValue();
            case Z:  return controller.getZAxisValue();
            case RX: return controller.getRXAxisValue();
            case RY: return controller.getRYAxisValue();
            case RZ: return controller.getRZAxisValue();
            case Z_POSITIVE: return Math.max(0.0F, controller.getZAxisValue());
            case Z_NEGATIVE: return Math.max(0.0F, -controller.getZAxisValue());
            default: return 0.0F;
        }
    }

    /** True when both triggers share one axis and therefore cannot be read independently. */
    public boolean hasSharedTriggerAxis() {
        return this.axes[PadAxis.TRIGGER_L.ordinal()] == Source.Z_POSITIVE
                || this.axes[PadAxis.TRIGGER_L.ordinal()] == Source.Z_NEGATIVE;
    }

    public static Family detectFamily(String name) {
        String id = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (id.contains("xbox") || id.contains("x-box") || id.contains("xinput")) {
            return Family.XBOX;
        }
        // DualShock 4 and DualSense both enumerate as "Wireless Controller" over USB.
        if (id.contains("dualshock") || id.contains("dual shock") || id.contains("dualsense")
                || id.contains("playstation") || id.contains("wireless controller")
                || id.contains("sony") || id.contains("ps4") || id.contains("ps5")) {
            return Family.PLAYSTATION;
        }
        return Family.GENERIC;
    }

    public static PadLayout of(Controller controller, Family forced, boolean swapTriggerAxis) {
        String name = controller.getName();
        Family family = forced != null ? forced : detectFamily(name);
        PadLayout layout = new PadLayout(family, name);
        switch (family) {
            case PLAYSTATION:
                layout.playstation();
                break;
            case XBOX:
            case GENERIC:
            default:
                layout.xbox(swapTriggerAxis);
                break;
        }
        layout.clampToDevice(controller);
        return layout;
    }

    /**
     * Xbox 360/One/Series under DirectInput, and the de-facto default for no-name pads.
     * Triggers share the Z axis; which half is which flips between driver versions, so the
     * assignment is configurable.
     */
    private void xbox(boolean swapTriggerAxis) {
        this.buttons[PadButton.SOUTH.ordinal()]  = 0;
        this.buttons[PadButton.EAST.ordinal()]   = 1;
        this.buttons[PadButton.WEST.ordinal()]   = 2;
        this.buttons[PadButton.NORTH.ordinal()]  = 3;
        this.buttons[PadButton.L1.ordinal()]     = 4;
        this.buttons[PadButton.R1.ordinal()]     = 5;
        this.buttons[PadButton.SELECT.ordinal()] = 6;
        this.buttons[PadButton.START.ordinal()]  = 7;
        this.buttons[PadButton.L3.ordinal()]     = 8;
        this.buttons[PadButton.R3.ordinal()]     = 9;
        this.buttons[PadButton.GUIDE.ordinal()]  = 10;

        this.axes[PadAxis.LEFT_X.ordinal()]  = Source.X;
        this.axes[PadAxis.LEFT_Y.ordinal()]  = Source.Y;
        this.axes[PadAxis.RIGHT_X.ordinal()] = Source.RX;
        this.axes[PadAxis.RIGHT_Y.ordinal()] = Source.RY;
        this.axes[PadAxis.TRIGGER_L.ordinal()] = swapTriggerAxis ? Source.Z_NEGATIVE : Source.Z_POSITIVE;
        this.axes[PadAxis.TRIGGER_R.ordinal()] = swapTriggerAxis ? Source.Z_POSITIVE : Source.Z_NEGATIVE;
    }

    /**
     * DualShock 4 / DualSense in DirectInput mode. Face buttons are ordered
     * Square, Cross, Circle, Triangle, and the triggers are both digital buttons
     * and full analog axes.
     */
    private void playstation() {
        this.buttons[PadButton.WEST.ordinal()]   = 0; // Square
        this.buttons[PadButton.SOUTH.ordinal()]  = 1; // Cross
        this.buttons[PadButton.EAST.ordinal()]   = 2; // Circle
        this.buttons[PadButton.NORTH.ordinal()]  = 3; // Triangle
        this.buttons[PadButton.L1.ordinal()]     = 4;
        this.buttons[PadButton.R1.ordinal()]     = 5;
        this.buttons[PadButton.L2.ordinal()]     = 6;
        this.buttons[PadButton.R2.ordinal()]     = 7;
        this.buttons[PadButton.SELECT.ordinal()] = 8; // Share / Create
        this.buttons[PadButton.START.ordinal()]  = 9; // Options
        this.buttons[PadButton.L3.ordinal()]     = 10;
        this.buttons[PadButton.R3.ordinal()]     = 11;
        this.buttons[PadButton.GUIDE.ordinal()]  = 12; // PS button

        this.axes[PadAxis.LEFT_X.ordinal()]  = Source.X;
        this.axes[PadAxis.LEFT_Y.ordinal()]  = Source.Y;
        this.axes[PadAxis.RIGHT_X.ordinal()] = Source.Z;
        this.axes[PadAxis.RIGHT_Y.ordinal()] = Source.RZ;
        this.axes[PadAxis.TRIGGER_L.ordinal()] = Source.RX;
        this.axes[PadAxis.TRIGGER_R.ordinal()] = Source.RY;
        // DirectInput reports the analog triggers centred at -1 and pressed at +1.
        this.triggerRange = TriggerRange.SIGNED;
    }

    /** How a dedicated analog trigger axis encodes "released". */
    private enum TriggerRange {
        UNSIGNED,
        SIGNED
    }

    private TriggerRange triggerRange = TriggerRange.UNSIGNED;

    /** Normalises a trigger reading into [0, 1]. */
    public float normalizeTrigger(float raw) {
        if (this.triggerRange == TriggerRange.SIGNED) {
            return Math.max(0.0F, Math.min(1.0F, (raw + 1.0F) * 0.5F));
        }
        return Math.max(0.0F, Math.min(1.0F, raw));
    }

    /** Drops mappings the connected device does not actually expose. */
    private void clampToDevice(Controller controller) {
        int buttonCount = controller.getButtonCount();
        for (int i = 0; i < this.buttons.length; i++) {
            if (this.buttons[i] >= buttonCount) {
                this.buttons[i] = NO_BUTTON;
            }
        }
    }

    @Override
    public String toString() {
        return "PadLayout{" + this.family + " \"" + this.deviceName + "\"}";
    }
}
