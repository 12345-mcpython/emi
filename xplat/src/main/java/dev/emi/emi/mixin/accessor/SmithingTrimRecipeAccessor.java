package dev.emi.emi.mixin.accessor;

import java.util.Optional;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SmithingTrimRecipe.class)
public interface SmithingTrimRecipeAccessor {

	@Accessor("template")
	Optional<Ingredient> getTemplate();

	@Accessor("base")
	Optional<Ingredient> getBase();

	@Accessor("addition")
	Optional<Ingredient> getAddition();
}
