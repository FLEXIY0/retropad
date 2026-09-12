package io.github.retropad.game;

import io.github.retropad.RetroPad;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiContainer;
import net.minecraft.common.block.container.Container;
import net.minecraft.common.block.container.Slot;
import net.minecraft.common.block.container.SlotArmor;
import net.minecraft.common.block.container.SlotCrafting;
import net.minecraft.common.entity.player.InventoryPlayer;
import net.minecraft.common.entity.inventory.InventoryCrafting;
import net.minecraft.common.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Tidies an inventory from a single button press.
 *
 * <p>Everything happens through the same slot clicks a player would make, so the server sees an
 * ordinary sequence of picking things up and putting them down and nothing has to be trusted to
 * the client. Two passes: first part-full stacks of the same thing are poured into each other,
 * then the survivors are walked into order by item, so a chest ends up with all the stone
 * together and all the planks together rather than scattered.
 *
 * <p>Armour slots, crafting grids and results are never touched, and neither are the small
 * machines — a furnace's three slots mean something individually, and shuffling fuel into the
 * output would be worse than leaving it alone.
 */
public final class PadSorter {

    private static final int LEFT = 0;
    private static final int CLICK = 0;

    /** Below this, an inventory is a machine with meaningful slots rather than storage. */
    private static final int SMALLEST_STORAGE = 9;
    /** A ceiling on the packets one press can send, in case a region is pathological. */
    private static final int MAX_CLICKS = 600;

    private PadSorter() {
        throw new AssertionError();
    }

    /**
     * Sorts the chest (or other storage) on screen, falling back to the player's own items when
     * the screen has none. Returns a short line about what happened, for the prompt bar.
     */
    public static String sortStorage(GuiContainer gui) {
        List<Slot> storage = storageSlots(gui.inventorySlots);
        if (storage.size() >= SMALLEST_STORAGE) {
            return sort(gui, storage, "Sorted the chest");
        }
        return sortBackpack(gui);
    }

    /** Sorts the player's own 36 slots, wherever they are shown. */
    public static String sortBackpack(GuiContainer gui) {
        return sort(gui, backpackSlots(gui.inventorySlots), "Sorted your items");
    }

    /** True when the screen has storage of its own, so the two sort buttons differ. */
    public static boolean hasStorage(GuiContainer gui) {
        return gui != null && storageSlots(gui.inventorySlots).size() >= SMALLEST_STORAGE;
    }

    // ------------------------------------------------------------------ slots

    /** The player's own items: the backpack and the hotbar, never the armour. */
    private static List<Slot> backpackSlots(Container container) {
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : container.slots) {
            if (sortable(slot) && slot.inventory instanceof InventoryPlayer) {
                slots.add(slot);
            }
        }
        return slots;
    }

    /** Whatever the screen is showing that is not the player's: a chest, a crate, a dispenser. */
    private static List<Slot> storageSlots(Container container) {
        List<Slot> slots = new ArrayList<>();
        for (Slot slot : container.slots) {
            if (sortable(slot) && !(slot.inventory instanceof InventoryPlayer)) {
                slots.add(slot);
            }
        }
        return slots;
    }

    private static boolean sortable(Slot slot) {
        return slot != null
                && !(slot instanceof SlotArmor)
                && !(slot instanceof SlotCrafting)
                && !(slot.inventory instanceof InventoryCrafting);
    }

    // ----------------------------------------------------------------- sorting

    private static String sort(GuiContainer gui, List<Slot> region, String done) {
        if (region.size() < 2) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.thePlayer == null) {
            return null;
        }
        // Sorting with something on the cursor would drop it into the first slot it visits.
        if (mc.thePlayer.inventory.getCursorStack() != null) {
            return "Put down what you are holding first";
        }
        try {
            int clicks = merge(gui, mc, region, 0);
            clicks = order(gui, mc, region, clicks);
            return clicks == 0 ? "Already tidy" : done;
        } catch (Throwable throwable) {
            RetroPad.LOGGER.warn("Sorting failed part way through", throwable);
            return "Could not finish sorting";
        }
    }

    /** Pours part-full stacks of the same item into the earliest one that has room. */
    private static int merge(GuiContainer gui, Minecraft mc, List<Slot> region, int clicks) {
        for (int i = 0; i < region.size() && clicks < MAX_CLICKS; i++) {
            Slot into = region.get(i);
            if (!hasRoom(into)) {
                continue;
            }
            for (int j = i + 1; j < region.size() && clicks < MAX_CLICKS; j++) {
                Slot from = region.get(j);
                if (!from.getHasStack() || !same(into.getStack(), from.getStack())) {
                    continue;
                }
                clicks += take(gui, from);
                clicks += put(gui, into);
                clicks += putBack(gui, mc, from);
                if (!hasRoom(into)) {
                    break;
                }
            }
        }
        return clicks;
    }

    /**
     * Walks the region into order, one slot at a time: find what belongs here, and swap it in.
     * A selection sort rather than anything cleverer because every move costs a packet, and this
     * makes at most one swap per slot.
     */
    private static int order(GuiContainer gui, Minecraft mc, List<Slot> region, int clicks) {
        for (int i = 0; i < region.size() && clicks < MAX_CLICKS; i++) {
            int best = i;
            for (int j = i + 1; j < region.size(); j++) {
                if (compare(region.get(j).getStack(), region.get(best).getStack()) < 0) {
                    best = j;
                }
            }
            if (best == i) {
                continue;
            }
            Slot from = region.get(best);
            Slot to = region.get(i);
            clicks += take(gui, from);
            clicks += put(gui, to);
            // Whatever was in the target slot is now on the cursor; it goes where the other
            // stack came from, which is empty and therefore always accepts it.
            clicks += putBack(gui, mc, from);
        }
        return clicks;
    }

    private static int take(GuiContainer gui, Slot slot) {
        gui.sendClickSlot(slot, slot.slotNumber, LEFT, CLICK);
        return 1;
    }

    private static int put(GuiContainer gui, Slot slot) {
        gui.sendClickSlot(slot, slot.slotNumber, LEFT, CLICK);
        return 1;
    }

    /** Puts down whatever is still on the cursor, if anything is. */
    private static int putBack(GuiContainer gui, Minecraft mc, Slot slot) {
        if (mc.thePlayer.inventory.getCursorStack() == null) {
            return 0;
        }
        gui.sendClickSlot(slot, slot.slotNumber, LEFT, CLICK);
        return 1;
    }

    private static boolean hasRoom(Slot slot) {
        if (!slot.getHasStack()) {
            return false;
        }
        ItemStack stack = slot.getStack();
        return stack.stackSize < Math.min(stack.getMaxStackSize(), slot.getSlotStackLimit());
    }

    private static boolean same(ItemStack a, ItemStack b) {
        if (a == null || b == null) {
            return false;
        }
        return a.getItemID() == b.getItemID() && a.getItemDamage() == b.getItemDamage();
    }

    /**
     * The order things end up in: everything of one kind together, the fullest stack first, and
     * the empty slots at the end where they are useful.
     */
    private static int compare(ItemStack a, ItemStack b) {
        if (a == null) {
            return b == null ? 0 : 1;
        }
        if (b == null) {
            return -1;
        }
        if (a.getItemID() != b.getItemID()) {
            return a.getItemID() < b.getItemID() ? -1 : 1;
        }
        if (a.getItemDamage() != b.getItemDamage()) {
            return a.getItemDamage() < b.getItemDamage() ? -1 : 1;
        }
        return Integer.compare(b.stackSize, a.stackSize);
    }
}
