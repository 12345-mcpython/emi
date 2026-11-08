package dev.emi.emi.runtime;

import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import com.google.common.collect.Lists;

import dev.emi.emi.EmiPort;
import dev.emi.emi.api.EmiInitRegistry;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.bom.BoM;
import dev.emi.emi.mixinsupport.EmiMixinTransformation;
import dev.emi.emi.platform.EmiAgnos;
import dev.emi.emi.registry.EmiComparisonDefaults;
import dev.emi.emi.registry.EmiDragDropHandlers;
import dev.emi.emi.registry.EmiExclusionAreas;
import dev.emi.emi.registry.EmiIngredientSerializers;
import dev.emi.emi.registry.EmiInitRegistryImpl;
import dev.emi.emi.registry.EmiPluginContainer;
import dev.emi.emi.registry.EmiRecipeFiller;
import dev.emi.emi.registry.EmiRecipes;
import dev.emi.emi.registry.EmiRegistryImpl;
import dev.emi.emi.registry.EmiStackList;
import dev.emi.emi.registry.EmiStackProviders;
import dev.emi.emi.registry.EmiTags;
import dev.emi.emi.screen.EmiScreenBase;
import dev.emi.emi.screen.EmiScreenManager;
import dev.emi.emi.search.EmiSearch;

public class EmiReloadManager {
	private static volatile int loadedResourcesMask = 0;
	private static volatile boolean synchronizedReloadPending;
	private static volatile boolean resourcesReady;
	private static volatile boolean clear = false, restart = false;
	// 0 - empty, 1 - reloading, 2 - loaded, -1 - error
	private static volatile int status = 0;
	private static Thread thread;
	public static volatile Component reloadStep = EmiPort.literal("");
	public static volatile long reloadWorry = Long.MAX_VALUE;

	static {
		EmiMixinTransformation.preach();
	}

	public static void reloadTags() {
		resourcesSynchronized(1);
	}

	public static void reloadRecipes() {
		resourcesSynchronized(2);
	}

	/**
	 * 1.21.2+ clients never receive a recipe table, so recipes can never synchronize on
	 * multiplayer. Mark them as handled so the reload can run with whatever data exists.
	 */
	public static void recipesUnavailable() {
		resourcesSynchronized(2);
	}

	private static synchronized void resourcesSynchronized(int resource) {
		loadedResourcesMask |= resource;
		resourcesReady = loadedResourcesMask == 3;
		if (resourcesReady) {
			loadedResourcesMask = 0;
			synchronizedReloadPending = true;
			EmiLog.info("Recipes and tags synchronized; EMI initialization queued for the next ready client tick");
		} else {
			EmiLog.info(resource == 1 ? "Tags synchronized, waiting for recipes to reload EMI..."
				: "Recipes synchronized, waiting for tags to reload EMI...");
		}
	}

	/** Queue a rebuild after a resource reload, but never before recipes and tags are ready. */
	public static synchronized void queueReload() {
		if (resourcesReady) {
			synchronizedReloadPending = true;
		}
	}

	/** Packet/event callbacks queue work; start only after the client world and player exist. */
	public static synchronized void tick() {
		if (!synchronizedReloadPending || !resourcesReady || loadedResourcesMask != 0) return;
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.player == null) return;
		synchronizedReloadPending = false;
		EmiLog.info("Initializing EMI on world entry without waiting for an inventory screen");
		reload();
	}

	public static void clear() {
		synchronized (EmiReloadManager.class) {
			loadedResourcesMask = 0;
			synchronizedReloadPending = false;
			resourcesReady = false;
			clear = true;
			status = 0;
			reloadWorry = Long.MAX_VALUE;
			if (thread != null && thread.isAlive()) {
				restart = true;
			} else {
				thread = new Thread(new ReloadWorker());
				thread.setDaemon(true);
				thread.start();
			}
		}
	}
	
	public static void reload() {
		synchronized (EmiReloadManager.class) {
			// A direct reload (including the world-entry tick) consumes any queued
			// request; leaving it set would start a second index build next tick.
			synchronizedReloadPending = false;
			step(EmiPort.literal("Starting Reload"));
			status = 1;
			if (thread != null && thread.isAlive()) {
				restart = true;
			} else {
				clear = false;
				thread = new Thread(new ReloadWorker());
				thread.setDaemon(false);
				thread.start();
			}
		}
	}

	public static void step(Component text) {
		step(text, 5_000);
	}

	public static void step(Component text, long worry) {
		EmiLog.info(text.getString());
		reloadStep = text;
		reloadWorry = System.currentTimeMillis() + worry;
	}

	public static boolean isLoaded() {
		return status == 2 && (thread == null || !thread.isAlive());
	}

	public static int getStatus() {
		return status;
	}
	
	private static class ReloadWorker implements Runnable {

		@Override
		public void run() {
			int retries = 3;
			outer:
			do {
				try {
					if (!clear) {
						EmiLog.info("Starting EMI reload...");
					}
					long reloadStart = System.currentTimeMillis();
					restart = false;
					step(EmiPort.literal("Clearing data"));
					EmiRecipes.clear();
					EmiStackList.clear();
					EmiIngredientSerializers.clear();
					EmiExclusionAreas.clear();
					EmiDragDropHandlers.clear();
					EmiStackProviders.clear();
					EmiRecipeFiller.clear();
					EmiHidden.clear();
					EmiTags.ADAPTERS_BY_CLASS.map().clear();
					EmiTags.ADAPTERS_BY_REGISTRY.clear();
					EmiScreenBase.clearScreenBoundsProviders();
					if (clear) {
						clear = false;
						continue;
					}
					Minecraft client = Minecraft.getInstance();
					if (client.level == null) {
						EmiReloadLog.warn("World is null");
						status = 0;
						break;
					// On a server there is no integrated recipe manager, but the client recipe book still
					// provides the synchronized displays, so reload from those instead of bailing out.
					} else if (!ProxyRecipeManager.isAvailable() && !DisplayRecipes.isUsable() && LocalRecipes.get().isEmpty()) {
						EmiReloadLog.warn("Recipe Manager is null");
						status = 0;
						break;
					}
					List<EmiPluginContainer> plugins = Lists.newArrayList();
					plugins.addAll(EmiAgnos.getPlugins().stream()
						.sorted((a, b) -> Integer.compare(entrypointPriority(a), entrypointPriority(b))).toList());
					
					EmiInitRegistry initRegistry = new EmiInitRegistryImpl();
					for (EmiPluginContainer container : plugins) {
						step(EmiPort.literal("Initializing plugin from " + container.id()), 5_000);
						long start = System.currentTimeMillis();
						try {
							container.plugin().initialize(initRegistry);
						} catch (Throwable e) {
							EmiReloadLog.warn("Exception initializing plugin provided by " + container.id(), e);
							if (restart) {
								continue outer;
							}
							continue;
						}
						EmiLog.info("Initialized plugin from " + container.id() + " in " + (System.currentTimeMillis() - start) + "ms");
					}
					EmiHidden.reload();

					step(EmiPort.literal("Processing tags"));
					EmiTags.reload();

					step(EmiPort.literal("Constructing index"));
					EmiComparisonDefaults.comparisons = new HashMap<>();
					EmiStackList.reload();
					if (restart) {
						continue;
					}
					EmiRegistry registry = new EmiRegistryImpl();
					
					for (EmiPluginContainer container : plugins) {
						step(EmiPort.literal("Loading plugin from " + container.id()), 10_000);
						long start = System.currentTimeMillis();
						try {
							container.plugin().register(registry);
						} catch (Throwable e) {
							EmiReloadLog.warn("Exception loading plugin provided by " + container.id(), e);
							if (restart) {
								continue outer;
							}
							continue;
						}
						EmiLog.info("Reloaded plugin from " + container.id() + " in " + (System.currentTimeMillis() - start) + "ms");
						if (restart) {
							continue outer;
						}
					}
					if (restart) {
						continue;
					}
					step(EmiPort.literal("Baking index"));
					EmiStackList.bake();
					step(EmiPort.literal("Registering late recipes"), 10_000);
					Consumer<EmiRecipe> registerLateRecipe = registry::addRecipe;
					for (Consumer<Consumer<EmiRecipe>> consumer : EmiRecipes.lateRecipes) {
						try {
							consumer.accept(registerLateRecipe);
						} catch (Exception e) {
							EmiReloadLog.warn("Exception loading late recipes for plugins:", e);
							if (restart) {
								continue outer;
							}
						}
					}
					step(EmiPort.literal("Baking recipes"), 15_000);
					EmiRecipes.bake();
					BoM.reload();
					EmiPersistentData.load();
					step(EmiPort.literal("Baking search"), 15_000);
					EmiSearch.bake();
					step(EmiPort.literal("Finishing up"));
					EmiScreenManager.search.update();
					EmiScreenManager.forceRecalculate();
					EmiReloadLog.bake();
					EmiLog.info("Reloaded EMI in " + (System.currentTimeMillis() - reloadStart) + "ms");
					status = 2;
				} catch (Throwable e) {
					EmiReloadLog.warn("Critical error occured during reload:", e);
					status = -1;
					if (retries-- > 0) {
						restart = true;
					}
				}
			} while (restart);
			thread = null;
		}

		private final static int entrypointPriority(EmiPluginContainer container) {
			return container.id().equals("emi") ? 0 : 1;
		}
	}
}
