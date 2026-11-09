package dev.emi.emi.runtime;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2fStack;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;

import dev.emi.emi.EmiPort;
import dev.emi.emi.config.EmiConfig;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * Recipe screenshot export.
 * <p>
 * 1.21.6 turned GUI drawing into a prepare/render split: {@code GuiGraphics} only submits elements to a
 * {@code GuiRenderState}, and a {@code GuiRenderer} renders them afterwards. The exporter therefore builds a
 * private render state, runs the renderer against it and renders with the capture target installed as the
 * main render target, which is what {@code GuiRenderer} asks {@code Minecraft} for. The renderer always
 * projects against the window (logical size = window pixels / GUI scale), so images larger than the window
 * are captured tile by tile and stitched together.
 */
public class EmiScreenshotRecorder {
	private static final String SCREENSHOTS_DIRNAME = "screenshots";
	private static final int BUFFER_CAPACITY = 786432;
	@Nullable
	private static RenderTarget captureTarget;

	/**
	 * The target a capture is currently drawing into, or {@code null} when nothing is being captured.
	 */
	@Nullable
	public static RenderTarget getCaptureTarget() {
		return captureTarget;
	}

	/**
	 * Saves a screenshot to the game's `screenshots` directory, doing the appropriate setup so that anything
	 * rendered through the supplied graphics will be captured and saved.
	 * <p>
	 * <b>Note:</b> the path can have <code>/</code> characters, indicating subdirectories. The path should
	 * <b>not</b> contain the <code>.png</code> extension, as that will be added after checking for duplicates.
	 * <p>
	 * <b>Note 2:</b> The width and height parameters are reflected in the viewport when rendering. But the
	 * EMI-config <code>ui.recipe-screenshot-scale</code> value causes the resulting image to be scaled.
	 *
	 * @param path     the path to save the screenshot to, without extension.
	 * @param width    the width of the screenshot, not counting EMI-config scale.
	 * @param height   the height of the screenshot, not counting EMI-config scale.
	 * @param renderer draws the screenshot content; it runs once per tile.
	 */
	public static void saveScreenshot(String path, int width, int height, Consumer<GuiGraphics> renderer) {
		if (RenderSystem.isOnRenderThread()) {
			saveScreenshotInner(path, width, height, renderer);
		} else {
			Minecraft.getInstance().execute(() -> saveScreenshotInner(path, width, height, renderer));
		}
	}

	private static void saveScreenshotInner(String path, int width, int height, Consumer<GuiGraphics> renderer) {
		Minecraft client = Minecraft.getInstance();
		int guiScale = Math.max(1, EmiPort.getGuiScale(client));
		int scale = EmiConfig.recipeScreenshotScale < 1 ? guiScale : EmiConfig.recipeScreenshotScale;
		int imageWidth = width * scale;
		int imageHeight = height * scale;
		if (width <= 0 || height <= 0 || imageWidth <= 0 || imageHeight <= 0) {
			return;
		}
		int windowWidth = Math.max(1, client.getWindow().getWidth());
		int windowHeight = Math.max(1, client.getWindow().getHeight());

		// Logical size of one tile in GUI units; the renderer projects the window onto the target.
		float poseScale = (float) scale / guiScale;
		float tileGuiWidth = windowWidth / (float) scale;
		float tileGuiHeight = windowHeight / (float) scale;
		int tilesX = Math.max(1, (int) Math.ceil(width / tileGuiWidth));
		int tilesY = Math.max(1, (int) Math.ceil(height / tileGuiHeight));
		int tileCount = tilesX * tilesY;

		NativeImage image = new NativeImage(imageWidth, imageHeight, false);
		RenderTarget target = new TextureTarget("EMI screenshot", windowWidth, windowHeight, true);
		GuiRenderState renderState = new GuiRenderState();
		MultiBufferSource.BufferSource bufferSource = MultiBufferSource.immediate(new ByteBufferBuilder(BUFFER_CAPACITY));
		GuiRenderer guiRenderer = new GuiRenderer(renderState, bufferSource, List.of());
		GuiGraphics graphics = new GuiGraphics(client, renderState);
		GpuBuffer fogBuffer = RenderSystem.getDevice()
			.createBuffer(() -> "EMI screenshot fog", GpuBuffer.USAGE_UNIFORM, FogRenderer.FOG_UBO_SIZE);
		GpuBufferSlice fog = fogBuffer.slice(0, FogRenderer.FOG_UBO_SIZE);
		ScissorState previousScissor = RenderSystem.getScissorStateForRenderTypeDraws();
		boolean scissorWasEnabled = previousScissor.enabled();
		int previousScissorX = previousScissor.x();
		int previousScissorY = previousScissor.y();
		int previousScissorWidth = previousScissor.width();
		int previousScissorHeight = previousScissor.height();
		int[] remaining = {tileCount};
		boolean success = false;
		try {
			RenderSystem.getDevice().createCommandEncoder()
				.clearColorAndDepthTextures(target.getColorTexture(), 0, target.getDepthTexture(), 1.0);
			captureTarget = target;
			RenderSystem.backupProjectionMatrix();
			for (int ty = 0; ty < tilesY; ty++) {
				for (int tx = 0; tx < tilesX; tx++) {
					int offsetX = tx * windowWidth;
					int offsetY = ty * windowHeight;
					int tileWidth = Math.min(windowWidth, imageWidth - offsetX);
					int tileHeight = Math.min(windowHeight, imageHeight - offsetY);
					if (tileWidth <= 0 || tileHeight <= 0) {
						continue;
					}
					Matrix3x2fStack pose = graphics.pose();
					pose.pushMatrix();
					pose.scale(poseScale, poseScale);
					pose.translate(-tx * tileGuiWidth, -ty * tileGuiHeight);
					renderer.accept(graphics);
					pose.popMatrix();
					guiRenderer.render(fog);
					GpuBuffer buffer = RenderSystem.getDevice()
						.createBuffer(() -> "EMI screenshot buffer", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
							windowWidth * windowHeight * 4);
					CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
					encoder.copyTextureToBuffer(target.getColorTexture(), buffer, 0, () -> {
						try (GpuBuffer.MappedView view = encoder.mapBuffer(buffer, true, false)) {
							for (int row = 0; row < tileHeight; row++) {
								int sourceRow = tileHeight - row - 1;
								for (int column = 0; column < tileWidth; column++) {
									int color = view.data().getInt((sourceRow * tileWidth + column) * 4);
									image.setPixelABGR(offsetX + column, offsetY + row, color);
								}
							}
						} finally {
							buffer.close();
						}
						if (--remaining[0] == 0) {
							saveScreenshotInner(client, path, image,
								message -> client.execute(() -> client.gui.getChat().addMessage(message)));
						}
					}, 0, 0, windowHeight - tileHeight, tileWidth, tileHeight);
				}
			}
			success = true;
		} catch (Throwable e) {
			EmiLog.error("Failed to capture screenshot", e);
			try {
				client.execute(() -> {
					try {
						client.gui.getChat().addMessage(EmiPort.translatable("screenshot.failure", e.getMessage()));
					} catch (Throwable feedbackError) {
						EmiLog.error("Failed to report screenshot error", feedbackError);
					}
				});
			} catch (Throwable feedbackError) {
				EmiLog.error("Failed to schedule screenshot error", feedbackError);
			}
		} finally {
			captureTarget = null;
			RenderSystem.restoreProjectionMatrix();
			ScissorState scissor = RenderSystem.getScissorStateForRenderTypeDraws();
			if (scissorWasEnabled) {
				scissor.enable(previousScissorX, previousScissorY, previousScissorWidth, previousScissorHeight);
			} else {
				scissor.disable();
			}
			target.destroyBuffers();
			guiRenderer.close();
			bufferSource.endBatch();
			fogBuffer.close();
			if (!success) {
				image.close();
			}
		}
	}

	private static void saveScreenshotInner(Minecraft client, String suggestedPath, NativeImage nativeImage, Consumer<Component> messageReceiver) {

		File screenshots = new File(client.gameDirectory, SCREENSHOTS_DIRNAME);
		screenshots.mkdir();

		String filename = getScreenshotFilename(screenshots, suggestedPath);
		File file = new File(screenshots, filename);

		// Make sure the parent file exists. Note: `/`s in suggestedPath are valid, as they indicate subdirectories. Java even translates this
		// correctly on Windows.
		File parent = file.getParentFile();
		parent.mkdirs();

		Util.ioPool().execute(() -> {
			try {
				nativeImage.writeToFile(file);

				Component text = EmiPort.literal(filename,
					Style.EMPTY.withUnderlined(true).withClickEvent(new ClickEvent.OpenFile(file.getAbsolutePath())));
				messageReceiver.accept(EmiPort.translatable("screenshot.success", text));
			} catch (Throwable e) {
				EmiLog.error("Failed to write screenshot", e);
				messageReceiver.accept(EmiPort.translatable("screenshot.failure", e.getMessage()));
			} finally {
				nativeImage.close();
			}
		});
	}

	private static String getScreenshotFilename(File directory, String path) {
		int i = 1;
		while ((new File(directory, path + (i == 1 ? "" : "_" + i) + ".png")).exists()) {
			++i;
		}
		return path + (i == 1 ? "" : "_" + i) + ".png";
	}
}
