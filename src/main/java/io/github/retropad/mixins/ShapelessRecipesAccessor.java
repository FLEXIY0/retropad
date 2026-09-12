package io.github.retropad.mixins;

import net.minecraft.common.recipe.Ingredient;
import net.minecraft.common.recipe.ShapelessRecipes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** As {@link ShapedRecipesAccessor}, for recipes whose ingredients have no fixed positions. */
@Mixin(ShapelessRecipes.class)
public interface ShapelessRecipesAccessor {

    @Accessor("ingredients")
    List<Ingredient> retropad$getIngredients();
}
