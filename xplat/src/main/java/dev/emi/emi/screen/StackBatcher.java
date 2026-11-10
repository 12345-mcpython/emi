package dev.emi.emi.screen;

import java.util.List;

import com.google.common.collect.Lists;

import dev.emi.emi.api.stack.EmiIngredient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * Batched stack rendering is disabled from 1.21.4 onwards.
 * <p>
 * The gui now renders through render pipelines that own their own buffer state, so the
 * per-render-type vertex batching this class used is no longer possible. From 1.21.6 the
 * vanilla {@code GuiRenderer} batches item rendering itself: every {@code renderFakeItem}
 * submits a render state which is baked into the gui item atlas during the prepare pass and
 * drawn as a single textured quad afterwards, so drawing stacks per slot is already batched.
 * This class stays as a thin shim so the screen and API code keeps compiling.
 */
public class StackBatcher {

	public interface Batchable {

		default boolean isSideLit() {
			return false;
		}

		default boolean isUnbatchable() {
			return true;
		}

		default void setUnbatchable() {
		}

		default void renderForBatch(MultiBufferSource vcp, GuiGraphics draw, int x, int y, int z, float delta) {
		}
	}

	public static final List<RenderType> EXTRA_RENDER_LAYERS = Lists.newArrayList();

	public static boolean isEnabled() {
		return false;
	}

	public StackBatcher() {
	}

	public boolean isPopulated() {
		return true;
	}

	public void repopulate() {
	}

	public void begin(int x, int y, int z) {
	}

	public void render(EmiIngredient stack, GuiGraphics draw, int x, int y, float delta) {
		stack.render(draw, x, y, delta, -1 ^ EmiIngredient.RENDER_AMOUNT);
	}

	public void render(EmiIngredient stack, GuiGraphics draw, int x, int y, float delta, int flags) {
		stack.render(draw, x, y, delta, flags);
	}

	public void render(Batchable batchable, GuiGraphics draw, int x, int y, float delta) {
	}

	public void draw() {
	}

	public static class ClaimedCollection {

		public boolean preallocate(int target) {
			return true;
		}

		public StackBatcher claim() {
			return new StackBatcher();
		}

		public void unclaimAll() {
		}
	}
}
