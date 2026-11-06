# Bookmark group production-model regression

Run without Gradle or a game process:

```powershell
python tests/bookmark-groups/run.py --java-home D:\java\zulu17.68.203-ca-jdk17.0.20.1-win_x64
```

The runner compiles the current production `EmiBookmarkGroups`, `CraftingTreeModel`, `MaterialNode`, and related material classes against the existing minimal game fixtures and an existing Gson JAR. It uses a temporary directory, downloads nothing, and does not read prior patches or build output. Pass `--gson PATH` when the default Gson cache is unavailable.

The original 31 checks and the complete 97-check baseline are retained. The current suite has 141 checks (97 baseline + 44 group-movement checks), including shared producer budgets, multiplier persistence, original plugin object preservation, folded subtree identity/movement/deletion, selected candidate persistence, recipe wrappers, and the following ledger regressions:

- Four crafts require one fully reusable startup tool; the tool remains a remainder.
- Finished-output, raw-material, and reusable-tool inventory reduce remaining quantities without altering full or explicit quantities or mutating the snapshot.
- A producer's container return can satisfy a downstream recipe inside the same folded box and disappears from its external boundary.
- An unused container return has `Role.REMAINDER`.
- A 50% output requires two whole execution batches for one requested unit; its expected boundary yield is one unit. Synthetic goals must not inherit the output chance.
- Probabilistic consumed inputs retain the largest single-slot startup requirement, and probabilistic coproducts aggregate expected amounts before rounding.
- Two folded boxes allocate shared raw inventory once across the whole group.
- Folded raw inputs retain original candidate ingredients and source anchors, so cycling them updates the original producer's effective fill recipe.
- Group tree snapshots resolve selected recipes without consulting global BoM defaults.
- Ordinary favorites with a null recipe support both execution and display projections in default, named crafting, and collapsed groups; explicit multipliers preserve their amounts in all quantity views. This reproduces the null-key lookup failure discovered in the real client.
- Shaped empty cells with the real empty-stack amount of one are skipped before catalyst accounting. Five unused crafting-grid positions must not produce a phantom input `5` and a phantom remainder `5`; full, inventory-adjusted, unfolded and folded projections are covered.
- A concrete producer output consumed by a generic candidate ingredient retains its concrete identity. Four oak planks transferred into an any-plank input remain producer results, rather than a zero result plus a fake four-plank remainder; original candidates remain unchanged.
- In a crafting group, an unselected generic input automatically uses the first matching other producer in bookmark order. The one-table/four-plank baseline remains, while ten tables calculate forty oak planks and ten logs. Root and group-header adjustments, tree root detection, folded/unfolded budgets, and finished-product/intermediate/raw inventory deductions agree. Folded rows may retain a zero calculated demand when their explicit bookmark budget still exposes that input.
- Effective fill recipes receive a concrete automatic candidate with the original input amount and chance, without mutating original plugin ingredients or candidate stacks. An explicit birch selection and its persisted choice override an available oak producer. With no same-group producer, the original generic input passes through; other groups cannot lend producers. Two matching producers resolve by bookmark order, independently of candidate order.
- Candidate cycling is relative to the current effective choice, including an automatic choice. The source ingredient still retains all candidates for the editor.

Group-movement regressions call the production `moveGroup(source, target, after)` API directly:

- Before and after insertion in both directions move every real anchor in its original order, retain both memberships, and leave the target whole. Each real reorder increments revision once.
- The two-argument overload retains before-target semantics. A null target appends for either `after` value.
- Adjacent identical order, repeating a move, same source/target, null source, stale source/target identities, and empty source/target groups are strict no-ops: favorite object order, saved metadata, and revision all stay unchanged.
- Default-group source and target retain ordinary favorite membership. Scattered default members are collected in source order when moved; target boundaries use the first/last target anchor rather than splitting the target span.
- A collapsed group carries its hidden anchors while preserving group IDs, todo/crafting/collapsed state, collapsed recipes, explicit multipliers, full-recipe flags, selected candidates, and the original plugin ingredient object. A metadata save/load round trip after movement verifies those settings and memberships against the unchanged favorite object list.

Fresh verification on Java 17: the unmodified test suite passed 97 checks before additions; the expanded suite passed all 141 checks against the current production API using the command above. No Gradle build or game process was used. The isolated suite exercises model behavior; NEI-style live insertion and drag-ghost rendering still require client UI validation.

`Row` quantity semantics are explicit: `explicitAmount` projects the explicit bookmark batch budgets; `calculatedAmount` projects the inventory-free calculated plan; `remainingAmount` projects the inventory-adjusted plan. `amount` aliases `remainingAmount` for existing consumers. The same group-wide ledger projects ordinary recipe and folded-box boundaries. Outputs consumed outside a box remain visible transfers; internal production/consumption cancels. Root recipe outputs are retained at zero when inventory satisfies the goal. Display and source anchors are distinct on folded inputs.

Known boundaries:

- This is a production-source isolated regression, not a Minecraft container, network, or physical modifier-key smoke test.
- Quantities use rounded expected yields with a minimum raw input slot requirement; they are not a guarantee of probabilistic success. Dynamic damaged-tool replacement and fluid-container conversion are not implemented.
- The ledger replays `CraftingTreeModel` whole-batch budgets and dependency order. It does not change producer selection, cycle cutting, material-tree depth/node caps, or execution support for machine/custom handlers.
- Explicit folded quantities use explicit bookmark budgets in the group ledger. They do not reproduce NEI's independent shadow-box recalculation and every `recipeRelations` boundary rule.
- Candidate provenance is retained by emitting separate ingredient rows for different original source ingredients/anchors, even when they currently resolve to the same material. This intentionally avoids a lossy merged candidate editor.
- Catalysts are recognized through the material model's self-returning input semantics. Display-only `getCatalysts()` metadata is forwarded to effective recipes but is not invented as an additional consumed input.
- An inventory argument is a snapshot; callers must invalidate/rebuild it when contents change. Cache identity alone cannot detect in-place inventory mutations.
