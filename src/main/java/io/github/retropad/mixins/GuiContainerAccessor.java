package io.github.retropad.mixins;

import net.minecraft.client.gui.GuiContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Container screens centre themselves on the fly; snapping needs the same numbers. */
@Mixin(GuiContainer.class)
public interface GuiContainerAccessor {

    @Accessor("xSize")
    int retropad$getXSize();

    @Accessor("ySize")
    int retropad$getYSize();
}
