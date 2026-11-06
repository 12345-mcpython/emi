package dev.emi.emi.runtime;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;

/**
 * Recipe lookup for the 1.21.2+ client.
 * <p>
 * The client no longer keeps a recipe table, so recipes are read from the integrated server while a
 * singleplayer world is open. Multiplayer still needs the {@code RecipeDisplay} pipeline: the server only
 * synchronizes displays there.
 */
public class ProxyRecipeManager {
	private static final Minecraft client = Minecraft.getInstance();
	private static Map<Recipe<?>, ResourceKey<Recipe<?>>> recipeIds = Map.of();

	public static boolean isAvailable() {
		return getRaw() != null;
	}

	public static @Nullable RecipeManager getRaw() {
		IntegratedServer server = client.getSingleplayerServer();
		if (server == null) {
			return null;
		}
		return server.getRecipeManager();
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
		RecipeManager raw = getRaw();
		if (raw == null || id == null) {
			return null;
		}
		for (RecipeHolder<?> holder : raw.getRecipes()) {
			if (holder.id().location().equals(id)) {
				return holder;
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	public static <I extends RecipeInput, T extends Recipe<I>> Stream<T> streamMatches(RecipeType<T> type, I inventory) {
		RecipeManager raw = getRaw();
		Level level = client.level;
		if (raw == null || level == null) {
			return Stream.empty();
		}
		return raw.getRecipes().stream()
			.filter(holder -> holder.value().getType() == type)
			.filter(holder -> ((Recipe<I>) holder.value()).matches(inventory, level))
			.map(holder -> (T) holder.value());
	}

	public static <I extends RecipeInput, T extends Recipe<I>> List<T> getMatches(RecipeType<T> type, I inventory) {
		return streamMatches(type, inventory).toList();
	}

	public static <I extends RecipeInput, T extends Recipe<I>> @Nullable T getFirst(RecipeType<T> type, I inventory) {
		RecipeManager raw = getRaw();
		if (raw == null || client.level == null) {
			return null;
		}
		return raw.getRecipeFor(type, inventory, client.level).map(RecipeHolder::value).orElse(null);
	}

	public static void bakeIds() {
		RecipeManager raw = getRaw();
		if (raw == null) {
			return;
		}
		Map<Recipe<?>, ResourceKey<Recipe<?>>> ids = new Reference2ObjectOpenHashMap<>();
		for (RecipeHolder<?> holder : raw.getRecipes()) {
			ids.put(holder.value(), holder.id());
		}
		recipeIds = ids;
	}
}
