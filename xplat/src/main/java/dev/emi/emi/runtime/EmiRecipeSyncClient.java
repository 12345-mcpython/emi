package dev.emi.emi.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;

import dev.emi.emi.network.EmiRecipeSyncS2CPacket;

/**
 * Client side half of {@link EmiRecipeSync}: collects the chunks and hands the complete map to the local
 * recipe sources once the last one arrived.
 */
public class EmiRecipeSyncClient {
	private static final Map<ResourceLocation, Recipe<?>> pending = new LinkedHashMap<>();

	public static void handle(EmiRecipeSyncS2CPacket packet) {
		pending.putAll(packet.recipes());
		if (packet.last()) {
			LocalRecipes.acceptSynced(pending);
			pending.clear();
			EmiReloadManager.reload();
		}
	}
}
