package dev.emi.emi.bom;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.google.common.collect.Lists;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;

public class MaterialNode {
	public final EmiIngredient ingredient;
	public final EmiStack remainder;
	public @Nullable EmiRecipe recipe;
	public @Nullable List<MaterialNode> children;
	public float consumeChance = 1, produceChance = 1;
	public long amount = 1;
	// Largest individual input slot, before identical inputs are combined.
	public long minBatch = 1;
	public long divisor = 1;
	public long remainderAmount = 0;
	public boolean catalyst = false;
	public boolean cycle = false, truncated = false;
	private static final int MAX_DEPTH = 128, MAX_NODES = 4096;
	// Should these be decoupled from material nodes?
	public FoldState state = FoldState.EXPANDED;
	public ProgressState progress = ProgressState.UNSTARTED;
	public long neededBatches = 0, totalNeeded = 0;

	public MaterialNode(EmiIngredient ingredient) {
		this.amount = ingredient.getAmount();
		this.minBatch = this.amount;
		this.ingredient = ingredient.copy().setAmount(1).setChance(1);
		if (this.ingredient.getEmiStacks().size() == 1) {
			this.remainder = this.ingredient.getEmiStacks().get(0).getRemainder().copy();
			this.remainderAmount = remainder.getAmount();
			this.remainder.setAmount(1);
		} else {
			this.remainder = EmiStack.EMPTY;
		}
		out: {
			for (EmiStack stack : ingredient.getEmiStacks()) {
				if (!stack.getRemainder().equals(stack) || stack.getRemainder().getAmount() < ingredient.getAmount()) {
					break out;
				}
			}
			catalyst = true;
		}
	}

	public MaterialNode(MaterialNode node) {
		this.ingredient = node.ingredient;
		this.remainder = node.remainder;
		this.recipe = node.recipe;
		this.amount = node.amount;
		this.minBatch = node.minBatch;
		this.divisor = node.divisor;
		this.remainderAmount = node.remainderAmount;
		this.consumeChance = node.consumeChance;
		this.produceChance = node.produceChance;
		this.catalyst = node.catalyst;
		this.cycle = node.cycle;
		this.truncated = node.truncated;
	}

	public void recalculate(MaterialTree tree) {
		recalculate(tree, Lists.newArrayList(), new int[] {MAX_NODES});
	}

	private void recalculate(MaterialTree tree, List<EmiRecipe> used, int[] budget) {
		cycle = false;
		truncated = false;
		if (--budget[0] < 0 || used.size() >= MAX_DEPTH) {
			defineRecipe(null);
			truncated = true;
			return;
		}
		EmiRecipe recipe = this.recipe;
		if (!used.isEmpty()) {
			recipe = tree.getRecipe(ingredient);
		}
		if (recipe == null || used.contains(recipe)) {
			cycle = recipe != null;
			defineRecipe(null);
			return;
		}
		if (recipe != null) {
			used.add(recipe);
			defineRecipe(recipe);
			for (MaterialNode node : children) {
				node.recalculate(tree, used, budget);
			}
			used.remove(used.size() - 1);
		}
	}

	private static long saturatedAdd(long a, long b) {
		return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
	}

	public void defineRecipe(EmiRecipe recipe) {
		produceChance = 1;
		this.recipe = recipe;
		this.children = null;
		this.divisor = 1;
		this.totalNeeded = 0;
		this.neededBatches = 0;
		if (recipe == null) {
			return;
		}
		divisor = 0;
		for (EmiStack stack : recipe.getOutputs()) {
			if (stack.equals(ingredient)) {
				if (divisor > 0) {
					if (produceChance != 1 || stack.getChance() != 1) {
						produceChance = (float) ((stack.getAmount() * (double) stack.getChance() + divisor * (double) produceChance) / (divisor * (double) 1 + stack.getAmount()));
					}
					divisor = saturatedAdd(divisor, stack.getAmount());
				} else {
					divisor = stack.getAmount();
					produceChance = stack.getChance();
				}
			}
		}
		if (divisor <= 0) {
			divisor = 1;
		}
		this.children = Lists.newArrayList();
		outer:
		for (EmiIngredient i : recipe.getInputs()) {
			EmiStack remainder = EmiStack.EMPTY;
			if (i.getEmiStacks().size() == 1) {
				remainder = i.getEmiStacks().get(0).getRemainder();
			}
			for (MaterialNode node : children) {
				if (EmiIngredient.areEqual(i, node.ingredient) && EmiIngredient.areEqual(remainder, node.remainder)) {
					long sum = saturatedAdd(node.amount, i.getAmount());
					node.consumeChance = (float) ((node.amount * (double) node.consumeChance + i.getAmount() * (double) i.getChance()) / (node.amount * (double) 1 + i.getAmount()));
					node.amount = sum;
					node.minBatch = Math.max(node.minBatch, i.getAmount());
					node.remainderAmount = saturatedAdd(node.remainderAmount, remainder.getAmount());
					node.catalyst &= new MaterialNode(i).catalyst;
					continue outer;
				}
			}
			if (!i.isEmpty()) {
				MaterialNode node = new MaterialNode(i);
				node.consumeChance = i.getChance();
				children.add(node);
			}
		}
	}
}
