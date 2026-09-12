package io.github.retropad.input;

/** Logical analog axes exposed to the rest of the mod. */
public enum PadAxis {
    LEFT_X,
    LEFT_Y,
    RIGHT_X,
    RIGHT_Y,
    TRIGGER_L,
    TRIGGER_R;

    public static final PadAxis[] VALUES = values();
}
