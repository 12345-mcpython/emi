"""Compile the real bookmark/group and material-model sources with isolated fixtures.

No Gradle, game, persistent user files, or build directory is used.
Usage: python tests/bookmark-groups/run.py --java-home PATH [--gson PATH]
Gson defaults to an existing non-sources Gson 2.10 JAR in the user's Gradle cache.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--java-home', type=Path, required=True)
parser.add_argument('--gson', type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
gson = args.gson
if gson is None:
    cache = Path.home() / '.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10'
    gson = next(cache.rglob('gson-2.10.jar'), None)
if gson is None or not gson.is_file():
    parser.error('Pass --gson with an existing Gson JAR; no dependencies are downloaded.')
java = args.java_home / 'bin'
java_suffix = '.exe' if (java / 'javac.exe').is_file() else ''

stubs = {
    'net/minecraft/util/Identifier.java': '''package net.minecraft.util;
public record Identifier(String value) { public String toString() { return value; } }''',
    'net/minecraft/item/Items.java': '''package net.minecraft.item;
public class Items { public static final String BARRIER = "barrier"; }''',
    'dev/emi/emi/api/recipe/EmiRecipeCategory.java': '''package dev.emi.emi.api.recipe;
public class EmiRecipeCategory {}''',
    'dev/emi/emi/api/widget/WidgetHolder.java': '''package dev.emi.emi.api.widget;
public interface WidgetHolder {}''',
    'dev/emi/emi/runtime/EmiFavorite.java': '''package dev.emi.emi.runtime;
import dev.emi.emi.api.recipe.*; import dev.emi.emi.api.stack.*;
public class EmiFavorite {
    final EmiIngredient stack; final EmiRecipe recipe;
    public EmiFavorite(EmiIngredient stack, EmiRecipe recipe) { this.stack = stack; this.recipe = recipe; }
    public EmiIngredient getStack() { return stack; }
    public EmiRecipe getRecipe() { return recipe; }
    public long getAmount() { return stack.getAmount(); }
}''',
    'dev/emi/emi/runtime/EmiFavorites.java': '''package dev.emi.emi.runtime;
import java.util.*;
public class EmiFavorites { public static List<EmiFavorite> favorites = new ArrayList<>(); }''',
    'dev/emi/emi/runtime/EmiPersistentData.java': '''package dev.emi.emi.runtime;
public class EmiPersistentData { public static void save() { EmiBookmarkGroups.save(); } }''',
    'dev/emi/emi/runtime/EmiLog.java': '''package dev.emi.emi.runtime;
public class EmiLog { public static void error(String text, Throwable error) { throw new AssertionError(text, error); } }''',
    'dev/emi/emi/config/SidebarType.java': '''package dev.emi.emi.config;
public enum SidebarType { FAVORITES }''',
    'dev/emi/emi/config/BookmarkViewMode.java': '''package dev.emi.emi.config;
public enum BookmarkViewMode {
    DEFAULT("default"), TODO("todo");
    final String name;
    BookmarkViewMode(String name) { this.name = name; }
    public String getName() { return name; }
    public BookmarkViewMode toggle() { return this == DEFAULT ? TODO : DEFAULT; }
    public static BookmarkViewMode byName(String name) {
        for (BookmarkViewMode mode : values()) if (mode.name.equalsIgnoreCase(name)) return mode;
        return DEFAULT;
    }
}''',
    'dev/emi/emi/config/EmiConfig.java': '''package dev.emi.emi.config;
public class EmiConfig { public static BookmarkViewMode bookmarkViewMode = BookmarkViewMode.DEFAULT; }''',
    'dev/emi/emi/screen/EmiScreenManager.java': '''package dev.emi.emi.screen;
public class EmiScreenManager { public static void repopulatePanels(dev.emi.emi.config.SidebarType type) {} }''',
    'dev/emi/emi/api/stack/serializer/EmiIngredientSerializer.java': '''package dev.emi.emi.api.stack.serializer;
import com.google.gson.*; import dev.emi.emi.api.stack.*;
public class EmiIngredientSerializer {
    public static JsonElement getSerialized(EmiIngredient ingredient) {
        JsonObject json = new JsonObject();
        JsonArray ids = new JsonArray();
        ingredient.getEmiStacks().forEach(stack -> ids.add(stack.id));
        json.add("ids", ids);
        json.addProperty("amount", ingredient.getAmount());
        return json;
    }
}''',
}

with tempfile.TemporaryDirectory(prefix='emi-bookmark-checks-') as temporary:
    source = Path(temporary) / 'src'

    def put(path, text):
        target = source / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding='utf-8')

    # Reuse the existing minimal game fixtures while compiling the production model.
    fixture_root = root / 'tests/crafting-tree/src'
    for fixture in fixture_root.rglob('*.java'):
        if fixture.name == 'CraftingTreeRegression.java':
            continue
        text = fixture.read_text(encoding='utf-8')
        if fixture.name == 'EmiRecipe.java':
            text = text.replace('public interface EmiRecipe {', '''public interface EmiRecipe {
    default net.minecraft.util.Identifier getId() { return null; }
    default EmiRecipeCategory getCategory() { return null; }
    default java.util.List<dev.emi.emi.api.stack.EmiIngredient> getCatalysts() { return java.util.List.of(); }
    default boolean supportsRecipeTree() { return !getInputs().isEmpty() && !getOutputs().isEmpty(); }
    default int getDisplayWidth() { return 0; }
    default int getDisplayHeight() { return 0; }
    default void addWidgets(dev.emi.emi.api.widget.WidgetHolder widgets) {}''')
        if fixture.name == 'EmiStack.java':
            text = text.replace('public static final EmiStack EMPTY',
                                'public static EmiStack of(Object object) { return new EmiStack(object.toString(), 1); } public static final EmiStack EMPTY')
        put(fixture.relative_to(fixture_root), text)
    production = root / 'xplat/src/main/java'
    for name in ('MaterialNode', 'MaterialTree', 'TreeCost', 'CraftingTreeModel',
                 'FlatMaterialCost', 'ChanceMaterialCost', 'ChanceState', 'FoldState', 'ProgressState'):
        path = Path(f'dev/emi/emi/bom/{name}.java')
        put(path, (production / path).read_text(encoding='utf-8'))
    path = Path('dev/emi/emi/runtime/EmiBookmarkGroups.java')
    put(path, (production / path).read_text(encoding='utf-8'))
    for path, text in stubs.items():
        put(path, text)
    put('dev/emi/emi/runtime/BookmarkGroupChecks.java',
        (Path(__file__).parent / 'BookmarkGroupChecks.java').read_text(encoding='utf-8'))
    classes = Path(temporary) / 'classes'
    subprocess.run([str(java / f'javac{java_suffix}'), '--release', '17', '-encoding', 'UTF-8',
                    '-cp', str(gson), '-d', str(classes), *map(str, source.rglob('*.java'))], check=True)
    import os
    subprocess.run([str(java / f'java{java_suffix}'), '-ea', '-cp', f'{classes}{os.pathsep}{gson}',
                    'dev.emi.emi.runtime.BookmarkGroupChecks'], check=True)
