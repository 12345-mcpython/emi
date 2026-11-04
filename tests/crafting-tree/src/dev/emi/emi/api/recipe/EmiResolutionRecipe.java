package dev.emi.emi.api.recipe;
import java.util.List;
import dev.emi.emi.api.stack.*;
public class EmiResolutionRecipe implements EmiRecipe {
    public final EmiStack stack;
    public EmiResolutionRecipe(EmiStack stack) { this.stack = stack; }
    public List<EmiIngredient> getInputs() { return List.of(stack); }
    public List<EmiStack> getOutputs() { return List.of(stack); }
}
