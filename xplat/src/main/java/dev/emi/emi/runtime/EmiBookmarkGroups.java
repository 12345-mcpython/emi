package dev.emi.emi.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.serializer.EmiIngredientSerializer;
import dev.emi.emi.api.widget.WidgetHolder;
import dev.emi.emi.bom.BoM;
import dev.emi.emi.bom.CraftingTreeModel;
import dev.emi.emi.bom.MaterialTree;
import dev.emi.emi.bom.MaterialNode;
import dev.emi.emi.config.BookmarkViewMode;
import dev.emi.emi.config.EmiConfig;

/** Presentation metadata for the existing ordered favorites, never a separate plan list. */
public final class EmiBookmarkGroups {
	private static final Group DEFAULT = new Group("0");
	private static final Map<String, Group> states = new LinkedHashMap<>();
	private static final Map<String, String> membership = new LinkedHashMap<>();
	private static final Set<String> fullRecipes = new HashSet<>();
	private static final Map<String, Long> multipliers = new LinkedHashMap<>();
	private static final Map<String, Map<String, String>> ingredientChoices = new LinkedHashMap<>();
	private static long revision;
	private static final Map<Group, CachedRows> rowCache = new IdentityHashMap<>();
	private record CachedRows(long revision, EmiPlayerInventory inventory, List<Row> rows) {}
	private EmiBookmarkGroups() {}

	public static final class Group {
		public final String id;
		public BookmarkViewMode viewMode;
		/** Legacy boolean mirror kept for older callers and serialized data. */
		@Deprecated
		public boolean todo;
		public boolean crafting, collapsed;
		public final Set<String> collapsedRecipes = new LinkedHashSet<>();
		private Group(String id) { this(id, BookmarkViewMode.DEFAULT); }
		private Group(String id, BookmarkViewMode mode) {
			this.id = id;
			setViewMode(mode);
		}
		public void setViewMode(BookmarkViewMode mode) {
			viewMode = mode == null ? BookmarkViewMode.DEFAULT : mode;
			todo = viewMode == BookmarkViewMode.TODO;
		}
		public List<EmiFavorite> anchors() { return favorites(this); }
	}

	public enum Role { RESULT, REMAINDER, INGREDIENT }

	public static final class Row {
		public final EmiIngredient ingredient;
		/** Original plugin ingredient, including all candidates, never quantity-scaled. */
		public final EmiIngredient source;
		public final EmiRecipe recipe;
		public final EmiFavorite anchor;
		/** Owner of the original ingredient; a folded row's display anchor remains the box root. */
		public final EmiFavorite sourceAnchor;
		public final Role role;
		public final boolean input;
		/** Explicit bookmark amount, inventory-free plan amount, and outstanding inventory-adjusted amount. */
		public final long explicitAmount, calculatedAmount, remainingAmount;
		public final long amount, batches;
		public final Group group;
		public Row(EmiIngredient ingredient, EmiRecipe recipe, EmiFavorite anchor, boolean input,
				long amount, long batches, Group group) {
			this(input ? chosen(anchor, ingredient) : ingredient, ingredient, recipe, anchor, anchor, input ? Role.INGREDIENT : Role.RESULT,
				amount, amount, amount, batches, group);
		}
		private Row(EmiIngredient ingredient, EmiIngredient source, EmiRecipe recipe, EmiFavorite anchor,
				EmiFavorite sourceAnchor, Role role, long explicit, long calculated, long remaining, long batches, Group group) {
			this.source = source;
			this.sourceAnchor = sourceAnchor;
			this.ingredient = ingredient.copy().setAmount(Math.max(0, remaining));
			this.recipe = recipe;
			this.anchor = anchor;
			this.role = role;
			this.input = role == Role.INGREDIENT;
			this.explicitAmount = explicit;
			this.calculatedAmount = calculated;
			this.remainingAmount = remaining;
			this.amount = remaining;
			this.batches = batches;
			this.group = group;
		}
	}

	public static Group defaultGroup() { return DEFAULT; }
	public static List<EmiFavorite> anchors(Group group) { return favorites(group); }
	public static long revision() { return revision; }
	public static long getRevision() { return revision; }
	public static String key(EmiFavorite favorite) {
		JsonElement stack = EmiIngredientSerializer.getSerialized(favorite.getStack());
		if (stack == null || stack.isJsonNull()) return "";
		return canonical(stack) + "|" + recipeId(favorite.getRecipe());
	}
	private static String canonical(JsonElement value) {
		if (value.isJsonObject()) {
			List<String> keys = new ArrayList<>(value.getAsJsonObject().keySet());
			Collections.sort(keys);
			StringBuilder result = new StringBuilder("{");
			for (String key : keys) result.append(new com.google.gson.JsonPrimitive(key)).append(':')
				.append(canonical(value.getAsJsonObject().get(key))).append(',');
			return result.append('}').toString();
		}
		if (value.isJsonArray()) {
			StringBuilder result = new StringBuilder("[");
			for (JsonElement element : value.getAsJsonArray()) result.append(canonical(element)).append(',');
			return result.append(']').toString();
		}
		return value.toString();
	}
	private static String recipeId(EmiRecipe recipe) {
		return recipe == null || recipe.getId() == null ? "" : recipe.getId().toString();
	}
	public static Group groupOf(EmiFavorite favorite) {
		return states.getOrDefault(membership.get(key(favorite)), DEFAULT);
	}
	public static List<Group> groups() {
		LinkedHashSet<Group> result = new LinkedHashSet<>();
		for (EmiFavorite favorite : EmiFavorites.favorites) result.add(groupOf(favorite));
		result.add(DEFAULT);
		return List.copyOf(result);
	}
	public static List<EmiFavorite> favorites(Group group) {
		return EmiFavorites.favorites.stream().filter(f -> groupOf(f) == group).toList();
	}
	private static String ingredientKey(EmiIngredient ingredient) {
		JsonElement json = EmiIngredientSerializer.getSerialized(ingredient.copy().setAmount(1).setChance(1));
		return json == null || json.isJsonNull() ? "" : canonical(json);
	}
	private static EmiIngredient chosen(EmiFavorite anchor, EmiIngredient ingredient) {
		if (anchor == null) return ingredient;
		Map<String, String> choices = ingredientChoices.get(key(anchor));
		String selected = choices == null ? null : choices.get(ingredientKey(ingredient));
		if (selected != null) {
			for (EmiStack candidate : ingredient.getEmiStacks()) if (ingredientKey(candidate).equals(selected)) {
				return candidate.copy().setAmount(ingredient.getAmount()).setChance(ingredient.getChance());
			}
			return ingredient;
		}
		// A group already expresses a producer choice. Connect generic/tag inputs to
		// that producer before planning, rather than requiring an extra manual cycle.
		Group group = groupOf(anchor);
		if (group.crafting && ingredient.getEmiStacks().size() > 1) {
			for (EmiFavorite producer : favorites(group)) {
				EmiRecipe recipe = producer.getRecipe();
				if (recipe == null || recipe == anchor.getRecipe()) continue;
				for (EmiStack output : recipe.getOutputs()) for (EmiStack candidate : ingredient.getEmiStacks()) {
					if (!candidate.isEmpty() && EmiIngredient.areEqual(candidate, output))
						return candidate.copy().setAmount(ingredient.getAmount()).setChance(ingredient.getChance());
				}
			}
		}
		return ingredient;
	}
	public static void cycleIngredient(Row row, int delta) {
		if (row == null || !row.input || row.sourceAnchor == null || delta == 0) return;
		List<EmiStack> candidates = row.source.getEmiStacks();
		if (candidates.size() < 2) return;
		EmiIngredient current = chosen(row.sourceAnchor, row.source);
		int index = 0;
		for (int i = 0; i < candidates.size(); i++) if (EmiIngredient.areEqual(current, candidates.get(i))) { index = i; break; }
		int next = Math.floorMod((long) index + delta, candidates.size());
		ingredientChoices.computeIfAbsent(key(row.sourceAnchor), ignored -> new LinkedHashMap<>())
			.put(ingredientKey(row.source), ingredientKey(candidates.get(next)));
		changed();
	}
	public static boolean isFullRecipe(EmiFavorite favorite) { return fullRecipes.contains(key(favorite)); }
	public static long multiplier(EmiFavorite favorite) { return multipliers.getOrDefault(key(favorite), 1L); }
	private static void changed() {
		revision++;
		rowCache.clear();
		EmiScreenManagerBridge.refresh();
		EmiPersistentData.save();
	}
	/** Called after ordinary favorite mutations, including drag reordering. */
	public static void favoritesChanged() {
		Set<String> active = new HashSet<>();
		for (EmiFavorite favorite : EmiFavorites.favorites) active.add(key(favorite));
		membership.keySet().retainAll(active);
		fullRecipes.retainAll(active);
		multipliers.keySet().retainAll(active);
		ingredientChoices.keySet().retainAll(active);
		revision++;
		rowCache.clear();
	}
	public static void group(List<EmiFavorite> selection) {
		List<EmiFavorite> actual = actual(selection);
		if (actual.isEmpty()) return;
		Group group = new Group(UUID.randomUUID().toString(), EmiConfig.bookmarkViewMode);
		states.put(group.id, group);
		int insertion = identityIndex(actual.getFirst());
		Set<EmiFavorite> identities = Collections.newSetFromMap(new IdentityHashMap<>());
		identities.addAll(actual);
		EmiFavorites.favorites.removeIf(identities::contains);
		EmiFavorites.favorites.addAll(Math.min(insertion, EmiFavorites.favorites.size()), actual);
		for (EmiFavorite favorite : actual) membership.put(key(favorite), group.id);
		changed();
	}
	public static void ungroup(List<EmiFavorite> selection) {
		for (EmiFavorite favorite : actual(selection)) membership.remove(key(favorite));
		changed();
	}
	private static int identityIndex(EmiFavorite favorite) {
		for (int i = 0; i < EmiFavorites.favorites.size(); i++) if (EmiFavorites.favorites.get(i) == favorite) return i;
		return -1;
	}
	private static List<EmiFavorite> actual(List<EmiFavorite> selection) {
		if (selection == null) return List.of();
		Set<EmiFavorite> identities = Collections.newSetFromMap(new IdentityHashMap<>());
		identities.addAll(selection);
		return EmiFavorites.favorites.stream().filter(identities::contains).toList();
	}
	/** Resolve a visible item or folded box to the real ordered bookmark identities it represents. */
	public static List<EmiFavorite> representedAnchors(Group group, EmiFavorite anchor) {
		if (group == null) return List.of();
		List<EmiFavorite> anchors = favorites(group);
		if (group.collapsed) return anchors;
		if (anchor == null || anchors.stream().noneMatch(f -> f == anchor)) return List.of();
		EmiRecipe recipe = anchor.getRecipe();
		if (recipe == null) return List.of(anchor);
		Map<EmiRecipe, EmiFavorite> selected = new LinkedHashMap<>();
		for (EmiFavorite favorite : anchors) if (favorite.getRecipe() != null && !favorite.getRecipe().getOutputs().isEmpty()) {
			selected.putIfAbsent(favorite.getRecipe(), favorite);
		}
		Map<EmiRecipe, EmiRecipe> boxes = boxes(group, selected);
		if (boxes.get(recipe) == recipe) {
			return anchors.stream().filter(f -> boxes.get(f.getRecipe()) == recipe).toList();
		}
		return anchors.stream().filter(f -> f.getRecipe() == recipe).toList();
	}

	/** Delete the true identities represented by a display row, committing metadata once. */
	public static void removeRepresented(Row row) {
		if (row == null) return;
		Set<EmiFavorite> removing = Collections.newSetFromMap(new IdentityHashMap<>());
		removing.addAll(representedAnchors(row.group, row.anchor));
		if (removing.isEmpty()) return;
		EmiFavorites.favorites.removeIf(removing::contains);
		Set<String> active = new HashSet<>();
		for (EmiFavorite favorite : EmiFavorites.favorites) active.add(key(favorite));
		membership.keySet().retainAll(active);
		fullRecipes.retainAll(active);
		multipliers.keySet().retainAll(active);
		ingredientChoices.keySet().retainAll(active);
		changed();
	}

	/** Move real bookmark objects before target, adopting its membership; null appends to default. */
	public static void move(List<EmiFavorite> selection, EmiFavorite target) {
		List<EmiFavorite> moving = actual(selection);
		if (moving.isEmpty()) return;
		Set<EmiFavorite> identities = Collections.newSetFromMap(new IdentityHashMap<>());
		identities.addAll(moving);
		if (target != null && (identities.contains(target) || identityIndex(target) < 0)) return;
		Group destination = target == null ? DEFAULT : groupOf(target);
		EmiFavorites.favorites.removeIf(identities::contains);
		int offset = target == null ? EmiFavorites.favorites.size() : identityIndex(target);
		EmiFavorites.favorites.addAll(offset, moving);
		for (EmiFavorite favorite : moving) {
			if (destination == DEFAULT) membership.remove(key(favorite));
			else membership.put(key(favorite), destination.id);
		}
		changed();
	}

	/** Move the entire group before target; null means the end. */
	public static void moveGroup(Group group, Group target) {
		moveGroup(group, target, false);
	}
	/** Atomic boundary insertion; never merge membership or split the target group. */
	public static void moveGroup(Group group, Group target, boolean after) {
		if (group == null || group == target || group != DEFAULT && !states.containsValue(group)
			|| target != null && target != DEFAULT && !states.containsValue(target)) return;
		List<EmiFavorite> moving = favorites(group);
		if (moving.isEmpty()) return;
		List<EmiFavorite> targets = target == null ? List.of() : favorites(target);
		if (target != null && targets.isEmpty()) return;
		Set<EmiFavorite> identities = Collections.newSetFromMap(new IdentityHashMap<>());
		identities.addAll(moving);
		List<EmiFavorite> reordered = new ArrayList<>(EmiFavorites.favorites);
		reordered.removeIf(identities::contains);
		EmiFavorite boundary = targets.isEmpty() ? null : targets.get(after ? targets.size() - 1 : 0);
		int index = reordered.size();
		if (boundary != null) for (int i = 0; i < reordered.size(); i++) if (reordered.get(i) == boundary) {
			index = i + (after ? 1 : 0); break;
		}
		reordered.addAll(index, moving);
		boolean different = false;
		for (int i = 0; i < reordered.size(); i++) if (reordered.get(i) != EmiFavorites.favorites.get(i)) { different = true; break; }
		if (!different) return;
		EmiFavorites.favorites.clear();
		EmiFavorites.favorites.addAll(reordered);
		changed();
	}
	public static void toggleView(Group group) {
		if (group != null) {
			group.setViewMode(group.viewMode.toggle());
			changed();
		}
	}
	public static void toggleCrafting(Group group) { if (group != null) { group.crafting = !group.crafting; changed(); } }
	public static void toggleCollapsed(Group group) { if (group != null) { group.collapsed = !group.collapsed; changed(); } }
	public static void toggleRecipe(Group group, EmiRecipe recipe) {
		String id = recipeId(recipe);
		if (group == null || id.isEmpty()) return;
		if (!group.collapsedRecipes.remove(id)) group.collapsedRecipes.add(id);
		changed();
	}
	public static void adjust(Group group, EmiFavorite anchor, long delta) {
		if (group == null) return;
		List<EmiFavorite> targets = anchor == null ? roots(favorites(group)) : List.of(anchor);
		boolean modified = false;
		for (EmiFavorite target : targets) {
			if (groupOf(target) != group) continue;
			long previous = multiplier(target);
			long next = delta > 0 && previous > Long.MAX_VALUE - delta ? Long.MAX_VALUE : Math.max(1, previous + delta);
			multipliers.put(key(target), next);
			modified = true;
		}
		if (modified) changed();
	}
	public static void removeGroup(Group group) {
		if (group == null) return;
		EmiFavorites.favorites.removeIf(f -> groupOf(f) == group);
		if (group != DEFAULT) states.remove(group.id);
		favoritesChanged();
		changed();
	}
	public static void remove(EmiFavorite favorite) {
		String key = key(favorite);
		EmiFavorites.favorites.removeIf(f -> key(f).equals(key));
		favoritesChanged();
		changed();
	}

	/** Explicit recipe bookmarking. Only this operation may expand default producers. */
	public static void addRecipe(EmiRecipe recipe, boolean expandDefaults) {
		List<EmiRecipe> recipes = new ArrayList<>();
		collect(recipe, expandDefaults, recipes, Collections.newSetFromMap(new IdentityHashMap<>()));
		List<EmiFavorite> added = new ArrayList<>();
		for (EmiRecipe selected : recipes) {
			// An explicit chain action may promote an ungrouped bookmark. A bookmark
			// already assigned to a named group is never silently stolen from that group.
			EmiFavorite anchor = new EmiFavorite(selected.getOutputs().getFirst().copy(), selected);
			String key = key(anchor);
			EmiFavorite existing = null;
			for (EmiFavorite favorite : EmiFavorites.favorites) if (key(favorite).equals(key)) { existing = favorite; break; }
			if (existing != null) {
				if (groupOf(existing) != DEFAULT) continue;
				anchor = existing;
			} else EmiFavorites.favorites.add(anchor);
			added.add(anchor);
			fullRecipes.add(key);
		}
		if (!added.isEmpty()) {
			group(added);
			if (expandDefaults && !added.isEmpty()) {
				Group group = groupOf(added.getFirst());
				group.setViewMode(BookmarkViewMode.TODO);
				group.crafting = true;
				changed();
			}
		}
	}
	private static void collect(EmiRecipe recipe, boolean expand, List<EmiRecipe> result, Set<EmiRecipe> seen) {
		if (recipe == null || recipe.getId() == null || recipe.getOutputs().isEmpty() || result.size() >= 256 || !seen.add(recipe)) return;
		result.add(recipe);
		if (expand) for (EmiIngredient input : recipe.getInputs()) collect(BoM.getRecipe(input), true, result, seen);
	}

	public static List<Row> rows(Group group, EmiPlayerInventory inventory) {
		return rowsInternal(group, inventory, false);
	}
	public static List<Row> executionRows(Group group, EmiPlayerInventory inventory) {
		return rowsInternal(group, inventory, true);
	}
	private static List<Row> rowsInternal(Group group, EmiPlayerInventory inventory, boolean execution) {
		if (group == null) return List.of();
		CachedRows cached = rowCache.get(group);
		if (!execution && cached != null && cached.revision == revision && cached.inventory == inventory) return cached.rows;
		List<EmiFavorite> anchors = favorites(group);
		Map<EmiRecipe, Long> amounts = new IdentityHashMap<>();
		Map<EmiRecipe, EmiFavorite> selected = new LinkedHashMap<>();
		for (EmiFavorite anchor : anchors) {
			if (anchor.getRecipe() != null && !anchor.getRecipe().getOutputs().isEmpty()) {
				selected.putIfAbsent(anchor.getRecipe(), anchor);
				amounts.put(anchor.getRecipe(), multiplier(anchor));
			}
		}
		Map<EmiRecipe, Long> explicit = new IdentityHashMap<>(amounts);
		CraftingTreeModel fullModel = group.crafting && !selected.isEmpty() ? model(selected, null) : null;
		CraftingTreeModel model = inventory == null ? fullModel : group.crafting && !selected.isEmpty() ? model(selected, inventory) : null;
		Map<EmiRecipe, Long> calculated = planAmounts(selected, explicit, fullModel);
		amounts = planAmounts(selected, explicit, model);
		List<Row> result = new ArrayList<>();
		Map<EmiRecipe, EmiRecipe> boxes = execution ? Map.of() : boxes(group, selected);
		Ledger fullLedger = new Ledger(selected, calculated, fullModel, null);
		Ledger remainingLedger = inventory == null ? fullLedger : new Ledger(selected, amounts, model, inventory);
		Ledger explicitLedger = new Ledger(selected, explicit, fullModel, null);
		Set<EmiRecipe> emitted = Collections.newSetFromMap(new IdentityHashMap<>());
		List<EmiFavorite> visible = group.collapsed && !execution ? roots(anchors) : anchors;
		for (EmiFavorite anchor : visible) {
			EmiRecipe recipe = anchor.getRecipe();
			EmiRecipe box = recipe == null ? null : boxes.get(recipe);
			if (box != null && !group.collapsed) {
				if (box != recipe || !emitted.add(recipe)) continue;
				result.addAll(boxRows(group, recipe, anchor, boxes, selected, explicitLedger, fullLedger, remainingLedger, amounts));
				continue;
			}
			if (!isFullRecipe(anchor) || recipe == null || recipe.getOutputs().isEmpty()) {
				long batches = amounts.getOrDefault(recipe, multiplier(anchor));
				result.add(new Row(anchor.getStack(), anchor.getStack(), recipe, anchor, anchor, Role.RESULT,
					multiply(anchor.getAmount(), multiplier(anchor)), multiply(anchor.getAmount(), calculated.getOrDefault(recipe, multiplier(anchor))),
					multiply(anchor.getAmount(), batches), batches, group));
				continue;
			}
			if (!emitted.add(recipe)) continue;
			long batches = amounts.getOrDefault(recipe, multiplier(anchor));
			if (execution || group.collapsed) {
				for (EmiStack output : recipe.getOutputs()) result.add(new Row(output, output, recipe, anchor, anchor, Role.RESULT,
					quantity(multiply(output.getAmount(), multiplier(anchor)) * chance(output.getChance()), 0),
					quantity(multiply(output.getAmount(), calculated.getOrDefault(recipe, 0L)) * chance(output.getChance()), 0),
					quantity(multiply(output.getAmount(), batches) * chance(output.getChance()), 0), batches, group));
			} else {
				result.addAll(boxRows(group, recipe, anchor, Map.of(recipe, recipe), selected,
					explicitLedger, fullLedger, remainingLedger, amounts));
			}
		}
		List<Row> rows = List.copyOf(result);
		if (!execution) rowCache.put(group, new CachedRows(revision, inventory, rows));
		return rows;
	}
	/** Assign each hidden producer once. Explicit collapsed roots remain separate at crossings. */
	private static Map<EmiRecipe, EmiRecipe> boxes(Group group, Map<EmiRecipe, EmiFavorite> selected) {
		Map<EmiRecipe, EmiRecipe> boxes = new IdentityHashMap<>();
		for (EmiRecipe recipe : selected.keySet()) if (group.collapsedRecipes.contains(recipeId(recipe))) boxes.put(recipe, recipe);
		for (EmiRecipe recipe : selected.keySet()) if (boxes.get(recipe) == recipe) {
			claimDependencies(recipe, recipe, selected, boxes, Collections.newSetFromMap(new IdentityHashMap<>()));
		}
		return boxes;
	}
	private static void claimDependencies(EmiRecipe recipe, EmiRecipe root, Map<EmiRecipe, EmiFavorite> selected,
			Map<EmiRecipe, EmiRecipe> boxes, Set<EmiRecipe> seen) {
		if (!seen.add(recipe)) return;
		for (EmiIngredient input : recipe.getInputs()) for (EmiRecipe producer : selected.keySet()) {
			if (producer == recipe || producer == root) continue;
			boolean produces = false;
			for (EmiStack output : producer.getOutputs()) if (EmiIngredient.areEqual(chosen(selected.get(recipe), input), output)) produces = true;
			if (!produces || boxes.containsKey(producer) && boxes.get(producer) != root) continue;
			boxes.put(producer, root);
			claimDependencies(producer, root, selected, boxes, seen);
		}
	}
	private static Map<EmiRecipe, Long> planAmounts(Map<EmiRecipe, EmiFavorite> selected,
			Map<EmiRecipe, Long> explicit, CraftingTreeModel model) {
		Map<EmiRecipe, Long> result = new IdentityHashMap<>(explicit);
		if (model != null) {
			for (EmiRecipe recipe : selected.keySet()) result.put(recipe, 0L);
			for (CraftingTreeModel.Step step : model.steps) if (selected.containsKey(step.recipe())) result.put(step.recipe(), step.batches());
		}
		return result;
	}
	/** One group-wide stock ledger per quantity view. Folded boxes only project its boundary flows. */
	private static final class Ledger {
		final List<Flow> flows = new ArrayList<>();
		Ledger(Map<EmiRecipe, EmiFavorite> selected, Map<EmiRecipe, Long> amounts,
				CraftingTreeModel model, EmiPlayerInventory inventory) {
			List<Stock> stocks = new ArrayList<>();
			if (inventory != null) for (EmiStack item : inventory.inventory.values()) {
				stocks.add(new Stock(item, null, item.getAmount(), true));
			}
			List<EmiRecipe> order = new ArrayList<>();
			if (model != null) for (CraftingTreeModel.Step step : model.steps) if (selected.containsKey(step.recipe())) order.add(step.recipe());
			for (EmiRecipe recipe : selected.keySet()) if (!order.contains(recipe)) order.add(recipe);
			for (EmiRecipe recipe : order) {
				long batches = amounts.getOrDefault(recipe, 0L);
				if (batches <= 0) continue;
				EmiFavorite anchor = selected.get(recipe);
				List<Stock> returned = new ArrayList<>();
				for (EmiIngredient source : recipe.getInputs()) {
					// Shaped recipes retain empty grid cells; they are layout, not materials.
					if (source.isEmpty()) continue;
					EmiIngredient input = chosen(anchor, source);
					if (input.isEmpty()) continue;
					MaterialNode node = new MaterialNode(input);
					double desired = node.catalyst ? input.getAmount() : multiply(input.getAmount(), batches) * chance(input.getChance());
					double outstanding = desired;
					for (Stock stock : stocks) {
						if (outstanding <= 1.0e-9) break;
						if (!matches(input, stock.ingredient) || stock.amount <= 0) continue;
						double taken = Math.min(outstanding, stock.amount);
						stock.amount -= taken;
						outstanding -= taken;
						if (!stock.inventory) flows.add(new Flow(stock.ingredient, source, anchor, stock.recipe, recipe, taken, 0));
						if (node.catalyst) returned.add(new Stock(stock.ingredient, stock.inventory ? null : recipe, taken, stock.inventory, true));
					}
					if (outstanding > 1.0e-9) {
						flows.add(new Flow(input, source, anchor, null, recipe, outstanding, input.getAmount()));
						if (node.catalyst) returned.add(new Stock(input, recipe, outstanding, false, true));
					}
					if (!node.catalyst && !node.remainder.isEmpty()) {
						returned.add(new Stock(node.remainder, recipe,
							multiply(node.remainderAmount, batches) * chance(input.getChance()), false));
					}
				}
				stocks.addAll(returned);
				for (EmiStack output : recipe.getOutputs()) stocks.add(new Stock(output, recipe,
					multiply(output.getAmount(), batches) * chance(output.getChance()), false));
			}
			for (Stock stock : stocks) if (!stock.inventory && !stock.catalyst && stock.amount > 1.0e-9) {
				flows.add(new Flow(stock.ingredient, stock.ingredient, selected.get(stock.recipe), stock.recipe, null, stock.amount, 0));
			}
		}
		List<Boundary> boundary(Set<EmiRecipe> members, EmiRecipe root) {
			List<Boundary> result = new ArrayList<>();
			for (Flow flow : flows) {
				boolean from = members.contains(flow.from), to = members.contains(flow.to);
				if (from == to) continue;
				Role role = to ? Role.INGREDIENT : flow.from == root && isOutput(root, flow.ingredient) ? Role.RESULT : Role.REMAINDER;
				// Inputs retain their candidate editor; outputs retain the actual produced stack.
				EmiIngredient ingredient = to ? chosen(flow.anchor, flow.source) : flow.ingredient;
				Boundary row = EmiBookmarkGroups.boundary(result, ingredient, flow.source, flow.anchor, role);
				row.amount += flow.amount;
				row.min = Math.max(row.min, flow.min);
			}
			return result;
		}
	}
	private static boolean isOutput(EmiRecipe recipe, EmiIngredient ingredient) {
		for (EmiStack output : recipe.getOutputs()) if (EmiIngredient.areEqual(output, ingredient)) return true;
		return false;
	}
	private static boolean matches(EmiIngredient ingredient, EmiIngredient stock) {
		for (EmiStack candidate : ingredient.getEmiStacks()) if (EmiIngredient.areEqual(candidate, stock)) return true;
		return false;
	}
	private static double chance(float chance) { return Float.isFinite(chance) ? Math.max(0, chance) : 0; }
	private static long quantity(double amount, long min) {
		return amount <= 1.0e-9 ? 0 : Math.max(min, amount >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0, Math.round(amount)));
	}
	private record Flow(EmiIngredient ingredient, EmiIngredient source, EmiFavorite anchor,
		EmiRecipe from, EmiRecipe to, double amount, long min) {}
	private static final class Stock {
		final EmiIngredient ingredient;
		final EmiRecipe recipe;
		final boolean inventory;
		final boolean catalyst;
		double amount;
		Stock(EmiIngredient ingredient, EmiRecipe recipe, double amount, boolean inventory) {
			this(ingredient, recipe, amount, inventory, false);
		}
		Stock(EmiIngredient ingredient, EmiRecipe recipe, double amount, boolean inventory, boolean catalyst) {
			this.ingredient = ingredient; this.recipe = recipe; this.amount = amount; this.inventory = inventory;
			this.catalyst = catalyst;
		}
	}
	private static final class Boundary {
		final EmiIngredient ingredient, source;
		final EmiFavorite anchor;
		final Role role;
		double amount;
		long min;
		Boundary(EmiIngredient ingredient, EmiIngredient source, EmiFavorite anchor, Role role) {
			this.ingredient = ingredient; this.source = source; this.anchor = anchor; this.role = role;
		}
		long quantity() { return EmiBookmarkGroups.quantity(amount, min); }
	}
	private static Boundary boundary(List<Boundary> rows, EmiIngredient ingredient, EmiIngredient source, EmiFavorite anchor, Role role) {
		for (Boundary row : rows) if (row.role == role && EmiIngredient.areEqual(row.ingredient, ingredient)
				&& (role != Role.INGREDIENT || row.source == source && row.anchor == anchor)) return row;
		Boundary row = new Boundary(ingredient, source, anchor, role);
		rows.add(row);
		return row;
	}
	private static long boundaryAmount(List<Boundary> rows, Boundary target) {
		for (Boundary row : rows) if (row.role == target.role && EmiIngredient.areEqual(row.ingredient, target.ingredient)
				&& (row.role != Role.INGREDIENT || row.source == target.source && row.anchor == target.anchor)) return row.quantity();
		return 0;
	}
	private static List<Row> boxRows(Group group, EmiRecipe root, EmiFavorite anchor,
			Map<EmiRecipe, EmiRecipe> boxes, Map<EmiRecipe, EmiFavorite> selected, Ledger explicit,
			Ledger calculated, Ledger remaining, Map<EmiRecipe, Long> amounts) {
		Set<EmiRecipe> members = Collections.newSetFromMap(new IdentityHashMap<>());
		for (EmiRecipe recipe : selected.keySet()) if (boxes.get(recipe) == root) members.add(recipe);
		List<Boundary> real = explicit.boundary(members, root), full = calculated.boundary(members, root), net = remaining.boundary(members, root);
		List<Boundary> all = new ArrayList<>();
		// Keep root output identity even when inventory eliminates the entire plan.
		for (EmiStack output : root.getOutputs()) boundary(all, output, output, anchor, Role.RESULT);
		for (List<Boundary> view : List.of(full, net, real)) for (Boundary row : view) boundary(all, row.ingredient, row.source, row.anchor, row.role);
		List<Row> result = new ArrayList<>();
		for (Role role : List.of(Role.RESULT, Role.REMAINDER, Role.INGREDIENT)) for (Boundary row : all) {
			if (row.role != role || role == Role.INGREDIENT && group.viewMode != BookmarkViewMode.TODO) continue;
			result.add(new Row(row.ingredient, row.source, root, anchor, row.anchor, role,
				boundaryAmount(real, row), boundaryAmount(full, row), boundaryAmount(net, row), amounts.getOrDefault(root, 0L), group));
		}
		return result;
	}
	private static List<EmiFavorite> roots(List<EmiFavorite> anchors) {
		List<EmiFavorite> result = new ArrayList<>();
		for (EmiFavorite candidate : anchors) {
			boolean consumed = false;
			for (EmiFavorite other : anchors) {
				if (other == candidate || other.getRecipe() == null || other.getRecipe() == candidate.getRecipe()) continue;
				for (EmiIngredient input : other.getRecipe().getInputs()) {
					if (EmiIngredient.areEqual(candidate.getStack(), chosen(other, input))) consumed = true;
				}
			}
			if (!consumed) result.add(candidate);
		}
		return result.isEmpty() && !anchors.isEmpty() ? List.of(anchors.getFirst()) : result;
	}
	/** Selected-candidate view for filling; handler lookup and execution budgets must use original. */
	public static EmiRecipe effectiveRecipe(Group group, EmiRecipe original) {
		if (original == null) return null;
		EmiFavorite anchor = favorites(group).stream().filter(f -> f.getRecipe() == original).findFirst().orElse(null);
		return effectiveRecipe(original, anchor);
	}
	private static EmiRecipe effectiveRecipe(EmiRecipe original, EmiFavorite anchor) {
		return new EmiRecipe() {
			public EmiRecipeCategory getCategory() { return original.getCategory(); }
			public ResourceLocation getId() { return original.getId(); }
			public List<EmiIngredient> getInputs() { return original.getInputs().stream().map(i -> chosen(anchor, i)).toList(); }
			public List<EmiStack> getOutputs() { return original.getOutputs(); }
			public List<EmiIngredient> getCatalysts() { return original.getCatalysts(); }
			public boolean supportsRecipeTree() { return original.supportsRecipeTree(); }
			public int getDisplayWidth() { return original.getDisplayWidth(); }
			public int getDisplayHeight() { return original.getDisplayHeight(); }
			public void addWidgets(WidgetHolder widgets) { original.addWidgets(widgets); }
		};
	}
	/** A selected-recipes-only snapshot; never reads or changes global BoM resolutions. */
	public static MaterialTree tree(Group group) {
		if (group == null) return null;
		Map<EmiRecipe, EmiFavorite> selected = new LinkedHashMap<>();
		for (EmiFavorite anchor : favorites(group)) if (anchor.getRecipe() != null && !anchor.getRecipe().getOutputs().isEmpty()) {
			selected.putIfAbsent(anchor.getRecipe(), anchor);
		}
		return selected.isEmpty() ? null : treeSnapshot(selected).tree;
	}
	private record TreeSnapshot(MaterialTree tree, Map<EmiRecipe, EmiRecipe> originals) {}
	private static TreeSnapshot treeSnapshot(Map<EmiRecipe, EmiFavorite> selected) {
		List<EmiIngredient> goals = new ArrayList<>();
		for (Map.Entry<EmiRecipe, EmiFavorite> entry : selected.entrySet()) {
			EmiStack output = entry.getKey().getOutputs().getFirst();
			boolean consumed = false;
			for (EmiRecipe other : selected.keySet()) {
				if (other == entry.getKey()) continue;
				for (EmiIngredient input : other.getInputs()) if (EmiIngredient.areEqual(output, chosen(selected.get(other), input))) consumed = true;
			}
			long demand = multiplier(entry.getValue()) - (consumed ? 1 : 0);
			if (demand > 0) goals.add(output.copy().setAmount(multiply(output.getAmount(), demand)).setChance(1));
		}
		// A wholly cyclic selection still has an explicit bounded goal.
		if (goals.isEmpty()) {
			Map.Entry<EmiRecipe, EmiFavorite> first = selected.entrySet().iterator().next();
			EmiStack output = first.getKey().getOutputs().getFirst();
			goals.add(output.copy().setAmount(multiply(output.getAmount(), multiplier(first.getValue()))).setChance(1));
		}
		Map<EmiRecipe, EmiRecipe> originals = new IdentityHashMap<>();
		List<EmiRecipe> effective = new ArrayList<>();
		for (Map.Entry<EmiRecipe, EmiFavorite> entry : selected.entrySet()) {
			EmiRecipe original = entry.getKey();
			EmiRecipe wrapper = effectiveRecipe(original, entry.getValue());
			effective.add(wrapper);
			originals.put(wrapper, original);
		}
		EmiRecipe root = new EmiRecipe() {
			public EmiRecipeCategory getCategory() { return selected.keySet().iterator().next().getCategory(); }
			public ResourceLocation getId() { return null; }
			public List<EmiIngredient> getInputs() { return goals; }
			public List<EmiStack> getOutputs() { return List.of(EmiStack.of(Items.BARRIER)); }
			public int getDisplayWidth() { return 0; }
			public int getDisplayHeight() { return 0; }
			public void addWidgets(WidgetHolder widgets) {}
		};
		MaterialTree tree = new MaterialTree(root) {
			@Override public EmiRecipe getRecipe(EmiIngredient ingredient) {
				for (EmiRecipe recipe : effective) for (EmiStack output : recipe.getOutputs()) {
					if (EmiIngredient.areEqual(ingredient, output)) return recipe;
				}
				return null;
			}
		};
		return new TreeSnapshot(tree, originals);
	}
	private static CraftingTreeModel model(Map<EmiRecipe, EmiFavorite> selected, EmiPlayerInventory inventory) {
		try {
			TreeSnapshot snapshot = treeSnapshot(selected);
			Map<EmiRecipe, EmiRecipe> originals = snapshot.originals;
			CraftingTreeModel model = new CraftingTreeModel(snapshot.tree, inventory);
			for (int i = 0; i < model.steps.size(); i++) {
				CraftingTreeModel.Step step = model.steps.get(i);
				EmiRecipe original = originals.get(step.recipe());
				if (original != null) model.steps.set(i, new CraftingTreeModel.Step(original, step.batches()));
			}
			return model;
		} catch (RuntimeException e) {
			EmiLog.error("Failed to calculate bookmark group", e);
			return null;
		}
	}
	private static long multiply(long a, long b) {
		if (a <= 0 || b <= 0) return 0;
		return a > Long.MAX_VALUE / b ? Long.MAX_VALUE : a * b;
	}

	public static JsonObject save() {
		JsonObject json = new JsonObject();
		json.addProperty("version", 1);
		JsonArray groups = new JsonArray();
		for (Group group : groups()) {
			JsonObject state = new JsonObject();
			state.addProperty("id", group.id);
			state.addProperty("view_mode", group.viewMode.getName());
			// Keep the legacy field for configs read by the previous build.
			state.addProperty("todo", group.viewMode == BookmarkViewMode.TODO);
			state.addProperty("crafting", group.crafting);
			state.addProperty("collapsed", group.collapsed);
			JsonArray folded = new JsonArray();
			group.collapsedRecipes.forEach(folded::add);
			state.add("collapsed_recipes", folded);
			groups.add(state);
		}
		json.add("groups", groups);
		JsonArray entries = new JsonArray();
		for (EmiFavorite favorite : EmiFavorites.favorites) {
			String key = key(favorite);
			JsonObject entry = new JsonObject();
			entry.addProperty("key", key);
			entry.addProperty("group", groupOf(favorite).id);
			entry.addProperty("full_recipe", isFullRecipe(favorite));
			entry.addProperty("multiplier", multiplier(favorite));
			JsonObject choices = new JsonObject();
			Map<String, String> selection = ingredientChoices.get(key);
			if (selection != null) selection.forEach(choices::addProperty);
			entry.add("ingredient_choices", choices);
			entries.add(entry);
		}
		json.add("entries", entries);
		return json;
	}
	public static void load(JsonObject json) {
		states.clear(); membership.clear(); fullRecipes.clear(); multipliers.clear(); ingredientChoices.clear();
		DEFAULT.setViewMode(EmiConfig.bookmarkViewMode);
		DEFAULT.crafting = DEFAULT.collapsed = false;
		DEFAULT.collapsedRecipes.clear();
		states.put(DEFAULT.id, DEFAULT);
		if (json != null) {
			if (json.has("groups") && json.get("groups").isJsonArray()) for (JsonElement element : json.getAsJsonArray("groups")) {
				try {
					JsonObject data = element.getAsJsonObject();
					String id = data.get("id").getAsString();
					Group group = id.equals("0") ? DEFAULT : new Group(id, EmiConfig.bookmarkViewMode);
					if (data.has("view_mode")) {
						group.setViewMode(BookmarkViewMode.byName(data.get("view_mode").getAsString()));
					} else if (data.has("todo")) {
						group.setViewMode(data.get("todo").getAsBoolean()
							? BookmarkViewMode.TODO : BookmarkViewMode.DEFAULT);
					} else {
						group.setViewMode(EmiConfig.bookmarkViewMode);
					}
					group.crafting = data.has("crafting") && data.get("crafting").getAsBoolean();
					group.collapsed = data.has("collapsed") && data.get("collapsed").getAsBoolean();
					if (data.has("collapsed_recipes")) for (JsonElement recipe : data.getAsJsonArray("collapsed_recipes")) group.collapsedRecipes.add(recipe.getAsString());
					states.put(id, group);
				} catch (RuntimeException e) { EmiLog.error("Skipping invalid bookmark group", e); }
			}
			if (json.has("entries") && json.get("entries").isJsonArray()) for (JsonElement element : json.getAsJsonArray("entries")) {
				try {
					JsonObject entry = element.getAsJsonObject();
					String key = entry.get("key").getAsString();
					String group = entry.get("group").getAsString();
					if (states.containsKey(group) && !group.equals("0")) membership.put(key, group);
					if (entry.has("full_recipe") && entry.get("full_recipe").getAsBoolean()) fullRecipes.add(key);
					if (entry.has("multiplier")) multipliers.put(key, Math.max(1, entry.get("multiplier").getAsLong()));
					if (entry.has("ingredient_choices") && entry.get("ingredient_choices").isJsonObject()) {
						Map<String, String> choices = new LinkedHashMap<>();
						for (Map.Entry<String, JsonElement> choice : entry.getAsJsonObject("ingredient_choices").entrySet()) {
							if (choice.getValue().isJsonPrimitive() && choice.getValue().getAsJsonPrimitive().isString()) choices.put(choice.getKey(), choice.getValue().getAsString());
						}
						ingredientChoices.put(key, choices);
					}
				} catch (RuntimeException e) { EmiLog.error("Skipping invalid bookmark membership", e); }
			}
		}
		favoritesChanged();
	}

	/** Isolate the refresh dependency from persistence and calculation code. */
	private static final class EmiScreenManagerBridge {
		static void refresh() {
			dev.emi.emi.screen.EmiScreenManager.repopulatePanels(dev.emi.emi.config.SidebarType.FAVORITES);
		}
	}
}
