package dev.emi.emi.test;

import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.awt.event.InputEvent;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Predicate;

import com.google.gson.JsonObject;

import dev.emi.emi.config.SidebarSide;
import dev.emi.emi.config.SidebarType;
import dev.emi.emi.input.EmiInput;
import dev.emi.emi.runtime.EmiBookmarkGroups;
import dev.emi.emi.runtime.EmiBookmarkGroups.Group;
import dev.emi.emi.runtime.EmiBookmarkGroups.Row;
import dev.emi.emi.runtime.EmiFavorite;
import dev.emi.emi.runtime.EmiFavorites;
import dev.emi.emi.runtime.EmiPersistentData;
import dev.emi.emi.runtime.EmiReloadManager;
import dev.emi.emi.screen.BookmarkSidebar.RowFavorite;
import dev.emi.emi.screen.EmiScreenManager;
import dev.emi.emi.screen.EmiScreenManager.SidebarPanel;
import dev.emi.emi.screen.RecipeScreen;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.EmiPort;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;
import dev.emi.emi.bom.BoM;
import dev.emi.emi.bom.CraftingTreeModel;
import dev.emi.emi.config.EmiConfig;
import dev.emi.emi.screen.BoMScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Screenshot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Optional real-client smoke test. Never registered without the external init script. */
public final class CraftingTreeSmoke implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("emi-crafting-tree-smoke");
    private static final String GATE = "emi.craftingTreeSmoke";
    private static final boolean MODEL_MODIFIERS = Boolean.getBoolean("emi.craftingTreeSmoke.modelModifiers");
    private BoMScreen screen;
    private EmiRecipeCategory category;
    private boolean truncatedTooltipChecked;
    private Path output;
    private long start;
    private int waitTicks;
    private int age;
    private boolean ended;
    private boolean exported;
    private RecipeScreen bookmarkScreen;
    private SidebarPanel bookmarkPanel;
    private Group bookmarkGroup;
    private List<EmiRecipe> bookmarkRecipes;
    private boolean bookmarksChecked;
    private Field reloadStatus;
    private int savedReloadStatus;
    private boolean savedEnabled;
    private List<EmiFavorite> savedFavorites;
    private List<EmiFavorite.Synthetic> savedSyntheticFavorites;
    private JsonObject savedGroups;
    private Map<EmiIngredient, EmiRecipe> savedDefaults;
    private Map<EmiIngredient, EmiRecipe> savedAddedRecipes;
    private java.util.Set<EmiRecipe> savedDisabledRecipes;
    private Map<Class<?>, dev.emi.emi.api.stack.serializer.EmiIngredientSerializer<?>> savedSerializersByClass;
    private Map<String, dev.emi.emi.api.stack.serializer.EmiIngredientSerializer<?>> savedSerializersByType;
    private Robot robot;
    private int heldModifier;
    private int pendingModifier;
    private long modifierDeadline;
    private long modifierFocusFrame;
    private boolean modifierPressed;
    private org.lwjgl.glfw.GLFWKeyCallback previousKeyCallback, diagnosticKeyCallback;
    private final List<String> modifierEvidence = new ArrayList<>();
    private int[] resultPoint, inputPoint;
    private dev.emi.emi.api.recipe.EmiRecipeManager savedRecipeManager;
    private long bookmarkRenderedFrames;
    private PendingBookmarkCapture pendingBookmarkCapture;
    private record PendingBookmarkCapture(String stage, String groupId, boolean green, long afterFrame, Runnable afterCapture) {}

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean(GATE)) {
            LOGGER.info("Smoke entrypoint disabled: pass -D{}=true to opt in", GATE);
            return;
        }
        // Minecraft's launcher marks AWT headless. This opt-in graphical fixture uses
        // Robot only for real modifier key state; set before the first AWT use.
        System.setProperty("java.awt.headless", "false");
        LOGGER.info("Smoke entrypoint armed; waiting for a fully loaded title screen");
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {
        if (ended) return;
        try {
            if (++waitTicks > 1200) throw new IllegalStateException("Smoke timed out waiting for title screen or export");
            if (screen == null) {
                if (!(client.screen instanceof TitleScreen) || client.getOverlay() != null) return;
                open(client);
                return;
            }
            check(client.screen == (bookmarkScreen == null ? screen : bookmarkScreen), "Test screen was replaced unexpectedly");
            check(client.player == null && client.level == null, "Smoke must run without a world/player");
            // Ticks can catch up without a render. Freeze the scenario until a real
            // RecipeScreen frame has completed, then read that normal framebuffer.
            if (pendingBookmarkCapture != null) {
                if (bookmarkRenderedFrames <= pendingBookmarkCapture.afterFrame()) return;
                PendingBookmarkCapture pending = pendingBookmarkCapture;
                captureNormalBookmarkFrame(client, pending);
                pendingBookmarkCapture = null;
                pending.afterCapture().run();
            }
            if (pendingModifier != 0 && !pollModifier(client)) return;
            switch (++age) {
                case 10 -> {
                    checkNameDraft(client);
                    checkFirstReverseSearch();
                    capture(client, "01-expanded");
                    search("minecraft:oak_planks");
                    LOGGER.info("Search dispatched through mouseClicked, charTyped and keyPressed");
                }
                case 20 -> {
                    capture(client, "02-search");
                    clearSearch();
                    click("fold_0");
                    click("fold_1");
                    check(button("fold_2") != null, "Fold toolbar did not reach collapsed state");
                }
                case 30 -> {
                    capture(client, "03-folded");
                    click("fold_2");
                    click("fit");
                    check(button("fold_0") != null, "Fold toolbar did not return to expanded state");
                    click("inventory_off");
                    check(BoM.craftingMode, "Inventory button failed to enable with null player");
                    check(button("inventory_on") != null, "Inventory label did not update");
                    click("refresh");
                }
                case 40 -> {
                    capture(client, "04-null-player-inventory");
                    click("inventory_on");
                    check(!BoM.craftingMode, "Inventory button failed to disable");
                    click("fit");
                    int x = (int) (screen.width * 0.55);
                    int y = (int) (screen.height * 0.7);
                    check(screen.mouseScrolled(x, y, 0, 1), "Zoom event not consumed");
                    check(screen.mouseClicked(x, y, 2), "Pan press not consumed");
                    check(screen.mouseDragged(x + 20, y + 10, 2, 20, 10), "Pan drag not consumed");
                    check(screen.mouseReleased(x + 20, y + 10, 2), "Pan release not consumed");
                    click("fit");
                }
                case 50 -> {
                    capture(client, "05-restored-fit");
                    click("next"); // Null old screen: safe no-op, never an automatic craft.
                    click("screenshot"); // Exercise the real full-layout export and framebuffer cleanup.
                    LOGGER.info("Real BoMScreen screenshot button invoked; waiting for asynchronous PNG");
                }
                case 60 -> {
                    capture(client, "06-after-export");
                    checkTruncatedTooltip(client);
                }
                case 70 -> openBookmarks(client);
                case 80 -> checkBookmarkTodo(client);
                case 90 -> checkBookmarkCompact(client);
                case 100 -> checkBookmarkCrafting(client);
                case 110 -> {
                    // Keep native key down across ticks so glfwPollEvents observes it.
                    holdModifier(KeyEvent.VK_CONTROL);
                }
                case 115 -> checkBookmarkQuantity();
                case 120 -> holdModifier(KeyEvent.VK_ALT);
                case 125 -> checkBookmarkCollapsed(client);
                case 130 -> checkBookmarkExpanded(client);
                case 140 -> checkBookmarkDragAndPersistence(client);
                default -> { }
            }
            if (age >= 140) {
                exported |= hasExport(client);
                if (exported && truncatedTooltipChecked && bookmarksChecked) {
                    restoreBookmarkState();
                    String marker = MODEL_MODIFIERS ? "PASS-MODEL-MODIFIERS.txt" : "PASS.txt";
                    String modifierEvidence = MODEL_MODIFIERS
                        ? "CtrlAltUI UNVERIFIED: quantity/collapse/expand use explicit model APIs; no Robot/GLFW modifier coverage."
                        : "Real GLFW Ctrl/Alt manager UI verified.";
                    Files.writeString(output.resolve(marker),
                        "PASS: title-screen real client render; shared subbranch; search; folding; fit; zoom/pan; null-player inventory; full-layout export; RecipeScreen native FAVORITES sidebar; todo/compact; shared amounts; manager ordinary click/drag/page-scroll; collapse/expand model; native Synthetic; group persistence; green/gray bracket framebuffer assertions.\n"
                            + modifierEvidence + "\n", StandardCharsets.UTF_8);
                    LOGGER.info("SMOKE {}: {}; captures at {}; real tree export at screenshots/emi/trees", marker, modifierEvidence, output.toAbsolutePath());
                    ended = true;
                    client.stop();
                } else if (age > 260) {
                    throw new IllegalStateException("No nonempty full-layout PNG appeared within 200 ticks of export");
                }
            }
        } catch (Throwable error) {
            ended = true;
            LOGGER.error("SMOKE FAIL", error);
            try {
                restoreBookmarkState();
            } catch (Throwable restoreError) {
                LOGGER.error("Could not restore test-only bookmark/reload state", restoreError);
            }
            try {
                if (output != null) Files.writeString(output.resolve("FAIL.txt"), error.toString(), StandardCharsets.UTF_8);
            } catch (Exception writeError) {
                LOGGER.error("Could not write failure marker", writeError);
            }
            client.stop();
        }
    }

    private void open(Minecraft client) throws Exception {
        check(client.player == null && client.level == null, "Expected a title screen without a player");
        start = System.currentTimeMillis();
        output = client.gameDirectory.toPath().resolve("screenshots/crafting-tree-smoke/" + start);
        Files.createDirectories(output);
        // Vanilla EMI categories are registered during recipe reload, which has not run at title.
        category = new EmiRecipeCategory(EmiPort.id("emi_tree_smoke", "crafting"), EmiStack.of(Items.CRAFTING_TABLE)) {
            @Override public Component getName() { return Component.literal("Smoke Crafting"); }
        };
        checkResolutionNavigation(client);
        // Real vanilla Items, intentionally synthetic recipe relationships for deterministic visuals.
        EmiRecipe goal = recipe("goal", Items.CRAFTING_TABLE, 1,
            EmiStack.of(Items.CHEST), EmiStack.of(Items.STICK, 4));
        EmiRecipe chest = recipe("chest", Items.CHEST, 1, EmiStack.of(Items.OAK_PLANKS, 8));
        EmiRecipe sticks = recipe("sticks", Items.STICK, 4, EmiStack.of(Items.OAK_PLANKS, 2));
        EmiRecipe planks = recipe("planks", Items.OAK_PLANKS, 4, EmiStack.of(Items.OAK_LOG));
        BoM.setGoal(goal);
        BoM.tree.resolutions.put(EmiStack.of(Items.CHEST), chest);
        BoM.tree.resolutions.put(EmiStack.of(Items.STICK), sticks);
        BoM.tree.resolutions.put(EmiStack.of(Items.OAK_PLANKS), planks);
        BoM.tree.resolutions.put(EmiStack.of(Items.OAK_LOG), null);
        BoM.tree.batches = 3;
        BoM.tree.recalculate();
        CraftingTreeModel model = new CraftingTreeModel(BoM.tree, null);
        check(model.entries.size() == 5, "Expected five graph entries, found " + model.entries.size());
        long shared = model.links.stream().filter(link -> link.child.ingredient.getEmiStacks().stream()
            .anyMatch(EmiStack.of(Items.OAK_PLANKS)::isEqual)).count();
        check(shared == 2, "Expected two links into shared planks, found " + shared);
        screen = new BoMScreen(null);
        client.setScreen(screen);
        LOGGER.info("Opened real BoMScreen: entries={}, links={}, batches={}, null player; wait 10 ticks", model.entries.size(), model.links.size(), BoM.tree.batches);
    }

    private void checkResolutionNavigation(Minecraft client) {
        var original = new dev.emi.emi.screen.RecipeScreen(null, java.util.Map.of());
        EmiIngredient target = EmiStack.of(Items.OAK_PLANKS);
        original.setResolutionTarget(target);
        client.setScreen(original);
        check(dev.emi.emi.screen.RecipeScreen.resolve == target, "Original resolution target lost");
        client.setScreen(new dev.emi.emi.screen.RecipeScreen(null, java.util.Map.of()));
        check(dev.emi.emi.screen.RecipeScreen.resolve == null, "Nested recipe screen inherited target");
        client.setScreen(original);
        check(dev.emi.emi.screen.RecipeScreen.resolve == target, "Returning failed to restore resolution target");
        LOGGER.info("RecipeScreen independent and restored resolution targets passed");
    }

    private void openBookmarks(Minecraft client) throws Exception {
        // Group mutations save emi.json. Fail before the first mutation unless both paths
        // are inside the opt-in Loom runDir, including process-relative persistence.
        Path run = client.gameDirectory.toPath().toRealPath();
        check(run.endsWith(Path.of("run", "crafting-tree-smoke")), "Smoke runDirectory is not isolated: " + run);
        check(EmiPersistentData.FILE.toPath().toAbsolutePath().normalize().getParent().toRealPath().equals(run),
            "Process-relative emi.json would escape isolated runDir");
        savedFavorites = new ArrayList<>(EmiFavorites.favorites);
        savedSyntheticFavorites = new ArrayList<>(EmiFavorites.syntheticFavorites);
        savedGroups = EmiBookmarkGroups.save().deepCopy();
        savedDefaults = new LinkedHashMap<>(BoM.defaultRecipes);
        savedAddedRecipes = new LinkedHashMap<>(BoM.addedRecipes);
        savedDisabledRecipes = new java.util.HashSet<>(BoM.disabledRecipes);
        savedSerializersByClass = new LinkedHashMap<>(dev.emi.emi.registry.EmiIngredientSerializers.BY_CLASS);
        savedSerializersByType = new LinkedHashMap<>(dev.emi.emi.registry.EmiIngredientSerializers.BY_TYPE);
        savedRecipeManager = dev.emi.emi.registry.EmiRecipes.manager;
        savedEnabled = EmiConfig.enabled;
        reloadStatus = EmiReloadManager.class.getDeclaredField("status");
        reloadStatus.setAccessible(true);
        savedReloadStatus = reloadStatus.getInt(null);
        Field reloadThread = EmiReloadManager.class.getDeclaredField("thread");
        reloadThread.setAccessible(true);
        Thread worker = (Thread) reloadThread.get(null);
        check(worker == null || !worker.isAlive(), "Cannot borrow loaded state while a real reload is running");
        reloadStatus.setInt(null, 2);
        EmiConfig.enabled = true;
        check(EmiReloadManager.isLoaded() && !EmiScreenManager.isDisabled(), "Title-screen manager gate is still disabled");
        // VanillaPlugin normally registers these during EMI init/reload; title has
        // not run that lifecycle. Use its real registration API and item serializer.
        new dev.emi.emi.registry.EmiInitRegistryImpl().addIngredientSerializer(
            dev.emi.emi.api.stack.ItemEmiStack.class, new dev.emi.emi.stack.serializer.ItemEmiStackSerializer());
        EmiFavorites.favorites.clear();
        EmiBookmarkGroups.load(null);
        BoM.addedRecipes.clear();
        BoM.disabledRecipes.clear();
        EmiRecipe root = recipe("bookmark_root", Items.CRAFTING_TABLE, 1,
            EmiStack.of(Items.CHEST), EmiStack.of(Items.STICK, 4));
        EmiRecipe chest = recipe("bookmark_chest", Items.CHEST, 1, EmiStack.of(Items.OAK_PLANKS, 8));
        EmiRecipe sticks = recipe("bookmark_sticks", Items.STICK, 4, EmiStack.of(Items.OAK_PLANKS, 2));
        EmiRecipe planks = recipe("bookmark_planks", Items.OAK_PLANKS, 4, EmiStack.of(Items.OAK_LOG));
        bookmarkRecipes = List.of(root, chest, planks, sticks);
        BoM.defaultRecipes.clear();
        for (EmiRecipe recipe : bookmarkRecipes) BoM.defaultRecipes.put(recipe.getOutputs().get(0), recipe);
        dev.emi.emi.registry.EmiRecipes.manager = new SmokeRecipeManager(category, bookmarkRecipes);
        for (EmiRecipe recipe : bookmarkRecipes) {
            EmiFavorite candidate = new EmiFavorite(recipe.getOutputs().get(0), recipe);
            check(!EmiBookmarkGroups.key(candidate).isEmpty(), "Real item serializer missing for " + recipe.getId());
            check(BoM.getRecipe(recipe.getOutputs().get(0)) == recipe, "BoM default producer overridden: " + recipe.getId());
            LOGGER.info("Bookmark fixture candidate recipe={}, key={}", recipe.getId(), EmiBookmarkGroups.key(candidate));
        }
        EmiBookmarkGroups.addRecipe(root, true);
        String actualAnchors = EmiFavorites.favorites.stream().map(f -> "recipe="
            + (f.getRecipe() == null ? null : f.getRecipe().getId()) + ",key=" + EmiBookmarkGroups.key(f)).toList().toString();
        LOGGER.info("addRecipe(root,true) actual count={} anchors={}", EmiFavorites.favorites.size(), actualAnchors);
        check(EmiFavorites.favorites.size() == 4, "addRecipe(root,true) expected four default child recipes; count="
            + EmiFavorites.favorites.size() + " anchors=" + actualAnchors);
        bookmarkGroup = EmiBookmarkGroups.groupOf(EmiFavorites.favorites.get(0));
        check(bookmarkGroup.todo && bookmarkGroup.crafting && !bookmarkGroup.collapsed, "Expanded recipe group initial flags");
        check(bookmarkGroup.anchors().size() == 4 && bookmarkGroup.anchors().stream().allMatch(EmiBookmarkGroups::isFullRecipe),
            "Recipe anchors were not grouped as full recipes");
        bookmarkScreen = new RecipeScreen(null, Map.of(category, bookmarkRecipes)) {
            @Override public void render(GuiGraphics raw, int mouseX, int mouseY, float delta) {
                super.render(raw, mouseX, mouseY, delta);
                // Observation only: production screen/mixin render path owns projection,
                // framebuffer binding, manager rendering and final buffer flush.
                bookmarkRenderedFrames++;
            }
        };
        client.setScreen(bookmarkScreen);
        checkLocalGroupTree(client);
        EmiScreenManager.forceRecalculate();
        bookmarkPanel = EmiScreenManager.getPanelFor(SidebarSide.LEFT);
        check(bookmarkPanel != null, "Native left sidebar missing");
        bookmarkPanel.setType(SidebarType.FAVORITES);
        check(bookmarkPanel.isVisible() && bookmarkPanel.getType() == SidebarType.FAVORITES,
            "Native left sidebar is not visible FAVORITES");
        check(bookmarkPanel.space != null && bookmarkPanel.space.tw >= 4 && bookmarkPanel.space.th >= 6,
            "Need at least four native sidebar columns and six rows at the requested window size");
        check(EmiConfig.viewRecipes.matchesMouse(0) && EmiConfig.viewUses.matchesMouse(1) && EmiConfig.craftOne.matchesMouse(0),
            "Run with default mouse bindings (left recipe/craft, right uses)");
        if (!MODEL_MODIFIERS) {
            robot = new Robot();
            GLFW.glfwFocusWindow(client.getWindow().getWindow());
        } else {
            LOGGER.warn("MODEL MODIFIERS explicitly enabled: CtrlAltUI UNVERIFIED; model APIs replace only modifier actions");
        }
        LOGGER.info("Opened real RecipeScreen + native LEFT/FAVORITES; borrowed reload status {} -> 2", savedReloadStatus);
    }

    private void checkLocalGroupTree(Minecraft client) throws Exception {
        var globalTree = BoM.tree;
        var currentPlan = dev.emi.emi.bom.CraftingPlans.current();
        boolean globalMode = BoM.craftingMode;
        BoMScreen original = screen;
        check(dev.emi.emi.runtime.BookmarkGroupActions.openTree(bookmarkGroup), "Group tree helper rejected real group");
        check(client.screen instanceof BoMScreen, "Group helper did not open real BoMScreen");
        screen = (BoMScreen) client.screen;
        try {
            Field model = BoMScreen.class.getDeclaredField("model");
            model.setAccessible(true);
            CraftingTreeModel snapshot = (CraftingTreeModel) model.get(screen);
            check(snapshot != null && !snapshot.entries.isEmpty(), "Group tree snapshot model is empty");
            check(BoM.tree == globalTree && dev.emi.emi.bom.CraftingPlans.current() == currentPlan, "Group tree replaced global tree/plan");
            for (String action : List.of("save", "delete", "copy", "paste", "next"))
                check(!button(action).active, "Group snapshot enabled persistent action: " + action);
            click("inventory_off");
            check(button("inventory_on") != null && BoM.craftingMode == globalMode, "Group inventory toggle changed global mode");
            click("inventory_on");
            check(BoM.tree == globalTree && dev.emi.emi.bom.CraftingPlans.current() == currentPlan && BoM.craftingMode == globalMode,
                "Group controls changed active global plan state");
            screen.onClose();
            check(client.screen == bookmarkScreen, "Group tree did not return original RecipeScreen identity");
            LOGGER.info("Local group tree snapshot/disabled persistence/inventory isolation/return-screen assertions passed");
        } finally {
            screen = original;
        }
    }

    private void checkBookmarkTodo(Minecraft client) throws Exception {
        List<Row> rows = EmiBookmarkGroups.rows(bookmarkGroup, EmiScreenManager.lastPlayerInventory);
        Method displayAmount = RowFavorite.class.getDeclaredMethod("displayAmount");
        displayAmount.setAccessible(true);
        for (RowFavorite visible : visibleBookmarks()) {
            check(((Long) displayAmount.invoke(visible)) == visible.row.calculatedAmount,
                "Unmodified native row must display calculated amount");
        }
        Method modeAmount = RowFavorite.class.getDeclaredMethod("displayAmount", boolean.class, boolean.class);
        modeAmount.setAccessible(true);
        for (RowFavorite visible : visibleBookmarks()) {
            check(((Long) modeAmount.invoke(visible, false, true)) == visible.row.calculatedAmount,
                "Ctrl must retain linked calculation for a crafting group");
            check(((Long) modeAmount.invoke(visible, true, true)) == visible.row.remainingAmount,
                "Shift remaining quantity keeps priority over Ctrl");
        }
        Method tooltipMode = RowFavorite.class.getDeclaredMethod("getTooltip", boolean.class);
        tooltipMode.setAccessible(true);
        for (RowFavorite visible : visibleBookmarks()) {
            List<?> normal = (List<?>) tooltipMode.invoke(visible, false);
            List<?> details = (List<?>) tooltipMode.invoke(visible, true);
            check(normal.size() == visible.getStack().getTooltip().size(),
                "Without Alt bookmark tooltip must contain only normal ingredient properties");
            check(details.size() > normal.size(), "Alt must reveal bookmark quantity and operation tips");
            check(visible.getTooltip().size() == normal.size(), "Unmodified tooltip leaked bookmark tips");
        }
        int at = 0;
        for (EmiRecipe recipe : bookmarkRecipes) {
            Row result = rows.get(at++);
            check(!result.input && result.recipe == recipe, "Todo result order differs from explicit recipe anchors");
            int[] point = bookmarkPoint(f -> f.row == result);
            int local = (point[1] - bookmarkPanel.space.ty) / 18;
            check(point[0] == bookmarkPanel.space.getX(0, local) + 8 + 9,
                "Todo recipe result is not in first column: " + recipe.getId());
            for (EmiIngredient input : recipe.getInputs()) {
                Row following = rows.get(at++);
                check(following.input && following.anchor == result.anchor && EmiIngredient.areEqual(following.ingredient, input),
                    "Todo inputs do not immediately follow their recipe result");
                int[] inputCell = bookmarkPoint(f -> f.row == following);
                check(inputCell[1] == point[1] && inputCell[0] > point[0], "Todo inputs did not follow result on its native sidebar row");
            }
        }
        check(at == rows.size(), "Unexpected todo rows");
        check(rows.stream().filter(r -> !r.input && r.recipe == bookmarkRecipes.get(2)).findFirst().orElseThrow().amount == 12,
            "Shared planks must round shared demand 8+2 to three batches / 12 output");
        check(rows.stream().filter(r -> r.input && r.ingredient.getEmiStacks().get(0).isEqual(EmiStack.of(Items.OAK_LOG)))
            .findFirst().orElseThrow().amount == 3, "Shared raw log quantity must be three, not per-branch rounded four");
        resultPoint = bookmarkPoint(f -> !f.row.input && f.row.recipe == bookmarkRecipes.get(0));
        inputPoint = bookmarkPoint(f -> f.row.input && f.row.ingredient.getEmiStacks().get(0).isEqual(EmiStack.of(Items.OAK_LOG)));
        var hovered = EmiScreenManager.getHoveredStack(inputPoint[0], inputPoint[1], false);
        check(hovered.getStack() instanceof RowFavorite && hovered.getRecipeContext() == null,
            "Group input leaked its parent recipe into native hovered interaction");
        managerClick(inputPoint, 0);
        check(client.screen == bookmarkScreen, "Terminal group input opened parent recipe screen");
        renderBookmarkCapture(client, "08-bookmarks-todo", true);
        LOGGER.info("Native todo rows: first-column results + adjacent inputs; shared logs=3; terminal input has no recipe context");
    }

    private void checkBookmarkCompact(Minecraft client) throws Exception {
        managerClick(gutterPoint(resultPoint), 0);
        check(!bookmarkGroup.todo && bookmarkGroup.crafting, "Gutter left-click did not toggle todo off");
        List<Row> rows = EmiBookmarkGroups.rows(bookmarkGroup, EmiScreenManager.lastPlayerInventory);
        check(rows.size() == 4 && rows.stream().noneMatch(r -> r.input), "Compact view must contain only recipe results");
        check(visibleBookmarks().size() == 4, "Native compact sidebar still displays inputs");
        renderBookmarkCapture(client, "09-bookmarks-compact", true, () -> {
            managerClick(gutterPoint(bookmarkPoint(f -> f.row.recipe == bookmarkRecipes.get(0))), 0);
            check(bookmarkGroup.todo, "Gutter left-click did not restore todo");
        });
    }

    private void checkBookmarkCrafting(Minecraft client) throws Exception {
        resultPoint = bookmarkPoint(f -> !f.row.input && f.row.recipe == bookmarkRecipes.get(0));
        managerClick(gutterPoint(resultPoint), 1);
        check(!bookmarkGroup.crafting, "Gutter right-click did not disable crafting");
        check(EmiBookmarkGroups.rows(bookmarkGroup, EmiScreenManager.lastPlayerInventory).stream().filter(r -> !r.input && r.recipe == bookmarkRecipes.get(2))
            .findFirst().orElseThrow().amount == 4, "Noncrafting mode must use one explicit plank batch");
        renderBookmarkCapture(client, "10-bookmarks-crafting-off", false, () -> {
            managerClick(gutterPoint(resultPoint), 1);
            check(bookmarkGroup.crafting, "Gutter right-click did not restore crafting");
        });
    }

    private void checkBookmarkQuantity() throws Exception {
        if (!MODEL_MODIFIERS) check(EmiInput.isControlDown(), "Robot Ctrl was not observed by GLFW; keep the client focused");
        long previous = EmiBookmarkGroups.multiplier(bookmarkGroup.anchors().get(0));
        if (MODEL_MODIFIERS) EmiBookmarkGroups.adjust(bookmarkGroup, bookmarkGroup.anchors().get(0), 1);
        else check(EmiScreenManager.mouseScrolled(resultPoint[0], resultPoint[1], 1), "Native Ctrl-scroll not consumed");
        check(EmiBookmarkGroups.multiplier(bookmarkGroup.anchors().get(0)) == previous + 1, "Ctrl-scroll did not adjust root quantity");
        check(EmiBookmarkGroups.rows(bookmarkGroup, EmiScreenManager.lastPlayerInventory).stream().filter(r -> r.input && r.recipe == bookmarkRecipes.get(2))
            .findFirst().orElseThrow().amount == 5, "Two roots need five shared raw logs (20 planks)");
        Method display = RowFavorite.class.getDeclaredMethod("displayAmount");
        display.setAccessible(true);
        for (RowFavorite visible : visibleBookmarks())
            check(((Long) display.invoke(visible)) == visible.row.calculatedAmount,
                "Ctrl-scroll must keep linked calculated amounts visible throughout the crafting group");
        if (MODEL_MODIFIERS) EmiBookmarkGroups.adjust(bookmarkGroup, bookmarkGroup.anchors().get(0), -1);
        else check(EmiScreenManager.mouseScrolled(resultPoint[0], resultPoint[1], -1), "Native Ctrl-scroll reset not consumed");
        check(EmiBookmarkGroups.multiplier(bookmarkGroup.anchors().get(0)) == previous, "Quantity reset failed");
        releaseModifier();
    }

    private void checkBookmarkCollapsed(Minecraft client) throws Exception {
        if (MODEL_MODIFIERS) EmiBookmarkGroups.toggleCollapsed(bookmarkGroup);
        else {
            check(EmiInput.isAltDown(), "Robot Alt was not observed by GLFW; keep the client focused");
            managerClick(gutterPoint(resultPoint), 0);
        }
        check(bookmarkGroup.collapsed, "Alt gutter click did not collapse group");
        List<Row> rows = EmiBookmarkGroups.rows(bookmarkGroup, EmiScreenManager.lastPlayerInventory);
        check(rows.size() == 1 && !rows.get(0).input && rows.get(0).recipe == bookmarkRecipes.get(0), "Collapsed group must keep its root result");
        renderBookmarkCapture(client, "11-bookmarks-collapsed", true, () -> {
            if (MODEL_MODIFIERS) EmiBookmarkGroups.toggleCollapsed(bookmarkGroup);
            else {
                // Keep Alt down until the collapsed state has really rendered and captured.
                check(EmiInput.isAltDown(), "Alt released before collapsed frame capture");
                managerClick(bookmarkPoint(f -> !f.row.input), 0);
            }
            check(!bookmarkGroup.collapsed && client.screen == bookmarkScreen, "Collapsed group root cannot expand safely");
            releaseModifier();
        });
    }

    private void checkBookmarkExpanded(Minecraft client) throws Exception {
        check(!EmiInput.isAltDown() && !EmiInput.isControlDown(), "Synthetic modifiers were not released");
        check(EmiBookmarkGroups.rows(bookmarkGroup, EmiScreenManager.lastPlayerInventory).size() == 9, "Expanded todo rows did not return");
        renderBookmarkCapture(client, "12-bookmarks-expanded", true);
    }

    private void checkBookmarkDragAndPersistence(Minecraft client) throws Exception {
        // A second standalone group guarantees a real multi-row gutter selection.
        EmiBookmarkGroups.addRecipe(recipe("bookmark_extra", Items.FURNACE, 1, EmiStack.of(Items.COBBLESTONE, 8)), false);
        List<EmiFavorite> all = new ArrayList<>(EmiFavorites.favorites);
        checkGroupReorder(all);
        int[] first = gutterPoint(bookmarkPoint(f -> f.row.anchor == all.get(0)));
        int[] last = gutterPoint(bookmarkPoint(f -> f.row.anchor == all.get(all.size() - 1)));
        check(first[1] != last[1], "Selection drag needs distinct real sidebar rows");
        check(EmiScreenManager.mouseClicked(first[0], first[1], 0), "Gutter drag press not consumed");
        check(EmiScreenManager.mouseDragged(last[0], last[1], 0, last[0] - first[0], last[1] - first[1]), "Native gutter drag not consumed");
        check(EmiScreenManager.mouseReleased(last[0], last[1], 0), "Gutter drag release not consumed");
        bookmarkGroup = EmiBookmarkGroups.groupOf(all.get(0));
        check(bookmarkGroup.anchors().size() == all.size(), "Native gutter drag did not group selected anchors");
        // Right drag the same rows to exercise native ungroup, then use model group
        // API to prepare the persisted final screenshot with readable todo rows.
        first = gutterPoint(bookmarkPoint(f -> f.row.anchor == all.get(0)));
        last = gutterPoint(bookmarkPoint(f -> f.row.anchor == all.get(all.size() - 1)));
        // Compact results may share one row; toggle todo first to make a true selection.
        managerClick(first, 0);
        check(bookmarkGroup.todo, "New group todo toggle failed");
        last = gutterPoint(bookmarkPoint(f -> f.row.anchor == all.get(all.size() - 1)));
        check(EmiScreenManager.mouseClicked(first[0], first[1], 1), "Ungroup press not consumed");
        check(EmiScreenManager.mouseDragged(last[0], last[1], 1, last[0] - first[0], last[1] - first[1]), "Ungroup drag not consumed");
        check(EmiScreenManager.mouseReleased(last[0], last[1], 1), "Ungroup release not consumed");
        check(all.stream().allMatch(f -> EmiBookmarkGroups.groupOf(f) == EmiBookmarkGroups.defaultGroup()), "Native right gutter drag did not ungroup");
        EmiBookmarkGroups.group(all.subList(0, 4));
        bookmarkGroup = EmiBookmarkGroups.groupOf(all.get(0));
        EmiBookmarkGroups.toggleView(bookmarkGroup);
        EmiBookmarkGroups.toggleCrafting(bookmarkGroup);
        EmiBookmarkGroups.adjust(bookmarkGroup, all.get(0), 1);
        EmiBookmarkGroups.toggleCollapsed(bookmarkGroup);
        EmiBookmarkGroups.toggleRecipe(bookmarkGroup, bookmarkRecipes.get(1));
        JsonObject saved = EmiBookmarkGroups.save().deepCopy();
        Files.writeString(output.resolve("bookmark-groups-roundtrip.json"), saved.toString(), StandardCharsets.UTF_8);
        EmiBookmarkGroups.load(null);
        check(EmiBookmarkGroups.groupOf(all.get(0)) == EmiBookmarkGroups.defaultGroup(), "load(null) did not clear membership");
        EmiBookmarkGroups.load(saved);
        bookmarkGroup = EmiBookmarkGroups.groupOf(all.get(0));
        check(bookmarkGroup.todo && bookmarkGroup.crafting && bookmarkGroup.collapsed && bookmarkGroup.anchors().size() == 4,
            "save/load lost group flags or members");
        check(EmiBookmarkGroups.multiplier(all.get(0)) == 2 && EmiBookmarkGroups.isFullRecipe(all.get(0))
            && bookmarkGroup.collapsedRecipes.contains(bookmarkRecipes.get(1).getId().toString()), "save/load lost multiplier/full recipe/per-recipe collapse");
        EmiBookmarkGroups.toggleCollapsed(bookmarkGroup);
        EmiBookmarkGroups.toggleRecipe(bookmarkGroup, bookmarkRecipes.get(1));
        EmiBookmarkGroups.adjust(bookmarkGroup, all.get(0), -1);
        checkNativeSynthetic();
        // Force multiple pages with complete real-item recipes, not fabricated row internals.
        for (int i = 0; i < bookmarkPanel.space.th + 2; i++) {
            EmiBookmarkGroups.addRecipe(recipe("bookmark_page_" + i, Items.STONE, 1, EmiStack.of(Items.COBBLESTONE)), false);
        }
        check(bookmarkPanel.bookmarks.pages() > 1, "Native bookmarks pagination did not grow");
        int page = bookmarkPanel.page;
        check(EmiScreenManager.mouseScrolled(first[0], first[1], -1), "Native page scroll not consumed");
        check(bookmarkPanel.page != page, "Unmodified manager scroll did not change bookmark page");
        check(EmiScreenManager.mouseScrolled(first[0], first[1], 1), "Native page scroll reset not consumed");
        check(bookmarkPanel.page == page, "Native bookmark page did not restore");
        renderBookmarkCapture(client, "13-bookmarks-roundtrip", true, () -> bookmarksChecked = true);
    }

    private void checkGroupReorder(List<EmiFavorite> all) throws Exception {
        var sidebar = bookmarkPanel.bookmarks;
        var source = EmiBookmarkGroups.groupOf(all.get(all.size() - 1));
        var target = bookmarkGroup;
        int[] start = gutterPoint(bookmarkPoint(f -> f.row.group == source));
        check(sidebar.mouseClicked(start[0], start[1], 0), "Group reorder bracket press failed");
        // Exercise the same drag initializer without synthesizing OS Shift.
        Method begin = sidebar.getClass().getDeclaredMethod("beginGroupMove", int.class, int.class);
        begin.setAccessible(true);
        begin.invoke(sidebar, start[0], start[1]);
        Field ghosts = sidebar.getClass().getDeclaredField("groupPreview"); ghosts.setAccessible(true);
        check(!((List<?>) ghosts.get(sidebar)).isEmpty(), "Group drag preview was not captured");
        int[] interior = gutterPoint(bookmarkPoint(f -> f.row.group == target && f.row.anchor == all.get(1)));
        long revision = EmiBookmarkGroups.revision();
        check(sidebar.mouseDragged(interior[0], interior[1], 0), "Live group drag not consumed");
        check(EmiBookmarkGroups.revision() == revision, "Group inserted inside another multi-row group");
        int[] boundary = gutterPoint(bookmarkPoint(f -> f.row.group == target && f.row.anchor == all.get(0)));
        sidebar.mouseDragged(boundary[0], boundary[1], 0);
        check(EmiFavorites.favorites.get(0) == all.get(all.size() - 1), "Upward group move did not apply before mouse release");
        sidebar.mouseReleased(boundary[0], boundary[1], 0);
        check(((List<?>) ghosts.get(sidebar)).isEmpty(), "Release retained group preview");
        start = gutterPoint(bookmarkPoint(f -> f.row.group == source));
        sidebar.mouseClicked(start[0], start[1], 0); begin.invoke(sidebar, start[0], start[1]);
        // Downward insertion waits until the target's final row.
        boundary = gutterPoint(bookmarkPoint(f -> f.row.group == target && f.row.anchor == all.get(3)));
        sidebar.mouseDragged(boundary[0], boundary[1], 0);
        check(EmiFavorites.favorites.equals(all), "Downward group move failed to restore order at the lower boundary");
        sidebar.mouseReleased(boundary[0], boundary[1], 0);
        check(target.anchors().size() == 4 && source.anchors().size() == 1 && target.todo && target.crafting,
            "Live group reorder changed membership or group state");
        LOGGER.info("Group move: live upward/downward boundaries, interior rejection, preview and release cleanup passed; Shift initializer invoked directly");
    }

    private void checkNativeSynthetic() {
        List<EmiFavorite.Synthetic> previous = new ArrayList<>(EmiFavorites.syntheticFavorites);
        int previousPage = bookmarkPanel.page;
        EmiFavorite.Synthetic progress = new EmiFavorite.Synthetic(EmiStack.of(Items.DIAMOND), 7, 7);
        try {
            EmiFavorites.syntheticFavorites.clear();
            EmiFavorites.syntheticFavorites.add(progress);
            // Do not forceRecalculate/updateSynthetic: this fixture supplies an explicit
            // native progress object after the null-player inventory has stabilized.
            bookmarkPanel.bookmarks.invalidate();
            boolean found = false;
            for (int page = 0; page < bookmarkPanel.bookmarks.pages() && !found; page++) {
                bookmarkPanel.page = page;
                var space = bookmarkPanel.space;
                for (int row = 0; row < space.th && !found; row++) {
                    for (int col = 0; col < space.getWidth(row); col++) {
                        int mx = space.getX(0, row) + col * 18 + 9;
                        int my = space.ty + row * 18 + 9;
                        if (bookmarkPanel.bookmarks.hovered(mx, my) != progress) continue;
                        check(EmiScreenManager.getHoveredStack(mx, my, false).getStack() == progress,
                            "Native manager wrapped/replaced Synthetic identity");
                        check(bookmarkPanel.bookmarks.tooltip(mx - 8, my).isEmpty(), "Synthetic row acquired a group gutter");
                        check(!bookmarkPanel.bookmarks.mouseClicked(mx, my, 0), "Synthetic row incorrectly entered group click handling");
                        check(!bookmarkPanel.bookmarks.mouseDragged(mx, my, 0), "Synthetic row retained a bogus group drag");
                        check(bookmarkGroup.anchors().size() == 4 && progress.amount == 7 && progress.state == -1,
                            "Native Synthetic mutated group members or progress state");
                        found = true;
                        break;
                    }
                }
            }
            check(found, "Native Synthetic disappeared from favorites pagination");
            LOGGER.info("Native Synthetic retained original identity/amount/tooltip path across bookmark pages; no group gutter or NPE");
        } finally {
            EmiFavorites.syntheticFavorites.clear();
            EmiFavorites.syntheticFavorites.addAll(previous);
            bookmarkPanel.bookmarks.invalidate();
            bookmarkPanel.page = previousPage;
        }
    }

    private List<RowFavorite> visibleBookmarks() {
        List<RowFavorite> visible = new ArrayList<>();
        var space = bookmarkPanel.space;
        for (int row = 0; row < space.th; row++) {
            int x = space.getX(0, row) + (space.getWidth(row) > 1 ? 8 : 0) + 9;
            for (int col = 0; col < Math.max(1, space.getWidth(row) - 1); col++) {
                EmiFavorite favorite = bookmarkPanel.bookmarks.hovered(x + col * 18, space.ty + row * 18 + 9);
                if (favorite instanceof RowFavorite value) visible.add(value);
            }
        }
        return visible;
    }

    private int[] bookmarkPoint(Predicate<RowFavorite> test) {
        var space = bookmarkPanel.space;
        for (int row = 0; row < space.th; row++) {
            int x = space.getX(0, row) + (space.getWidth(row) > 1 ? 8 : 0) + 9;
            for (int col = 0; col < Math.max(1, space.getWidth(row) - 1); col++) {
                int mx = x + col * 18, my = space.ty + row * 18 + 9;
                EmiFavorite favorite = bookmarkPanel.bookmarks.hovered(mx, my);
                if (favorite instanceof RowFavorite value && test.test(value)) return new int[] { mx, my };
            }
        }
        throw new IllegalStateException("Expected bookmark cell is not visible on native page " + bookmarkPanel.page);
    }

    private int[] gutterPoint(int[] point) {
        var space = bookmarkPanel.space;
        return new int[] { space.getX(0, (point[1] - space.ty) / 18) + 3, point[1] };
    }

    private void managerClick(int[] point, int button) {
        check(EmiScreenManager.mouseClicked(point[0], point[1], button), "Manager mouse press not consumed");
        EmiScreenManager.mouseReleased(point[0], point[1], button);
    }

    private void holdModifier(int key) {
        if (MODEL_MODIFIERS) return;
        releaseModifier();
        Minecraft client = Minecraft.getInstance();
        long window = client.getWindow().getWindow();
        int[] x = new int[1], y = new int[1];
        int[] left = new int[1], top = new int[1], right = new int[1], bottom = new int[1];
        GLFW.glfwGetWindowPos(window, x, y);
        GLFW.glfwGetWindowFrameSize(window, left, top, right, bottom);
        check(top[0] > 0, "Real modifier focus requires a decorated window title bar; frameTop=" + top[0]);
        int titleX = x[0] + 120;
        int titleY = y[0] - Math.max(4, top[0] / 2);
        check(titleY >= 0, "Client title bar is outside desktop: " + titleX + "," + titleY);
        GLFW.glfwShowWindow(window);
        GLFW.glfwFocusWindow(window);
        // Win32 may reject focus requests from a background process. A real title-bar
        // click makes this exact client foreground without clicking any recipe/grid.
        robot.mouseMove(titleX, titleY);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            // GLFW_FOCUSED can remain cached true while another HWND is foreground.
            // One real OS foreground request; Robot remains the actual key source.
            com.sun.jna.NativeLibrary user32 = com.sun.jna.NativeLibrary.getInstance("user32");
            long hwnd = org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(window);
            int accepted = user32.getFunction("SetForegroundWindow", com.sun.jna.Function.ALT_CONVENTION)
                .invokeInt(new Object[] {new com.sun.jna.Pointer(hwnd)});
            LOGGER.info("One Win32 SetForegroundWindow request accepted={} hwnd={}", accepted, hwnd);
        }
        installModifierDiagnostics(window);
        modifierEvidence.clear();
        logModifierDiagnostics(client, "before Robot keyPress");
        pendingModifier = key;
        modifierDeadline = System.nanoTime() + 2_000_000_000L;
        modifierFocusFrame = bookmarkRenderedFrames;
        LOGGER.info("Real modifier {} titlebar focus click at {},{}; GLFW focused={}", key, titleX, titleY,
            GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED));
    }

    private boolean pollModifier(Minecraft client) {
        long window = client.getWindow().getWindow();
        boolean focused = GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
        boolean foreground = true;
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            com.sun.jna.Pointer active = com.sun.jna.NativeLibrary.getInstance("user32")
                .getFunction("GetForegroundWindow", com.sun.jna.Function.ALT_CONVENTION).invokePointer(new Object[0]);
            foreground = com.sun.jna.Pointer.nativeValue(active) == org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(window);
        }
        boolean observed = pendingModifier == KeyEvent.VK_CONTROL ? EmiInput.isControlDown()
            : pendingModifier == KeyEvent.VK_ALT ? EmiInput.isAltDown() : EmiInput.isShiftDown();
        if (modifierPressed && focused && observed) {
            LOGGER.info("Real modifier {} confirmed by GLFW after foreground click", pendingModifier);
            pendingModifier = 0;
            return true;
        }
        if (System.nanoTime() >= modifierDeadline) {
            logModifierDiagnostics(client, "deadline before releasing physical key");
            throw new IllegalStateException("Desktop input environment failure: modifier=" + pendingModifier
                + ", GLFW focused=" + focused + ", keyPressed=" + modifierPressed + ", observed=" + observed
                + "; real titlebar focus/GLFW modifier did not complete within 2 seconds; " + modifierEvidence);
        }
        if (!modifierPressed && focused && foreground && bookmarkRenderedFrames > modifierFocusFrame) {
            heldModifier = pendingModifier;
            robot.keyPress(heldModifier);
            modifierPressed = true;
            LOGGER.info("Foreground GLFW window confirmed; Robot modifier {} keyPress sent", heldModifier);
        }
        return false;
    }

    private void installModifierDiagnostics(long window) {
        if (diagnosticKeyCallback != null) return;
        // Observe the REAL native callback and forward it unchanged. Calling this
        // callback ourselves would not update GLFW's internal glfwGetKey state.
        diagnosticKeyCallback = org.lwjgl.glfw.GLFWKeyCallback.create((handle, key, scan, action, mods) -> {
            String event = "native callback key=" + key + " scan=" + scan + " action=" + action + " mods=" + mods;
            modifierEvidence.add(event);
            LOGGER.info("Modifier diagnostics {}", event);
            if (previousKeyCallback != null) previousKeyCallback.invoke(handle, key, scan, action, mods);
        });
        previousKeyCallback = GLFW.glfwSetKeyCallback(window, diagnosticKeyCallback);
    }

    private void logModifierDiagnostics(Minecraft client, String phase) {
        long window = client.getWindow().getWindow();
        StringBuilder data = new StringBuilder(phase).append(" os.name=").append(System.getProperty("os.name"))
            .append(" isMac=").append(Minecraft.ON_OSX)
            .append(" focused=").append(GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_FOCUSED));
        for (int code : new int[] {341, 345, 342, 346, 343, 347}) data.append(" glfw[").append(code).append("]=").append(GLFW.glfwGetKey(window, code));
        data.append(" Screen.ctrl=").append(net.minecraft.client.gui.screens.Screen.hasControlDown())
            .append(" InputUtil.leftCtrl=").append(com.mojang.blaze3d.platform.InputConstants.isKeyDown(window, 341))
            .append(" EmiInput.ctrl=").append(EmiInput.isControlDown());
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            try {
                com.sun.jna.NativeLibrary user32 = com.sun.jna.NativeLibrary.getInstance("user32");
                com.sun.jna.Function async = user32.getFunction("GetAsyncKeyState", com.sun.jna.Function.ALT_CONVENTION);
                for (int vk : new int[] {0x11, 0xa2, 0xa3, 0x12, 0xa4, 0xa5})
                    data.append(" win32[").append(vk).append("]=").append((async.invokeInt(new Object[] {vk}) & 0x8000) != 0);
                com.sun.jna.Pointer foreground = user32.getFunction("GetForegroundWindow", com.sun.jna.Function.ALT_CONVENTION).invokePointer(new Object[0]);
                data.append(" foregroundHWND=").append(com.sun.jna.Pointer.nativeValue(foreground))
                    .append(" glfwHWND=").append(org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(window));
            } catch (Throwable unavailable) { data.append(" win32DiagnosticsUnavailable=").append(unavailable); }
        }
        modifierEvidence.add(data.toString());
        LOGGER.info("Modifier diagnostics {}", data);
        try { Files.writeString(output.resolve("modifier-diagnostics.txt"), String.join("\n", modifierEvidence) + "\n", StandardCharsets.UTF_8); }
        catch (Exception error) { LOGGER.warn("Could not persist modifier diagnostics", error); }
    }

    private void releaseModifier() {
        if (heldModifier != 0 && robot != null) robot.keyRelease(heldModifier);
        if (diagnosticKeyCallback != null) {
            GLFW.glfwSetKeyCallback(Minecraft.getInstance().getWindow().getWindow(), previousKeyCallback);
            diagnosticKeyCallback.free();
            diagnosticKeyCallback = previousKeyCallback = null;
        }
        heldModifier = pendingModifier = 0;
        modifierPressed = false;
    }

    private void renderBookmarkCapture(Minecraft client, String stage, boolean green) {
        renderBookmarkCapture(client, stage, green, () -> {});
    }

    private void renderBookmarkCapture(Minecraft client, String stage, boolean green, Runnable afterCapture) {
        check(pendingBookmarkCapture == null, "Previous native bookmark capture is still pending");
        pendingBookmarkCapture = new PendingBookmarkCapture(stage, bookmarkGroup.id, green, bookmarkRenderedFrames, afterCapture);
        LOGGER.info("Queued normal RecipeScreen frame capture: {} after frame={}", stage, bookmarkRenderedFrames);
    }

    private void captureNormalBookmarkFrame(Minecraft client, PendingBookmarkCapture pending) throws Exception {
        check(RenderSystem.isOnRenderThread(), "Native sidebar capture must run on render thread");
        check(client.screen == bookmarkScreen, "Normal bookmark frame screen changed");
        try (NativeImage pixels = Screenshot.takeScreenshot(client.getMainRenderTarget())) {
            int[] point = bookmarkPoint(f -> f.row.group.id.equals(pending.groupId()));
            int row = (point[1] - bookmarkPanel.space.ty) / 18;
            int gx = bookmarkPanel.space.getX(0, row) + 1;
            int gy = bookmarkPanel.space.ty + row * 18 + 8;
            double sx = (double) pixels.getWidth() / bookmarkScreen.width;
            double sy = (double) pixels.getHeight() / bookmarkScreen.height;
            int argb = pixels.getPixelRGBA((int) ((gx + 0.5) * sx), (int) ((gy + 0.5) * sy));
            int rgb = ((argb & 255) << 16) | (argb & 0xff00) | ((argb >>> 16) & 255); // NativeImage ABGR -> RGB
            Path image = output.resolve(pending.stage() + ".png");
            pixels.writeToFile(image.toFile());
            check(Files.size(image) > 0, "Empty native sidebar screenshot");
            check(rgb == (pending.green() ? 0x45a967 : 0x999999), "Native FAVORITES bracket pixel color: " + Integer.toHexString(rgb)
                + " at GUI " + gx + "," + gy + " (capture saved: " + image + ")");
        }
        LOGGER.info("Normal RecipeScreen/FAVORITES screenshot frame={} and {} bracket assertion: {}", bookmarkRenderedFrames,
            pending.green() ? "green" : "gray", pending.stage());
    }

    private void restoreBookmarkState() throws Exception {
        releaseModifier();
        if (savedFavorites == null) return;
        EmiFavorites.favorites.clear();
        EmiFavorites.favorites.addAll(savedFavorites);
        EmiFavorites.syntheticFavorites.clear();
        EmiFavorites.syntheticFavorites.addAll(savedSyntheticFavorites);
        EmiBookmarkGroups.load(savedGroups);
        BoM.defaultRecipes.clear();
        BoM.defaultRecipes.putAll(savedDefaults);
        BoM.addedRecipes.clear();
        BoM.addedRecipes.putAll(savedAddedRecipes);
        BoM.disabledRecipes.clear();
        BoM.disabledRecipes.addAll(savedDisabledRecipes);
        dev.emi.emi.registry.EmiRecipes.manager = savedRecipeManager;
        dev.emi.emi.registry.EmiIngredientSerializers.BY_CLASS.clear();
        dev.emi.emi.registry.EmiIngredientSerializers.BY_CLASS.putAll(savedSerializersByClass);
        dev.emi.emi.registry.EmiIngredientSerializers.BY_TYPE.clear();
        dev.emi.emi.registry.EmiIngredientSerializers.BY_TYPE.putAll(savedSerializersByType);
        EmiPersistentData.save();
        EmiConfig.enabled = savedEnabled;
        if (reloadStatus != null) reloadStatus.setInt(null, savedReloadStatus);
        savedFavorites = null;
    }

    private record SmokeRecipeManager(EmiRecipeCategory category, List<EmiRecipe> recipes)
            implements dev.emi.emi.api.recipe.EmiRecipeManager {
        @Override public List<EmiRecipeCategory> getCategories() { return List.of(category); }
        @Override public List<EmiIngredient> getWorkstations(EmiRecipeCategory ignored) { return List.of(); }
        @Override public List<EmiRecipe> getRecipes() { return recipes; }
        @Override public List<EmiRecipe> getRecipes(EmiRecipeCategory value) { return value == category ? recipes : List.of(); }
        @Override public EmiRecipe getRecipe(ResourceLocation id) { return recipes.stream().filter(r -> id.equals(r.getId())).findFirst().orElse(null); }
        @Override public List<EmiRecipe> getRecipesByInput(EmiStack stack) {
            return recipes.stream().filter(r -> r.getInputs().stream().flatMap(i -> i.getEmiStacks().stream()).anyMatch(stack::isEqual)).toList();
        }
        @Override public List<EmiRecipe> getRecipesByOutput(EmiStack stack) {
            return recipes.stream().filter(r -> r.getOutputs().stream().anyMatch(stack::isEqual)).toList();
        }
    }

    private EditBox nameField() {
        return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
            .filter(field -> field.getY() != 5).findFirst().orElseThrow();
    }

    private void checkNameDraft(Minecraft client) {
        String draft = "Unsaved smoke draft";
        EditBox name = nameField();
        screen.mouseClicked(name.getX() + 3, name.getY() + 3, 0);
        for (char c : draft.toCharArray()) check(screen.charTyped(c, 0), "Name character rejected");
        click("refresh");
        check(draft.equals(nameField().getValue()), "Refresh erased unsaved name draft");
        int width = screen.width, height = screen.height;
        screen.resize(client, Math.max(320, width - 30), Math.max(240, height - 20));
        check(draft.equals(nameField().getValue()), "Resize erased unsaved name draft");
        screen.resize(client, width, height);
        check(draft.equals(nameField().getValue()), "Restoring size erased unsaved name draft");
        LOGGER.info("Unsaved name draft survives refresh and resize");
    }

    private void checkFirstReverseSearch() throws Exception {
        searchField().setValue("minecraft:");
        // Shift is polled from physical GLFW key state; invoke only the direction seam,
        // then observe its private cursor rather than pretending modifiers press a key.
        Method find = BoMScreen.class.getDeclaredMethod("find", boolean.class);
        find.setAccessible(true);
        find.invoke(screen, true);
        Field cursor = BoMScreen.class.getDeclaredField("searchIndex");
        cursor.setAccessible(true);
        check(cursor.getInt(screen) == 4, "First reverse search did not choose final match");
        searchField().setValue("");
        click("fit");
        LOGGER.info("First reverse search selects last of five matches");
    }

    private void checkTruncatedTooltip(Minecraft client) throws Exception {
        List<Item> items = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) if (item != Items.AIR && items.size() < 132) items.add(item);
        check(items.size() == 132, "Not enough registered vanilla items for depth guard test");
        EmiRecipe goal = recipe("depth_0", items.get(0), 1, EmiStack.of(items.get(1)));
        BoM.setGoal(goal);
        for (int i = 1; i < 131; i++) {
            BoM.tree.resolutions.put(EmiStack.of(items.get(i)), recipe("depth_" + i, items.get(i), 1, EmiStack.of(items.get(i + 1))));
        }
        BoM.tree.resolutions.put(EmiStack.of(items.get(131)), null);
        BoM.tree.recalculate();
        CraftingTreeModel model = new CraftingTreeModel(BoM.tree, null);
        var cut = model.entries.stream().filter(entry -> entry.truncated).findFirst().orElseThrow(() -> new IllegalStateException("128-depth guard did not truncate"));
        check(!model.required.isEmpty(), "Truncated external material missing from costs");
        screen.recalculateTree();
        searchField().setValue(cut.ingredient.getEmiStacks().get(0).getId().toString());
        Method find = BoMScreen.class.getDeclaredMethod("find", boolean.class);
        find.setAccessible(true);
        find.invoke(screen, false);
        searchField().setFocused(false);
        nameField().setFocused(false);
        int left = Math.max(72, Math.min(132, screen.width / 5));
        int right = Math.max(102, Math.min(210, screen.width / 4));
        int columns = Math.max(1, screen.width / 56);
        int top = 30 + ((12 + columns - 1) / columns) * 21;
        int mx = left + 3 + Math.max(1, screen.width - left - right - 6) / 2;
        int my = top + Math.max(1, screen.height - top - 30) / 2;
        Method hovered = BoMScreen.class.getDeclaredMethod("hovered", double.class, double.class);
        hovered.setAccessible(true);
        Object visual = hovered.invoke(screen, (double) mx, (double) my);
        check(visual != null, "Truncated node not centered for tooltip");
        Field entry = visual.getClass().getDeclaredField("entry");
        entry.setAccessible(true);
        check(((CraftingTreeModel.Entry) entry.get(visual)).truncated, "Tooltip target is not truncated");
        String key = "emi.tree.truncated";
        check(!Component.translatable(key).getString().equals(key), "Truncation translation missing");
        GuiGraphics draw = new GuiGraphics(client, client.renderBuffers().bufferSource());
        screen.render(draw, mx, my, 0);
        draw.flush();
        capture(client, "07-truncated-tooltip");
        truncatedTooltipChecked = true;
        LOGGER.info("Actual depth-limited tree tooltip rendered; unexpanded external material retained");
    }

    private void search(String query) {
        EditBox field = searchField();
        check(screen.mouseClicked(field.getX() + 4, field.getY() + 4, 0), "Search field click not consumed");
        for (char c : query.toCharArray()) check(screen.charTyped(c, 0), "Search char not consumed: " + c);
        check(query.equals(field.getValue()), "Search text mismatch");
        check(screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0), "Search Enter not consumed");
    }

    private EditBox searchField() {
        return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
            .filter(field -> field.getY() == 5).findFirst().orElseThrow(() -> new IllegalStateException("Search field missing"));
    }

    private void clearSearch() {
        searchField().setValue("");
        screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0); // Removes focus; does not close the screen.
    }

    private Button button(String suffix) {
        String label = Component.translatable("emi.tree." + suffix).getString();
        return screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
            .filter(button -> button.getMessage().getString().equals(label)).findFirst()
            .orElseThrow(() -> new IllegalStateException("Toolbar button missing: " + suffix + " / " + label));
    }

    private void click(String suffix) {
        Button button = button(suffix);
        check(button.active, "Toolbar button inactive: " + suffix);
        double x = button.getX() + button.getWidth() / 2.0;
        double y = button.getY() + button.getHeight() / 2.0;
        check(screen.mouseClicked(x, y, 0), "Toolbar click not consumed: " + suffix);
        screen.mouseReleased(x, y, 0);
        LOGGER.info("Clicked toolbar {}", suffix);
    }

    private void capture(Minecraft client, String stage) throws Exception {
        check(RenderSystem.isOnRenderThread(), "Framebuffer capture must run on render thread");
        Path image = output.resolve(stage + ".png");
        try (NativeImage pixels = Screenshot.takeScreenshot(client.getMainRenderTarget())) {
            pixels.writeToFile(image.toFile());
        }
        check(Files.size(image) > 0, "Empty framebuffer capture: " + image);
        LOGGER.info("Framebuffer capture saved: {}", image.toAbsolutePath());
    }

    private boolean hasExport(Minecraft client) throws Exception {
        Path directory = client.gameDirectory.toPath().resolve("screenshots/emi/trees");
        if (!Files.isDirectory(directory)) return false;
        try (Stream<Path> files = Files.list(directory)) {
            return files.anyMatch(path -> {
                try {
                    return path.getFileName().toString().startsWith("plan") && path.toString().endsWith(".png")
                        && Files.getLastModifiedTime(path).toMillis() >= start && Files.size(path) > 0;
                } catch (Exception error) {
                    return false;
                }
            });
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private EmiRecipe recipe(String id, Item item, long amount, EmiIngredient... inputs) {
        return new TestRecipe(category, EmiPort.id("emi_tree_smoke", "/" + id), List.of(inputs), List.of(EmiStack.of(item, amount)));
    }

    private record TestRecipe(EmiRecipeCategory category, ResourceLocation id, List<EmiIngredient> inputs, List<EmiStack> outputs) implements EmiRecipe {
        @Override public EmiRecipeCategory getCategory() { return category; }
        @Override public ResourceLocation getId() { return id; }
        @Override public List<EmiIngredient> getInputs() { return inputs; }
        @Override public List<EmiStack> getOutputs() { return outputs; }
        @Override public int getDisplayWidth() { return 134; }
        @Override public int getDisplayHeight() { return 36; }
        @Override public void addWidgets(WidgetHolder widgets) {
            for (int i = 0; i < inputs.size(); i++) widgets.addSlot(inputs.get(i), i * 18, 9);
            widgets.addSlot(outputs.get(0), 108, 9).recipeContext(this);
        }
    }
}
