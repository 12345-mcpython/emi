package dev.emi.emi.test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.NativeImage;

import dev.emi.emi.EmiPort;
import dev.emi.emi.runtime.EmiDrawContext;
import dev.emi.emi.runtime.EmiScreenshotRecorder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Verification fixture for the 1.21.5 offscreen recipe screenshot exporter.
 * <p>
 * Waits for the title screen, asks EmiScreenshotRecorder for a 64x32 capture (red rectangle, a stone
 * item), then validates the written PNG: the transparent background must survive, the rectangle must be
 * opaque red, and the item and text areas must contain rendered pixels.
 */
public class ScreenshotSmoke implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("emi-screenshot-smoke");
	private static final int CAPTURE_WIDTH = 64;
	private static final int CAPTURE_HEIGHT = 32;

	private int state = 0;
	private int ticks;
	private int scale;
	private String failure;

	@Override
	public void onInitializeClient() {
		if (!Boolean.getBoolean("emi.screenshotSmoke")) {
			return;
		}
		LOGGER.info("SCREENSHOT SMOKE armed");
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	private void tick(Minecraft client) {
		try {
			switch (state) {
				case 0 -> {
					if (client.screen instanceof TitleScreen && client.getOverlay() == null) {
						state = 1;
						ticks = 0;
					}
				}
				case 1 -> {
					if (++ticks >= 10) {
						state = 2;
					}
				}
				case 2 -> capture(client);
				case 3 -> {
					if (++ticks > 300) {
						fail(client, "Timed out waiting for the export file");
					} else {
						check(client);
					}
				}
				default -> {
				}
			}
		} catch (Throwable e) {
			fail(client, "Exception: " + e);
		}
	}

	private void capture(Minecraft client) throws Exception {
		state = 3;
		ticks = 0;
		scale = Math.max(1, EmiPort.getGuiScale(client));
		Path directory = client.gameDirectory.toPath().resolve("screenshots/emi/smoke");
		if (Files.isDirectory(directory)) {
			try (Stream<Path> files = Files.walk(directory)) {
				files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
			}
		}
		LOGGER.info("SCREENSHOT SMOKE capturing {}x{} at scale {}", CAPTURE_WIDTH, CAPTURE_HEIGHT, scale);
		GuiGraphics graphics = new GuiGraphics(client, client.renderBuffers().bufferSource());
		EmiDrawContext context = EmiDrawContext.wrap(graphics);
		EmiScreenshotRecorder.saveScreenshot("emi/smoke/capture", CAPTURE_WIDTH, CAPTURE_HEIGHT, () -> {
			context.fill(4, 4, 32, 16, 0xFFFF0000);
			graphics.renderFakeItem(new ItemStack(Items.STONE), 44, 4);
			graphics.drawString(client.font, "EMI", 4, 23, 0xFFFFFFFF, false);
			graphics.flush();
		});
	}

	private void check(Minecraft client) {
		Path file = client.gameDirectory.toPath().resolve("screenshots/emi/smoke/capture.png");
		if (!Files.isRegularFile(file) || file.toFile().length() == 0) {
			return;
		}
		String problem = null;
		try (InputStream in = Files.newInputStream(file); NativeImage image = NativeImage.read(in)) {
			int expectedWidth = CAPTURE_WIDTH * scale;
			int expectedHeight = CAPTURE_HEIGHT * scale;
			StringBuilder details = new StringBuilder("size=" + image.getWidth() + "x" + image.getHeight()
				+ " expected=" + expectedWidth + "x" + expectedHeight);
			if (image.getWidth() != expectedWidth || image.getHeight() != expectedHeight) {
				problem = "Unexpected image size";
			}
			int corner = image.getPixel(0, 0);
			details.append(" corner=0x").append(Integer.toHexString(corner));
			if (problem == null && (corner >>> 24) != 0) {
				problem = "Background is not transparent";
			}
			int rect = image.getPixel((4 + 16) * scale, (4 + 8) * scale);
			details.append(" rect=0x").append(Integer.toHexString(rect));
			if (problem == null && rect != 0xFFFF0000) {
				problem = "Rectangle did not render as opaque red";
			}
			boolean itemPixels = false;
			for (int y = 4 * scale; y < 20 * scale && !itemPixels; y++) {
				for (int x = 44 * scale; x < 60 * scale; x++) {
					if ((image.getPixel(x, y) >>> 24) != 0) {
						itemPixels = true;
						break;
					}
				}
			}
			details.append(" itemPixels=").append(itemPixels);
			if (problem == null && !itemPixels) {
				problem = "Stone item did not render into the capture";
			}
			boolean textPixels = false;
			for (int y = 23 * scale; y < CAPTURE_HEIGHT * scale && !textPixels; y++) {
				for (int x = 4 * scale; x < 32 * scale; x++) {
					int color = image.getPixel(x, y);
					int alpha = color >>> 24;
					if (alpha == 0xFF && ((color >> 16) & 0xFF) > 180 && ((color >> 8) & 0xFF) > 180 && (color & 0xFF) > 180) {
						textPixels = true;
						break;
					}
				}
			}
			details.append(" textPixels=").append(textPixels);
			if (problem == null && !textPixels) {
				problem = "Text did not render into the capture";
			}
			LOGGER.info("SCREENSHOT SMOKE {}", details);
		} catch (Exception e) {
			// The io thread may still be writing; retry on a later tick.
			return;
		}
		if (problem == null) {
			finish(client, true, "PASS");
		} else {
			finish(client, false, problem);
		}
	}

	private void fail(Minecraft client, String reason) {
		finish(client, false, reason);
	}

	private void finish(Minecraft client, boolean pass, String message) {
		if (state == 4) {
			return;
		}
		state = 4;
		String line = (pass ? "PASS " : "FAIL ") + message;
		LOGGER.info("SCREENSHOT SMOKE {}", line);
		try {
			Files.writeString(client.gameDirectory.toPath().resolve("screenshot-smoke-result.txt"), line);
		} catch (Exception e) {
			LOGGER.error("SCREENSHOT SMOKE could not write the result file", e);
		}
		client.stop();
	}
}
