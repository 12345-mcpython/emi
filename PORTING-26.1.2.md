# Porting EMI from 1.21.1 to 26.1.2

Status: **1.21.5 port delivered** (builds, dedicated server dist safety, recipe sync, recipe screenshot export).
Target 26.1.2 not reached yet.

## Branches

| Branch        | Contents                                                                                       |
|---------------|------------------------------------------------------------------------------------------------|
| `1.21`        | last fully featured release line (Minecraft 1.21.1)                                            |
| `1.21.4`      | finished 1.21.4 line (JEI 20 + full recipe sync)                                               |
| `1.21.5`      | current line: 1.21.5 port, now including the offscreen recipe screenshot capture              |

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

## JEI on 1.21.4

The Fabric platform is back; only NeoForge gets JEI. `jei-1.21.4-fabric` does not exist, so
`jei_version` points at `mezz.jei:jei-1.21.4-neoforge:20.0.0.4` and the common sources compile jemi against
that jar, so both platform jars carry the bridge classes. On Fabric nothing touches them: the JEI plugin
annotation is never scanned and `jei.PluginCallerMixin` is `@Pseudo`, which Mixin skips when the target
class is absent. Confirmed by launching the Fabric dev client, which starts normally with only the expected
`ClassNotFoundException` warning for `mezz/jei/library/load/PluginCaller`.

jemi was ported from the JEI 19 API to the JEI 20 API: `IIngredientAcceptor`, `IRecipeType` /
`IRecipeHolderType`, `RecipeIngredientRole.CRAFTING_STATION`, `getSubtypeData`, `getUid`, no
`getTypedIngredientByUid`, no `addSlotToWidget`, no `RecipeManager#getAllRecipesFor`, and the NeoForge
fluid helper instead of the Fabric-only ingredient type.

Dev runs needed one more fix: `GlobalMixin` is generated by `generateClassLists`, and the NeoForge dev mod
only contains the `xplat` source set, so the task now also mirrors the generated class into the `xplat`
classes directory. With that, `:neoforge:runClient` boots EMI together with `Just Enough Items 20.0.0.4`
and JEI reaches `JEI_STARTED` without mixin or plugin errors.

## Vanilla recipes on a server

Since 1.21.2 the client no longer keeps a recipe table; `ClientPacketListener#getRecipeManager` is gone and
the client only receives the synchronized recipe displays (`RecipeManager#getSynchronizedRecipes`) that
populate `ClientRecipeBook` as `RecipeDisplayEntry` records. The checkpoint therefore read vanilla recipes
from the integrated server (`ProxyRecipeManager`), which works in singleplayer and left the index empty on
servers.

`runtime/DisplayRecipes` closes that hole: when no integrated server is present it walks
`ClientRecipeBook#getCollections()`, resolves every `SlotDisplay` through
`SlotDisplayContext.fromLevel(level)` and turns the displays into EMI recipes:

* `ShapedCraftingRecipeDisplay` / `ShapelessCraftingRecipeDisplay` become `EmiCraftingRecipe`;
* `FurnaceRecipeDisplay`, `StonecutterRecipeDisplay` and `SmithingRecipeDisplay` become a compact
  `BasicEmiRecipe` row, with the category picked from the crafting station item
  (furnace / blast_furnace / smoker / campfire / stonecutter / smithing_table);
* ids are synthetic (`emi:/display/<index>`), so they cannot collide with data pack ids.

`VanillaPlugin` calls it only when `ProxyRecipeManager.getRaw() == null`, so singleplayer keeps using the
server recipes and nothing is registered twice. The reload logs
`[EMI] Registered N recipes from client displays`.

## Regression register (1.21.4 line)

Outstanding regressions left by the 1.21.1 to 1.21.4 migration. MC 1.21.4 sources are available locally in
`.gradle/loom-cache/minecraftMaven/net/minecraft/*/...-sources.jar` (mojmap names), which is what each
entry points at.

| # | Where | Symptom | Reference in MC 1.21.4 source |
| --- | --- | --- | --- |
| 1 | `screen/EmiScreenshotRecorder.java:58` | Recipe export renders without the 1.21.4 projection setup | `client/renderer/GameRenderer`, `com.mojang.blaze3d.vertex.ProjectionMatrixBuffer`, `client/gui/render/RenderTarget` |
| 2 | `screen/StackBatcher.java:13` | Batched stack rendering disabled, everything draws unbatched (performance) | `client/renderer/RenderType`, `com.mojang.blaze3d.vertex.VertexConsumer` / `MeshData` (the old `VertexBuffer` / `BufferUploader` path is gone) |
| 3 | `VanillaPlugin.java:479` | `isDamageable` replaced by a `maxDamage > 0` approximation | `world/item/ItemStack#isDamageableItem` |
| 4 | `bom/TreeCost.java:105` | Chanced remainders are not part of the cost calculation | pre-existing upstream TODO (`add partial chanced remainders`), not a 1.21.4 change |

Reclassified while working through the list: entries 4, 5 and 6 were originally filed as upgrade
regressions but are not caused by the 1.21.1 -> 1.21.4 move.

* `TreeCost` chanced remainders: the `TODO add partial chanced remainders` comment predates the port.
* `EmiBookCloningRecipe`: it is a decorative sample recipe generator (random titles, authors, generations),
  the vanilla `BookCloningRecipe` still is a `CustomRecipe`, so nothing about it changed with the version;
  the `TODO more book stuff` is an upstream fidelity note.
* `EmiRecipe#getBackingRecipe`: since 1.21.2 the client genuinely has no `Recipe` objects, only displays, so a
  `RecipeHolder` cannot be returned on a server. Browsing is covered by `DisplayRecipes` and filling goes
  through `FillRecipeC2SPacket`, so this is an inherent limitation of the new recipe model rather than a
  missing port step.

That left **#2 (stack batching)** as the only candidate, and inspecting the 1.21.4 render path closed it too:
`ItemEmiStack#render` draws through `GuiGraphics#renderFakeItem`, which already feeds the vanilla
`MultiBufferSource.BufferSource` that `GuiGraphics` owns and flushes once per frame. The custom batcher is
disabled, but rendering is still batched by vanilla; what is lost is only the per-stack `ItemStackRenderState`
cache (model state is re-resolved each frame). That is a performance TODO, not a migration regression, and
restoring it means keeping a `ItemStackRenderState` cache and calling
`state.render(pose, bufferSource, light, overlay)` from `renderForBatch`.

Net result: no open migration regressions remain on the 1.21.4 line.

Fixed on this line already: client display pipeline (`DisplayRecipes`), display context for ingredients and
results, real shaped pattern layout, remainder index guard, custom crafting inputs from the display,
`canFit` grid check, server packet guard, tag model particle textures.

Dead code, not a regression: `EmiSuspiciousStewRecipe` (the vanilla recipe no longer exists), and
`EmiPort#getQuads(BakedModel)` (baked models still exist in 1.21.4, they go away in 1.21.5).

## 1.21.5 hop (branch `1.21.5`)

The `1.21.5` branch starts from the finished `1.21.4-jei` line (so every 1.21.4 fix carries over) and bumps
`minecraft_version=1.21.5`, `fabric_api_version=0.128.2+1.21.5`, `neoforge_version=21.5.101` and
architectury loom to `1.10.455`. The old `1.21.5-wip` branch is superseded: it was based on an early 1.21.4
port without the display pipeline, the local data pack fallback or the JEI 20 bridge.

`gradlew :xplat:compileJava` currently reports 36 errors in these clusters:

| Cluster | Removed in 1.21.5 | Touch points |
| --- | --- | --- |
| Baked models | `net.minecraft.client.resources.model.BakedModel`, `ModelResourceLocation` | `EmiPort`, `ItemEmiStack`, `EmiAgnos`, `EmiTags#registerTagModels` |
| Vertex plumbing | `com.mojang.blaze3d.vertex.VertexBuffer`, `BufferUploader` | `EmiPort#setShader`, `screen/widget/config/ListWidget` |
| Render state | `GlStateManager` import in `ListWidget` | config list widget |
| Items | `net.minecraft.world.item.ArmorItem` | `VanillaPlugin` |
| Recipes | `SmithingTransformRecipe` / `SmithingTrimRecipe` field layout | `mixin/accessor/Smithing*Accessor` |

Order that keeps the tree buildable as long as possible: drop the armour and smithing paths first (small,
localised), then `ListWidget`/`GlStateManager`, then the render plumbing (`EmiPort#setShader`,
`#getQuads`), and finally the tag model pipeline, which is the only part that needs the new item model
set (`ItemModelResolver` / `ItemStackRenderState`) rather than a mechanical removal.

### 1.21.5 progress

The `1.21.5` branch now carries every 1.21.4 fix plus `minecraft_version=1.21.5`,
`fabric_api_version=0.128.2+1.21.5`, `neoforge_version=21.5.101`, loom `1.10.455` and
`jei_version=jei-1.21.5-neoforge:21.4.0.27` (JEI still has no Fabric build for 1.21.5, so jemi stays
NeoForge only). Compile errors went 36 -> 56 after the mechanical removals, the inventory adaptions, the
component/NBT updates and the JEI 21 overrides (`drawTooltip`, `Either` based `getLines`).

What is left is one cluster: the render pipeline rework (primer 1.21.5 "Render Pipeline Rework" and
"Model Rework").

| File | Errors | Needed |
| --- | --- | --- |
| `runtime/EmiScreenshotRecorder` | 20 | offscreen capture on the new pipeline (`RenderTarget`/`GpuTexture`) |
| `screen/widget/config/ListWidget` | 16 | `Scissor`/`GlStateManager` in their new packages |
| `EmiRenderHelper` | 14 | `GuiGraphics#blit` takes a `Function<ResourceLocation, RenderType>` and `GpuTexture` |
| `EmiPort` | 2 | `BufferUploader` removed, `EmiPort#draw` needs the new submit path |

Removed in 1.21.5 and already handled: `ArmorItem`, `Smithing*Recipe` optional ingredient fields (now read
from `SmithingRecipeDisplay`), `BakedModel`/`ModelResourceLocation` (tag model rebake stubbed, tag icons fall
back to the first ingredient), `VertexBuffer`/`BufferUploader` helpers in `EmiPort`, `Inventory#items`,
`RenderSystem#enableDepthTest/enableBlend`, `TagParser#parseTag`, `Tag#getAsString`, `DyedItemColor`'s second
component, `ItemModelResolver#updateForTopItem`'s handedness argument.

### 1.21.5 compiles

`:fabric:build` and `:neoforge:build` now succeed on the `1.21.5` branch, so every module compiles and the
jars are produced. One path is deliberately degraded until it gets its own rewrite:

* tag representation models: `EmiTags#registerTagModels` is a stub, so tag stacks render as their first
  ingredient. 1.21.5 removed `ModelResourceLocation` and Fabric replaced `addModels(ResourceLocation...)`
  with the typed `ExtraModelKey` API, so rebaking the tag icons needs the new item model pipeline. An earlier
  2x2 procedural grid was reverted; the first-ingredient fallback is the pre-1.21.5 behaviour.

Everything else from 1.21.4 is intact, including the display pipeline, the local data pack fallback, the
server sync receivers and the JEI 21 bridge (NeoForge only, `jei-1.21.5-neoforge:21.4.0.27`).

### 1.21.5 status after the sync and icon work

Recipe sources on 1.21.5 now match 1.21.4: the integrated server, NeoForge's own `recipe_content` event,
the `emi:recipe_sync` channel EMI servers push on join (chunked, 256 recipes per packet, applied once the
last chunk arrives), `fabric:recipe_sync` for the Paper bridge, and the client data pack fallback. Duplicate
deliveries of the same table are ignored so the index reloads once.

Tag icons fall back to their first ingredient (see above); the earlier 2x2 grid was reverted.

Recipe screenshot export works again on 1.21.5. `RenderType` resolves its output target per draw through
`Minecraft#getMainRenderTarget`, so the exporter renders into an offscreen `TextureTarget` and
`MinecraftClientMixin` answers that lookup with the capture target while the renderer runs - 1.21.5 has no
global `RenderTarget#bindWrite`. The projection and model-view setup mirrors `GameRenderer`, pending GUI
batches are flushed before the copy, and `CommandEncoder#copyTextureToBuffer` reads the pixels back so the
transparent background survives (`Screenshot#takeScreenshot` would force the alpha channel opaque). A
2026-11-09 client smoke (`tests/screenshot-smoke`, 192x96 capture from a 1280x800 window at GUI scale 3)
confirmed the output size, the transparent corner, an opaque rectangle, a rendered stone item and a rendered
text run. One limitation remains: `GuiGraphics#enableScissor` still computes the scissor box from the window
height and GUI scale, so a third-party recipe renderer that clips would be off inside the offscreen target.
EMI's own recipe and tree exports do not use scissor.

`BoMScreen` draws its background at the start of `render()` now. Since 1.20.5 the menu blur lives in
`Screen#renderBackground`, which `super.render` executes at the end of the old method, so the tree and the
stats panel were blurred and dimmed by the in-world menu background after they had already been drawn. The
screenshot smoke opens a real `BoMScreen` and asserts that the "plans" header keeps its `0xFFB6C7E8` colour;
before the fix the same probe found no matching pixels and a brightest pixel of `0x3A404B`.

