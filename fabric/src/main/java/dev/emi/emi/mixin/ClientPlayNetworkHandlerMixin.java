package dev.emi.emi.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.emi.emi.runtime.EmiLog;
import dev.emi.emi.runtime.EmiReloadManager;
import net.minecraft.client.multiplayer.ClientPacketListener;

/**
 * The 1.21.2+ client no longer receives a recipe table, so there is nothing to reload when the
 * server sends recipes; only the tag sync is still observed here.
 */
@Mixin(ClientPacketListener.class)
public class ClientPlayNetworkHandlerMixin {

	@Inject(at = @At("RETURN"), method = "handleUpdateTags")
	private void refreshTagBasedData(CallbackInfo info) {
		EmiReloadManager.reloadTags();
	}

	@Inject(at = @At("RETURN"), method = "handleLogin")
	private void onGameJoin(CallbackInfo info) {
		EmiLog.info("Joining server, EMI waiting for tag data from server...");
		// Recipes are fetched lazily while reloading: from the integrated server in singleplayer and
		// not at all on multiplayer, where the client has no recipe table on 1.21.2+.
		EmiReloadManager.reloadRecipes();
	}
}
