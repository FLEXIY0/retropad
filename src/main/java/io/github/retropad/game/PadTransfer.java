package io.github.retropad.game;

import io.github.retropad.RetroPad;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiContainer;
import net.minecraft.common.block.container.Container;
import net.minecraft.common.block.container.Slot;
import net.minecraft.common.block.container.SlotArmor;
import net.minecraft.common.block.container.SlotCrafting;
import net.minecraft.common.entity.inventory.InventoryCrafting;
import net.minecraft.common.entity.player.InventoryPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Emptying a chest, and filling one, without aiming at a single slot.
 *
 * <p>Both are the shift-click the game already has, repeated over a region — so a full chest
 * stops accepting items exactly as it would by hand, and nothing is destroyed when there is
 * nowhere for a stack to go.
 *
 * <p>Depositing leaves the hotbar alone. Sweeping your pickaxe and sword into a chest because
 * you wanted to drop off the cobble is the kind of favour nobody asks for twice.
 */
public final class PadTransfer {

    private static final int LEFT = 0;
    private static final int QUICK_MOVE = 1;

    private static final int HOTBAR_SLOTS = 9;
    private static final int BACKPACK_SLOTS = 36;
    private static final int SMALLEST_STORAGE = 9;

    private PadTransfer() {
        throw new AssertionError();
    }

    /** Sends the backpack into the open storage. Returns a line for the toast, or null. */
    public static String depositAll(GuiContainer gui) {
        if (!hasStorage(gui)) {
            return null;
        }
        List<Slot> source = backpack(gui.inventorySlots);
        // The hotbar is the last row of the player's slots in every screen that shows it.
        if (source.size() == BACKPACK_SLOTS) {
            source = source.subList(0, source.size() - HOTBAR_SLOTS);
        }
        int moved = sweep(gui, source);
        if (moved == 0) {
            return "Nothing to put away";
        }
        return "Put away " + moved + (moved == 1 ? " stack" : " stacks");
    }

    /** Pulls everything out of the open storage. */
    public static String takeAll(GuiContainer gui) {
        if (!hasStorage(gui)) {
            return null;
        }
        int moved = sweep(gui, storage(gui.inventorySlots));
        if (moved == 0) {
            return "Nothing to take";
        }
        return "Took " + moved + (moved == 1 ? " stack" : " stacks");
    }

    public static boolean hasStorage(GuiContainer gui) {
        return gui != null && storage(gui.inventorySlots).size() >= SMALLEST_STORAGE;
    }

    /**
     * Shift-clicks every occupied slot of a region, counting only the ones that actually left.
     * A slot that still has something in it is one the other side had no room for, which is a
     * full chest or a full bag rather than an error.
     */
    private static int sweep(GuiContainer gui, List<Slot> region) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.thePlayer == null || mc.thePlayer.inventory.getCursorStack() != null) {
            return 0;
        }
        int moved = 0;
        try {
            for (Slot slot : region) {
                if (!slot.getHasStack()) {
                    continue;
                }
                gui.sendClickSlot(slot, slot.slotNumber, LEFT, QUICK_MOVE);
                if (!slot.getHasStack()) {
                    moved++;
                }
            }
        } catch (Throwable throwable) {
            RetroPad.LOGGER.warn("Moving items failed part way through", throwable);
        }
        return moved;
    }

    private static List<Slot> backpack(Container container) {
        return region(container, true);
    }

    private static List<Slot> storage(Container container) {
        return region(container, false);
    }

    private static List<Slot> region(Container container, boolean playerSide) {
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : container.slots) {
            if (slot == null || slot instanceof SlotArmor || slot instanceof SlotCrafting
                    || slot.inventory instanceof InventoryCrafting) {
                continue;
            }
            if (slot.inventory instanceof InventoryPlayer == playerSide) {
                slots.add(slot);
            }
        }
        return slots;
    }
}
