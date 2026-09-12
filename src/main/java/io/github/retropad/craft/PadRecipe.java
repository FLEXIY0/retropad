package io.github.retropad.craft;

import net.minecraft.common.item.ItemStack;
import net.minecraft.common.recipe.Ingredient;

/**
 * One craftable entry in the menu: what comes out, and what has to go into which cell.
 *
 * <p>Cells are stored in a fixed 3x3 layout with nulls for empty ones, whatever shape the
 * recipe declared, so that filling a crafting grid is the same loop for every recipe.
 */
public final class PadRecipe {

    public static final int GRID = 3;

    private final ItemStack output;
    private final Ingredient[] cells;
    private final int width;
    private final int height;
    private final boolean shapeless;
    private final RecipeCategory category;

    PadRecipe(ItemStack output, Ingredient[] cells, int width, int height, boolean shapeless) {
        this.output = output;
        this.cells = cells;
        this.width = width;
        this.height = height;
        this.shapeless = shapeless;
        this.category = RecipeCategory.of(output);
    }

    public ItemStack getOutput() {
        return this.output;
    }

    public RecipeCategory getCategory() {
        return this.category;
    }

    /** Width of the recipe's own shape, 1 to 3. */
    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public boolean isShapeless() {
        return this.shapeless;
    }

    /** Fits inside the player's own 2x2 grid, so no crafting table is needed. */
    public boolean fitsInInventoryGrid() {
        return this.width <= 2 && this.height <= 2;
    }

    /** The ingredient for a cell of the recipe's own shape, or null when that cell is empty. */
    public Ingredient cellAt(int column, int row) {
        if (column < 0 || row < 0 || column >= this.width || row >= this.height) {
            return null;
        }
        return this.cells[row * this.width + column];
    }

    /** Every non-empty ingredient, in reading order. */
    public Ingredient[] ingredients() {
        int count = 0;
        for (Ingredient cell : this.cells) {
            if (cell != null) {
                count++;
            }
        }
        Ingredient[] present = new Ingredient[count];
        int next = 0;
        for (Ingredient cell : this.cells) {
            if (cell != null) {
                present[next++] = cell;
            }
        }
        return present;
    }
}
