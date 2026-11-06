# 可选 Fabric 客户端合成树与原生收藏侧栏视觉 smoke

该测试仅用于 Minecraft 1.21.1/Fabric 开发客户端，使用项目现有的 Fabric API、EMI 及本地 Minecraft 资源。没有修改生产代码或任何现有 Gradle 文件，也不会在普通 `build` 中自动编译、加载或运行。测试目录不是发布模组的一部分。

## 启动

在仓库根目录使用项目所需的 Java 21 环境执行：

```powershell
.\gradlew.bat --offline -I tests/crafting-tree-smoke/init.gradle :fabric:runClient
```

仅编译可选测试 sourceSet（不启动客户端）：

```powershell
.\gradlew.bat --offline -I tests/crafting-tree-smoke/init.gradle :fabric:craftingTreeSmokeClasses
```

init 脚本仅通过显式 `-I` 生效，创建 `craftingTreeSmoke` sourceSet 与 `emi_tree_smoke` Loom mod，并给 `runClient` 加入 `-Demi.craftingTreeSmoke=true` gate。缺少该系统属性时 entrypoint 不执行任何测试。脚本让客户端使用隔离目录 `fabric/run/crafting-tree-smoke`（相对 Fabric 子项目），防止触碰普通开发运行目录的设置或保存计划。窗口请求 1280×800，JVM 最大堆为 2 GiB。

若离线缓存缺少启动资源、原生库、依赖或资源索引，请记录该构建/运行障碍；不要把未能启动称为 smoke 通过。不要求为了本测试强行下载资源。需要可用图形桌面和 OpenGL，不能当作 headless 单元测试。新增收藏侧栏阶段会用 `java.awt.Robot` 真实按住 Ctrl/Alt；入口显式关闭 AWT headless 标记。运行期间使用带可见标题栏的窗口且不要手动输入；fixture 根据 GLFW window position/frame top 计算本窗口标题栏坐标并用 Robot 实际点击，避免 Win32 只接收 focus request 却不切前台。随后跨正常 render/tick 等待 GLFW_FOCUSED，才发送真实 keyPress，再等待 `EmiInput` 观察到修饰键；整个等待最多两秒，不 busy sleep。按键跨 tick 保持以便 GLFW 轮询，成功或异常路径都释放测试按键。桌面禁止 Robot、窗口无法聚焦或 GLFW 未观察到按键时，测试明确失败，不跳过修饰键断言。

### 显式 model-modifiers 模式与证据限制

2026年11月3日实际运行 `1793711344599` 的严格模式在真实窗口 `GLFW_FOCUSED=true`、Robot 已发送 keyPress 后，仍未由 GLFW/EmiInput 观察到 Ctrl，按两秒上限明确失败。此前运行已验证正常 framebuffer 的 todo/compact 绿色和 crafting-off 灰色截图。该桌面输入失败不代表 Ctrl/Alt UI 已通过。

若需要继续验证剩余正常 UI、模型和截图阶段，可显式执行：

```powershell
.\gradlew.bat --offline '-Demi.craftingTreeSmoke.modelModifiers=true' -I tests/crafting-tree-smoke/init.gradle :fabric:runClient
```

init 脚本仅转发该系统属性，默认 false。此模式不创建 Robot、不模拟修饰键：数量通过 `EmiBookmarkGroups.adjust`，整体折叠/展开通过 `toggleCollapsed`；普通 manager 点击、拖拽、无修饰键分页滚动、Synthetic 原对象 hover、save/load 和真实正常渲染截图仍执行。成功只写 `PASS-MODEL-MODIFIERS.txt`，内容明确 `CtrlAltUI UNVERIFIED`，绝不生成普通 `PASS.txt`。这是不同范围的证据，不能声称真实 Ctrl-scroll、Alt-click 或 Alt 根节点展开已验证。严格默认模式保持原物理输入断言与普通 PASS 条件。

## 流程与断言

入口等待 `TitleScreen` 且加载 overlay 已关闭，随后在物品 bootstrap 完成、无世界无玩家的真实客户端中创建实现 `EmiRecipe` 的测试配方。类别为此时创建的独立 `EmiRecipeCategory`（ID `emi_tree_smoke:crafting`，图标为真实工作台），不依赖标题页尚未初始化的 EMI 默认类别；物品全部为真实 `Items`。关系是用于测试的合成关系，并非原版真实配方：

- 目标工作台需要一个箱子与四根木棍。
- 箱子需要八块橡木木板，四根木棍需要两块橡木木板。
- 四块木板需要一根橡木原木。

测试调用 `BoM.setGoal` 并在 `BoM.tree.resolutions` 显式指定生产配方与原木终止节点，以三批构造稳定图形。独立模型断言五个合并节点、两条流入共享木板节点的连接。它打开真实 `new BoMScreen(null)`，不会创建世界或伪造玩家库存，也不会保存计划。

画面打开后先等待十个 tick，再按阶段调用真实 UI 的 `mouseClicked`、`charTyped`、`keyPressed`、`mouseReleased`、`mouseDragged` 与 `mouseScrolled`：

1. 检查未保存的计划名称草稿在刷新与两次 resize 后仍保留，检查首次反向搜索定位最后一个匹配，然后截取原始完整展开画面。
2. 点击搜索框、逐字符输入资源 ID、按 Enter 定位并截图。
3. 循环折叠按钮至折叠分支状态并截图，随后展开和 fit。
4. 无玩家时开启库存、核对按钮标签和 `BoM.craftingMode`、刷新快照并截图，再关闭。
5. 执行缩放、平移及 fit，并截图。
6. 点击“填入1批”（没有旧容器的安全空操作）和真实“导出图片”按钮，等待完整布局 PNG 写入；随后再次截取客户端帧缓冲以检查截图后仍能继续渲染。

7. 使用132种真实物品构造深依赖链，触发128层展开限制，检查截断节点和外部材料需求，定位并实际渲染截断 tooltip，保存第七张截图。

打开树之前还使用空配方 Map 的独立 `RecipeScreen` 检查：原页面设置选择目标后，嵌套页面目标为 null，返回原实例后恢复目标。

按钮按当前语言资源的译文定位，不调用私有 action，也不绕过按钮鼠标事件。搜索框以真实子控件定位。首次反向搜索因 Shift 由 GLFW 物理状态轮询而采用反射调用方向入口，并读取搜索游标断言；截断 tooltip 通过反射确认定位的节点确实被截断，再调用真实渲染。所有客户端画面使用 Minecraft `ScreenshotRecorder.takeScreenshot(client.getFramebuffer())` 读回并在测试线程保存 PNG，同时关闭 `NativeImage`。生产 UI 的导出仍走其真实异步截图路径。

## 真实 RecipeScreen 左侧收藏栏

树与截断 tooltip 阶段完成后，测试打开真实 `RecipeScreen(null, Map.of(category, recipes))`，沿用独立类别和真实 `Items`。测试临时安装仅含这四个合成配方的 `EmiRecipeManager`，给 `BoM.defaultRecipes` 指定默认子配方，调用 `EmiBookmarkGroups.addRecipe(root, true)`；断言恰好产生四个 full-recipe anchors、同组、默认 todo+crafting。不会把标题页尚未注册的原版 EMI 类别或世界配方当作已加载。标题页也未注册物品 serializer，因此 fixture 通过真实 `EmiInitRegistryImpl.addIngredientSerializer(ItemEmiStack.class, new ItemEmiStackSerializer())` 临时注册原版 EMI 物品序列化器；否则 `key(anchor)` 为空会把不同配方错误去重。两张 serializer registry 保存并恢复。fixture 还临时清空并恢复 `BoM.addedRecipes` / `disabledRecipes`，逐项检查默认 producer，日志打印候选 key 及实际 anchors/count，避免残留选择覆盖默认关系。

标题页的 `EmiReloadManager.isLoaded()` 正常为 false，manager 会禁用。fixture 先确认没有活跃 reload 线程，再仅在该测试阶段反射保存/设置 `status=2`，使用原生 `EmiScreenManager` 的 LEFT/FAVORITES panel、`panel.bookmarks` 和真实 `RecipeScreen` bounds；成功或失败均恢复原 status。没有修改生产 gate，也不创建替代侧栏。测试在首次会持久化的收藏操作前检查 `client.runDirectory` 以 `run/crafting-tree-smoke` 结尾，并且进程相对 `emi.json` 的目录必须等于该隔离目录；若不同直接失败，避免保存落到普通运行目录。原 favorites、group metadata、BoM defaults、recipe manager 和 enabled 在结束时恢复。

新增断言与真实交互如下：

1. todo 模型及 `panel.bookmarks.hovered` 的实际格坐标双重检查：每个 recipe 结果在首列，其 inputs 紧接在同一行后续列；共享木板合并为三批、12 输出，原木仅三根（不是分支各自取整的四根）。
2. 通过原生 manager `mouseClicked`/`mouseReleased` 点击 gutter：左键切换 compact-only results（恰好四格、无 inputs）再还原；右键切换 crafting 并检查绿色/灰色括号及数量变化。使用当前默认鼠标绑定，不靠重绑让测试通过。
3. 真实 Ctrl 按键跨五个 tick 后检查 `EmiInput.isControlDown()`，调用 manager `mouseScrolled` 增减 root multiplier，断言两批目标共享原木为五根，再还原数量。
4. 真实 Alt 按键跨五个 tick 后点击 gutter 折叠整组，断言仅保留 root；继续 Alt 点击仍可见 root，必须展开整组并保留原 `RecipeScreen`，避免折叠后失去展开入口。
5. group 的终端原木 input 通过 manager hover 暴露的 recipe context 必须为 null；实际默认左键按下/释放后不能打开父配方页面。此处选择没有生产者的终端输入；有自身生产配方的输入仍允许按普通物品查看自身配方。
6. 原生 manager `mouseDragged` 从首行 gutter 到额外炉子组行，实际分组；再右键 gutter drag 实际解组。使用公开模型 `group/toggleView/toggleCrafting/adjust/toggleCollapsed/toggleRecipe/save/load` 检查 membership、todo/crafting/collapsed、full_recipe、multiplier 与 collapsed_recipes 的 roundtrip，保存 JSON 证据。
7. 加入足量真实物品测试配方使 `panel.bookmarks.pages()>1`，通过无修饰键 manager `mouseScrolled` 换页并返回。
8. 每阶段排队预期 stage/group ID/color，使用只增加 render 计数的 `RecipeScreen` 子类观察真实正常渲染。测试暂停推进场景，直到至少经历一个新的正常 render，再在下一 tick 开始读取客户端 framebuffer；不在 END_TICK 手动重画 GUI，不依赖遗留投影/深度状态。compact 还原、crafting-on 还原和 Alt 展开均在截图断言完成后执行，最终 PASS 也等第13张图写完。按 GUI→framebuffer 比例在 native gutter 检查精确绿色 `#45a967` 或灰色 `#999999` 像素，不能仅凭 group.crafting 标记声称绿色括号已 render。第13张截图为 roundtrip、数量还原与分页返回后的原生侧栏。

## 结果

隔离运行目录中应出现：

```text
screenshots/crafting-tree-smoke/<启动毫秒时间>/
  01-expanded.png
  02-search.png
  03-folded.png
  04-null-player-inventory.png
  05-restored-fit.png
  06-after-export.png
  07-truncated-tooltip.png
  08-bookmarks-todo.png
  09-bookmarks-compact.png
  10-bookmarks-crafting-off.png
  11-bookmarks-collapsed.png
  12-bookmarks-expanded.png
  13-bookmarks-roundtrip.png
  bookmark-groups-roundtrip.json
  PASS.txt 或 PASS-MODEL-MODIFIERS.txt 或 FAIL.txt
screenshots/emi/trees/plan*.png
```

测试记录 `emi-crafting-tree-smoke` 日志，在成功或失败后调用 `client.scheduleStop()` 自动退出。它在真实导出图片非空且全部交互断言完成时才写 `PASS.txt` 和 `SMOKE PASS`；请勿仅凭进程退出码认定通过。导出最多等待二百 tick，整个流程最多一千二百 tick（依赖客户端实际 tick，资源加载卡住或原生窗口错误仍需外部超时）。

历史记录：2026年11月3日的旧版 fixture 曾完成一次真实 Fabric 客户端运行并通过。旧产物位于隔离目录的 `screenshots/crafting-tree-smoke/1793707488135/`：`PASS.txt` 与七张截图；真实完整布局导出位于 `screenshots/emi/trees/plan.png`。该记录仅覆盖旧版树阶段，不能作为新增原生收藏侧栏阶段已经通过的证据。新增阶段须重新运行并取得第08至13张截图、新版 PASS 和 JSON。

这套标题页 smoke 验证实际图形 API、基础交互与文件导出；PNG 仍需人工检查文字、节点、连线、分组、裁切及布局。它不验证真实玩家库存分配、机器处理器填充、服务器联机、Forge 游戏运行、完整计划保存/导入流程或 GPU 异常注入，不能把标题页的无世界测试当作这些场景已通过。新增的独立真实整合服务器 2×2/3×3 合成 fixture 见 `world/README.md`，使用其单独 init/runDir 启动，不与本标题页 fixture 一起跑。

## 严格物理输入诊断

默认严格模式现在把实际 `MinecraftClient.IS_SYSTEM_MAC`、`os.name`、左右 Ctrl/Alt/Super 的原始 `GLFW.glfwGetKey`、`InputUtil`/`Screen`/`EmiInput` 结果写入 `modifier-diagnostics.txt`。Windows 下还记录 `GetAsyncKeyState` 与 foreground HWND 对比 GLFW HWND；不能查询时明确记录 unavailable。测试包装真实 GLFW key callback，只观察原生输入并原样转发，结束时恢复原 callback；不会主动调用 callback 伪造轮询结果。

本地 Minecraft 源码确认：`Screen.hasControlDown()` 在 `IS_SYSTEM_MAC` 时查 343/347（Super），否则查 341/345（Ctrl）；`InputUtil.isKeyPressed` 直接判断 `GLFW.glfwGetKey(handle, code)==1`。当前 EMI `ScreenMixin` 仅追加 init/resize widgets，不改此逻辑。仅调用 `glfwSetKeyCallback` 返回的 callback 或 `Keyboard.onKey` 并不能更新 GLFW 内部 key state，不能据此标记 Ctrl polling 通过。`focused=true/keyPressed=true` 只证明 GLFW 缓存焦点和 Robot API 调用，并不证明 Windows 当前前台窗口或 OS 真的向 GLFW 交付了 Ctrl。

2026年11月3日21:44运行 `1793713444210` 严格 FAIL，诊断明确：`os.name=Windows 11/isMac=false`；Robot发送Ctrl后 `GetAsyncKeyState(VK_CONTROL/VK_LCONTROL)=true`，但 GLFW341/345=0、Screen/InputUtil/EmiInput均false，无真实native key callback。与此同时 `GetForegroundWindow=920346` 与该 GLFW 窗口 HWND `4918064` 不同，尽管 `GLFW_FOCUSED=1`。因此可排除Mac分支和EmiInput包装逻辑；直接证据是Robot确实按下了OS左Ctrl，但当前前台并非该GLFW窗口，native polling未收到。fixture 后续在标题栏点击后只尝试一次真实 `SetForegroundWindow`，且只有 Win32 foreground HWND 实际等于目标窗口才发送Robot键；不再把GLFW缓存焦点当作充分条件。严格PASS仍要求真实raw polling观察到修饰键。

分组树入口现在同时检查 `BookmarkGroupActions.openTree` 打开的真实 `BoMScreen` 有非空快照模型，save/delete/copy/paste/next按钮禁用，库存toggle不改变全局 `BoM.craftingMode`，`BoM.tree/CraftingPlans.current()` identity保留且关闭回原 `RecipeScreen` identity。以上隔离回归已在该次严格FAIL之前全部通过；不因此把Ctrl/Alt未通过混称为整套通过。

