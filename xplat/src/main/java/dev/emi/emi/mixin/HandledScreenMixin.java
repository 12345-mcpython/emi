package dev.emi.emi.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.mojang.blaze3d.platform.Window;
import dev.emi.emi.EmiPort;
import dev.emi.emi.platform.EmiAgnos;
import dev.emi.emi.runtime.EmiDrawContext;
import dev.emi.emi.screen.EmiScreenManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

@Mixin(AbstractContainerScreen.class)
public abstract class HandledScreenMixin extends Screen {
	@Shadow
	protected int imageWidth, imageHeight, leftPos, topPos;

	private HandledScreenMixin() { super(null); }

	@Override
	public void renderBackground(GuiGraphics raw) {
		super.renderBackground(raw);
		EmiDrawContext context = EmiDrawContext.wrap(raw);
		Window window = minecraft.getWindow();
		int mouseX = (int) (minecraft.mouseHandler.xpos() * window.getGuiScaledWidth() / window.getScreenWidth());
		int mouseY = (int) (minecraft.mouseHandler.ypos() * window.getGuiScaledHeight() / window.getScreenHeight());
		EmiScreenManager.drawBackground(context, mouseX, mouseY, minecraft.getFrameTime());
	}

	@Inject(at = @At(value = "INVOKE",
			target = "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen.renderLabels(Lnet/minecraft/client/gui/GuiGraphics;II)V",
			shift = Shift.AFTER),
		method = "render")
	private void renderForeground(GuiGraphics raw, int mouseX, int mouseY, float delta, CallbackInfo info) {
		if (EmiAgnos.isForge()) {
			return;
		}
		EmiDrawContext context = EmiDrawContext.wrap(raw);
		context.push();
		context.translate(-leftPos, -topPos);
		EmiPort.setPositionTexShader();
		EmiScreenManager.render(context, mouseX, mouseY, delta);
		EmiScreenManager.drawForeground(context, mouseX, mouseY, delta);
		context.pop();
	}
}