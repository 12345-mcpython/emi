# Bookmark crafting regression

Run without Gradle or a game launch:

```powershell
python tests/bookmark-crafting/run.py --java-home $env:JAVA_HOME
```

The test compiles the **real** `BookmarkCrafting.java` and `EmiCraftContext.java` against small isolated fixtures. The current suite has 59 checks: dependency ordering and co-product deduplication, cycles and caps, waits/timeouts, context changes, rejected fills/materials, public start/cancel/status, inventory-discounted budgets, fill-only `NONE`, deterministic multi-output acknowledgements, prediction without raw feedback, ambiguous output changes, candidate-aware filling with original plugin identity, custom craft rejection, own/foreign grid safety, and exact one-batch input limits. Fixtures do not verify Minecraft networking or physical sidebar input.

The package-private `BookmarkCrafting.Access`, `Runner`, `Budget`, and `plan` are the deterministic seam. Public callers use `start(Group, boolean useInventory)`, `tick()`, `isRunning()`, `cancel()`, `fill(Group, boolean all, boolean toInventory)`, and `status()`. `Status` reports `(running, result, reason, completed, operations)`; `completed` counts acknowledged batches and `operations` counts dispatched requests. Accepted is not completed; successful exhaustion is `completed/budget_complete`. Status is not persisted.

## In-game smoke

1. Select a recipe group such as logs -> planks -> crafting table. Open the real player 2×2 or crafting-table 3×3 menu with empty cursor, supply raw materials, and call `start(group, false)`; use the existing client tick hook. Dependencies must finish before their consumers, within the snapshot budgets.
2. Repeat with existing intermediates and `start(group, true)`: `executionRows(group, inventory)` discounts inventory. False supplies null to planning and does not discount requested production. Both modes still require actual fill materials. Collapse/todo changes must not alter execution rows.
3. Select a concrete tag candidate with `cycleIngredient`. With oak and birch available, choose birch and its selected producer; only birch may be consumed. Handler discovery, type checks, slot mapping and packet recipe identity must receive the plugin's original recipe; effective selected inputs determine the actual fill stacks.
4. Test own nonempty grid inputs with additional materials in inventory: only one exact batch may be filled/taken per dispatch. Foreign or locked grid items must reject without changing authoritative contents. Capacity must cover clearing the old grid, outputs and returns. Closing/changing the actual screen, handler or syncId must stop without reopening a screen.
5. On a real server, delay/suppress raw inventory feedback. Client prediction alone must not complete a request or allow a second dispatch. A later raw packet must update the server-inventory mirror and all distinct outputs must each increase by exactly one batch. Greater increases stop as `ambiguous_output_change`; absent confirmation stops at 100 player ticks as `server_ack_timeout`. Check multi-output recipes separately from the vanilla single-output world cases.
6. `fill(group, true, false)` sends at most one 64-batch `Destination.NONE` fill, leaves the runner idle, and reports `filled/fill_only`; ingredients in the grid are not production. Inventory fill starts an acknowledged single batch, or the full plan when `all=true`.
7. Repeat supported crafting through both the EMI server fill path and `EmiClient.onServer=false` vanilla clientFill/ClickSlot path. Selecting this flag is not equivalent to uninstalling server EMI. Custom/nonstandard handlers and standard handlers overriding `craft` must reject, including fill-only.

## Intentional limits

- No machine automation. Both production and fill-only require a `StandardRecipeHandler` using the default `StandardRecipeHandler.craft`; custom overrides/nonstandard handlers reject. Production additionally requires an immediate output slot. The implementation prepares candidate-aware stacks and dispatches the supported fill path directly.
- Automatic requests contain exactly one batch, even when an own grid already holds matching ingredients. Only matching effective input/return stacks may be cleared, all slots must permit taking, and capacity is reserved for grid contents, outputs and returns. Foreign layouts reject; the old layout is not restored.
- Every output must be a distinct, positive, deterministic item. Duplicate outputs, probabilistic/nonitem outputs, self-consuming or returned outputs reject. Nonempty inputs must have positive deterministic amounts, and at least one input must be unambiguously consumed with no remainder. Catalyst-only/no-consumed-input recipes reject because QUICK_MOVE otherwise lacks a one-batch bound. Planning probability support does not imply automatic execution support.
- Only raw server slot/inventory packet data, forwarded after the packet main-thread guard to `observeServerSlot`/`observeServerInventory`, updates the acknowledgement mirror. Neither a local inventory fingerprint nor predicted stacks acknowledge production. All output deltas must equal one exact batch. External concurrent movement of the same output can still make observation ambiguous; there is no transaction ID correlating the craft to the packet.
- At most 256 selected recipes and 4096 requests per run; cycles reject. No default producer lookup, background thread, layout restoration, machine control, or automatic screen reopening. Changed screen/handler/syncId or an occupied cursor invalidates the run.
- The group `Stock`/`Flow` ledger and `CraftingTreeModel` support expected probability amounts, remainders, reusable startup tools, three quantity projections and source-aware candidate choices. Runner restrictions above are deliberately narrower. Dynamic damaged-tool replacement and fluid-container conversion remain outside the model boundary.

## Confirmed evidence — 2026-11-03

The parent agent freshly reran the Java 17 source regressions: runner 59, bookmark groups 62 (including the ordinary recipe-less favorite/null-recipe executionRows regression), base model 79, plans 290 and lifecycle 7, totaling 497 passing checks. Transfer checks also passed; no assertion count was supplied. This document update did not run Gradle or rerun these suites itself.

The latest real integrated-server fixture passed seven scenarios in `fabric/run/crafting-world-smoke/screenshots/crafting-world-smoke/emi-world-smoke-1793714835690/PASS.txt`, with screenshots and authoritative inventory snapshots. It reruns all six scenarios from the historical `emi-world-smoke-1793713632747` artifact and adds exact material withdrawal:

- Player 2×2 log -> planks -> table: `completed=operations=2`, one table, empty grid/cursor.
- Workbench 3×3 two logs -> planks -> chest: `completed=operations=3`, one chest, empty grid/cursor.
- Explicit birch candidate and producer: two acknowledged batches, birch consumed and oak untouched.
- Foreign cobblestone grid: `foreign_or_locked_grid` rejection with server materials unchanged.
- Own prefilled grid with an extra inventory log: exactly one acknowledged batch, four planks and one untouched log, empty grid.
- `EmiClient.onServer=false`: real vanilla clientFill/ClickSlot path, one acknowledged batch and server four planks.
- EMI-server exact material withdrawal: 3 of a real source stack of 64, authoritative source=61, player=3, cursor=0, `completed=3/operations=1` with raw source/destination acknowledgements.

See `../crafting-tree-smoke/world/README.md` for fixture scope. This is public runner/transfer API through real vanilla handler/network evidence, not a physical sidebar-start test, standalone remote-server test, Forge runtime test or arbitrary machine/custom-handler test. The vanilla transfer path without server EMI still lacks world completion evidence; its strict slot/cursor acknowledgement and prediction-suppression timeout limitations remain documented in `../bookmark-transfer/README.md`.

The latest strict title-screen/RecipeScreen smoke passed real GLFW Ctrl/Alt manager input in `fabric/run/crafting-tree-smoke/screenshots/crafting-tree-smoke/1793715023124/PASS.txt` (13 PNGs and roundtrip JSON). It verifies manager quantity scrolling and Alt collapse/expand, not real-world crafting. `RowFavorite` now uses standard Minecraft shadowed text, compact units and scaling to fit 17px. Manual inspection of `08-bookmarks-todo.png` confirmed the white blocks are gone and quantities 1, 4, 8, 12 and 3 are readable; MicroTextRenderer is unchanged. Earlier strict artifact `1793714081752` is historical. Model-mode marker `1793714208968/PASS-MODEL-MODIFIERS.txt` was read back: quantity/folding use explicit model APIs and it states `CtrlAltUI UNVERIFIED`; the old `1793711650627` model artifact is historical and is not used as current-version evidence.

The parent agent also completed final Java 17 offline Fabric and Forge builds after the standard-text rendering change, and checked production classes, mixins and smoke-source isolation in both JARs. This documentation task did not invoke Gradle. Remaining runtime gaps include a standalone remote server, Forge gameplay, other machine handlers, cross-page long-press drags and complete plan import/export interaction; the screenshots do not cover every font/layout/GPU combination.
