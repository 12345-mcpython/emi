package dev.emi.emi.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.crafting.Recipe;

import dev.emi.emi.network.EmiRecipeSyncS2CPacket;

/**
 * Client side half of {@link EmiRecipeSync}: collects the chunks and hands the complete map to the local
 * recipe sources once the last one arrived.
 */
public class EmiRecipeSyncClient {
	private static final Map<Identifier, Recipe<?>> pending = new LinkedHashMap<>();

	public static void handle(EmiRecipeSyncS2CPacket packet) {
		pending.putAll(packet.recipes());
		if (!packet.last()) {
			return;
		}
		Map<Identifier, Recipe<?>> complete = new LinkedHashMap<>(pending);
		pending.clear();
		if (LocalRecipes.hasSynced()) {
			// The loader sync already delivered the table, do not reload twice.
			return;
		}
		LocalRecipes.acceptSynced(complete);
		EmiReloadManager.reload();
	}
}
