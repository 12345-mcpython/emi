package dev.emi.emi.api.recipe;
import java.util.List;
import dev.emi.emi.api.stack.*;
public class EmiResolutionRecipe implements EmiRecipe { public final EmiIngredient ingredient; public final EmiStack stack; public EmiResolutionRecipe(EmiIngredient i, EmiStack s) { ingredient=i; stack=s; } public EmiIngredient ingredient() { return ingredient; } public EmiStack stack() { return stack; } public List<EmiIngredient> getInputs() { return List.of(stack); } public List<EmiStack> getOutputs() { return List.of(stack); } }
