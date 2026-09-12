package io.github.retropad.gui;

import com.indigo3d.util.RenderSystem;

/**
 * Puts the renderer into a state where flat panels and text actually show up.
 *
 * <p>Inventory screens end their draw with {@code enableLighting()} and {@code enableDepthTest()}
 * still set — the last two lines of {@code GuiContainer.drawScreen}. Anything drawn after that,
 * which is exactly where this mod's overlays go, is lit by a scene with no lights: text vanishes
 * and panels come out flat grey. Ordinary screens leave lighting off, which is why the same
 * overlay looked right in a menu and wrong in the inventory.
 */
public final class PadRenderState {

    private PadRenderState() {
        throw new AssertionError();
    }

    /** Call before drawing any overlay. */
    public static void begin() {
        RenderSystem.disableLighting();
        RenderSystem.disableDepthTest();
        RenderSystem.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Hands the renderer back the way the screen left it.
     *
     * @param wasLit true after an inventory screen, which ends its draw with lighting and depth
     *     testing on; false after an ordinary screen, which leaves both off.
     */
    public static void end(boolean wasLit) {
        if (wasLit) {
            RenderSystem.enableLighting();
            RenderSystem.enableDepthTest();
        }
    }
}
