package io.github.retropad.craft;

import io.github.retropad.RetroPad;
import net.minecraft.client.gui.GuiContainer;
import net.minecraft.common.block.container.Container;
import net.minecraft.common.block.container.Slot;
import net.minecraft.common.block.container.SlotCrafting;
import net.minecraft.common.entity.inventory.InventoryCrafting;
import net.minecraft.common.item.ItemStack;
import net.minecraft.common.recipe.Ingredient;

import java.util.ArrayList;
import java.util.List;

/**
 * Crafts a recipe by driving the crafting screen that is already open.
 *
 * <p>Nothing here touches inventory contents directly. Every step is an ordinary slot click of
 * the kind a player makes with a mouse, sent through the container the server already knows
 * about, so a server validates each one exactly as it would any other click. That rules out the
 * whole class of desync bugs a client-side shortcut would invite, at the cost of a short burst
 * of clicks per craft.
 *
 * <p>The sequence per source stack is pick it up, drop one item into each cell that needs it,
 * put the remainder back. It is written to be safe when interrupted: whatever is left in hand
 * always goes back to the slot it came from before the next stack is picked up.
 */
public final class CraftExecutor {

    /** Left mouse button, as slot clicks number them. */
    private static final int LEFT = 0;
    private static final int RIGHT = 1;
    /** Transfer types: a plain click, and the shift-click that moves a whole stack. */
    private static final int CLICK = 0;
    private static final int QUICK_MOVE = 1;

    private CraftExecutor() {
        throw new AssertionError();
    }

    /** Why a craft could not run, or null when it can. */
    public static String reasonCannotCraft(GuiContainer gui, PadRecipe recipe) {
        if (gui == null || recipe == null) {
            return "No crafting screen open";
        }
        List<Slot> matrix = matrixSlots(gui.inventorySlots);
        if (matrix.isEmpty() || resultSlot(gui.inventorySlots) == null) {
            return "This screen has no crafting grid";
        }
        int side = matrix.size() >= 9 ? 3 : 2;
        if (recipe.getWidth() > side || recipe.getHeight() > side) {
            return "Needs a crafting table";
        }
        if (plan(gui.inventorySlots, recipe, side) == null) {
            return "Missing ingredients";
        }
        return null;
    }

    /**
     * Fills the grid and takes the result. Returns true if the craft was carried out; a false
     * return means nothing was moved, or that everything moved was put back.
     */
    public static boolean craft(GuiContainer gui, PadRecipe recipe) {
        if (reasonCannotCraft(gui, recipe) != null) {
            return false;
        }
        Container container = gui.inventorySlots;
        List<Slot> matrix = matrixSlots(container);
        Slot result = resultSlot(container);
        int side = matrix.size() >= 9 ? 3 : 2;

        if (!clearGrid(gui, matrix)) {
            return false;
        }

        List<Placement> placements = plan(container, recipe, side);
        if (placements == null) {
            return false;
        }

        try {
            for (Placement placement : placements) {
                gui.sendClickSlot(placement.source, placement.source.slotNumber, LEFT, CLICK);
                for (Slot target : placement.targets) {
                    gui.sendClickSlot(target, target.slotNumber, RIGHT, CLICK);
                }
                // Put back whatever is still in hand, always, before touching another stack.
                gui.sendClickSlot(placement.source, placement.source.slotNumber, LEFT, CLICK);
            }

            if (result.getHasStack()) {
                gui.sendClickSlot(result, result.slotNumber, LEFT, QUICK_MOVE);
                return true;
            }
            // The grid did not produce anything — the recipe did not match after all. Undo.
            RetroPad.LOGGER.warn("Crafting produced nothing; returning the ingredients");
            clearGrid(gui, matrix);
            return false;
        } catch (Throwable throwable) {
            RetroPad.LOGGER.warn("Crafting failed, returning the ingredients", throwable);
            clearGrid(gui, matrix);
            return false;
        }
    }

    /** Moves anything already sitting in the grid back to the inventory. */
    private static boolean clearGrid(GuiContainer gui, List<Slot> matrix) {
        for (Slot slot : matrix) {
            if (slot.getHasStack()) {
                gui.sendClickSlot(slot, slot.slotNumber, LEFT, QUICK_MOVE);
            }
        }
        for (Slot slot : matrix) {
            if (slot.getHasStack()) {
                // The inventory is full, so there is nowhere to put the grid's contents and
                // no safe way to continue.
                return false;
            }
        }
        return true;
    }

    /**
     * Works out which inventory stack feeds which grid cell, without moving anything.
     * Returns null when the ingredients are not all there.
     */
    private static List<Placement> plan(Container container, PadRecipe recipe, int side) {
        List<Slot> matrix = matrixSlots(container);
        List<Slot> sources = inventorySlots(container);
        // How many items each source slot has already been promised to this plan.
        int[] reserved = new int[sources.size()];
        List<Placement> placements = new ArrayList<>();

        for (int row = 0; row < recipe.getHeight(); row++) {
            for (int column = 0; column < recipe.getWidth(); column++) {
                Ingredient ingredient = recipe.cellAt(column, row);
                if (ingredient == null) {
                    continue;
                }
                int cellIndex = row * side + column;
                if (cellIndex >= matrix.size()) {
                    return null;
                }
                Slot target = matrix.get(cellIndex);

                int sourceIndex = findSource(sources, reserved, ingredient);
                if (sourceIndex < 0) {
                    return null;
                }
                reserved[sourceIndex]++;
                addPlacement(placements, sources.get(sourceIndex), target);
            }
        }
        return placements;
    }

    private static int findSource(List<Slot> sources, int[] reserved, Ingredient ingredient) {
        for (int i = 0; i < sources.size(); i++) {
            ItemStack stack = sources.get(i).getStack();
            if (stack == null || stack.stackSize - reserved[i] <= 0) {
                continue;
            }
            try {
                if (ingredient.matchIngredient(stack)) {
                    return i;
                }
            } catch (Throwable ignored) {
                // A malformed ingredient should not take the whole menu down.
            }
        }
        return -1;
    }

    private static void addPlacement(List<Placement> placements, Slot source, Slot target) {
        for (Placement placement : placements) {
            if (placement.source == source) {
                placement.targets.add(target);
                return;
            }
        }
        Placement placement = new Placement(source);
        placement.targets.add(target);
        placements.add(placement);
    }

    /** The crafting grid's slots, in the container's own order. */
    public static List<Slot> matrixSlots(Container container) {
        List<Slot> matrix = new ArrayList<>();
        for (Slot slot : container.slots) {
            if (slot != null && slot.inventory instanceof InventoryCrafting
                    && !(slot instanceof SlotCrafting)) {
                matrix.add(slot);
            }
        }
        return matrix;
    }

    /** The slot the finished item appears in. */
    public static Slot resultSlot(Container container) {
        for (Slot slot : container.slots) {
            if (slot instanceof SlotCrafting) {
                return slot;
            }
        }
        return null;
    }

    /** Everything that is not part of the crafting area — the player's own items. */
    private static List<Slot> inventorySlots(Container container) {
        List<Slot> sources = new ArrayList<>();
        for (Slot slot : container.slots) {
            if (slot != null && !(slot.inventory instanceof InventoryCrafting)
                    && !(slot instanceof SlotCrafting)) {
                sources.add(slot);
            }
        }
        return sources;
    }

    /** One source stack and every cell it has to fill. */
    private static final class Placement {
        final Slot source;
        final List<Slot> targets = new ArrayList<>();

        Placement(Slot source) {
            this.source = source;
        }
    }
}
