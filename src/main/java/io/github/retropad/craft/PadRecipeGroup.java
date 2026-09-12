package io.github.retropad.craft;

import net.minecraft.common.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Every way of making one particular item, gathered under a single cell.
 *
 * <p>ReIndev registers a separate recipe for each accepted set of ingredients, so a plain listing
 * repeats the same result over and over — eleven identical entries for pumpkin seeds, say. The
 * console UI shows one cell per result and lets you page through the alternatives with up and
 * down, which is what the arrows above and below the selected cell are for.
 */
public final class PadRecipeGroup {

    private final List<PadRecipe> variants = new ArrayList<>();

    PadRecipeGroup(PadRecipe first) {
        this.variants.add(first);
    }

    void add(PadRecipe recipe) {
        this.variants.add(recipe);
    }

    /** What the cell shows: the result, which every variant shares. */
    public ItemStack getOutput() {
        return this.variants.get(0).getOutput();
    }

    public RecipeCategory getCategory() {
        return this.variants.get(0).getCategory();
    }

    public int size() {
        return this.variants.size();
    }

    public boolean hasAlternatives() {
        return this.variants.size() > 1;
    }

    /** Wraps, so holding up or down cycles through the alternatives without stopping. */
    public PadRecipe variant(int index) {
        int size = this.variants.size();
        int wrapped = ((index % size) + size) % size;
        return this.variants.get(wrapped);
    }

    public List<PadRecipe> variants() {
        return this.variants;
    }

    /**
     * The key two recipes must share to belong in the same group: the same item, in the same
     * damage state. Stack size is deliberately left out — one recipe giving four planks and
     * another giving one is still "how to make planks".
     */
    static long keyOf(ItemStack output) {
        if (output == null) {
            return Long.MIN_VALUE;
        }
        int damage;
        try {
            damage = output.getItemDamage();
        } catch (Throwable ignored) {
            damage = 0;
        }
        return ((long) output.getItemID() << 32) | (damage & 0xFFFFFFFFL);
    }
}
