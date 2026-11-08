package dev.emi.emi.runtime;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeSerializer;

import dev.emi.emi.EmiPort;

/**
 * Recipe sources that do not come from the network.
 * <p>
 * A 1.21.4 server never sends the recipe table: {@code ClientboundUpdateRecipesPacket} only carries the
 * item property sets and stonecutter options, and the recipe book packet only covers unlocked recipes. The
 * client jar does contain the vanilla recipe JSON, but it lives in the data pack domain, while
 * {@code Minecraft#getResourceManager} only exposes the client resources domain. This reads the data packs
 * directly through {@link PackResources#listResources} with {@link PackType#SERVER_DATA}.
 */
public class LocalRecipes {
	private static Map<ResourceLocation, Recipe<?>> cache = null;
	private static Map<ResourceLocation, Recipe<?>> synced = null;
	private static int failed = 0;

	/**
	 * Recipes pushed by a server side recipe sync (EMI on the server, or a compat plugin using the
	 * {@code fabric:recipe_sync} / {@code neoforge:recipe_content} channels).
	 */
	public static void acceptSynced(Map<ResourceLocation, Recipe<?>> recipes) {
		synced = Map.copyOf(recipes);
		cache = null;
		EmiLog.warn("[EMI] Server recipe sync provided " + recipes.size() + " recipes");
	}

	public static Map<ResourceLocation, Recipe<?>> get() {
		if (synced != null) {
			return synced;
		}
		if (cache == null) {
			cache = load();
		}
		return cache;
	}

	public static void invalidate() {
		cache = null;
		synced = null;
		failed = 0;
	}

	public static int failures() {
		return failed;
	}

	private static Map<ResourceLocation, Recipe<?>> load() {
		Map<ResourceLocation, Recipe<?>> recipes = new LinkedHashMap<>();
		failed = 0;
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			return recipes;
		}
		Map<ResourceLocation, IoSupplier<InputStream>> files = new LinkedHashMap<>();
		VanillaPackResources vanilla = client.getVanillaPackResources();
		if (vanilla != null) {
			collect(vanilla, files);
		}
		for (Pack pack : client.getResourcePackRepository().getSelectedPacks()) {
			try (PackResources resources = pack.open()) {
				collect(resources, files);
			} catch (Throwable t) {
				failed++;
			}
		}
		if (files.isEmpty()) {
			EmiLog.warn("[EMI] Local data pack has no recipe resources; the client only gets recipes from the server");
			return recipes;
		}
		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, client.level.registryAccess());
		for (Map.Entry<ResourceLocation, IoSupplier<InputStream>> entry : files.entrySet()) {
			try (InputStream stream = entry.getValue().get();
					Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
				JsonElement json = JsonParser.parseReader(reader);
				Recipe<?> recipe = Recipe.CODEC.parse(ops, json).getOrThrow();
				recipes.put(stripPrefix(entry.getKey()), recipe);
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

	private static void collect(PackResources pack, Map<ResourceLocation, IoSupplier<InputStream>> files) {
		for (String namespace : pack.getNamespaces(PackType.SERVER_DATA)) {
			pack.listResources(PackType.SERVER_DATA, namespace, "recipe", (id, supplier) -> {
				if (id.getPath().endsWith(".json")) {
					files.putIfAbsent(id, supplier);
				}
			});
		}
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

	/** Reads the {@code fabric:recipe_sync} payload: a list of serializer grouped holders. */
	public static Map<ResourceLocation, Recipe<?>> decodeFabric(RegistryFriendlyByteBuf buf) {
		Map<ResourceLocation, Recipe<?>> recipes = new LinkedHashMap<>();
		int groups = buf.readVarInt();
		for (int g = 0; g < groups; g++) {
			ResourceLocation serializerId = buf.readResourceLocation();
			RecipeSerializer<?> serializer = BuiltInRegistries.RECIPE_SERIALIZER.get(serializerId)
				.map(net.minecraft.core.Holder.Reference::value).orElse(null);
			int count = buf.readVarInt();
			for (int i = 0; i < count; i++) {
				ResourceLocation id = buf.readResourceLocation();
				if (serializer == null) {
					continue;
				}
				try {
					recipes.put(id, serializer.streamCodec().decode(buf));
				} catch (Throwable t) {
					failed++;
				}
			}
		}
		return recipes;
	}

	/** Reads the {@code neoforge:recipe_content} payload: a recipe type filter plus holders. */
	public static Map<ResourceLocation, Recipe<?>> decodeNeoForge(RegistryFriendlyByteBuf buf) {
		Map<ResourceLocation, Recipe<?>> recipes = new LinkedHashMap<>();
		int types = buf.readVarInt();
		Set<Object> ignored = new HashSet<>();
		for (int i = 0; i < types; i++) {
			ignored.add(buf.readVarInt());
		}
		int count = buf.readVarInt();
		for (int i = 0; i < count; i++) {
			try {
				RecipeHolder<?> holder = RecipeHolder.STREAM_CODEC.decode(buf);
				recipes.put(holder.id().location(), holder.value());
			} catch (Throwable t) {
				failed++;
			}
		}
		return recipes;
	}
}
