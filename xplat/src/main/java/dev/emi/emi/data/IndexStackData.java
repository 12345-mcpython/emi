package dev.emi.emi.data;

import java.util.List;
import java.util.function.Predicate;

import dev.emi.emi.api.stack.EmiIngredient;

public record IndexStackData(boolean disable, List<Added> added, List<EmiIngredient> removed, List<Filter> filters) {

	public record Added(EmiIngredient added, EmiIngredient after) {
	}

	public record Filter(Predicate<String> filter) {
	}
}
