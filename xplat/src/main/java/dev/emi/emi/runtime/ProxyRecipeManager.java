package dev.emi.emi.runtime;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import org.jetbrains.annotations.Nullable;

/**
 * Recipe ids used to come from the client recipe manager. Since 1.21.2 the client no longer keeps
 * a recipe table at all: recipes are synchronized as {@code RecipeDisplay} entries, so this proxy
 * currently reports no recipes and the vanilla recipe categories stay empty until the display
 * pipeline is wired up.
 */
public class ProxyRecipeManager {
	private static Map<Recipe<?>, ResourceKey<Recipe<?>>> recipeIds = Map.of();

	public static boolean isAvailable() {
		return false;
	}

	public static @Nullable RecipeManager getRaw() {
		return null;
	}

	public static @Nullable ResourceLocation getId(Recipe<?> recipe) {
		ResourceKey<Recipe<?>> key = recipeIds.get(recipe);
		return key == null ? null : key.location();
	}

	public static boolean hasId(ResourceLocation id) {
		for (ResourceKey<Recipe<?>> key : recipeIds.values()) {
			if (key.location().equals(id)) {
				return true;
			}
		}
		return false;
	}

	public static @Nullable Recipe<?> getRecipe(ResourceLocation id) {
		RecipeHolder<?> entry = getRecipeEntry(id);
		return entry == null ? null : entry.value();
	}

	public static @Nullable RecipeHolder<?> getRecipeEntry(ResourceLocation id) {
		return null;
	}

	public static <I extends RecipeInput, T extends Recipe<I>> Stream<T> streamMatches(RecipeType<T> type, I inventory) {
		return Stream.empty();
	}

	public static <I extends RecipeInput, T extends Recipe<I>> List<T> getMatches(RecipeType<T> type, I inventory) {
		return List.of();
	}

	public static <I extends RecipeInput, T extends Recipe<I>> @Nullable T getFirst(RecipeType<T> type, I inventory) {
		return null;
	}

	public static void bakeIds() {
		recipeIds = Map.of();
	}
}
