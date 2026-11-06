"""Compile/test real material-transfer runtime with isolated fixtures, without Gradle."""
import argparse
import ast
from pathlib import Path
import subprocess
import tempfile

p = argparse.ArgumentParser()
p.add_argument('--java-home', type=Path, required=True)
a = p.parse_args()
root = Path(__file__).resolve().parents[2]
# Reuse fixture definitions, never execute the other test runner.
module = ast.parse((root/'tests/bookmark-crafting/run.py').read_text(encoding='utf-8'))
stubs = next(ast.literal_eval(n.value) for n in module.body if isinstance(n, ast.Assign) and any(isinstance(t, ast.Name) and t.id == 'stubs' for t in n.targets))
stubs['net/minecraft/item/ItemStack.java'] = stubs['net/minecraft/item/ItemStack.java'].replace('public ItemStack copy()', 'public void decrement(int n){count-=n;} public static boolean areEqual(ItemStack a,ItemStack b){return a.isEmpty()&&b.isEmpty()||a.id.equals(b.id)&&a.count==b.count;} public ItemStack copy()')
stubs['net/minecraft/screen/slot/Slot.java'] = stubs['net/minecraft/screen/slot/Slot.java'].replace('public int getIndex()', 'public boolean canInsert(ItemStack s){return true;} public int getMaxItemCount(ItemStack s){return 64;} public int getIndex()')
stubs['net/minecraft/screen/GenericContainerScreenHandler.java'] = '''package net.minecraft.screen; import net.minecraft.client.MinecraftClient.Inventory; public class GenericContainerScreenHandler extends ScreenHandler {public Inventory inventory=new Inventory();public int getRows(){return 3;}public Inventory getInventory(){return inventory;}}'''
stubs['net/minecraft/screen/slot/SlotActionType.java'] = 'package net.minecraft.screen.slot; public enum SlotActionType {PICKUP}'
stubs['net/minecraft/client/MinecraftClient.java'] = stubs['net/minecraft/client/MinecraftClient.java'].replace('public Object currentScreen,world=new Object(),interactionManager=new Object();','public Object currentScreen,world=new Object();public Interaction interactionManager=new Interaction();public static class Interaction {public void clickSlot(int id,int s,int b,net.minecraft.screen.slot.SlotActionType t,Player p){}}')
stubs['dev/emi/emi/api/stack/ItemEmiStack.java'] = '''package dev.emi.emi.api.stack; public class ItemEmiStack extends EmiStack {public ItemEmiStack(String s){super(s);}}'''
stubs['dev/emi/emi/api/stack/EmiStack.java'] = stubs['dev/emi/emi/api/stack/EmiStack.java'].replace('new EmiStack(s.id)', 'new ItemEmiStack(s.id)').replace('public boolean isEqual(', 'public EmiStack copy(){EmiStack s=new ItemEmiStack(id);s.amount=amount;return s;} public boolean isEqual(')
stubs['dev/emi/emi/api/recipe/EmiPlayerInventory.java'] = '''package dev.emi.emi.api.recipe; import java.util.*;import dev.emi.emi.api.stack.*;public class EmiPlayerInventory {public EmiPlayerInventory(List<EmiStack> s){}}'''
stubs['dev/emi/emi/runtime/EmiBookmarkGroups.java'] = '''package dev.emi.emi.runtime; import java.util.*; import dev.emi.emi.api.stack.*;import dev.emi.emi.bom.*; public class EmiBookmarkGroups {public static class Group{}public static class Row {public Object recipe;public long remainingAmount;public EmiIngredient ingredient;}public static MaterialTree tree(Group g){return null;}public static List<Row> executionRows(Group g,Object i){return List.of();}}'''
stubs['dev/emi/emi/bom/MaterialTree.java'] = 'package dev.emi.emi.bom;public class MaterialTree {}'
stubs['dev/emi/emi/bom/FlatMaterialCost.java'] = 'package dev.emi.emi.bom;import dev.emi.emi.api.stack.*;public class FlatMaterialCost {public EmiIngredient ingredient;public long amount;}'
stubs['dev/emi/emi/bom/CraftingTreeModel.java'] = '''package dev.emi.emi.bom; import java.util.*;import dev.emi.emi.api.recipe.*;public class CraftingTreeModel {public List<FlatMaterialCost> missing=List.of(),available=List.of();public CraftingTreeModel(MaterialTree t,EmiPlayerInventory i){}}'''
# The unrelated crafting fixtures depend on intentionally absent APIs.
keep = ['net/minecraft/', 'dev/emi/emi/api/stack/', 'dev/emi/emi/bom/']
stubs = {k:v for k,v in stubs.items() if any(k.startswith(prefix) for prefix in keep) or k in ['dev/emi/emi/api/recipe/EmiPlayerInventory.java','dev/emi/emi/runtime/EmiBookmarkGroups.java','dev/emi/emi/runtime/EmiLog.java']}
stubs['dev/emi/emi/platform/EmiClient.java'] = 'package dev.emi.emi.platform; public class EmiClient {public static boolean onServer;}'
stubs['dev/emi/emi/network/EmiNetwork.java'] = 'package dev.emi.emi.network; public class EmiNetwork {public static void sendToServer(FillRecipeC2SPacket p){}}'
stubs['dev/emi/emi/network/FillRecipeC2SPacket.java'] = 'package dev.emi.emi.network; import java.util.*;import net.minecraft.screen.*;import net.minecraft.screen.slot.*;import net.minecraft.item.*; public class FillRecipeC2SPacket {public FillRecipeC2SPacket(ScreenHandler h,int a,List<Slot>s,List<Slot>d,Slot o,List<ItemStack>st){}}'
with tempfile.TemporaryDirectory(prefix='emi-transfer-') as directory:
    src = Path(directory)/'src'
    for name,text in stubs.items():
        target=src/name
        target.parent.mkdir(parents=True,exist_ok=True)
        target.write_text(text,encoding='utf-8')
    files = list(src.rglob('*.java')) + [root/'xplat/src/main/java/dev/emi/emi/runtime/BookmarkMaterialTransfer.java', root/'tests/bookmark-transfer/src/dev/emi/emi/runtime/BookmarkMaterialTransferChecks.java']
    classes=Path(directory)/'classes'
    subprocess.run([str(a.java_home/'bin/javac.exe'),'--release','17','-encoding','UTF-8','-d',str(classes),*map(str,files)],check=True)
    subprocess.run([str(a.java_home/'bin/java.exe'),'-ea','-cp',str(classes),'dev.emi.emi.runtime.BookmarkMaterialTransferChecks'],check=True)
