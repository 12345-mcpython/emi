package dev.emi.emi.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import dev.emi.emi.network.EmiNetwork;
import dev.emi.emi.network.EmiRecipeSyncS2CPacket;

/**
 * Server side recipe push. Runs on a dedicated server, so it stays free of client references.
 */
public class EmiRecipeSync {
	private static final int RECIPES_PER_PACKET = 256;

	public static void send(ServerPlayer player) {
		MinecraftServer server = player.getServer();
		if (server == null) {
			return;
		}
		List<RecipeHolder<?>> holders = new ArrayList<>(server.getRecipeManager().getRecipes());
		if (holders.isEmpty()) {
			return;
		}
		int sent = 0;
		for (int index = 0; index < holders.size(); index += RECIPES_PER_PACKET) {
			int end = Math.min(index + RECIPES_PER_PACKET, holders.size());
			Map<ResourceLocation, Recipe<?>> chunk = new LinkedHashMap<>();
			for (RecipeHolder<?> holder : holders.subList(index, end)) {
				chunk.put(holder.id().location(), holder.value());
			}
			boolean last = end >= holders.size();
			EmiNetwork.sendToClient(player, new EmiRecipeSyncS2CPacket(last, chunk));
			sent += chunk.size();
		}
		EmiLog.warn("[EMI] Sent " + sent + " recipes to " + player.getGameProfile().getName());
	}
}
