package dev.emi.emi.screen.tooltip;

import dev.emi.emi.EmiPort;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.runtime.EmiDrawContext;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Compact icon grid for a bookmark-chain tooltip. Each cell shows an
 * ingredient and its amount; cells with a missing amount get a red outline.
 */
public class BookmarkChainTooltipComponent implements EmiTooltipComponent {
    public record Entry(EmiIngredient ingredient, long amount, long missing) {
    }

    private static final int COLUMNS = 9;
    private static final int SLOT = 18;
    private final List<Entry> entries;
    private final boolean missingOnly;

    public BookmarkChainTooltipComponent(List<Entry> entries, boolean missingOnly) {
        this.entries = new ArrayList<>(entries);
        this.missingOnly = missingOnly;
    }

    private int columns() {
        return Math.clamp(entries.size(), 1, COLUMNS);
    }

    @Override
    public int getHeight() {
        return entries.isEmpty() ? 0 : ((entries.size() - 1) / COLUMNS + 1) * SLOT;
    }

    @Override
    public int getWidth(Font textRenderer) {
        return entries.isEmpty() ? 0 : columns() * SLOT;
    }

    @Override
    public void drawTooltip(EmiDrawContext context, TooltipRenderData render) {
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            int x = i % COLUMNS * SLOT;
            int y = i / COLUMNS * SLOT;
            context.drawStack(entry.ingredient(), x, y);
            if (entry.missing() > 0) {
                // Red outline makes missing materials stand out even when the icon
                // itself has similar colors to an available stack.
                context.fill(x, y, SLOT, 1, 0xFFFF5555);
                context.fill(x, y + SLOT - 1, SLOT, 1, 0xFFFF5555);
                context.fill(x, y, 1, SLOT, 0xFFFF5555);
                context.fill(x + SLOT - 1, y, 1, SLOT, 0xFFFF5555);
            }
            long amount = missingOnly ? entry.missing() : entry.amount();
            if (amount > 0) {
                Component text = EmiPort.literal(compact(amount));
                int width = render.text().width(text);
                float scale = Math.min(1f, 17f / Math.max(1, width));
                context.push();
                context.matrices().translate(x + SLOT - 1, y + SLOT - 1, 300);
                context.matrices().scale(scale, scale, 1);
                context.drawTextWithShadow(text, -width, -9,
                        missingOnly || entry.missing() > 0 ? 0xFFFF7070 : 0xFFFFFFFF);
                context.pop();
            }
        }
    }

    private static String compact(long amount) {
        if (amount < 1000) {
            return Long.toString(amount);
        }
        String[] units = {"k", "M", "B", "T", "Q", "E"};
        long divisor = 1;
        int unit = -1;
        while (unit < units.length - 1 && amount / divisor >= 1000) {
            divisor *= 1000;
            unit++;
        }
        long whole = amount / divisor;
        long tenth = divisor == 1 ? 0 : amount % divisor / (divisor / 10);
        return whole + (whole < 100 && tenth > 0 ? "." + tenth : "") + units[Math.max(0, unit)];
    }
}
