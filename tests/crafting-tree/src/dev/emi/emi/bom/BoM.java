package dev.emi.emi.bom;
import java.util.*;
import dev.emi.emi.api.stack.*;
import dev.emi.emi.api.recipe.*;
public class BoM {
	static final Map<EmiIngredient,EmiRecipe> recipes = new HashMap<>();
	public static EmiRecipe getRecipe(EmiIngredient ingredient) { return recipes.get(ingredient); }
}
