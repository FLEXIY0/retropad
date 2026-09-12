package io.github.retropad.mixins;

import net.minecraft.client.gui.GuiButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** {@link GuiButton} keeps its size protected; snapping needs it to find button centres. */
@Mixin(GuiButton.class)
public interface GuiButtonAccessor {

    @Accessor("width")
    int retropad$getWidth();

    @Accessor("height")
    int retropad$getHeight();
}
