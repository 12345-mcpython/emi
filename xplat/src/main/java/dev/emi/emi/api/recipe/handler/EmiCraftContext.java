package dev.emi.emi.api.recipe.handler;

import org.jetbrains.annotations.ApiStatus;

import dev.emi.emi.api.recipe.EmiPlayerInventory;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;

public record EmiCraftContext<T extends AbstractContainerMenu>(AbstractContainerScreen<T> screen,
															   EmiPlayerInventory inventory, Type type,
															   Destination destination, int amount) {
	@ApiStatus.Internal
	public EmiCraftContext {
	}

	@ApiStatus.Internal
	public EmiCraftContext(AbstractContainerScreen<T> screen, EmiPlayerInventory inventory, Type type) {
		this(screen, inventory, type, Destination.NONE, 1);
	}


	public T getScreenHandler() {
		return screen.getMenu();
	}

	/**
	 * @deprecated use {@link #screen()}
	 */
	@Deprecated
	public AbstractContainerScreen<T> getScreen() {
		return screen;
	}

	/**
	 * @deprecated use {@link #inventory()}
	 */
	@Deprecated
	public EmiPlayerInventory getInventory() {
		return inventory;
	}

	/**
	 * @deprecated use {@link #type()}
	 */
	@Deprecated
	public Type getType() {
		return type;
	}

	/**
	 * @deprecated use {@link #destination()}
	 */
	@Deprecated
	public Destination getDestination() {
		return destination;
	}

	/**
	 * @deprecated use {@link #amount()}
	 */
	@Deprecated
	public int getAmount() {
		return amount;
	}


	public enum Type {
		/**
		 * A fill from the recipe screen's fill button
		 */
		FILL_BUTTON,
		/**
		 * A fill from a stack in a sidebar with recipe context
		 */
		CRAFTABLE
	}

	public enum Destination {
		/**
		 * No output, simply move the ingredients to where they belong.
		 */
		NONE,
		/**
		 * If possible, immediately craft the recipe and move it into the player's cursor.
		 * If this action is not supported, this should do nothing more than FILL.
		 */
		CURSOR,
		/**
		 * If possible, immediately craft the recipe and move it into the player's inventory.
		 * If this action is not supported, this should do nothing more than FILL.
		 */
		INVENTORY,
	}
}
