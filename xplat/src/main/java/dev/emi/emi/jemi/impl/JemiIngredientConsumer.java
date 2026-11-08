package dev.emi.emi.jemi.impl;

import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.google.common.collect.Lists;

import dev.emi.emi.EmiPort;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.config.FluidUnit;
import dev.emi.emi.jemi.JemiUtil;
import mezz.jei.api.gui.builder.IIngredientAcceptor;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.level.material.Fluid;

@SuppressWarnings({ "rawtypes", "unchecked" })
public class JemiIngredientConsumer implements IIngredientAcceptor<JemiIngredientConsumer> {
    public List<EmiIngredient> stacks = Lists.newArrayList();

    @Override
    public JemiIngredientConsumer add(SlotDisplay display) {
        ItemStack stack = display.resolveForFirstStack(EmiPort.emptyContext());
        if (!stack.isEmpty()) {
            stacks.add(EmiStack.of(stack));
        }
        return this;
    }

    @Override
    public JemiIngredientConsumer add(Ingredient ingredient) {
        EmiIngredient emi = EmiIngredient.of(ingredient);
        if (!emi.isEmpty()) {
            stacks.add(emi);
        }
        return this;
    }

    @Override
    public JemiIngredientConsumer add(Fluid fluid) {
        return addFluidStack(fluid, FluidUnit.BUCKET);
    }

    @Override
    public JemiIngredientConsumer add(Fluid fluid, long amount) {
        return addFluidStack(fluid, amount);
    }

    @Override
    public JemiIngredientConsumer add(Fluid fluid, long amount, DataComponentPatch componentPatch) {
        return addFluidStack(fluid, amount, componentPatch);
    }

    @Override
    public <I> JemiIngredientConsumer addIngredients(IIngredientType<I> ingredientType, List<@Nullable I> ingredients) {
        for (I i : ingredients) {
            add(ingredientType, i);
        }
        return this;
    }

    @Override
    public <I> JemiIngredientConsumer add(IIngredientType<I> ingredientType, I ingredient) {
        stacks.add(JemiUtil.getStack(ingredientType, ingredient));
        return this;
    }

    @Override
    public JemiIngredientConsumer addIngredientsUnsafe(List<?> ingredients) {
        for (Object o : ingredients) {
            stacks.add(JemiUtil.getStack(o));
        }
        return this;
    }

    @Override
    public JemiIngredientConsumer addTypedIngredients(List<ITypedIngredient<?>> ingredients) {
        for (ITypedIngredient i : ingredients) {
            add(i.getType(), i.getIngredient());
        }
        return this;
    }

    @Override
    public JemiIngredientConsumer addOptionalTypedIngredients(List<Optional<ITypedIngredient<?>>> ingredients) {
        for (Optional<ITypedIngredient<?>> i : ingredients) {
            if (i.isPresent()) {
                ITypedIngredient t = i.get();
                add(t.getType(), t.getIngredient());
            }
        }
        return this;
    }

    @Override
    public JemiIngredientConsumer addFluidStack(Fluid fluid) {
        return addFluidStack(fluid, FluidUnit.BUCKET);
    }

    @Override
    public JemiIngredientConsumer addFluidStack(Fluid fluid, long amount) {
        return addFluidStack(fluid, amount, DataComponentPatch.EMPTY);
    }

    @Override
    public JemiIngredientConsumer addFluidStack(Fluid fluid, long amount, DataComponentPatch component) {
        stacks.add(EmiStack.of(fluid, component, amount));
        return this;
    }
}
