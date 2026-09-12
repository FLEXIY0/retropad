package io.github.retropad.mixins;

import io.github.retropad.RetroPad;
import io.github.retropad.game.PadGameplayInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.PlayerController;
import net.minecraft.common.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Writes the pad's actions into the key bindings at the head of the tick that reads them.
 *
 * <p>Doing this from the render hook instead looked equivalent and was not: the tick both
 * re-syncs key state from real hardware and drains the press counter, so a hold asserted a
 * frame earlier could already be gone, and a tap could be consumed before it was ever counted.
 */
@Mixin(Minecraft.class)
public class MixinMinecraft {

    @Inject(method = "runTick", at = @At("HEAD"))
    public void retropad$applyPadKeys(CallbackInfo ci) {
        retropad$assertPadKeys();
    }

    /**
     * The same write again, immediately before the tick asks whether the player is still using
     * an item.
     *
     * <p>That branch stops an item's use the moment {@code keyBindUseItem.pressed} reads false,
     * and everything between the head of the tick and this point — the mouse and keyboard event
     * loops above all — can clear that flag for a key no hand is physically holding. Asserting
     * it here is what lets a held trigger finish eating instead of restarting every tick.
     */
    @Inject(method = "runTick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/EntityPlayerSP;isUsingItem()Z"))
    public void retropad$holdPadKeysForItemUse(CallbackInfo ci) {
        PadGameplayInput pad = retropad$activePad();
        if (pad != null) {
            // Only the held flags: the tap countdown belongs to the head of the tick, and
            // running it twice would burn a tap before the tick ever saw it.
            pad.assertHeldBindings();
        }
    }

    /**
     * Keeps an item in use while the pad holds the use button.
     *
     * <p>The tick stops an item's use the instant it reads {@code keyBindUseItem.pressed} as
     * false, and that flag is shared state the game re-syncs from real hardware. Rather than
     * keep betting that no code path clears it between the write and the read — the bet that
     * has already been lost twice — this intercepts the call that does the stopping and simply
     * does not make it while the pad is holding the button. Release the trigger and the hold
     * ends, because the binding is no longer held and the call goes through as usual.
     */
    @Redirect(method = "runTick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/player/PlayerController;"
                            + "onStoppedUsingItem(Lnet/minecraft/common/entity/player/EntityPlayer;)V"))
    public void retropad$keepUsingItem(PlayerController controller, EntityPlayer player) {
        Minecraft mc = (Minecraft) (Object) this;
        PadGameplayInput pad = retropad$activePad();
        if (pad != null && mc.gameSettings != null && pad.isHolding(mc.gameSettings.keyBindUseItem)) {
            return;
        }
        controller.onStoppedUsingItem(player);
    }

    private static void retropad$assertPadKeys() {
        PadGameplayInput pad = retropad$activePad();
        if (pad != null) {
            pad.applyForTick();
        }
    }

    private static PadGameplayInput retropad$activePad() {
        if (!RetroPad.isPadActive()) {
            return null;
        }
        PadGameplayInput pad = RetroPad.gameplayInput();
        return pad != null && pad.isActive() ? pad : null;
    }
}
