package dev.emi.emi.bom;

import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiResolutionRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;

import java.util.*;

/**
 * A snapshot of a material tree. Inventory and material nodes are never mutated.
 */
public class CraftingTreeModel {
    public final List<Entry> entries = new ArrayList<>();
    public final List<Link> links = new ArrayList<>();
    /**
     * Raw requirements without inventory, and raw requirements after using inventory.
     */
    public final List<FlatMaterialCost> required;
    public final List<FlatMaterialCost> missing;
    /**
     * Player inventory actually allocated (including reusable catalysts and intermediates).
     */
    public final List<FlatMaterialCost> available;
    /**
     * Surplus produced by the plan; unused player inventory is deliberately excluded.
     */
    public final List<FlatMaterialCost> leftovers;
    /**
     * Inventory-adjusted recipe batches, in dependency-first order.
     */
    public final List<Step> steps;
    public final EmiIngredient result;
    public final long resultAmount;
    private final List<Entry> order = new ArrayList<>();

    public CraftingTreeModel(MaterialTree tree, EmiPlayerInventory inventory) {
        result = tree.goal.ingredient.copy();
        resultAmount = multiply(Math.max(0, tree.batches), Math.max(0, tree.goal.amount));
        build(tree.goal, Collections.newSetFromMap(new IdentityHashMap<>()));
        sort();
        Plan full = calculate(null, false);
        Plan remaining = inventory == null ? full : calculate(inventory, true);
        required = full.costs;
        missing = remaining.costs;
        available = remaining.available;
        leftovers = remaining.leftovers;
        steps = remaining.steps;
        if (inventory == null) {
            for (Entry e : entries) {
                e.needed = e.total;
                e.neededBatches = e.totalBatches;
            }
        }
    }

    public static class Entry {
        public final EmiIngredient ingredient;
        public final EmiRecipe recipe;
        public long total, needed;
        public int depth;
        public boolean catalyst, cycle, truncated;
        public float chance = 1;
        public long totalBatches, neededBatches;
        public final List<Entry> children = new ArrayList<>();
        private final MaterialNode node;
        private final List<Link> outgoing = new ArrayList<>();

        private Entry(MaterialNode node) {
            this.node = node;
            ingredient = node.ingredient.copy();
            recipe = node.recipe;
            catalyst = node.catalyst;
            cycle = node.cycle;
            truncated = node.truncated;
        }
    }

    public static class Link {
        public final Entry parent, child;
        public final long amount;
        public final float chance;
        public final boolean catalyst;
        private final EmiStack remainder;
        private final long remainderAmount, minBatch;

        private Link(Entry parent, Entry child, MaterialNode input) {
            this.parent = parent;
            this.child = child;
            amount = input.amount;
            chance = input.consumeChance;
            catalyst = input.catalyst;
            remainder = input.remainder;
            remainderAmount = input.remainderAmount;
            minBatch = input.minBatch;
        }
    }

    public record Step(EmiRecipe recipe, long batches) {
    }

    private Entry build(MaterialNode node, Set<MaterialNode> path) {
        for (Entry entry : entries) {
            if (entry.recipe == node.recipe && EmiIngredient.areEqual(entry.ingredient, node.ingredient)) {
                entry.catalyst |= node.catalyst;
                entry.cycle |= node.cycle;
                entry.truncated |= node.truncated;
                return entry;
            }
        }
        Entry entry = new Entry(node);
        entries.add(entry);
        if (node.children == null || !path.add(node)) {
            return entry;
        }
        for (MaterialNode input : node.children) {
            Entry child = build(input, path);
            // A manually built tree may itself contain back references. Keep the graph a DAG.
            if (child == entry || reaches(child, entry, Collections.newSetFromMap(new IdentityHashMap<>()))) {
                entry.cycle = true;
                continue;
            }
            Link link = new Link(entry, child, input);
            entry.outgoing.add(link);
            links.add(link);
            if (!entry.children.contains(child)) {
                entry.children.add(child);
            }
            if (input.recipe == null && entry.recipe != null && input.ingredient != null) {
                // MaterialNode cuts recipe cycles into unresolved leaves.
                for (MaterialNode ancestor : path) {
                    if (ancestor.recipe != null && EmiIngredient.areEqual(ancestor.ingredient, input.ingredient)) {
                        child.cycle = true;
                    }
                }
            }
        }
        path.remove(node);
        return entry;
    }

    private boolean reaches(Entry from, Entry target, Set<Entry> seen) {
        if (from == target) return true;
        if (!seen.add(from)) return false;
        for (Entry child : from.children) {
            if (reaches(child, target, seen)) return true;
        }
        return false;
    }

    private void sort() {
        Map<Entry, Integer> incoming = new IdentityHashMap<>();
        for (Entry entry : entries) incoming.put(entry, 0);
        for (Link link : links) incoming.merge(link.child, 1, Integer::sum);
        List<Entry> ready = new ArrayList<>();
        for (Entry entry : entries) if (incoming.get(entry) == 0) ready.add(entry);
        while (!ready.isEmpty()) {
            // Process ready producers before consumers of their co-products where possible.
            int selected = 0;
            for (int i = 1; i < ready.size(); i++) {
                if (ready.get(i).ingredient.getEmiStacks().size() < ready.get(selected).ingredient.getEmiStacks().size())
                    selected = i;
            }
            for (int i = 0; i < ready.size(); i++) {
                Entry producer = ready.get(i);
                if (producer.recipe == null) continue;
                // Delay raw leaves until all ready recipe branches have exposed their co-products.
                if (ready.get(selected).recipe == null) selected = i;
                boolean useful = false;
                for (Entry other : ready) {
                    if (other != producer && produces(producer, other.ingredient,
                            Collections.newSetFromMap(new IdentityHashMap<>()))) useful = true;
                }
                if (useful) {
                    selected = i;
                    break;
                }
            }
            Entry entry = ready.remove(selected);
            order.add(entry);
            for (Link link : entry.outgoing) {
                link.child.depth = Math.max(link.child.depth, entry.depth + 1);
                if (incoming.merge(link.child, -1, Integer::sum) == 0) ready.add(link.child);
            }
        }
    }

    private boolean produces(Entry entry, EmiIngredient ingredient, Set<Entry> seen) {
        if (!seen.add(entry)) return false;
        if (entry.recipe != null) {
            for (EmiStack output : entry.recipe.getOutputs()) {
                if (EmiIngredient.areEqual(output, ingredient)) return true;
            }
        }
        for (Entry child : entry.children) if (produces(child, ingredient, seen)) return true;
        return false;
    }

    private Plan calculate(EmiPlayerInventory inventory, boolean progress) {
        Map<Entry, Demand> demands = new IdentityHashMap<>();
        for (Entry entry : entries) demands.put(entry, new Demand());
        demands.get(entries.getFirst()).add(resultAmount, resultAmount, false, false, 1, null);
        List<Stock> stock = new ArrayList<>();
        if (inventory != null) {
            for (EmiStack stack : inventory.inventory.values()) {
                stock.add(new Stock(stack, stack.getAmount(), false, null));
            }
        }
        Plan plan = new Plan();
        Map<EmiRecipe, Long> batchesByRecipe = new LinkedHashMap<>();
        for (Entry entry : order) {
            Demand demand = demands.get(entry);
            double expected = demand.expected();
            long nominal = demand.nominal();
            if (progress) entry.needed = rounded(expected);
            else {
                entry.total = rounded(expected);
                entry.chance = nominal == 0 ? 1 : (float) (expected / nominal);
            }
            if (nominal == 0 || expected <= 0) continue;
            double given = take(stock, entry.ingredient, expected, demand, plan);
            double remaining = Math.max(0, expected - given);
            // Reserve reusable units separately from consumed units, including freshly crafted tools.
            if (demand.expectedCatalyst > 0)
                stock.add(new Stock(entry.ingredient, demand.expectedCatalyst, true, entry));
            if (progress) entry.needed = rounded(remaining);
            if (remaining <= 1.0e-9) continue;
            long outstanding = given == 0 ? nominal : Math.min(nominal, ceiling(remaining / (expected / nominal)));
            double factor = remaining / outstanding;
            if (entry.recipe == null || entry.node.produceChance <= 0 || entry.outgoing.isEmpty() && entry.cycle) {
                addCost(plan.costs, entry.ingredient, outstanding, factor, demand.chanced, Math.min(outstanding, demand.minBatch));
                // A catalyst is acquired once and can be shared by subsequent consumers.
                continue;
            }
            if (entry.recipe instanceof EmiResolutionRecipe) {
                for (Link link : entry.outgoing) {
                    demands.get(link.child).add(outstanding, remaining, link.catalyst, demand.chanced, link.minBatch, entry);
                }
                continue;
            }
            // Execute whole recipe batches using their expected output; never fractional crafts.
            long batches = ceiling(remaining / (Math.max(1, entry.node.divisor) * (double) entry.node.produceChance));
            double producedFactor = 1;
            boolean chanced = demand.chanced || entry.node.produceChance != 1;
            if (progress) entry.neededBatches = rounded(batches * producedFactor);
            else entry.totalBatches = rounded(batches * producedFactor);
            batchesByRecipe.merge(entry.recipe, rounded(batches * producedFactor), CraftingTreeModel::sum);
            for (Part part : demand.parts) if (part.source != null) plan.before(entry.recipe, part.source.recipe);
            for (Link link : entry.outgoing) {
                long amount = link.catalyst ? link.amount : multiply(batches, link.amount);
                double inputFactor = link.catalyst ? 1 : producedFactor * link.chance;
                demands.get(link.child).add(amount, amount * inputFactor, link.catalyst, chanced || link.chance != 1, link.minBatch, entry);
            }
            // Surplus of the requested output has the request's probability, not inverse output chance.
            double surplus = multiply(batches, entry.node.divisor) * (double) entry.node.produceChance - remaining;
            if (surplus > 0) stock.add(new Stock(entry.ingredient, surplus, true, entry));
            for (EmiStack output : entry.recipe.getOutputs()) {
                if (!EmiIngredient.areEqual(output, entry.ingredient)) {
                    stock.add(new Stock(output, batches * (double) output.getAmount() * producedFactor * output.getChance(), true, entry));
                }
            }
            for (Link link : entry.outgoing) {
                if (!link.catalyst && !link.remainder.isEmpty() && link.remainderAmount > 0) {
                    stock.add(new Stock(link.remainder, batches * (double) link.remainderAmount * producedFactor * link.chance, true, entry));
                }
            }
        }
        for (Stock item : stock) {
            if (item.produced && item.amount > 1.0e-9)
                addCost(plan.leftovers, item.ingredient, 1, item.amount, item.amount != Math.rint(item.amount), 0);
        }
        List<EmiRecipe> recipes = new ArrayList<>(batchesByRecipe.keySet());
        Collections.reverse(recipes);
        Set<EmiRecipe> emitted = Collections.newSetFromMap(new IdentityHashMap<>());
        while (!recipes.isEmpty()) {
            int selected = -1;
            for (int i = 0; i < recipes.size(); i++) {
                EmiRecipe candidate = recipes.get(i);
                boolean ready = true;
                for (EmiRecipe dependency : plan.dependencies.getOrDefault(candidate, List.of())) {
                    if (batchesByRecipe.containsKey(dependency) && !emitted.contains(dependency)) {
                        ready = false;
                        break;
                    }
                }
                if (ready) {
                    selected = i;
                    break;
                }
            }
            // Conflicting alternative plans may yield an execution cycle; retain a stable bounded result.
            if (selected < 0) selected = 0;
            EmiRecipe recipe = recipes.remove(selected);
            plan.steps.add(new Step(recipe, batchesByRecipe.get(recipe)));
            emitted.add(recipe);
        }
        return plan;
    }

    private double take(List<Stock> stock, EmiIngredient ingredient, double desired, Demand demand, Plan plan) {
        double given = 0;
        for (Part part : demand.parts) {
            double needed = part.expected;
            for (Stock item : stock) {
                // A recipe's output cannot finance that recipe's own startup inputs.
                if (item.source != null && part.source != null && reaches(item.source, part.source,
                        Collections.newSetFromMap(new IdentityHashMap<>()))) continue;
                boolean matches = false;
                for (EmiStack candidate : ingredient.getEmiStacks()) {
                    if (EmiIngredient.areEqual(candidate, item.ingredient)) {
                        matches = true;
                        break;
                    }
                }
                if (matches) {
                    double amount = Math.min(needed, item.amount);
                    given += amount;
                    needed -= amount;
                    item.amount -= amount;
                    if (amount > 0 && item.source != null && part.source != null)
                        plan.before(item.source.recipe, part.source.recipe);
                    if (!item.produced && amount > 0)
                        addCost(plan.available, item.ingredient, 1, amount, amount != Math.rint(amount), 0);
                    if (needed <= 1.0e-9) break;
                }
            }
        }
        return Math.min(desired, given);
    }

    private static void addCost(List<FlatMaterialCost> costs, EmiIngredient ingredient, long amount, double factor, boolean chanced, long minBatch) {
        if (amount <= 0 || factor <= 0) return;
        if (!chanced && factor == Math.rint(factor)) {
            amount = multiply(amount, rounded(factor));
            factor = 1;
        }
        for (int i = 0; i < costs.size(); i++) {
            FlatMaterialCost existing = costs.get(i);
            if (EmiIngredient.areEqual(existing.ingredient, ingredient)) {
                if (existing instanceof ChanceMaterialCost chance) {
                    long combined = sum(chance.amount, amount);
                    chance.chance = (float) ((chance.amount * (double) chance.chance + amount * factor) / combined);
                    chance.amount = combined;
                    chance.minBatch(minBatch);
                } else if (chanced || factor != 1) {
                    ChanceMaterialCost chance = new ExpectedCost(ingredient, existing.amount, 1);
                    long combined = sum(chance.amount, amount);
                    chance.chance = (float) ((chance.amount * (double) chance.chance + amount * factor) / combined);
                    chance.amount = combined;
                    chance.minBatch(minBatch);
                    costs.set(i, chance);
                } else existing.amount = sum(existing.amount, amount);
                return;
            }
        }
        if (chanced || factor != 1) {
            ChanceMaterialCost cost = new ExpectedCost(ingredient.copy(), amount, (float) factor);
            cost.minBatch = minBatch;
            costs.add(cost);
        } else costs.add(new FlatMaterialCost(ingredient.copy(), amount));
    }

    private static long sum(long a, long b) {
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    private static long multiply(long a, long b) {
        return a == 0 || b == 0 ? 0 : a > Long.MAX_VALUE / b ? Long.MAX_VALUE : a * b;
    }

    private static long ceiling(double value) {
        return value >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) Math.ceil(value - 1.0e-9);
    }

    private static long rounded(double value) {
        return value >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0, Math.round(value));
    }

    /**
     * ChanceMaterialCost compatibility without its float Math.round -> int overflow for large plans.
     */
    private static class ExpectedCost extends ChanceMaterialCost {
        ExpectedCost(EmiIngredient ingredient, long amount, float chance) {
            super(ingredient, amount, chance);
        }

        @Override
        public long getEffectiveAmount() {
            return Math.max(minBatch, rounded(amount * (double) chance));
        }
    }

    private static class Demand {
        long consumed, catalyst, minBatch;
        double expectedConsumed, expectedCatalyst;
        boolean chanced;
        final List<Part> parts = new ArrayList<>();

        void add(long nominal, double expected, boolean reusable, boolean chanced, long minBatch, Entry source) {
            if (reusable) {
                if (expected > expectedCatalyst) parts.add(new Part(expected - expectedCatalyst, source));
                catalyst = Math.max(catalyst, nominal);
                expectedCatalyst = Math.max(expectedCatalyst, expected);
            } else {
                parts.add(new Part(expected, source));
                consumed = sum(consumed, nominal);
                expectedConsumed += expected;
            }
            this.chanced |= chanced;
            this.minBatch = Math.max(this.minBatch, minBatch);
        }

        long nominal() {
            return sum(consumed, catalyst);
        }

        double expected() {
            return expectedConsumed + expectedCatalyst;
        }

        boolean catalystOnly() {
            return consumed == 0 && catalyst > 0;
        }
    }

    private record Part(double expected, Entry source) {
    }

    private static class Stock {
        final EmiIngredient ingredient;
        final boolean produced;
        final Entry source;
        double amount;

        Stock(EmiIngredient ingredient, double amount, boolean produced, Entry source) {
            this.source = source;
            this.ingredient = ingredient;
            this.amount = amount;
            this.produced = produced;
        }
    }

    private static class Plan {
        final List<FlatMaterialCost> costs = new ArrayList<>(), leftovers = new ArrayList<>(), available = new ArrayList<>();
        final List<Step> steps = new ArrayList<>();
        final Map<EmiRecipe, List<EmiRecipe>> dependencies = new IdentityHashMap<>();

        void before(EmiRecipe first, EmiRecipe second) {
            if (first != null && second != null && first != second) {
                List<EmiRecipe> list = dependencies.computeIfAbsent(second, k -> new ArrayList<>());
                if (!list.contains(first)) list.add(first);
            }
        }
    }
}
