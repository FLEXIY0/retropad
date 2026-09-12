package io.github.retropad.mixins;

import com.indigo3d.util.RenderSystem;
import io.github.retropad.gui.ItemTint;
import net.minecraft.client.renderer.world.RenderBlocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The other half of fading an item out.
 *
 * <p>An item that is a block is not drawn from an icon but built as a small 3D cube, and the
 * colour the item renderer set is overwritten the moment that starts — so without this, planks
 * and stone would stay solid while everything else faded. Only the inventory rendering path is
 * touched; the world is drawn by other methods entirely.
 */
@Mixin(RenderBlocks.class)
public class MixinRenderBlocks {

    @Redirect(method = "renderBlockOnInventory",
            at = @At(value = "INVOKE", target = "Lcom/indigo3d/util/RenderSystem;color(FFFF)V"))
    public void retropad$tintBlock(float red, float green, float blue, float alpha) {
        RenderSystem.color(red, green, blue, ItemTint.alpha(alpha));
    }
}
