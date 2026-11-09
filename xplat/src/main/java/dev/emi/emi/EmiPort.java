package dev.emi.emi;

import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Button.OnPress;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.TallFlowerBlock;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.material.Fluid;
import org.joml.Matrix4f;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import dev.emi.emi.api.stack.Comparison;

/**
 * Multiversion quarantine, to avoid excessive git pain
 */
public final class EmiPort {
	private static final net.minecraft.util.RandomSource RANDOM = net.minecraft.util.RandomSource.create();

	public static MutableComponent literal(String s) {
		return Component.literal(s);
	}

	public static MutableComponent literal(String s, ChatFormatting formatting) {
		return Component.literal(s).withStyle(formatting);
	}

	public static MutableComponent literal(String s, ChatFormatting... formatting) {
		return Component.literal(s).withStyle(formatting);
	}

	public static MutableComponent literal(String s, Style style) {
		return Component.literal(s).setStyle(style);
	}
	
	public static MutableComponent translatable(String s) {
		return Component.translatable(s);
	}
	
	public static MutableComponent translatable(String s, ChatFormatting formatting) {
		return Component.translatable(s).withStyle(formatting);
	}
	
	public static MutableComponent translatable(String s, Object... objects) {
		return Component.translatable(s, objects);
	}

	public static MutableComponent append(MutableComponent text, Component appended) {
		return text.append(appended);
	}

	public static FormattedCharSequence ordered(Component text) {
		return text.getVisualOrderText();
	}

	public static Collection<ResourceLocation> findResources(ResourceManager manager, String prefix, Predicate<String> pred) {
		return manager.listResources(prefix, i -> pred.test(i.toString())).keySet();
	}

	public static InputStream getInputStream(Resource resource) {
		try {
			return resource.open();
		} catch (Exception e) {
			return null;
		}
	}

	public static BannerPatternLayers addRandomBanner(BannerPatternLayers patterns, Random random) {
		var lookup = Minecraft.getInstance().level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN);
		var patternsList = lookup.listElements().toList();
		return new BannerPatternLayers.Builder().addAll(patterns).add(patternsList.get(random.nextInt(patternsList.size())),
			DyeColor.values()[random.nextInt(DyeColor.values().length)]).build();
	}

	public static boolean canTallFlowerDuplicate(TallFlowerBlock tallFlowerBlock) {
		try {
			return tallFlowerBlock.isValidBonemealTarget(null, null, null) && tallFlowerBlock.isBonemealSuccess(null, null, null, null);
		} catch(Exception e) {
			return false;
		}
	}



	public static void draw(BufferBuilder bufferBuilder) {
		// 1.21.5 removed BufferUploader; submitting a raw builder now needs a render pass from the command
		// encoder. The mesh still owns the builder buffers, so it has to be closed or the next reuse warns
		// "Clearing BufferBuilder with unused batches".
		try (MeshData mesh = bufferBuilder.buildOrThrow()) {
			// Drawing the mesh needs a RenderPass; tinted sprites keep their base colour until then.
		}
	}

	public static int getGuiScale(Minecraft client) {
		return (int) client.getWindow().getGuiScale();
	}

	public static void setPositionTexShader() {
		// Gui render types carry their pipeline now.
	}

	public static void setPositionColorTexShader() {
	}

	public static Registry<Item> getItemRegistry() {
		return BuiltInRegistries.ITEM;
	}

	public static Registry<Block> getBlockRegistry() {
		return BuiltInRegistries.BLOCK;
	}

	public static Registry<Fluid> getFluidRegistry() {
		return BuiltInRegistries.FLUID;
	}

	public static Registry<Potion> getPotionRegistry() {
		return BuiltInRegistries.POTION;
	}

	public static List<Holder.Reference<Enchantment>> getEnchantments() {
		Minecraft client = Minecraft.getInstance();
		return client.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).listElements().toList();
	}

	public static ResourceLocation getEnchantmentId(Enchantment enchantment) {
		for (Holder.Reference<Enchantment> holder : getEnchantments()) {
			if (holder.value() == enchantment) {
				return holder.key().location();
			}
		}
		return EmiPort.id("minecraft", "unknown");
	}

	public static Holder<Enchantment> getEnchantmentHolder(Enchantment enchantment) {
		for (Holder.Reference<Enchantment> holder : getEnchantments()) {
			if (holder.value() == enchantment) {
				return holder;
			}
		}
		return null;
	}

	public static Button newButton(int x, int y, int w, int h, Component name, OnPress action) {
		return Button.builder(name, action).pos(x, y).size(w, h).build();
	}

	public static ItemStack getOutput(Recipe<?> recipe) {
		var displays = recipe.display();
		if (displays.isEmpty()) {
			return ItemStack.EMPTY;
		}
		return displays.get(0).result().resolveForFirstStack(displayContext());
	}

	/**
	 * Slot displays such as tag or composite displays need the level registries to resolve; an empty
	 * context silently yields nothing, which used to drop recipes entirely on 1.21.2+.
	 */
	public static net.minecraft.util.context.ContextMap displayContext() {
		net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
		if (client.level != null) {
			return net.minecraft.world.item.crafting.display.SlotDisplayContext.fromLevel(client.level);
		}
		return emptyContext();
	}

	public static net.minecraft.util.context.ContextMap emptyContext() {
		try {
			var ctor = net.minecraft.util.context.ContextMap.class.getDeclaredConstructor(java.util.Map.class);
			ctor.setAccessible(true);
			return ctor.newInstance(java.util.Map.of());
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public static void focus(EditBox widget, boolean focused) {
		// Also ensure a current focus-element in the screen is cleared if it changes
		Minecraft client = Minecraft.getInstance();
		if (client != null && client.screen != null) {
			var currentFocus = client.screen.getFocused();
			if (!focused && currentFocus == widget || focused && currentFocus != widget) {
				client.screen.setFocused(null);
			}
		}
		widget.setFocused(focused);
	}

	public static Stream<Item> getDisabledItems() {
		Minecraft client = Minecraft.getInstance();
		FeatureFlagSet fs = client.level.enabledFeatures();
		return getItemRegistry().stream().filter(i -> !i.isEnabled(fs));
	}

	public static Comparison compareStrict() {
		return Comparison.compareComponents();
	}

	public static ItemStack setPotion(ItemStack stack, Potion potion) {
		stack.update(DataComponents.POTION_CONTENTS, PotionContents.EMPTY, getPotionRegistry().wrapAsHolder(potion), PotionContents::withPotion);
		return stack;
	}

	public static DataComponentPatch emptyExtraData() {
		return DataComponentPatch.EMPTY;
	}

	public static ResourceLocation id(String id) {
		return ResourceLocation.parse(id);
	}

	public static ResourceLocation id(String namespace, String path) {
		return ResourceLocation.fromNamespaceAndPath(namespace, path);
	}

	public static void applyModelViewMatrix() {
		// The gui pipelines read the pose stack directly.
	}

	public static void playClickSound() {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
	}
}
