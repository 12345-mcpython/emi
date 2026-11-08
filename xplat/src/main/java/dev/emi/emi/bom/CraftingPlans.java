package dev.emi.emi.bom;

import com.google.gson.*;
import dev.emi.emi.EmiPort;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiResolutionRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.serializer.EmiIngredientSerializer;
import dev.emi.emi.runtime.EmiLog;
import dev.emi.emi.runtime.EmiPersistentData;

import java.util.*;

/**
 * Named, explicitly saved crafting trees, independent of the recipe reload lifecycle.
 */
public final class CraftingPlans {
    public static final int MAX_PLANS = 256;
    public static final int MAX_IMPORT_LENGTH = 1_048_576;
    private static final int MAX_ENTRIES = 16384;
    private static final List<CraftingPlan> plans = new ArrayList<>();
    private static CraftingPlan current;

    private CraftingPlans() {
    }

    public static List<CraftingPlan> all() {
        return Collections.unmodifiableList(plans);
    }

    /**
     * Null for a temporary tree which has not been explicitly saved.
     */
    public static CraftingPlan current() {
        return current;
    }

    /**
     * Capture in memory only: in particular, never recursively calls persistent save.
     */
    public static void capture() {
        if (current != null && BoM.tree != null) {
            capture(current, BoM.tree);
        }
    }

    private static void capture(CraftingPlan plan, MaterialTree tree) {
        plan.batches = Math.max(1, tree.batches);
        plan.goalAmount = Math.max(1, tree.goal.amount);
        // Freeze the selected chain, not just manual overrides: later default changes
        // must not silently turn an explicitly saved plan into a different tree.
        Map<EmiIngredient, EmiRecipe> choices = new LinkedHashMap<>();
        captureNodes(tree.goal, tree, choices, new ArrayList<>());
        choices.putAll(tree.resolutions);
        JsonArray resolutions = new JsonArray();
        for (Map.Entry<EmiIngredient, EmiRecipe> entry : choices.entrySet()) {
            try {
                JsonElement ingredient = serialize(entry.getKey());
                JsonElement recipe = serializeRecipe(entry.getValue());
                if (ingredient != null && recipe != null) {
                    JsonObject resolution = new JsonObject();
                    resolution.add("ingredient", ingredient);
                    resolution.add("recipe", recipe);
                    resolutions.add(resolution);
                }
            } catch (RuntimeException e) {
                EmiLog.error("Failed to capture crafting plan resolution", e);
            }
        }
        plan.resolutions = resolutions;
    }

    private static void captureNodes(MaterialNode node, MaterialTree tree, Map<EmiIngredient, EmiRecipe> choices,
                                     List<EmiRecipe> path) {
        if (node == null) {
            return;
        }
        if (node.recipe == null) {
            if (node.truncated) {
                return;
            }
            // A cycle-cut node is not a globally terminated material. Preserve the
            // producer seen elsewhere rather than freezing the cut as a null override.
            EmiRecipe selected = tree.getRecipe(node.ingredient);
            if (selected == null || !path.contains(selected)) {
                choices.putIfAbsent(node.ingredient, null);
            }
            return;
        }
        choices.put(node.ingredient, node.recipe);
        if (path.contains(node.recipe)) {
            return;
        }
        path.add(node.recipe);
        if (node.children != null) {
            for (MaterialNode child : node.children) {
                captureNodes(child, tree, choices, path);
            }
        }
        path.removeLast();
    }

    public static void open(int index) {
        if (index < 0 || index >= plans.size()) {
            return;
        }
        capture();
        CraftingPlan plan = plans.get(index);
        MaterialTree tree = createTree(plan);
        if (tree != null) {
            current = plan;
            BoM.tree = tree;
            BoM.craftingMode = false;
            EmiPersistentData.save();
        }
    }

    public static void remove(int index) {
        if (index < 0 || index >= plans.size()) {
            return;
        }
        capture();
        if (plans.remove(index) == current) {
            current = null;
        }
        EmiPersistentData.save();
    }

    public static void rename(String name) {
        if (current != null && name != null && !name.isBlank()) {
            current.name = cleanName(name);
            EmiPersistentData.save();
        }
    }

    public static void saveCurrent() {
        if (BoM.tree == null) {
            return;
        }
        if (current == null) {
            if (plans.size() >= MAX_PLANS) {
                return;
            }
            current = snapshot();
            if (current == null) {
                return;
            }
            plans.add(current);
        } else {
            capture();
        }
        EmiPersistentData.save();
    }

    /**
     * Used by BoM only; changing goals never implicitly creates a saved plan.
     */
    static void setGoal(EmiRecipe recipe) {
        capture();
        current = null;
        JsonElement serialized = serializeRecipe(recipe);
        for (CraftingPlan plan : plans) {
            if (serialized != null && serialized.equals(plan.recipe)) {
                MaterialTree restored = createTree(plan);
                if (restored != null) {
                    current = plan;
                    BoM.tree = restored;
                    BoM.craftingMode = false;
                    return;
                }
            }
        }
        BoM.tree = recipe == null ? null : new MaterialTree(recipe);
        BoM.craftingMode = false;
    }

    public static JsonObject save() {
        capture();
        JsonObject json = new JsonObject();
        json.addProperty("version", 1);
        json.addProperty("current", current == null ? -1 : plans.indexOf(current));
        JsonArray entries = new JsonArray();
        for (CraftingPlan plan : plans) {
            entries.add(writePlan(plan));
        }
        json.add("plans", entries);
        return json;
    }

    /**
     * Missing legacy data means no plans; stale recipe/tree references are always discarded.
     */
    public static void load(JsonObject json) {
        plans.clear();
        current = null;
        BoM.tree = null;
        BoM.craftingMode = false;
        if (json == null || !json.has("plans") || !json.get("plans").isJsonArray()) {
            return;
        }
        int selected = -1;
        try {
            if (json.has("current")) {
                selected = json.get("current").getAsInt();
            }
        } catch (RuntimeException e) {
            EmiLog.error("Invalid current crafting plan", e);
        }
        int index = 0;
        for (JsonElement entry : json.getAsJsonArray("plans")) {
            if (plans.size() >= MAX_PLANS) {
                break;
            }
            try {
                CraftingPlan plan = readPlan(entry, false);
                MaterialTree tree = createTree(plan);
                if (tree != null) {
                    plans.add(plan);
                    if (index == selected) {
                        current = plan;
                        BoM.tree = tree;
                    }
                }
            } catch (RuntimeException e) {
                EmiLog.error("Skipping invalid crafting plan", e);
            }
            index++;
        }
    }

    public static String exportCurrent() {
        capture();
        CraftingPlan plan = current == null ? snapshot() : current;
        return plan == null ? "" : writePlan(plan).toString();
    }

    /**
     * Invalid imports never replace the current tree or modify the saved list.
     */
    public static boolean importPlan(String json) {
        if (json == null || json.length() > MAX_IMPORT_LENGTH || plans.size() >= MAX_PLANS) {
            EmiLog.warn("Rejected crafting plan import: input or plan limit");
            return false;
        }
        try {
            CraftingPlan plan = readPlan(JsonParser.parseString(json), true);
            MaterialTree tree = createTree(plan);
            if (tree == null) {
                throw new IllegalArgumentException("Missing goal recipe");
            }
            capture();
            plans.add(plan);
            current = plan;
            BoM.tree = tree;
            BoM.craftingMode = false;
            EmiPersistentData.save();
            return true;
        } catch (RuntimeException | StackOverflowError e) {
            EmiLog.error("Failed to import crafting plan", e);
            return false;
        }
    }

    /**
     * Stable across amounts, chances, object property order, and recipe reloads.
     */
    public static String key(EmiIngredient ingredient) {
        JsonElement json = serialize(ingredient);
        return json == null ? "" : canonical(json).toString();
    }

    private static CraftingPlan snapshot() {
        if (BoM.tree == null || BoM.tree.goal == null) {
            return null;
        }
        JsonElement recipe = serializeRecipe(BoM.tree.goal.recipe);
        if (recipe == null || recipe.isJsonNull()) {
            return null;
        }
        CraftingPlan plan = new CraftingPlan(BoM.tree.goal.ingredient.getEmiStacks().getFirst().getName().getString(), recipe);
        capture(plan, BoM.tree);
        return plan;
    }

    private static JsonObject writePlan(CraftingPlan plan) {
        JsonObject json = new JsonObject();
        json.addProperty("name", plan.name);
        json.add("recipe", plan.recipe.deepCopy());
        json.addProperty("batches", Math.max(1, plan.batches));
        json.addProperty("goal_amount", Math.max(1, plan.goalAmount));
        json.addProperty("use_inventory", plan.useInventory);
        json.add("resolutions", plan.resolutions.deepCopy());
        JsonArray folded = new JsonArray();
        for (String key : plan.folded) {
            if (key != null && !key.isEmpty()) {
                folded.add(key);
            }
        }
        json.add("folded", folded);
        return json;
    }

    private static CraftingPlan readPlan(JsonElement entry, boolean strict) {
        JsonObject json = entry.getAsJsonObject();
        JsonElement recipe = json.get("recipe");
        if (recipe == null || recipe.isJsonNull() || deserializeRecipe(recipe) == null) {
            throw new IllegalArgumentException("Missing goal recipe");
        }
        CraftingPlan plan = new CraftingPlan(json.has("name") ? cleanName(json.get("name").getAsString()) : "Crafting plan", recipe.deepCopy());
        if (json.has("batches")) {
            plan.batches = positiveLong(json.get("batches"));
            if (plan.batches < 1) {
                throw new IllegalArgumentException("Invalid batch count");
            }
        }
        if (json.has("goal_amount")) {
            plan.goalAmount = positiveLong(json.get("goal_amount"));
            if (plan.goalAmount < 1) {
                throw new IllegalArgumentException("Invalid goal amount");
            }
        }
        if (json.has("use_inventory")) {
            if (!json.get("use_inventory").isJsonPrimitive() || !json.getAsJsonPrimitive("use_inventory").isBoolean()) {
                throw new IllegalArgumentException("Invalid inventory setting");
            }
            plan.useInventory = json.get("use_inventory").getAsBoolean();
        }
        if (json.has("folded")) {
            JsonArray folded = json.getAsJsonArray("folded");
            if (folded.size() > MAX_ENTRIES) {
                throw new IllegalArgumentException("Too many folded entries");
            }
            for (JsonElement key : folded) {
                try {
                    String value = key.getAsString();
                    if (!value.isEmpty() && value.length() <= MAX_IMPORT_LENGTH) {
                        plan.folded.add(value);
                    }
                } catch (RuntimeException e) {
                    if (strict) {
                        throw e;
                    }
                    EmiLog.error("Skipping invalid folded material", e);
                }
            }
        }
        if (json.has("resolutions")) {
            JsonArray resolutions = json.getAsJsonArray("resolutions");
            if (resolutions.size() > MAX_ENTRIES) {
                throw new IllegalArgumentException("Too many resolutions");
            }
            for (JsonElement resolution : resolutions) {
                try {
                    JsonObject value = resolution.getAsJsonObject();
                    EmiIngredient ingredient = deserializeIngredient(value.get("ingredient"));
                    JsonElement choice = value.get("recipe");
                    if (ingredient.isEmpty() || choice == null || (!choice.isJsonNull() && deserializeRecipe(choice) == null)) {
                        throw new IllegalArgumentException("Invalid resolution");
                    }
                    plan.resolutions.add(value.deepCopy());
                } catch (RuntimeException e) {
                    if (strict) {
                        throw e;
                    }
                    EmiLog.error("Skipping invalid crafting resolution", e);
                }
            }
        }
        return plan;
    }

    private static MaterialTree createTree(CraftingPlan plan) {
        try {
            EmiRecipe recipe = deserializeRecipe(plan.recipe);
            if (recipe == null || recipe.getOutputs().isEmpty()) {
                return null;
            }
            MaterialTree tree = new MaterialTree(recipe);
            tree.batches = Math.max(1, plan.batches);
            for (JsonElement entry : plan.resolutions) {
                try {
                    JsonObject json = entry.getAsJsonObject();
                    EmiIngredient ingredient = deserializeIngredient(json.get("ingredient"));
                    JsonElement choice = json.get("recipe");
                    EmiRecipe resolution = deserializeRecipe(choice);
                    if (!ingredient.isEmpty() && (choice.isJsonNull() || resolution != null)) {
                        tree.resolutions.put(ingredient, resolution);
                        if (ingredient.equals(tree.goal.ingredient)) {
                            tree.goal.defineRecipe(resolution);
                            if (resolution != null) {
                                tree.goal.amount = tree.goal.divisor;
                            }
                        }
                    }
                } catch (RuntimeException e) {
                    EmiLog.error("Skipping unavailable crafting resolution", e);
                }
            }
            if (plan.goalAmount > 0) {
                tree.goal.amount = plan.goalAmount;
            }
            tree.recalculate();
            return tree;
        } catch (RuntimeException e) {
            EmiLog.error("Failed to restore crafting plan", e);
            return null;
        }
    }

    private static JsonElement serializeRecipe(EmiRecipe recipe) {
        if (recipe == null) {
            return JsonNull.INSTANCE;
        } else if (recipe instanceof EmiResolutionRecipe resolution) {
            JsonElement ingredient = serialize(resolution.ingredient);
            JsonElement stack = serialize(resolution.stack);
            if (ingredient != null && stack != null) {
                JsonObject json = new JsonObject();
                json.add("ingredient", ingredient);
                json.add("stack", stack);
                return json;
            }
        } else if (recipe.getId() != null) {
            return new com.google.gson.JsonPrimitive(recipe.getId().toString());
        }
        return null;
    }

    private static EmiRecipe deserializeRecipe(JsonElement json) {
        if (json == null || json.isJsonNull()) {
            return null;
        } else if (json.isJsonPrimitive()) {
            return EmiApi.getRecipeManager().getRecipe(EmiPort.id(json.getAsString()));
        }
        JsonObject resolution = json.getAsJsonObject();
        EmiIngredient ingredient = deserializeIngredient(resolution.get("ingredient"));
        EmiIngredient stack = deserializeIngredient(resolution.get("stack"));
        if (ingredient.isEmpty() || stack.isEmpty() || stack.getEmiStacks().size() != 1
                || !ingredient.getEmiStacks().contains(stack.getEmiStacks().getFirst())) {
            throw new IllegalArgumentException("Invalid ingredient resolution");
        }
        return new EmiResolutionRecipe(ingredient, stack.getEmiStacks().getFirst());
    }

    private static JsonElement serialize(EmiIngredient ingredient) {
        return ingredient == null ? null : EmiIngredientSerializer.getSerialized(ingredient.copy().setAmount(1).setChance(1));
    }

    private static EmiIngredient deserializeIngredient(JsonElement json) {
        return EmiIngredientSerializer.getDeserialized(json == null ? null : json.deepCopy());
    }

    private static long positiveLong(JsonElement json) {
        if (!json.isJsonPrimitive() || !json.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Expected positive integer");
        }
        try {
            long value = json.getAsBigDecimal().longValueExact();
            if (value > 0) {
                return value;
            }
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Invalid positive integer", e);
        }
        throw new IllegalArgumentException("Expected positive integer");
    }

    private static String cleanName(String name) {
        String result = name.strip();
        return result.isEmpty() ? "Crafting plan" : result.substring(0, Math.min(256, result.length()));
    }

    private static JsonElement canonical(JsonElement json) {
        if (json.isJsonObject()) {
            JsonObject sorted = new JsonObject();
            Map<String, JsonElement> properties = new TreeMap<>();
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
                properties.put(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, JsonElement> entry : properties.entrySet()) {
                sorted.add(entry.getKey(), canonical(entry.getValue()));
            }
            return sorted;
        } else if (json.isJsonArray()) {
            List<JsonElement> values = new ArrayList<>();
            for (JsonElement entry : json.getAsJsonArray()) {
                values.add(canonical(entry));
            }
            JsonArray sorted = new JsonArray();
            values.forEach(sorted::add);
            return sorted;
        }
        return json.deepCopy();
    }
}
