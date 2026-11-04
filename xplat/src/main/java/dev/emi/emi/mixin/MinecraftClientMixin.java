package dev.emi.emi.mixin;

import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.emi.emi.platform.EmiClient;
import dev.emi.emi.runtime.EmiLog;
import dev.emi.emi.runtime.EmiReloadManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
	@Shadow
	public ClientWorld world;

	@Inject(at = @At("TAIL"), method = "tick")
	private void warmupEmi(CallbackInfo info) {
		EmiReloadManager.tick();
		dev.emi.emi.screen.EmiScreenManager.warmupTick();
	}

	@Inject(at = @At("HEAD"), method = "reloadResources(Z)Ljava/util/concurrent/CompletableFuture;")
	private void beforeResourceReload(boolean force, CallbackInfoReturnable<CompletableFuture<Void>> info) {
		dev.emi.emi.screen.EmiScreenManager.setResourcesReloading(true);
	}

	@Inject(at = @At("RETURN"), method = "reloadResources(Z)Ljava/util/concurrent/CompletableFuture;")
	private void reloadResources(boolean force, CallbackInfoReturnable<CompletableFuture<Void>> info) {
		CompletableFuture<Void> future = info.getReturnValue();
		if (future != null) {
			future.whenComplete((ignored, error) -> MinecraftClient.getInstance().execute(() -> {
				// Tag/recipe synchronization owns first-load scheduling. Resource
				// reloads only queue a rebuild once those resources are ready.
				if (error == null) {
					EmiReloadManager.queueReload();
				} else {
					EmiLog.error("Resource reload failed; EMI initialization remains queued", error);
				}
				dev.emi.emi.screen.EmiScreenManager.setResourcesReloading(false);
			}));
		} else {
			dev.emi.emi.screen.EmiScreenManager.setResourcesReloading(false);
		}
	}

	@Inject(at = @At("HEAD"), method = "disconnect(Lnet/minecraft/client/gui/screen/Screen;)V")
	private void disconnect(CallbackInfo info) {
		EmiLog.info("Disconnecting from server, EMI data cleared");
		EmiReloadManager.clear();
		dev.emi.emi.screen.EmiScreenManager.resetWarmup();
		EmiClient.onServer = false;
	}
}
