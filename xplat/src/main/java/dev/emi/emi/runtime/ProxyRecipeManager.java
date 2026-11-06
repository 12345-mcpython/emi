package dev.emi.emi.runtime;

import java.util.List;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public class ProxyRecipeManager {
	private static final Minecraft client = Minecraft.getInstance();
	
	public static boolean isAvailable() {
		return getRaw() != null;
	}

	public static RecipeManager getRaw() {
		Level world = client.level;
		if (world == null) {
			return null;
		}
		return world.getRecipeManager();
	}

	public static ResourceLocation getId(Recipe<?> recipe) {
		return recipe.getId();
	}

	public static boolean hasId(ResourceLocation id) {
		RecipeManager raw = getRaw();
		return raw.byKey(id).isPresent();
	}

	public static @Nullable Recipe<?> getRecipe(ResourceLocation id) {
		RecipeManager raw = getRaw();
		if (raw == null || id == null) {
			return null;
		}
		return raw.byKey(id).orElse(null);
	}

	public static <I extends Container, T extends Recipe<I>> Stream<T> streamMatches(RecipeType<T> type, I inventory) {
		RecipeManager raw = getRaw();
		if (raw == null) {
			return Stream.empty();
		}
		return raw.getRecipesFor(type, inventory, client.level).stream();
	}

	public static <I extends Container, T extends Recipe<I>> List<T> getMatches(RecipeType<T> type, I inventory) {
		return streamMatches(type, inventory).toList();
	}

	public static <I extends Container, T extends Recipe<I>> @Nullable T getFirst(RecipeType<T> type, I inventory) {
		RecipeManager raw = getRaw();
		if (raw == null) {
			return null;
		}
		return raw.getRecipeFor(type, inventory, client.level).orElse(null);
	}

	public static void bakeIds() {
		// Nothing to do on older versions
	}
}
