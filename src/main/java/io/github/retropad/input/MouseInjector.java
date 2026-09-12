package io.github.retropad.input;

import io.github.retropad.RetroPad;
import org.lwjgl.input.Mouse;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

/**
 * Makes the pad drive the game's real mouse instead of a second, painted-on cursor.
 *
 * <p>Half of the game's screens never see a synthetic {@code mouseClicked} call: list widgets
 * like the world select screen poll {@link Mouse#isButtonDown(int)} straight from their draw
 * method. So moving the cursor is not enough — the button state LWJGL reports has to change
 * too, and LWJGL keeps that in a private buffer with no setter. Reflection is the only way in,
 * and it is contained entirely in this class.
 */
public final class MouseInjector {

    private static Field buttonsField;
    private static boolean unavailable;

    private MouseInjector() {
        throw new AssertionError();
    }

    /** Moves the hardware cursor. Coordinates are LWJGL display pixels, origin bottom-left. */
    public static void setCursorPosition(int x, int y) {
        try {
            Mouse.setCursorPosition(x, y);
        } catch (Throwable throwable) {
            RetroPad.LOGGER.warn("Could not move the cursor: " + throwable);
        }
    }

    /**
     * Holds or releases a mouse button as far as the rest of the game can tell.
     * Returns false if LWJGL's internals could not be reached, in which case callers should
     * fall back to calling the screen's click methods and accept that lists will not respond.
     */
    public static boolean setButton(int button, boolean down) {
        ByteBuffer buttons = buttons();
        if (buttons == null || button < 0 || button >= buttons.capacity()) {
            return false;
        }
        buttons.put(button, (byte) (down ? 1 : 0));
        return true;
    }

    public static boolean isAvailable() {
        return buttons() != null;
    }

    private static ByteBuffer buttons() {
        if (unavailable) {
            return null;
        }
        try {
            if (buttonsField == null) {
                Field field = Mouse.class.getDeclaredField("buttons");
                field.setAccessible(true);
                buttonsField = field;
            }
            return (ByteBuffer) buttonsField.get(null);
        } catch (Throwable throwable) {
            // A future LWJGL could rename the field; degrade instead of spamming every frame.
            RetroPad.LOGGER.warn("Cannot reach LWJGL mouse button state, "
                    + "list widgets will not respond to the pad: " + throwable);
            unavailable = true;
            return null;
        }
    }
}
