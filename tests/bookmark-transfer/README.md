# Bookmark material transfer checks

Run without Gradle:

```powershell
python tests/bookmark-transfer/run.py --java-home "$env:JAVA_HOME"
python tests/bookmark-transfer/compile-production.py --java-home "$env:JAVA_HOME"
```

`run.py` compiles the actual production runner against isolated fixtures and runs assertion tests for exact 3-of-64 withdrawal, partial destination stacks, owned cursor lifecycle, prediction without acknowledgement, timeout return, cancellation before and after a deposit, handler change, foreign cursor, take/insert permissions, full inventory, operation budget, per-tick bound, and unsupported fluid material. It reuses fixture text from the neighboring bookmark-crafting runner without running that runner.

`compile-production.py` compiles the actual runtime against the repository's existing compiled EMI classes and cached mapped Minecraft/dependency jars; it changes neither Gradle configuration nor compiled production output. Temporary Java artifacts are removed automatically.

## Integration

- `start(group, useInventory)` builds raw requirements from `new CraftingTreeModel(EmiBookmarkGroups.tree(group), inventory).missing`, plus standalone recipe-less bookmarks. Recipe intermediates are not transferred as additional demands.
- `tick()` must run on the client tick; `cancel()` requests a bounded cancellation and cursor return on that tick.
- Forward raw server packet contents after the packet main-thread guard: `observeServerSlot(syncId, slot, stack)` including `(-1,-1,cursor)`, and `observeServerInventory(syncId, stacks, cursor)`.
- `status()` reports `(running, result, reason, completed, operations)`. Completed counts acknowledged individual deposited items, not locally predicted movement.

The initial support boundary is an exact vanilla `GenericContainerScreenHandler`, 1–6 rows, storage inventory slots followed by exactly the ordinary 36 player inventory slots. All slot objects must be vanilla `Slot`; output, crafting, armor and modded special handlers are excluded. Initial nonempty cursors are rejected. Fluid demands are explicitly unsupported and never converted to buckets.

When `EmiClient.onServer` is true, one existing `FillRecipeC2SPacket` requests exactly `min(remaining demand, source count, item max, destination capacity)` items from a single real storage source into a single empty player destination. The payload is copied from the actual source stack. No gameplay inventory or cursor is changed locally. Both source and destination must subsequently receive raw server slot packets with the exact expected contents before the budget advances. The empty cursor remains unused, so no new cursor packet is required. Occupied destinations, including partial matching stacks, are excluded because the existing fill protocol clears destinations before filling. Cancellation waits for an in-flight request's bounded acknowledgement and starts no further request.

Without EMI server support, source counts are borrowed by a left PICKUP; one right PICKUP deposits exactly one item, and any remainder returns to its original storage slot. This click path also supports partial destination stacks where item/NBT combine, insertion and maximum count permit.

## Important vanilla acknowledgement limit

Ordinary vanilla click processing may suppress raw S2C slot/cursor packets when the client's prediction matches the server. This implementation intentionally does not treat prediction as acknowledgement and sends no stale-revision/resync probe. If matching raw packets do not arrive within 100 ticks, transfer stops and attempts to return its owned cursor to the original slot. Return itself also waits at most 100 ticks. Consequently normal vanilla servers can stop with `server_ack_timeout` rather than complete the withdrawal. This is an explicit limitation, not a claim of working server-confirmed transfer on every vanilla connection. A full inventory packet supplies source/destination and cursor acknowledgement together. Runtime world verification must distinguish acknowledged completion from local slot changes.

The EMI server branch adds isolated checks for exact 3-of-64 transfer without cursor packets, capacity splitting, occupied destination exclusion, both-slot acknowledgement, no retry after timeout, and cancellation during an in-flight request. The existing server fill protocol has a concurrency limitation: if another actor removes the source or occupies the destination between client validation and server handling, its existing fallback can offer/drop items. This client-only change cannot make that protocol atomic; it makes no protocol or server behavior changes.

Each tick emits at most one operation, at most 4096 total; the click path reserves its last operation for cursor return. A changed screen/handler causes an immediate stop without interacting with the new handler; vanilla screen close owns returning that cursor. If the original source can no longer accept the cursor, status explicitly reports `return_blocked_cursor_remaining`. No code drops items, uses QUICK_MOVE, sets actual inventory/cursor stacks, or creates new gameplay items.
