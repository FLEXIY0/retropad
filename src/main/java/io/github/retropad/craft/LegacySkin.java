package io.github.retropad.craft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.world.Tessellator;

import com.indigo3d.util.RenderSystem;

/**
 * The Better Than Legacy look: its texture atlas, its geometry, its sprite coordinates.
 *
 * <p>The artwork and the numbers here come from that mod (CC0-1.0, licence text kept beside the
 * textures), so the crafting menu is the console UI people already know rather than something
 * drawn from scratch. The window is 273 by 175, the atlas is 512 square, and every sprite below
 * is quoted from the layout the original draws with.
 */
public final class LegacySkin {

    /** The atlas is 512 wide, while the game's own helper assumes 256 — hence a scale factor. */
    public static final float ATLAS = 512.0F;
    public static final String ATLAS_PATH = "/textures/gui/retropad/legacycrafting.png";
    private static final String ICON_PATH = "/textures/gui/retropad/icon/";

    /** Window size, taken from the original screen. */
    public static final int WINDOW_WIDTH = 273;
    public static final int WINDOW_HEIGHT = 175;

    /** Category tabs across the top. */
    public static final int TAB_WIDTH = 35;
    public static final int TAB_HEIGHT = 30;
    /** Tabs overlap by a pixel, which is what makes the row read as one strip. */
    public static final int TAB_STEP = TAB_WIDTH - 1;
    public static final int TAB_SELECTED_V = 229;

    /** The strip of recipe cells inside the window. */
    public static final int STRIP_X = 12;
    public static final int STRIP_Y = 51;
    public static final int STRIP_STEP = 18;
    public static final int STRIP_CELLS = 12;

    /** Sprites at the bottom of the atlas, below the window graphic. */
    public static final int CELL_PLAIN_U = 36;
    public static final int CELL_PLAIN_V = 175;
    public static final int CELL_PLAIN_SIZE = 24;
    public static final int CELL_TALL_U = 35;
    public static final int CELL_TALL_V = 175;
    public static final int CELL_TALL_W = 26;
    public static final int CELL_TALL_H = 24;
    public static final int ARROW_UP_U = 115;
    public static final int ARROW_UP_V = 175;
    public static final int ARROW_DOWN_U = 141;
    public static final int ARROW_DOWN_V = 175;
    public static final int ARROW_W = 26;
    public static final int ARROW_H = 31;
    /** The 3x3 ingredient panel shown on the left. */
    public static final int GRID_U = 61;
    public static final int GRID_V = 175;
    public static final int GRID_SIZE = 54;
    public static final int GRID_X = 19;
    public static final int GRID_Y = 108;

    /** Where the original puts its two headings. */
    public static final int LABEL_Y = 97;
    public static final int LABEL_CRAFTING_X = 73;
    public static final int LABEL_RIGHT_X = 204;
    public static final int CATEGORY_LABEL_Y = 36;
    /** Dark grey, drawn without a shadow — the console UI's text colour. */
    public static final int LABEL_COLOR = 0x404040;

    private LegacySkin() {
        throw new AssertionError();
    }

    public static void bindAtlas(Minecraft mc) {
        bind(mc, ATLAS_PATH);
    }

    public static void bindIcon(Minecraft mc, String name) {
        bind(mc, ICON_PATH + name + ".png");
    }

    private static void bind(Minecraft mc, String path) {
        RenderSystem.color(1.0F, 1.0F, 1.0F, 1.0F);
        mc.renderEngine.bindTexture(mc.renderEngine.getTexture(path));
    }

    /**
     * Draws a piece of a texture, with the drawn size and the texture size given separately.
     *
     * <p>The game's own {@code drawTexturedModalRect} divides by a fixed 256 and cannot scale,
     * so it can address neither a 512-wide atlas nor an icon drawn at 90% of its size.
     */
    public static void blitScaled(float x, float y, float drawWidth, float drawHeight,
                                  float u, float v, float texWidth, float texHeight, float atlasSize) {
        float scale = 1.0F / atlasSize;
        float u0 = u * scale;
        float u1 = (u + texWidth) * scale;
        float v0 = v * scale;
        float v1 = (v + texHeight) * scale;
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.addVertexWithUV(x, y + drawHeight, 0.0D, u0, v1);
        tessellator.addVertexWithUV(x + drawWidth, y + drawHeight, 0.0D, u1, v1);
        tessellator.addVertexWithUV(x + drawWidth, y, 0.0D, u1, v0);
        tessellator.addVertexWithUV(x, y, 0.0D, u0, v0);
        tessellator.draw();
    }

    /** Draws a piece of the crafting atlas at its natural size. */
    public static void blit(float x, float y, float u, float v, float width, float height) {
        blitScaled(x, y, width, height, u, v, width, height, ATLAS);
    }

    /**
     * Draws a whole 32-pixel icon scaled into a box. The tabs show them at 90% when selected
     * and 75% otherwise, which is what lifts the active tab out of the row.
     */
    public static void icon(Minecraft mc, String name, float x, float y, float size) {
        bindIcon(mc, name);
        blitScaled(x, y, size, size, 0.0F, 0.0F, 32.0F, 32.0F, 32.0F);
    }
}
