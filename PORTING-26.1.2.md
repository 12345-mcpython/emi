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

## 1.21.4 is NeoForge only

Fabric was dropped from the 1.21.4 line. The reason is JEI: for 1.21.4 JEI only ships a NeoForge
build (`mezz.jei:jei-1.21.4-neoforge`, 20.0.0.x), while `jei-1.21.4-fabric` does not exist, so the
Fabric platform could never reach feature parity with the JEI integration enabled.

What changed:

* `enabled_platforms=neoforge`, the `fabric` module is gone and so is `xplat/.../fabric.mod.json`;
* `xplat/build.gradle` no longer expands or includes Fabric metadata and takes the version from
  `:neoforge`;
* `net.fabricmc:fabric-loader` stays as a compile dependency because one common class uses
  `@Environment(EnvType.CLIENT)`.

Verified: `:neoforge:build` succeeds, the jar has 608 entries with `META-INF/neoforge.mods.toml`
and no Fabric classes or metadata, and the platform independent harnesses still pass (crafting tree
79, crafting plans 290 + 7, bookmark groups 141, bookmark crafting 59, bookmark material transfer).

`tests/crafting-tree-smoke` and its `world` variant still target `:fabric:runClient` and therefore do
not run on this line until they are ported to a NeoForge fixture mod (they need a
`META-INF/neoforge.mods.toml` plus a `@Mod` entry point instead of the Fabric entrypoint).

## JEI on 1.21.4

JEI only publishes a NeoForge build for 1.21.4 (`mezz.jei:jei-1.21.4-neoforge:20.0.0.4`), which is why the
platform is NeoForge only and why jemi had to be ported from the JEI 19 API to the JEI 20 API:

* `IIngredientConsumer` became `IIngredientAcceptor<THIS>` with `add(SlotDisplay)` / `add(Ingredient)` /
  `add(Fluid…)` as the required methods;
* recipe descriptors moved to `IRecipeType` / `IRecipeHolderType`, so `RecipeTypes.*` constants and the
  category, transfer handler and recipes gui all use those types now;
* `RecipeIngredientRole.CATALYST` was renamed to `CRAFTING_STATION`;
* `ISubtypeManager#getSubtypeInfo` became `getSubtypeData`, `IIngredientHelper#getUniqueId` became `getUid`;
* `IIngredientManager#getTypedIngredientByUid` is gone, the stack serializer searches the registered
  ingredients and matches the helper uid instead;
* `IRecipeLayoutBuilder#addSlotToWidget` is gone;
* `RecipeManager#getAllRecipesFor` no longer exists on 1.21.4, the plugin filters `getRecipes()` by type;
* the Fabric-only `IJeiFluidIngredient` is gone; NeoForge fluids go through the platform fluid helper.

### Dev runs

`GlobalMixin` is not source code: `generateClassLists` in the root build generates it from the compiled
class list. The NeoForge dev mod is assembled from the `xplat` source set only, so the generated class was
missing on the dev classpath and the client died in mixin preparation. The task now also mirrors the class
into the `xplat` classes directory, which restores `:neoforge:runClient`.

Verified so far: the dev client boots with `EMI 1.1.24-SNAPSHOT+1.21.4+neoforge` and
`Just Enough Items 20.0.0.4` loaded, the resource manager includes `mod/emi` and `mod/jei`, JEI starts
without mixin or plugin errors, and the packaged jar contains 40 jemi classes. The JEI to EMI data bridge
only runs once a recipe manager exists, so it still needs an in-world check.

