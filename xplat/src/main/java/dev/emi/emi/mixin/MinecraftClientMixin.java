package dev.emi.emi.mixin;

import java.util.concurrent.CompletableFuture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.emi.emi.platform.EmiClient;
import dev.emi.emi.runtime.EmiLog;
import dev.emi.emi.runtime.EmiReloadManager;
import dev.emi.emi.platform.EmiClientState;

@Mixin(Minecraft.class)
public class MinecraftClientMixin {
	@Shadow
	public ClientLevel level;

	/**
	 * GUI render types resolve their output target through this getter, so an offscreen recipe screenshot
	 * answers it with its own target instead of binding a framebuffer, which 1.21.5 removed.
	 */
	@Inject(at = @At("HEAD"), method = "getMainRenderTarget", cancellable = true)
	private void overrideMainRenderTarget(CallbackInfoReturnable<RenderTarget> info) {
		RenderTarget target = dev.emi.emi.runtime.EmiScreenshotRecorder.getCaptureTarget();
		if (target != null) {
			info.setReturnValue(target);
		}
	}

	@Inject(at = @At("TAIL"), method = "tick")
	private void warmupEmi(CallbackInfo info) {
		EmiReloadManager.tick();
		dev.emi.emi.screen.EmiScreenManager.warmupTick();
	}

	@Inject(at = @At("HEAD"), method = "reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;")
	private void beforeResourceReload(CallbackInfoReturnable<CompletableFuture<Void>> info) {
		dev.emi.emi.screen.EmiScreenManager.setResourcesReloading(true);
	}

	@Inject(at = @At("RETURN"), method = "reloadResourcePacks()Ljava/util/concurrent/CompletableFuture;")
	private void reloadResources(CallbackInfoReturnable<CompletableFuture<Void>> info) {
		CompletableFuture<Void> future = info.getReturnValue();
		if (future != null) {
			future.whenComplete((ignored, error) -> Minecraft.getInstance().execute(() -> {
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

	@Inject(at = @At("HEAD"), method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V")
	private void disconnect(CallbackInfo info) {
		EmiLog.info("Disconnecting from server, EMI data cleared");
		EmiReloadManager.clear();
		dev.emi.emi.screen.EmiScreenManager.resetWarmup();
		EmiClientState.onServer = false;
	}
}
