package io.github.retropad.craft;

import io.github.retropad.RetroPad;
import io.github.retropad.mixins.ShapedRecipesAccessor;
import io.github.retropad.mixins.ShapelessRecipesAccessor;
import net.minecraft.common.item.ItemStack;
import net.minecraft.common.recipe.CraftingManager;
import net.minecraft.common.recipe.IRecipe;
import net.minecraft.common.recipe.Ingredient;
import net.minecraft.common.recipe.ShapedRecipes;
import net.minecraft.common.recipe.ShapelessRecipes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every recipe the crafting menu can show, grouped by category.
 *
 * <p>Built once on first use and then reused: the list only changes when mods register recipes,
 * which has finished long before a screen can be opened. Recipes whose ingredients are computed
 * rather than declared — armour dyeing and the like — are skipped, because there is nothing to
 * show in a grid and nothing definite to put in one.
 *
 * <p>Recipes making the same item are folded into one {@link PadRecipeGroup}, so the strip shows
 * a result once rather than repeating it for every accepted set of ingredients.
 */
public final class RecipeCatalog {

    private static RecipeCatalog instance;

    private final Map<RecipeCategory, List<PadRecipeGroup>> byCategory =
            new EnumMap<>(RecipeCategory.class);
    private final int total;
    private final int groupCount;

    private RecipeCatalog(List<PadRecipe> recipes) {
        for (RecipeCategory category : RecipeCategory.VALUES) {
            this.byCategory.put(category, new ArrayList<PadRecipeGroup>());
        }
        // Keyed by result so the alternatives land together, and insertion-ordered so the strip
        // keeps the order the game registered its recipes in.
        Map<Long, PadRecipeGroup> groups = new LinkedHashMap<>();
        for (PadRecipe recipe : recipes) {
            long key = PadRecipeGroup.keyOf(recipe.getOutput());
            PadRecipeGroup group = groups.get(key);
            if (group == null) {
                group = new PadRecipeGroup(recipe);
                groups.put(key, group);
                this.byCategory.get(recipe.getCategory()).add(group);
            } else {
                group.add(recipe);
            }
        }
        this.total = recipes.size();
        this.groupCount = groups.size();
    }

    public static RecipeCatalog get() {
        if (instance == null) {
            instance = build();
        }
        return instance;
    }

    /** Drops the cached catalog, in case recipes are ever registered late. */
    public static void invalidate() {
        instance = null;
    }

    public List<PadRecipeGroup> inCategory(RecipeCategory category) {
        List<PadRecipeGroup> groups = this.byCategory.get(category);
        return groups == null ? Collections.<PadRecipeGroup>emptyList() : groups;
    }

    public int size() {
        return this.total;
    }

    private static RecipeCatalog build() {
        List<PadRecipe> recipes = new ArrayList<>();
        int skipped = 0;
        try {
            for (Object entry : CraftingManager.getInstance().getRecipeList()) {
                if (!(entry instanceof IRecipe)) {
                    continue;
                }
                PadRecipe recipe = convert((IRecipe) entry);
                if (recipe == null) {
                    skipped++;
                } else {
                    recipes.add(recipe);
                }
            }
        } catch (Throwable throwable) {
            RetroPad.LOGGER.warn("Could not read the recipe list: " + throwable);
        }
        RecipeCatalog catalog = new RecipeCatalog(recipes);
        RetroPad.LOGGER.info("Crafting menu: " + recipes.size() + " recipes in "
                + catalog.groupCount + " groups (" + skipped + " with computed ingredients skipped)");
        return catalog;
    }

    private static PadRecipe convert(IRecipe recipe) {
        ItemStack output;
        try {
            output = recipe.getRecipeOutput();
        } catch (Throwable throwable) {
            return null;
        }
        if (output == null) {
            return null;
        }

        if (recipe instanceof ShapedRecipes) {
            ShapedRecipesAccessor shaped = (ShapedRecipesAccessor) recipe;
            Ingredient[] cells = shaped.retropad$getIngredients();
            int width = shaped.retropad$getWidth();
            int height = shaped.retropad$getHeight();
            if (cells == null || width <= 0 || height <= 0 || cells.length < width * height) {
                return null;
            }
            return new PadRecipe(output, cells.clone(), width, height, false);
        }

        if (recipe instanceof ShapelessRecipes) {
            List<Ingredient> list = ((ShapelessRecipesAccessor) recipe).retropad$getIngredients();
            if (list == null || list.isEmpty() || list.size() > PadRecipe.GRID * PadRecipe.GRID) {
                return null;
            }
            // Lay a shapeless recipe out left to right; the grid does not care where the
            // pieces sit, and a stable layout is what lets the menu draw and fill it.
            int width = Math.min(PadRecipe.GRID, list.size());
            int height = (list.size() + width - 1) / width;
            Ingredient[] cells = new Ingredient[width * height];
            for (int i = 0; i < list.size(); i++) {
                cells[i] = list.get(i);
            }
            return new PadRecipe(output, cells, width, height, true);
        }

        return null;
    }
}
