package io.github.retropad.mixins;

import com.indigo3d.util.RenderSystem;
import io.github.retropad.gui.ItemTint;
import net.minecraft.client.renderer.entity.RenderItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lets the crafting menu draw an item faded out.
 *
 * <p>The renderer picks its own colours as it goes — a shade for dyed armour, white for
 * everything else — and ends by resetting to white, so an alpha set before the call is simply
 * overwritten. Redirecting those calls is the only place opacity can be applied to the item's
 * own pixels rather than to a square drawn over its cell. Blending is already on for the whole
 * of {@code drawItemIntoGui}, so the alpha lands as transparency.
 */
@Mixin(RenderItem.class)
public class MixinRenderItem {

    @Redirect(method = "drawItemIntoGui",
            at = @At(value = "INVOKE", target = "Lcom/indigo3d/util/RenderSystem;color(FFFF)V"))
    public void retropad$tintItem(float red, float green, float blue, float alpha) {
        RenderSystem.color(red, green, blue, ItemTint.alpha(alpha));
    }
}
