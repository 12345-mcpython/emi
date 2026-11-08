package dev.emi.emi.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.handler.StandardRecipeHandler;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.platform.EmiClient;
import dev.emi.emi.registry.EmiRecipeFiller;
import dev.emi.emi.runtime.EmiBookmarkGroups.Group;
import dev.emi.emi.runtime.EmiBookmarkGroups.Row;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

/** Executes the production planner, scheduler, material dispatch and raw-packet observer. */
public class BookmarkCraftingChecks {
    static int checks;
    static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    static final class Recipe implements EmiRecipe {
        final List<EmiStack> outputs = new ArrayList<>();
        final List<EmiIngredient> inputs = new ArrayList<>();
        Recipe(String output, String... inputs) { outputs.add(new EmiStack(output)); for (String input : inputs) this.inputs.add(new EmiStack(input)); }
        public List<EmiIngredient> getInputs() { return inputs; }
        public List<EmiStack> getOutputs() { return outputs; }
    }
    static final class Fake implements BookmarkCrafting.Access {
        long now, feedback;
        boolean valid = true, craftable = true, accepted = true;
        int sends;
        EmiRecipe sent;
        Map<EmiStack, Long> outputs = new HashMap<>();
        public boolean valid() { return valid; }
        public long time() { return now; }
        public long feedback() { return feedback; }
        public List<Long> outputCounts(EmiRecipe recipe) { return recipe.getOutputs().stream().map(o -> outputs.getOrDefault(o, 0L)).toList(); }
        public boolean canCraft(BookmarkCrafting.Budget b) { return craftable; }
        public boolean craft(BookmarkCrafting.Budget b) { sends++; sent = b.effective; return accepted; }
        void ack() { feedback++; for (EmiStack output : sent.getOutputs()) outputs.merge(output, output.getAmount(), Long::sum); }
    }
    static BookmarkCrafting.Runner runner(Fake fake, Recipe recipe, long count) { return new BookmarkCrafting.Runner(fake, List.of(new BookmarkCrafting.Budget(recipe, count))); }
    static class Handler implements StandardRecipeHandler<AbstractContainerMenu> {
        Slot output = new Slot(), grid = new Slot(), extra;
        boolean supported = true;
        EmiRecipe expected, checked, slotsRecipe;
        public boolean supportsRecipe(EmiRecipe recipe) { checked = recipe; return supported && (expected == null || recipe == expected); }
        public Slot getOutputSlot(AbstractContainerMenu handler) { return output; }
        public List<Slot> getInputSources(AbstractContainerMenu handler) { return handler.slots; }
        public List<Slot> getCraftingSlots(AbstractContainerMenu handler) { return extra == null ? List.of(grid) : List.of(grid, extra); }
        public List<Slot> getCraftingSlots(EmiRecipe r, AbstractContainerMenu handler) { slotsRecipe = r; return getCraftingSlots(handler); }
    }
    static Minecraft client = Minecraft.getInstance();
    static Handler handler;
    static AbstractContainerMenu container;
    static AbstractContainerScreen<AbstractContainerMenu> screen;
    static Group group = new Group();
    static void setup(Recipe recipe, long batches) {
        BookmarkCrafting.cancel();
        client.player = new LocalPlayer();
        container = new AbstractContainerMenu(); handler = new Handler();
        container.slots.add(handler.grid);
        for (int i = 0; i < 36; i++) { Slot s = new Slot(); s.container = client.player.getInventory(); s.index = i; container.slots.add(s); }
        screen = new AbstractContainerScreen<>(container); client.screen = screen; client.player.containerMenu = container;
        EmiRecipeFiller.handler = handler; EmiRecipeFiller.accepted = true; EmiRecipeFiller.missing = false; EmiRecipeFiller.calls = 0;
        EmiPlayerInventory.INSTANCE.available = true; EmiBookmarkGroups.effective.clear(); EmiClient.onServer = false;
        EmiBookmarkGroups.plain = List.of(new Row(recipe, batches, false)); EmiBookmarkGroups.offset = List.of(new Row(recipe, 2, false));
    }
    static void tick() { client.player.tickCount++; BookmarkCrafting.tick(); }
    public static void main(String[] args) {
        Recipe root = new Recipe("product", "part"), producer = new Recipe("part", "raw");
        var plan = BookmarkCrafting.plan(List.of(new Row(root, 2, false), new Row(root, 2, true), new Row(producer, 8, false), new Row(root, 2, false)));
        check(plan.size() == 2 && plan.get(0).recipe == producer && plan.get(1).recipe == root, "dependency-first and original identity");
        check(plan.get(1).remaining == 2, "duplicate output rows use max budget");
        Recipe a = new Recipe("a", "b"), b = new Recipe("b", "a");
        check(BookmarkCrafting.plan(List.of(new Row(a, 1, false), new Row(b, 1, false))).isEmpty(), "cycles rejected");
        List<Row> huge = new ArrayList<>(); for (int i = 0; i <= BookmarkCrafting.MAX_RECIPES; i++) huge.add(new Row(new Recipe("item" + i), 1, false));
        check(BookmarkCrafting.plan(huge).isEmpty(), "recipe cap");
        check(BookmarkCrafting.plan(List.of(new Row(root, 0, false))).isEmpty(), "zero budgets skipped");
        Fake f = new Fake(); var run = runner(f, root, 2); run.tick(); run.tick();
        check(f.sends == 1 && run.plan.get(0).remaining == 2, "same-tick callbacks cannot resend or spend budget");
        for (int i = 1; i < 20; i++) { f.now = i; run.tick(); }
        check(f.sends == 1 && run.running, "wait without retries");
        f.outputs.put(root.outputs.get(0), 1L); f.now++; run.tick();
        check(f.sends == 1, "output prediction without feedback cannot acknowledge");
        f.feedback++; f.now++; run.tick();
        check(f.sends == 2 && run.plan.get(0).remaining == 1, "server feedback acknowledges exactly one batch");
        f.ack(); f.now++; run.tick();
        check(!run.running && run.completed == 2 && run.reason.equals("budget_complete"), "exact budget completed status");
        f = new Fake(); run = runner(f, root, Long.MAX_VALUE); run.tick(); f.now = BookmarkCrafting.ACK_TIMEOUT; run.tick();
        check(!run.running && run.reason.equals("server_ack_timeout") && f.sends == 1, "timeout never retries");
        f = new Fake(); run = runner(f, root, 2); run.tick(); f.valid = false; f.now++; run.tick();
        check(!run.running && run.reason.equals("screen_or_handler_changed"), "handler switch stops pending");
        f = new Fake(); f.accepted = false; run = runner(f, root, 2); run.tick();
        check(!run.running && f.sends == 1 && run.reason.equals("fill_rejected"), "partial failure never retried");
        f = new Fake(); f.craftable = false; run = runner(f, root, 2); run.tick(); check(!run.running && f.sends == 0, "missing materials no send");
        f = new Fake(); run = runner(f, root, Long.MAX_VALUE);
        for (int i = 0; i <= BookmarkCrafting.MAX_OPERATIONS; i++) { f.now = i; if (f.sent != null) f.ack(); run.tick(); }
        check(!run.running && f.sends == BookmarkCrafting.MAX_OPERATIONS && run.reason.equals("operation_limit"), "operation bound");
        Recipe multi = new Recipe("primary", "raw"); multi.outputs.add(new EmiStack("secondary"));
        f = new Fake(); run = runner(f, multi, 1); run.tick(); f.feedback++; f.outputs.put(multi.outputs.get(0), 1L); f.now++; run.tick();
        check(run.running && run.completed == 0, "multi-output waits for all outputs");
        f.outputs.put(multi.outputs.get(1), 1L); f.now++; run.tick(); check(!run.running && run.completed == 1, "all outputs confirmed");
        f = new Fake(); run = runner(f, root, 2); run.tick(); f.feedback++; f.outputs.put(root.outputs.get(0), 2L); f.now++; run.tick();
        check(!run.running && run.completed == 0 && run.reason.equals("ambiguous_output_change"), "excess output never consumes budget");
        setup(root, 1000);
        check(BookmarkCrafting.start(group, true) && EmiBookmarkGroups.lastInventory != null, "Ctrl inventory rows");
        check(!BookmarkCrafting.start(group, false), "active run not replaced"); BookmarkCrafting.tick();
        check(EmiRecipeFiller.sent.get(0).getCount() == 1 && EmiRecipeFiller.destination == dev.emi.emi.api.recipe.handler.EmiCraftContext.Destination.INVENTORY, "getStacks extra existing batches clamped to exactly one");
        client.player.getInventory().stacks.set(0, new ItemStack("product", 1)); tick();
        check(EmiRecipeFiller.calls == 1 && BookmarkCrafting.status().completed() == 0, "local prediction cannot acknowledge facade");
        BookmarkCrafting.observeServerSlot(container.containerId + 1, 1, new ItemStack("product", 1)); tick();
        check(BookmarkCrafting.status().completed() == 0, "foreign sync ignored");
        BookmarkCrafting.observeServerSlot(container.containerId, 0, new ItemStack("product", 1)); tick();
        check(BookmarkCrafting.status().completed() == 0, "grid output is not delivered inventory");
        BookmarkCrafting.observeServerSlot(-2, 0, new ItemStack("product", 1)); tick();
        check(BookmarkCrafting.status().completed() == 1 && EmiRecipeFiller.calls == 2, "raw -2 player inventory confirms output");
        BookmarkCrafting.cancel(); check(!BookmarkCrafting.isRunning() && BookmarkCrafting.status().result().equals("cancelled"), "observable cancellation");
        setup(root, 1); check(BookmarkCrafting.start(group, false) && EmiBookmarkGroups.lastInventory == null, "normal no inventory discount"); BookmarkCrafting.tick();
        BookmarkCrafting.observeServerSlot(container.containerId, 1, new ItemStack("product", 1)); tick();
        check(!BookmarkCrafting.isRunning() && BookmarkCrafting.status().result().equals("completed"), "handler slot mapping confirmed");
        setup(root, 1); BookmarkCrafting.start(group, false); BookmarkCrafting.tick();
        List<ItemStack> full = new ArrayList<>(); full.add(new ItemStack("", 0)); full.add(new ItemStack("product", 1));
        BookmarkCrafting.observeServerInventory(container.containerId + 2, full); tick(); check(BookmarkCrafting.status().completed() == 0, "foreign full inventory ignored");
        BookmarkCrafting.observeServerInventory(container.containerId, full); tick(); check(BookmarkCrafting.status().completed() == 1, "full inventory raw confirmation");
        setup(root, 1); BookmarkCrafting.start(group, false); BookmarkCrafting.tick(); client.screen = new Object(); tick();
        check(!BookmarkCrafting.isRunning() && BookmarkCrafting.status().reason().equals("screen_or_handler_changed"), "real screen transition stops");
        setup(root, 1000); check(BookmarkCrafting.fill(group, true, false) && EmiRecipeFiller.amount == 64 && !BookmarkCrafting.isRunning(), "fill-only bounded without production");
        check(BookmarkCrafting.status().result().equals("filled"), "fill-only observable result");
        setup(root, 1); handler.output = null; check(!BookmarkCrafting.start(group, false), "machine rejected");
        setup(root, 1); handler.grid.item = new ItemStack("raw", 1); check(!BookmarkCrafting.start(group, false) && BookmarkCrafting.status().reason().equals("foreign_or_locked_grid"), "foreign recipe grid protected");
        setup(root, 1); handler.grid.item = new ItemStack("part", 32); check(BookmarkCrafting.start(group, false), "own preloaded inputs accepted"); BookmarkCrafting.tick();
        check(EmiRecipeFiller.sent.get(0).getCount() == 1, "own many-batch grid rebuilt to one");
        Recipe tool = new Recipe("product", "tool", "raw"); ((EmiStack)tool.inputs.get(0)).remainder = new EmiStack("worn_tool");
        setup(tool, 2); handler.extra = new Slot(); container.slots.add(handler.extra); handler.grid.item = new ItemStack("worn_tool", 1); check(BookmarkCrafting.start(group, false), "own returned tool accepted with a consumable ingredient");
        setup(root, 1); handler.grid.item = new ItemStack("part", 1); handler.grid.take = false; check(!BookmarkCrafting.start(group, false), "locked own grid rejected");
        setup(root, 1); container.carried = new ItemStack("held", 1); check(!BookmarkCrafting.start(group, false), "occupied cursor rejected");
        setup(root, 1); EmiPlayerInventory.INSTANCE.available = false; check(!BookmarkCrafting.start(group, false), "actual inventory required");
        check(!BookmarkCrafting.fill(group, true, false) && EmiRecipeFiller.calls == 0, "fill-only also needs actual materials");
        setup(root, 1); EmiRecipeFiller.accepted = false; check(!BookmarkCrafting.fill(group, true, false) && EmiRecipeFiller.calls == 1, "rejected partial fill no fallthrough");
        setup(root, 1); EmiRecipeFiller.handler = null; check(!BookmarkCrafting.start(group, false), "no handler false");
        setup(root, 1); handler.output.item = new ItemStack("other", 1); check(!BookmarkCrafting.start(group, false), "foreign result slot protected");
        setup(root, 1); for (int i = 0; i < 36; i++) client.player.getInventory().stacks.set(i, new ItemStack("full",64));
        check(!BookmarkCrafting.start(group, false) && BookmarkCrafting.status().reason().equals("no_output_capacity"), "inventory capacity prevents item drop");
        handler.grid.item = new ItemStack("part", 1);
        check(!BookmarkCrafting.fill(group, false, false) && BookmarkCrafting.status().reason().equals("no_return_capacity"), "fill-only protects full-inventory existing layout");
        Recipe self = new Recipe("product", "product"); setup(self, 1); check(!BookmarkCrafting.start(group, false), "self-consuming output rejected");
        Recipe chance = new Recipe("product", "part"); chance.outputs.get(0).chance = .5f; setup(chance, 1); check(!BookmarkCrafting.start(group, false), "probabilistic output rejected");
        Recipe duplicate = new Recipe("product", "part"); duplicate.outputs.add(new EmiStack("product")); setup(duplicate, 1); check(!BookmarkCrafting.start(group, false), "ambiguous duplicate output rejected");
        setup(root, 1); Recipe chosen = new Recipe("product", "chosen_part"); EmiBookmarkGroups.effective.put(root, chosen); handler.expected = root;
        check(BookmarkCrafting.start(group, false), "effective choices supported with identity-only handler"); BookmarkCrafting.tick();
        check(EmiRecipeFiller.lookup == root && handler.slotsRecipe == root && EmiRecipeFiller.filled == root && EmiSidebars.last == root, "handler lookup, shape, transport and history retain original identity");
        check(EmiRecipeFiller.collected == chosen && EmiPlayerInventory.INSTANCE.checked == chosen && EmiRecipeFiller.sent.get(0).id.equals("chosen_part"), "selected candidate actually collected and sent");
        setup(root, 1); EmiRecipeFiller.handler = new Handler() { public boolean craft(EmiRecipe r, dev.emi.emi.api.recipe.handler.EmiCraftContext<AbstractContainerMenu> c) { return true; } };
        check(!BookmarkCrafting.start(group, false) && BookmarkCrafting.status().reason().equals("custom_craft_handler"), "custom handler craft semantics not bypassed");
        Recipe catalystOnly = new Recipe("product", "tool"); ((EmiStack)catalystOnly.inputs.get(0)).remainder = new EmiStack("tool");
        setup(catalystOnly, 1); check(!BookmarkCrafting.start(group, false) && BookmarkCrafting.status().reason().equals("no_consumed_input"), "catalyst-only quick-move cannot be bounded");
        Recipe randomInput = new Recipe("product", "part"); ((EmiStack)randomInput.inputs.get(0)).chance = .5f;
        setup(randomInput, 1); check(!BookmarkCrafting.start(group, false), "probabilistic consumption cannot be bounded");
        setup(root, 1); EmiClient.onServer = true; check(BookmarkCrafting.start(group, false), "server fill accepted"); BookmarkCrafting.tick();
        check(EmiRecipeFiller.sent.get(0).getCount() == 1 && EmiRecipeFiller.filled == root, "server transport exact one and original recipe");
        BookmarkCrafting.cancel(); client.screen = null;
        check(!BookmarkCrafting.start(group, false) && !BookmarkCrafting.fill(group, false, false), "no container safe false");
        System.out.println("BookmarkCrafting checks passed: " + checks);
    }
}
