package dev.emi.emi.api.recipe;
import java.util.List;
import dev.emi.emi.api.stack.*;
public interface EmiRecipe {
    List<EmiIngredient> getInputs();
    List<EmiStack> getOutputs();
}
