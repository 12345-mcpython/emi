package dev.emi.emi.runtime;

/**
 * Recipe screenshot export.
 * <p>
 * 1.21.5 replaced the framebuffer path with {@code GpuTexture} plus command encoders: {@code RenderTarget}
 * no longer exposes {@code bindWrite}, {@code RenderSystem} lost {@code recordRenderCall} and the projection
 * setters, and reading pixels back needs {@code GpuDevice#createBufferFromTexture}. Until that is reworked the
 * exporter reports that it is unavailable instead of producing a blank image.
 */
public class EmiScreenshotRecorder {
	public static void saveScreenshot(String path, int width, int height, Runnable renderer) {
		EmiLog.warn("Recipe screenshot export is unavailable on 1.21.5; " + path
			+ " (" + width + "x" + height + ") was not captured");
		EmiLog.warn("1.21.5 needs the GpuTexture and command encoder based capture path, see PORTING-26.1.2.md");
	}

}
