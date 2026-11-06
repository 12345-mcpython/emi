package dev.emi.emi.screen;

import dev.emi.emi.EmiPort;
import dev.emi.emi.config.EmiConfig;
import dev.emi.emi.runtime.EmiDrawContext;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

public class DisabledToast implements Toast {
	private static final ResourceLocation TEXTURE = EmiPort.id("toast/advancement");

	@Override
	public void render(GuiGraphics raw, Font font, long time) {
		EmiDrawContext context = EmiDrawContext.wrap(raw);
		context.resetColor();
		raw.blitSprite(RenderType::guiTextured, TEXTURE, 0, 0, this.width(), this.height());
		context.drawCenteredText(EmiPort.translatable("emi.disabled"), width() / 2, 7);
		context.drawCenteredText(EmiConfig.toggleVisibility.getBindText(), width() / 2, 18);
	}

	@Override
	public void update(ToastManager manager, long time) {
	}

	@Override
	public Visibility getWantedVisibility() {
		return EmiConfig.enabled ? Visibility.HIDE : Visibility.SHOW;
	}
}
