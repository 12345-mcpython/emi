package dev.emi.emi.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.emi.emi.runtime.EmiLog;
import dev.emi.emi.screen.EmiScreenManager;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

@Mixin(KeyboardHandler.class)
public class KeyboardMixin {
	@Shadow @Final
	private Minecraft minecraft;

	// 1.21.10 wraps key presses in a KeyEvent, and charTyped in a CharacterEvent.
	@Inject(at = @At("HEAD"), method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", cancellable = true)
	public void onKey(long window, int action, net.minecraft.client.input.KeyEvent event, CallbackInfo info) {
		try {
			Screen screen = minecraft.screen;
			if (screen instanceof AbstractContainerScreen<?> hs) {
				if (action == 1 || action == 2) {
					if (EmiScreenManager.keyPressed(event.key(), event.scancode(), event.modifiers())) {
						info.cancel();
					}
				}
			}
		} catch (Exception e) {
			EmiLog.error("Error while handling key press", e);
		}
	}

	@Inject(at = @At("HEAD"), method = "charTyped(JLnet/minecraft/client/input/CharacterEvent;)V", cancellable = true)
	public void onChar(long window, net.minecraft.client.input.CharacterEvent event, CallbackInfo info) {
		try {
			{
				Screen screen = minecraft.screen;
				if (screen instanceof AbstractContainerScreen<?> hs && this.minecraft.getOverlay() == null) {
					if (EmiScreenManager.search.charTyped(event)) {
						info.cancel();
					}
				}
			}
		} catch (Exception e) {
			EmiLog.error("Error while handling char", e);
		}
	}

}
