package dev.emi.emi.recipe;

import java.util.List;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import com.google.common.collect.Lists;

import dev.emi.emi.EmiPort;
import dev.emi.emi.EmiUtil;
import dev.emi.emi.api.recipe.EmiCraftingRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.runtime.EmiLog;
import dev.emi.emi.runtime.ProxyRecipeManager;

public class EmiShapedRecipe extends EmiCraftingRecipe {
	private final int recipeWidth, recipeHeight;

	public EmiShapedRecipe(ShapedRecipe recipe) {
		super(padIngredients(recipe), EmiStack.of(EmiPort.getOutput(recipe)), ProxyRecipeManager.getId(recipe), false);
		this.recipeWidth = recipe.getWidth();
		this.recipeHeight = recipe.getHeight();
		setRemainders(input, recipe);
	}

	@Override
	public boolean canFit(int width, int height) {
		// 1.21.2+ dropped canCraftInDimensions, so the grid size check has to be kept here.
		return recipeWidth <= width && recipeHeight <= height && super.canFit(width, height);
	}

	public static void setRemainders(List<EmiIngredient> input, CraftingRecipe recipe) {
		try {
			TransientCraftingContainer inv = EmiUtil.getCraftingInventory();
			for (int i = 0; i < input.size(); i++) {
				if (input.get(i).isEmpty()) {
					continue;
				}
				for (int j = 0; j < input.size(); j++) {
					if (j == i) {
						continue;
					}
					if (!input.get(j).isEmpty()) {
						inv.setItem(j, input.get(j).getEmiStacks().get(0).getItemStack().copy());
					}
				}
				List<EmiStack> stacks = input.get(i).getEmiStacks();
				for (EmiStack stack : stacks) {
					inv.setItem(i, stack.getItemStack().copy());
					CraftingInput cri = CraftingInput.of(inv.getWidth(), inv.getHeight(), inv.getItems());
					if (cri.width() <= 3 && cri.height() <= 3) {
						ItemStack remainder = ItemStack.EMPTY;
						// 1.21.2+ shrinks the crafting input for smaller recipes, so the remainder list
						// does not always cover the padded 3x3 grid. Only read positions that exist.
						int rx = i % 3, ry = i / 3;
						if (rx < cri.width() && ry < cri.height()) {
							List<ItemStack> remainders = recipe.getRemainingItems(cri);
							int index = ry * cri.width() + rx;
							if (index >= 0 && index < remainders.size()) {
								remainder = remainders.get(index);
							}
						}
						if (!remainder.isEmpty()) {
							stack.setRemainder(EmiStack.of(remainder));
						}
					}
				}
				inv.clearContent();
			}
		} catch (Exception e) {
			EmiLog.error("Exception thrown setting remainders for " + ProxyRecipeManager.getId(recipe), e);
		}
	}

	private static List<EmiIngredient> padIngredients(ShapedRecipe recipe) {
		List<EmiIngredient> list = Lists.newArrayList();
		int width = recipe.getWidth();
		int height = recipe.getHeight();
		java.util.List<java.util.Optional<net.minecraft.world.item.crafting.Ingredient>> pattern = recipe.getIngredients();
		for (int y = 0; y < 3; y++) {
			for (int x = 0; x < 3; x++) {
				int index = y * width + x;
				if (x < width && y < height && index < pattern.size()) {
					java.util.Optional<net.minecraft.world.item.crafting.Ingredient> ingredient = pattern.get(index);
					list.add(ingredient.isPresent() ? EmiIngredient.of(ingredient.get()) : EmiStack.EMPTY);
				} else {
					list.add(EmiStack.EMPTY);
				}
			}
		}
		return list;
	}
}
