package io.github.retropad.gui;

import com.indigo3d.util.RenderSystem;
import io.github.retropad.RetroPad;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButtonWeb;
import net.minecraft.common.util.math.MathHelper;

/**
 * The community link on the title screen, built as one of the game's own web buttons.
 *
 * <p>It extends {@link GuiButtonWeb} so the title screen treats it as one: the menu opens the
 * link for anything of that type, which is also why the address lives in a field it can read.
 * What has to be repeated here is only the drawing, because the original binds ReIndev's sheet
 * and has no row to spare — this one binds its own, laid out identically and built from the same
 * plate, so the button is the game's button with a different symbol on it.
 */
public final class GuiButtonCommunity extends GuiButtonWeb {

    public static final int ID = 611;
    private static final String LABEL = "Back to Retro";
    private static final String TEXTURE = "/textures/gui/retropad/web_buttons.png";

    /** The sheet holds one row, so the icons are at the top of it. */
    private static final int ROW_V = 0;
    private static final int SIZE = 20;
    /** Where the right-hand cap of the growing plate sits on the sheet. */
    private static final int CAP_U = 236;
    private static final int LABEL_COLOR = 16777120;
    /** How fast the plate grows out from under the icon, in pixels per tick. */
    private static final int GROW_PER_TICK = 16;

    private final int labelWidth;
    private int tick;
    private int previousTick;
    private boolean hovering;

    public GuiButtonCommunity(int x, int y) {
        super(ID, x, y, LABEL, RetroPad.CONFIG.communityUrl, 0);
        this.labelWidth = Minecraft.getInstance().fontRenderer.getStringWidth(LABEL);
    }

    /** True when there is somewhere to go; without a link the button is decoration. */
    public static boolean hasLink() {
        String url = RetroPad.CONFIG.communityUrl;
        return url != null && !url.trim().isEmpty();
    }

    /**
     * Swallows the press when no address is set, so the menu never tries to open an empty one,
     * and says where to put it instead.
     */
    @Override
    public boolean mousePressed(Minecraft mc, float x, float y) {
        if (!super.mousePressed(mc, x, y)) {
            return false;
        }
        if (hasLink()) {
            return true;
        }
        PadToast.show("Set the community link in the mod settings");
        return false;
    }

    @Override
    public void updateScreen() {
        int target = this.labelWidth + 8;
        this.previousTick = this.tick;
        if (this.hovering) {
            this.tick = Math.min(target, this.tick + GROW_PER_TICK);
        } else {
            this.tick = Math.max(0, this.tick - GROW_PER_TICK);
        }
    }

    @Override
    public void drawElement(Minecraft mc, float x, float y, float deltaTicks) {
        if (!this.visible) {
            return;
        }
        this.hovering = x >= this.xPosition && y >= this.yPosition
                && x < this.xPosition + this.width && y < this.yPosition + this.height;
        this.mouseDragged(mc, x, y);

        RenderSystem.color(1.0F, 1.0F, 1.0F, 1.0F);
        mc.renderEngine.bindTexture(mc.renderEngine.getTexture(TEXTURE));

        float grown = MathHelper.lerp_f(deltaTicks, this.previousTick, this.tick);
        if (this.hovering || this.tick > 0) {
            // The plate is one stretched slice with its own cap on the end, which is what makes
            // it unroll rather than slide.
            this.drawTexturedModalRect(this.xPosition + SIZE, this.yPosition,
                    0, ROW_V + SIZE, (int) (grown - SIZE), SIZE);
            this.drawTexturedModalRect((int) (this.xPosition + grown), this.yPosition,
                    CAP_U, ROW_V + SIZE, SIZE, SIZE);
        }
        this.drawTexturedModalRect(this.xPosition, this.yPosition,
                this.hovering ? SIZE : 0, ROW_V, SIZE, SIZE);

        if (this.hovering && this.tick > this.labelWidth) {
            mc.fontRenderer.drawStringWithShadow(this.displayString,
                    this.xPosition + 23, this.yPosition + 6, LABEL_COLOR);
        }
    }
}
