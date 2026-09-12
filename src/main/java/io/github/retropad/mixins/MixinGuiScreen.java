package io.github.retropad.mixins;

import io.github.retropad.RetroPad;
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

/** Draws the pad button hints on top of ordinary screens. */
@Mixin(GuiScreen.class)
public class MixinGuiScreen {

    @Inject(method = "drawScreen", at = @At("HEAD"))
    public void retropad$holdPadClick(float mouseX, float mouseY, float partialTicks, CallbackInfo ci) {
        PadScreenInput input = RetroPad.screenInput();
        if (RetroPad.isPadActive() && input != null) {
            input.reassertButtons();
        }
    }

    @Inject(method = "drawScreen", at = @At("TAIL"))
    public void retropad$drawPadHints(float mouseX, float mouseY, float partialTicks, CallbackInfo ci) {
        GuiScreen self = (GuiScreen) (Object) this;
        // Container screens call super.drawScreen() before painting themselves, so drawing
        // here would put the cursor underneath them; MixinGuiContainer handles those.
        if (self instanceof GuiContainer) {
            return;
        }
        PadScreenInput input = RetroPad.screenInput();
        if (RetroPad.isPadActive() && input != null && input.isDriving()
                && PadInputMode.padDriving()) {
            PadRenderState.begin();
            try {
                input.drawHoverOutline();
                PadKeyboard.render(self);
                PadHintBar.render(self);
                PadDebugOverlay.render();
                if (PadCursor.wanted(self)) {
                    PadCursor.draw(input.guiX(Minecraft.getInstance(), self),
                            input.guiY(Minecraft.getInstance(), self));
                }
            } finally {
                PadRenderState.end(false);
            }
        }
    }
}
