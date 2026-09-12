package io.github.retropad.gui;

/**
 * A fade applied to the next item drawn, used to mark what cannot be crafted.
 *
 * <p>Dimming has to happen on the item itself, not as a square over its cell: a rectangle covers
 * the tile and looks like a sticker, while fading follows the item's own shape and leaves the
 * cell alone. The catch is that the item renderer sets the draw colour itself — for dyed leather
 * and coloured blocks — and resets it to white when it is done, so simply setting a colour
 * beforehand achieves nothing. {@code MixinRenderItem} and {@code MixinRenderBlocks} fold this
 * into every colour those renderers set, which is why the value lives here rather than in a
 * local.
 */
public final class ItemTint {

    private static float alpha = 1.0F;
    private static boolean active;

    private ItemTint() {
        throw new AssertionError();
    }

    /** Draws the next item at this opacity. Colours are left alone. */
    public static void fade(float opacity) {
        alpha = opacity;
        active = true;
    }

    public static void clear() {
        alpha = 1.0F;
        active = false;
    }

    public static boolean isActive() {
        return active;
    }

    public static float alpha(float value) {
        return value * alpha;
    }
}
