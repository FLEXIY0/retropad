package io.github.retropad.mixins;

import net.minecraft.client.gui.GuiSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The geometry of a list widget, so the pad can put the cursor on a row.
 *
 * <p>{@code selectNextElementUp} and {@code selectNextElementDown} exist on {@link GuiSlot} but
 * are empty methods in ReIndev — calling them moves nothing. A row is selected only by the
 * widget's own click handling, which reads the mouse position it is drawn with, so the way to
 * choose a world from a pad is to move the pointer onto the row and click it.
 */
@Mixin(GuiSlot.class)
public interface GuiSlotAccessor {

    @Accessor("top")
    int retropad$getTop();

    @Accessor("bottom")
    int retropad$getBottom();

    @Accessor("left")
    int retropad$getLeft();

    @Accessor("right")
    int retropad$getRight();

    @Accessor("slotHeight")
    int retropad$getSlotHeight();

    /** How wide a row is drawn, which is narrower than the widget it sits in. */
    @Accessor("slotWidth")
    int retropad$getSlotWidth();

    @Accessor("slotOffset")
    int retropad$getSlotOffset();

    @Accessor("headerPadding")
    int retropad$getHeaderPadding();

    @Accessor("amountScrolled")
    float retropad$getAmountScrolled();

    /**
     * Total height of the list's contents. Used instead of {@code getSize()}, which is abstract
     * on this class — an invoker for an abstract method is rejected when the mixin is applied,
     * and the failure would only surface on the screen that first loads a list.
     */
    @Invoker("getContentHeight")
    int retropad$getContentHeight();
}
