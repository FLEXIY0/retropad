package io.github.retropad.input;

import io.github.retropad.RetroPad;
import org.lwjgl.input.Mouse;

/**
 * Which hand is on the controls.
 *
 * <p>A pad interface drawn over a screen somebody is using with a mouse is clutter: the pointer
 * is duplicated, the prompts describe buttons nobody is pressing, and the highlight fights the
 * hover. So the moment the real mouse moves, the pad steps back and everything it draws goes
 * away; the first thing touched on the pad takes the screen straight back.
 *
 * <p>Telling a real mouse movement from this mod's own is a matter of remembering where the
 * pointer was put. Anything else that moves it was a hand.
 */
public final class PadInputMode {

    /** A jitter of a pixel or two is a resting hand, not an attempt to take over. */
    private static final int MOUSE_SLACK = 3;

    /**
     * How long after moving the pointer ourselves to disregard where it is.
     *
     * <p>A warp is not instant from the operating system's point of view: the motion event it
     * causes comes back a frame or two later, and until it does the position read here can be
     * either the old one or the new one. Comparing positions across that gap reads as a hand on
     * the mouse, which is why jumping between buttons with the d-pad used to summon an arrow.
     */
    private static final long SETTLE_MS = 350L;

    private static int seenX = Integer.MIN_VALUE;
    private static int seenY = Integer.MIN_VALUE;
    private static long settleUntil;
    private static long lastPadAt;
    private static boolean mouseDriving;

    private PadInputMode() {
        throw new AssertionError();
    }

    /** True while the pad owns the screen and its interface should be drawn. */
    public static boolean padDriving() {
        return !mouseDriving || !RetroPad.CONFIG.yieldToMouse;
    }

    /** Notes that this mod moved the pointer, so the movement that follows is its own. */
    public static void noteCursorWrite(int x, int y) {
        seenX = x;
        seenY = y;
        settleUntil = System.currentTimeMillis() + SETTLE_MS;
    }

    /**
     * Works out who is driving, once per frame. Pad activity always wins: it is a deliberate
     * act, while a mouse can be nudged by the desk.
     */
    public static void update(GamepadState state) {
        int x;
        int y;
        try {
            x = Mouse.getX();
            y = Mouse.getY();
        } catch (Throwable ignored) {
            return;
        }
        if (state != null && state.isAnyActivity()) {
            mouseDriving = false;
            lastPadAt = System.currentTimeMillis();
            seenX = x;
            seenY = y;
            return;
        }
        if (seenX == Integer.MIN_VALUE || System.currentTimeMillis() < settleUntil) {
            seenX = x;
            seenY = y;
            return;
        }
        if (Math.abs(x - seenX) > MOUSE_SLACK || Math.abs(y - seenY) > MOUSE_SLACK) {
            mouseDriving = true;
        }
        seenX = x;
        seenY = y;
    }

    /**
     * Whether the pad has been touched lately.
     *
     * <p>Nothing this mod does should move the pointer on its own while the pad is sitting on
     * the table: a menu opened with the mouse belongs to the mouse, and dragging the pointer to
     * a button the moment a screen appears is how it ended up pinned in a corner.
     */
    public static boolean padRecentlyUsed(long withinMs) {
        return lastPadAt != 0L && System.currentTimeMillis() - lastPadAt < withinMs;
    }

    /** Called when a screen opens or closes, so a stale position cannot trigger a false switch. */
    public static void forget() {
        seenX = Integer.MIN_VALUE;
        seenY = Integer.MIN_VALUE;
        settleUntil = System.currentTimeMillis() + SETTLE_MS;
    }
}
