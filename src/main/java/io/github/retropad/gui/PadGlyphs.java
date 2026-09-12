package io.github.retropad.gui;

import io.github.retropad.PadConfig;
import io.github.retropad.RetroPad;
import io.github.retropad.craft.LegacySkin;
import io.github.retropad.input.PadLayout;
import net.minecraft.client.Minecraft;

/**
 * Button glyphs, drawn from Better Than Legacy's own sheet.
 *
 * <p>The sheet is a grid: one column per button and one row per style of controller, each cell
 * 13 pixels square. So a DualShock's cross and an Xbox pad's A are the same column on different
 * rows, and showing the right one is a matter of picking the row that matches what is plugged in.
 */
public final class PadGlyphs {

    public static final int A = 0;
    public static final int B = 1;
    public static final int X = 2;
    public static final int Y = 3;
    public static final int SELECT = 4;
    public static final int HOME = 5;
    public static final int START = 6;
    public static final int STICK_LEFT = 7;
    public static final int STICK_RIGHT = 8;
    public static final int LB = 9;
    public static final int RB = 10;
    public static final int LT = 11;
    public static final int RT = 12;
    public static final int DPAD_UP = 13;
    public static final int DPAD_DOWN = 14;
    public static final int DPAD_LEFT = 15;
    public static final int DPAD_RIGHT = 16;

    public static final int SIZE = 13;

    private static final float ATLAS = 256.0F;
    private static final String PATH = "/textures/gui/retropad/buttons.png";

    /** Rows of the sheet, in the order the original lists its controller types. */
    private static final int ROW_GENERIC = 0;
    private static final int ROW_DUAL_SHOCK_4 = 2;
    private static final int ROW_XBOX_ONE = 4;

    private PadGlyphs() {
        throw new AssertionError();
    }

    public static void draw(Minecraft mc, int button, float x, float y) {
        mc.renderEngine.bindTexture(mc.renderEngine.getTexture(PATH));
        LegacySkin.blitScaled(x, y, SIZE, SIZE, button * SIZE, row() * SIZE, SIZE, SIZE, ATLAS);
    }

    /** Which row of the sheet suits the connected pad. */
    private static int row() {
        PadConfig config = RetroPad.CONFIG;
        switch (config.controllerType) {
            case XBOX:
                return ROW_XBOX_ONE;
            case PLAYSTATION:
                return ROW_DUAL_SHOCK_4;
            case GENERIC:
                return ROW_GENERIC;
            case AUTO:
            default:
                break;
        }
        if (RetroPad.gamepad() == null) {
            return ROW_GENERIC;
        }
        PadLayout layout = RetroPad.gamepad().getState().getLayout();
        if (layout == null) {
            return ROW_GENERIC;
        }
        switch (layout.getFamily()) {
            case XBOX:
                return ROW_XBOX_ONE;
            case PLAYSTATION:
                return ROW_DUAL_SHOCK_4;
            default:
                return ROW_GENERIC;
        }
    }
}
