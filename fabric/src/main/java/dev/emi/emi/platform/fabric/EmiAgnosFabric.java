package dev.emi.emi.platform.fabric;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.commons.lang3.text.WordUtils;

import com.google.common.collect.Lists;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.emi.emi.EmiPort;
import dev.emi.emi.EmiRenderHelper;
import dev.emi.emi.EmiUtil;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.FluidEmiStack;
import dev.emi.emi.jemi.JemiUtil;
import dev.emi.emi.mixin.accessor.BakedModelManagerAccessor;
import dev.emi.emi.mixin.accessor.BrewingRecipeRegistryRecipeAccessor;
import dev.emi.emi.platform.EmiAgnos;
import dev.emi.emi.recipe.EmiBrewingRecipe;
import dev.emi.emi.registry.EmiPluginContainer;
import dev.emi.emi.registry.EmiTags;
import dev.emi.emi.runtime.EmiLog;
import dev.emi.emi.screen.FakeScreen;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
import net.fabricmc.fabric.api.registry.FuelRegistry;
import net.fabricmc.fabric.api.transfer.v1.client.fluid.FluidVariantRendering;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributes;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.level.material.Fluid;
import org.apache.commons.lang3.text.WordUtils;

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
		for (Ingredient ingredient : PotionBrewing.ALLOWED_CONTAINERS) {
			for (ItemStack stack : ingredient.getItems()) {
				String pid = EmiUtil.subId(stack.getItem());
				for (PotionBrewing.Mix<Potion> recipe : PotionBrewing.POTION_MIXES) {
					try {
						Ingredient recipeIngredient = ((BrewingRecipeRegistryRecipeAccessor) recipe).getIngredient();
						if (recipeIngredient.getItems().length > 0) {
							ResourceLocation id = EmiPort.id("emi", "/brewing/" + pid
								+ "/" + EmiUtil.subId(recipeIngredient.getItems()[0].getItem())
								+ "/" + EmiUtil.subId(EmiPort.getPotionRegistry().getKey((Potion) ((BrewingRecipeRegistryRecipeAccessor) recipe).getInput()))
								+ "/" + EmiUtil.subId(EmiPort.getPotionRegistry().getKey((Potion) ((BrewingRecipeRegistryRecipeAccessor) recipe).getOutput())));
							registry.addRecipe(new EmiBrewingRecipe(
								EmiStack.of(EmiPort.setPotion(stack.copy(), (Potion) ((BrewingRecipeRegistryRecipeAccessor) recipe).getInput())), EmiIngredient.of(recipeIngredient),
								EmiStack.of(EmiPort.setPotion(stack.copy(), (Potion) ((BrewingRecipeRegistryRecipeAccessor) recipe).getOutput())), id));
						}
					} catch (Exception e) {
						EmiLog.error("Error registering brewing recipe", e);
					}
				}
			}
		}

		for (PotionBrewing.Mix<Item> recipe : PotionBrewing.CONTAINER_MIXES) {
			try {
				Ingredient recipeIngredient = ((BrewingRecipeRegistryRecipeAccessor) recipe).getIngredient();
				if (recipeIngredient.getItems().length > 0) {
					String gid = EmiUtil.subId(recipeIngredient.getItems()[0].getItem());
					String iid = EmiUtil.subId((Item) ((BrewingRecipeRegistryRecipeAccessor) recipe).getInput());
					String oid = EmiUtil.subId((Item) ((BrewingRecipeRegistryRecipeAccessor) recipe).getOutput());
					Consumer<Holder<Potion>> potionRecipeGen = entry -> {
						Potion potion = entry.value();
						if (potion == Potions.EMPTY) {
							return;
						}
						if (PotionBrewing.isBrewablePotion(potion)) {
							ResourceLocation id = new ResourceLocation("emi", "/brewing/item/"
								+ EmiUtil.subId(entry.unwrapKey().get().location()) + "/" + gid + "/" + iid + "/" + oid);
							registry.addRecipe(new EmiBrewingRecipe(
								EmiStack.of(EmiPort.setPotion(new ItemStack((Item) ((BrewingRecipeRegistryRecipeAccessor) recipe).getInput()), potion)), EmiIngredient.of(recipeIngredient),
								EmiStack.of(EmiPort.setPotion(new ItemStack((Item) ((BrewingRecipeRegistryRecipeAccessor) recipe).getOutput()), potion)), id));
						}
					};
					if ((((BrewingRecipeRegistryRecipeAccessor) recipe).getInput() instanceof PotionItem)) {
						EmiPort.getPotionRegistry().holders().forEach(potionRecipeGen);
					} else {
						potionRecipeGen.accept(EmiPort.getPotionRegistry().wrapAsHolder(Potions.AWKWARD));
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
	protected Component getFluidNameAgnos(Fluid fluid, CompoundTag nbt) {
		return FluidVariantAttributes.getName(FluidVariant.of(fluid, nbt));
	}

	@Override
	protected List<Component> getFluidTooltipAgnos(Fluid fluid, CompoundTag nbt) {
		return FluidVariantRendering.getTooltip(FluidVariant.of(fluid, nbt));
	}

	@Override
	protected boolean isFloatyFluidAgnos(FluidEmiStack stack) {
		FluidVariant fluid = FluidVariant.of(stack.getKeyOfType(Fluid.class), stack.getNbt());
		return FluidVariantAttributes.isLighterThanAir(fluid);
	}

	@Override
	protected void renderFluidAgnos(FluidEmiStack stack, PoseStack matrices, int x, int y, float delta, int xOff, int yOff, int width, int height) {
		FluidVariant fluid = FluidVariant.of(stack.getKeyOfType(Fluid.class), stack.getNbt());
		TextureAtlasSprite[] sprites = FluidVariantRendering.getSprites(fluid);
		if (sprites == null || sprites.length < 1 || sprites[0] == null) {
			return;
		}
		TextureAtlasSprite sprite = sprites[0];
		int color = FluidVariantRendering.getColor(fluid);
		
		EmiRenderHelper.drawTintedSprite(matrices, sprite, color, x, y, xOff, yOff, width, height);
	}

	@Override
	protected EmiStack createFluidStackAgnos(Object object) {
		return JemiUtil.getFluidFromJei(object);
	}

	@Override
	protected boolean canBatchAgnos(ItemStack stack) {
		return ColorProviderRegistry.ITEM.get(stack.getItem()) == null;
	}

	@Override
	protected Map<Item, Integer> getFuelMapAgnos() {
		Object2IntMap<Item> fuelMap = new Object2IntOpenHashMap<>();
		for (Item item : EmiPort.getItemRegistry()) {
			if (FuelRegistry.INSTANCE.get(item) == null) {
				continue;
			}
			int time = FuelRegistry.INSTANCE.get(item);
			if (time > 0) {
				fuelMap.put(item, time);
			}
		}
		return fuelMap;
	}

	@Override
	protected BakedModel getBakedTagModelAgnos(ResourceLocation id) {
		Minecraft client = Minecraft.getInstance();
		return ((BakedModelManagerAccessor) client.getModelManager()).getModels()
			.getOrDefault(id, client.getModelManager().getMissingModel());
	}

	@Override
	protected boolean isEnchantableAgnos(ItemStack stack, Enchantment enchantment) {
		return true;
	}
}
