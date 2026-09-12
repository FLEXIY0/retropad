package io.github.retropad.mixins;

import io.github.retropad.RetroPad;
import io.github.retropad.craft.PadCraftingOverlay;
import io.github.retropad.game.PadScreenInput;
import io.github.retropad.gui.PadCursor;
import io.github.retropad.gui.PadDebugOverlay;
import io.github.retropad.gui.PadHintBar;
import io.github.retropad.input.PadInputMode;
import io.github.retropad.gui.PadKeyboard;
import io.github.retropad.gui.PadRenderState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiContainer;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Inventory-style screens: the crafting menu is drawn over them, and the pad's hints and
 * pointer go on top of that.
 */
@Mixin(GuiContainer.class)
public class MixinGuiContainer {

    @Inject(method = "drawScreen", at = @At("TAIL"))
    public void retropad$drawPadHints(float mouseX, float mouseY, float partialTicks, CallbackInfo ci) {
        GuiContainer self = (GuiContainer) (Object) this;
        // The screen's last two statements switch lighting and depth testing back on, so every
        // overlay below has to undo that or it draws lit, flat and mostly invisible.
        PadRenderState.begin();
        try {
            PadCraftingOverlay.get().render(self, partialTicks);

            PadScreenInput input = RetroPad.screenInput();
            if (RetroPad.isPadActive() && input != null && input.isDriving()
                    && PadInputMode.padDriving()) {
                input.drawHoverOutline();
                PadKeyboard.render(self);
                PadHintBar.render(self);
                PadDebugOverlay.render();
                if (PadCursor.wanted(self)) {
                    PadCursor.draw(input.guiX(Minecraft.getInstance(), self),
                            input.guiY(Minecraft.getInstance(), self));
                }
            }
        } finally {
            PadRenderState.end(true);
        }
    }

    /**
     * Swallows clicks that land on the crafting menu.
     *
     * <p>Without this the click would also reach the inventory underneath — picking up or
     * scattering a stack behind the panel the player is actually looking at.
     */
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    protected void retropad$clickCraftingMenu(float mouseX, float mouseY, int button, CallbackInfo ci) {
        PadCraftingOverlay overlay = PadCraftingOverlay.get();
        if (overlay.isOpenOn((GuiContainer) (Object) this) && overlay.click(mouseX, mouseY)) {
            ci.cancel();
        }
    }

    /** Escape closes the crafting menu first, and only then the screen. */
    @Inject(method = "keyTyped", at = @At("HEAD"), cancellable = true)
    protected void retropad$closeCraftingMenu(char character, int keyCode, CallbackInfo ci) {
        PadCraftingOverlay overlay = PadCraftingOverlay.get();
        if (keyCode == 1 && overlay.isOpenOn((GuiContainer) (Object) this)) {
            overlay.close();
            ci.cancel();
        }
    }

    /** The container is gone once the screen closes, so the menu must not outlive it. */
    @Inject(method = "onGuiClosed", at = @At("HEAD"))
    public void retropad$closeOverlayWithScreen(CallbackInfo ci) {
        PadCraftingOverlay overlay = PadCraftingOverlay.get();
        if (overlay.isOpenOn((GuiContainer) (Object) this)) {
            overlay.close();
        }
    }
}
