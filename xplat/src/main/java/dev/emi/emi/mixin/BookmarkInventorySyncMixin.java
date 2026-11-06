package dev.emi.emi.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.emi.emi.runtime.BookmarkCrafting;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;

/** Feed only received server inventory data to the bookmark executor. */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class BookmarkInventorySyncMixin {
    @Inject(method = "onScreenHandlerSlotUpdate", at = @At("TAIL"))
    private void emi$bookmarkSlotUpdate(ScreenHandlerSlotUpdateS2CPacket packet, CallbackInfo ci) {
        BookmarkCrafting.observeServerSlot(packet.getSyncId(), packet.getSlot(), packet.getStack());
        dev.emi.emi.runtime.BookmarkMaterialTransfer.observeServerSlot(packet.getSyncId(), packet.getSlot(), packet.getStack());
    }

    @Inject(method = "onInventory", at = @At("TAIL"))
    private void emi$bookmarkInventory(InventoryS2CPacket packet, CallbackInfo ci) {
        BookmarkCrafting.observeServerInventory(packet.getSyncId(), packet.getContents());
        dev.emi.emi.runtime.BookmarkMaterialTransfer.observeServerInventory(packet.getSyncId(), packet.getContents(), packet.getCursorStack());
    }
}
