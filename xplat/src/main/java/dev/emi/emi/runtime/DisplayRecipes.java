package dev.emi.emi.runtime;

import java.util.List;

import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;

import dev.emi.emi.EmiPort;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.BasicEmiRecipe;
import dev.emi.emi.api.recipe.EmiCraftingRecipe;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.recipe.VanillaEmiRecipeCategories;
import dev.emi.emi.api.render.EmiTexture;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;

/**
 * Builds EMI recipes from the client side recipe book.
 * <p>
 * Since 1.21.2 the client no longer receives a recipe table, only the synchronized recipe displays that
 * populate the recipe book. {@link ProxyRecipeManager} can read full recipes from the integrated server in
 * singleplayer, but on a server that is not available, which used to leave the index without any vanilla
 * recipes. This source reads the displays instead, so vanilla recipes show up in multiplayer too.
 */
public class DisplayRecipes {
	private static final Minecraft client = Minecraft.getInstance();

	public static boolean isUsable() {
		return client.player != null && client.level != null;
	}

	public static int register(EmiRegistry registry) {
		if (!isUsable()) {
			return 0;
		}
		ClientRecipeBook book = client.player.getRecipeBook();
		ContextMap context = SlotDisplayContext.fromLevel(client.level);
		int count = 0;
		for (RecipeCollection collection : book.getCollections()) {
			for (RecipeDisplayEntry entry : collection.getRecipes()) {
				try {
					EmiRecipe recipe = convert(entry, context);
					if (recipe != null) {
						registry.addRecipe(recipe);
						count++;
					}
				} catch (Throwable t) {
					EmiReloadLog.warn("Exception thrown when parsing a recipe display", t);
				}
			}
		}
		EmiLog.info("[EMI] Registered " + count + " recipes from client displays");
		return count;
	}

	private static EmiRecipe convert(RecipeDisplayEntry entry, ContextMap context) {
		RecipeDisplay display = entry.display();
		ResourceLocation id = EmiPort.id("emi", "/display/" + entry.id().index());
		EmiRecipeCategory category = categoryFor(display.craftingStation().resolveForFirstStack(context));
		if (display instanceof ShapedCraftingRecipeDisplay shaped) {
			List<EmiIngredient> inputs = resolve(shaped.ingredients(), context);
			EmiStack output = resolveStack(shaped.result(), context);
			if (output.isEmpty() || inputs.isEmpty()) {
				return null;
			}
			return new EmiCraftingRecipe(inputs, output, id, false);
		} else if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
			List<EmiIngredient> inputs = resolve(shapeless.ingredients(), context);
			EmiStack output = resolveStack(shapeless.result(), context);
			if (output.isEmpty() || inputs.isEmpty()) {
				return null;
			}
			return new EmiCraftingRecipe(inputs, output, id, true);
		} else if (display instanceof FurnaceRecipeDisplay furnace) {
			List<EmiIngredient> inputs = List.of(
				resolve(furnace.ingredient(), context),
				resolve(furnace.fuel(), context));
			EmiStack output = resolveStack(furnace.result(), context);
			if (output.isEmpty()) {
				return null;
			}
			return new DisplayRecipe(category, id, inputs, List.of(output));
		} else if (display instanceof StonecutterRecipeDisplay stonecutter) {
			List<EmiIngredient> inputs = List.of(resolve(stonecutter.input(), context));
			EmiStack output = resolveStack(stonecutter.result(), context);
			if (output.isEmpty()) {
				return null;
			}
			return new DisplayRecipe(category, id, inputs, List.of(output));
		} else if (display instanceof SmithingRecipeDisplay smithing) {
			List<EmiIngredient> inputs = List.of(
				resolve(smithing.template(), context),
				resolve(smithing.base(), context),
				resolve(smithing.addition(), context));
			EmiStack output = resolveStack(smithing.result(), context);
			if (output.isEmpty()) {
				return null;
			}
			return new DisplayRecipe(category, id, inputs, List.of(output));
		}
		return null;
	}

	private static EmiRecipeCategory categoryFor(ItemStack station) {
		if (!station.isEmpty()) {
			ResourceLocation key = BuiltInRegistries.ITEM.getKey(station.getItem());
			String path = key == null ? "" : key.getPath();
			switch (path) {
				case "blast_furnace" -> {
					return VanillaEmiRecipeCategories.BLASTING;
				}
				case "smoker" -> {
					return VanillaEmiRecipeCategories.SMOKING;
				}
				case "campfire" -> {
					return VanillaEmiRecipeCategories.CAMPFIRE_COOKING;
				}
				case "stonecutter" -> {
					return VanillaEmiRecipeCategories.STONECUTTING;
				}
				case "smithing_table" -> {
					return VanillaEmiRecipeCategories.SMITHING;
				}
				case "furnace" -> {
					return VanillaEmiRecipeCategories.SMELTING;
				}
				default -> {
				}
			}
		}
		return VanillaEmiRecipeCategories.CRAFTING;
	}

	private static List<EmiIngredient> resolve(List<SlotDisplay> displays, ContextMap context) {
		return displays.stream().map(display -> resolve(display, context)).toList();
	}

	private static EmiIngredient resolve(SlotDisplay display, ContextMap context) {
		List<EmiStack> stacks = display.resolveForStacks(context).stream()
			.filter(stack -> !stack.isEmpty()).map(EmiStack::of).toList();
		return stacks.isEmpty() ? EmiStack.EMPTY : EmiIngredient.of(stacks);
	}

	private static EmiStack resolveStack(SlotDisplay display, ContextMap context) {
		ItemStack stack = display.resolveForFirstStack(context);
		return stack.isEmpty() ? EmiStack.EMPTY : EmiStack.of(stack);
	}

	/**
	 * A flat input row, arrow and output row for displays that have no dedicated EMI equivalent.
	 */
	private static class DisplayRecipe extends BasicEmiRecipe {
		public DisplayRecipe(EmiRecipeCategory category, ResourceLocation id, List<EmiIngredient> inputs, List<EmiStack> outputs) {
			super(category, id, inputs.size() * 18 + 26 + outputs.size() * 18, 18);
			this.inputs.addAll(inputs);
			this.outputs.addAll(outputs);
		}

		@Override
		public void addWidgets(WidgetHolder widgets) {
			int x = 0;
			for (EmiIngredient input : inputs) {
				widgets.addSlot(input, x, 0);
				x += 18;
			}
			widgets.addTexture(EmiTexture.EMPTY_ARROW, x, 0);
			x += 26;
			for (EmiStack output : outputs) {
				widgets.addSlot(output, x, 0);
				x += 18;
			}
		}
	}
}
