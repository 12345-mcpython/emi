package dev.emi.emi.runtime;

import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.crafting.Recipe;

import dev.emi.emi.EmiPort;

/**
 * Reads the recipes that ship with the client's own data packs.
 * <p>
 * A 1.21.4 server never sends the recipe table: {@code ClientboundUpdateRecipesPacket} only carries the
 * item property sets and stonecutter options, and the recipe book packet only covers unlocked recipes. On
 * a server without EMI that leaves the index almost empty, so fall back to the data pack copy the client
 * already has.
 */
public class LocalRecipes {
	private static Map<ResourceLocation, Recipe<?>> cache = null;
	private static int failed = 0;

	public static Map<ResourceLocation, Recipe<?>> get() {
		if (cache == null) {
			cache = load();
		}
		return cache;
	}

	public static void invalidate() {
		cache = null;
		failed = 0;
	}

	public static int failures() {
		return failed;
	}

	private static Map<ResourceLocation, Recipe<?>> load() {
		Map<ResourceLocation, Recipe<?>> recipes = new LinkedHashMap<>();
		failed = 0;
		Minecraft client = Minecraft.getInstance();
		if (client.getResourceManager() == null || client.level == null) {
			return recipes;
		}
		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, client.level.registryAccess());
		for (ResourceLocation file : client.getResourceManager().listResources("recipe",
				path -> path.getPath().endsWith(".json")).keySet()) {
			try {
				Resource resource = client.getResourceManager().getResourceOrThrow(file);
				try (Reader reader = resource.openAsReader()) {
					JsonElement json = JsonParser.parseReader(reader);
					Recipe<?> recipe = Recipe.CODEC.parse(ops, json).getOrThrow();
					recipes.put(stripPrefix(file), recipe);
				}
			} catch (Throwable t) {
				failed++;
			}
		}
		if (failed > 0) {
			EmiLog.warn("[EMI] Local data pack fallback skipped " + failed + " unreadable recipe files");
		}
		if (!recipes.isEmpty()) {
			EmiLog.warn("[EMI] Local data pack fallback loaded " + recipes.size()
				+ " recipes because the server did not provide any");
		}
		return recipes;
	}

	private static ResourceLocation stripPrefix(ResourceLocation file) {
		String path = file.getPath();
		if (path.startsWith("recipe/")) {
			path = path.substring("recipe/".length());
		}
		if (path.endsWith(".json")) {
			path = path.substring(0, path.length() - ".json".length());
		}
		return EmiPort.id(file.getNamespace(), path);
	}
}
