package dev.emi.emi.mixin.accessor;

import java.util.Optional;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SmithingTransformRecipe.class)
public interface SmithingTransformRecipeAccessor {

	@Accessor("template")
	Optional<Ingredient> getTemplate();

	@Accessor("base")
	Ingredient getBase();

	@Accessor("addition")
	Optional<Ingredient> getAddition();
}
