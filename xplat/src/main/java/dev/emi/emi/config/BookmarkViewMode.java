package dev.emi.emi.config;

import dev.emi.emi.EmiPort;
import net.minecraft.network.chat.Component;

/**
 * Layout mode for a bookmark group. DEFAULT flows items through the grid;
 * TODO puts every result/item at the start of its own row, like GTNH's
 * bookmark list view.
 */
public enum BookmarkViewMode implements ConfigEnum {
	DEFAULT("default"),
	TODO("todo"),
	;

	private final String name;

	BookmarkViewMode(String name) {
		this.name = name;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public Component getText() {
		return EmiPort.translatable("emi.bookmark_view_mode." + name);
	}

	public static BookmarkViewMode byName(String name) {
		if (name != null) {
			for (BookmarkViewMode mode : values()) {
				if (mode.name.equalsIgnoreCase(name)) {
					return mode;
				}
			}
		}
		return DEFAULT;
	}

	public BookmarkViewMode toggle() {
		return this == DEFAULT ? TODO : DEFAULT;
	}
}
