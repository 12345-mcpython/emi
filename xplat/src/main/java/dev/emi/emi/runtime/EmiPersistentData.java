package dev.emi.emi.runtime;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import dev.emi.emi.bom.BoM;
import dev.emi.emi.bom.CraftingPlans;
import net.minecraft.util.JsonHelper;

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
			if (JsonHelper.hasArray(json, "favorites")) {
				EmiFavorites.load(JsonHelper.getArray(json, "favorites"));
			}
			EmiBookmarkGroups.load(JsonHelper.hasJsonObject(json, "bookmark_groups") ? JsonHelper.getObject(json, "bookmark_groups") : null);
			EmiSidebars.load(json);
			if (JsonHelper.hasJsonObject(json, "recipe_defaults")) {
				BoM.loadAdded(JsonHelper.getObject(json, "recipe_defaults"));
			}
			if (JsonHelper.hasJsonObject(json, "crafting_plans")) {
				CraftingPlans.load(JsonHelper.getObject(json, "crafting_plans"));
			}
			if (JsonHelper.hasArray(json, "hidden_stacks")) {
				EmiHidden.load(JsonHelper.getArray(json, "hidden_stacks"));
			}
		} catch (Exception e) {
			EmiLog.error("Failed to parse persistent data", e);
		}
	}
}
