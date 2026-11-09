package dev.emi.emi.network;

import dev.emi.emi.runtime.EmiLog;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class EmiNetwork {
    public static final CustomPacketPayload.Type<FillRecipeC2SPacket> FILL_RECIPE = new CustomPacketPayload.Type<>(ResourceLocation.parse("emi:fill_recipe"));
    public static final CustomPacketPayload.Type<CreateItemC2SPacket> CREATE_ITEM = new CustomPacketPayload.Type<>(ResourceLocation.parse("emi:create_item"));
    public static final CustomPacketPayload.Type<CommandS2CPacket> COMMAND = new CustomPacketPayload.Type<>(ResourceLocation.parse("emi:command"));
    public static final CustomPacketPayload.Type<EmiChessPacket> CHESS = new CustomPacketPayload.Type<>(ResourceLocation.parse("emi:chess"));
    public static final CustomPacketPayload.Type<PingS2CPacket> PING = new CustomPacketPayload.Type<>(ResourceLocation.parse("emi:ping"));
    private static BiConsumer<ServerPlayer, EmiPacket> clientSender;
    private static Consumer<EmiPacket> serverSender;
    private static final Set<Class<?>> rejectedSends = new HashSet<>();

    public static void initServer(BiConsumer<ServerPlayer, EmiPacket> sender) {
        clientSender = sender;
    }

    public static void initClient(Consumer<EmiPacket> sender) {
        serverSender = sender;
    }

    public static void sendToClient(ServerPlayer player, EmiPacket packet) {
        clientSender.accept(player, packet);
    }

    public static void sendToServer(EmiPacket packet) {
        if (serverSender == null) {
            return;
        }
        try {
            serverSender.accept(packet);
        } catch (RuntimeException e) {
            if (rejectedSends.add(packet.getClass())) {
                EmiLog.warn("Server rejected the EMI packet " + packet.type().id()
                    + "; the server probably does not have EMI installed");
            }
        }
    }
}
