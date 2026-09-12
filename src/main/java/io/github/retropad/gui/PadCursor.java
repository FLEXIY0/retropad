package io.github.retropad.gui;

import io.github.retropad.RetroPad;
import io.github.retropad.game.PadScreenInput;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiContainer;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Cursor;
import org.lwjgl.input.Mouse;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * The pad's pointer: the system cursor hidden, and a crosshair drawn in its place.
 *
 * <p>The pad still drives the real mouse — that is what makes hover, tooltips and list widgets
 * work — so this only changes what the pointer *looks* like. Hiding is done with a fully
 * transparent hardware cursor rather than by grabbing the mouse, which would stop the game
 * tracking its position at all.
 */
public final class PadCursor {

    private static final int OUTLINE = 0xFF000000;
    private static final int FILL = 0xFFFFFFFF;
    /** Length of each crosshair arm in GUI pixels. */
    private static final float ARM = 5.0F;

    private static Cursor blankCursor;
    private static boolean hidden;
    private static boolean unavailable;

    private PadCursor() {
        throw new AssertionError();
    }

    /**
     * Whether a pointer belongs on this screen at all.
     *
     * <p>A menu of buttons is driven by the highlight jumping between them, and a pointer there
     * is a second thing to follow that says nothing the highlight does not. An inventory is the
     * opposite: it is a grid of things to point at, and the stack under the pointer is the whole
     * question, so that one keeps its crosshair.
     */
    public static boolean wanted(GuiScreen screen) {
        if (!RetroPad.CONFIG.padCursor) {
            return false;
        }
        if (!RetroPad.CONFIG.hideCursorInMenus || screen instanceof GuiContainer) {
            return true;
        }
        // Moving the stick is asking for a pointer: it is the only way to reach anything the
        // highlight does not jump to. Navigating with the d-pad is not, so the crosshair fades
        // out again once the stick has been still for a moment.
        PadScreenInput input = RetroPad.screenInput();
        return input != null && input.isPointing();
    }

    /** Hides the system pointer, if it is not already hidden. */
    public static void hide() {
        if (hidden || unavailable) {
            return;
        }
        try {
            if (blankCursor == null) {
                blankCursor = createBlankCursor();
            }
            Mouse.setNativeCursor(blankCursor);
            hidden = true;
        } catch (Throwable throwable) {
            // Some drivers refuse custom cursors; keep the system one rather than lose it.
            RetroPad.LOGGER.warn("Could not hide the system cursor: " + throwable);
            unavailable = true;
        }
    }

    /**
     * Forgets that the pointer was hidden, so the next request applies the blank cursor again.
     *
     * <p>Rebuilding the display — a resize, a jump to fullscreen — drops the cursor the window
     * was given without telling anyone, and a flag saying it is still hidden would keep this
     * from ever putting it back.
     */
    public static void reassert() {
        hidden = false;
    }

    /** Gives the system pointer back. Safe to call when it was never hidden. */
    public static void restore() {
        if (!hidden) {
            return;
        }
        hidden = false;
        try {
            Mouse.setNativeCursor(null);
        } catch (Throwable throwable) {
            RetroPad.LOGGER.warn("Could not restore the system cursor: " + throwable);
        }
    }

    /**
     * A cursor of entirely transparent pixels. Windows rejects arbitrary sizes, so this asks
     * LWJGL what the driver's minimum is instead of guessing 1x1.
     */
    private static Cursor createBlankCursor() throws Exception {
        int size = Math.max(Cursor.getMinCursorSize(), 1);
        IntBuffer pixels = ByteBuffer
                .allocateDirect(size * size * 4)
                .order(ByteOrder.nativeOrder())
                .asIntBuffer();
        while (pixels.hasRemaining()) {
            pixels.put(0);
        }
        pixels.flip();
        return new Cursor(size, size, 0, size - 1, 1, pixels, null);
    }

    /**
     * Draws the crosshair at the pointer. Coordinates are in GUI space, which is where the
     * screen being drawn already is.
     *
     * <p>Container screens leave the renderer set up for three-dimensional item models —
     * lighting on, depth testing on — and a flat rectangle drawn in that state comes out dim
     * and can be buried behind an item. Menus leave none of that on, which is exactly why the
     * same crosshair looked good in one place and wrong in the other. Reset the pieces that
     * matter, then hand the state back the way it was found.
     */
    public static void draw(float x, float y) {
        // Render state is set up by PadRenderState around the whole overlay pass.
        // Outline first, then a thinner bright core, so it stays readable over an item slot.
        Gui.drawRect(x - ARM - 1.0F, y - 1.0F, x + ARM + 1.0F, y + 1.0F, OUTLINE);
        Gui.drawRect(x - 1.0F, y - ARM - 1.0F, x + 1.0F, y + ARM + 1.0F, OUTLINE);
        Gui.drawRect(x - ARM, y - 0.5F, x + ARM, y + 0.5F, FILL);
        Gui.drawRect(x - 0.5F, y - ARM, x + 0.5F, y + ARM, FILL);
    }
}
