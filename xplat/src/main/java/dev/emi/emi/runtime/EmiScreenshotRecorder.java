package dev.emi.emi.runtime;

import java.io.File;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.BufferType;
import com.mojang.blaze3d.buffers.BufferUsage;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuTexture;

import dev.emi.emi.EmiPort;
import dev.emi.emi.config.EmiConfig;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * Recipe screenshot export.
 * <p>
 * 1.21.5 has no global framebuffer binding anymore: every {@code RenderType} builds its own render pass from
 * the output state's render target, and the GUI render types resolve that target through
 * {@code Minecraft#getMainRenderTarget}. A capture therefore installs a {@code TextureTarget} of its own and
 * {@code MinecraftClientMixin} answers that lookup with it while the renderer runs; the projection and model
 * view are set up the same way {@code GameRenderer} prepares the GUI. Pixels are read back through
 * {@code CommandEncoder#copyTextureToBuffer} instead of {@code Screenshot#takeScreenshot}, because the latter
 * forces the alpha channel to opaque and recipe images keep a transparent background.
 */
public class EmiScreenshotRecorder {
	private static final String SCREENSHOTS_DIRNAME = "screenshots";
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
	 * Saves a screenshot to the game's `screenshots` directory, doing the appropriate setup so that anything rendered in renderer will be captured
	 * and saved.
	 * <p>
	 * <b>Note:</b> the path can have <code>/</code> characters, indicating subdirectories. Java handles these correctly on Windows. The path should
	 * <b>not</b> contain the <code>.png</code> extension, as that will be added after checking for duplicates. If a file with this path already
	 * exists, then path will be suffixed with a <code>_#</code>, before adding the <code>.png</code> extension, where <code>#</code> represents an
	 * increasing number to avoid conflicts.
	 * <p>
	 * <b>Note 2:</b> The width and height parameters are reflected in the viewport when rendering. But the EMI-config
	 * <code>ui.recipe-screenshot-scale</code> value causes the resulting image to be scaled.
	 *
	 * @param path     the path to save the screenshot to, without extension.
	 * @param width    the width of the screenshot, not counting EMI-config scale.
	 * @param height   the height of the screenshot, not counting EMI-config scale.
	 * @param renderer a function to render the things being screenshotted.
	 */
	public static void saveScreenshot(String path, int width, int height, Runnable renderer) {
		if (RenderSystem.isOnRenderThread()) {
			saveScreenshotInner(path, width, height, renderer);
		} else {
			Minecraft.getInstance().execute(() -> saveScreenshotInner(path, width, height, renderer));
		}
	}

	private static void saveScreenshotInner(String path, int width, int height, Runnable renderer) {
		Minecraft client = Minecraft.getInstance();
		int scale = EmiConfig.recipeScreenshotScale < 1 ? EmiPort.getGuiScale(client) : EmiConfig.recipeScreenshotScale;
		scale = Math.max(1, scale);
		RenderTarget target = new TextureTarget("EMI recipe screenshot", width * scale, height * scale, true);
		ScissorState previousScissor = new ScissorState();
		previousScissor.copyFrom(RenderSystem.SCISSOR_STATE);
		Matrix4fStack modelView = RenderSystem.getModelViewStack();
		boolean restoredProjection = false;
		boolean restoredModelView = false;
		try {
			RenderSystem.getDevice().createCommandEncoder()
				.clearColorAndDepthTextures(target.getColorTexture(), 0x00000000, target.getDepthTexture(), 1.0);
			RenderSystem.disableScissor();
			captureTarget = target;
			RenderSystem.backupProjectionMatrix();
			RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0f, width, height, 0f, 1000f, 21000f), ProjectionType.ORTHOGRAPHIC);
			restoredProjection = true;
			modelView.pushMatrix();
			modelView.translation(0f, 0f, -11000f);
			restoredModelView = true;
			Lighting.setupFor3DItems();
			renderer.run();
			// Renderers may leave their batches queued, and no render pass may be open while copying the texture out.
			client.renderBuffers().bufferSource().endBatch();
			readPixels(target, image -> saveScreenshotInner(client, path, image,
				message -> client.execute(() -> client.gui.getChat().addMessage(message))));
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
			if (restoredModelView) {
				modelView.popMatrix();
			}
			if (restoredProjection) {
				RenderSystem.restoreProjectionMatrix();
			}
			RenderSystem.SCISSOR_STATE.copyFrom(previousScissor);
			// The colour texture is only read by the copy issued above, so it can be released right away.
			target.destroyBuffers();
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

	/**
	 * Copies the colour texture of an offscreen target into a {@code NativeImage}, preserving the alpha channel.
	 */
	private static void readPixels(RenderTarget target, Consumer<NativeImage> consumer) {
		GpuTexture texture = target.getColorTexture();
		if (texture == null) {
			throw new IllegalStateException("Screenshot target has no colour texture");
		}
		int width = target.width;
		int height = target.height;
		int pixelSize = texture.getFormat().pixelSize();
		GpuBuffer buffer = RenderSystem.getDevice()
			.createBuffer(() -> "EMI screenshot buffer", BufferType.PIXEL_PACK, BufferUsage.STATIC_READ, width * height * pixelSize);
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		encoder.copyTextureToBuffer(texture, buffer, 0, () -> {
			try (GpuBuffer.ReadView view = encoder.readBuffer(buffer)) {
				NativeImage image = new NativeImage(width, height, false);
				boolean handedOff = false;
				try {
					for (int y = 0; y < height; y++) {
						int row = y * width * pixelSize;
						for (int x = 0; x < width; x++) {
							image.setPixelABGR(x, height - y - 1, view.data().getInt(row + x * pixelSize));
						}
					}
					consumer.accept(image);
					handedOff = true;
				} finally {
					if (!handedOff) {
						image.close();
					}
				}
			} finally {
				buffer.close();
			}
		}, 0);
	}

	private static String getScreenshotFilename(File directory, String path) {
		int i = 1;
		while ((new File(directory, path + (i == 1 ? "" : "_" + i) + ".png")).exists()) {
			++i;
		}
		return path + (i == 1 ? "" : "_" + i) + ".png";
	}
}
