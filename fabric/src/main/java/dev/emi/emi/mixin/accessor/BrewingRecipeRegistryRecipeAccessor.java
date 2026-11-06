package dev.emi.emi.mixin.accessor;

import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.Ingredient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PotionBrewing.Mix.class)
public interface BrewingRecipeRegistryRecipeAccessor {

	@Accessor("from")
	Object getInput();

	@Accessor("ingredient")
	Ingredient getIngredient();

	@Accessor("to")
	Object getOutput();
}
