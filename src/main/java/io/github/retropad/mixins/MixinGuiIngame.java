package io.github.retropad.mixins;

import io.github.retropad.RetroPad;
import io.github.retropad.gui.PadDebugOverlay;
import io.github.retropad.gui.PadHintBar;
import net.minecraft.client.gui.GuiIngame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes room under the hotbar for the button prompts, and puts them there.
 *
 * <p>The HUD is drawn from a bottom edge handed to {@code renderHUD}, so moving the whole lot up
 * is a matter of handing it a higher one — the hotbar, the bars and the held item's name all
 * follow. The crosshair is the exception: it is placed at half that number, so it would drift up
 * by half the lift, and the redirect below puts it back in the centre of the screen where it
 * belongs.
 */
@Mixin(GuiIngame.class)
public class MixinGuiIngame {

    @ModifyArg(method = "renderGameOverlay",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiIngame;renderHUD(IIF)V"),
            index = 1)
    public int retropad$liftHud(int bottom) {
        return bottom - PadHintBar.hudLift();
    }

    @ModifyArg(method = "renderGameOverlay",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiIngame;drawItemTooltip"
                            + "(Lnet/minecraft/client/gui/FontRenderer;II)V"),
            index = 2)
    public int retropad$liftHeldItemName(int y) {
        return y - PadHintBar.hudLift();
    }

    /**
     * The two draws between the crosshair's blend mode and the hotbar's item lighting: the
     * crosshair itself and the eating cursor that replaces it. Both are placed from half the
     * HUD's bottom edge, so both need half the lift given back.
     */
    @Redirect(method = "renderHUD",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiIngame;drawTexturedModalRect(FFIIII)V"),
            slice = @Slice(
                    from = @At(value = "INVOKE",
                            target = "Lcom/indigo3d/util/RenderSystem;blendFunc(II)V", ordinal = 1),
                    to = @At(value = "INVOKE",
                            target = "Lcom/indigo3d/util/RenderSystem;enableRescaleNormal()V")))
    public void retropad$keepCrosshairCentred(GuiIngame gui, float x, float y,
                                                int minU, int minV, int maxU, int maxV) {
        gui.drawTexturedModalRect(x, y + PadHintBar.hudLift() / 2.0F, minU, minV, maxU, maxV);
    }

    @Inject(method = "renderGameOverlay", at = @At("TAIL"))
    public void retropad$drawOverlays(float partialTicks, CallbackInfo ci) {
        PadHintBar.renderInGame();
        if (RetroPad.CONFIG.debugOverlay) {
            PadDebugOverlay.render();
        }
    }
}
