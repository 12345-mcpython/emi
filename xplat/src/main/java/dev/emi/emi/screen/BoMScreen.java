package dev.emi.emi.screen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.emi.emi.EmiPort;
import dev.emi.emi.EmiRenderHelper;
import dev.emi.emi.EmiUtil;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiResolutionRecipe;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.recipe.handler.EmiRecipeHandler;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.bom.BoM;
import dev.emi.emi.bom.ChanceMaterialCost;
import dev.emi.emi.bom.CraftingPlan;
import dev.emi.emi.bom.CraftingPlans;
import dev.emi.emi.bom.CraftingTreeModel;
import dev.emi.emi.bom.CraftingTreeModel.Entry;
import dev.emi.emi.bom.FlatMaterialCost;
import dev.emi.emi.bom.MaterialTree;
import dev.emi.emi.config.EmiConfig;
import dev.emi.emi.input.EmiInput;
import dev.emi.emi.registry.EmiRecipeFiller;
import dev.emi.emi.runtime.EmiDrawContext;
import dev.emi.emi.runtime.EmiFavorites;
import dev.emi.emi.runtime.EmiHistory;
import dev.emi.emi.runtime.EmiPersistentData;
import dev.emi.emi.runtime.EmiScreenshotRecorder;
import dev.emi.emi.screen.tooltip.RecipeTooltipComponent;

/** A selected recipe plan, a shared material ledger, and a pannable dependency graph. */
public class BoMScreen extends Screen {
	public final AbstractContainerScreen<?> old;
	private final Screen returnScreen;
	private final MaterialTree groupTree;
	private final String groupName;
	private final Set<String> folded = new LinkedHashSet<>();
	private final List<Visual> visuals = new ArrayList<>();
	private final Map<Entry, Visual> visualByEntry = new IdentityHashMap<>();
	private final List<StatRow> stats = new ArrayList<>();
	private CraftingTreeModel model;
	private CraftingPlan boundPlan;
	private Object boundTree;
	private EmiPlayerInventory snapshot;
	private boolean useInventory, showStats = true;
	private int collapseMode, section, statScroll, planScroll, searchIndex = -1;
	private int leftWidth, rightWidth, top, lastMouseX, lastMouseY;
	private float zoom = 1;
	private double panX, panY, scrollAcc;
	private int graphWidth = 1, graphHeight = 1;
	private CraftingPlan namePlan;
	private Object nameTree;
	private EditBox search, name;
	private final Map<String, Button> toolbar = new LinkedHashMap<>();
	private Visual pressedNode;
	private boolean draggingGraph, movedGraph;
	private double dragDistance;
	private Bounds canvas = new Bounds(0, 0, 1, 1);
	private Component notice = Component.empty();
	private boolean firstLayout = true;
	private enum Section { NEEDED, AVAILABLE, CRAFTING, READY, RESULTS, LEFTOVERS, MACHINES }

	public BoMScreen(AbstractContainerScreen<?> old) {
		super(EmiPort.translatable("screen.emi.recipe_tree"));
		this.old = old;
		this.returnScreen = old;
		this.groupTree = null;
		this.groupName = null;
	}

	/** Group snapshot never replaces the active global tree or saved plan. */
	public BoMScreen(Screen previous, MaterialTree tree, String name) {
		super(EmiPort.translatable("screen.emi.recipe_tree"));
		this.old = previous instanceof AbstractContainerScreen<?> handled ? handled : EmiApi.getHandledScreen();
		this.returnScreen = previous;
		this.groupTree = java.util.Objects.requireNonNull(tree);
		this.groupName = name;
	}

	private MaterialTree tree() { return groupTree == null ? BoM.tree : groupTree; }
	private CraftingPlan plan() { return groupTree == null ? CraftingPlans.current() : null; }

	@Override
	public void init() {
		String query = search == null ? "" : search.getValue();
		String draftName = name == null ? "" : name.getValue();
		boolean samePlan = boundPlan == plan() && boundTree == tree();
		boolean sameNameTarget = namePlan == plan() && nameTree == tree();
		draggingGraph = movedGraph = false;
		pressedNode = null;
		toolbar.clear();
		leftWidth = Mth.clamp(width / 5, 72, 132);
		rightWidth = showStats ? Mth.clamp(width / 4, 102, 210) : 0;
		clearWidgets();
		search = new EditBox(font, leftWidth + 8, 5, Math.max(36, width - leftWidth - 16), 18, tr("search"));
		search.setMaxLength(128);
		search.setHint(tr("search"));
		search.setValue(query);
		search.setResponder(s -> searchIndex = -1);
		addRenderableWidget(search);
		String[] actions = {"fit", "inventory", "refresh", "fold", "stats", "sections", "save", "delete", "copy", "paste", "screenshot", "next"};
		int columns = Math.max(1, width / 56);
		int buttonWidth = width / columns;
		for (int i = 0; i < actions.length; i++) {
			String action = actions[i];
			Button button = EmiPort.newButton((i % columns) * buttonWidth + 2, 27 + (i / columns) * 21,
				buttonWidth - 4, 19, actionText(action), b -> action(action));
			addRenderableWidget(button);
			toolbar.put(action, button);
		}
		top = 30 + ((actions.length + columns - 1) / columns) * 21;
		canvas = new Bounds(leftWidth + 3, top, Math.max(1, width - leftWidth - rightWidth - 6), Math.max(1, height - top - 30));
		name = new EditBox(font, 4, top + 3, leftWidth - 8, 18, tr("plan_name"));
		name.setMaxLength(80);
		addRenderableWidget(name);
		recalculateTree();
		if (samePlan && sameNameTarget) name.setValue(draftName);
		else name.setValue(groupTree != null ? groupName : plan() == null ? "" : plan().name);
		name.setEditable(groupTree == null);
		namePlan = plan();
		nameTree = tree();
		if (firstLayout) {
			fit();
			firstLayout = false;
		}
	}

	private Component tr(String suffix, Object... args) {
		return EmiPort.translatable("emi.tree." + suffix, args);
	}

	private Component actionText(String action) {
		if (action.equals("inventory")) return tr(useInventory ? "inventory_on" : "inventory_off");
		if (action.equals("fold")) return tr("fold_" + collapseMode);
		return tr(action);
	}

	private void action(String action) {
		if (groupTree != null && Set.of("save", "delete", "paste", "copy", "next").contains(action)) return;
		switch (action) {
			case "fit" -> fit();
			case "inventory" -> {
				useInventory = !useInventory;
				snapshot = useInventory && minecraft.player != null ? EmiPlayerInventory.of(minecraft.player) : null;
				remember();
				recalculateTree();
				init();
			}
			case "refresh" -> {
				snapshot = useInventory && minecraft.player != null ? EmiPlayerInventory.of(minecraft.player) : null;
				recalculateTree();
			}
			case "fold" -> {
				collapseMode = (collapseMode + 1) % 3;
				folded.clear();
				if (model != null) for (int i = 1; i < model.entries.size(); i++) {
					Entry e = model.entries.get(i);
					if (!e.children.isEmpty() && (collapseMode == 2 || collapseMode == 1 && (e.needed == 0 || e.recipe != null && ready(e.recipe)))) folded.add(CraftingPlans.key(e.ingredient));
				}
				remember();
				init();
				fit();
			}
			case "stats" -> { showStats = !showStats; init(); fit(); }
			case "sections" -> { section = (section + 1) % (Section.values().length + 1); statScroll = 0; buildStats(); }
			case "save" -> {
				if (tree() == null) return;
				CraftingPlans.saveCurrent();
				boundPlan = plan();
				if (boundPlan == null) { notice = tr("plan_limit"); return; }
				if (!name.getValue().isBlank()) CraftingPlans.rename(name.getValue());
				remember();
				recalculateTree();
				notice = tr("saved");
			}
			case "delete" -> {
				int index = CraftingPlans.all().indexOf(plan());
				if (index >= 0) { CraftingPlans.remove(index); firstLayout = true; init(); }
			}
			case "copy" -> {
				remember();
				String json = CraftingPlans.exportCurrent();
				if (json != null && !json.isEmpty()) {
					// Temporary trees have UI state as well, without implicitly saving a plan.
					var data = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
					var collapsed = new com.google.gson.JsonArray();
					folded.forEach(collapsed::add);
					data.add("folded", collapsed);
					data.addProperty("use_inventory", useInventory);
					minecraft.keyboardHandler.setClipboard(data.toString());
					notice = tr("copied");
				}
			}
			case "paste" -> {
				if (CraftingPlans.importPlan(minecraft.keyboardHandler.getClipboard())) { firstLayout = true; init(); notice = tr("imported"); }
				else notice = tr("invalid_plan");
			}
			case "screenshot" -> screenshot();
			case "next" -> fillNext();
		}
	}

	private void remember() {
		CraftingPlan plan = plan();
		if (plan != null) {
			plan.folded.clear();
			plan.folded.addAll(folded);
			plan.useInventory = useInventory;
			CraftingPlans.capture();
			EmiPersistentData.save();
		}
	}

	public void recalculateTree() {
		CraftingPlan plan = plan();
		if (boundPlan != plan || boundTree != tree()) {
			folded.clear();
			if (plan != null) folded.addAll(plan.folded);
			useInventory = plan != null && plan.useInventory;
			boundPlan = plan;
			boundTree = tree();
			snapshot = useInventory && minecraft.player != null ? EmiPlayerInventory.of(minecraft.player) : null;
		}
		if (groupTree == null) BoM.craftingMode = useInventory;
		model = tree() == null ? null : new CraftingTreeModel(tree(), useInventory ? snapshot : null);
		if (name != null && (namePlan != plan || nameTree != tree())) {
			name.setValue(groupTree != null ? groupName : plan == null ? "" : plan.name);
			namePlan = plan;
			nameTree = tree();
		}
		layoutGraph();
		buildStats();
		toolbar.forEach((action, button) -> {
			button.setMessage(actionText(action));
			button.active = groupTree != null && Set.of("save", "delete", "paste", "copy", "next").contains(action) ? false : switch (action) {
				case "delete" -> plan != null;
				case "paste", "stats", "sections" -> true;
				default -> model != null;
			};
		});
	}

	private void layoutGraph() {
		visuals.clear();
		visualByEntry.clear();
		if (model == null || model.entries.isEmpty()) return;
		Set<Entry> visible = Collections.newSetFromMap(new IdentityHashMap<>());
		List<Entry> queue = new ArrayList<>();
		queue.add(model.entries.get(0));
		for (int i = 0; i < queue.size(); i++) {
			Entry e = queue.get(i);
			if (!visible.add(e)) continue;
			if (!folded.contains(CraftingPlans.key(e.ingredient))) queue.addAll(e.children);
		}
		Map<Integer, List<Visual>> rows = new LinkedHashMap<>();
		graphWidth = 1;
		graphHeight = 1;
		for (Entry e : model.entries) {
			if (!visible.contains(e)) continue;
			Visual v = new Visual(e);
			visuals.add(v);
			visualByEntry.put(e, v);
			rows.computeIfAbsent(e.depth, k -> new ArrayList<>()).add(v);
		}
		for (Map.Entry<Integer, List<Visual>> row : rows.entrySet()) {
			int rowWidth = row.getValue().stream().mapToInt(v -> v.width + 18).sum() - 18;
			int x = -rowWidth / 2;
			for (Visual v : row.getValue()) {
				v.x = x;
				v.y = row.getKey() * 70;
				x += v.width + 18;
				graphHeight = Math.max(graphHeight, v.y + 40);
			}
			graphWidth = Math.max(graphWidth, rowWidth);
		}
	}

	private void fit() {
		zoom = Mth.clamp(Math.min((canvas.width() - 24f) / Math.max(1, graphWidth), (canvas.height() - 24f) / Math.max(1, graphHeight)), 0.15f, 2f);
		panX = 0;
		panY = 12;
	}

	private void buildStats() {
		stats.clear();
		if (model == null) return;
		for (Section s : EnumSet.allOf(Section.class)) {
			if (section > 0 && section != s.ordinal() + 1) continue;
			stats.add(new StatRow(tr("section." + s.name().toLowerCase(Locale.ROOT)), null, null));
			int before = stats.size();
			switch (s) {
				case NEEDED -> { for (FlatMaterialCost cost : useInventory ? model.missing : model.required) addCost(cost); }
				case AVAILABLE -> { if (useInventory) for (FlatMaterialCost cost : model.available) addCost(cost); }
				case CRAFTING, READY -> {
					for (CraftingTreeModel.Step step : model.steps) {
						if ((s == Section.READY) != ready(step.recipe)) continue;
						EmiIngredient output = step.recipe.getOutputs().get(0);
						stats.add(new StatRow(EmiPort.literal("×" + step.batches + " " + label(output)), output, step.recipe));
					}
				}
				case RESULTS -> stats.add(new StatRow(amount(model.result, model.resultAmount), model.result, null));
				case LEFTOVERS -> { for (FlatMaterialCost cost : model.leftovers) addCost(cost); }
				case MACHINES -> {
					Map<Component, Long> machines = new LinkedHashMap<>();
					for (CraftingTreeModel.Step step : model.steps) machines.merge(step.recipe.getCategory().getName(), step.batches, BoMScreen::addSaturated);
					for (Map.Entry<Component, Long> entry : machines.entrySet()) stats.add(new StatRow(EmiPort.literal("×" + entry.getValue() + " " + entry.getKey().getString()), null, null));
				}
			}
			if (stats.size() == before) stats.add(new StatRow(tr("none"), null, null));
		}
		statScroll = Mth.clamp(statScroll, 0, Math.max(0, stats.size() - statRows()));
	}

	private static long addSaturated(long a, long b) {
		return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
	}

	private void addCost(FlatMaterialCost cost) {
		Component text = amount(cost.ingredient, cost.getEffectiveAmount());
		if (cost instanceof ChanceMaterialCost) text = EmiPort.literal("≈" + text.getString()).withStyle(ChatFormatting.GOLD);
		stats.add(new StatRow(text, cost.ingredient, null));
	}

	private Component amount(EmiIngredient ingredient, long count) {
		return EmiPort.literal(EmiRenderHelper.getAmountText(ingredient, count).getString() + " " + label(ingredient));
	}

	private String label(EmiIngredient ingredient) {
		return ingredient.getEmiStacks().isEmpty() ? "?" : ingredient.getEmiStacks().get(0).getName().getString();
	}

	/** Readiness describes materials, independently of whether this screen has that machine. */
	private boolean ready(EmiRecipe recipe) {
		if (snapshot == null || !useInventory) return recipe.getInputs().stream().allMatch(EmiIngredient::isEmpty);
		List<EmiStack> stacks = snapshot.inventory.values().stream().map(EmiStack::copy).toList();
		long[] used = new long[stacks.size()];
		List<EmiIngredient> inputs = new ArrayList<>(recipe.getInputs());
		inputs.sort(java.util.Comparator.comparingInt(i -> i.getEmiStacks().size()));
		for (EmiIngredient input : inputs) {
			if (input.isEmpty()) continue;
			long needed = input.getAmount();
			for (int i = 0; i < stacks.size() && needed > 0; i++) {
				EmiStack stock = stacks.get(i);
				if (input.getEmiStacks().stream().anyMatch(stock::isEqual)) {
					long take = Math.min(needed, Math.max(0, stock.getAmount() - used[i]));
					used[i] += take;
					needed -= take;
				}
			}
			if (needed > 0) return false;
		}
		return true;
	}

	private boolean canFill(EmiRecipe recipe) {
		return old != null && canFill(recipe, old);
	}

	private <T extends net.minecraft.world.inventory.AbstractContainerMenu> boolean canFill(EmiRecipe recipe, AbstractContainerScreen<T> screen) {
		var handler = EmiRecipeFiller.getFirstValidHandler(recipe, screen);
		return handler != null && handler.canCraft(recipe, new EmiCraftContext<>(screen, handler.getInventory(screen), EmiCraftContext.Type.FILL_BUTTON, EmiCraftContext.Destination.NONE, 1));
	}

	private void fillNext() {
		if (model == null || old == null) return;
		for (CraftingTreeModel.Step step : model.steps) {
			if (canFill(step.recipe) && EmiRecipeFiller.performFill(step.recipe, old, EmiCraftContext.Type.FILL_BUTTON, EmiCraftContext.Destination.NONE, 1)) return;
		}
		notice = tr("no_next");
	}

	@Override
	public void render(GuiGraphics raw, int mouseX, int mouseY, float delta) {
		EmiDrawContext context = EmiDrawContext.wrap(raw);
		context.fill(0, 0, width, height, 0xDD000000);
		this.renderMenuBackground(raw);
		lastMouseX = mouseX;
		lastMouseY = mouseY;
		context.fill(0, top, leftWidth, height - top - 24, 0xc020222a);
		context.drawText(tr("plans"), 5, 9, 0xffb6c7e8);
		renderPlans(context);
		raw.enableScissor(canvas.x(), canvas.y(), canvas.right(), canvas.bottom());
		context.push();
		context.translate((float) (canvas.x() + canvas.width() / 2.0 + panX), (float) (canvas.y() + panY));
		context.matrices().scale(zoom, zoom, 1);
		renderGraph(context, delta);
		context.pop();
		raw.disableScissor();
		if (model == null) context.drawCenteredText(tr("empty"), canvas.x() + canvas.width() / 2, canvas.y() + 36);
		if (showStats) renderStats(context, mouseX, mouseY, delta);
		if (tree() != null) {
			context.fill(canvas.x(), canvas.bottom() + 2, canvas.width(), 20, 0xcc20222a);
			context.drawCenteredText(tr("batches", tree().batches), canvas.x() + canvas.width() / 2, canvas.bottom() + 7);
		}
		context.drawText(EmiPort.literal(font.plainSubstrByWidth(notice.getString(), Math.max(1, width - 10))), 5, height - 10, 0xffb6c7e8);
		super.render(raw, mouseX, mouseY, delta);
		Visual hovered = hovered(mouseX, mouseY);
		if (hovered != null && !search.isFocused() && !name.isFocused()) drawEntryTooltip(context, hovered.entry, mouseX, mouseY);
		else if (showStats && mouseX >= width - rightWidth && mouseY >= top) {
			int index = statScroll + (mouseY - top - 4) / 22;
			if (index >= 0 && index < stats.size()) {
				StatRow row = stats.get(index);
				if (row.ingredient != null) EmiRenderHelper.drawTooltip(this, context, row.ingredient.getTooltip(), mouseX, mouseY);
			}
		}
	}

	private void renderPlans(EmiDrawContext context) {
		List<CraftingPlan> plans = groupTree == null ? CraftingPlans.all() : List.of();
		int visible = Math.max(0, (height - top - 50) / 23);
		planScroll = Mth.clamp(planScroll, 0, Math.max(0, plans.size() - visible));
		for (int i = planScroll; i < Math.min(plans.size(), planScroll + visible); i++) {
			int y = top + 27 + (i - planScroll) * 23;
			if (plans.get(i) == plan()) context.fill(2, y - 2, leftWidth - 4, 22, 0xff384966);
			context.drawText(EmiPort.literal(font.plainSubstrByWidth(plans.get(i).name, leftWidth - 10)), 5, y + 4);
		}
	}

	private void renderGraph(EmiDrawContext context, float delta) {
		if (model == null) return;
		for (CraftingTreeModel.Link link : model.links) {
			Visual parent = visualByEntry.get(link.parent), child = visualByEntry.get(link.child);
			if (parent == null || child == null || folded.contains(CraftingPlans.key(link.parent.ingredient))) continue;
			int x1 = parent.x + parent.width / 2, y1 = parent.y + 40, x2 = child.x + child.width / 2, y2 = child.y;
			int middle = y1 + (y2 - y1) / 2;
			int color = useInventory && child.entry.needed == 0 ? 0xff58b783 : 0xff6b7d9d;
			context.fill(x1, y1, 1, Math.max(1, middle - y1), color);
			context.fill(Math.min(x1, x2), middle, Math.abs(x2 - x1) + 1, 1, color);
			context.fill(x2, middle, 1, Math.max(1, y2 - middle), color);
		}
		String query = search == null ? "" : search.getValue().toLowerCase(Locale.ROOT);
		for (Visual v : visuals) {
			boolean match = !query.isBlank() && matches(v.entry, query);
			int border = match ? 0xffffcc55 : v.entry.cycle || v.entry.truncated ? 0xffdb8457 : useInventory && v.entry.needed == 0 ? 0xff58b783 : 0xff8299bd;
			context.fill(v.x - 1, v.y - 1, v.width + 2, 42, border);
			context.fill(v.x, v.y, v.width, 40, 0xff20242e);
			v.entry.ingredient.render(context.raw(), v.x + 4, v.y + 4, delta, ~(EmiIngredient.RENDER_AMOUNT | EmiIngredient.RENDER_REMAINDER));
			context.drawText(EmiPort.literal(font.plainSubstrByWidth(label(v.entry.ingredient), v.width - 28)), v.x + 24, v.y + 5);
			Component quantity = EmiRenderHelper.getAmountText(v.entry.ingredient, useInventory ? v.entry.needed : v.entry.total);
			if (v.entry.chance != 1) quantity = EmiPort.literal("≈" + quantity.getString());
			context.drawText(quantity, v.x + 24, v.y + 18, border);
			if (v.entry.recipe != null) {
				v.entry.recipe.getCategory().renderSimplified(context.raw(), v.x + 4, v.y + 22, delta);
			}
			if (!v.entry.children.isEmpty()) context.drawText(EmiPort.literal(folded.contains(CraftingPlans.key(v.entry.ingredient)) ? "+" : "−"), v.x + v.width - 9, v.y + 28, 0xffb6c7e8);
		}
	}

	private int statRows() { return Math.max(1, (height - top - 28) / 22); }

	private void renderStats(EmiDrawContext context, int mx, int my, float delta) {
		int x = width - rightWidth;
		context.fill(x, top, rightWidth, height - top - 24, 0xe020222a);
		for (int i = statScroll; i < Math.min(stats.size(), statScroll + statRows()); i++) {
			StatRow row = stats.get(i);
			int y = top + 4 + (i - statScroll) * 22;
			if (row.ingredient != null) {
				row.ingredient.render(context.raw(), x + 4, y, delta, ~(EmiIngredient.RENDER_AMOUNT | EmiIngredient.RENDER_REMAINDER));
				context.drawText(EmiPort.literal(font.plainSubstrByWidth(row.text.getString(), rightWidth - 28)), x + 24, y + 5);
			} else context.drawText(EmiPort.literal(font.plainSubstrByWidth(row.text.getString(), rightWidth - 10)), x + 5, y + 5, 0xffb6c7e8);
		}
	}

	private void drawEntryTooltip(EmiDrawContext context, Entry e, int mx, int my) {
		List<ClientTooltipComponent> tooltip = new ArrayList<>(e.ingredient.getTooltip());
		tooltip.add(ClientTooltipComponent.create(tr("quantity", e.total, e.needed).getVisualOrderText()));
		tooltip.add(ClientTooltipComponent.create(tr("node_help").getVisualOrderText()));
		if (e.cycle) tooltip.add(ClientTooltipComponent.create(tr("cycle").getVisualOrderText()));
		if (e.truncated) tooltip.add(ClientTooltipComponent.create(tr("truncated").getVisualOrderText()));
		if (e.catalyst) tooltip.add(ClientTooltipComponent.create(tr("catalyst").getVisualOrderText()));
		if (e.recipe != null) tooltip.add(new RecipeTooltipComponent(e.recipe));
		EmiRenderHelper.drawTooltip(this, context, tooltip, mx, my);
	}

	private Visual hovered(double mx, double my) {
		if (!canvas.contains((int) mx, (int) my)) return null;
		double x = (mx - canvas.x() - canvas.width() / 2.0 - panX) / zoom;
		double y = (my - canvas.y() - panY) / zoom;
		for (Visual v : visuals) if (x >= v.x && x < v.x + v.width && y >= v.y && y < v.y + 40) return v;
		return null;
	}

	private boolean matches(Entry e, String query) {
		return label(e.ingredient).toLowerCase(Locale.ROOT).contains(query)
			|| e.ingredient.getEmiStacks().stream().anyMatch(s -> s.getId().toString().toLowerCase(Locale.ROOT).contains(query));
	}

	private void find(boolean backwards) {
		if (model == null || search.getValue().isBlank()) return;
		List<Entry> matches = model.entries.stream().filter(e -> matches(e, search.getValue().toLowerCase(Locale.ROOT))).toList();
		if (matches.isEmpty()) { notice = tr("no_matches"); return; }
		searchIndex = searchIndex < 0 ? (backwards ? matches.size() - 1 : 0) : Math.floorMod(searchIndex + (backwards ? -1 : 1), matches.size());
		Entry target = matches.get(searchIndex);
		// Reveal every ancestor without altering any recipe or calculated quantity.
		Set<Entry> path = Collections.newSetFromMap(new IdentityHashMap<>());
		path.add(target);
		boolean changed;
		do {
			changed = false;
			for (CraftingTreeModel.Link link : model.links) if (path.contains(link.child) && path.add(link.parent)) changed = true;
		} while (changed);
		for (Entry e : path) folded.remove(CraftingPlans.key(e.ingredient));
		layoutGraph();
		Visual v = visualByEntry.get(target);
		if (v != null) { panX = -(v.x + v.width / 2.0) * zoom; panY = canvas.height() / 2.0 - (v.y + 20) * zoom; }
		remember();
	}

	private void recipes(EmiIngredient ingredient, EmiRecipe recipe, boolean uses) {
		remember();
		if (uses) EmiApi.displayUses(ingredient);
		else {
			EmiApi.displayRecipes(ingredient);
			if (groupTree == null && minecraft.screen instanceof RecipeScreen rs) {
				rs.setResolutionTarget(ingredient);
				rs.init(minecraft, rs.width, rs.height);
				if (recipe != null) EmiApi.focusRecipe(recipe);
			}
		}
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (super.mouseClicked(mouseX, mouseY, button)) return true;
		search.setFocused(false);
		name.setFocused(false);
		if (EmiConfig.back.matchesMouse(button)) { remember(); EmiHistory.pop(); return true; }
		Visual boundHover = hovered(mouseX, mouseY);
		if (button > 2 && boundHover != null && stackAction(boundHover, bind -> bind.matchesMouse(button))) return true;
		if (groupTree == null && mouseX < leftWidth && mouseY >= top + 27 && mouseY < height - 24) {
			int index = planScroll + ((int) mouseY - top - 27) / 23;
			if (index < CraftingPlans.all().size()) { remember(); CraftingPlans.open(index); firstLayout = true; init(); }
			return true;
		}
		if (showStats && mouseX >= width - rightWidth && mouseY >= top + 4 && mouseY < height - 24) {
			int index = statScroll + ((int) mouseY - top - 4) / 22;
			if (index < stats.size() && stats.get(index).ingredient != null) {
				StatRow row = stats.get(index);
				if (button == 0) { search.setValue(label(row.ingredient)); find(EmiInput.isShiftDown()); }
				else if (button == 1) recipes(row.ingredient, row.recipe, false);
			}
			return true;
		}
		Visual v = hovered(mouseX, mouseY);
		if (canvas.contains((int) mouseX, (int) mouseY) && (button == 0 || button == 2)) {
			pressedNode = v;
			draggingGraph = true;
			movedGraph = false;
			dragDistance = 0;
			return true;
		}
		if (v != null) {
			if (groupTree == null && button == 1 && EmiInput.isShiftDown() && v.entry != model.entries.get(0)) {
				BoM.addResolution(v.entry.ingredient, null);
				recalculateTree();
				remember();
			} else if (button == 1) {
				String key = CraftingPlans.key(v.entry.ingredient);
				if (!folded.remove(key)) folded.add(key);
				layoutGraph(); remember();
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double amount) {
		scrollAcc += amount;
		int steps = (int) scrollAcc;
		scrollAcc -= steps;
		if (steps == 0) return true;
		if (mouseX < leftWidth && mouseY >= top) { planScroll -= steps; return true; }
		if (showStats && mouseX >= width - rightWidth && mouseY >= top) { statScroll = Mth.clamp(statScroll - steps, 0, Math.max(0, stats.size() - statRows())); return true; }
		if (groupTree == null && tree() != null && (mouseY > canvas.bottom() && mouseY < height - 10 || EmiInput.isControlDown() && hovered(mouseX, mouseY) != null && hovered(mouseX, mouseY).entry == model.entries.get(0))) {
			long change = steps * (EmiInput.isShiftDown() ? 16L : 1L);
			tree().batches = change > 0 ? addSaturated(tree().batches, change) : Math.max(1, tree().batches + change);
			recalculateTree(); remember(); return true;
		}
		Visual v = hovered(mouseX, mouseY);
		if (groupTree == null && v != null && EmiInput.isShiftDown() && v.entry.ingredient.getEmiStacks().size() > 1) {
			List<EmiStack> choices = v.entry.ingredient.getEmiStacks();
			EmiRecipe current = tree().getRecipe(v.entry.ingredient);
			int index = current instanceof EmiResolutionRecipe r ? choices.indexOf(r.stack) : 0;
			BoM.addResolution(v.entry.ingredient, new EmiResolutionRecipe(v.entry.ingredient, choices.get(Math.floorMod(index + steps, choices.size()))));
			recalculateTree(); remember(); return true;
		}
		if (!canvas.contains((int) mouseX, (int) mouseY)) return false;
		float oldZoom = zoom;
		zoom = Mth.clamp(zoom * (float) Math.pow(1.2, steps), 0.15f, 4f);
		double x = mouseX - canvas.x() - canvas.width() / 2.0;
		double y = mouseY - canvas.y();
		panX = x - (x - panX) * zoom / oldZoom;
		panY = y - (y - panY) * zoom / oldZoom;
		return true;
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
		if (draggingGraph && (button == 0 || button == 2)) {
			dragDistance += Math.abs(dx) + Math.abs(dy);
			movedGraph |= dragDistance > 3;
			if (movedGraph) { panX += dx; panY += dy; }
			return true;
		}
		return super.mouseDragged(mx, my, button, dx, dy);
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		if (draggingGraph && (button == 0 || button == 2)) {
			Visual clicked = pressedNode;
			boolean activate = button == 0 && !movedGraph && clicked != null && hovered(mx, my) == clicked;
			draggingGraph = false;
			pressedNode = null;
			if (activate) {
				if (groupTree == null && EmiInput.isShiftDown()) {
					EmiRecipe recipe = EmiUtil.getRecipeResolution(clicked.entry.ingredient, EmiPlayerInventory.of(minecraft.player));
					if (recipe != null) { BoM.addResolution(clicked.entry.ingredient, recipe); recalculateTree(); remember(); }
				} else if (!stackAction(clicked, bind -> bind.matchesMouse(button))) recipes(clicked.entry.ingredient, clicked.entry.recipe, false);
			}
			return true;
		}
		return super.mouseReleased(mx, my, button);
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
			if (search.isFocused() || name.isFocused()) { search.setFocused(false); name.setFocused(false); return true; }
			onClose(); return true;
		}
		if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
			if (name.isFocused()) action("save"); else find(EmiInput.isShiftDown());
			return true;
		}
		if (super.keyPressed(keyCode, scanCode, modifiers)) return true;
		if (search.isFocused() || name.isFocused()) return false;
		if (EmiInput.isControlDown() && keyCode == GLFW.GLFW_KEY_F) { setFocused(search); search.setFocused(true); return true; }
		if (EmiInput.isControlDown() && keyCode == GLFW.GLFW_KEY_S) { action("save"); return true; }
		if (EmiConfig.back.matchesKey(keyCode, scanCode)) { remember(); EmiHistory.pop(); return true; }
		if (minecraft.options.keyInventory.matches(keyCode, scanCode)) { onClose(); return true; }
		Visual v = hovered(lastMouseX, lastMouseY);
		return v != null && stackAction(v, bind -> bind.matchesKey(keyCode, scanCode));
	}

	private boolean stackAction(Visual v, java.util.function.Predicate<dev.emi.emi.input.EmiBind> matches) {
		if (matches.test(EmiConfig.favorite)) { EmiFavorites.addFavorite(v.entry.ingredient, v.entry.recipe); return true; }
		if (matches.test(EmiConfig.viewUses)) { recipes(v.entry.ingredient, v.entry.recipe, true); return true; }
		if (matches.test(EmiConfig.viewRecipes)) { recipes(v.entry.ingredient, v.entry.recipe, false); return true; }
		return false;
	}

	private void screenshot() {
		if (model == null || visuals.isEmpty()) return;
		int imageWidth = graphWidth + (showStats ? 240 : 0) + 32;
		int imageHeight = Math.max(graphHeight + 55, showStats ? stats.size() * 22 + 32 : 1);
		int scale = EmiConfig.recipeScreenshotScale < 1 ? EmiPort.getGuiScale(minecraft) : EmiConfig.recipeScreenshotScale;
		// Guard before allocating GPU/CPU storage. Oversized trees remain fully editable.
		long pixels = (long) imageWidth * imageHeight * scale * scale;
		if (imageWidth * (long) scale > 8192 || imageHeight * (long) scale > 8192 || pixels > 16_777_216L) { notice = tr("image_too_large"); return; }
		EmiScreenshotRecorder.saveScreenshot("emi/trees/plan", imageWidth, imageHeight, () -> {
			GuiGraphics raw = new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
			EmiDrawContext context = EmiDrawContext.wrap(raw);
			context.fill(0, 0, imageWidth, imageHeight, 0xff171b24);
			context.drawText(title, 12, 8);
			context.push();
			context.translate(graphWidth / 2f + 16, 30);
			renderGraph(context, 0);
			context.pop();
			if (showStats) for (int i = 0; i < stats.size(); i++) {
				StatRow row = stats.get(i);
				int x = graphWidth + 26, y = 24 + i * 22;
				if (row.ingredient != null) row.ingredient.render(raw, x, y, 0, ~(EmiIngredient.RENDER_AMOUNT | EmiIngredient.RENDER_REMAINDER));
				context.drawText(EmiPort.literal(font.plainSubstrByWidth(row.text.getString(), 202)), x + (row.ingredient == null ? 0 : 22), y + 4);
			}
			raw.flush();
		});
	}

	@Override public boolean isPauseScreen() { return false; }
	@Override public void onClose() { remember(); minecraft.setScreen(returnScreen); }

	private class Visual {
		final Entry entry;
		final int width;
		int x, y;
		Visual(Entry entry) {
			this.entry = entry;
			width = Math.max(90, Math.min(170, font.width(label(entry.ingredient)) + 32));
		}
	}
	private record StatRow(Component text, EmiIngredient ingredient, EmiRecipe recipe) {}
}
