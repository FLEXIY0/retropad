package io.github.retropad.mixins;

import io.github.retropad.input.PadRumble;
import net.minecraft.client.player.PlayerController;
import net.minecraft.common.entity.Entity;
import net.minecraft.common.entity.player.EntityPlayer;
import net.minecraft.common.item.ItemStack;
import net.minecraft.common.util.math.Vec3D;
import net.minecraft.common.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The two moments in the world worth feeling: a block giving way, and a hit landing.
 *
 * <p>Both are taken from the controller rather than from the blocks and mobs themselves, so
 * every block and every creature is covered by one hook each instead of a list that would go
 * stale the moment ReIndev adds something.
 */
@Mixin(PlayerController.class)
public class MixinPlayerController {

    @Inject(method = "sendBlockRemoved", at = @At("RETURN"))
    public void retropad$feelBlockBreak(int x, int y, int z, int facing,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            PadRumble.play(PadRumble.Effect.BREAK);
        }
    }

    @Inject(method = "attackEntity", at = @At("TAIL"))
    public void retropad$feelHit(EntityPlayer player, Entity entity, CallbackInfo ci) {
        PadRumble.play(PadRumble.Effect.HIT);
    }

    /**
     * Called every tick the button is held against a block, so the tool grinding away is felt
     * as a steady tick rather than one continuous buzz — hence a pulse rather than an effect.
     */
    @Inject(method = "sendBlockRemoving", at = @At("TAIL"))
    public void retropad$feelMining(int x, int y, int z, int facing, CallbackInfo ci) {
        PadRumble.pulse(PadRumble.Effect.MINE, 90L);
    }

    @Inject(method = "sendPlaceBlock", at = @At("RETURN"))
    public void retropad$feelPlacing(EntityPlayer player, World world, ItemStack itemStack,
                                       int x, int y, int z, int facing, Vec3D hit,
                                       CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            PadRumble.play(PadRumble.Effect.PLACE);
        }
    }
}
