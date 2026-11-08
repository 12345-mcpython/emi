package dev.emi.emi.mixin;

import dev.emi.emi.runtime.BookmarkCrafting;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feed only received server inventory data to the bookmark executor.
 */
@Mixin(ClientPacketListener.class)
public abstract class BookmarkInventorySyncMixin {
    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    private void emi$bookmarkSlotUpdate(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        BookmarkCrafting.observeServerSlot(packet.getContainerId(), packet.getSlot(), packet.getItem());
        dev.emi.emi.runtime.BookmarkMaterialTransfer.observeServerSlot(packet.getContainerId(), packet.getSlot(), packet.getItem());
    }

    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void emi$bookmarkInventory(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        BookmarkCrafting.observeServerInventory(packet.getContainerId(), packet.getItems());
        dev.emi.emi.runtime.BookmarkMaterialTransfer.observeServerInventory(packet.getContainerId(), packet.getItems(), packet.getCarriedItem());
    }
}
