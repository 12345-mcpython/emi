package dev.emi.emi.api.recipe;
import java.util.List;
import dev.emi.emi.api.stack.*;
public record EmiResolutionRecipe(EmiStack stack) implements EmiRecipe {
    public List<EmiIngredient> getInputs() { return List.of(stack); }
    public List<EmiStack> getOutputs() { return List.of(stack); }
}
