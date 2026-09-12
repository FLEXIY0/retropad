package io.github.retropad.mixins;

import net.minecraft.common.recipe.Ingredient;
import net.minecraft.common.recipe.ShapedRecipes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A shaped recipe keeps its grid private; the crafting menu needs it to show and fill one. */
@Mixin(ShapedRecipes.class)
public interface ShapedRecipesAccessor {

    @Accessor("ingredients")
    Ingredient[] retropad$getIngredients();

    @Accessor("width")
    int retropad$getWidth();

    @Accessor("height")
    int retropad$getHeight();
}
