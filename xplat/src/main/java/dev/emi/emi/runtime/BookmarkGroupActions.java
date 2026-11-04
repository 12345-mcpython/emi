package dev.emi.emi.runtime;

import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.bom.MaterialTree;
import dev.emi.emi.runtime.EmiBookmarkGroups.Group;
import dev.emi.emi.screen.BoMScreen;
import net.minecraft.client.MinecraftClient;

/** Entry points shared by the native bookmark sidebar and its group shortcuts. */
public final class BookmarkGroupActions {
    private BookmarkGroupActions() {}

    public static boolean openTree(Group group) {
        if (group == null) return false;
        MaterialTree tree = EmiBookmarkGroups.tree(group);
        if (tree == null) return false;
        var client = MinecraftClient.getInstance();
        String name = "";
        if (!group.anchors().isEmpty()) {
            EmiIngredient ingredient = group.anchors().get(0).getStack();
            if (!ingredient.getEmiStacks().isEmpty()) name = ingredient.getEmiStacks().get(0).getName().getString();
        }
        client.setScreen(new BoMScreen(client.currentScreen, tree, name));
        return true;
    }
}
