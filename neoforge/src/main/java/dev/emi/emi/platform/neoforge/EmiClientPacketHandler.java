package dev.emi.emi.platform.neoforge;

import dev.emi.emi.network.CommandS2CPacket;
import dev.emi.emi.network.EmiChessPacket;
import dev.emi.emi.network.EmiNetwork;
import dev.emi.emi.network.EmiRecipeSyncS2CPacket;
import dev.emi.emi.network.PingS2CPacket;
import dev.emi.emi.network.SyncPayloads;
import dev.emi.emi.runtime.EmiLog;
import dev.emi.emi.runtime.EmiReloadManager;
import dev.emi.emi.runtime.LocalRecipes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * NeoForge 21.8 moved clientbound payload handlers to a client only event; the mod bus event only
 * registers the payload type/codec. Without this a client never handles {@code emi:recipe_sync}, which
 * shows up as "server provided no recipes" when connecting to a Fabric server (or any third party sync).
 */
@EventBusSubscriber(modid = "emi", value = Dist.CLIENT)
public class EmiClientPacketHandler {

	@SubscribeEvent
	public static void register(RegisterClientPayloadHandlersEvent event) {
		event.register(EmiRecipeSyncS2CPacket.TYPE,
			(payload, context) -> context.enqueueWork(() -> payload.apply(context.player())));
		event.register(EmiNetwork.PING, (payload, context) -> handle(payload, context));
		event.register(EmiNetwork.COMMAND, (payload, context) -> handle(payload, context));
		event.register(EmiPacketHandler.ID_CHESS_CLIENTBOUND, (payload, context) -> handle(payload, context));
		event.register(SyncPayloads.Fabric.TYPE, (payload, context) -> context.enqueueWork(() -> {
			LocalRecipes.acceptSynced(payload.recipes());
			EmiReloadManager.reload();
		}));
		event.register(SyncPayloads.FabricFinished.TYPE,
			(payload, context) -> context.enqueueWork(() -> EmiLog.info("Server recipe sync finished")));
	}

	private static void handle(dev.emi.emi.network.EmiPacket packet, IPayloadContext context) {
		if (!context.flow().isClientbound()) {
			throw new IllegalArgumentException("Trying to handle clientbound packet on server: " + packet);
		}
		var player = context.player();
		context.enqueueWork(() -> packet.apply(player));
	}
}
