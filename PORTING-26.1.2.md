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

* tag representation models: `EmiTags#registerTagModels` is a stub on 1.21.5, but the visible behaviour is
  unchanged from the 1.21.4 line: `TagEmiIngredient#render` already fell back to the first member there,
  because the 1.21.4 port dropped the baked tag model path (`EmiAgnos#getBakedTagModel`, `EmiPort#getQuads`
  are dead code on that line). 1.21.1 still rendered the modelled 2x2-style tag icons. Restoring them needs
  the new item model pipeline (`ItemModelResolver` / `ExtraModelKey`); an earlier 2x2 procedural grid
  (`ca5b19a`) was reverted (`9d97c0a`).

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

## 1.21.8 hop (planned, from `1.21.5`)

`1.21.5 -> 1.21.8` skips two releases. `1.21.6` carries the GUI rework and the Blaze3d buffer/uniform
rework, `1.21.7` is small, and `1.21.8` only adds `GraphicsWorkarounds`. Primers:
`E:\code\java\move\port\primers\1.21.6`, `1.21.7`, `1.21.8`.

### Version matrix

| Component | 1.21.5 (current) | 1.21.8 (next) |
|---|---|---|
| Minecraft | 1.21.5 | 1.21.8 |
| NeoForge | 21.5.101 | 21.8.57 |
| Fabric API | 0.128.2+1.21.5 | `0.136.1+1.21.8` (Modrinth, 2025-11-16) |
| JEI | `jei-1.21.5-neoforge:21.4.0.27` | `jei-1.21.8-neoforge:24.2.0.6` (NeoForge only, Modrinth) |
| Loom | 1.10.455 | needs a newer Loom (1.11+); `maven.fabricmc.net` was unreachable from this machine, resolve before branching |
| Java | 21 | 21 |

### Breaking changes that touch EMI

| # | Change (1.21.6 unless noted) | EMI touch points | Plan |
|---|---|---|---|
| 1 | GUI prepare/render split: `GuiGraphics` submits to `GuiRenderState` (takes it instead of `MultiBufferSource`), `flush` is removed, `pose()` returns `Matrix3x2fStack`, draw methods take `RenderPipeline` + `TextureSetup`, `drawSpecial` is removed | `runtime/EmiDrawContext`, `EmiRenderHelper`, `screen/*`, `screen/widget/config/ListWidget`, `api/widget/*` | rework the two wrappers first, then compile screen by screen; visual pass for layering |
| 2 | `drawString` now takes ARGB and alpha 0 renders nothing | `EmiDrawContext.drawText*`, literal colours such as `0xFFFFFF` / `0x22ffff` | normalise inside `EmiDrawContext` (`color \| 0xFF000000` when alpha is 0) and audit literals |
| 3 | `render*Tooltip` -> `set*TooltipForNextFrame` / `renderDeferredTooltip`; `AbstractWidget#getTooltip` removed; `ClientTooltipComponent#renderText` takes `GuiGraphics`; `TooltipRenderUtil` drops the Z offset | `EmiRenderHelper.drawTooltip`, `EmiScreenManager` (dev overlay), `RecipeScreen`, `ResolutionButtonWidget`, `EmiTooltipComponent`, `mixin/accessor/DrawContextAccessor` | migrate to the deferred tooltip API, keep EMI's tooltip builder |
| 4 | Screenshot capture: the prepare phase only submits states, `GuiRenderer` renders later, so the current "override `getMainRenderTarget` + draw through `GuiGraphics`" no longer fills an offscreen target | `runtime/EmiScreenshotRecorder`, `tests/screenshot-smoke`, `BoMScreen#screenshot`, `RecipeScreenshotButtonWidget` | redesign; inspect `GuiRenderer` / `PictureInPictureRenderer` on 1.21.8 first, prefer Picture-in-Picture or a `GuiRenderer` target entry, fallbacks: main-target render + crop, or direct `RenderPipeline` submission |
| 5 | Blaze3d rework: `GpuBuffer` / `BufferType` / `BufferUsage` replaced by usage ints plus `GpuBufferSlice` / `MappedView`; `createRenderPass(Supplier<String>, GpuTextureView...)`; `RenderPass#enableScissor` removed; `RenderSystem.SCISSOR_STATE` private; `setProjectionMatrix` takes a `GpuBufferSlice`; `Lighting` is `AutoCloseable` with `setupFor` | same recorder (readback, projection, lighting, scissor) | rewrite those parts; read back through `createBuffer(..., usage)` and `MappedView` |
| 6 | `Screenshot#grab` / `takeScreenshot` take a downscale factor | recorder if the vanilla readback is used; tests | pass `1` |
| 7 | `Window#getGuiScale` / `setGuiScale` use `int` | `EmiPort#getGuiScale`, screenshot scale maths | trivial |
| 8 | `Minecraft#disconnect(Screen)` removed (`disconnectWithProgressScreen` / `disconnectWithSavingScreen`); `ToastManager` takes `Options`; `Screen#renderBlurredBackground` takes `GuiGraphics`; `GuiGraphics#blurBeforeThisStratum` | `MinecraftClientMixin`, `ToastManagerMixin`, `BoMScreen` background fix | update mixin targets and re-verify the BoM background order |
| 9 | (1.21.7) `GuiItemRenderState` uses `TrackingItemRenderState`, `ItemStackRenderState#getModelIdentity` moved, `RenderPipeline$Builder#withColorLogic` deprecated | `api/stack/ItemEmiStack`, `screen/StackBatcher` (no-op shim) | verify `ItemStackRenderState` usage; EMI does not read the model identity today |
| 10 | NBT access through `ValueInput` / `ValueOutput` instead of `CompoundTag` | only `recipe/special/EmiBookCloningRecipe` imports `CompoundTag` | leave or migrate the sample recipe |
| 11 | New vanilla item tags in 1.21.6 (`harnesses`, `happy_ghast_food`, `happy_ghast_tempt_items`) plus later additions | dev-mode untranslated tag warnings, `en_us.json` / `zh_cn.json` | rerun `python scripts/scan_untranslated_tags.py --client-jar <1.21.8 client jar>` and add names or exclusions |
| 12 | Tag provider rewrite (`TagAppender`, `ItemTagsProvider` removed) | EMI does not generate tags | none |
| 13 | `GameRenderer` no longer takes `ResourceManager`; `Minecraft#getMobEffectTextures` removed | EMI does not use them | none |

### First compile inventory (2026-11-09)

The local `1.21.8` branch has the dependency bump (`minecraft_version=1.21.8`, Fabric API
`0.136.1+1.21.8`, NeoForge `21.8.57`, JEI `jei-1.21.8-neoforge:24.2.0.6`, Architectury Loom `1.13.469`).
Dependencies resolve and the Minecraft 1.21.8 mappings download; the first `:xplat:compileJava` run fails
with **30 unique errors in 13 files** (96 javac lines including repeats):

| File | Unique errors | Cause |
|---|---|---|
| `runtime/EmiScreenshotRecorder` | 9 | `GpuBuffer` / `BufferType` / `BufferUsage` / `MappedView` rework, `setProjectionMatrix(GpuBufferSlice)`, `Lighting` changes, and the GUI is now deferred |
| `screen/widget/config/ListWidget` | 5 | `GuiGraphics#pose()` returns `Matrix3x2fStack`; scissor helpers changed |
| `runtime/EmiDrawContext` | 3 | `Matrix3x2fStack` vs `PoseStack`; `blit(RenderType::..., ...)` no longer exists |
| `screen/BoMScreen` | 3 | `renderBlurredBackground(GuiGraphics)`, `new GuiGraphics(client, ...)` needs a `GuiRenderState`, `raw.flush()` gone |
| `api/widget/TankWidget` | 2 | `pose()` type |
| `api/stack/ItemEmiStack` | 1 | item model / render state API |
| `EmiRenderHelper` | 1 | blit / texture setup |
| `screen/EmiScreenManager` | 1 | `renderComponentTooltip` -> `setComponentTooltipForNextFrame` |
| `screen/RecipeScreen` | 1 | same tooltip rename |
| `screen/tooltip/EmiTooltipComponent` | 1 | `ClientTooltipComponent#renderText` signature |
| `screen/widget/ResolutionButtonWidget` | 1 | same tooltip rename |
| `screen/DisabledToast` | 1 | toast rendering API |
| `widget/RecipeScreenshotButtonWidget` | 1 | `new GuiGraphics(client, bufferSource)` -> `GuiRenderState` |

Note: the first mapping download failed once with `Failed to setup mappings: loom:mappings:layered+hash.40545`
and succeeded on the retry; that is transient, not a version mismatch.

### 1.21.8 progress (2026-11-09)

`:fabric:build` and `:neoforge:build` succeed on the `1.21.8` branch. The GUI layer was migrated to the
prepare/render split: `EmiDrawContext` exposes a `Matrix3x2fStack`, `blit` uses `RenderPipelines.GUI_TEXTURED`,
text colours are normalised to opaque, tooltips go through `set*TooltipForNextFrame` / `renderTooltip`, and
fluid sprites are submitted as a tinted `BlitRenderState` (through the new `GuiGraphicsAccessor`). The
screenshot exporter was redesigned: it builds its own `GuiRenderState` + `GuiRenderer`, installs the capture
`TextureTarget` as the main render target, and captures the image tile by tile because the new renderer always
projects against the window. `MinecraftClientMixin` now targets `reloadResourcePacks()` and
`disconnect(Screen, boolean)`, `HandledScreenMixin` injects into `renderContents`, `DrawContextMixin` targets
`setTooltipForNextFrame`, and `DrawContextAccessor` was replaced by the public `GuiGraphics#renderTooltip`.
NeoForge 21.8 removed `EventBusSubscriber.Bus` and moved client packet sending to `ClientPacketDistributor`.

The Fabric screenshot smoke passes on 1.21.8: a 192x96 capture (transparent corner, red rectangle, stone item
and text), a 700x520 capture that is larger than the window (2100x1560, captured as 2x2 tiles with the corner
markers and the line crossing the seams intact) and the real BoM header probe (`pixels=540`, `0xFFB6C7E8`).
The 1.21.6 item tags were scanned and named in `en_us.json` + `zh_cn.json`
(`harnesses`, `happy_ghast_food`, `happy_ghast_tempt_items`), so the dev-mode warning list is empty again.
`:neoforge:runServer` reaches `Done (3.185s)!` on 1.21.8. The NeoForge client also runs: it joins a
singleplayer world, EMI receives 1407 recipes, JEI 24.2.0.6 starts, the jemi bridge initializes and the EMI
reload completes with no mixin errors (screenshot `neoforge/run/screenshots/2026-11-09_21.19.08.png`).
Real-client pass (2026-11-10): recipe screens, config screens, tooltips, fluid tanks and the BoM tree export
all checked out on a NeoForge 1.21.8 client; only cosmetic differences from 1.21.1 remain (see the known gaps
below: Fabric jemi, multi-item tag icons, and the vanilla item-atlas prepare cost).

#### Item rendering batching on 1.21.6+

`StackBatcher` stays a no-op shim: 1.21.4 replaced the per-`RenderType` vertex buffers with pipelines, so the old
batcher cannot be restored. Starting with 1.21.6 the vanilla `GuiRenderer` batches items itself: every
`GuiGraphics#renderFakeItem` submits a `GuiItemRenderState`, the prepare pass bakes it into the gui item atlas
(`GuiRenderer#prepareItemElements`) and the draw pass emits one textured quad per slot. Drawing stacks per slot is
therefore already batched; what remains is the per-frame atlas prepare, not the draw calls. The obsolete
`ui.use-batched-renderer` option was removed in `860619d` (it had no effect since 1.21.4), together with the
NeoForge `StackBatcher.EXTRA_RENDER_LAYERS` registration. Further optimisation means submitting fewer states
(visibility culling) and caching `ItemStackRenderState`, not reviving the custom batcher. `StackBatcher` and the
public `Batchable` interface stay as a compatibility shim for third-party ingredient implementations.

### Port order

1. Branch `1.21.8` from `1.21.5`; bump `minecraft_version`, `neoforge_version`, `jei_version`, the Fabric API version and Loom; resolve the new dependencies online.
2. `:xplat:compileJava`, then cluster the errors: GUI, screenshot, Blaze3d, mixin targets, misc.
3. Rework `EmiDrawContext` / `EmiRenderHelper` first (they funnel most GUI calls), then the screens and widgets.
4. Normalise text colours and migrate tooltips.
5. Redesign the capture path and update `tests/screenshot-smoke`; verify size, alpha, item, text and a real `plan.png` export.
6. Update mixin targets and re-verify the BoM background order with `renderBlurredBackground(GuiGraphics)` / `blurBeforeThisStratum`.
7. Rerun the tag scan and add the new names.
8. `:fabric:build`, `:neoforge:build`, `:neoforge:runServer`, the screenshot smoke and a real-client pass over the recipe screen, the BoM screen, the config screens, tooltips and the sidebar.
9. Update this document and the handoff, then rebuild dist.

### Risks

- The new GUI sort (strata, node tree, scissor -> pipeline -> texture comparator) changes draw order. EMI's tooltips, ghost stacks, sidebar and drag preview were tuned for submit order; expect overlap or depth issues and budget a visual pass.
- Screenshot export is the largest unknown. If Picture-in-Picture cannot render arbitrary `GuiGraphics` content, fall back to rendering into the main target and cropping (the old route B) and accept one frame of flicker.
- JEI 24 (`24.2.0.6`) API details are unknown offline; jemi may need `IIngredientAcceptor` / `IDrawable` updates. Fabric still has no JEI for 1.21.6+.
- The Fabric API and Loom versions for 1.21.8 must be resolved online before the branch can build.

## 1.21.10（2026-11-10，已完成）

从 `1.21.8` 拉 `1.21.10` 分支，主要处理 1.21.9 的大改：

- 输入事件对象化（`MouseButtonEvent`/`KeyEvent`/`CharacterEvent`、`MouseHandler#onButton`）
- `Level#isClientSide()`、`PreparableReloadListener` 的 `SharedState`
- 渲染提交化：`GuiRenderer` 需要 `SubmitNodeCollector` + `FeatureRenderDispatcher`
- 图集：`AtlasManager#getAtlasOrThrow` + `AtlasIds.BLOCKS`（1.21.10 不接受 `TextureAtlas.LOCATION_BLOCKS`）
- 截图 capture 与游戏共用 `renderBuffers().bufferSource()`，否则 item atlas 不 flush
- Fabric JEI/jemi 恢复启用（JEI 26 的 `fabric:recipe_sync` 冲突用 try-register 规避）

验证：双平台编译通过，Fabric/NeoForge 实机通过；screenshot smoke 全过。已知：JEI 26.3.0.31 与 NeoForge 21.10.64 不兼容（JEI 侧），多物品标签图标仍 stub。
