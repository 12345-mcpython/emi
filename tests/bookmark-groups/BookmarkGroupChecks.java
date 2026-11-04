package dev.emi.emi.runtime;

import java.util.List;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.util.Identifier;

/** Executed with the production bookmark class and production material graph implementation. */
public class BookmarkGroupChecks {
	private static int checks;

	private static void check(boolean value, String message) {
		checks++;
		if (!value) throw new AssertionError(message);
	}

	private record Recipe(String id, List<EmiIngredient> inputs, List<EmiStack> outputs) implements EmiRecipe {
		public Identifier getId() { return new Identifier(id); }
		public List<EmiIngredient> getInputs() { return inputs; }
		public List<EmiStack> getOutputs() { return outputs; }
	}

	private static final class CandidateIngredient implements EmiIngredient {
		final List<EmiStack> stacks; long amount; float chance = 1;
		CandidateIngredient(List<EmiStack> stacks, long amount) { this.stacks = stacks; this.amount = amount; }
		public List<EmiStack> getEmiStacks() { return stacks; }
		public EmiIngredient copy() { return new CandidateIngredient(stacks, amount).setChance(chance); }
		public long getAmount() { return amount; }
		public EmiIngredient setAmount(long value) { amount = value; return this; }
		public float getChance() { return chance; }
		public EmiIngredient setChance(float value) { chance = value; return this; }
	}
	private static EmiStack stack(String name, long amount) { return new EmiStack(name, amount); }

	private static Recipe recipe(String id, String output, long amount, EmiIngredient... inputs) {
		return new Recipe(id, List.of(inputs), List.of(stack(output, amount)));
	}

	private static long batches(List<EmiBookmarkGroups.Row> rows, EmiRecipe recipe) {
		return rows.stream().filter(row -> row.recipe == recipe && !row.input).findFirst().orElseThrow().batches;
	}

	private static EmiBookmarkGroups.Row row(List<EmiBookmarkGroups.Row> rows, String id, EmiBookmarkGroups.Role role) {
		return rows.stream().filter(r -> r.role == role && r.ingredient.getEmiStacks().get(0).id.equals(id)).findFirst().orElseThrow();
	}
	private static EmiBookmarkGroups.Group setup(EmiRecipe root, EmiRecipe... dependencies) {
		EmiFavorites.favorites.clear();
		EmiBookmarkGroups.load(null);
		EmiBookmarkGroups.addRecipe(root, false);
		var anchor = EmiFavorites.favorites.get(0);
		for (EmiRecipe recipe : dependencies) EmiFavorites.favorites.add(new EmiFavorite(recipe.getOutputs().get(0), recipe));
		EmiBookmarkGroups.group(List.copyOf(EmiFavorites.favorites));
		var group = EmiBookmarkGroups.groupOf(anchor);
		EmiBookmarkGroups.toggleCrafting(group);
		EmiBookmarkGroups.toggleView(group);
		EmiBookmarkGroups.toggleRecipe(group, root);
		return group;
	}
	private static void plainFavoriteChecks() {
		EmiFavorites.favorites.clear();
		EmiBookmarkGroups.load(null);
		var plain = new EmiFavorite(stack("plain", 7), null);
		EmiFavorites.favorites.add(plain);
		EmiBookmarkGroups.favoritesChanged();
		var group = EmiBookmarkGroups.defaultGroup();
		var execution = EmiBookmarkGroups.executionRows(group, null);
		check(execution.size() == 1 && execution.get(0).recipe == null && execution.get(0).anchor == plain
			&& execution.get(0).amount == 7 && execution.get(0).batches == 1,
			"default-group ordinary favorite execution projection accepts null recipe and preserves its amount");
		var display = EmiBookmarkGroups.rows(group, null);
		check(display.size() == 1 && display.get(0).recipe == null && display.get(0).ingredient.getAmount() == 7,
			"default-group ordinary favorite display accepts null recipe");
		EmiBookmarkGroups.adjust(group, plain, 2);
		execution = EmiBookmarkGroups.executionRows(group, null);
		check(execution.get(0).amount == 21 && execution.get(0).explicitAmount == 21
			&& execution.get(0).calculatedAmount == 21 && execution.get(0).remainingAmount == 21,
			"default-group ordinary favorite execution projection applies explicit multiplier to all quantity views");
		EmiBookmarkGroups.group(List.of(plain));
		group = EmiBookmarkGroups.groupOf(plain);
		EmiBookmarkGroups.toggleCrafting(group);
		EmiBookmarkGroups.toggleView(group);
		execution = EmiBookmarkGroups.executionRows(group, null);
		check(execution.size() == 1 && execution.get(0).recipe == null && execution.get(0).amount == 21,
			"named crafting group with only an ordinary favorite executes safely without any recipe model");
		display = EmiBookmarkGroups.rows(group, null);
		check(display.size() == 1 && display.get(0).recipe == null && display.get(0).amount == 21
			&& display.get(0).role == EmiBookmarkGroups.Role.RESULT && !display.get(0).input,
			"named-group ordinary favorite display retains amount and role without a recipe");
		EmiBookmarkGroups.toggleCollapsed(group);
		check(EmiBookmarkGroups.executionRows(group, null).get(0).amount == 21
			&& EmiBookmarkGroups.rows(group, null).get(0).amount == 21,
			"collapsed ordinary-only group preserves safe null-recipe execution and display projection");
	}
	private static void shapedEmptyChecks() {
		// Vanilla EmptyEmiStack has amount 1 and an immutable empty remainder.
		EmiStack hole = stack("", 1); hole.remainder(hole);
		Recipe table = recipe("test:shaped_holes", "table", 1,
			stack("plank", 1), stack("plank", 1), hole,
			stack("plank", 1), stack("plank", 1), hole, hole, hole, hole);
		var group = setup(table);
		EmiBookmarkGroups.adjust(group, EmiFavorites.favorites.get(0), 9);
		var inventory = new dev.emi.emi.api.recipe.EmiPlayerInventory(List.of(stack("plank", 8)));
		for (boolean folded : List.of(true, false)) {
			for (var stock : java.util.Arrays.asList(null, inventory)) {
				var rows = EmiBookmarkGroups.rows(group, stock);
				check(rows.stream().noneMatch(r -> r.ingredient.isEmpty() || r.source.isEmpty()),
					"shaped empty cells never appear as input or catalyst remainder, folded=" + folded);
				check(row(rows, "table", EmiBookmarkGroups.Role.RESULT).calculatedAmount == 10,
					"ignoring layout holes preserves the ten-batch result");
			}
			if (folded) EmiBookmarkGroups.toggleRecipe(group, table);
		}
		var rows = EmiBookmarkGroups.rows(group, null);
		check(rows.stream().filter(r -> r.input).mapToLong(r -> r.calculatedAmount).sum() == 40,
			"only four genuine plank slots contribute to ten table batches");
		check(hole.getAmount() == 1 && hole.isEmpty() && hole.getRemainder() == hole,
			"projection does not mutate the empty layout ingredient");
	}

	private static void tagProducerFlowChecks() {
		var planks = new CandidateIngredient(List.of(stack("oak_plank", 1), stack("birch_plank", 1)), 4);
		Recipe table = recipe("test:tag_table", "table", 1, planks);
		Recipe oak = recipe("test:oak_plank", "oak_plank", 4, stack("log", 1));
		EmiFavorites.favorites.clear();
		EmiBookmarkGroups.load(null);
		EmiBookmarkGroups.addRecipe(table, false);
		EmiBookmarkGroups.addRecipe(oak, false);
		EmiBookmarkGroups.group(List.copyOf(EmiFavorites.favorites));
		var group = EmiBookmarkGroups.groupOf(EmiFavorites.favorites.get(0));
		EmiBookmarkGroups.toggleView(group);
		EmiBookmarkGroups.toggleCrafting(group);
		var rows = EmiBookmarkGroups.rows(group, null);
		var output = rows.stream().filter(r -> r.recipe == oak && r.role == EmiBookmarkGroups.Role.RESULT).findFirst().orElseThrow();
		check(output.calculatedAmount == 4, "concrete producer output consumed by a tag retains its output identity and quantity");
		check(rows.stream().noneMatch(r -> r.recipe == oak && r.role == EmiBookmarkGroups.Role.REMAINDER),
			"a consumed plank output is not mislabeled as a tag-shaped remainder");
		check(planks.getEmiStacks().size() == 2 && planks.getAmount() == 4,
			"classifying producer flows does not mutate the original tag or candidates");
		EmiBookmarkGroups.adjust(group, EmiFavorites.favorites.get(0), 9);
		rows = EmiBookmarkGroups.rows(group, null);
		var inputs = rows.stream().filter(r -> r.recipe == table && r.input).toList();
		check(inputs.size() == 1 && inputs.get(0).calculatedAmount == 40 && inputs.get(0).source == planks
			&& inputs.get(0).ingredient.getEmiStacks().size() == 1
			&& inputs.get(0).ingredient.getEmiStacks().get(0).id.equals("oak_plank"),
			"automatic producer choice keeps one forty-plank row and its original candidate editor");
		check(row(rows, "oak_plank", EmiBookmarkGroups.Role.RESULT).calculatedAmount == 40
			&& row(rows, "log", EmiBookmarkGroups.Role.INGREDIENT).calculatedAmount == 10,
			"ten tables automatically require forty producer planks and ten logs");
		check(batches(EmiBookmarkGroups.executionRows(group, null), oak) == 10,
			"root adjustment scales the producer execution budget to ten batches");
		var effective = EmiBookmarkGroups.effectiveRecipe(group, table);
		check(effective.getInputs().get(0).getEmiStacks().size() == 1
			&& effective.getInputs().get(0).getEmiStacks().get(0).id.equals("oak_plank")
			&& effective.getInputs().get(0).getAmount() == 4,
			"effective fill recipe automatically uses concrete oak with the source slot amount");
		check(table.getInputs().get(0) == planks && planks.getEmiStacks().size() == 2
			&& planks.getAmount() == 4 && planks.getEmiStacks().get(0).getAmount() == 1,
			"automatic linking does not mutate original plugin inputs or candidate amounts");
		var tree = EmiBookmarkGroups.tree(group);
		check(tree != null && tree.goal.children.size() == 1
			&& tree.goal.children.get(0).recipe.getId().equals(table.getId()),
			"automatic dependency selection leaves only the table as the group tree root");
		EmiBookmarkGroups.adjust(group, null, 1);
		check(EmiBookmarkGroups.multiplier(EmiFavorites.favorites.get(0)) == 11
			&& EmiBookmarkGroups.multiplier(EmiFavorites.favorites.get(1)) == 1
			&& batches(EmiBookmarkGroups.executionRows(group, null), oak) == 11,
			"group header adjustment changes only the real root and derives eleven producer batches");
		EmiBookmarkGroups.adjust(group, null, -1);
		EmiBookmarkGroups.toggleRecipe(group, table);
		rows = EmiBookmarkGroups.rows(group, null);
		check(row(rows, "log", EmiBookmarkGroups.Role.INGREDIENT).calculatedAmount == 10
			&& rows.stream().filter(r -> r.input && r.source == planks).allMatch(r -> r.calculatedAmount == 0)
			&& batches(EmiBookmarkGroups.executionRows(group, null), oak) == 10,
			"folding cancels calculated internal plank demand without changing its ten-log budget");
		var inventory = new dev.emi.emi.api.recipe.EmiPlayerInventory(
			List.of(stack("table", 2), stack("oak_plank", 8), stack("log", 1)));
		rows = EmiBookmarkGroups.rows(group, inventory);
		check(row(rows, "log", EmiBookmarkGroups.Role.INGREDIENT).calculatedAmount == 10
			&& row(rows, "log", EmiBookmarkGroups.Role.INGREDIENT).remainingAmount == 5,
			"finished tables and intermediate oak stock reduce folded outstanding logs to five");
		check(batches(EmiBookmarkGroups.executionRows(group, inventory), table) == 8
			&& batches(EmiBookmarkGroups.executionRows(group, inventory), oak) == 6,
			"inventory-adjusted execution produces only eight tables and six oak batches");
		EmiBookmarkGroups.toggleRecipe(group, table);
		rows = EmiBookmarkGroups.rows(group, inventory);
		check(row(rows, "log", EmiBookmarkGroups.Role.INGREDIENT).calculatedAmount == 10
			&& row(rows, "log", EmiBookmarkGroups.Role.INGREDIENT).remainingAmount == 5
			&& inventory.inventory.values().stream().mapToLong(EmiStack::getAmount).sum() == 11,
			"unfolding keeps the same full and remaining budget without consuming the inventory snapshot");
		var input = rows.stream().filter(r -> r.recipe == table && r.input).findFirst().orElseThrow();
		EmiBookmarkGroups.cycleIngredient(input, 1);
		rows = EmiBookmarkGroups.rows(group, null);
		check(row(rows, "birch_plank", EmiBookmarkGroups.Role.INGREDIENT).calculatedAmount == 40
			&& batches(EmiBookmarkGroups.executionRows(group, null), oak) == 1,
			"manual birch override is not silently replaced by the available oak producer");
		check(EmiBookmarkGroups.effectiveRecipe(group, table).getInputs().get(0).getEmiStacks().get(0).id.equals("birch_plank"),
			"effective recipe honors the explicit birch override instead of automatic oak");
		var saved = EmiBookmarkGroups.save();
		EmiBookmarkGroups.load(saved);
		group = EmiBookmarkGroups.groupOf(EmiFavorites.favorites.get(0));
		check(EmiBookmarkGroups.effectiveRecipe(group, table).getInputs().get(0).getEmiStacks().get(0).id.equals("birch_plank"),
			"saved explicit birch override remains stronger than automatic producer linkage");
		group = setup(table);
		check(EmiBookmarkGroups.effectiveRecipe(group, table).getInputs().get(0) == planks,
			"without a same-group producer the original generic input passes through unchanged");
		EmiBookmarkGroups.addRecipe(oak, false);
		check(EmiBookmarkGroups.groupOf(EmiFavorites.favorites.get(1)) != group
			&& EmiBookmarkGroups.effectiveRecipe(group, table).getInputs().get(0) == planks,
			"a producer in another group cannot select this group's generic input");
		Recipe birch = recipe("test:birch_plank", "birch_plank", 4, stack("birch_log", 1));
		group = setup(table, oak, birch);
		check(EmiBookmarkGroups.effectiveRecipe(group, table).getInputs().get(0).getEmiStacks().get(0).id.equals("oak_plank"),
			"two matching producers choose the first producer in group bookmark order");
		group = setup(table, birch, oak);
		check(EmiBookmarkGroups.effectiveRecipe(group, table).getInputs().get(0).getEmiStacks().get(0).id.equals("birch_plank"),
			"reversing producer bookmark order deterministically chooses birch instead of candidate order");
		var probabilistic = new CandidateIngredient(List.of(stack("oak_plank", 9), stack("birch_plank", 7)), 3);
		probabilistic.setChance(0.25f);
		Recipe chanceTable = recipe("test:chance_tag_table", "chance_table", 1, probabilistic);
		group = setup(chanceTable, oak);
		var chosenInput = EmiBookmarkGroups.effectiveRecipe(group, chanceTable).getInputs().get(0);
		check(chosenInput.getEmiStacks().get(0).id.equals("oak_plank")
			&& chosenInput.getAmount() == 3 && chosenInput.getChance() == 0.25f,
			"automatic selection normalizes candidate amount and chance to the original ingredient");
		check(probabilistic.getAmount() == 3 && probabilistic.getChance() == 0.25f
			&& probabilistic.getEmiStacks().get(0).getAmount() == 9
			&& probabilistic.getEmiStacks().get(0).getChance() == 1,
			"normalizing the effective candidate leaves source amount, chance and candidate untouched");
	}

	private static void ledgerChecks() {
		EmiStack tool = stack("tool", 1); tool.remainder(tool.copy());
		Recipe plates = recipe("test:plates", "plate", 1, stack("ore", 1), tool);
		var group = setup(plates);
		var anchor = EmiFavorites.favorites.get(0);
		EmiBookmarkGroups.adjust(group, anchor, 3);
		var rows = EmiBookmarkGroups.rows(group, null);
		check(row(rows, "tool", EmiBookmarkGroups.Role.INGREDIENT).amount == 1, "folded catalyst is one startup tool, never multiplied by four batches");
		check(row(rows, "ore", EmiBookmarkGroups.Role.INGREDIENT).amount == 4, "folded consumed input still scales by batches");
		check(row(rows, "tool", EmiBookmarkGroups.Role.REMAINDER).amount == 1, "reusable startup tool survives as remainder");
		var stock = new dev.emi.emi.api.recipe.EmiPlayerInventory(List.of(stack("plate", 2), stack("ore", 1), stack("tool", 1)));
		rows = EmiBookmarkGroups.rows(group, stock);
		var oreRow = row(rows, "ore", EmiBookmarkGroups.Role.INGREDIENT);
		check(oreRow.explicitAmount == 4 && oreRow.calculatedAmount == 4 && oreRow.remainingAmount == 1, "fold has separate explicit/full/remaining amounts after finished product and material stock allocation");
		check(row(rows, "tool", EmiBookmarkGroups.Role.INGREDIENT).remainingAmount == 0, "inventory catalyst eliminates outstanding startup tool");
		check(row(rows, "plate", EmiBookmarkGroups.Role.RESULT).remainingAmount == 2, "inventory finished output reduces recipe production");
		check(stock.inventory.values().stream().mapToLong(EmiStack::getAmount).sum() == 4, "projection never mutates caller inventory");

		EmiStack water = stack("water_bucket", 1); water.remainder(stack("bucket", 1));
		Recipe wet = recipe("test:wet", "wet", 1, water);
		Recipe dry = recipe("test:dry", "dry", 1, stack("wet", 1), stack("bucket", 1));
		group = setup(dry, wet);
		rows = EmiBookmarkGroups.rows(group, null);
		check(rows.stream().noneMatch(r -> r.ingredient.getEmiStacks().get(0).id.equals("bucket")), "internal container remainder finances downstream consumption and vanishes from box boundary");
		check(row(rows, "water_bucket", EmiBookmarkGroups.Role.INGREDIENT).amount == 1, "container source remains external input");
		group = setup(wet);
		rows = EmiBookmarkGroups.rows(group, null);
		check(row(rows, "bucket", EmiBookmarkGroups.Role.REMAINDER).amount == 1, "unused container return is a remainder role");

		EmiStack lucky = stack("lucky", 1); lucky.setChance(0.5f);
		EmiStack dust = stack("dust", 2); dust.setChance(0.25f);
		Recipe random = new Recipe("test:random", List.of(dust), List.of(lucky));
		group = setup(random);
		rows = EmiBookmarkGroups.rows(group, null);
		check(row(rows, "lucky", EmiBookmarkGroups.Role.RESULT).amount == 1, "probability output uses expected yield rather than nominal batch multiplication");
		check(batches(EmiBookmarkGroups.executionRows(group, null), random) == 2, "half chance output needs two whole execution batches");
		check(row(rows, "dust", EmiBookmarkGroups.Role.INGREDIENT).amount == 2, "probabilistic input respects minimum single-slot startup material");

		EmiStack expectedReturn = stack("spent", 1); expectedReturn.setChance(0.25f);
		Recipe coProduct = new Recipe("test:coproduct", List.of(stack("raw", 1)), List.of(stack("main", 1), expectedReturn));
		group = setup(coProduct);
		EmiBookmarkGroups.adjust(group, EmiFavorites.favorites.get(0), 3);
		rows = EmiBookmarkGroups.rows(group, null);
		check(row(rows, "spent", EmiBookmarkGroups.Role.RESULT).amount == 1, "probabilistic coproduct aggregates expected units across batches before rounding");
		check(row(rows, "main", EmiBookmarkGroups.Role.RESULT).amount == 4, "deterministic output retains its full production count beside probabilistic coproduct");

		Recipe sharedA = recipe("test:sharedA", "a", 1, stack("raw", 1));
		Recipe sharedB = recipe("test:sharedB", "b", 1, stack("raw", 1));
		group = setup(sharedA, sharedB);
		EmiBookmarkGroups.toggleRecipe(group, sharedB);
		stock = new dev.emi.emi.api.recipe.EmiPlayerInventory(List.of(stack("raw", 1)));
		rows = EmiBookmarkGroups.rows(group, stock);
		check(rows.stream().filter(r -> r.input && r.ingredient.getEmiStacks().get(0).id.equals("raw")).mapToLong(r -> r.remainingAmount).sum() == 1,
			"multiple folded boxes allocate shared inventory exactly once across the group ledger");
		check(rows.stream().filter(r -> r.input && r.ingredient.getEmiStacks().get(0).id.equals("raw")).mapToLong(r -> r.calculatedAmount).sum() == 2,
			"inventory-free quantity stays unchanged while shared inventory reduces net demand");

		var candidates = new CandidateIngredient(List.of(stack("oak", 1), stack("birch", 1)), 2);
		Recipe producer = recipe("test:tagproducer", "intermediate", 1, candidates);
		Recipe consumer = recipe("test:tagconsumer", "final", 1, stack("intermediate", 1));
		group = setup(consumer, producer);
		rows = EmiBookmarkGroups.rows(group, null);
		var foldedInput = row(rows, "oak", EmiBookmarkGroups.Role.INGREDIENT);
		check(foldedInput.source == candidates && foldedInput.sourceAnchor.getRecipe() == producer && foldedInput.anchor.getRecipe() == consumer, "fold preserves real ingredient provenance independently from box display anchor");
		EmiBookmarkGroups.cycleIngredient(foldedInput, 1);
		rows = EmiBookmarkGroups.rows(group, null);
		check(row(rows, "birch", EmiBookmarkGroups.Role.INGREDIENT).amount == 2, "folded candidate can be changed using retained original source");
		check(EmiBookmarkGroups.effectiveRecipe(group, producer).getInputs().get(0).getEmiStacks().get(0).id.equals("birch"), "fill recipe wrapper uses selected folded ingredient");
		check(candidates.getEmiStacks().size() == 2 && candidates.getAmount() == 2, "effective recipe leaves plugin shape and amount unchanged");
		var wrapper = EmiBookmarkGroups.effectiveRecipe(group, producer);
		check(wrapper.getInputs().size() == producer.getInputs().size() && wrapper.getInputs().get(0).getAmount() == 2
			&& wrapper.getOutputs() == producer.getOutputs(), "effective fill recipe preserves input shape, amounts, and original outputs");
		check(EmiBookmarkGroups.effectiveRecipe(group, consumer).getInputs().get(0) == consumer.getInputs().get(0),
			"unchosen original input objects pass through effective recipe unchanged");
		var snapshot = EmiBookmarkGroups.tree(group);
		check(snapshot != null && snapshot.goal.children.size() == 1 && snapshot.goal.children.get(0).recipe != null, "group tree snapshot resolves selected recipes");
		long revision = EmiBookmarkGroups.revision();
		EmiBookmarkGroups.removeRepresented(rows.get(0));
		check(EmiFavorites.favorites.isEmpty() && EmiBookmarkGroups.revision() == revision + 1, "folded removal deletes represented identities and invalidates once");
	}

	private record MoveFixture(List<EmiFavorite> anchors, EmiBookmarkGroups.Group a,
			EmiBookmarkGroups.Group b, EmiBookmarkGroups.Group c) {}

	private static MoveFixture moveFixture() {
		EmiFavorites.favorites.clear();
		EmiBookmarkGroups.load(null);
		List<EmiFavorite> anchors = java.util.stream.IntStream.range(0, 8)
			.mapToObj(i -> new EmiFavorite(stack("move_" + i, i + 1), null)).toList();
		EmiFavorites.favorites.addAll(anchors);
		EmiBookmarkGroups.group(anchors.subList(0, 2));
		EmiBookmarkGroups.group(anchors.subList(2, 4));
		EmiBookmarkGroups.group(anchors.subList(4, 6));
		return new MoveFixture(anchors, EmiBookmarkGroups.groupOf(anchors.get(0)),
			EmiBookmarkGroups.groupOf(anchors.get(2)), EmiBookmarkGroups.groupOf(anchors.get(4)));
	}

	private static boolean sameOrder(List<EmiFavorite> expected) {
		if (EmiFavorites.favorites.size() != expected.size()) return false;
		for (int i = 0; i < expected.size(); i++) if (EmiFavorites.favorites.get(i) != expected.get(i)) return false;
		return true;
	}

	private static void moveNoop(Runnable action, String message) {
		var order = List.copyOf(EmiFavorites.favorites);
		var metadata = EmiBookmarkGroups.save();
		long revision = EmiBookmarkGroups.revision();
		action.run();
		check(sameOrder(order) && EmiBookmarkGroups.revision() == revision
			&& EmiBookmarkGroups.save().equals(metadata), message);
	}

	private static void moveGroupChecks() {
		for (boolean forward : List.of(false, true)) for (boolean after : List.of(false, true)) {
			var fixture = moveFixture();
			var source = forward ? fixture.a : fixture.c;
			var target = forward ? fixture.c : fixture.a;
			var moving = List.copyOf(source.anchors());
			var targetAnchors = List.copyOf(target.anchors());
			var expected = new java.util.ArrayList<>(fixture.anchors);
			expected.removeIf(f -> moving.stream().anyMatch(m -> m == f));
			int boundary = expected.indexOf(targetAnchors.get(after ? 1 : 0)) + (after ? 1 : 0);
			expected.addAll(boundary, moving);
			long revision = EmiBookmarkGroups.revision();
			EmiBookmarkGroups.moveGroup(source, target, after);
			String direction = "forward=" + forward + ", after=" + after;
			check(sameOrder(expected), "whole-group boundary move keeps both anchor orders without splitting target: " + direction);
			check(EmiBookmarkGroups.revision() == revision + 1,
				"group move invalidates exactly once only when order changes: " + direction);
			check(moving.stream().allMatch(f -> EmiBookmarkGroups.groupOf(f) == source)
				&& targetAnchors.stream().allMatch(f -> EmiBookmarkGroups.groupOf(f) == target),
				"group boundary move preserves source and target membership: " + direction);
			moveNoop(() -> EmiBookmarkGroups.moveGroup(source, target, after),
				"repeating boundary insertion is a strict order/metadata/revision no-op: " + direction);
		}
		var adjacent = moveFixture();
		moveNoop(() -> EmiBookmarkGroups.moveGroup(adjacent.a, adjacent.b, false),
			"already immediately before target is a strict no-op");
		moveNoop(() -> EmiBookmarkGroups.moveGroup(adjacent.c, adjacent.b, true),
			"already immediately after target is a strict no-op");
		var fixture = moveFixture();
		var all = fixture.anchors;
		long revision = EmiBookmarkGroups.revision();
		EmiBookmarkGroups.moveGroup(fixture.c, fixture.a);
		check(sameOrder(List.of(all.get(4), all.get(5), all.get(0), all.get(1), all.get(2), all.get(3), all.get(6), all.get(7)))
			&& EmiBookmarkGroups.revision() == revision + 1, "legacy two-argument group move inserts before target");
		for (boolean after : List.of(false, true)) {
			fixture = moveFixture();
			all = fixture.anchors;
			var source = fixture.a;
			revision = EmiBookmarkGroups.revision();
			EmiBookmarkGroups.moveGroup(source, null, after);
			check(sameOrder(List.of(all.get(2), all.get(3), all.get(4), all.get(5), all.get(6), all.get(7), all.get(0), all.get(1)))
				&& EmiBookmarkGroups.revision() == revision + 1, "null target appends all source anchors regardless of after=" + after);
			moveNoop(() -> EmiBookmarkGroups.moveGroup(source, null, after), "repeated end insertion is a strict no-op, after=" + after);
		}
		fixture = moveFixture();
		var source = fixture.a;
		var target = fixture.b;
		moveNoop(() -> EmiBookmarkGroups.moveGroup(source, source, false), "same-group before is a strict no-op");
		moveNoop(() -> EmiBookmarkGroups.moveGroup(source, source, true), "same-group after is a strict no-op");
		moveNoop(() -> EmiBookmarkGroups.moveGroup(null, target, true), "null source is a strict no-op");
		var stale = source;
		EmiBookmarkGroups.load(EmiBookmarkGroups.save());
		var liveSource = EmiBookmarkGroups.groupOf(fixture.anchors.get(0));
		var liveTarget = EmiBookmarkGroups.groupOf(fixture.anchors.get(2));
		moveNoop(() -> EmiBookmarkGroups.moveGroup(stale, liveTarget, true), "stale source group is rejected without revision change");
		moveNoop(() -> EmiBookmarkGroups.moveGroup(liveSource, stale, true), "stale target group is rejected instead of appending");
		EmiBookmarkGroups.ungroup(liveTarget.anchors());
		moveNoop(() -> EmiBookmarkGroups.moveGroup(liveSource, liveTarget, false), "empty target group is rejected instead of appending");
		moveNoop(() -> EmiBookmarkGroups.moveGroup(liveTarget, liveSource, true), "empty source group is a strict no-op");

		fixture = moveFixture();
		all = fixture.anchors;
		var defaults = EmiBookmarkGroups.defaultGroup();
		EmiBookmarkGroups.moveGroup(defaults, fixture.b, false);
		check(sameOrder(List.of(all.get(0), all.get(1), all.get(6), all.get(7), all.get(2), all.get(3), all.get(4), all.get(5))),
			"default source moves all ordinary anchors before the whole named target");
		check(EmiBookmarkGroups.groupOf(all.get(6)) == defaults && EmiBookmarkGroups.groupOf(all.get(7)) == defaults
			&& fixture.b.anchors().equals(all.subList(2, 4)), "default members remain default and named target membership is unchanged");
		EmiBookmarkGroups.moveGroup(fixture.a, defaults, true);
		check(sameOrder(List.of(all.get(6), all.get(7), all.get(0), all.get(1), all.get(2), all.get(3), all.get(4), all.get(5))),
			"default target after inserts after its last anchor without splitting its members");
		fixture = moveFixture();
		all = fixture.anchors;
		// Ordinary favorites can be separated by named groups in the saved favorites list.
		EmiFavorites.favorites.clear();
		EmiFavorites.favorites.addAll(List.of(all.get(6), all.get(0), all.get(1), all.get(7), all.get(2), all.get(3), all.get(4), all.get(5)));
		EmiBookmarkGroups.favoritesChanged();
		EmiBookmarkGroups.moveGroup(defaults, fixture.c, true);
		check(sameOrder(List.of(all.get(0), all.get(1), all.get(2), all.get(3), all.get(4), all.get(5), all.get(6), all.get(7)))
			&& defaults.anchors().equals(all.subList(6, 8)), "scattered default source collects all anchors in original order without changing membership");
		EmiFavorites.favorites.clear();
		EmiFavorites.favorites.addAll(List.of(all.get(6), all.get(0), all.get(1), all.get(7), all.get(2), all.get(3), all.get(4), all.get(5)));
		EmiBookmarkGroups.favoritesChanged();
		EmiBookmarkGroups.moveGroup(fixture.c, defaults, true);
		check(sameOrder(List.of(all.get(6), all.get(0), all.get(1), all.get(7), all.get(4), all.get(5), all.get(2), all.get(3))),
			"after scattered default target uses last target anchor and never inserts inside its boundary");
		EmiBookmarkGroups.moveGroup(fixture.c, defaults, false);
		check(sameOrder(List.of(all.get(4), all.get(5), all.get(6), all.get(0), all.get(1), all.get(7), all.get(2), all.get(3))),
			"before scattered default target uses first target anchor and never inserts inside its boundary");
		moveGroupPersistenceChecks();
	}

	private static void moveGroupPersistenceChecks() {
		EmiFavorites.favorites.clear();
		EmiBookmarkGroups.load(null);
		var candidates = new CandidateIngredient(List.of(stack("move_oak", 1), stack("move_birch", 1)), 3);
		var recipe = recipe("test:move_candidates", "move_result", 2, candidates);
		EmiBookmarkGroups.addRecipe(recipe, false);
		var root = EmiFavorites.favorites.get(0);
		var child = new EmiFavorite(stack("move_child", 2), null);
		var targetA = new EmiFavorite(stack("move_target_a", 1), null);
		var targetB = new EmiFavorite(stack("move_target_b", 1), null);
		var ordinary = new EmiFavorite(stack("move_default", 4), null);
		EmiFavorites.favorites.addAll(List.of(child, targetA, targetB, ordinary));
		EmiBookmarkGroups.group(List.of(root, child));
		var source = EmiBookmarkGroups.groupOf(root);
		EmiBookmarkGroups.group(List.of(targetA, targetB));
		var target = EmiBookmarkGroups.groupOf(targetA);
		EmiBookmarkGroups.toggleView(source);
		EmiBookmarkGroups.toggleCrafting(source);
		EmiBookmarkGroups.adjust(source, root, 4);
		EmiBookmarkGroups.adjust(source, child, 2);
		var input = EmiBookmarkGroups.rows(source, null).stream().filter(r -> r.input).findFirst().orElseThrow();
		EmiBookmarkGroups.cycleIngredient(input, 1);
		EmiBookmarkGroups.toggleRecipe(source, recipe);
		EmiBookmarkGroups.toggleCollapsed(source);
		EmiBookmarkGroups.toggleView(target);
		EmiBookmarkGroups.toggleCollapsed(target);
		String sourceId = source.id, targetId = target.id;
		long revision = EmiBookmarkGroups.revision();
		EmiBookmarkGroups.moveGroup(source, target, true);
		var expected = List.of(targetA, targetB, root, child, ordinary);
		check(sameOrder(expected) && EmiBookmarkGroups.revision() == revision + 1,
			"collapsed group move carries all hidden anchors once and leaves target contiguous");
		check(source.todo && source.crafting && source.collapsed && source.collapsedRecipes.contains(recipe.getId().toString())
			&& target.todo && target.collapsed && !target.crafting, "moving preserves independent source and target presentation states");
		check(EmiBookmarkGroups.groupOf(root) == source && EmiBookmarkGroups.groupOf(child) == source
			&& EmiBookmarkGroups.groupOf(targetA) == target && EmiBookmarkGroups.groupOf(targetB) == target
			&& EmiBookmarkGroups.groupOf(ordinary) == EmiBookmarkGroups.defaultGroup(), "moving never merges any group or adopts default membership");
		check(EmiBookmarkGroups.multiplier(root) == 5 && EmiBookmarkGroups.multiplier(child) == 3
			&& EmiBookmarkGroups.isFullRecipe(root), "moving retains each anchor multiplier and full-recipe flag");
		check(EmiBookmarkGroups.effectiveRecipe(source, recipe).getInputs().get(0).getEmiStacks().get(0).id.equals("move_birch")
			&& recipe.getInputs().get(0) == candidates && candidates.getEmiStacks().size() == 2 && candidates.getAmount() == 3,
			"moving retains selected candidate and original plugin candidate ingredient");
		var saved = EmiBookmarkGroups.save();
		EmiBookmarkGroups.load(saved);
		source = EmiBookmarkGroups.groupOf(root);
		target = EmiBookmarkGroups.groupOf(targetA);
		check(sameOrder(expected) && source.id.equals(sourceId) && target.id.equals(targetId)
			&& source.todo && source.crafting && source.collapsed && source.collapsedRecipes.contains(recipe.getId().toString())
			&& target.todo && target.collapsed && !target.crafting, "post-move JSON reload preserves group identities, states and favorite object order");
		check(EmiBookmarkGroups.groupOf(child) == source && EmiBookmarkGroups.groupOf(targetB) == target
			&& EmiBookmarkGroups.groupOf(ordinary) == EmiBookmarkGroups.defaultGroup()
			&& EmiBookmarkGroups.multiplier(root) == 5 && EmiBookmarkGroups.multiplier(child) == 3
			&& EmiBookmarkGroups.isFullRecipe(root), "post-move JSON reload preserves all memberships, multipliers and full-recipe metadata");
		check(EmiBookmarkGroups.effectiveRecipe(source, recipe).getInputs().get(0).getEmiStacks().get(0).id.equals("move_birch")
			&& recipe.getInputs().get(0) == candidates && candidates.getEmiStacks().size() == 2,
			"post-move JSON reload preserves candidate override without replacing plugin source");
	}

	public static void main(String[] args) throws Exception {
		Recipe planks = recipe("test:planks", "plank", 4, stack("log", 1));
		Recipe chest = recipe("test:chest", "chest", 1, stack("plank", 8));
		Recipe sticks = recipe("test:sticks", "stick", 4, stack("plank", 2));
		EmiFavorite plankFavorite = new EmiFavorite(stack("plank", 4), planks);
		EmiFavorite chestFavorite = new EmiFavorite(stack("chest", 1), chest);
		EmiFavorite stickFavorite = new EmiFavorite(stack("stick", 4), sticks);
		EmiFavorites.favorites.addAll(List.of(chestFavorite, stickFavorite, plankFavorite));
		EmiBookmarkGroups.load(null);
		EmiBookmarkGroups.group(List.of(chestFavorite, stickFavorite, plankFavorite));
		var group = EmiBookmarkGroups.groupOf(chestFavorite);
		EmiBookmarkGroups.toggleCrafting(group);

		var rows = EmiBookmarkGroups.executionRows(group, null);
		check(batches(rows, planks) == 3, "shared plank demand 10 needs three batches");
		EmiBookmarkGroups.adjust(group, plankFavorite, 1);
		check(batches(EmiBookmarkGroups.executionRows(group, null), planks) == 4,
			"intermediate multiplier contributes an additional batch");
		EmiBookmarkGroups.adjust(group, null, 1);
		rows = EmiBookmarkGroups.executionRows(group, null);
		check(batches(rows, chest) == 2 && batches(rows, sticks) == 2,
			"whole-group quantity adjustment changes the roots");

		long beforeFold = batches(rows, planks);
		EmiBookmarkGroups.toggleRecipe(group, chest);
		EmiBookmarkGroups.toggleRecipe(group, sticks);
		EmiBookmarkGroups.toggleView(group);
		var visible = EmiBookmarkGroups.rows(group, null);
		check(visible.stream().noneMatch(row -> row.recipe == planks),
			"shared producer belongs to one collapsed box and is hidden internally");
		check(visible.stream().anyMatch(row -> row.input && row.ingredient.getEmiStacks().get(0).id.equals("log")),
			"collapsed box exposes external log input");
		check(visible.stream().anyMatch(row -> !row.input && row.ingredient.getEmiStacks().get(0).id.equals("plank")),
			"collapsed box exposes plank output shared with the other box");
		check(batches(EmiBookmarkGroups.executionRows(group, null), planks) == beforeFold,
			"recipe-box folding never changes the execution ledger");
		EmiBookmarkGroups.toggleCollapsed(group);
		check(!EmiBookmarkGroups.rows(group, null).isEmpty(), "group folding retains final products");
		check(batches(EmiBookmarkGroups.executionRows(group, null), planks) == beforeFold,
			"group folding never changes execution quantities");

		var json = EmiBookmarkGroups.save();
		String groupId = group.id;
		EmiBookmarkGroups.load(json);
		group = EmiBookmarkGroups.groupOf(chestFavorite);
		check(group.id.equals(groupId) && group.crafting && group.collapsed && group.todo,
			"group identity and presentation settings survive JSON round trip");
		check(EmiBookmarkGroups.multiplier(plankFavorite) == 2, "quantity survives JSON round trip");
		EmiBookmarkGroups.addRecipe(chest, false);
		check(EmiBookmarkGroups.groupOf(chestFavorite) == group,
			"explicitly assigned group is not stolen by repeated recipe bookmarking");
		EmiBookmarkGroups.load(null);
		check(EmiBookmarkGroups.groupOf(chestFavorite) == EmiBookmarkGroups.defaultGroup()
			&& EmiFavorites.favorites.size() == 3, "legacy metadata reset preserves ordinary favorites");
		EmiBookmarkGroups.addRecipe(chest, false);
		check(EmiBookmarkGroups.isFullRecipe(chestFavorite)
			&& EmiBookmarkGroups.groupOf(chestFavorite) != EmiBookmarkGroups.defaultGroup(),
			"explicit recipe action promotes an ungrouped ordinary favorite");
		EmiFavorites.favorites.clear();
		EmiBookmarkGroups.load(null);
		EmiIngredient alternatives = new EmiIngredient() {
			long amount = 2;
			public List<EmiStack> getEmiStacks() { return List.of(stack("oak", 1), stack("birch", 1)); }
			public EmiIngredient copy() {
				final long copied = amount;
				return new CandidateIngredient(getEmiStacks(), copied);
			}
			public long getAmount() { return amount; }
			public EmiIngredient setAmount(long value) { amount = value; return this; }
			public float getChance() { return 1; }
			public EmiIngredient setChance(float value) { return this; }
		};
		Recipe tagged = recipe("test:tagged", "result", 1, alternatives);
		Recipe birch = recipe("test:birch", "birch", 1, stack("seed", 1));
		EmiBookmarkGroups.addRecipe(tagged, false);
		var tagAnchor = EmiFavorites.favorites.get(0);
		var birchAnchor = new EmiFavorite(stack("birch", 1), birch);
		EmiFavorites.favorites.add(birchAnchor);
		EmiBookmarkGroups.group(List.of(tagAnchor, birchAnchor));
		var tagGroup = EmiBookmarkGroups.groupOf(tagAnchor);
		EmiBookmarkGroups.toggleView(tagGroup);
		EmiBookmarkGroups.toggleCrafting(tagGroup);
		var inputRow = EmiBookmarkGroups.rows(tagGroup, null).stream().filter(row -> row.input).findFirst().orElseThrow();
		check(inputRow.ingredient.getEmiStacks().get(0).id.equals("birch"),
			"same-group birch producer is selected automatically before manual cycling");
		EmiBookmarkGroups.cycleIngredient(inputRow, -1);
		inputRow = EmiBookmarkGroups.rows(tagGroup, null).stream().filter(row -> row.recipe == tagged && row.input).findFirst().orElseThrow();
		check(inputRow.ingredient.getEmiStacks().get(0).id.equals("oak"),
			"candidate cycling starts from the effective automatic choice rather than candidate zero");
		EmiBookmarkGroups.cycleIngredient(inputRow, 1);
		var selectedRow = EmiBookmarkGroups.rows(tagGroup, null).stream().filter(row -> row.recipe == tagged && row.input).findFirst().orElseThrow();
		check(selectedRow.ingredient.getEmiStacks().get(0).id.equals("birch"), "candidate wheel selects a concrete display stack");
		check(selectedRow.source.getEmiStacks().size() == 2, "display selection retains original candidates");
		check(batches(EmiBookmarkGroups.executionRows(tagGroup, null), birch) == 2, "selected candidate drives the execution ledger");
		var choiceJson = EmiBookmarkGroups.save();
		EmiBookmarkGroups.load(choiceJson);
		tagGroup = EmiBookmarkGroups.groupOf(tagAnchor);
		selectedRow = EmiBookmarkGroups.rows(tagGroup, null).stream().filter(row -> row.recipe == tagged && row.input).findFirst().orElseThrow();
		check(selectedRow.ingredient.getEmiStacks().get(0).id.equals("birch"), "ingredient choice survives JSON round trip");
		EmiBookmarkGroups.cycleIngredient(selectedRow, -1);
		selectedRow = EmiBookmarkGroups.rows(tagGroup, null).stream().filter(row -> row.recipe == tagged && row.input).findFirst().orElseThrow();
		check(selectedRow.ingredient.getEmiStacks().get(0).id.equals("oak"), "reverse wheel uses retained candidates");
		check(tagged.getInputs().get(0).getEmiStacks().size() == 2, "plugin recipe is not replaced or mutated");
		EmiFavorites.favorites.clear();
		EmiBookmarkGroups.load(null);
		EmiStack tool = stack("hammer", 1);
		tool.remainder(tool.copy());
		EmiRecipe tooling = new EmiRecipe() {
			public Identifier getId() { return new Identifier("test:tooling"); }
			public List<EmiIngredient> getInputs() { return List.of(stack("ore", 1), tool); }
			public List<EmiStack> getOutputs() { return List.of(stack("plate", 1)); }
			public List<EmiIngredient> getCatalysts() { return List.of(tool); }
			public boolean supportsRecipeTree() { return false; }
		};
		EmiBookmarkGroups.addRecipe(tooling, false);
		var toolAnchor = EmiFavorites.favorites.get(0);
		var toolGroup = EmiBookmarkGroups.groupOf(toolAnchor);
		EmiBookmarkGroups.adjust(toolGroup, toolAnchor, 2);
		java.lang.reflect.Method modelMethod = EmiBookmarkGroups.class.getDeclaredMethod("model", java.util.Map.class, dev.emi.emi.api.recipe.EmiPlayerInventory.class);
		modelMethod.setAccessible(true);
		java.util.Map<EmiRecipe, EmiFavorite> selected = new java.util.LinkedHashMap<>();
		selected.put(tooling, toolAnchor);
		var toolModel = (dev.emi.emi.bom.CraftingTreeModel) modelMethod.invoke(null, selected, null);
		var effectiveTooling = toolModel.entries.stream().map(entry -> entry.recipe)
			.filter(value -> value != null && value.getId() != null && value.getId().toString().equals("test:tooling"))
			.findFirst().orElseThrow();
		check(effectiveTooling != tooling && effectiveTooling.getCatalysts().equals(tooling.getCatalysts()),
			"effective wrapper forwards catalyst metadata without modifying original recipe");
		check(!effectiveTooling.supportsRecipeTree(), "effective wrapper preserves recipe-tree support policy");
		check(toolModel.missing.stream().anyMatch(cost -> cost.ingredient.getEmiStacks().get(0).id.equals("hammer") && cost.amount == 1),
			"three recipe batches retain exactly one reusable startup tool cost");
		EmiFavorites.favorites.clear();
		EmiBookmarkGroups.load(null);
		EmiFavorite moveChest = new EmiFavorite(stack("chest", 1), chest);
		EmiFavorite movePlanks = new EmiFavorite(stack("plank", 4), planks);
		EmiFavorite moveSticks = new EmiFavorite(stack("stick", 4), sticks);
		EmiFavorite target = new EmiFavorite(stack("target", 1), null);
		EmiFavorites.favorites.addAll(List.of(moveChest, movePlanks, moveSticks, target));
		EmiBookmarkGroups.group(List.of(moveChest, movePlanks, moveSticks));
		var sourceGroup = EmiBookmarkGroups.groupOf(moveChest);
		EmiBookmarkGroups.group(List.of(target));
		var targetGroup = EmiBookmarkGroups.groupOf(target);
		EmiBookmarkGroups.toggleView(targetGroup);
		EmiBookmarkGroups.toggleRecipe(sourceGroup, chest);
		var represented = EmiBookmarkGroups.representedAnchors(sourceGroup, moveChest);
		check(represented.size() == 2 && represented.get(0) == moveChest && represented.get(1) == movePlanks,
			"folded subtree represents root plus hidden dependency anchors in real order");
		EmiBookmarkGroups.move(represented, target);
		check(EmiFavorites.favorites.get(1) == moveChest && EmiFavorites.favorites.get(2) == movePlanks && EmiFavorites.favorites.get(3) == target,
			"folded subtree moves both true identities before destination");
		check(EmiBookmarkGroups.groupOf(moveChest) == targetGroup && EmiBookmarkGroups.groupOf(movePlanks) == targetGroup,
			"cross-group move adopts destination membership");
		check(EmiBookmarkGroups.groupOf(moveSticks) == sourceGroup && targetGroup.todo && sourceGroup.collapsedRecipes.contains(chest.getId().toString()),
			"cross-group move preserves unrelated anchors and all group presentation states");
		long beforeNoop = EmiBookmarkGroups.revision();
		EmiBookmarkGroups.move(List.of(moveChest, movePlanks), moveChest);
		check(EmiBookmarkGroups.revision() == beforeNoop && EmiFavorites.favorites.get(1) == moveChest,
			"source containing target is a strict no-op");
		EmiBookmarkGroups.move(List.of(moveChest, movePlanks), null);
		check(EmiFavorites.favorites.get(2) == moveChest && EmiFavorites.favorites.get(3) == movePlanks
			&& EmiBookmarkGroups.groupOf(moveChest) == EmiBookmarkGroups.defaultGroup()
			&& EmiBookmarkGroups.groupOf(movePlanks) == EmiBookmarkGroups.defaultGroup(),
			"null target appends in real order and restores default membership");
		EmiBookmarkGroups.toggleCollapsed(sourceGroup);
		check(EmiBookmarkGroups.representedAnchors(sourceGroup, moveSticks).equals(List.of(moveSticks)),
			"collapsed group resolves all remaining real anchors");
		EmiFavorite imposter = new EmiFavorite(stack("stick", 4), sticks);
		beforeNoop = EmiBookmarkGroups.revision();
		EmiBookmarkGroups.move(List.of(imposter), target);
		check(EmiBookmarkGroups.revision() == beforeNoop, "equal-content object outside favorites cannot move a real anchor");
		ledgerChecks();
		plainFavoriteChecks();
		shapedEmptyChecks();
		tagProducerFlowChecks();
		moveGroupChecks();
		System.out.println("Bookmark group checks passed: " + checks);
	}
}
