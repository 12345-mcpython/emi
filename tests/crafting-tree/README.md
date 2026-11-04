# Crafting tree calculation regressions

Run from the EMI checkout using JDK 17:

```powershell
.\tests\crafting-tree\run.ps1 -JavaHome 'D:\path\to\jdk17'
```

The script compiles the actual `MaterialNode`, `MaterialTree`, `TreeCost`, `CraftingTreeModel`, and cost/state classes. Small test-only stubs replace Minecraft/Guava/API infrastructure, not the calculation implementation. Output classes stay in `xplat/build/crafting-tree-regression`; no Gradle dependencies or game startup are needed.

Cases cover shared DAG demand and one-time batch rounding, globally allocated raw/intermediate/root inventory, actual inventory allocation, topological depth and recipe ordering, probabilistic materials and integer expected-output recipe batches, zero-chance production, duplicate ingredient probability weighting, sibling/deep co-products and multi-output recipe sharing, startup material constraints, container returns, shared/mixed/crafted catalysts, partial self-return quantities, clearing root/child recipes, cycle termination, huge-count saturation, concrete-before-alternative inventory allocation, and depth/node expansion budgets.

Model conventions:

- `required` is the no-inventory raw plan; `missing` is its separately calculated inventory-adjusted plan. `available` records actual allocated player stacks, including intermediates and catalysts. It is not `required - missing`.
- `leftovers` includes plan surplus, returned containers, and retained catalysts. Unallocated player inventory is excluded.
- Production uses whole batches based on expected output (`ceil(demand / (output * chance))`). Probabilistic leaf consumption remains `ChanceMaterialCost`, so it can be displayed approximately using EMI's existing effective-amount rule. This is an estimate, not a probability guarantee.
- Partial self-returns remain consumables. Returns are available to independent branches; they cannot pre-finance their own producing recipe's startup inputs. The planner deliberately does not optimize sequential return reuse within a batch sequence.
- Expansion is bounded at depth 128 and 4096 expanded material nodes. Truncated dependencies remain visible unresolved requirements with `Entry.truncated`; cycles expose `Entry.cycle`.
- Inventory allocation prefers concrete ingredients before broad alternatives that are ready at the same time. It is a deterministic planning heuristic, not a global combinatorial inventory optimizer.
