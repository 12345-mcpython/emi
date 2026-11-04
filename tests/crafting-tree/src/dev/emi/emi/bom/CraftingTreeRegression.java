package dev.emi.emi.bom;

import java.util.*;
import dev.emi.emi.api.recipe.*;
import dev.emi.emi.api.stack.*;

/** Plain-Java executable tests compile the real production model and material-tree classes. */
public class CraftingTreeRegression {
    static int assertions;
    record Recipe(List<EmiIngredient> getInputs, List<EmiStack> getOutputs) implements EmiRecipe {}
    static EmiStack s(String id, long amount) { return new EmiStack(id, amount); }
    static Recipe recipe(String output, long amount, EmiIngredient... input) {
        Recipe r = new Recipe(List.of(input), List.of(s(output, amount)));
        BoM.recipes.put(s(output, 1), r);
        return r;
    }
    static void equal(long actual, long expected, String message) {
        assertions++;
        if (actual != expected) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }
    static void check(boolean condition, String message) { equal(condition ? 1 : 0, 1, message); }
    static long cost(List<FlatMaterialCost> list, String id) {
        return list.stream().filter(c -> EmiIngredient.areEqual(c.ingredient, s(id, 1))).mapToLong(FlatMaterialCost::getEffectiveAmount).sum();
    }
    static CraftingTreeModel.Entry entry(CraftingTreeModel model, String id) {
        return model.entries.stream().filter(e -> e.ingredient.equals(s(id, 1))).findFirst().orElseThrow();
    }
    static void sharedAndInventory() {
        BoM.recipes.clear();
        Recipe component = recipe("component", 4, s("ore", 3));
        recipe("left", 1, s("component", 1));
        recipe("right", 1, s("component", 1));
        MaterialTree tree = new MaterialTree(recipe("goal", 1, s("left", 1), s("right", 1)));
        CraftingTreeModel full = new CraftingTreeModel(tree, null);
        equal(cost(full.required, "ore"), 3, "shared demand rounds once");
        equal(cost(full.leftovers, "component"), 2, "shared surplus");
        equal(full.entries.stream().filter(e -> e.recipe == component).count(), 1, "shared entry merged");
        equal(full.links.stream().filter(l -> l.child == entry(full, "component")).count(), 2, "two shared links");
        equal(entry(full, "component").total, 2, "shared total");
        for (CraftingTreeModel.Link link : full.links) check(link.child.depth > link.parent.depth, "topological depth");
        equal(full.steps.get(0).batches, 1, "dependency step batches");
        check(full.steps.get(0).recipe == component, "dependency first");
        EmiStack stock = s("component", 1);
        CraftingTreeModel partial = new CraftingTreeModel(tree, new EmiPlayerInventory(List.of(stock)));
        equal(cost(partial.missing, "ore"), 3, "single intermediate inventory allocation");
        equal(entry(partial, "component").needed, 1, "inventory deducted once");
        equal(stock.getAmount(), 1, "inventory remains untouched");
        CraftingTreeModel ready = new CraftingTreeModel(tree, new EmiPlayerInventory(List.of(s("goal", 1))));
        equal(ready.missing.size(), 0, "finished root needs nothing");
        equal(ready.steps.size(), 0, "finished root no steps");
    }
    static void sharedRawInventory() {
        BoM.recipes.clear();
        recipe("left", 1, s("ore", 2));
        recipe("right", 1, s("ore", 3));
        MaterialTree tree = new MaterialTree(recipe("goal", 1, s("left", 1), s("right", 1)));
        CraftingTreeModel model = new CraftingTreeModel(tree, new EmiPlayerInventory(List.of(s("ore", 4))));
        equal(cost(model.required, "ore"), 5, "raw total");
        equal(cost(model.missing, "ore"), 1, "raw stock used once");
        equal(model.leftovers.size(), 0, "unused inventory excluded");
    }
    static void probability() {
        BoM.recipes.clear();
        Recipe outputChance = new Recipe(List.of(s("ore", 2)), List.of(s("goal", 1).setChance(.5f)));
        MaterialTree tree = new MaterialTree(outputChance);
        CraftingTreeModel model = new CraftingTreeModel(tree, null);
        equal(cost(model.required, "ore"), 4, "probabilistic production scales inputs");
        check(model.required.get(0) instanceof ChanceMaterialCost, "EMI chance class preserved");
        equal(model.steps.get(0).batches, 2, "expected production batches");
        BoM.recipes.clear();
        tree = new MaterialTree(recipe("goal", 1, s("ore", 2).setChance(.5f), s("ore", 2)));
        model = new CraftingTreeModel(tree, null);
        equal(cost(model.required, "ore"), 3, "duplicate ingredient chances weighted");
        model = new CraftingTreeModel(tree, new EmiPlayerInventory(List.of(s("ore", 1))));
        equal(cost(model.missing, "ore"), 2, "probability inventory");
    }
    static void byproductsAndRemainders() {
        BoM.recipes.clear();
        Recipe producer = new Recipe(List.of(s("ore", 1)), List.of(s("left", 1), s("dust", 2)));
        BoM.recipes.put(s("left", 1), producer);
        MaterialTree tree = new MaterialTree(recipe("goal", 1, s("dust", 1), s("left", 1)));
        CraftingTreeModel model = new CraftingTreeModel(tree, null);
        equal(cost(model.required, "dust"), 0, "sibling co-product reused regardless of input order");
        equal(cost(model.required, "ore"), 1, "co-product producer input");
        equal(cost(model.leftovers, "dust"), 1, "co-product surplus");
        BoM.recipes.clear();
        EmiStack bucket = s("bucket", 1);
        tree = new MaterialTree(recipe("goal", 1, s("fluid", 1).remainder(bucket)));
        tree.batches = 3;
        model = new CraftingTreeModel(tree, null);
        equal(cost(model.leftovers, "bucket"), 3, "container remainder");
        equal(bucket.getAmount(), 1, "source remainder untouched");
    }
    static void catalyst() {
        BoM.recipes.clear();
        EmiStack tool = s("tool", 1); tool.remainder(tool);
        recipe("left", 1, tool);
        recipe("right", 1, tool);
        MaterialTree tree = new MaterialTree(recipe("goal", 1, s("left", 1), s("right", 1)));
        tree.batches = 12;
        CraftingTreeModel model = new CraftingTreeModel(tree, null);
        equal(cost(model.required, "tool"), 1, "catalyst shared across branches and batches");
        model = new CraftingTreeModel(tree, new EmiPlayerInventory(List.of(s("tool", 1))));
        equal(cost(model.missing, "tool"), 0, "catalyst stock retained");
    }
    static void recipeResetAndCycle() {
        BoM.recipes.clear();
        Recipe a = recipe("a", 1, s("b", 1));
        recipe("b", 1, s("a", 1));
        MaterialTree tree = new MaterialTree(a);
        CraftingTreeModel model = new CraftingTreeModel(tree, null);
        equal(model.entries.size(), 3, "cycle ends in unresolved leaf");
        check(model.entries.stream().anyMatch(e -> e.cycle), "cycle marked");
        equal(cost(model.required, "a"), 1, "cycle unresolved input retained");
        tree.addResolution(s("b", 1), null);
        check(tree.goal.children.get(0).recipe == null, "null child resolution");
        check(tree.goal.children.get(0).children == null, "null child has no stale subtree");
        tree.addResolution(s("a", 1), null);
        check(tree.goal.recipe == null && tree.goal.children == null, "null root clears recipe and children");
        tree.calculateCost();
        equal(tree.cost.costs.get(s("a", 1)).amount, 1, "legacy cost handles cleared recipe");
        MaterialNode node = new MaterialNode(s("x", 2).setChance(.5f));
        node.consumeChance = .3f; node.produceChance = .4f; node.catalyst = true;
        MaterialNode copy = new MaterialNode(node);
        check(copy.consumeChance == .3f && copy.produceChance == .4f && copy.catalyst, "copy preserves semantics");
    }
    record Alternatives(List<EmiStack> getEmiStacks, long getAmount, float getChance) implements EmiIngredient {
        public EmiIngredient copy() { return this; }
        public EmiIngredient setAmount(long amount) { return new Alternatives(getEmiStacks,amount,getChance); }
        public EmiIngredient setChance(float chance) { return new Alternatives(getEmiStacks,getAmount,chance); }
    }
    static void inventoryAndBudget() {
        BoM.recipes.clear();
        EmiIngredient tag = new Alternatives(List.of(s("iron",1),s("copper",1)),1,1);
        MaterialTree tree = new MaterialTree(recipe("g",1,tag,s("iron",1)));
        CraftingTreeModel model = new CraftingTreeModel(tree,new EmiPlayerInventory(List.of(s("iron",1),s("copper",1))));
        equal(model.missing.size(),0,"specific stock reserved before broad alternative");
        equal(cost(model.available,"iron"),1,"specific iron allocated once");
        equal(cost(model.available,"copper"),1,"alternative copper allocated");
        check(model.available.stream().noneMatch(c->c instanceof ChanceMaterialCost),"integer inventory remains flat");
        BoM.recipes.clear();
        recipe("part",1,s("ore",1));
        tree = new MaterialTree(recipe("g",1,s("part",1)));
        model = new CraftingTreeModel(tree,new EmiPlayerInventory(List.of(s("part",1),s("ore",50))));
        equal(cost(model.available,"part"),1,"available includes intermediate");
        equal(cost(model.available,"ore"),0,"saved raw input is not claimed as inventory allocation");
        equal(model.leftovers.size(),0,"unused raw inventory excluded");
        BoM.recipes.clear();
        for(int i=0;i<18;i++) {
            recipe("a"+i,1,s("a"+(i+1),1),s("b"+(i+1),1));
            recipe("b"+i,1,s("a"+(i+1),1),s("b"+(i+1),1));
        }
        tree = new MaterialTree(BoM.recipes.get(s("a0",1)));
        model = new CraftingTreeModel(tree,null);
        check(model.entries.stream().anyMatch(e->e.truncated),"total expansion budget exposed");
        check(model.entries.size()<100,"diamond graph merged after bounded expansion");
    }
    static void reviewRegressions() {
        BoM.recipes.clear();
        Recipe nested = new Recipe(List.of(s("ore",1)), List.of(s("c",1),s("a",1)));
        BoM.recipes.put(s("c",1), nested);
        recipe("b",1,s("c",1));
        CraftingTreeModel model = new CraftingTreeModel(new MaterialTree(recipe("g",1,s("a",1),s("b",1))),null);
        equal(cost(model.required,"a"),0,"nested co-product substitutes raw");
        equal(cost(model.leftovers,"a"),0,"nested co-product consumed");
        equal(cost(model.required,"ore"),1,"nested co-product inputs");
        check(model.steps.get(0).recipe == nested,"nested producer executes first");
        BoM.recipes.clear();
        Recipe multi = new Recipe(List.of(s("ore",1)),List.of(s("a",1),s("b",1)));
        BoM.recipes.put(s("a",1),multi); BoM.recipes.put(s("b",1),multi);
        model = new CraftingTreeModel(new MaterialTree(recipe("g",1,s("a",1),s("b",1))),null);
        equal(cost(model.required,"ore"),1,"one recipe shared across distinct outputs");
        equal(model.steps.stream().filter(step -> step.recipe == multi).mapToLong(step -> step.batches).sum(),1,"shared production step");
        BoM.recipes.clear();
        Recipe bootstrap = new Recipe(List.of(s("b",1)),List.of(s("a",1),s("b",1)));
        model = new CraftingTreeModel(new MaterialTree(bootstrap),null);
        equal(cost(model.required,"b"),1,"own co-product cannot pay startup");
        equal(cost(model.leftovers,"b"),1,"startup input returned");
        BoM.recipes.clear();
        EmiStack tool = s("x",1); tool.remainder(tool);
        MaterialTree tree = new MaterialTree(recipe("g",1,s("x",1),tool));
        model = new CraftingTreeModel(tree,null);
        equal(cost(model.required,"x"),2,"mixed consumed and catalyst acquisition");
        equal(cost(model.leftovers,"x"),1,"mixed catalyst retained");
        model = new CraftingTreeModel(tree,new EmiPlayerInventory(List.of(s("x",2))));
        equal(cost(model.missing,"x"),0,"mixed inventory sufficient");
        equal(cost(model.leftovers,"x"),1,"mixed inventory catalyst retained");
        equal(cost(model.available,"x"),2,"actual inventory allocation");
        BoM.recipes.clear();
        tool = s("tool",1); tool.remainder(tool);
        recipe("tool",1,s("iron",1));
        model = new CraftingTreeModel(new MaterialTree(recipe("g",1,tool,s("raw",1))),null);
        equal(cost(model.required,"iron"),1,"crafted catalyst acquired");
        equal(cost(model.leftovers,"tool"),1,"crafted catalyst retained");
        BoM.recipes.clear();
        EmiStack partial = s("x",2).remainder(s("x",1));
        tree = new MaterialTree(recipe("g",1,partial)); tree.batches=10;
        model = new CraftingTreeModel(tree,null);
        check(!tree.goal.children.get(0).catalyst,"partial self-return is consumable");
        equal(cost(model.required,"x"),20,"partial self-return never free catalyst");
        equal(cost(model.leftovers,"x"),10,"partial self-return kept");
        BoM.recipes.clear();
        Recipe chance = new Recipe(List.of(s("ore",1)),List.of(s("a",4).setChance(.5f)));
        BoM.recipes.put(s("a",1),chance);
        tree = new MaterialTree(recipe("g",1,s("a",2)));
        model = new CraftingTreeModel(tree,null);
        equal(cost(model.required,"ore"),1,"expected output batch rounding");
        equal(cost(model.leftovers,"a"),0,"expected surplus not nominal surplus");
        chance = new Recipe(List.of(s("ore",4)),List.of(s("a",4)));
        BoM.recipes.put(s("a",1),chance);
        tree = new MaterialTree(recipe("g",1,s("a",1).setChance(.5f)));
        model = new CraftingTreeModel(tree,null);
        equal(cost(model.required,"ore"),4,"chance input cannot execute fractional recipe");
        equal(model.steps.stream().filter(step->step.recipe.getOutputs().get(0).equals(s("a",1))).mapToLong(step->step.batches).sum(),1,"chance input one whole step");
        tree = new MaterialTree(new Recipe(List.of(s("ore",1)),List.of(s("a",1).setChance(0))));
        model = new CraftingTreeModel(tree,null);
        equal(cost(model.required,"a"),1,"zero chance external requirement");
        equal(model.steps.size(),0,"zero chance no impossible billion batches");
        BoM.recipes.clear();
        tree = new MaterialTree(recipe("g",2,s("ore",Long.MAX_VALUE)));
        tree.batches=Long.MAX_VALUE;
        model = new CraftingTreeModel(tree,null);
        equal(model.resultAmount,Long.MAX_VALUE,"result saturates");
        equal(cost(model.required,"ore"),Long.MAX_VALUE,"cost saturates");
        tree = new MaterialTree(recipe("huge",1,s("ore",1).setChance(.5f)));
        tree.batches=10_000_000_000L;
        model = new CraftingTreeModel(tree,null);
        equal(cost(model.required,"ore"),5_000_000_000L,"chance effective amount exceeds integer range");
        BoM.recipes.clear();
        for(int i=0;i<160;i++) recipe("n"+i,1,s("n"+(i+1),1));
        tree = new MaterialTree(BoM.recipes.get(s("n0",1)));
        model = new CraftingTreeModel(tree,null);
        check(model.entries.stream().anyMatch(e->e.truncated),"depth limit exposed");
        check(model.entries.size()<=130,"depth bounded");
        tree.addResolution(s("n0",1),new Recipe(List.of(s("ore",1)),List.of(s("n0",7))));
        equal(tree.goal.amount,7,"root recipe selection preserves batch units");
    }
    public static void main(String[] args) {
        sharedAndInventory(); sharedRawInventory(); probability(); byproductsAndRemainders(); catalyst(); recipeResetAndCycle(); reviewRegressions(); inventoryAndBudget();
        System.out.println("PASS: " + assertions + " crafting-tree assertions");
    }
}
