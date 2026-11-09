package dev.emi.emi.network;

import java.util.Map;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;

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
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("fabric", "recipe_sync"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Fabric> CODEC =
			StreamCodec.of((buf, payload) -> { }, buf -> new Fabric(LocalRecipes.decodeFabric(buf)));

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** {@code fabric:recipe_sync_finished}: marker the bridge sends after the recipe list. */
	public record FabricFinished() implements CustomPacketPayload {
		public static final CustomPacketPayload.Type<FabricFinished> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("fabric", "recipe_sync_finished"));
		public static final StreamCodec<RegistryFriendlyByteBuf, FabricFinished> CODEC =
			StreamCodec.of((buf, payload) -> { }, buf -> new FabricFinished());

		@Override
		public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
