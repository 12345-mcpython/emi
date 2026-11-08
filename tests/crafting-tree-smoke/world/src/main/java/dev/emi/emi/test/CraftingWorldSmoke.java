package dev.emi.emi.test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.runtime.BookmarkCrafting;
import dev.emi.emi.runtime.EmiBookmarkGroups;
import dev.emi.emi.runtime.EmiBookmarkGroups.Group;
import dev.emi.emi.runtime.EmiFavorites;
import dev.emi.emi.runtime.EmiReloadManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Screenshot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;

/** Only real vanilla recipes, menus, server slots and packets; no Access seam or status overrides. */
public final class CraftingWorldSmoke implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("emi-crafting-world-smoke");
	private enum Stage { TITLE, LOADING, SETUP_INVENTORY, INVENTORY_READY, DISPLAY_READY, INVENTORY_RUNNING,
		INVENTORY_ACK, SETUP_TABLE, TABLE_READY, TABLE_RUNNING, TABLE_ACK,
		EXTRA_SETUP, EXTRA_READY, EXTRA_RUNNING, EXTRA_ACK,
		MATERIAL_SETUP, MATERIAL_READY, MATERIAL_RUNNING, MATERIAL_ACK, DONE }
	private Stage stage = Stage.TITLE;
	private Path output;
	private String worldName;
	private long deadline;
	private int ticks;
	private int runStarted;
	private int extra;
	private boolean serverFill;
	private CompletableFuture<?> pending;
	private final List<String> evidence = new ArrayList<>();

	@Override public void onInitializeClient() {
		if (!Boolean.getBoolean("emi.craftingWorldSmoke")) return;
		deadline = System.currentTimeMillis() + 240_000;
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		LOGGER.info("WORLD SMOKE armed: isolated flat survival integrated server, real inventory/table ack");
	}

	private void tick(Minecraft client) {
		if (stage == Stage.DONE) return;
		try {
			ticks++;
			check(System.currentTimeMillis() < deadline, "Timeout in " + stage + " after " + ticks + " client ticks");
			// Never pause the integrated server merely because its test window lost foreground focus.
			client.options.pauseOnLostFocus = false;
			switch (stage) {
				case TITLE -> {
					if (!(client.screen instanceof TitleScreen) || client.getOverlay() != null) return;
					Path run = client.gameDirectory.toPath().toAbsolutePath().normalize();
					check(run.endsWith(Path.of("run", "crafting-world-smoke")), "Unsafe run directory " + run);
					check(Path.of("").toAbsolutePath().normalize().equals(run), "Process directory differs from isolated runDir");
					worldName = "emi-world-smoke-" + System.currentTimeMillis();
					output = run.resolve("screenshots/crafting-world-smoke/" + worldName);
					Files.createDirectories(output);
					check(client.level == null && client.player == null, "Startup preparation must not require a world");
					var startup = dev.emi.emi.screen.EmiScreenManager.class.getDeclaredField("startupPrepared");
					startup.setAccessible(true);
					check(startup.getBoolean(null), "Title screen appeared before EMI interface preparation");
					var startupPool = dev.emi.emi.screen.EmiScreenManager.class.getDeclaredField("batchers");
					startupPool.setAccessible(true);
					Object pool = startupPool.get(null);
					var idle = pool.getClass().getDeclaredField("unclaimed");
					idle.setAccessible(true);
					check(((List<?>) idle.get(pool)).size() == 4, "Expected four prepared buffers at title screen");
					dev.emi.emi.screen.EmiScreenManager.prepareStartup();
					check(((List<?>) idle.get(pool)).size() == 4, "Repeated startup preparation allocated duplicate buffers");
					record("Title screen: four EMI buffers ready with no world/player; repeated startup preparation is a no-op");
					var pending = EmiReloadManager.class.getDeclaredField("synchronizedReloadPending");
					pending.setAccessible(true);
					EmiReloadManager.reloadRecipes();
					check(!pending.getBoolean(null), "Started initialization with tags missing");
					EmiReloadManager.reloadTags();
					check(pending.getBoolean(null), "Synchronized resources did not queue initialization");
					EmiReloadManager.tick();
					check(pending.getBoolean(null) && EmiReloadManager.getStatus() == 0,
						"Initialization started without world/player");
					EmiReloadManager.clear();
					check(!pending.getBoolean(null), "Disconnect retained pending initialization");
					EmiReloadManager.reloadTags();
					check(!pending.getBoolean(null), "Started initialization with recipes missing");
					EmiReloadManager.reloadRecipes();
					check(pending.getBoolean(null), "Reverse synchronization order did not queue initialization");
					EmiReloadManager.clear();
					check(!pending.getBoolean(null), "Second disconnect retained pending initialization");
					record("Entry scheduler: both synchronization orders queue once; missing world defers; disconnect cancels pending work");
					check(!Files.exists(client.getLevelSource().getBaseDir().resolve(worldName)), "World already exists");
					stage = Stage.LOADING; // loader can recursively pump tasks
					GameRules rules = new GameRules();
					rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
					rules.getRule(GameRules.RULE_DODAYLIGHTCYCLE).set(false, null);
					client.createWorldOpenFlows().createFreshLevel(worldName,
						new LevelSettings(worldName, GameType.SURVIVAL, false, Difficulty.PEACEFUL, false, rules, WorldDataConfiguration.SAFE_MODE),
						new WorldOptions(473921L, false, false),
						registries -> registries.get(Registries.WORLD_PRESET).get(WorldPresets.FLAT).createDimensionsRegistryHolder(), null);
				}
				case LOADING -> {
					if (client.level == null || client.player == null || client.getSingleplayerServer() == null || !EmiReloadManager.isLoaded()) return;
					check(client.gameMode != null && !client.player.isCreative(), "Expected real survival player");
					check(!(client.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>),
						"Inventory opened before natural world-entry initialization completed");
					var queued = EmiReloadManager.class.getDeclaredField("synchronizedReloadPending");
					queued.setAccessible(true);
					check(!queued.getBoolean(null), "World-entry initialization remained queued after load");
					record("World entry initialized EMI without opening any inventory screen");
					record("Loaded real flat survival world; EMI naturally loaded " + EmiApi.getRecipeManager().getRecipes().size() + " recipes");
					serverSetup(client, player -> {
						player.changeGameMode(GameType.SURVIVAL);
						player.getInventory().clear();
						player.getInventory().setStack(0, new ItemStack(Items.OAK_LOG));
						player.playerScreenHandler.syncState();
					});
					stage = Stage.SETUP_INVENTORY;
				}
				case SETUP_INVENTORY -> {
					if (!complete() || count(client, Items.OAK_LOG) != 1) return;
					var ready = dev.emi.emi.screen.EmiScreenManager.class.getDeclaredField("warmupComplete");
					ready.setAccessible(true);
					if (!ready.getBoolean(null)) return;
					check(client.screen == null, "Prewarm must finish without opening an inventory");
					var poolField = dev.emi.emi.screen.EmiScreenManager.class.getDeclaredField("batchers");
					poolField.setAccessible(true);
					Object pool = poolField.get(null);
					var idleField = pool.getClass().getDeclaredField("unclaimed");
					idleField.setAccessible(true);
					var buffers = new ArrayList<>((List<?>) idleField.get(pool));
					check(buffers.size() >= 4, "Expected four preallocated sidebar buffers before first inventory");
					record("Before first inventory: EMI fully loaded and four reusable sidebar buffer sets prepared with no screen");
					client.setScreen(new InventoryScreen(client.player));
					var activeField = pool.getClass().getDeclaredField("claimed");
					activeField.setAccessible(true);
					check(((java.util.Set<?>) activeField.get(pool)).stream().allMatch(buffers::contains),
						"First inventory did not reuse the preallocated sidebar buffer pool");
					stage = Stage.INVENTORY_READY;
					runStarted = ticks;
				}
				case INVENTORY_READY -> {
					if (ticks - runStarted < 10) return;
					check(client.screen instanceof InventoryScreen, "Not real InventoryScreen");
					check(client.player.currentScreenHandler == client.player.playerScreenHandler, "Not real player 2x2 menu");
					Group displayGroup = group("crafting_table", "oak_planks");
					var tableAnchor = displayGroup.anchors().get(0);
					EmiBookmarkGroups.adjust(displayGroup, tableAnchor, 9);
					for (var inventory : java.util.Arrays.asList(null, dev.emi.emi.api.recipe.EmiPlayerInventory.of(client.player))) {
						var displayRows = EmiBookmarkGroups.rows(displayGroup, inventory);
						check(displayRows.stream().noneMatch(row -> row.ingredient.isEmpty() || row.source.isEmpty()),
							"Empty shaped slot leaked into bookmark rows");
						check(displayRows.stream().anyMatch(row -> row.role == EmiBookmarkGroups.Role.RESULT
							&& row.ingredient.getEmiStacks().get(0).getItemStack().isOf(Items.CRAFTING_TABLE)
							&& row.calculatedAmount == 10), "Ten-table display goal changed");
					}
					record("Ten-table selected-material display: no empty ingredient or remainder rows in full/inventory views");
					// Reproduce the user's unselected any-plank tag and concrete producer.
					EmiFavorites.favorites.clear();
					EmiBookmarkGroups.load(null);
					for (String id : List.of("crafting_table", "oak_planks"))
						EmiBookmarkGroups.addRecipe(EmiApi.getRecipeManager().getRecipe(new ResourceLocation("minecraft", id)), false);
					EmiBookmarkGroups.group(List.copyOf(EmiFavorites.favorites));
					displayGroup = EmiBookmarkGroups.groupOf(EmiFavorites.favorites.get(0));
					EmiBookmarkGroups.toggleView(displayGroup);
					EmiBookmarkGroups.toggleCrafting(displayGroup);
					EmiBookmarkGroups.adjust(displayGroup, displayGroup.anchors().get(0), 9);
					var rawRows = EmiBookmarkGroups.rows(displayGroup, dev.emi.emi.api.recipe.EmiPlayerInventory.of(client.player));
					check(rawRows.stream().noneMatch(row -> row.ingredient.isEmpty() || row.source.isEmpty()), "Unselected tag leaked empty slots");
					check(rawRows.stream().noneMatch(row -> row.role == EmiBookmarkGroups.Role.REMAINDER), "Consumed plank output mislabeled as remainder");
					check(rawRows.stream().anyMatch(row -> row.recipe.getId().equals(new ResourceLocation("minecraft", "oak_planks"))
						&& row.role == EmiBookmarkGroups.Role.RESULT && row.calculatedAmount == 40), "Ten-table group failed to scale its plank producer to forty");
					check(rawRows.stream().anyMatch(row -> row.input && row.recipe.getId().equals(new ResourceLocation("minecraft", "oak_planks"))
						&& row.calculatedAmount == 10), "Ten-table group failed to scale raw logs to ten");
					check(EmiBookmarkGroups.effectiveRecipe(displayGroup, displayGroup.anchors().get(0).getRecipe()).getInputs().stream()
						.filter(input -> !input.isEmpty()).allMatch(input -> input.getEmiStacks().size() == 1
							&& input.getEmiStacks().get(0).getItemStack().isOf(Items.OAK_PLANKS)), "Automatic same-group producer choice not used for filling");
					record("Unselected tag display: " + rawRows.stream().map(row -> row.role + " "
						+ row.ingredient.getEmiStacks().get(0).getId() + " calculated=" + row.calculatedAmount).toList());
					stage = Stage.DISPLAY_READY;
					runStarted = ticks;
				}
				case DISPLAY_READY -> {
					if (ticks - runStarted < 4) return;
					capture(client, "00-ten-table-no-phantom-materials");
					Group group = group("crafting_table", "oak_planks");
					check(BookmarkCrafting.start(group, false), "2x2 group was not accepted");
					stage = Stage.INVENTORY_RUNNING;
					runStarted = ticks;
				}
				case INVENTORY_RUNNING -> {
					check(ticks - runStarted < 200, "2x2 runner did not finish");
					if (BookmarkCrafting.isRunning()) return;
					checkCompleted(2);
					check(count(client, Items.CRAFTING_TABLE) == 1, "Client did not receive crafted table; " + inventory(client));
					pending = client.getSingleplayerServer().submit(() -> snapshot(client, false));
					stage = Stage.INVENTORY_ACK;
				}
				case INVENTORY_ACK -> {
					if (!complete()) return;
					Snapshot ack = (Snapshot) pending.join();
					check(ack.table == 1 && ack.logs == 0 && ack.planks == 0 && ack.chest == 0, "Wrong authoritative 2x2 inventory: " + ack);
					check(ack.emptyGrid && ack.emptyCursor && ack.syncId == 0, "Dirty authoritative 2x2 menu: " + ack);
					check(count(client, Items.OAK_LOG) == ack.logs && count(client, Items.OAK_PLANKS) == ack.planks, "Client/server 2x2 disagreement");
					capture(client, "01-inventory-2x2-server-ack");
					record("2x2 real server ack " + ack + "; client table=1; recipe chain oak_log -> 4 oak_planks -> crafting_table");
					client.player.closeHandledScreen();
					serverSetup(client, player -> {
						player.getInventory().clear();
						player.getInventory().setStack(0, new ItemStack(Items.OAK_LOG, 2));
						BlockPos pos = player.getBlockPos();
						player.getServerWorld().setBlockState(pos, Blocks.CRAFTING_TABLE.getDefaultState());
						player.openHandledScreen(Blocks.CRAFTING_TABLE.getDefaultState().createScreenHandlerFactory(player.getServerWorld(), pos));
						player.currentScreenHandler.syncState();
					});
					stage = Stage.SETUP_TABLE;
				}
				case SETUP_TABLE -> {
					if (!complete() || !(client.screen instanceof CraftingScreen) || count(client, Items.OAK_LOG) != 2) return;
					check(client.player.currentScreenHandler instanceof CraftingMenu, "Missing real table handler");
					stage = Stage.TABLE_READY;
					runStarted = ticks;
				}
				case TABLE_READY -> {
					if (ticks - runStarted < 10) return;
					check(BookmarkCrafting.start(group("chest", "oak_planks"), false), "3x3 group was not accepted");
					stage = Stage.TABLE_RUNNING;
					runStarted = ticks;
				}
				case TABLE_RUNNING -> {
					check(ticks - runStarted < 250, "3x3 runner did not finish");
					if (BookmarkCrafting.isRunning()) return;
					checkCompleted(3);
					check(count(client, Items.CHEST) == 1, "Client did not receive crafted chest; " + inventory(client));
					pending = client.getSingleplayerServer().submit(() -> snapshot(client, true));
					stage = Stage.TABLE_ACK;
				}
				case TABLE_ACK -> {
					if (!complete()) return;
					Snapshot ack = (Snapshot) pending.join();
					check(ack.chest == 1 && ack.logs == 0 && ack.planks == 0 && ack.table == 0, "Wrong authoritative 3x3 inventory: " + ack);
					check(ack.emptyGrid && ack.emptyCursor && ack.syncId > 0, "Dirty authoritative table menu: " + ack);
					check(client.player.currentScreenHandler.syncId == ack.syncId, "Client/server menu sync ID mismatch");
					check(count(client, Items.OAK_LOG) == ack.logs && count(client, Items.OAK_PLANKS) == ack.planks, "Client/server 3x3 disagreement");
					capture(client, "02-table-3x3-server-ack");
					record("3x3 real server ack " + ack + "; client chest=1; recipe chain 2 oak_log -> 8 oak_planks -> chest");
					extra = 0;
					setupExtra(client);
				}
				case EXTRA_SETUP -> {
					if (!complete()) return;
					if (!(client.screen instanceof CraftingScreen)) return;
					stage = Stage.EXTRA_READY;
					runStarted = ticks;
				}
				case EXTRA_READY -> {
					if (ticks - runStarted < 10) return;
					Group group;
					if (extra == 0) group = group("crafting_table", "birch_planks", Items.BIRCH_PLANKS, Items.BIRCH_LOG);
					else group = singleGroup("oak_planks");
					// Disabled-chain groups intentionally reject crafting now; the
					// synthetic single-recipe fixture still exercises the handler.
					group.crafting = true;
					if (extra == 3) {
						serverFill = dev.emi.emi.platform.EmiClient.onServer;
						dev.emi.emi.platform.EmiClient.onServer = false;
					}
					boolean started = BookmarkCrafting.start(group, false);
					if (extra == 1) {
						check(!started && "foreign_or_locked_grid".equals(BookmarkCrafting.status().reason()),
							"Foreign cobblestone grid must reject: " + BookmarkCrafting.status());
					} else check(started, "Extra scenario rejected " + extra + ": " + BookmarkCrafting.status());
					stage = Stage.EXTRA_RUNNING;
					runStarted = ticks;
				}
				case EXTRA_RUNNING -> {
					check(ticks - runStarted < 180, "Extra runner timeout " + extra);
					if (BookmarkCrafting.isRunning()) return;
					if (extra != 1) checkCompleted(extra == 0 ? 2 : 1);
					var uuid = client.player.getUuid();
					pending = client.getSingleplayerServer().submit(() -> {
						var player = client.getSingleplayerServer().getPlayerManager().getPlayer(uuid);
						String ack = "extra=" + extra + " oakLog=" + count(player.getInventory(), Items.OAK_LOG)
							+ " birchLog=" + count(player.getInventory(), Items.BIRCH_LOG) + " planks=" + count(player.getInventory(), Items.OAK_PLANKS)
							+ " birchPlanks=" + count(player.getInventory(), Items.BIRCH_PLANKS) + " table=" + count(player.getInventory(), Items.CRAFTING_TABLE)
							+ " grid1=" + player.currentScreenHandler.slots.get(1).getStack();
						if (extra == 0) check(count(player.getInventory(), Items.OAK_LOG) == 1 && count(player.getInventory(), Items.BIRCH_LOG) == 0
							&& count(player.getInventory(), Items.BIRCH_PLANKS) == 0 && count(player.getInventory(), Items.CRAFTING_TABLE) == 1, "Birch selection failed " + ack);
						else if (extra == 1) check(player.currentScreenHandler.slots.get(1).getStack().isOf(Items.COBBLESTONE)
							&& count(player.getInventory(), Items.OAK_LOG) == 1 && count(player.getInventory(), Items.OAK_PLANKS) == 0, "Foreign grid changed " + ack);
						else check(count(player.getInventory(), Items.OAK_PLANKS) == 4 && count(player.getInventory(), Items.OAK_LOG) == (extra == 2 ? 1 : 0)
							&& player.currentScreenHandler.slots.get(1).getStack().isEmpty(), "Own-grid/vanilla batch mismatch " + ack);
						return ack;
					});
					stage = Stage.EXTRA_ACK;
				}
				case EXTRA_ACK -> {
					if (!complete()) return;
					record("Authoritative extra ack " + pending.join());
					capture(client, "0" + (extra + 3) + "-extra-server-ack");
					if (extra == 3) dev.emi.emi.platform.EmiClient.onServer = serverFill;
					if (++extra < 4) { setupExtra(client); return; }
					Files.writeString(output.resolve("PASS-CRAFTING.txt"), String.join("\n", evidence), StandardCharsets.UTF_8);
					client.player.closeHandledScreen();
					serverSetup(client, player -> {
						player.getInventory().clear();
						var chest = new net.minecraft.world.SimpleContainer(27);
						chest.setStack(0, new ItemStack(Items.COBBLESTONE, 64));
						player.openHandledScreen(new net.minecraft.world.SimpleMenuProvider(
							(syncId, inventory, entity) -> net.minecraft.world.inventory.ChestMenu.createGeneric9x3(syncId, inventory, chest),
							net.minecraft.network.chat.Component.literal("Smoke exact withdrawal")));
						player.currentScreenHandler.syncState();
					});
					stage = Stage.MATERIAL_SETUP;
				}
				case MATERIAL_SETUP -> {
					if (!complete() || !(client.player.currentScreenHandler instanceof net.minecraft.world.inventory.ChestMenu)) return;
					stage = Stage.MATERIAL_READY;
					runStarted = ticks;
				}
				case MATERIAL_READY -> {
					if (ticks - runStarted < 10) return;
					EmiFavorites.favorites.clear();
					EmiBookmarkGroups.load(new com.google.gson.JsonObject());
					EmiFavorites.favorites.add(new dev.emi.emi.runtime.EmiFavorite(dev.emi.emi.api.stack.EmiStack.of(Items.COBBLESTONE, 3), null));
					EmiBookmarkGroups.favoritesChanged();
					check(dev.emi.emi.runtime.BookmarkMaterialTransfer.start(EmiBookmarkGroups.defaultGroup(), false),
						"Material start rejected " + dev.emi.emi.runtime.BookmarkMaterialTransfer.status());
					stage = Stage.MATERIAL_RUNNING;
					runStarted = ticks;
				}
				case MATERIAL_RUNNING -> {
					check(ticks - runStarted < 240, "Material transfer did not stop");
					if (dev.emi.emi.runtime.BookmarkMaterialTransfer.isRunning()) return;
					var uuid = client.player.getUuid();
					pending = client.getSingleplayerServer().submit(() -> {
						var player = client.getSingleplayerServer().getPlayerManager().getPlayer(uuid);
						return "source=" + player.currentScreenHandler.slots.get(0).getStack().getCount()
							+ " player=" + count(player.getInventory(), Items.COBBLESTONE)
							+ " cursor=" + player.currentScreenHandler.getCursorStack().getCount();
					});
					stage = Stage.MATERIAL_ACK;
				}
				case MATERIAL_ACK -> {
					if (!complete()) return;
					record("Material real server ack " + pending.join() + " status=" + dev.emi.emi.runtime.BookmarkMaterialTransfer.status());
					Files.writeString(output.resolve("material-result.txt"), evidence.get(evidence.size() - 1), StandardCharsets.UTF_8);
					capture(client, "07-material-server-ack");
					check("source=61 player=3 cursor=0".equals(pending.join()), "Exact 3of64 withdrawal mismatch " + pending.join());
					var status = dev.emi.emi.runtime.BookmarkMaterialTransfer.status();
					check("completed".equals(status.result()) && status.completed() == 3, "Material raw ack failed " + status);
					Files.writeString(output.resolve("PASS.txt"), "PASS real integrated-server survival inventory/table crafting\n" + String.join("\n", evidence)
						+ "\nScope: BookmarkCrafting.start API, real vanilla handlers, real packet/slot ack. OS Ctrl/Alt and sidebar start click NOT covered.\n", StandardCharsets.UTF_8);
					LOGGER.info("WORLD SMOKE PASS: {}", output);
					stage = Stage.DONE;
					client.stop();
				}
				default -> { }
			}
		} catch (Throwable error) {
			stage = Stage.DONE;
			BookmarkCrafting.cancel();
			LOGGER.error("WORLD SMOKE FAIL", error);
			try {
				if (output != null) Files.writeString(output.resolve("FAIL.txt"), error + "\n" + String.join("\n", evidence), StandardCharsets.UTF_8);
			} catch (Exception writeError) { LOGGER.error("Could not write FAIL marker", writeError); }
			client.stop();
		}
	}

	private void setupExtra(Minecraft client) {
		serverSetup(client, player -> {
			player.currentScreenHandler.slots.get(1).setStack(ItemStack.EMPTY);
			player.getInventory().clear();
			player.getInventory().setStack(0, new ItemStack(Items.OAK_LOG));
			if (extra == 0) player.getInventory().setStack(1, new ItemStack(Items.BIRCH_LOG));
			if (extra == 1) player.currentScreenHandler.slots.get(1).setStack(new ItemStack(Items.COBBLESTONE));
			if (extra == 2) player.currentScreenHandler.slots.get(1).setStack(new ItemStack(Items.OAK_LOG));
			player.currentScreenHandler.onContentChanged(player.getInventory());
			player.currentScreenHandler.syncState();
		});
		stage = Stage.EXTRA_SETUP;
	}

	private Group singleGroup(String recipeId) {
		EmiFavorites.favorites.clear();
		EmiBookmarkGroups.load(new com.google.gson.JsonObject());
		EmiRecipe recipe = EmiApi.getRecipeManager().getRecipe(new ResourceLocation("minecraft", recipeId));
		check(recipe != null, "Missing recipe " + recipeId);
		EmiBookmarkGroups.addRecipe(recipe, false);
		return EmiBookmarkGroups.groupOf(EmiFavorites.favorites.get(0));
	}

	private Group group(String rootId, String dependencyId) {
		return group(rootId, dependencyId, Items.OAK_PLANKS, Items.OAK_LOG);
	}

	private Group group(String rootId, String dependencyId, Item plankChoice, Item logChoice) {
		EmiFavorites.favorites.clear();
		EmiBookmarkGroups.load(new com.google.gson.JsonObject());
		for (String id : List.of(rootId, dependencyId)) {
			EmiRecipe recipe = EmiApi.getRecipeManager().getRecipe(new ResourceLocation("minecraft", id));
			check(recipe != null, "Missing loaded vanilla recipe minecraft:" + id);
			EmiBookmarkGroups.addRecipe(recipe, false);
		}
		EmiBookmarkGroups.group(List.copyOf(EmiFavorites.favorites));
		Group group = EmiBookmarkGroups.groupOf(EmiFavorites.favorites.get(0));
		EmiBookmarkGroups.toggleView(group);
		EmiBookmarkGroups.toggleCrafting(group);
		// Vanilla recipes use plank/log tags. Make the real bookmark candidate
		// selection explicit so the selected oak producer is a genuine dependency.
		for (var anchor : group.anchors()) {
		  List<dev.emi.emi.api.stack.EmiIngredient> seen = new ArrayList<>();
		  for (var source : anchor.getRecipe().getInputs()) {
			if (seen.stream().anyMatch(previous -> dev.emi.emi.api.stack.EmiIngredient.areEqual(previous, source))) continue;
			seen.add(source);
			List<dev.emi.emi.api.stack.EmiStack> options = source.getEmiStacks();
			if (options.size() < 2) continue;
			for (int i = 0; i < options.size(); i++) {
				ItemStack option = options.get(i).getItemStack();
				if (option.isOf(plankChoice) || option.isOf(logChoice)) {
					var effective = EmiBookmarkGroups.effectiveRecipe(group, anchor.getRecipe()).getInputs()
						.get(anchor.getRecipe().getInputs().indexOf(source));
					int current = 0;
					for (int c = 0; c < options.size(); c++)
						if (dev.emi.emi.api.stack.EmiIngredient.areEqual(effective, options.get(c))) { current = c; break; }
					int delta = Math.floorMod(i - current, options.size());
					EmiBookmarkGroups.cycleIngredient(new EmiBookmarkGroups.Row(source, anchor.getRecipe(), anchor, true,
						source.getAmount(), 1, group), delta == 0 ? options.size() : delta);
					break;
				}
			}
		  }
		}
		check(group.anchors().size() == 2, "Expected exactly two selected real recipes");
		record("Start selected recipes " + rootId + ", " + dependencyId + " rows=" + EmiBookmarkGroups.executionRows(group, null).stream()
			.filter(row -> !row.input).map(row -> row.recipe.getId() + "x" + row.batches).toList());
		return group;
	}

	private void checkCompleted(int batches) {
		BookmarkCrafting.Status status = BookmarkCrafting.status();
		record("Production runner status=" + status);
		check("completed".equals(status.result()) && "budget_complete".equals(status.reason())
			&& status.completed() == batches && status.operations() == batches,
			"Runner did not acknowledge every real batch: " + status);
	}

	private void serverSetup(Minecraft client, Consumer<ServerPlayer> action) {
		var uuid = client.player.getUuid();
		pending = client.getSingleplayerServer().submit(() -> {
			var player = client.getSingleplayerServer().getPlayerManager().getPlayer(uuid);
			check(player != null, "Missing integrated-server player");
			action.accept(player);
		});
	}
	private boolean complete() {
		if (!pending.isDone()) return false;
		pending.join();
		return true;
	}
	private Snapshot snapshot(Minecraft client, boolean table) {
		ServerPlayer player = client.getSingleplayerServer().getPlayerManager().getPlayer(client.player.getUuid());
		check(player != null && player.interactionManager.getGameMode() == GameType.SURVIVAL, "Missing server survival player");
		AbstractContainerMenu handler = player.currentScreenHandler;
		check(table ? handler instanceof CraftingMenu : handler == player.playerScreenHandler, "Wrong authoritative menu");
		boolean empty = true;
		for (int i = 0; i < (table ? 10 : 5); i++) empty &= handler.slots.get(i).getStack().isEmpty();
		return new Snapshot(count(player.getInventory(), Items.OAK_LOG), count(player.getInventory(), Items.OAK_PLANKS),
			count(player.getInventory(), Items.CRAFTING_TABLE), count(player.getInventory(), Items.CHEST), handler.syncId, empty, handler.getCursorStack().isEmpty());
	}
	private record Snapshot(int logs, int planks, int table, int chest, int syncId, boolean emptyGrid, boolean emptyCursor) {}
	private int count(Minecraft client, Item item) { return count(client.player.getInventory(), item); }
	private int count(net.minecraft.world.Container inventory, Item item) {
		int count = 0;
		for (int i = 0; i < inventory.size(); i++) if (inventory.getStack(i).isOf(item)) count += inventory.getStack(i).getCount();
		return count;
	}
	private String inventory(Minecraft client) {
		return "logs=" + count(client, Items.OAK_LOG) + ", planks=" + count(client, Items.OAK_PLANKS)
			+ ", table=" + count(client, Items.CRAFTING_TABLE) + ", chest=" + count(client, Items.CHEST);
	}
	private void capture(Minecraft client, String name) throws Exception {
		try (NativeImage image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(output.resolve(name + ".png")); }
	}
	private void record(String text) { evidence.add(text); LOGGER.info("WORLD SMOKE {}", text); }
	private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
