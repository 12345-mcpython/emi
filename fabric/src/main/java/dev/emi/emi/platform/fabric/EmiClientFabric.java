package dev.emi.emi.platform.fabric;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

import dev.emi.emi.data.EmiData;
import dev.emi.emi.network.CommandS2CPacket;
import dev.emi.emi.network.EmiChessPacket;
import dev.emi.emi.network.EmiNetwork;
import dev.emi.emi.network.EmiPacket;
import dev.emi.emi.network.PingS2CPacket;
import dev.emi.emi.platform.EmiClient;
import dev.emi.emi.registry.EmiTags;
import io.netty.buffer.Unpooled;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.model.ModelLoadingRegistry;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;

public class EmiClientFabric implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		EmiClient.init();
		EmiData.init(reloader -> {
			ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new IdentifiableResourceReloadListener() {

				@Override
				public CompletableFuture<Void> reload(PreparableReloadListener.PreparationBarrier var1, ResourceManager var2, ProfilerFiller var3,
						ProfilerFiller var4, Executor var5, Executor var6) {
					return reloader.reload(var1, var2, var3, var4, var5, var6);
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

		ModelLoadingRegistry.INSTANCE.registerModelProvider((manager, consumer) -> {
			EmiTags.registerTagModels(manager, consumer);
		});

		EmiNetwork.initClient(packet -> {
			FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
			packet.write(buf);
			ClientPlayNetworking.send(packet.getId(), buf);
		});

		registerPacketReader(EmiNetwork.PING, PingS2CPacket::new);
		registerPacketReader(EmiNetwork.COMMAND, CommandS2CPacket::new);
		registerPacketReader(EmiNetwork.CHESS, EmiChessPacket.S2C::new);
	}

	private void registerPacketReader(ResourceLocation id, Function<FriendlyByteBuf, EmiPacket> create) {
		ClientPlayNetworking.registerGlobalReceiver(id, (client, handler, buf, sender) -> {
			EmiPacket packet = create.apply(buf);
			client.execute(() -> {
				packet.apply(client.player);
			});
		});
	}
}
