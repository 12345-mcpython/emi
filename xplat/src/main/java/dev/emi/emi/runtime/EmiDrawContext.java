package dev.emi.emi.runtime;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix3x2fStack;
import dev.emi.emi.EmiPort;
import dev.emi.emi.api.stack.EmiIngredient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

public class EmiDrawContext {
	private final Minecraft client = Minecraft.getInstance();
	private final GuiGraphics context;
	
	private EmiDrawContext(GuiGraphics context) {
		this.context = context;
	}

	public static EmiDrawContext wrap(GuiGraphics context) {
		return new EmiDrawContext(context);
	}

	public GuiGraphics raw() {
		return context;
	}

	public Matrix3x2fStack matrices() {
		return context.pose();
	}

	public void push() {
		matrices().pushMatrix();
	}

	public void pop() {
		matrices().popMatrix();
	}

	public void translate(float x, float y) {
		matrices().translate(x, y);
	}

	public void drawTexture(Identifier texture, int x, int y, int u, int v, int width, int height) {
		drawTexture(texture, x, y, width, height, u, v, width, height, 256, 256);
	}

	public void drawTexture(Identifier texture, int x, int y, int z, float u, float v, int width, int height) {
		drawTexture(texture, x, y, z, u, v, width, height, 256, 256);
	}

	public void drawTexture(Identifier texture, int x, int y, int z, float u, float v, int width, int height, int textureWidth, int textureHeight) {
		EmiPort.setPositionTexShader();
		context.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, width, height, textureWidth, textureHeight, packedColor());
	}

	public void drawTexture(Identifier texture, int x, int y, int width, int height, float u, float v, int regionWidth, int regionHeight, int textureWidth, int textureHeight) {
		EmiPort.setPositionTexShader();
		context.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, width, height, regionWidth, regionHeight, textureWidth, textureHeight, packedColor());
	}

	public void fill(int x, int y, int width, int height, int color) {
		context.fill(x, y, x + width, y + height, color);
	}

	/** 1.21.6 treats the colour as ARGB; opaque is the historical behaviour. */
	private static int textColor(int color) {
		return (color & 0xFF000000) == 0 ? color | 0xFF000000 : color;
	}

	public void drawText(Component text, int x, int y) {
		drawText(text, x, y, -1);
	}

	public void drawText(Component text, int x, int y, int color) {
		context.drawString(client.font, text, x, y, textColor(color), false);
	}

	public void drawText(FormattedCharSequence text, int x, int y, int color) {
		context.drawString(client.font, text, x, y, textColor(color), false);
	}

	public void drawTextWithShadow(Component text, int x, int y) {
		drawTextWithShadow(text, x, y, -1);
	}

	public void drawTextWithShadow(Component text, int x, int y, int color) {
		context.drawString(client.font, text, x, y, textColor(color), true);
	}

	public void drawTextWithShadow(FormattedCharSequence text, int x, int y, int color) {
		context.drawString(client.font, text, x, y, textColor(color), true);
	}

	public void drawCenteredText(Component text, int x, int y) {
		drawCenteredText(text, x, y, -1);
	}

	public void drawCenteredText(Component text, int x, int y, int color) {
		context.drawString(client.font, text, x - client.font.width(text) / 2, y, textColor(color), false);
	}

	public void drawCenteredTextWithShadow(Component text, int x, int y) {
		drawCenteredTextWithShadow(text, x, y, -1);
	}

	public void drawCenteredTextWithShadow(Component text, int x, int y, int color) {
		context.drawCenteredString(client.font, text.getVisualOrderText(), x, y, textColor(color));
	}

	public void enableDepthTest() {
		// 1.21.5 moved render state into the render pipeline; the GUI pass sets depth itself.
	}

	public void disableDepthTest() {
	}

	public void enableBlend() {
	}

	public void disableBlend() {
	}

	public void resetColor() {
		setColor(1f, 1f, 1f, 1f);
	}

	public void setColor(float r, float g, float b) {
		setColor(r, g, b, 1f);
	}

	// Tint state is global on purpose: widgets often set a colour on one context and draw through
	// another one (e.g. ConfigJumpButton -> SizedButtonWidget), which used to share the shader colour.
	private static float colorR = 1f, colorG = 1f, colorB = 1f, colorA = 1f;

	public void setColor(float r, float g, float b, float a) {
		colorR = r;
		colorG = g;
		colorB = b;
		colorA = a;
	}

	/** 1.21.6 bakes vertex colour into the blit, so the tint has to be passed explicitly. */
	private int packedColor() {
		int a = Math.max(0, Math.min(255, Math.round(colorA * 255f)));
		int r = Math.max(0, Math.min(255, Math.round(colorR * 255f)));
		int g = Math.max(0, Math.min(255, Math.round(colorG * 255f)));
		int b = Math.max(0, Math.min(255, Math.round(colorB * 255f)));
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	public float red() {
		return colorR;
	}

	public float green() {
		return colorG;
	}

	public float blue() {
		return colorB;
	}

	public float alpha() {
		return colorA;
	}

	public void drawStack(EmiIngredient stack, int x, int y) {
		stack.render(raw(), x, y, client.getDeltaTracker().getGameTimeDeltaPartialTick(false));
	}

	public void drawStack(EmiIngredient stack, int x, int y, int flags) {
		drawStack(stack, x, y, client.getDeltaTracker().getGameTimeDeltaPartialTick(false), flags);
	}

	public void drawStack(EmiIngredient stack, int x, int y, float delta, int flags) {
		stack.render(raw(), x, y, delta, flags);
	}
}
