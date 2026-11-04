param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$out = Join-Path $root 'xplat/build/crafting-tree-regression'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$javac = if ($JavaHome) { Join-Path $JavaHome 'bin/javac.exe' } else { 'javac' }
$java = if ($JavaHome) { Join-Path $JavaHome 'bin/java.exe' } else { 'java' }
$sources = @(Get-ChildItem (Join-Path $PSScriptRoot 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
foreach ($name in @('MaterialNode', 'MaterialTree', 'TreeCost', 'CraftingTreeModel', 'FlatMaterialCost', 'ChanceMaterialCost', 'ChanceState', 'FoldState', 'ProgressState')) {
    $sources += Join-Path $root "xplat/src/main/java/dev/emi/emi/bom/$name.java"
}
& $javac -encoding UTF-8 -d $out @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }
& $java -ea -cp $out dev.emi.emi.bom.CraftingTreeRegression
if ($LASTEXITCODE -ne 0) { throw "regression failed: $LASTEXITCODE" }
