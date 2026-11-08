package dev.emi.emi.runtime;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import net.minecraft.util.GsonHelper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import dev.emi.emi.bom.BoM;
import dev.emi.emi.bom.CraftingPlans;

public class EmiPersistentData {
	public static final File FILE = new File("emi.json");
	public static final Gson GSON = new Gson().newBuilder().setPrettyPrinting().create();

	public static void save() {
		try {
			JsonObject json = new JsonObject();
			json.add("favorites", EmiFavorites.save());
			json.add("bookmark_groups", EmiBookmarkGroups.save());
			EmiSidebars.save(json);
			json.add("recipe_defaults", BoM.saveAdded());
			json.add("crafting_plans", CraftingPlans.save());
			json.add("hidden_stacks", EmiHidden.save());
			FileWriter writer = new FileWriter(FILE);
			GSON.toJson(json, writer);
			writer.close();
		} catch (Exception e) {
			EmiLog.error("Failed to write persistent data", e);
		}
	}

	public static void load() {
		// Recipe reloads must not leave a tree referencing the previous recipe manager.
		CraftingPlans.load(null);
		EmiBookmarkGroups.load(null);
		BoM.addedRecipes.clear();
		BoM.disabledRecipes.clear();
		if (!FILE.exists()) {
			return;
		}
		try (FileReader reader = new FileReader(FILE)) {
			JsonObject json = GSON.fromJson(reader, JsonObject.class);
			if (json == null) {
				return;
			}
			if (GsonHelper.isArrayNode(json, "favorites")) {
				EmiFavorites.load(GsonHelper.getAsJsonArray(json, "favorites"));
			}
			EmiBookmarkGroups.load(GsonHelper.isObjectNode(json, "bookmark_groups") ? GsonHelper.getAsJsonObject(json, "bookmark_groups") : null);
			EmiSidebars.load(json);
			if (GsonHelper.isObjectNode(json, "recipe_defaults")) {
				BoM.loadAdded(GsonHelper.getAsJsonObject(json, "recipe_defaults"));
			}
			if (GsonHelper.isObjectNode(json, "crafting_plans")) {
				CraftingPlans.load(GsonHelper.getAsJsonObject(json, "crafting_plans"));
			}
			if (GsonHelper.isArrayNode(json, "hidden_stacks")) {
				EmiHidden.load(GsonHelper.getAsJsonArray(json, "hidden_stacks"));
			}
		} catch (Exception e) {
			EmiLog.error("Failed to parse persistent data", e);
		}
	}
}
