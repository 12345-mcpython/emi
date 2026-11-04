package dev.emi.emi.runtime;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.recipe.handler.EmiRecipeHandler;
import dev.emi.emi.api.recipe.handler.StandardRecipeHandler;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.platform.EmiClient;
import dev.emi.emi.registry.EmiRecipeFiller;
import dev.emi.emi.runtime.EmiBookmarkGroups.Group;
import dev.emi.emi.runtime.EmiBookmarkGroups.Row;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

/** Client-thread, bounded, one-batch runner. Production is confirmed only by server inventory packets. */
public final class BookmarkCrafting {
	static final int MAX_RECIPES = 256;
	static final int MAX_OPERATIONS = 4096;
	static final int ACK_TIMEOUT = 100;
	private static Runner active;
	private static Status last = new Status(false, "idle", "not_started", 0, 0);

	public record Status(boolean running, String result, String reason, int completed, int operations) {}
	private BookmarkCrafting() {}

	public static Status status() { return active == null ? last : active.status(); }
	public static boolean isRunning() { return active != null && active.running; }
	public static void cancel() {
		if (active != null) { active.stop("cancelled", "user_cancelled"); last = active.status(); active = null; }
	}
	private static boolean rejected(String reason) { last = new Status(false, "rejected", reason, 0, 0); return false; }

	public static boolean start(Group group, boolean useInventory) {
		if (active != null) return false;
		if (group == null) return rejected("no_group");
		if (!group.crafting) return rejected("crafting_disabled");
		try {
			ClientAccess access = ClientAccess.create();
			if (access == null) return rejected("invalid_screen_or_cursor");
			EmiPlayerInventory inventory = useInventory ? EmiPlayerInventory.of(MinecraftClient.getInstance().player) : null;
			List<Budget> plan = plan(EmiBookmarkGroups.executionRows(group, inventory));
			if (plan.isEmpty()) return rejected("empty_or_cyclic_plan");
			Runner runner = new Runner(access, plan);
			if (!runner.hasCraftable()) return rejected(access.reason());
			active = runner;
			return true;
		} catch (RuntimeException e) {
			EmiLog.error("Unable to start bookmark crafting", e);
			return rejected("exception");
		}
	}

	public static void tick() {
		Runner runner = active;
		if (runner == null) return;
		try { runner.tick(); }
		catch (RuntimeException e) { runner.stop("stopped", "exception"); EmiLog.error("Stopped bookmark crafting", e); }
		if (!runner.running && active == runner) { last = runner.status(); active = null; }
	}

	/** Called only with raw server packet contents, after the packet's main-thread guard. */
	public static void observeServerSlot(int syncId, int slot, ItemStack stack) {
		if (active != null && active.access instanceof ClientAccess access) access.observeSlot(syncId, slot, stack);
	}
	/** A full handler packet contains handler slot indices, not player-inventory indices. */
	public static void observeServerInventory(int syncId, List<ItemStack> stacks) {
		if (active != null && active.access instanceof ClientAccess access && syncId == access.syncId) {
			for (int i = 0; i < stacks.size(); i++) access.observeSlot(syncId, i, stacks.get(i));
		}
	}

	/** Fill-only never reports production; inventory mode runs an acknowledged single batch or plan. */
	public static boolean fill(Group group, boolean all, boolean toInventory) {
		if (toInventory && all) return start(group, false);
		if (active != null) return false;
		if (group == null) return rejected("no_group");
		if (!group.crafting) return rejected("crafting_disabled");
		try {
			ClientAccess access = ClientAccess.create();
			if (access == null) return rejected("invalid_screen_or_cursor");
			for (Budget budget : plan(EmiBookmarkGroups.executionRows(group, null))) {
				if (toInventory) {
					if (access.canCraft(budget)) { active = new Runner(access, List.of(new Budget(budget.recipe, budget.effective, 1))); return true; }
				} else {
					int amount = all ? (int) Math.min(64, budget.remaining) : 1;
					if (access.canFill(budget, amount)) {
						boolean accepted = access.fill(budget, amount, false);
						last = new Status(false, accepted ? "filled" : "stopped", accepted ? "fill_only" : "fill_rejected", 0, 1);
						return accepted;
					}
				}
			}
			return rejected(access.reason());
		} catch (RuntimeException e) { EmiLog.error("Unable to fill bookmark group", e); return rejected("exception"); }
	}

	interface Access {
		boolean valid();
		long time();
		long feedback();
		List<Long> outputCounts(EmiRecipe recipe);
		boolean canCraft(Budget budget);
		boolean craft(Budget budget);
		default boolean hasGridItems(EmiRecipe recipe) { return false; }
		default boolean clearGrid(EmiRecipe recipe) { return false; }
		default String reason() { return "no_craftable_recipe"; }
	}
	static final class Budget {
		final EmiRecipe recipe, effective;
		long remaining;
		Budget(EmiRecipe recipe, long remaining) { this(recipe, recipe, remaining); }
		Budget(EmiRecipe recipe, EmiRecipe effective, long remaining) { this.recipe = recipe; this.effective = effective; this.remaining = remaining; }
	}
	static final class Runner {
		final Access access;
		final List<Budget> plan;
		boolean running = true;
		String result = "running", reason = "ready";
		Budget pending;
		EmiRecipe lastRecipe;
		boolean craftedOnce;
		List<Long> outputBefore;
		long feedbackBefore, sentAt, lastTick = Long.MIN_VALUE, cleanupAt;
		int operations, completed;
		Runner(Access access, List<Budget> plan) {
			this.access = access;
			this.plan = plan;
			this.lastRecipe = plan.isEmpty() ? null : plan.get(0).recipe;
		}
		Status status() { return new Status(running, result, reason, completed, operations); }
		void stop(String result, String reason) { running = false; this.result = result; this.reason = reason; }
		boolean hasCraftable() {
			if (!access.valid()) return false;
			for (Budget budget : plan) if (budget.remaining > 0 && access.canCraft(budget)) return true;
			return false;
		}
		void tick() {
			if (!running) return;
			if (!access.valid()) { stop("stopped", "screen_or_handler_changed"); return; }
			long now = access.time();
			if (now == lastTick) return;
			if (now < lastTick) { stop("stopped", "clock_changed"); return; }
			lastTick = now;
			if (pending != null) {
				boolean acknowledged = access.feedback() > feedbackBefore;
				List<Long> counts = access.outputCounts(pending.effective);
				List<EmiStack> outputs = pending.effective.getOutputs();
				for (int i = 0; i < outputs.size(); i++) {
					long produced = counts.get(i) - outputBefore.get(i);
					if (produced > outputs.get(i).getAmount()) { stop("stopped", "ambiguous_output_change"); return; }
					acknowledged &= produced == outputs.get(i).getAmount();
				}
				if (!acknowledged) {
					if (now - sentAt >= ACK_TIMEOUT) stop("stopped", "server_ack_timeout");
					return;
				}
				pending.remaining--; completed++; craftedOnce = true; pending = null;
			}
			// Return reusable tools/catalysts left on the crafting grid before the
			// next batch. Only clear after one of our own crafts: a foreign grid at
			// start must still be rejected rather than silently moved.
			if (craftedOnce && lastRecipe != null) {
				if (access.hasGridItems(lastRecipe)) {
					if (cleanupAt == 0) {
						if (!access.clearGrid(lastRecipe)) {
							stop("stopped", "grid_cleanup_blocked");
							return;
						}
						cleanupAt = now;
					} else if (now - cleanupAt >= ACK_TIMEOUT) {
						stop("stopped", "grid_cleanup_timeout");
					}
					return;
				}
				cleanupAt = 0;
			}
			boolean unfinished = plan.stream().anyMatch(b -> b.remaining > 0);
			if (!unfinished) { stop("completed", "budget_complete"); return; }
			if (operations >= MAX_OPERATIONS) { stop("stopped", "operation_limit"); return; }
			for (Budget budget : plan) {
				if (budget.remaining <= 0 || !access.canCraft(budget)) continue;
				outputBefore = access.outputCounts(budget.effective);
				feedbackBefore = access.feedback(); pending = budget; sentAt = now; operations++;
				lastRecipe = budget.recipe;
				reason = "awaiting_server_inventory";
				if (!access.craft(budget)) stop("stopped", "fill_rejected");
				return;
			}
			stop("stopped", access.reason());
		}
	}

	static List<Budget> plan(List<Row> rows) {
		Map<EmiRecipe, Budget> selected = new LinkedHashMap<>();
		for (Row row : rows) {
			if (row.input || row.recipe == null || row.batches <= 0) continue;
			Budget previous = selected.get(row.recipe);
			if (previous == null) selected.put(row.recipe, new Budget(row.recipe, EmiBookmarkGroups.effectiveRecipe(row.group, row.recipe), row.batches));
			else previous.remaining = Math.max(previous.remaining, row.batches);
			if (selected.size() > MAX_RECIPES) return List.of();
		}
		List<Budget> ordered = new ArrayList<>();
		Map<EmiRecipe, Integer> marks = new IdentityHashMap<>();
		for (Budget budget : selected.values()) if (!visit(budget, selected, marks, ordered)) return List.of();
		return ordered;
	}
	private static boolean visit(Budget budget, Map<EmiRecipe, Budget> selected, Map<EmiRecipe, Integer> marks, List<Budget> ordered) {
		Integer mark = marks.get(budget.recipe);
		if (mark != null) return mark == 2;
		marks.put(budget.recipe, 1);
		for (Budget dependency : selected.values()) {
			if (dependency == budget) continue;
			boolean consumes = false;
			for (EmiIngredient input : budget.effective.getInputs()) for (EmiStack output : dependency.effective.getOutputs()) {
				for (EmiStack option : input.getEmiStacks()) if (!option.isEmpty() && output.isEqual(option)) consumes = true;
			}
			if (consumes && !visit(dependency, selected, marks, ordered)) return false;
		}
		marks.put(budget.recipe, 2); ordered.add(budget); return true;
	}

	private static final class ClientAccess implements Access {
		final HandledScreen<ScreenHandler> screen;
		final ScreenHandler handler;
		final int syncId;
		final List<ItemStack> serverInventory = new ArrayList<>();
		long feedback;
		String reason = "no_craftable_recipe";
		@SuppressWarnings("unchecked")
		ClientAccess(HandledScreen<?> screen) {
			this.screen = (HandledScreen<ScreenHandler>) screen; handler = screen.getScreenHandler(); syncId = handler.syncId;
			var inventory = MinecraftClient.getInstance().player.getInventory();
			for (int i = 0; i < inventory.size(); i++) serverInventory.add(inventory.getStack(i).copy());
		}
		static ClientAccess create() {
			MinecraftClient client = MinecraftClient.getInstance();
			if (!client.isOnThread() || client.player == null || !(client.currentScreen instanceof HandledScreen<?> screen)) return null;
			ClientAccess access = new ClientAccess(screen);
			return access.valid() && access.handler.getCursorStack().isEmpty() ? access : null;
		}
		@Override public boolean valid() {
			MinecraftClient client = MinecraftClient.getInstance();
			return client.isOnThread() && client.player != null && client.world != null && client.interactionManager != null
				&& client.currentScreen == screen && screen.getScreenHandler() == handler
				&& client.player.currentScreenHandler == handler && handler.syncId == syncId && handler.getCursorStack().isEmpty();
		}
		@Override public long time() { return MinecraftClient.getInstance().player.age; }
		@Override public long feedback() { return feedback; }
		@Override public String reason() { return reason; }
		boolean deny(String reason) { this.reason = reason; return false; }
		void observeSlot(int packetSyncId, int slotIndex, ItemStack stack) {
			if (!valid()) return;
			int index = -1;
			var player = MinecraftClient.getInstance().player;
			if (packetSyncId == -2) index = slotIndex;
			else {
				ScreenHandler source = packetSyncId == syncId ? handler : packetSyncId == 0 ? player.playerScreenHandler : null;
				if (source != null && slotIndex >= 0 && slotIndex < source.slots.size()) {
					Slot slot = source.slots.get(slotIndex);
					if (slot.inventory == player.getInventory()) index = slot.getIndex();
				}
			}
			if (index >= 0 && index < serverInventory.size()) { serverInventory.set(index, stack.copy()); feedback++; }
		}
		@Override public List<Long> outputCounts(EmiRecipe recipe) {
			List<Long> totals = new ArrayList<>();
			for (EmiStack output : recipe.getOutputs()) {
				long total = 0;
				for (ItemStack stack : serverInventory) if (!stack.isEmpty() && output.isEqual(EmiStack.of(stack))) total += stack.getCount();
				totals.add(total);
			}
			return totals;
		}
		private EmiRecipeHandler<ScreenHandler> recipeHandler(Budget budget) {
			if (!valid()) return null;
			EmiRecipeHandler<ScreenHandler> found = EmiRecipeFiller.getFirstValidHandler(budget.recipe, screen);
			return found != null && found.supportsRecipe(budget.recipe) ? found : null;
		}
		private StandardRecipeHandler<ScreenHandler> standardFor(EmiRecipe recipe) {
			EmiRecipeHandler<ScreenHandler> found = recipeHandler(new Budget(recipe, 1));
			return found instanceof StandardRecipeHandler<ScreenHandler> standard ? standard : null;
		}
		@Override public boolean hasGridItems(EmiRecipe recipe) {
			if (!valid()) return false;
			StandardRecipeHandler<ScreenHandler> standard = standardFor(recipe);
			if (standard == null) return false;
			for (Slot slot : standard.getCraftingSlots(handler)) {
				if (slot != null && !slot.getStack().isEmpty()) return true;
			}
			return false;
		}
		@Override public boolean clearGrid(EmiRecipe recipe) {
			if (!valid()) return false;
			StandardRecipeHandler<ScreenHandler> standard = standardFor(recipe);
			if (standard == null) return false;
			MinecraftClient client = MinecraftClient.getInstance();
			boolean clicked = false;
			for (Slot slot : standard.getCraftingSlots(handler)) {
				if (slot != null && !slot.getStack().isEmpty()
						&& slot.canTakeItems(client.player)) {
					// Move reusable tools, catalysts and remainders back to the player
					// inventory. A normal server click keeps both sides authoritative.
					client.interactionManager.clickSlot(handler.syncId, slot.id, 0,
						SlotActionType.QUICK_MOVE, client.player);
					clicked = true;
				}
			}
			return clicked;
		}
		private boolean safeOutputs(EmiRecipe recipe) {
			if (recipe.getOutputs().isEmpty()) return deny("no_output");
			List<EmiStack> seen = new ArrayList<>();
			for (EmiStack output : recipe.getOutputs()) {
				if (output.getAmount() <= 0 || output.getChance() != 1 || output.getItemStack().isEmpty()) return deny("unobservable_output");
				for (EmiStack prior : seen) if (output.isEqual(prior)) return deny("duplicate_output");
				for (EmiIngredient input : recipe.getInputs()) for (EmiStack option : input.getEmiStacks()) {
					if (output.isEqual(option) || (!option.getRemainder().isEmpty() && output.isEqual(option.getRemainder()))) return deny("self_consuming_or_returned_output");
				}
				seen.add(output);
			}
			boolean consumed = false;
			for (EmiIngredient input : recipe.getInputs()) {
				if (input.isEmpty()) continue;
				if (input.getAmount() <= 0 || input.getChance() != 1) return deny("unobservable_input");
				boolean everyOptionConsumed = true;
				for (EmiStack option : input.getEmiStacks()) {
					// A changed/damaged remainder can still match a broad ingredient and repeat QUICK_MOVE.
					if (!option.getRemainder().isEmpty()) everyOptionConsumed = false;
				}
				consumed |= everyOptionConsumed;
			}
			// QUICK_MOVE may repeat until ingredients run out. A catalyst-only recipe has no one-batch bound.
			return consumed || deny("no_consumed_input");
		}
		private boolean safeGrid(Budget budget, StandardRecipeHandler<ScreenHandler> standard, boolean production) {
			List<Slot> slots = standard.getCraftingSlots(budget.recipe, handler);
			List<EmiIngredient> inputs = budget.effective.getInputs();
			if (slots.isEmpty() || inputs.size() > slots.size()) return deny("invalid_crafting_slots");
			List<Slot> all = new ArrayList<>(standard.getCraftingSlots(handler));
			for (Slot slot : slots) if (slot != null && !all.contains(slot)) all.add(slot);
			for (Slot slot : all) {
				if (slot == null || slot.getStack().isEmpty()) continue;
				int index = slots.indexOf(slot);
				boolean own = false;
				if (index >= 0 && index < inputs.size()) for (EmiStack option : inputs.get(index).getEmiStacks()) {
					EmiStack actual = EmiStack.of(slot.getStack());
					if (option.isEqual(actual) || (!option.getRemainder().isEmpty() && option.getRemainder().isEqual(actual))) own = true;
				}
				if (!own || !slot.canTakeItems(MinecraftClient.getInstance().player)) return deny("foreign_or_locked_grid");
			}
			// Clearing happens in both fill-only and production paths. Never risk dropping the old layout.
			List<ItemStack> room = new ArrayList<>();
			var inventory = MinecraftClient.getInstance().player.getInventory();
			for (int i = 0; i < 36; i++) room.add(inventory.getStack(i).copy());
			for (Slot slot : all) if (slot != null && !reserve(room, slot.getStack())) return deny("no_return_capacity");
			if (production) {
				Slot output = standard.getOutputSlot(handler);
				if (output == null) return deny("no_instant_output_slot");
				if (!output.getStack().isEmpty() && !budget.effective.getOutputs().get(0).isEqual(EmiStack.of(output.getStack()))) return deny("foreign_output_slot");
				for (EmiStack result : budget.effective.getOutputs()) if (!reserve(room, result.getItemStack())) return deny("no_output_capacity");
				// Remainders replace the inputs consumed by the recipe and therefore
				// need no second slot. Reserving them again made tool/catalyst
				// recipes report a false no_return_capacity.
			}
			return true;
		}
		private boolean reserve(List<ItemStack> inventory, ItemStack added) {
			int count = added.getCount();
			for (ItemStack stack : inventory) {
				if (count <= 0) return true;
				if (!stack.isEmpty() && ItemStack.canCombine(stack, added)) {
					int moved = Math.min(count, Math.max(0, stack.getMaxCount() - stack.getCount()));
					stack.setCount(stack.getCount() + moved); count -= moved;
				}
			}
			for (int i = 0; i < inventory.size() && count > 0; i++) if (inventory.get(i).isEmpty()) {
				ItemStack stack = added.copy(); stack.setCount(Math.min(count, stack.getMaxCount())); count -= stack.getCount(); inventory.set(i, stack);
			}
			return count <= 0;
		}
		@Override public boolean canCraft(Budget budget) { return safeOutputs(budget.effective) && prepare(budget, 1, true) != null; }
		boolean canFill(Budget budget, int amount) { return prepare(budget, amount, false) != null; }
		private List<ItemStack> prepare(Budget budget, int amount, boolean production) {
			EmiRecipeHandler<ScreenHandler> found = recipeHandler(budget);
			if (!(found instanceof StandardRecipeHandler<ScreenHandler> standard)) { deny("unsupported_handler"); return null; }
			try {
				if (standard.getClass().getMethod("craft", EmiRecipe.class, EmiCraftContext.class).getDeclaringClass() != StandardRecipeHandler.class) {
					deny("custom_craft_handler"); return null;
				}
			} catch (ReflectiveOperationException e) { deny("custom_craft_handler"); return null; }
			if (!safeGrid(budget, standard, production)) return null;
			EmiPlayerInventory inventory = found.getInventory(screen);
			if (inventory == null || !inventory.canCraft(budget.effective, 1)) { deny("missing_materials"); return null; }
			if (!found.canCraft(budget.recipe, new EmiCraftContext<>(screen, inventory, EmiCraftContext.Type.CRAFTABLE,
				production ? EmiCraftContext.Destination.INVENTORY : EmiCraftContext.Destination.NONE, amount))) { deny("handler_rejected"); return null; }
			// Only the ingredient view is wrapped. Every slot/type/identity query sees the plugin's original recipe.
			StandardRecipeHandler<ScreenHandler> view = new StandardRecipeHandler<>() {
				public List<Slot> getInputSources(ScreenHandler h) { return standard.getInputSources(h); }
				public List<Slot> getCraftingSlots(ScreenHandler h) { return standard.getCraftingSlots(h); }
				public List<Slot> getCraftingSlots(EmiRecipe r, ScreenHandler h) { return standard.getCraftingSlots(budget.recipe, h); }
				public Slot getOutputSlot(ScreenHandler h) { return standard.getOutputSlot(h); }
				public boolean supportsRecipe(EmiRecipe r) { return standard.supportsRecipe(budget.recipe); }
			};
			List<ItemStack> stacks = EmiRecipeFiller.getStacks(view, budget.effective, screen, amount);
			if (stacks == null || stacks.size() != budget.effective.getInputs().size()) { deny("missing_materials"); return null; }
			List<ItemStack> exact = new ArrayList<>();
			for (int i = 0; i < stacks.size(); i++) {
				EmiIngredient input = budget.effective.getInputs().get(i);
				ItemStack stack = stacks.get(i).copy();
				if (!input.isEmpty()) {
					long needed = input.getAmount();
					if (needed <= 0 || needed > Integer.MAX_VALUE || stack.getCount() < needed) { deny("invalid_input_amount"); return null; }
					if (production) stack.setCount((int) needed);
				}
				exact.add(stack);
			}
			return exact;
		}
		@Override public boolean craft(Budget budget) { return fill(budget, 1, true); }
		boolean fill(Budget budget, int amount, boolean production) {
			List<ItemStack> stacks = prepare(budget, amount, production);
			if (stacks == null) return false;
			StandardRecipeHandler<ScreenHandler> standard = (StandardRecipeHandler<ScreenHandler>) recipeHandler(budget);
			boolean accepted;
			if (EmiClient.onServer) { EmiClient.sendFillRecipe(standard, screen, syncId, production ? 2 : 0, stacks, budget.recipe); accepted = true; }
			else accepted = EmiRecipeFiller.clientFill(standard, budget.recipe, screen, stacks,
				production ? EmiCraftContext.Destination.INVENTORY : EmiCraftContext.Destination.NONE);
			if (accepted) { EmiSidebars.craft(budget.recipe); MinecraftClient.getInstance().setScreen(screen); }
			return accepted;
		}
	}
}
