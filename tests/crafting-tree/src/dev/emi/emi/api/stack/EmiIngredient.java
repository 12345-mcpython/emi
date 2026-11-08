package dev.emi.emi.api.stack;
import java.util.List;
public interface EmiIngredient {
    List<EmiStack> getEmiStacks();
    EmiIngredient copy();
    long getAmount();
    EmiIngredient setAmount(long amount);
    float getChance();
    EmiIngredient setChance(float chance);
    default boolean isEmpty() { return getEmiStacks().stream().allMatch(EmiStack::isEmpty); }
    static boolean areEqual(EmiIngredient a, EmiIngredient b) {
        List<EmiStack> as = a.getEmiStacks(), bs = b.getEmiStacks();
        if (as.size() != bs.size()) return false;
        for (int i = 0; i < as.size(); i++) if (!as.get(i).equals(bs.get(i))) return false;
        return true;
    }
}
