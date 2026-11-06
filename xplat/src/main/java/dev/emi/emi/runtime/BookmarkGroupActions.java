package dev.emi.emi.runtime;

import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.bom.MaterialTree;
import dev.emi.emi.runtime.EmiBookmarkGroups.Group;
import dev.emi.emi.screen.BoMScreen;
import net.minecraft.client.Minecraft;

/** Entry points shared by the native bookmark sidebar and its group shortcuts. */
public final class BookmarkGroupActions {
    private BookmarkGroupActions() {}

    public static boolean openTree(Group group) {
        if (group == null) return false;
        MaterialTree tree = EmiBookmarkGroups.tree(group);
        if (tree == null) return false;
        var client = Minecraft.getInstance();
        String name = "";
        if (!group.anchors().isEmpty()) {
            EmiIngredient ingredient = group.anchors().getFirst().getStack();
            if (!ingredient.getEmiStacks().isEmpty()) name = ingredient.getEmiStacks().getFirst().getName().getString();
        }
        client.setScreen(new BoMScreen(client.screen, tree, name));
        return true;
    }
}
