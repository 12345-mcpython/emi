"""Run real CraftingPlans sources against isolated recipe/stack fixtures (no game launch).
Usage: python scripts/test_crafting_plans.py --java-home PATH --gson PATH
"""
import argparse
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--java-home', type=Path, required=True)
parser.add_argument('--gson', type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
stubs = {
'dev/emi/emi/EmiPort.java': '''package dev.emi.emi; public class EmiPort { public static String id(String id) { if (!id.contains(":")) throw new IllegalArgumentException(); return id; } }''',
'dev/emi/emi/api/EmiApi.java': '''package dev.emi.emi.api;
import java.util.*; import dev.emi.emi.api.recipe.EmiRecipe;
public class EmiApi { public static final Map<String, EmiRecipe> recipes = new HashMap<>(); public static EmiApi getRecipeManager() { return new EmiApi(); } public EmiRecipe getRecipe(String id) { return recipes.get(id); } }''',
'dev/emi/emi/api/stack/EmiIngredient.java': '''package dev.emi.emi.api.stack;
import java.util.*; public interface EmiIngredient { EmiIngredient copy(); EmiIngredient setAmount(long a); EmiIngredient setChance(float c); boolean isEmpty(); List<EmiStack> getEmiStacks(); }''',
'dev/emi/emi/api/stack/EmiStack.java': '''package dev.emi.emi.api.stack;
import java.util.*; public class EmiStack implements EmiIngredient { public final String id; public long amount=1; public float chance=1; public EmiStack(String id) { this.id=id; } public EmiStack copy() { EmiStack s=new EmiStack(id); s.amount=amount; s.chance=chance; return s; } public EmiStack setAmount(long a) { amount=a; return this; } public EmiStack setChance(float c) { chance=c; return this; } public boolean isEmpty() { return id.isEmpty(); } public List<EmiStack> getEmiStacks() { return List.of(this); } public Name getName() { return new Name(id); } public record Name(String value) { public String getString() { return value; } } public boolean equals(Object o) { return o instanceof EmiStack s && id.equals(s.id); } public int hashCode() { return id.hashCode(); } }''',
'dev/emi/emi/api/stack/serializer/EmiIngredientSerializer.java': '''package dev.emi.emi.api.stack.serializer;
import com.google.gson.*; import dev.emi.emi.api.stack.*; public class EmiIngredientSerializer { public static JsonElement getSerialized(EmiIngredient i) { String id=((EmiStack)i).id; return id.startsWith("{") ? JsonParser.parseString(id) : new JsonPrimitive(id); } public static EmiIngredient getDeserialized(JsonElement j) { return new EmiStack(j==null || j.isJsonNull() ? "" : j.getAsString()); } }''',
'dev/emi/emi/api/recipe/EmiRecipe.java': '''package dev.emi.emi.api.recipe; import java.util.*; import dev.emi.emi.api.stack.*; public interface EmiRecipe { String getId(); List<EmiStack> getOutputs(); default List<EmiIngredient> getInputs() { return List.of(); } }''',
'dev/emi/emi/api/recipe/EmiResolutionRecipe.java': '''package dev.emi.emi.api.recipe; import java.util.*; import dev.emi.emi.api.stack.*; public class EmiResolutionRecipe implements EmiRecipe { public final EmiIngredient ingredient; public final EmiStack stack; public EmiResolutionRecipe(EmiIngredient i, EmiStack s) { ingredient=i; stack=s; } public EmiIngredient ingredient() { return ingredient; } public EmiStack stack() { return stack; } public String getId() { return null; } public List<EmiStack> getOutputs() { return List.of(stack); } }''',
'dev/emi/emi/runtime/EmiLog.java': '''package dev.emi.emi.runtime; public class EmiLog { public static void error(String s, Throwable t) {} public static void warn(String s) {} }''',
'dev/emi/emi/runtime/EmiPersistentData.java': '''package dev.emi.emi.runtime; import dev.emi.emi.bom.CraftingPlans; public class EmiPersistentData { public static int saves; public static void save() { if (++saves>10000) throw new AssertionError("recursive save"); CraftingPlans.save(); } }''',
'dev/emi/emi/bom/BoM.java': '''package dev.emi.emi.bom; import java.util.*; import dev.emi.emi.api.stack.*; import dev.emi.emi.api.recipe.*; public class BoM { public static MaterialTree tree; public static boolean craftingMode; public static final Map<EmiIngredient,EmiRecipe> defaults=new HashMap<>(); public static void setGoal(EmiRecipe r) { CraftingPlans.setGoal(r); } }''',
'dev/emi/emi/bom/MaterialNode.java': '''package dev.emi.emi.bom; import java.util.*; import dev.emi.emi.api.stack.*; import dev.emi.emi.api.recipe.*; public class MaterialNode { public EmiIngredient ingredient; public EmiRecipe recipe; public List<MaterialNode> children; public long amount=1,divisor=1; public boolean truncated; public MaterialNode(EmiIngredient i) { ingredient=i; } public void defineRecipe(EmiRecipe r) { recipe=r; children=null; divisor=r==null ? 1 : r.getOutputs().get(0).amount; } }''',
'dev/emi/emi/bom/MaterialTree.java': '''package dev.emi.emi.bom; import java.util.*; import dev.emi.emi.api.stack.*; import dev.emi.emi.api.recipe.*; public class MaterialTree { public MaterialNode goal; public long batches=1; public final Map<EmiIngredient,EmiRecipe> resolutions=new HashMap<>(); public MaterialTree(EmiRecipe r) { goal=new MaterialNode(r.getOutputs().get(0)); goal.defineRecipe(r); } public EmiRecipe getRecipe(EmiIngredient i) { return resolutions.containsKey(i) ? resolutions.get(i) : BoM.defaults.get(i); } public void recalculate() {} }''',
'dev/emi/emi/bom/PlanChecks.java': '''package dev.emi.emi.bom;
import com.google.gson.*; import java.util.*; import dev.emi.emi.api.*; import dev.emi.emi.api.recipe.*; import dev.emi.emi.api.stack.*; import dev.emi.emi.runtime.*;
public class PlanChecks {
 static int assertions; static void check(boolean value,String text) { assertions++; if(!value) throw new AssertionError(text); }
 record Recipe(String id, EmiStack output) implements EmiRecipe { public String getId(){return id;} public List<EmiStack> getOutputs(){return List.of(output);} }
 static Recipe recipe(String id,String output){Recipe r=new Recipe(id,new EmiStack(output));EmiApi.recipes.put(id,r);return r;}
 public static void main(String[] args) {
  Recipe root=recipe("test:root","product"), producer=recipe("test:producer","part");
  CraftingPlans.load(null); BoM.setGoal(root); BoM.tree.batches=7; BoM.tree.goal.amount=4; CraftingPlans.capture();
  check(CraftingPlans.all().isEmpty() && CraftingPlans.current()==null,"temporary tree stays temporary");
  check(!CraftingPlans.exportCurrent().isEmpty(),"temporary tree export");
  EmiStack part=new EmiStack("part"), raw=new EmiStack("raw");
  MaterialNode branch=new MaterialNode(part); branch.defineRecipe(producer);
  MaterialNode leaf=new MaterialNode(raw); branch.children=List.of(leaf); BoM.tree.goal.children=List.of(branch);
  BoM.defaults.put(part,producer);
  // A repeated root recipe cut by cycle detection is never a null override.
  MaterialNode cycle=new MaterialNode(root.output()); BoM.defaults.put(root.output(),root);
  EmiStack budgetLimited=new EmiStack("budget-limited");
  MaterialNode truncated=new MaterialNode(budgetLimited); truncated.truncated=true;
  branch.children=List.of(leaf,cycle,truncated);
  EmiStack stop=new EmiStack("stop"), tag=new EmiStack("tag");
  BoM.tree.resolutions.put(stop,null); BoM.tree.resolutions.put(tag,new EmiResolutionRecipe(tag,tag));
  CraftingPlans.saveCurrent(); CraftingPlan saved=CraftingPlans.current();
  saved.name="First"; saved.folded.add(CraftingPlans.key(part)); saved.useInventory=false;
  int saves=EmiPersistentData.saves; CraftingPlans.capture(); check(saves==EmiPersistentData.saves,"capture does not save");
  JsonObject json=CraftingPlans.save(); String exported=CraftingPlans.exportCurrent();
  check(CraftingPlans.key(part).equals(CraftingPlans.key(part.copy().setAmount(42).setChance(.2f))),"key ignores amount/chance");
  check(CraftingPlans.key(new EmiStack("{\\\"b\\\":2,\\\"a\\\":1}")).equals(CraftingPlans.key(new EmiStack("{\\\"a\\\":1,\\\"b\\\":2}"))),"key canonicalizes object property order");
  check(!CraftingPlans.key(new EmiStack("{\\\"nbt\\\":[1,2]}")).equals(CraftingPlans.key(new EmiStack("{\\\"nbt\\\":[2,1]}"))),"key preserves NBT array order");
  BoM.setGoal(recipe("test:other","other")); check(CraftingPlans.current()==null,"different goal clears current");
  check(saved.batches==7,"switch captures batches"); BoM.setGoal(root); check(CraftingPlans.current()==saved && BoM.tree.batches==7,"same goal restores plan");
  Recipe freshRoot=recipe("test:root","product"), freshProducer=recipe("test:producer","part");
  BoM.defaults.clear(); CraftingPlans.load(json);
  check(BoM.tree.goal.recipe==freshRoot && BoM.tree.goal.recipe!=root,"reload rebinds root recipe");
  check(BoM.tree.goal.amount==4,"goal single batch output survives normalized material key");
  check(BoM.tree.resolutions.get(part)==freshProducer,"reload freezes producer and rebinds recipe");
  check(BoM.tree.resolutions.containsKey(raw) && BoM.tree.resolutions.get(raw)==null,"raw material termination survives default changes");
  check(BoM.tree.resolutions.get(freshRoot.output())==freshRoot,"cycle cut does not terminate root");
  check(!BoM.tree.resolutions.containsKey(budgetLimited),"budget-truncated node is not persisted as termination");
  check(BoM.tree.resolutions.containsKey(stop) && BoM.tree.resolutions.get(stop)==null,"explicit null survives");
  check(BoM.tree.resolutions.get(tag) instanceof EmiResolutionRecipe,"ingredient resolution survives");
  check(!CraftingPlans.current().useInventory && CraftingPlans.current().folded.contains(CraftingPlans.key(part)),"view settings survive");
  JsonObject terminated=JsonParser.parseString(exported).getAsJsonObject();
  for(JsonElement entry:terminated.getAsJsonArray("resolutions")) { JsonObject choice=entry.getAsJsonObject(); if(choice.get("ingredient").getAsString().equals("product")) choice.add("recipe",JsonNull.INSTANCE); }
  check(CraftingPlans.importPlan(terminated.toString()) && BoM.tree.goal.recipe==null && BoM.tree.goal.amount==4,"root termination retains goal amount");
  CraftingPlans.load(json);
  JsonArray entries=json.getAsJsonArray("plans"); entries.add(JsonNull.INSTANCE); entries.add(entries.get(0).deepCopy());
  CraftingPlans.load(json); check(CraftingPlans.all().size()==2,"bad plan independently skipped");
  JsonObject damaged=JsonParser.parseString(exported).getAsJsonObject(); damaged.getAsJsonArray("resolutions").add(JsonNull.INSTANCE);
  JsonObject recovery=new JsonObject(); JsonArray plans=new JsonArray(); plans.add(damaged); recovery.add("plans",plans); recovery.addProperty("current",0);
  CraftingPlans.load(recovery); check(BoM.tree.resolutions.containsKey(stop),"bad resolution independently skipped");
  int count=CraftingPlans.all().size(); MaterialTree before=BoM.tree;
  for(String batch : List.of("0", "-1", "1.5", "9223372036854775808", "\\\"7\\\"")) { JsonObject invalid=JsonParser.parseString(exported).getAsJsonObject(); invalid.add("batches",JsonParser.parseString(batch)); check(!CraftingPlans.importPlan(invalid.toString()),"invalid batch rejected: "+batch); }
  JsonObject badBoolean=JsonParser.parseString(exported).getAsJsonObject(); badBoolean.addProperty("use_inventory","invalid"); check(!CraftingPlans.importPlan(badBoolean.toString()),"nonboolean inventory rejected");
  check(!CraftingPlans.importPlan(damaged.toString()),"bad import rejected strictly");
  check(!CraftingPlans.importPlan("[") && !CraftingPlans.importPlan(" ".repeat(CraftingPlans.MAX_IMPORT_LENGTH+1)),"malformed and oversized imports rejected");
  check(CraftingPlans.all().size()==count && BoM.tree==before,"bad import has no effects");
  check(CraftingPlans.importPlan(exported),"valid import selected");
  CraftingPlans.rename("Renamed"); check(CraftingPlans.current().name.equals("Renamed"),"rename");
  CraftingPlans.remove(CraftingPlans.all().size()-1); check(CraftingPlans.current()==null && BoM.tree!=null,"remove detaches but retains temporary tree");
  CraftingPlans.load(new JsonObject()); check(CraftingPlans.all().isEmpty() && BoM.tree==null,"legacy JSON clears stale trees");
  CraftingPlans.load(json); CraftingPlans.open(1); check(CraftingPlans.current()==CraftingPlans.all().get(1),"open by index");
  while(CraftingPlans.all().size()<CraftingPlans.MAX_PLANS) check(CraftingPlans.importPlan(exported),"fill import limit");
  check(!CraftingPlans.importPlan(exported),"plan limit enforced");
  try { CraftingPlans.all().clear(); throw new AssertionError("mutable all"); } catch(UnsupportedOperationException expected) {}
  System.out.println("CraftingPlans checks passed: "+assertions);
 }
}'''
}
with tempfile.TemporaryDirectory(prefix='emi-plan-checks-') as directory:
    temporary = Path(directory)
    source = temporary / 'src'
    for name, text in stubs.items():
        target = source / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding='utf-8')
    for name in ('CraftingPlan', 'CraftingPlans'):
        target = source / f'dev/emi/emi/bom/{name}.java'
        target.write_text((root / f'xplat/src/main/java/dev/emi/emi/bom/{name}.java').read_text(encoding='utf-8'), encoding='utf-8')
    classes = temporary / 'classes'
    subprocess.run([str(args.java_home / 'bin/javac.exe'), '--release', '21', '-encoding', 'UTF-8', '-cp', str(args.gson), '-d', str(classes), *map(str, source.rglob('*.java'))], check=True)
    subprocess.run([str(args.java_home / 'bin/java.exe'), '-cp', f'{classes};{args.gson}', 'dev.emi.emi.bom.PlanChecks'], check=True)

    # Compile the real persistent-data lifecycle separately, so file I/O and early
    # returns are exercised rather than hidden behind the plan suite's save stub.
    lifecycle = {
        'net/minecraft/util/GsonHelper.java': '''package net.minecraft.util; import com.google.gson.*; public class GsonHelper { public static boolean isArrayNode(JsonObject j,String k){return j.has(k)&&j.get(k).isJsonArray();} public static boolean isObjectNode(JsonObject j,String k){return j.has(k)&&j.get(k).isJsonObject();} public static JsonArray getAsJsonArray(JsonObject j,String k){return j.getAsJsonArray(k);} public static JsonObject getAsJsonObject(JsonObject j,String k){return j.getAsJsonObject(k);} }''',
        'dev/emi/emi/bom/BoM.java': '''package dev.emi.emi.bom; import java.util.*; import com.google.gson.*; public class BoM { public static final Map<String,Object> addedRecipes=new HashMap<>(); public static final Set<Object> disabledRecipes=new HashSet<>(); public static JsonObject saveAdded(){return new JsonObject();} public static void loadAdded(JsonObject j){addedRecipes.put("fresh",new Object());} }''',
        'dev/emi/emi/bom/CraftingPlans.java': '''package dev.emi.emi.bom; import com.google.gson.*; public class CraftingPlans { public static Object tree; public static int restores; public static JsonObject save(){return new JsonObject();} public static void load(JsonObject j){tree=null; if(j!=null){if(BoM.addedRecipes.containsKey("stale"))throw new AssertionError("stale recipes at plan restore");tree=new Object();restores++;}} }''',
        'dev/emi/emi/runtime/EmiFavorites.java': '''package dev.emi.emi.runtime; import com.google.gson.*; public class EmiFavorites { public static JsonArray save(){return new JsonArray();} public static void load(JsonArray j){} }''',
        'dev/emi/emi/runtime/EmiBookmarkGroups.java': '''package dev.emi.emi.runtime; import com.google.gson.*; public class EmiBookmarkGroups { public static JsonObject save(){return new JsonObject();} public static void load(JsonObject j){} }''',
        'dev/emi/emi/runtime/EmiHidden.java': '''package dev.emi.emi.runtime; import com.google.gson.*; public class EmiHidden { public static JsonArray save(){return new JsonArray();} public static void load(JsonArray j){} }''',
        'dev/emi/emi/runtime/EmiSidebars.java': '''package dev.emi.emi.runtime; import com.google.gson.*; public class EmiSidebars { public static void save(JsonObject j){} public static void load(JsonObject j){} }''',
        'dev/emi/emi/runtime/EmiLog.java': stubs['dev/emi/emi/runtime/EmiLog.java'],
        'dev/emi/emi/runtime/LifecycleChecks.java': '''package dev.emi.emi.runtime;
import java.nio.file.*; import dev.emi.emi.bom.*;
public class LifecycleChecks {
 static int checks; static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static void stale(){BoM.addedRecipes.put("stale",new Object());BoM.disabledRecipes.add(new Object());CraftingPlans.tree=new Object();}
 public static void main(String[] args)throws Exception{
  stale();EmiPersistentData.load();check(BoM.addedRecipes.isEmpty()&&BoM.disabledRecipes.isEmpty()&&CraftingPlans.tree==null,"missing file clears stale references");
  for(String json:new String[]{"{}","null","[","{\\\"favorites\\\":[]}"}){Files.writeString(Path.of("emi.json"),json);stale();EmiPersistentData.load();check(BoM.addedRecipes.isEmpty()&&BoM.disabledRecipes.isEmpty()&&CraftingPlans.tree==null,"legacy or malformed clears stale references");}
  Files.writeString(Path.of("emi.json"),"{\\\"recipe_defaults\\\":{},\\\"crafting_plans\\\":{}}");stale();EmiPersistentData.load();check(BoM.addedRecipes.containsKey("fresh")&&!BoM.addedRecipes.containsKey("stale")&&CraftingPlans.tree!=null,"defaults rebind before plan restore");
  EmiPersistentData.save();check(Files.readString(Path.of("emi.json")).contains("crafting_plans"),"save includes plans");
  System.out.println("Persistent lifecycle checks passed: "+checks);
 }
}'''
    }
    lifecycle_source = temporary / 'lifecycle-src'
    for name, text in lifecycle.items():
        target = lifecycle_source / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding='utf-8')
    runtime_target = lifecycle_source / 'dev/emi/emi/runtime/EmiPersistentData.java'
    runtime_target.write_text((root / 'xplat/src/main/java/dev/emi/emi/runtime/EmiPersistentData.java').read_text(encoding='utf-8'), encoding='utf-8')
    lifecycle_classes = temporary / 'lifecycle-classes'
    subprocess.run([str(args.java_home / 'bin/javac.exe'), '--release', '21', '-encoding', 'UTF-8', '-cp', str(args.gson), '-d', str(lifecycle_classes), *map(str, lifecycle_source.rglob('*.java'))], check=True)
    subprocess.run([str(args.java_home / 'bin/java.exe'), '-cp', f'{lifecycle_classes};{args.gson}', 'dev.emi.emi.runtime.LifecycleChecks'], cwd=temporary, check=True)
