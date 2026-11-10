package dev.emi.emi.platform.fabric;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import it.unimi.dsi.fastutil.objects.Object2FloatMap;
import org.apache.commons.lang3.text.WordUtils;

import com.google.common.collect.Lists;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.emi.emi.EmiPort;
import dev.emi.emi.EmiRenderHelper;
import dev.emi.emi.EmiUtil;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.FabricEmiStack;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.FluidEmiStack;
import dev.emi.emi.mixin.accessor.BrewingRecipeRegistryAccessor;
import dev.emi.emi.platform.EmiAgnos;
import dev.emi.emi.recipe.EmiBrewingRecipe;
import dev.emi.emi.registry.EmiPluginContainer;
import dev.emi.emi.runtime.EmiLog;
import dev.emi.emi.screen.FakeScreen;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.fabricmc.fabric.api.transfer.v1.client.fluid.FluidVariantRendering;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributes;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.material.Fluid;

public class EmiAgnosFabric extends EmiAgnos {
	static {
		EmiAgnos.delegate = new EmiAgnosFabric();
	}

	@Override
	protected boolean isForgeAgnos() {
		return false;
	}

	@SuppressWarnings("deprecation")
	@Override
	protected String getModNameAgnos(String namespace) {
		if (namespace.equals("c")) {
			return "Common";
		}
		Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(namespace);
		if (container.isPresent()) {
			return container.get().getMetadata().getName();
		}
		container = FabricLoader.getInstance().getModContainer(namespace.replace('_', '-'));
		if (container.isPresent()) {
			return container.get().getMetadata().getName();
		}
		return WordUtils.capitalizeFully(namespace.replace('_', ' '));
	}

	@Override
	protected Path getConfigDirectoryAgnos() {
		return FabricLoader.getInstance().getConfigDir();
	}

	@Override
	protected boolean isDevelopmentEnvironmentAgnos() {
		return FabricLoader.getInstance().isDevelopmentEnvironment();
	}

	@Override
	protected boolean isModLoadedAgnos(String id) {
		return FabricLoader.getInstance().isModLoaded(id);
	}

	@Override
	protected List<String> getAllModNamesAgnos() {
		return FabricLoader.getInstance().getAllMods().stream().map(c -> c.getMetadata().getName()).toList();
	}

	@Override
	protected List<String> getAllModAuthorsAgnos() {
		return FabricLoader.getInstance().getAllMods().stream().flatMap(c -> c.getMetadata().getAuthors().stream())
			.map(p -> p.getName()).distinct().toList();
	}

	@Override
	protected List<String> getModsWithPluginsAgnos() {
		List<String> list = Lists.newArrayList();
		for (EntrypointContainer<EmiPlugin> container : FabricLoader.getInstance().getEntrypointContainers("emi", EmiPlugin.class)) {
			try {
				list.add(container.getProvider().getMetadata().getId());
			} catch (Throwable t) {
				EmiLog.error("Critical exception thrown when reading EMI Plugin from mod " + container.getProvider().getMetadata().getId(), t);
			}
		}
		return list;
	}


	@Override
	protected List<EmiPluginContainer> getPluginsAgnos() {
		List<EmiPluginContainer> list = Lists.newArrayList();
		for (EntrypointContainer<EmiPlugin> container : FabricLoader.getInstance().getEntrypointContainers("emi", EmiPlugin.class)) {
			try {
				list.add(new EmiPluginContainer(container.getEntrypoint(), container.getProvider().getMetadata().getId()));
			} catch (Throwable t) {
				EmiLog.error("Critical exception thrown when constructing EMI Plugin from mod " + container.getProvider().getMetadata().getId(), t);
			}
		}
		return list;
	}

	@Override
	protected void addBrewingRecipesAgnos(EmiRegistry registry) {
		PotionBrewing brewingRegistry = Minecraft.getInstance().level != null ? Minecraft.getInstance().level.potionBrewing() : PotionBrewing.EMPTY;
		BrewingRecipeRegistryAccessor brewingRegistryAccess = (BrewingRecipeRegistryAccessor)brewingRegistry;
		for (Ingredient ingredient : brewingRegistryAccess.getPotionTypes()) {
			for (var itemHolder : ingredient.items().toList()) {
				ItemStack stack = new ItemStack(itemHolder.value());
				String pid = EmiUtil.subId(stack.getItem());
				for (PotionBrewing.Mix<Potion> recipe : brewingRegistryAccess.getPotionRecipes()) {
					try {
						Ingredient recipeIngredient = recipe.ingredient();
						if (recipeIngredient.items().findAny().isPresent()) {
							ResourceLocation id = EmiPort.id("emi", "/brewing/" + pid
								+ "/" + EmiUtil.subId(recipeIngredient.items().findFirst().get().value())
								+ "/" + EmiUtil.subId(EmiPort.getPotionRegistry().getKey(recipe.from().value()))
								+ "/" + EmiUtil.subId(EmiPort.getPotionRegistry().getKey(recipe.to().value())));
							registry.addRecipe(new EmiBrewingRecipe(
								EmiStack.of(EmiPort.setPotion(stack.copy(), recipe.from().value())), EmiIngredient.of(recipeIngredient),
								EmiStack.of(EmiPort.setPotion(stack.copy(), recipe.to().value())), id));
						}
					} catch (Exception e) {
						EmiLog.error("Error registering brewing recipe", e);
					}
				}
			}
		}

		for (PotionBrewing.Mix<Item> recipe : brewingRegistryAccess.getItemRecipes()) {
			try {
				Ingredient recipeIngredient = recipe.ingredient();
				if (recipeIngredient.items().findAny().isPresent()) {
					String gid = EmiUtil.subId(recipeIngredient.items().findFirst().get().value());
					String iid = EmiUtil.subId(recipe.from().value());
					String oid = EmiUtil.subId(recipe.to().value());
					Consumer<Holder<Potion>> potionRecipeGen = entry -> {
						if (brewingRegistry.isBrewablePotion(entry)) {
							ResourceLocation id = EmiPort.id("emi", "/brewing/item/"
								+ EmiUtil.subId(entry.unwrapKey().get().location()) + "/" + gid + "/" + iid + "/" + oid);
							registry.addRecipe(new EmiBrewingRecipe(
								EmiStack.of(EmiPort.setPotion(new ItemStack(recipe.from().value()), entry.value())), EmiIngredient.of(recipeIngredient),
								EmiStack.of(EmiPort.setPotion(new ItemStack(recipe.to().value()), entry.value())), id));
						}
					};
					if (recipe.from().value() instanceof PotionItem) {
						EmiPort.getPotionRegistry().stream().map(EmiPort.getPotionRegistry()::wrapAsHolder).forEach(potionRecipeGen);
					} else {
						potionRecipeGen.accept(Potions.AWKWARD);
					}
				}
			} catch (Exception e) {
				EmiLog.error("Error registering brewing recipe", e);
			}
		}
	}

	@Override
	protected List<ClientTooltipComponent> getItemTooltipAgnos(ItemStack stack) {
		return FakeScreen.INSTANCE.getTooltipComponentListFromItem(stack);
	}

	@Override
	protected Component getFluidNameAgnos(Fluid fluid, DataComponentPatch componentChanges) {
		return FluidVariantAttributes.getName(FluidVariant.of(fluid, componentChanges));
	}

	@Override
	protected List<Component> getFluidTooltipAgnos(Fluid fluid, DataComponentPatch componentChanges) {
		return FluidVariantRendering.getTooltip(FluidVariant.of(fluid, componentChanges));
	}

	@Override
	protected boolean isFloatyFluidAgnos(FluidEmiStack stack) {
		FluidVariant fluid = FluidVariant.of(stack.getKeyOfType(Fluid.class), stack.getComponentChanges());
		return FluidVariantAttributes.isLighterThanAir(fluid);
	}

	@Override
	protected void renderFluidAgnos(FluidEmiStack stack, net.minecraft.client.gui.GuiGraphics draw, int x, int y, float delta, int xOff, int yOff, int width, int height) {
		FluidVariant fluid = FluidVariant.of(stack.getKeyOfType(Fluid.class), stack.getComponentChanges());
		TextureAtlasSprite[] sprites = FluidVariantRendering.getSprites(fluid);
		TextureAtlasSprite sprite = sprites == null || sprites.length < 1 ? null : sprites[0];
		if (sprite == null) {
			// Fall back to the block model icon, the same one the world renderer uses for water.
			net.minecraft.world.level.block.state.BlockState state = fluid.getFluid()
				.defaultFluidState().createLegacyBlock();
			sprite = Minecraft.getInstance().getModelManager().getBlockModelShaper().getParticleIcon(state);
		}
		if (sprite == null) {
			return;
		}
		int color = FluidVariantRendering.getColor(fluid);
		if (color == -1) {
			color = 0xFFFFFFFF;
		}
		
		EmiRenderHelper.drawTintedSprite(draw, sprite, color, x, y, xOff, yOff, width, height);
	}

	@Override
	protected EmiStack createFluidStackAgnos(Object object) {
		// JEI ingredient bridging is disabled while jemi is excluded on this checkpoint.
		return EmiStack.EMPTY;
	}

	@Override
	protected boolean canBatchAgnos(ItemStack stack) {
		return true;
	}

	@Override
	protected Map<Item, Integer> getFuelMapAgnos() {
		try {
			return fuelMapFromLevel();
		} catch (Throwable t) {
			// Dedicated servers never need this map; never let a missing client class break them.
			return Map.of();
		}
	}

	private static Map<Item, Integer> fuelMapFromLevel() {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null) {
			return Map.of();
		}
		net.minecraft.world.level.block.entity.FuelValues values = client.level.fuelValues();
		if (values == null) {
			values = net.minecraft.world.level.block.entity.FuelValues.vanillaBurnTimes(
				client.level.registryAccess(), client.level.enabledFeatures());
		}
		Object2IntMap<Item> fuelMap = new Object2IntOpenHashMap<>();
		for (Item item : values.fuelItems()) {
			int time = values.burnDuration(item.getDefaultInstance());
			if (time > 0) {
				fuelMap.put(item, time);
			}
		}
		return fuelMap;
	}

	@Override
	protected boolean isEnchantableAgnos(ItemStack stack, Enchantment enchantment) {
		return true;
	}

	@Override
	protected Object2FloatMap<ItemLike> getComposterMapAgnos() {
		return ComposterBlock.COMPOSTABLES;
	}
}
