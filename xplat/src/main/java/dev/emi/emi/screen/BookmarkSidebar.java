package dev.emi.emi.screen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import dev.emi.emi.EmiPort;
import dev.emi.emi.EmiRenderHelper;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.FluidEmiStack;
import dev.emi.emi.bom.CraftingTreeModel;
import dev.emi.emi.bom.FlatMaterialCost;
import dev.emi.emi.bom.MaterialTree;
import dev.emi.emi.input.EmiInput;
import dev.emi.emi.input.EmiBind;
import dev.emi.emi.config.EmiConfig;
import dev.emi.emi.config.BookmarkViewMode;
import dev.emi.emi.runtime.BookmarkCrafting;
import dev.emi.emi.runtime.BookmarkGroupActions;
import dev.emi.emi.runtime.BookmarkMaterialTransfer;
import dev.emi.emi.runtime.EmiBookmarkGroups;
import dev.emi.emi.runtime.EmiBookmarkGroups.Group;
import dev.emi.emi.runtime.EmiBookmarkGroups.Row;
import dev.emi.emi.runtime.EmiDrawContext;
import dev.emi.emi.runtime.EmiFavorite;
import dev.emi.emi.runtime.EmiFavorites;
import dev.emi.emi.screen.EmiScreenManager.ScreenSpace;
import dev.emi.emi.screen.EmiScreenManager.SidebarPanel;
import dev.emi.emi.screen.tooltip.BookmarkChainTooltipComponent;

/** The bookmark grid lives in the native favorites sidebar, including its row gutter. */
public final class BookmarkSidebar {
    private final SidebarPanel panel;
    private final List<Cell> cells = new ArrayList<>();
    private final List<Group> rows = new ArrayList<>();
    private List<EmiFavorite.Synthetic> syntheticFavorites = List.of();
    private long revision = -1;
    private Object inventory, geometry;
    private int pages = 1;
    private Group pressedGroup;
    private Cell pressedCell;
    private int startRow = -1, endRow = -1, pressButton;
    private boolean moved, movingGroup;
    private long pressedAt, edgeSince;
    private int edgeDirection;
    private Group chainGroup;
    private long chainRevision = -1;
    private Object chainInventory;
    private List<BookmarkChainTooltipComponent.Entry> chainOutputs = List.of();
    private List<BookmarkChainTooltipComponent.Entry> chainRequired = List.of();
    private List<BookmarkChainTooltipComponent.Entry> chainMissing = List.of();
    private final List<DragCell> groupPreview = new ArrayList<>();
    private record DragCell(EmiFavorite favorite, int dx, int dy) {}
    private static final long DRAG_THRESHOLD_MS = 250;
    private static final long PAGE_SCROLL_DELAY_MS = 350;

    public BookmarkSidebar(SidebarPanel panel) { this.panel = panel; }

    public void invalidate() { revision = -1; }

    private void layout() {
        ScreenSpace space = panel.space;
        long current = EmiBookmarkGroups.revision();
        if (space == null || space.th < 1 || space.tw < 1) return;
        if (revision == current && inventory == EmiScreenManager.lastPlayerInventory && geometry == space
                && sameSyntheticFavorites()) return;
        revision = current;
        inventory = EmiScreenManager.lastPlayerInventory;
        geometry = space;
        syntheticFavorites = List.copyOf(EmiFavorites.syntheticFavorites);
        cells.clear();
        rows.clear();
        int row = 0, col = 0;
        for (Group group : EmiBookmarkGroups.groups()) {
            List<Row> contents = EmiBookmarkGroups.rows(group, EmiScreenManager.lastPlayerInventory);
            if (contents.isEmpty()) continue;
            if (col > 0) { row++; col = 0; }
            EmiFavorite previous = null;
            for (Row item : contents) {
                if (group.viewMode == BookmarkViewMode.TODO && previous != null && previous != item.anchor && col > 0) { row++; col = 0; }
                int attempts = 0;
                while (capacity(space, row) == 0 && attempts++ <= space.th) row++;
                if (attempts > space.th) break;
                if (col >= capacity(space, row)) {
                    if (group.collapsed) break;
                    row++; col = group.viewMode == BookmarkViewMode.TODO && item.input && capacity(space, row) > 1 ? 1 : 0;
                }
                attempts = 0;
                while (capacity(space, row) == 0 && attempts++ <= space.th) row++;
                if (attempts > space.th) break;
                while (rows.size() <= row) rows.add(null);
                rows.set(row, group);
                cells.add(new Cell(item, row, col++));
                previous = item.anchor;
            }
            if (col > 0) { row++; col = 0; }
        }
        // Keep the native crafting-tree progress entries after persisted bookmarks.
        // They retain their own render/tooltip/crafting behavior and are never group members.
        for (EmiFavorite.Synthetic favorite : syntheticFavorites) {
            if (col >= space.getWidth(Math.floorMod(row, space.th))) { row++; col = 0; }
            int attempts = 0;
            while (space.getWidth(Math.floorMod(row, space.th)) == 0 && attempts++ <= space.th) row++;
            if (attempts > space.th) break;
            while (rows.size() <= row) rows.add(null);
            cells.add(new Cell(favorite, row, col++));
        }
        pages = Math.max(1, (rows.size() + space.th - 1) / space.th);
        panel.page = Math.floorMod(panel.page, pages);
    }

    private boolean sameSyntheticFavorites() {
        if (syntheticFavorites.size() != EmiFavorites.syntheticFavorites.size()) return false;
        for (int i = 0; i < syntheticFavorites.size(); i++)
            if (syntheticFavorites.get(i) != EmiFavorites.syntheticFavorites.get(i)) return false;
        return true;
    }

    private int capacity(ScreenSpace space, int row) {
        int width = space.getWidth(Math.floorMod(row, space.th));
        return width <= 0 ? 0 : Math.max(1, width - 1);
    }

    public int pages() { layout(); return pages; }
    public void scroll(int delta) { layout(); panel.page = Math.floorMod(panel.page + delta, pages); }
    private int baseRow() { return panel.page * panel.space.th; }
    private int x(Cell cell) {
        int local = Math.floorMod(cell.row, panel.space.th);
        return panel.space.getX(0, local) + (cell.item != null && panel.space.getWidth(local) > 1 ? 8 : 0) + cell.column * 18;
    }
    private int y(Cell cell) { return panel.space.ty + Math.floorMod(cell.row, panel.space.th) * 18; }
    private int absoluteRow(int my) { return baseRow() + (my - panel.space.ty) / 18; }
    private boolean header(int mx, int my) {
        ScreenSpace s = panel.space;
        return panel.header && my >= s.ty - 18 && my < s.ty && mx >= s.tx + 36 && mx < s.tx + s.tw * 18 - 16;
    }
    private boolean gutter(int mx, int my) {
        ScreenSpace s = panel.space;
        if (!s.contains(mx, my)) return false;
        int local = (my - s.ty) / 18;
        int row = baseRow() + local;
        // Native progress entries use the entire row, without a grouping gutter.
        if (row < rows.size() && rows.get(row) == null) return false;
        int left = s.getX(0, local);
        return s.getWidth(local) > 1 && mx >= left && mx < left + 8;
    }
    private Group groupAt(int mx, int my) {
        layout();
        if (header(mx, my)) return EmiBookmarkGroups.defaultGroup();
        if (!panel.space.contains(mx, my)) return null;
        int row = absoluteRow(my);
        return row >= 0 && row < rows.size() ? rows.get(row) : null;
    }
    private Cell cellAt(int mx, int my) {
        layout();
        for (Cell cell : cells) if (cell.row >= baseRow() && cell.row < baseRow() + panel.space.th
            && mx >= x(cell) && mx < x(cell) + 18 && my >= y(cell) && my < y(cell) + 18) return cell;
        return null;
    }
    public EmiFavorite hovered(int mx, int my) { Cell cell = cellAt(mx, my); return cell == null ? null : cell.favorite; }

    public void render(EmiDrawContext context, int mx, int my, float delta) {
        layout();
        updateDrag(mx, my);
        ScreenSpace space = panel.space;
        int first = baseRow(), last = first + space.th;
        for (int row = first; row < Math.min(last, rows.size()); row++) {
            Group group = rows.get(row);
            if (group == null) continue;
            int local = row - first, left = space.getX(0, local), top = space.ty + local * 18;
            int color = group.crafting ? 0xff45a967 : 0xff999999;
            if (group.id.equals("0")) continue;
            if (space.getWidth(local) > 1) {
                context.fill(left + 1, top, 2, 18, color);
                if (row == 0 || rows.get(row - 1) != group || row == first) context.fill(left + 1, top, 6, 2, color);
                if (row + 1 == rows.size() || rows.get(row + 1) != group || row + 1 == last) context.fill(left + 1, top + 16, 6, 2, color);
                if (!movingGroup && startRow >= 0 && row >= Math.min(startRow, endRow) && row <= Math.max(startRow, endRow))
                    context.fill(left, top, space.getWidth(local) * 18, 18, 0x4445da75);
            }
        }
        Set<EmiFavorite> batchLabels = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Cell cell : cells) {
            if (cell.row < first || cell.row >= last) continue;
            int cx = x(cell), cy = y(cell);
            if (mx >= cx && mx < cx + 18 && my >= cy && my < cy + 18)
                EmiRenderHelper.drawSlotHightlight(context, cx, cy, 18, 18, 0);
            cell.favorite.render(context.raw(), cx + 1, cy + 1, delta, -1);
            if (cell.item != null && !cell.item.input && cell.item.recipe != null) {
                if (batchLabels.add(cell.item.anchor)) {
                    context.push();
                    context.matrices().translate(cx, cy, 250);
                    context.matrices().scale(0.5f, 0.5f, 1);
                    context.drawText(EmiPort.literal("×" + cell.item.batches), 0, 0, 0xffb0b0b0);
                    context.pop();
                }
                boolean boxed = cell.item.group.collapsed || cell.item.recipe.getId() != null && cell.item.group.collapsedRecipes.contains(cell.item.recipe.getId().toString());
                if (boxed) {
                    int color = cell.item.group.collapsed ? 0xff45a967 : 0xffad76d4;
                    context.fill(cx, cy, 18, 1, color); context.fill(cx, cy + 17, 18, 1, color);
                    context.fill(cx, cy, 1, 18, color); context.fill(cx + 17, cy, 1, 18, color);
                }
            }
        }
        if (movingGroup && pressedGroup != null && !groupPreview.isEmpty()) {
            context.push();
            context.matrices().translate(0, 0, 400);
            int color = pressedGroup.crafting ? 0xff45a967 : 0xff999999;
            for (DragCell ghost : groupPreview)
                ghost.favorite.render(context.raw(), mx + ghost.dx + 1, my + ghost.dy + 1, delta,
                    EmiIngredient.RENDER_INGREDIENT | EmiIngredient.RENDER_ICON);
            int top = groupPreview.stream().mapToInt(DragCell::dy).min().orElse(0);
            int bottom = groupPreview.stream().mapToInt(DragCell::dy).max().orElse(0) + 18;
            int left = groupPreview.stream().mapToInt(DragCell::dx).min().orElse(0) - 7;
            context.fill(mx + left, my + top, 2, bottom - top, color);
            context.fill(mx + left, my + top, 6, 2, color);
            context.fill(mx + left, my + bottom - 2, 6, 2, color);
            context.pop();
        }
    }

    public List<ClientTooltipComponent> tooltip(int mx, int my) {
        layout();
        if (!header(mx, my) && !gutter(mx, my)) return List.of();
        Group group = groupAt(mx, my);
        if (group == null) return List.of(ClientTooltipComponent.create(EmiPort.translatable("emi.bookmarks.drag_group").getVisualOrderText()));
        ensureChain(group);
        List<ClientTooltipComponent> result = new ArrayList<>();
        result.add(ClientTooltipComponent.create(EmiPort.translatable("emi.bookmarks.chain.title")
            .withStyle(ChatFormatting.GOLD).getVisualOrderText()));
        if (!chainOutputs.isEmpty()) {
            result.add(chainLabel("outputs"));
            result.add(new BookmarkChainTooltipComponent(chainOutputs, false));
        }
        if (!chainRequired.isEmpty()) {
            result.add(chainLabel("required"));
            result.add(new BookmarkChainTooltipComponent(chainRequired, false));
        }
        if (!chainMissing.isEmpty()) {
            result.add(chainLabel("missing"));
            result.add(new BookmarkChainTooltipComponent(chainMissing, true));
        }
        if (EmiInput.isAltDown()) {
            result.addAll(List.of(
                tip("group"), tip(group.crafting ? "crafting_on" : "crafting_off"),
                tip("toggle_view"), tip("toggle_crafting"), tip("collapse_group"),
                tip("drag_group"), tip("ungroup"), tip("sort_group"), quantityTip(), tip("display_amounts"),
                treeTip(), takeTip(), craftTip()));
            addCraftStatus(result);
        } else {
            result.add(tip("chain_help"));
        }
        return result;
    }

    private static ClientTooltipComponent chainLabel(String key) {
        return ClientTooltipComponent.create(EmiPort.translatable("emi.bookmarks.chain." + key)
            .withStyle(ChatFormatting.GRAY).getVisualOrderText());
    }

    private void ensureChain(Group group) {
        long revision = EmiBookmarkGroups.revision();
        Object inventory = EmiScreenManager.lastPlayerInventory;
        if (group == chainGroup && revision == chainRevision && inventory == chainInventory) return;
        chainGroup = group;
        chainRevision = revision;
        chainInventory = inventory;

        List<Row> rows = EmiBookmarkGroups.rows(group, EmiScreenManager.lastPlayerInventory);
        Map<EmiIngredient, Long> outputs = new LinkedHashMap<>();
        for (Row row : rows) {
            if (!row.input && row.role == EmiBookmarkGroups.Role.RESULT && row.recipe != null) {
                addAmount(outputs, row.ingredient, row.explicitAmount);
            }
        }

        Map<EmiIngredient, Long> required = new LinkedHashMap<>();
        Map<EmiIngredient, Long> missing = new LinkedHashMap<>();
        MaterialTree tree = EmiBookmarkGroups.tree(group);
        CraftingTreeModel model = null;
        if (tree != null) {
            try {
                model = new CraftingTreeModel(tree, EmiScreenManager.lastPlayerInventory);
            } catch (RuntimeException ignored) {
            }
        }
        if (model != null) {
            for (FlatMaterialCost cost : model.required) addAmount(required, cost.ingredient, cost.getEffectiveAmount());
            for (FlatMaterialCost cost : model.missing) addAmount(missing, cost.ingredient, cost.getEffectiveAmount());
        } else {
            // Fallback for an unloaded/recipe-less chain: use the visible input rows.
            for (Row row : rows) {
                if (row.input) {
                    addAmount(required, row.ingredient, row.calculatedAmount);
                    addAmount(missing, row.ingredient, row.remainingAmount);
                }
            }
        }

        List<BookmarkChainTooltipComponent.Entry> requiredEntries = new ArrayList<>();
        for (Map.Entry<EmiIngredient, Long> e : required.entrySet()) {
            requiredEntries.add(new BookmarkChainTooltipComponent.Entry(e.getKey(), e.getValue(),
                amountOf(missing, e.getKey())));
        }
        List<BookmarkChainTooltipComponent.Entry> missingEntries = new ArrayList<>();
        for (Map.Entry<EmiIngredient, Long> e : missing.entrySet()) {
            if (e.getValue() > 0) {
                missingEntries.add(new BookmarkChainTooltipComponent.Entry(e.getKey(), e.getValue(), e.getValue()));
            }
        }
        List<BookmarkChainTooltipComponent.Entry> outputEntries = new ArrayList<>();
        for (Map.Entry<EmiIngredient, Long> e : outputs.entrySet()) {
            outputEntries.add(new BookmarkChainTooltipComponent.Entry(e.getKey(), Math.max(1, e.getValue()), 0));
        }
        chainOutputs = List.copyOf(outputEntries);
        chainRequired = List.copyOf(requiredEntries);
        chainMissing = List.copyOf(missingEntries);
    }

    private static void addAmount(Map<EmiIngredient, Long> map, EmiIngredient ingredient, long amount) {
        if (ingredient == null || ingredient.isEmpty() || amount <= 0) return;
        for (Map.Entry<EmiIngredient, Long> entry : map.entrySet()) {
            if (EmiIngredient.areEqual(entry.getKey(), ingredient)) {
                entry.setValue(saturatedAdd(entry.getValue(), amount));
                return;
            }
        }
        map.put(ingredient.copy().setAmount(1), amount);
    }

    private static long amountOf(Map<EmiIngredient, Long> map, EmiIngredient ingredient) {
        for (Map.Entry<EmiIngredient, Long> entry : map.entrySet()) {
            if (EmiIngredient.areEqual(entry.getKey(), ingredient)) return entry.getValue();
        }
        return 0;
    }

    private static long saturatedAdd(long a, long b) {
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }
    private static ClientTooltipComponent tip(String key, Object... args) { return ClientTooltipComponent.create(EmiPort.translatable("emi.bookmarks." + key, args).getVisualOrderText()); }
    private static ClientTooltipComponent quantityTip() { return tip("quantity", EmiConfig.getBookmarkQuantityStep()); }
    private static ClientTooltipComponent treeTip() { return tip("group_tree", EmiConfig.viewBookmarkGroup.getBindText()); }
    private static ClientTooltipComponent takeTip() { return tip("take_tip", EmiConfig.takeBookmarkGroup.getBindText()); }
    private static ClientTooltipComponent craftTip() { return tip("group_craft", EmiConfig.craftBookmarkGroup.getBindText()); }
    private static void addCraftStatus(List<ClientTooltipComponent> result) {
        BookmarkCrafting.Status craft = BookmarkCrafting.status();
        BookmarkMaterialTransfer.Status transfer = BookmarkMaterialTransfer.status();
        ClientTooltipComponent craftTip = tip("craft_status", EmiPort.translatable("emi.bookmarks.status." + craft.result()),
            EmiPort.translatable("emi.bookmarks.reason." + craft.reason()), craft.completed(), craft.operations());
        ClientTooltipComponent transferTip = tip("transfer_status", EmiPort.translatable("emi.bookmarks.status." + transfer.result()),
            EmiPort.translatable("emi.bookmarks.reason." + transfer.reason()), transfer.completed(), transfer.operations());
        // Put the active transfer before an idle crafting runner.
        if (transfer.running()) { result.add(transferTip); result.add(craftTip); }
        else { result.add(craftTip); result.add(transferTip); }
    }
    private static void showTransferStatus() {
        BookmarkMaterialTransfer.Status status = BookmarkMaterialTransfer.status();
        var client = net.minecraft.client.Minecraft.getInstance();
        if (client.gui != null) client.gui.setOverlayMessage(EmiPort.translatable("emi.bookmarks.transfer_status",
            EmiPort.translatable("emi.bookmarks.status." + status.result()),
            EmiPort.translatable("emi.bookmarks.reason." + status.reason()), status.completed(), status.operations()), false);
    }

    public boolean mouseClicked(int mx, int my, int button) {
        layout();
        Cell cell = cellAt(mx, my);
        if (cell != null && cell.item != null && button == 0 && EmiInput.isAltDown()) {
            Group group = cell.item.group;
            if (group.collapsed) EmiBookmarkGroups.toggleCollapsed(group);
            else if (group.crafting && cell.item.recipe != null) EmiBookmarkGroups.toggleRecipe(group, cell.item.recipe);
            return true;
        }
        if ((header(mx, my) || gutter(mx, my)) && (button == 0 || button == 1)) {
            if (button == 0 && EmiInput.isShiftDown()
                    && groupAt(mx, my) == EmiBookmarkGroups.defaultGroup()) return true;
            pressedGroup = groupAt(mx, my);
            startRow = endRow = header(mx, my) ? -1 : absoluteRow(my);
            pressButton = button;
            moved = false;
            pressedAt = net.minecraft.Util.getMillis();
            edgeDirection = 0;
            movingGroup = EmiInput.isShiftDown() && button == 0 && pressedGroup != null
                && pressedGroup != EmiBookmarkGroups.defaultGroup();
            if (movingGroup) beginGroupMove(mx, my);
            return true;
        }
        if (cell != null && cell.item != null && !cell.item.input && button == 0 && EmiInput.isShiftDown()) {
            pressedCell = cell; moved = false; pressButton = button;
            pressedAt = net.minecraft.Util.getMillis();
            edgeDirection = 0;
            return true;
        }
        return false;
    }
    private void beginGroupMove(int mx, int my) {
        moved = movingGroup = true;
        groupPreview.clear();
        for (Cell member : cells) if (member.item != null && member.item.group == pressedGroup
                && member.row >= baseRow() && member.row < baseRow() + panel.space.th)
            groupPreview.add(new DragCell(member.favorite, x(member) - mx, y(member) - my));
    }
    private void updateDrag(int mx, int my) {
        if (pressedCell == null && pressedGroup == null && startRow < 0) return;
        long now = net.minecraft.Util.getMillis();
        ScreenSpace space = panel.space;
        if (pressedCell != null) moved |= cellAt(mx, my) != pressedCell || now - pressedAt >= DRAG_THRESHOLD_MS;
        else if (startRow >= 0) moved |= !space.contains(mx, my) || absoluteRow(my) != startRow || now - pressedAt >= DRAG_THRESHOLD_MS;
        if (!moved) return;
        int direction = 0;
        if (pages > 1 && mx >= space.tx && mx < space.tx + space.tw * 18) {
            if (my >= space.ty - 18 && my < space.ty + 6) direction = -1;
            else if (my >= space.ty + space.th * 18 - 6 && my < space.ty + space.th * 18 + 18) direction = 1;
        }
        if (direction == 0 || direction != edgeDirection) {
            edgeDirection = direction;
            edgeSince = now;
        } else if (now - edgeSince >= PAGE_SCROLL_DELAY_MS) {
            int next = Math.clamp(panel.page + direction, 0, pages - 1);
            panel.page = next;
            edgeSince = now;
        }
        if (movingGroup) {
            moveGroupAt(mx, my);
            return;
        }
        if (startRow >= 0 && mx >= space.tx && mx < space.tx + space.tw * 18) {
            int maxLocal = Math.max(0, space.th - 1);
            int local = Math.clamp((my - space.ty) / 18, 0, maxLocal);
            endRow = Math.min(rows.size() - 1, baseRow() + local);
        }
    }
    /** NEI-style live reorder at whole-group row boundaries only. */
    private void moveGroupAt(int mx, int my) {
        ScreenSpace space = panel.space;
        if (pressedGroup == null || !space.contains(mx, my) || header(mx, my)) return;
        int over = absoluteRow(my);
        if (over < 0) return;
        int source = rows.indexOf(pressedGroup);
        if (source < 0) return;
        if (over >= rows.size()) {
            EmiBookmarkGroups.moveGroup(pressedGroup, null);
            layout();
            return;
        }
        Group target = rows.get(over);
        // Synthetic progress entries are not sortable groups.
        if (target == null || target == pressedGroup) return;
        boolean down = source < over;
        // Do not split a multi-row target: enter its first row from above only
        // when moving up, or its last row from below when moving down.
        if (down && over + 1 < rows.size() && rows.get(over + 1) == target) return;
        if (!down && over > 0 && rows.get(over - 1) == target) return;
        EmiBookmarkGroups.moveGroup(pressedGroup, target, down);
        layout();
    }
    public boolean mouseDragged(int mx, int my, int button) {
        if (pressedCell == null && pressedGroup == null && startRow < 0) return false;
        if (button != pressButton) return false;
        updateDrag(mx, my);
        return true;
    }
    public boolean mouseReleased(int mx, int my, int button) {
        if (button != pressButton) return false;
        if (movingGroup) {
            moveGroupAt(mx, my);
            pressedGroup = null; startRow = endRow = -1;
            movingGroup = moved = false; edgeDirection = 0; groupPreview.clear();
            invalidate();
            return true;
        }
        if (pressedCell != null) {
            Cell target = cellAt(mx, my);
            if (moved && !pressedCell.item.input && panel.space.contains(mx, my)
                    && (target == null || target.item != null && !target.item.input)) {
                EmiBookmarkGroups.move(EmiBookmarkGroups.representedAnchors(pressedCell.item.group, pressedCell.item.anchor),
                    target == null ? null : target.item.anchor);
            }
            if (!moved && target != null && target.item != null && target.item.anchor == pressedCell.item.anchor) {
                EmiScreenManager.stackInteraction(new EmiScreenManager.SidebarEmiStackInteraction(target.favorite, panel.space,
                    target.favorite.getRecipe(), true), bind -> bind.matchesMouse(button));
            }
            pressedCell = null; moved = false; edgeDirection = 0; invalidate(); return true;
        }
        if (pressedGroup == null && startRow < 0) return false;
        Group group = pressedGroup;
        if (moved) {
            {
                List<EmiFavorite> selected = new ArrayList<>();
                Set<EmiFavorite> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                for (Cell cell : cells) if (cell.item != null && cell.row >= Math.min(startRow, endRow) && cell.row <= Math.max(startRow, endRow)) {
                    for (EmiFavorite anchor : EmiBookmarkGroups.representedAnchors(cell.item.group, cell.item.anchor))
                        if (seen.add(anchor)) selected.add(anchor);
                }
                if (pressButton == 1) EmiBookmarkGroups.ungroup(selected);
                else EmiBookmarkGroups.group(selected);
            }
        } else if (group != null) {
            if (button == 0 && EmiInput.isAltDown()) EmiBookmarkGroups.toggleCollapsed(group);
            else if (button == 0) EmiBookmarkGroups.toggleView(group);
            else if (button == 1) EmiBookmarkGroups.toggleCrafting(group);
        }
        pressedGroup = null; startRow = endRow = -1; movingGroup = moved = false; edgeDirection = 0; invalidate(); return true;
    }
    public boolean mouseScrolled(int mx, int my, int amount) {
        layout();
        Cell hovered = cellAt(mx, my);
        if (EmiInput.isShiftDown() && hovered != null && hovered.item != null && hovered.item.input && hovered.item.source.getEmiStacks().size() > 1) {
            EmiBookmarkGroups.cycleIngredient(hovered.item, amount); return true;
        }
        if (!EmiInput.isControlDown()) return false;
        Group group = groupAt(mx, my);
        Cell cell = cellAt(mx, my);
        if (group == null || cell != null && cell.item == null) return false;
        if (amount != 0) EmiBookmarkGroups.adjust(group, cell == null || group.collapsed ? null : cell.item.anchor, amount * (EmiInput.isAltDown() ? (long) EmiConfig.getBookmarkQuantityStep() : 1L));
        return true;
    }

    private static int anchorIndex(EmiFavorite anchor) {
        for (int i = 0; i < EmiFavorites.favorites.size(); i++) if (EmiFavorites.favorites.get(i) == anchor) return i;
        return -1;
    }

    /** Translate a visual drop target back to the ordered bookmark anchors. */
    public boolean drop(EmiIngredient ingredient, int mx, int my) {
        layout();
        if (!panel.space.contains(mx, my) || ingredient.isEmpty()) return false;
        Cell target = cellAt(mx, my);
        if (ingredient instanceof EmiFavorite.Synthetic || ingredient instanceof RowFavorite row && row.row.input
                || target != null && target.item != null && target.item.input) return false;
        EmiFavorite targetAnchor = target == null || target.item == null ? null : target.item.anchor;
        EmiIngredient source = ingredient instanceof RowFavorite row ? row.row.anchor : ingredient;
        if (targetAnchor != null && targetAnchor.getRecipe() == null && targetAnchor.strictEquals(source)) return true;
        if (source instanceof EmiFavorite anchor && anchorIndex(anchor) >= 0) {
            EmiBookmarkGroups.move(EmiBookmarkGroups.representedAnchors(EmiBookmarkGroups.groupOf(anchor), anchor), targetAnchor);
        } else {
            Set<EmiFavorite> previous = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            previous.addAll(EmiFavorites.favorites);
            int index = targetAnchor == null ? EmiFavorites.favorites.size() : anchorIndex(targetAnchor);
            EmiFavorites.addFavoriteAt(source, Math.max(0, index));
            for (EmiFavorite added : EmiFavorites.favorites) if (!previous.contains(added)) {
                EmiBookmarkGroups.move(List.of(added), targetAnchor);
                break;
            }
        }
        invalidate();
        return true;
    }

    public boolean keyPressed(int mx, int my, java.util.function.Function<EmiBind, Boolean> matches) {
        layout();
        boolean groupControl = header(mx, my) || gutter(mx, my);
        Cell cell = cellAt(mx, my);
        Group group = groupAt(mx, my);
        if (group == null) return false;
        if (groupControl && EmiInput.isShiftDown() && EmiScreenManager.matchesFavorite(matches)) {
            EmiBookmarkGroups.removeGroup(group); invalidate(); return true;
        }
        if (cell != null && cell.item != null && isCollapsed(cell.item) && EmiScreenManager.matchesFavorite(matches)) {
            if (EmiInput.isShiftDown()) { EmiBookmarkGroups.removeRepresented(cell.item); invalidate(); }
            return true;
        }
        if ((groupControl || cell != null && cell.item != null && !cell.item.input) && matches.apply(EmiConfig.viewBookmarkGroup)) {
            BookmarkGroupActions.openTree(group);
            return true;
        }
        if ((groupControl || cell != null && cell.item != null && !cell.item.input) && matchesWithShift(EmiConfig.takeBookmarkGroup, matches)) {
            if (BookmarkMaterialTransfer.isRunning()) BookmarkMaterialTransfer.cancel();
            else {
                BookmarkCrafting.cancel();
                BookmarkMaterialTransfer.start(group, EmiInput.isShiftDown());
            }
            showTransferStatus();
            return true;
        }
        boolean groupCraft = matchesWithShift(EmiConfig.craftBookmarkGroup, matches);
        if ((groupControl || cell != null && cell.item != null && !cell.item.input) && groupCraft) {
            boolean cancelling = BookmarkCrafting.isRunning();
            if (cancelling) BookmarkCrafting.cancel();
            else {
                BookmarkMaterialTransfer.cancel();
                if (BookmarkMaterialTransfer.isRunning()) {
                    showTransferStatus();
                    return true;
                }
                BookmarkCrafting.start(group, EmiInput.isControlDown());
            }
            BookmarkCrafting.Status status = BookmarkCrafting.status();
            var client = net.minecraft.client.Minecraft.getInstance();
            if (client.gui != null) client.gui.setOverlayMessage(EmiPort.translatable("emi.bookmarks.craft_status",
                EmiPort.translatable("emi.bookmarks.status." + status.result()),
                EmiPort.translatable("emi.bookmarks.reason." + status.reason()), status.completed(), status.operations()), false);
            return true;
        }
        return false;
    }

    private static boolean matchesWithShift(EmiBind bind, java.util.function.Function<EmiBind, Boolean> matches) {
        if (matches.apply(bind)) return true;
        List<EmiBind.ModifiedKey> keys = bind.boundKeys.stream().map(key ->
            new EmiBind.ModifiedKey(key.key(), key.modifiers() | EmiInput.SHIFT_MASK
                | (EmiInput.isControlDown() ? EmiInput.CONTROL_MASK : 0))).toList();
        return matches.apply(new EmiBind("", keys.toArray(EmiBind.ModifiedKey[]::new)));
    }

    private static boolean isCollapsed(Row row) {
        return row.group.collapsed || row.recipe != null && row.recipe.getId() != null
            && row.group.collapsedRecipes.contains(row.recipe.getId().toString());
    }

    public static final class RowFavorite extends EmiFavorite {
        public final Row row;
        RowFavorite(Row row) { super(row.ingredient.copy().setAmount(Math.max(1, row.amount)), row.input ? null : row.recipe); this.row = row; }
        /** Display modifiers never write back to a persisted favorite or alter crafting amounts. */
        private long displayAmount() {
            return displayAmount(EmiInput.isShiftDown(), EmiInput.isControlDown());
        }
        private long displayAmount(boolean shift, boolean control) {
            return Math.max(0, shift ? row.remainingAmount
                : control && !row.group.crafting ? row.explicitAmount : row.calculatedAmount);
        }
        @Override public void render(GuiGraphics raw, int x, int y, float delta, int flags) {
            EmiDrawContext context = EmiDrawContext.wrap(raw);
            context.enableBlend();
            stack.render(raw, x, y, delta, flags & ~EmiIngredient.RENDER_AMOUNT);
            if (recipe != null && (flags & EmiIngredient.RENDER_INGREDIENT) != 0)
                EmiRenderHelper.renderRecipeFavorite(stack, context, x, y);
            if ((flags & EmiIngredient.RENDER_AMOUNT) != 0) {
                boolean fluid = !stack.getEmiStacks().isEmpty() && stack.getEmiStacks().getFirst() instanceof FluidEmiStack;
                long amount = displayAmount();
                var text = fluid ? EmiConfig.fluidUnit.translate(amount) : EmiPort.literal(compactAmount(amount));
                int width = net.minecraft.client.Minecraft.getInstance().font.width(text);
                float scale = Math.min(1f, 17f / Math.max(1, width));
                context.push();
                context.matrices().translate(x + 17, y + 17, 300);
                context.matrices().scale(scale, scale, 1);
                context.drawTextWithShadow(text, -width, -9);
                context.pop();
            }
            context.enableBlend();
        }
        private static String compactAmount(long amount) {
            String[] units = {"", "k", "M", "B", "T", "Q", "E"};
            long divisor = 1;
            int unit = 0;
            while (amount / divisor >= 1000 && unit < units.length - 1) { divisor *= 1000; unit++; }
            long whole = amount / divisor;
            long tenth = divisor == 1 ? 0 : (amount % divisor) / (divisor / 10);
            return whole + (unit > 0 && whole < 100 && tenth > 0 ? "." + tenth : "") + units[unit];
        }
        private ClientTooltipComponent amountTip(String key, long amount) {
            amount = Math.max(0, amount);
            boolean fluid = !stack.getEmiStacks().isEmpty() && stack.getEmiStacks().getFirst() instanceof FluidEmiStack;
            return tip(key, fluid ? EmiConfig.fluidUnit.translate(amount)
                : amount == 0 ? EmiPort.literal("0") : EmiRenderHelper.getAmountText(stack, amount));
        }
        @Override public List<ClientTooltipComponent> getTooltip() {
            return getTooltip(EmiInput.isAltDown());
        }
        private List<ClientTooltipComponent> getTooltip(boolean alt) {
            if (!alt) return stack.getTooltip();
            List<ClientTooltipComponent> result = new ArrayList<>(super.getTooltip());
            String role = switch (row.role) { case RESULT -> "result"; case REMAINDER -> "remainder"; case INGREDIENT -> "ingredient"; };
            result.add(ClientTooltipComponent.create(EmiPort.translatable("emi.bookmarks." + role).withStyle(ChatFormatting.GRAY).getVisualOrderText()));
            result.add(amountTip("explicit_amount", row.explicitAmount));
            result.add(amountTip("calculated_amount", row.calculatedAmount));
            result.add(amountTip("remaining_amount", row.remainingAmount));
            result.add(tip("display_amounts"));
            result.add(ClientTooltipComponent.create(EmiPort.translatable("emi.bookmarks.batches", row.batches).withStyle(ChatFormatting.GRAY).getVisualOrderText()));
            result.add(quantityTip());
            if (!row.input) { result.add(treeTip()); result.add(takeTip()); result.add(craftTip()); addCraftStatus(result); }
            if (row.input && row.source.getEmiStacks().size() > 1) result.add(tip("cycle_ingredient"));
            if (row.group.crafting) result.add(tip("collapse_recipe"));
            if (isCollapsed(row)) result.add(tip("remove_collapsed", EmiConfig.favorite.getBindText()));
            return result;
        }
        @Override public boolean isUnbatchable() { return true; }
    }
    private final class Cell {
        final Row item; final EmiFavorite favorite; final int row, column;
        Cell(Row item, int row, int column) { this.item = item; this.favorite = new RowFavorite(item); this.row = row; this.column = column; }
        Cell(EmiFavorite.Synthetic favorite, int row, int column) { this.item = null; this.favorite = favorite; this.row = row; this.column = column; }
    }
}
