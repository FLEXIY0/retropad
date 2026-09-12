package io.github.retropad.mixins;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/** Opens up the pieces of {@link GuiScreen} the virtual cursor needs to drive a screen. */
@Mixin(GuiScreen.class)
public interface GuiScreenAccessor {

    @Invoker("mouseClicked")
    void retropad$mouseClicked(float mouseX, float mouseY, int button);

    @Invoker("mouseMovedOrUp")
    void retropad$mouseMovedOrUp(float mouseX, float mouseY, int button);

    @Accessor("controlList")
    List<?> retropad$getControlList();

    /**
     * The list widget a screen is currently showing, if any. List widgets run their own
     * selection and scrolling, so the d-pad drives them through that rather than by moving
     * the cursor across rows.
     */
    @Accessor("activeGuiSlot")
    GuiSlot retropad$getActiveGuiSlot();
}
