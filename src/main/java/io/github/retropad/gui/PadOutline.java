package io.github.retropad.gui;

import net.minecraft.client.gui.Gui;

/**
 * The selection outline: a square that grows onto whatever is highlighted, waits, then pulses.
 *
 * <p>One implementation shared by the crafting menu and by ordinary screens, so the whole mod
 * highlights things the same way. Everything is derived from the clock rather than stepped per
 * frame, so the speed does not change with the frame rate.
 */
public final class PadOutline {

    /** How long the square takes to shrink onto its target. */
    private static final long GROW_MS = 150L;
    /** How long it holds still before it starts pulsing. */
    private static final long BLINK_DELAY_MS = 650L;
    private static final long BLINK_PERIOD_MS = 900L;
    /** How far outside the target the square starts. */
    private static final float START_SPREAD = 4.0F;

    private PadOutline() {
        throw new AssertionError();
    }

    /**
     * @param since milliseconds since this target became the highlighted one
     */
    public static void draw(float x0, float y0, float x1, float y1, long since) {
        float grown = ease(Math.min(1.0F, since / (float) GROW_MS));
        float spread = START_SPREAD * (1.0F - grown);

        float alpha = 1.0F;
        if (since > BLINK_DELAY_MS) {
            double phase = (since - BLINK_DELAY_MS) / (double) BLINK_PERIOD_MS * Math.PI * 2.0;
            alpha = 0.45F + 0.55F * (float) ((Math.sin(phase) + 1.0) * 0.5);
        }
        int color = ((int) (alpha * 255.0F) << 24) | 0xFFFFFF;

        float left = x0 - spread;
        float top = y0 - spread;
        float right = x1 + spread;
        float bottom = y1 + spread;

        Gui.drawRect(left, top, right, top + 1.0F, color);
        Gui.drawRect(left, bottom - 1.0F, right, bottom, color);
        Gui.drawRect(left, top, left + 1.0F, bottom, color);
        Gui.drawRect(right - 1.0F, top, right, bottom, color);
    }

    /** Ease-out: quick at first, settling rather than stopping dead. */
    private static float ease(float t) {
        float inverse = 1.0F - t;
        return 1.0F - inverse * inverse;
    }
}
