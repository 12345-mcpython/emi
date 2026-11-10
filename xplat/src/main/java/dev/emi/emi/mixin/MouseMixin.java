package dev.emi.emi.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.emi.emi.runtime.EmiLog;
import dev.emi.emi.screen.EmiScreenManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonInfo;

@Mixin(MouseHandler.class)
public class MouseMixin {
	@Shadow @Final
	private Minecraft minecraft;
	@Shadow
	private double xpos, ypos;
	@Shadow
	private MouseButtonInfo activeButton;
	@Shadow private double accumulatedDX;
	@Shadow private double accumulatedDY;

	// 1.21.10 wraps button presses in a MouseButtonInfo and dispatches them from MouseHandler#onButton.
	@Inject(at = @At("HEAD"), method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V", cancellable = true)
	private void onButton(long window, MouseButtonInfo buttonInfo, int action, CallbackInfo info) {
		try {
			Screen screen = minecraft.screen;
			if (screen instanceof AbstractContainerScreen<?> hs) {
				double mx = this.xpos * minecraft.getWindow().getGuiScaledWidth() / minecraft.getWindow().getScreenWidth();
				double my = this.ypos * minecraft.getWindow().getGuiScaledHeight() / minecraft.getWindow().getScreenHeight();
				if (action == 1) {
					if (EmiScreenManager.mouseClicked(mx, my, buttonInfo.button())) {
						info.cancel();
					}
				} else if (action == 0) {
					if (EmiScreenManager.mouseReleased(mx, my, buttonInfo.button())) {
						info.cancel();
					}
				}
			}
		} catch (Exception e) {
			EmiLog.error("Error while handling mouse button", e);
		}
	}

	@Inject(at = @At("HEAD"), method = "handleAccumulatedMovement", cancellable = true)
	private void onMouseDragged(CallbackInfo info) {
		try {
			Screen screen = minecraft.screen;
			if (screen instanceof AbstractContainerScreen<?> hs) {
				double mx = this.xpos * minecraft.getWindow().getGuiScaledWidth() / minecraft.getWindow().getScreenWidth();
				double my = this.ypos * minecraft.getWindow().getGuiScaledHeight() / minecraft.getWindow().getScreenHeight();
				double dx = this.accumulatedDX * minecraft.getWindow().getGuiScaledWidth() / minecraft.getWindow().getScreenWidth();
				double dy = this.accumulatedDY * minecraft.getWindow().getGuiScaledHeight() / minecraft.getWindow().getScreenHeight();
				int button = this.activeButton == null ? -1 : this.activeButton.button();
				EmiScreenManager.mouseDragged(mx, my, button, dx, dy);
			}
		} catch (Exception e) {
			EmiLog.error("Error while handling mouse drag", e);
		}
	}

	@Inject(at = @At("HEAD"), method = "onScroll(JDD)V", cancellable = true)
	private void onMouseScrolled(long window, double horizontal, double vertical, CallbackInfo info) {
		try {
			Screen screen = minecraft.screen;
			if (screen instanceof AbstractContainerScreen<?> hs) {
				double amount = (minecraft.options.discreteMouseScroll().get() ? Math.signum(vertical) : vertical) * minecraft.options.mouseWheelSensitivity().get();
				double mx = xpos * minecraft.getWindow().getGuiScaledWidth() / minecraft.getWindow().getScreenWidth();
				double my = ypos * minecraft.getWindow().getGuiScaledHeight() / minecraft.getWindow().getScreenHeight();
				if (EmiScreenManager.mouseScrolled(mx, my, amount)) {
					info.cancel();
				}
			}
		} catch (Exception e) {
			EmiLog.error("Error while handling mouse scroll", e);
		}
	}
}
