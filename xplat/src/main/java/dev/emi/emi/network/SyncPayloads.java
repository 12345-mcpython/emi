package dev.emi.emi.network;

import java.util.Map;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;

import dev.emi.emi.EmiPort;
import dev.emi.emi.runtime.LocalRecipes;

/**
 * Clientbound channels of third party server side recipe sync, such as the Paper JEI compat plugin.
 * <p>
 * A 1.21.4 server never sends the recipe table, so without this a vanilla or Paper server shows almost no
 * recipes. These payloads carry serializer encoded recipes; both are registered as optional channels so a
 * server that does not send them is unaffected.
 */
public final class SyncPayloads {
	private SyncPayloads() {
	}

	/** {@code fabric:recipe_sync}: serializer grouped recipe holders. */
	public record Fabric(Map<ResourceLocation, Recipe<?>> recipes) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<Fabric> TYPE =
			new CustomPacketPayload.Type<>(EmiPort.id("fabric", "recipe_sync"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Fabric> CODEC =
			StreamCodec.of((buf, payload) -> { }, buf -> new Fabric(LocalRecipes.decodeFabric(buf)));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** {@code neoforge:recipe_content}: recipe type filter plus recipe holders. */
	public record NeoForge(Map<ResourceLocation, Recipe<?>> recipes) implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<NeoForge> TYPE =
			new CustomPacketPayload.Type<>(EmiPort.id("neoforge", "recipe_content"));
		public static final StreamCodec<RegistryFriendlyByteBuf, NeoForge> CODEC =
			StreamCodec.of((buf, payload) -> { }, buf -> new NeoForge(LocalRecipes.decodeNeoForge(buf)));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
