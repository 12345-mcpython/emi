package dev.emi.emi.api.recipe;
import java.util.*;
import dev.emi.emi.api.stack.EmiStack;
public class EmiPlayerInventory {
    public final Map<EmiStack, EmiStack> inventory = new LinkedHashMap<>();
    public EmiPlayerInventory(List<EmiStack> stacks) { for (EmiStack stack : stacks) inventory.put(stack, stack); }
}
