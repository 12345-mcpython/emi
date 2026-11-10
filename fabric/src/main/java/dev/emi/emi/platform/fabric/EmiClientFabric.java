package dev.emi.emi.platform.fabric;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

import com.google.common.collect.Lists;

import dev.emi.emi.data.EmiData;
import dev.emi.emi.network.CommandS2CPacket;
import dev.emi.emi.network.EmiChessPacket;
import dev.emi.emi.network.EmiNetwork;
import dev.emi.emi.network.EmiRecipeSyncS2CPacket;
import dev.emi.emi.runtime.EmiRecipeSyncClient;
import dev.emi.emi.network.SyncPayloads;
import dev.emi.emi.network.EmiPacket;
import dev.emi.emi.network.PingS2CPacket;
import dev.emi.emi.platform.EmiClient;
import dev.emi.emi.registry.EmiTags;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.fabricmc.fabric.api.client.model.loading.v1.PreparableModelLoadingPlugin;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.StreamDecoder;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import dev.emi.emi.runtime.LocalRecipes;
import dev.emi.emi.runtime.EmiReloadManager;
import dev.emi.emi.runtime.EmiLog;

public class EmiClientFabric implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		EmiClient.init();
		EmiData.init(reloader -> {
			ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new IdentifiableResourceReloadListener() {

				@Override
				public CompletableFuture<Void> reload(PreparableReloadListener.SharedState state,
					Executor prepareExecutor, PreparableReloadListener.PreparationBarrier barrier, Executor applyExecutor) {
					return reloader.reload(state, prepareExecutor, barrier, applyExecutor);
				}

				@Override
				public String getName() {
					return reloader.getName();
				}

				@Override
				public ResourceLocation getFabricId() {
					return reloader.getEmiId();
				}
			});
		});

		PreparableModelLoadingPlugin.<List<ResourceLocation>>register((manager, executor) -> {
			return CompletableFuture.supplyAsync(() -> {
				List<ResourceLocation> ids = Lists.newArrayList();
				EmiTags.registerTagModels(manager.resourceManager(), ids::add);
				return ids;
			}, executor);
		}, (ids, context) -> {
			// 1.21.5 replaced standalone model registration with ExtraModelKey; the tag model
			// pipeline is stubbed, so the collected ids are skipped here.
		});

		EmiNetwork.initClient(packet -> {
			if (ClientPlayNetworking.canSend(packet.type())) {
				ClientPlayNetworking.send(packet);
			}
		});

		// Third party server recipe sync (for example the Paper JEI compat plugin). Optional channels: a
		// server that never sends them is unaffected. JEI 26 on Fabric registers the fabric:recipe_sync
		// channels itself, so only claim them when they are still free.
		if (tryRegisterS2C(SyncPayloads.Fabric.TYPE, SyncPayloads.Fabric.CODEC)) {
			ClientPlayNetworking.registerGlobalReceiver(SyncPayloads.Fabric.TYPE, (payload, context) ->
				context.client().execute(() -> {
					LocalRecipes.acceptSynced(payload.recipes());
					EmiReloadManager.reload();
				}));
		}
		// NeoForge owns neoforge:recipe_content and re-posts it as RecipesReceivedEvent.
		if (tryRegisterS2C(SyncPayloads.FabricFinished.TYPE, SyncPayloads.FabricFinished.CODEC)) {
			ClientPlayNetworking.registerGlobalReceiver(SyncPayloads.FabricFinished.TYPE, (payload, context) ->
				context.client().execute(() -> EmiLog.info("Server recipe sync finished")));
		}

		EmiRecipeSyncS2CPacket.clientHandler = EmiRecipeSyncClient::handle;
		ClientPlayNetworking.registerGlobalReceiver(EmiRecipeSyncS2CPacket.TYPE, (payload, context) ->
			context.client().execute(() -> payload.apply(context.player())));

		registerPacketReader(EmiNetwork.PING, PingS2CPacket::new);
		registerPacketReader(EmiNetwork.COMMAND, CommandS2CPacket::new);
		registerPacketReader(EmiNetwork.CHESS, EmiChessPacket.S2C::new);
	}

	private static <T extends CustomPacketPayload> boolean tryRegisterS2C(CustomPacketPayload.Type<T> id, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
		try {
			PayloadTypeRegistry.playS2C().register(id, codec);
			return true;
		} catch (IllegalArgumentException e) {
			// Another mod (JEI 26 on Fabric) already owns this channel.
			return false;
		}
	}

	private <T extends EmiPacket> void registerPacketReader(CustomPacketPayload.Type<T> id, StreamDecoder<RegistryFriendlyByteBuf, T> decode) {
		ClientPlayNetworking.registerGlobalReceiver(id, (payload, context) -> {
			context.client().execute(() -> {
				payload.apply(context.client().player);
			});
		});
	}
}
