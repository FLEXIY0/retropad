package io.github.retropad.input;

/**
 * Logical gamepad buttons, named by position rather than by vendor label.
 * SOUTH/EAST/WEST/NORTH are A/B/X/Y on Xbox and Cross/Circle/Square/Triangle on PlayStation.
 */
public enum PadButton {
    SOUTH,
    EAST,
    WEST,
    NORTH,
    L1,
    R1,
    L2,
    R2,
    SELECT,
    START,
    L3,
    R3,
    GUIDE,
    DPAD_UP,
    DPAD_DOWN,
    DPAD_LEFT,
    DPAD_RIGHT,
    /**
     * Menu navigation: the d-pad or the left stick pushed far enough to count as a direction.
     * Kept separate from {@link #DPAD_UP} and friends so that walking in the world never
     * reads as a menu keypress.
     */
    NAV_UP,
    NAV_DOWN,
    NAV_LEFT,
    NAV_RIGHT;

    public static final PadButton[] VALUES = values();

    /** True for buttons this mod synthesises from analog triggers or the POV hat. */
    public boolean isSynthetic() {
        return this == L2 || this == R2 || this.ordinal() >= DPAD_UP.ordinal();
    }

    /** True for the four menu-navigation pseudo-buttons. */
    public boolean isNavigation() {
        return this.ordinal() >= NAV_UP.ordinal();
    }
}
