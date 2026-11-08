# Porting EMI from 1.21.1 to 26.1.2

Status: **1.21.4 checkpoint delivered** (builds + regression harnesses green). Target 26.1.2 not reached yet.

## Branches

| Branch        | Contents                                                                                       |
|---------------|------------------------------------------------------------------------------------------------|
| `1.21`        | last fully featured release line (Minecraft 1.21.1)                                            |
| `1.21.4-port` | the working 1.21.4 checkpoint (`b49d133`, tag `1.21.4-port`); the next hops continue from here |
| `1.21.5-wip`  | version bump to 1.21.5 plus the blocker inventory; does not compile yet                        |

## Checkpoint plan

`1.21.1 → 1.21.4 → 1.21.5 → 1.21.8 → 1.21.10 → 1.21.11 → 26.1.2`

1.21.3 is skipped: it shares the 1.21.4 API family and, like 1.21.2-1.21.4, has no JEI release.

## Version matrix

| Checkpoint    | MC      | NeoForge   | Fabric API     | JEI         | Loom     | Notes                                   |
|---------------|---------|------------|----------------|-------------|----------|-----------------------------------------|
| 1.21.4 (done) | 1.21.4  | 21.4.161   | 0.119.4+1.21.4 | none        | 1.9.436  | jemi excluded                           |
| 1.21.5        | 1.21.5  | 21.5.101   | 0.128.2+1.21.5 | 21.4.0.27   | 1.10.455 | needs the model/render rework           |
| 1.21.8        | 1.21.8  | 21.8.57    | tbd            | 24.2.0.6    | tbd      | absorbs the 1.21.6 GUI rework           |
| 1.21.10       | 1.21.10 | 21.10.64   | tbd            | 26.3.0.31   | tbd      | absorbs the 1.21.9 debug/font rework    |
| 1.21.11       | 1.21.11 | 21.11.48   | tbd            | 27.54.0.116 | tbd      | `ResourceLocation` becomes `Identifier` |
| 26.1.2        | 26.1.2  | 26.1.2.126 | tbd            | 29.2.0.21   | tbd      | Java 25                                 |

Gradle is 8.14 and the architectury plugin is 3.4.164 for every checkpoint so far. newer architectury plugins
select a loom API (`LoomInterface114`) that the published looms do not implement, so the plugin has to stay at 3.4.x.

## What the 1.21.4 checkpoint changed

- `ToastComponent` → `ToastManager`, `Toast#render(GuiGraphics, Font, long)` plus `getWantedVisibility`.
- Recipe results come from the display registry:
  `recipe.display() → RecipeDisplay#result() → SlotDisplay#resolveForFirstStack`.
  Ingredients come from `Recipe#placementInfo()`.
- `GuiGraphics` blits take a `Function<ResourceLocation, RenderType>`; `setColor` is gone (colours are tracked in
  `EmiDrawContext`).
- Shader hooks (`GameRenderer#getPositionTexShader`, `RenderSystem#applyModelViewMatrix`) are gone; `EmiPort` keeps them
  as no-ops.
- Tooltips: `ClientTooltipComponent#getHeight(Font)` and `renderImage(..., int width, int height, ...)`.
- Items render through `ItemStackRenderState` / `ItemModelResolver`; the vertex batcher (`StackBatcher`) is disabled.
- Registry access is lookup based: `RegistryAccess#lookupOrThrow`, `Registry#get` returns holders, `Registry#getTags`,
  `Ingredient#items()` yields item holders.
- Fabric: `PreparableReloadListener#reload` takes `(barrier, manager, prepareExecutor, applyExecutor)`.
- NeoForge: `AddClientReloadListenersEvent#addListener`, no `RecipesUpdatedEvent`, `TextureAtlas.LOCATION_BLOCKS`,
  `ModelEvent.RegisterAdditional#register(ResourceLocation)`.

### Known degradations on 1.21.4

Started intentionally so the checkpoint could build; each has a TODO in the code.

1. Recipe browsing only works in singleplayer: `ProxyRecipeManager` reads recipes from the integrated server.
   Multiplayer still has
   to be served by the 1.21.2+ `RecipeDisplay` / recipe-info payloads, because the client keeps no recipe table there.
2. jemi/JEI integration is excluded (no JEI build exists for 1.21.2-1.21.4).
3. `StackBatcher` is a no-op shim, everything draws through `GuiGraphics`.
4. Recipe screenshots render without the orthographic projection override.
5. Fuel maps are empty, armour anvil-material recipes and the craft-grid dimension checks are gone.

## 1.21.5 blockers (`1.21.5-wip`)

- `net.minecraft.client.resources.model.*` model types moved (`BakedModel`, `BakedModelManager`, `ModelManager`,
  `ModelResourceLocation`).
- `com.mojang.blaze3d.vertex.VertexBuffer` / `BufferUploader` moved; `EmiPort` and any remaining batching code must
  follow.
- `ArmorItem` is gone (armour is component data), so `VanillaPlugin` needs the item-attribute route.
- `SmithingTransformRecipe` / `SmithingTrimRecipe` accessors changed again (fields are no longer `template`/`base`/
  `addition`).
- `ItemRenderer#renderModelLists` is gone; item rendering goes through render states.
- The 1.21.5 primer's "Render Pipeline Rework" and "Model Rework" sections cover the rest.

## 26.1.2 specifics to plan for

- 1.21.6 GUI rework (`GuiElementRenderState`), 1.21.9 debug/font pipelines, 1.21.11 package renames
  (`ResourceLocation` → `Identifier`) and the render type shuffle, 26.1 Java 25 plus the item-instance/stack-template
  rework.
- Each of those changes the same rendering and recipe code that is already degraded here, so the recipe-display pipeline
  and the
  item render state work should land before the later hops.

## Branch layout

| Branch | Contents |
| --- | --- |
| `1.21` | Minecraft 1.21.1, Mojang mappings; production line and the base for all later hops |
| `1.21.4-port` | the working 1.21.4 checkpoint (tag `1.21.4-checkpoint`) |
| `1.21.5-wip` | 1.21.5 version bump plus the first baked-model removal; does not compile |

## Code cleanup review (1.21 branch)

Commits `832ee4d` (13 files) and `2ec7b05` (124 files, `final` modifiers, `StringBuilder`, `isEmpty()`,
`getFirst()/getLast()`, collection rewrites) were reviewed hunk by hunk. **No behavioural break was found.**

Evidence:

* `:fabric:build` and `:neoforge:build` both succeed on `1.21`.
* Independent regression harnesses pass: crafting tree 79 assertions, crafting plans 290 + 7 lifecycle,
  bookmark groups 141, bookmark crafting 59, bookmark material transfer (click / EMI server exact, partial,
  raw ack, cancel, screen, cursor, permissions, full, bounds, fluid).

### Verified equivalences

* The only control-flow change is `CraftingTreeModel`'s dependency scan: `if (…) ready = false;` became
  `{ ready = false; break; }`. The loop only decides whether any dependency is still pending, so breaking early
  is equivalent.
* `tag.getEmiStacks().containsAll(…)` became `new HashSet<>(tag.getEmiStacks()).containsAll(…)` in `BoM` and
  `RecipeDefaults`. `EmiStack.equals` compares `getKey()` first and `hashCode()` is `getKey().hashCode()`, so
  equal stacks always share a hash; membership results are unchanged (only the complexity drops from O(n²) to O(n)).
* Every dropped `public` sits inside an `interface` (`EmiIngredient`, `Comparison.Predicate`/`HashFunction`,
  `StandardRecipeHandler`, `EmiRecipeHandler`, `EmiDragDropHandler.BoundsBased`/`SlotBased`,
  `EmiTooltipComponent.TextRenderData`/`TooltipRenderData`, `EmiRegistryAdapter.StackConstructor`, the mixin
  accessors). Interface members and nested types are implicitly public / public static, so the published API
  surface did not change.
* New `final` fields (`EmiRecipeCategory.id`/`sorter`, `CraftingTreeModel.Entry.recipe`,
  `EmiNameWidget.x`/`y`, `BasicEmiRecipe.inputs`/`catalysts`, `StackBatcher.ClaimedCollection` sets) have no
  remaining writes — the code still compiles.
* The mechanical rewrites are equivalent: `size() == 0` → `isEmpty()`, `get(0)`/`get(size() - 1)` →
  `getFirst()`/`getLast()`, `String +=` → `StringBuilder` (always converted back through `toString()` in the
  same order), `Collectors.toCollection(ArrayList::new)` → `new ArrayList<>(…)`, the `EmiBind` modifier
  `if`/`else if` chain → `switch` with the same three cases, `Stream.concat(List.of(…).stream(), …)` →
  `Stream.concat(Stream.of(…), …)`, redundant casts (`((Object) screen)`, `((EmiPacket) payload)`),
  `private class` → `private static class` (compiles, so it never touched outer instance state) and removed
  generic witnesses (target typing keeps the same types).

### Items to keep in mind

1. `final` was added to public API fields (`EmiRecipeCategory.id`/`sorter`, `CraftingTreeModel.Entry.recipe`).
   Any future in-place reload or re-resolution has to rebuild those objects instead of mutating them.
2. The `HashSet` rewrites assume `EmiStack.equals` implies an identical `getKey()`. That holds today; if `equals`
   ever becomes finer grained than the key, those two `containsAll` calls silently become stricter.

## Smoke fixtures

`tests/crafting-tree-smoke` is an opt-in fixture that only participates in the build when Gradle is started with
`-I tests/crafting-tree-smoke/init.gradle`; it is not part of any production source set, so a plain `build`
never compiles it.

State on the 1.21 branch:

* Title-screen fixture (`CraftingTreeSmoke`) — **ported to Mojang mappings and compiling**:
  `:fabric:craftingTreeSmokeClasses` succeeds and emits
  `fabric/build/classes/java/craftingTreeSmoke/dev/emi/emi/test/CraftingTreeSmoke*.class`.
  Run it with `gradlew -I tests/crafting-tree-smoke/init.gradle :fabric:runClient` (needs a desktop with GL and
  may fail the Robot modifier-key assertions on machines where the GLFW window cannot take the foreground).
* World fixture (`tests/crafting-tree-smoke/world`, `CraftingWorldSmoke`) — ported mechanically but still has
  about 60 compile errors, all vanilla member/signature differences: `Player#changeGameMode` → `setGameMode`,
  `Inventory#setStack` → `setItem`, the `GameRules` key names, and the world-creation flow
  (`createWorldOpenFlows().createFreshLevel(…)` plus `WorldPresets.FLAT` dimension holders).

## Code cleanup review, second batch

The follow-up cleanup converted several types to records and adopted Java 21 APIs. Two consequences were found and
handled.

### Toolchain

The production code now relies on Java 21 (`List#getFirst`/`getLast`/`removeLast`, `Math.clamp(int, int, int)` and
record deconstruction patterns), which is fine because Minecraft 1.21 itself requires Java 21. The regression
harnesses, however, compiled with `javac --release 17`, so four of five of them failed. `tests/bookmark-groups`,
`tests/bookmark-crafting`, `tests/bookmark-transfer` and `scripts/test_crafting_plans.py` now use `--release 21`.

### API compatibility (conservative choice)

Records make the former `public final` fields inaccessible, which breaks addons that read them. Two public API
types were therefore kept as plain classes with their original public fields, while also exposing accessor style
readers so the newer call sites keep working:

* `EmiTexture` — `public final texture, u, v, width, height, regionWidth, regionHeight, textureWidth, textureHeight`
  plus `texture()`, `u()`, `v()`, `width()`, `height()`, `regionWidth()`, `regionHeight()`, `textureWidth()`,
  `textureHeight()`.
* `EmiResolutionRecipe` — `public final ingredient, stack` plus `ingredient()`, `stack()`; the record patterns in
  `BoM` and `CraftingPlans` became classic `instanceof` checks.

`EmiCraftContext` stays a record because its former getters can be offered as deprecated delegates; it now exposes
`@Deprecated getScreen()/getInventory()/getType()/getDestination()/getAmount()` next to the record accessors
`screen()/inventory()/type()/destination()/amount()`.

Internal-only records (`CraftingTreeModel.Step`, `SearchStack`, `JemiScrollBoxWidget`) were left as records; the
regression fixtures were updated to accessor calls (`step.recipe()`, `step.batches()`) and their
`EmiResolutionRecipe` stubs were aligned with the class form.

Verification after the compatibility pass: `:fabric:build` and `:neoforge:build` succeed, and all five harnesses
pass (crafting tree 79, crafting plans 290 + 7, bookmark groups 141, bookmark crafting 59, bookmark material
transfer full scenario).

## Formatting

The tree is formatted with the IDE style: **four spaces per indent level**, no tabs, no trailing
whitespace, one trailing newline. `scripts/format_sources.py` enforces the whitespace part of that
without touching code meaning (it converts leading whitespace to the equivalent four-space prefix,
strips trailing whitespace, and leaves text blocks alone because their indentation is significant).
Pass `--tabs` to normalise to tabs instead.

```
python scripts/format_sources.py xplat/src fabric/src neoforge/src tests
python scripts/format_sources.py --tabs xplat/src fabric/src neoforge/src tests   # tab indentation
```

Reformatting was verified to be cosmetic only: stripping comments and whitespace from every changed
file before and after gives identical token streams, the extracted string and text block literals are
unchanged, both platform builds succeed and all five regression harnesses pass.
