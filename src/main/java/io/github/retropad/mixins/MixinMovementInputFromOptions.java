package io.github.retropad.mixins;

import io.github.retropad.RetroPad;
import io.github.retropad.game.PadGameplayInput;
import net.minecraft.client.player.MovementInput;
import net.minecraft.client.player.MovementInputFromOptions;
import net.minecraft.common.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds the left stick into the movement state.
 *
 * <p>This is the one thing key synthesis cannot do: the keyboard only ever produces -1, 0 or 1,
 * while a stick has to produce everything in between for walking speed to be analog. Keyboard
 * input is left alone whenever the stick is centred, so both can be used in the same session.
 */
@Mixin(MovementInputFromOptions.class)
public class MixinMovementInputFromOptions extends MovementInput {

    /** Vanilla's sneak multiplier, reapplied because this runs after it. */
    private static final float SNEAK_SCALE = 0.3F;

    @Inject(method = "updatePlayerMoveState", at = @At("TAIL"))
    public void retropad$applyGamepad(EntityPlayer player, CallbackInfo ci) {
        if (!RetroPad.isPadActive()) {
            return;
        }
        PadGameplayInput pad = RetroPad.gameplayInput();
        if (pad == null || !pad.isActive()) {
            return;
        }

        float forward = pad.getMoveForward();
        float strafe = pad.getMoveStrafe();
        boolean sneaking = pad.isSneaking() || this.sneak;

        if (forward != 0.0F || strafe != 0.0F) {
            this.moveForward = sneaking ? forward * SNEAK_SCALE : forward;
            this.moveStrafe = sneaking ? strafe * SNEAK_SCALE : strafe;
        }
        if (pad.isJumping()) {
            this.jump = true;
        }
        if (pad.isSneaking()) {
            this.sneak = true;
        }
    }
}
