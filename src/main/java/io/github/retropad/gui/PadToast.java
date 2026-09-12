package io.github.retropad.gui;

import io.github.retropad.input.PadRumble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;

/**
 * A line of text that says what a button just did, and then gets out of the way.
 *
 * <p>A press that rearranges a whole chest needs to admit that it did something, or it reads as
 * a bug the first time nothing obvious moves. It sits just above the prompt row, fades out on
 * its own, and is never something the player has to dismiss.
 */
public final class PadToast {

    private static final long LIFETIME_MS = 2200L;
    private static final long FADE_MS = 500L;
    private static final int COLOR = 0xFFFFFFFF;
    /** Clear of the prompt row below it. */
    private static final int ABOVE_PROMPTS = 20;

    private static String message;
    private static long shownAt;

    private PadToast() {
        throw new AssertionError();
    }

    /** Shows a line. A null message means "nothing worth saying", and clears any old one. */
    public static void show(String text) {
        message = text;
        shownAt = System.currentTimeMillis();
        if (text != null) {
            PadRumble.play(PadRumble.Effect.UI);
        }
    }

    public static void render(GuiScreen screen) {
        if (message == null) {
            return;
        }
        long age = System.currentTimeMillis() - shownAt;
        if (age > LIFETIME_MS) {
            message = null;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        FontRenderer font = mc.fontRenderer;
        if (font == null) {
            return;
        }
        float fade = age > LIFETIME_MS - FADE_MS ? (LIFETIME_MS - age) / (float) FADE_MS : 1.0F;
        int color = ((int) (fade * 255.0F) << 24) | (COLOR & 0xFFFFFF);
        float x = (screen.width - font.getStringWidth(message)) / 2.0F;
        font.drawStringWithShadow(message, x, screen.height - ABOVE_PROMPTS - 9.0F, color);
    }
}
