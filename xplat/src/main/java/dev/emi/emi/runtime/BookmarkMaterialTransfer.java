package dev.emi.emi.runtime;

import java.util.ArrayList;
import java.util.List;

import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.ItemEmiStack;
import dev.emi.emi.bom.CraftingTreeModel;
import dev.emi.emi.bom.FlatMaterialCost;
import dev.emi.emi.bom.MaterialTree;
import dev.emi.emi.runtime.EmiBookmarkGroups.Group;
import dev.emi.emi.network.EmiNetwork;
import dev.emi.emi.network.FillRecipeC2SPacket;
import dev.emi.emi.platform.EmiClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

/** Exact, bounded item-only withdrawals. No QUICK_MOVE, fabricated stacks or client-prediction acknowledgements. */
public final class BookmarkMaterialTransfer {
	static final int MAX_OPERATIONS = 4096, ACK_TIMEOUT = 100, MAX_MATERIALS = 256;
	private static Runner active;
	private static Status last = new Status(false, "idle", "not_started", 0, 0);
	public record Status(boolean running, String result, String reason, int completed, int operations) {}
	private BookmarkMaterialTransfer() {}
	public static Status status() { return active == null ? last : active.status(); }
	public static boolean isRunning() { return active != null && active.running; }
	public static void cancel() { if (active != null) active.cancelReason = "user_cancelled"; }
	private static boolean reject(String reason) { last = new Status(false, "rejected", reason, 0, 0); return false; }

	public static boolean start(Group group, boolean useInventory) {
		if (isRunning()) return false;
		if (group == null) return reject("no_group");
		try {
			ClientAccess access = ClientAccess.create();
			if (access == null) return reject("unsupported_container_or_cursor");
			List<EmiStack> stock = new ArrayList<>();
			for (int slot : access.destinations) if (!access.stack(slot).isEmpty()) stock.add(EmiStack.of(access.stack(slot)));
			// EmiPlayerInventory merges equal stacks by mutating their amounts. Keep
			// our standalone allocation snapshot separate to avoid counting stock twice.
			EmiPlayerInventory inventory = useInventory ? new EmiPlayerInventory(stock.stream().map(EmiStack::copy).toList()) : null;
			List<Need> needs = new ArrayList<>();
			MaterialTree tree = EmiBookmarkGroups.tree(group);
			CraftingTreeModel model = tree == null ? null : new CraftingTreeModel(tree, inventory);
			if (model != null) for (FlatMaterialCost cost : model.missing) add(needs, cost.ingredient, cost.amount);
			// The synthetic recipe tree deliberately excludes standalone bookmarks.
			// Allocate only stock not already consumed by the recipe plan.
			List<Need> available = new ArrayList<>();
			for (EmiStack stack : stock) add(available, stack, stack.getAmount());
			if (useInventory && model != null) for (FlatMaterialCost cost : model.available) consume(available, cost.ingredient, cost.amount);
			for (var row : EmiBookmarkGroups.executionRows(group, null)) if (row.recipe == null) {
				long amount = row.remainingAmount;
				if (useInventory) amount -= consume(available, row.ingredient, amount);
				add(needs, row.ingredient, amount);
			}
			if (needs.size() > MAX_MATERIALS) return reject("material_limit");
			if (needs.isEmpty()) { last = new Status(false, "completed", "budget_complete", 0, 0); return true; }
			active = new Runner(access, needs);
			return true;
		} catch (UnsupportedOperationException e) { return reject("unsupported_material"); }
		catch (RuntimeException e) { EmiLog.error("Unable to start bookmark material transfer", e); return reject("exception"); }
	}
	static final class Need {
		final EmiIngredient ingredient;
		long remaining;
		Need(EmiIngredient ingredient, long amount) { this.ingredient = ingredient; remaining = amount; }
		boolean matches(ItemStack stack) {
			if (stack.isEmpty()) return false;
			for (EmiStack candidate : ingredient.getEmiStacks()) if (candidate.isEqual(EmiStack.of(stack))) return true;
			return false;
		}
	}
	static void add(List<Need> needs, EmiIngredient ingredient, long amount) {
		if (amount <= 0) return;
		if (ingredient.getEmiStacks().isEmpty() || ingredient.getEmiStacks().stream().anyMatch(s -> !(s instanceof ItemEmiStack))) {
			throw new UnsupportedOperationException("Only item materials can be withdrawn; fluids are never converted to buckets");
		}
		needs.add(new Need(ingredient, amount));
	}
	private static long consume(List<Need> stock, EmiIngredient ingredient, long amount) {
		long used = 0;
		for (Need entry : stock) {
			boolean match = false;
			for (EmiStack a : ingredient.getEmiStacks()) for (EmiStack b : entry.ingredient.getEmiStacks()) if (a.isEqual(b)) match = true;
			if (match) { long take = Math.min(amount - used, entry.remaining); entry.remaining -= take; used += take; }
			if (used == amount) break;
		}
		return used;
	}
	public static void tick() {
		Runner runner = active;
		if (runner == null) return;
		try { runner.tick(); }
		catch (RuntimeException e) { runner.stop("exception_cursor_may_remain"); EmiLog.error("Stopped bookmark material transfer", e); }
		if (!runner.running && active == runner) { last = runner.status(); active = null; }
	}
	/** Call with raw packet data on the client thread, including syncId=-1 cursor packets. */
	public static void observeServerSlot(int syncId, int slot, ItemStack stack) {
		if (active != null && active.access instanceof ClientAccess access) access.observe(syncId, slot, stack);
	}
	public static void observeServerInventory(int syncId, List<ItemStack> stacks, ItemStack cursor) {
		if (active != null && active.access instanceof ClientAccess access && syncId == access.handler.syncId) {
			for (int i = 0; i < stacks.size(); i++) access.observe(syncId, i, stacks.get(i));
			access.observe(-1, -1, cursor);
		}
	}

	interface Access {
		boolean valid(); long time(); ItemStack stack(int slot); ItemStack cursor();
		List<Integer> sources(); List<Integer> destinations();
		boolean take(int slot); boolean insert(int slot, ItemStack stack); int capacity(int slot, ItemStack stack);
		long slotVersion(int slot); long cursorVersion(); ItemStack serverStack(int slot); ItemStack serverCursor();
		void click(int slot, int button);
		default boolean exactSupported() { return false; }
		default void transferExact(int source, int destination, int count) { throw new UnsupportedOperationException(); }
	}
	static final class Runner {
		final Access access; final List<Need> needs;
		boolean running = true; String reason = "ready", cancelReason;
		int completed, operations, source = -1, pendingSlot = -1;
		long lastTick = Long.MIN_VALUE, sentAt, slotVersion, cursorVersion;
		ItemStack owned, expectedSlot, expectedCursor;
		Need current;
		int action; // 1 pickup, 2 one-item deposit, 3 return, 4 server exact fill
		int exactDestination, exactCount;
		long exactDestinationVersion;
		ItemStack exactDestinationStack;
		Runner(Access access, List<Need> needs) { this.access = access; this.needs = needs; }
		Status status() { return new Status(running, running ? "running" : reason.equals("budget_complete") ? "completed" : "stopped", reason, completed, operations); }
		void stop(String reason) { running = false; this.reason = reason; }
		boolean ownedCursor() {
			return owned != null && !access.cursor().isEmpty() && ItemStack.canCombine(owned, access.cursor())
				&& ItemStack.areEqual(access.cursor(), expectedCursor == null ? owned : expectedCursor);
		}
		void tick() {
			if (!running) return;
			if (!access.valid()) { stop(ownedCursor() ? "screen_changed_cursor_remaining" : "screen_or_handler_changed"); return; }
			long now = access.time();
			if (now == lastTick) return;
			if (now < lastTick) { cancelReason = "clock_changed"; }
			lastTick = now;
			if (pendingSlot >= 0) {
				boolean ack = access.slotVersion(pendingSlot) > slotVersion
					&& ItemStack.areEqual(access.serverStack(pendingSlot), expectedSlot);
				if (action == 4) {
					// This protocol never uses the cursor. Unchanged empty cursor need not
					// receive its own packet, but both changed slots must be acknowledged.
					if (!access.cursor().isEmpty() || !access.serverCursor().isEmpty()) { stop("cursor_changed"); return; }
					ack &= access.slotVersion(exactDestination) > exactDestinationVersion
						&& ItemStack.areEqual(access.serverStack(exactDestination), exactDestinationStack);
				} else ack &= access.cursorVersion() > cursorVersion && ItemStack.areEqual(access.serverCursor(), expectedCursor);
				if (!ack) {
					if (now - sentAt < ACK_TIMEOUT) return;
					pendingSlot = -1;
					if (action == 3) { stop(access.cursor().isEmpty() ? "server_ack_timeout" : "server_ack_timeout_cursor_remaining"); return; }
					cancelReason = "server_ack_timeout";
				} else {
					pendingSlot = -1;
					if (action == 2) { current.remaining--; completed++; }
					if (action == 4) { current.remaining -= exactCount; completed += exactCount; }
					if (action == 3) { owned = null; source = -1; if (cancelReason != null) { stop(cancelReason); return; } }
				}
			}
			if (cancelReason != null) { restore(); return; }
			if (ownedCursor()) {
				if (operations >= MAX_OPERATIONS - 1) { cancelReason = "operation_limit"; restore(); return; }
				if (current.remaining <= 0) { restore(); return; }
				int destination = destination(access.cursor());
				if (destination < 0) { cancelReason = "inventory_full"; restore(); return; }
				ItemStack next = access.stack(destination).isEmpty() ? access.cursor().copy() : access.stack(destination).copy();
				next.setCount(access.stack(destination).getCount() + 1);
				ItemStack cursor = access.cursor().copy(); cursor.decrement(1);
				send(destination, 1, next, cursor, 2); return;
			}
			if (!access.cursor().isEmpty()) { stop("cursor_changed"); return; }
			owned = null; source = -1;
			current = needs.stream().filter(n -> n.remaining > 0).findFirst().orElse(null);
			if (current == null) { stop("budget_complete"); return; }
			if (operations >= (access.exactSupported() ? MAX_OPERATIONS : MAX_OPERATIONS - 2)) { stop("operation_limit"); return; }
			if (access.exactSupported()) { exact(); return; }
			for (int slot : access.sources()) {
				ItemStack stack = access.stack(slot);
				if (!current.matches(stack) || !access.take(slot) || !access.insert(slot, stack) || access.capacity(slot, stack) < stack.getCount()) continue;
				if (destination(stack) < 0) { stop("inventory_full"); return; }
				source = slot; owned = stack.copy();
				ItemStack empty = stack.copy(); empty.setCount(0);
				send(slot, 0, empty, stack.copy(), 1); return;
			}
			stop("missing_source_material");
		}
		void exact() {
			for (int slot : access.sources()) {
				ItemStack stack = access.stack(slot);
				if (!current.matches(stack) || !access.take(slot)) continue;
				for (int destination : access.destinations()) {
					// Fill clears the declared destination first, so never send an occupied
					// player slot (even a matching partial stack) to the existing protocol.
					if (!access.stack(destination).isEmpty() || !access.insert(destination, stack)) continue;
					int count = (int) Math.min(current.remaining, Math.min(stack.getCount(),
						Math.min(stack.getMaxCount(), access.capacity(destination, stack))));
					if (count <= 0) continue;
					pendingSlot = slot; action = 4; exactDestination = destination; exactCount = count;
					expectedSlot = stack.copy(); expectedSlot.decrement(count);
					exactDestinationStack = stack.copy(); exactDestinationStack.setCount(count);
					slotVersion = access.slotVersion(slot); exactDestinationVersion = access.slotVersion(destination);
					sentAt = access.time(); operations++; reason = "awaiting_server_inventory";
					access.transferExact(slot, destination, count); return;
				}
				stop("inventory_full"); return;
			}
			stop("missing_source_material");
		}
		int destination(ItemStack cursor) {
			for (int slot : access.destinations()) {
				ItemStack stack = access.stack(slot);
				if ((stack.isEmpty() || ItemStack.canCombine(stack, cursor)) && access.insert(slot, cursor)
					&& stack.getCount() < Math.min(cursor.getMaxCount(), access.capacity(slot, cursor))) return slot;
			}
			return -1;
		}
		void restore() {
			if (access.cursor().isEmpty()) { owned = null; source = -1; if (cancelReason != null) stop(cancelReason); return; }
			if (!ownedCursor() || source < 0) { stop("cursor_changed"); return; }
			ItemStack stack = access.stack(source), cursor = access.cursor();
			if ((!stack.isEmpty() && !ItemStack.canCombine(stack, cursor)) || !access.insert(source, cursor)
				|| stack.getCount() + cursor.getCount() > Math.min(cursor.getMaxCount(), access.capacity(source, cursor)) || operations >= MAX_OPERATIONS) {
				stop("return_blocked_cursor_remaining"); return;
			}
			ItemStack returned = cursor.copy(); returned.setCount(stack.getCount() + cursor.getCount());
			ItemStack empty = cursor.copy(); empty.setCount(0);
			send(source, 0, returned, empty, 3);
		}
		void send(int slot, int button, ItemStack stack, ItemStack cursor, int action) {
			pendingSlot = slot; expectedSlot = stack; expectedCursor = cursor; this.action = action;
			slotVersion = access.slotVersion(slot); cursorVersion = access.cursorVersion(); sentAt = access.time();
			operations++; reason = action == 3 ? "returning_cursor" : "awaiting_server_inventory";
			access.click(slot, button);
		}
	}

	private static final class ClientAccess implements Access {
		final HandledScreen<?> screen; final GenericContainerScreenHandler handler;
		final List<Integer> sources = new ArrayList<>(), destinations = new ArrayList<>();
		final List<ItemStack> server = new ArrayList<>(); final long[] versions;
		ItemStack cursor; long cursorVersion, sequence;
		ClientAccess(HandledScreen<?> screen, GenericContainerScreenHandler handler) {
			this.screen = screen; this.handler = handler; versions = new long[handler.slots.size()];
			var player = MinecraftClient.getInstance().player;
			int size = handler.getRows() * 9;
			if (handler.getRows() < 1 || handler.getRows() > 6 || handler.slots.size() != size + 36 || handler.getInventory().size() != size) throw new IllegalArgumentException("Invalid generic container");
			for (int i = 0; i < handler.slots.size(); i++) {
				Slot slot = handler.slots.get(i);
				if (slot.getClass() != Slot.class) throw new IllegalArgumentException("Special slots unsupported");
				if (i < size) {
					if (slot.inventory != handler.getInventory() || slot.getIndex() != i) throw new IllegalArgumentException("Invalid storage range");
					sources.add(i);
				} else {
					int expected = i < size + 27 ? i - size + 9 : i - size - 27;
					if (slot.inventory != player.getInventory() || slot.getIndex() != expected) throw new IllegalArgumentException("Invalid player range");
					destinations.add(i);
				}
				server.add(slot.getStack().copy());
			}
			cursor = handler.getCursorStack().copy();
		}
		static ClientAccess create() {
			MinecraftClient client = MinecraftClient.getInstance();
			if (!client.isOnThread() || client.player == null || !(client.currentScreen instanceof HandledScreen<?> screen)
				|| screen.getScreenHandler().getClass() != GenericContainerScreenHandler.class || !screen.getScreenHandler().getCursorStack().isEmpty()) return null;
			ClientAccess access = new ClientAccess(screen, (GenericContainerScreenHandler) screen.getScreenHandler());
			return access.valid() ? access : null;
		}
		@Override public boolean valid() {
			MinecraftClient client = MinecraftClient.getInstance();
			return client.isOnThread() && client.player != null && client.world != null && client.interactionManager != null
				&& client.currentScreen == screen && screen.getScreenHandler() == handler && client.player.currentScreenHandler == handler;
		}
		@Override public long time() { return MinecraftClient.getInstance().player.age; }
		@Override public ItemStack stack(int slot) { return handler.slots.get(slot).getStack(); }
		@Override public ItemStack cursor() { return handler.getCursorStack(); }
		@Override public List<Integer> sources() { return sources; }
		@Override public List<Integer> destinations() { return destinations; }
		@Override public boolean take(int slot) { return handler.slots.get(slot).canTakeItems(MinecraftClient.getInstance().player); }
		@Override public boolean insert(int slot, ItemStack stack) { return handler.slots.get(slot).canInsert(stack); }
		@Override public int capacity(int slot, ItemStack stack) { return handler.slots.get(slot).getMaxItemCount(stack); }
		@Override public long slotVersion(int slot) { return versions[slot]; }
		@Override public long cursorVersion() { return cursorVersion; }
		@Override public ItemStack serverStack(int slot) { return server.get(slot); }
		@Override public ItemStack serverCursor() { return cursor; }
		void observe(int syncId, int slot, ItemStack stack) {
			if (!valid()) return;
			if (syncId == -1 && slot == -1) { cursor = stack.copy(); cursorVersion = ++sequence; }
			else if (syncId == handler.syncId && slot >= 0 && slot < server.size()) { server.set(slot, stack.copy()); versions[slot] = ++sequence; }
		}
		@Override public boolean exactSupported() { return EmiClient.onServer; }
		@Override public void transferExact(int source, int destination, int count) {
			if (!valid() || !EmiClient.onServer || !handler.getCursorStack().isEmpty()
				|| !sources.contains(source) || !destinations.contains(destination)) throw new IllegalStateException("Invalid exact transfer");
			Slot from = handler.slots.get(source), to = handler.slots.get(destination);
			ItemStack actual = from.getStack();
			if (!to.getStack().isEmpty() || !from.canTakeItems(MinecraftClient.getInstance().player)
				|| !to.canInsert(actual) || count <= 0 || count > actual.getCount()
				|| count > Math.min(actual.getMaxCount(), to.getMaxItemCount(actual))) throw new IllegalStateException("Exact transfer changed");
			// This is a request copied from a real source stack; the existing server
			// protocol removes matching source items. No local inventory is mutated.
			ItemStack request = actual.copy(); request.setCount(count);
			EmiNetwork.sendToServer(new FillRecipeC2SPacket(handler, 0, List.of(from), List.of(to), null, List.of(request)));
		}
		@Override public void click(int slot, int button) {
			MinecraftClient client = MinecraftClient.getInstance();
			client.interactionManager.clickSlot(handler.syncId, slot, button, SlotActionType.PICKUP, client.player);
		}
	}
}
