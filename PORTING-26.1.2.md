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
