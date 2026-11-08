package dev.emi.emi.api.stack;
import java.util.List;
public class EmiStack implements EmiIngredient {
    public static final EmiStack EMPTY = new EmiStack("", 0);
    public final String id;
    private long amount;
    private float chance = 1;
    private EmiStack remainder;
    public EmiStack(String id, long amount) { this.id = id; this.amount = amount; }
    public EmiStack remainder(EmiStack stack) { remainder = stack; return this; }
    public EmiStack getRemainder() { return remainder == null ? EMPTY : remainder; }
    public List<EmiStack> getEmiStacks() { return List.of(this); }
    public EmiStack copy() { return new EmiStack(id, amount).setChance(chance).remainder(remainder); }
    public long getAmount() { return amount; }
    public EmiStack setAmount(long amount) { this.amount = amount; return this; }
    public float getChance() { return chance; }
    public EmiStack setChance(float chance) { this.chance = chance; return this; }
    public boolean isEmpty() { return id.isEmpty(); }
    public boolean equals(Object other) { return other instanceof EmiStack stack && id.equals(stack.id); }
    public int hashCode() { return id.hashCode(); }
    public String toString() { return id; }
}
