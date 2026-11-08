package dev.emi.emi.bom;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A saved tree snapshot. Recipe references are resolved only when opening it.
 */
public class CraftingPlan {
    public String name;
    public final Set<String> folded = new LinkedHashSet<>();
    public boolean useInventory = true;
    public long batches = 1;

    long goalAmount;
    final JsonElement recipe;
    JsonArray resolutions = new JsonArray();

    CraftingPlan(String name, JsonElement recipe) {
        this.name = name;
        this.recipe = recipe;
    }
}
