package dev.emi.emi.bom;

import dev.emi.emi.api.stack.EmiIngredient;

public class FractionalMaterialCost {
    public final EmiIngredient ingredient;
    public final float amount;

    public FractionalMaterialCost(EmiIngredient ingredient, float amount) {
        this.ingredient = ingredient;
        this.amount = amount;
    }
}
