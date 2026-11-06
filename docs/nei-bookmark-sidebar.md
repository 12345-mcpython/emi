# GTNH NEI 左侧收藏合成链对照

本文依据工作区 `NotEnoughItems/src/main/java/codechicken/nei/` 当前源码阅读整理；行号属于该源码快照。本次左侧栏实现按当前源码独立重写，未套用旧补丁。EMI 的实现范围与已知差异列于下文；编译、回归及客户端验证以末尾验证状态为准。

## 界面身份与状态

截图所示左侧紧凑材料／产物条、左边细绿括号属于 `BookmarkPanel`，不是独立 `GuiCraftingTree`。

- `BookmarkPanel.java:49,102–109`：面板持有 `BookmarkGrid`，每次更新切到 `BookmarkStorage` 的活动 namespace。
- `BookmarkPanel.java:529–552`：按容器左侧可用区域计算边界，额外预留 `GROUP_PANEL_WIDTH`。
- `bookmark/BookmarkGrid.java:87–94,318–349`：合成链组色 `0x6645DA75`，左侧组操作带宽 7 像素；该操作带有独立命中检测。
- `bookmark/BookmarkGrid.java:443–563`：先根据每行所属组画括号，再绘制格子；`drawGroup` 第 540 行仅在非默认组且 `crafting != null` 时使用绿色，普通组灰色。跨页组会省略相应页边的括号端点。
- `BookmarkPanel.java:164–170`：默认组 0 没有同样的独立组括号，启用合成链时页码两边显示绿色方括号。
- `bookmark/BookmarkGroup.java:11–14,25–52`：四个独立状态是 `viewMode`（DEFAULT/TODO_LIST）、`crafting`（null 或 RecipeChainDetails）、整组 `collapsed`、配方集合 `collapsedRecipes`。切换视图不等于切换合成链，也不等于折叠。

独立树是 `bookmark/tree/GuiCraftingTree.java:80–81` 的 `GuiContainer`；其 `124–154` 持有 grid/group、独立 canvas/camera/toolbar/stats 状态；`157–160` 用 `displayGuiScreen(new GuiCraftingTree(...))` 打开。左侧组悬停 T 的桥接在 `api/ShortcutInputHandler.java:105–109`。移植左侧条不要求移植独立树的画布、缩放、工具栏或统计面板。

## 最小数据流

1. `bookmark/BookmarkItem.java:167–193` 定义 ITEM/RESULT/INGREDIENT，以及 groupId、recipeId、候选材料 permutations、long multiplier、factor、chance 和 fluidCellAmount。一个配方的材料和产物是多个条目，不只是一个产物图标。
2. `BookmarkPanel.java:221–281` 添加配方或组；`bookmark/BookmarkGrid.java:96–106` 维护有序条目、组表和 generator。
3. `bookmark/BookmarkGrid.java:212–236,351–402`：dirty 后去重、使组连续、把相同配方结果放在材料前、清理不完整配方和失效折叠状态，再刷新各组合成链。`578–586` 区分条目 dirty 和仅网格 dirty。
4. `bookmark/RecipeChainDetails.java:112–195`：`refresh` 调用 `RecipeChainMath`，产生 calculatedItems、itemToRecipe、outputRecipes、recipeInMiddle、recipeRelations；区分显式倍率、计算倍率和 Shift 数量。`22–38` 分类 RESULT / REMAINDER / INGREDIENT，排序为产物、余料、材料。
5. `bookmark/RecipeChainDetails.java:197–270`：折叠配方聚合依赖子链，并创建负索引材料／余料影子槽；这些是显示投影，不能把可视索引直接当原始条目索引。
6. `bookmark/BookmarkGridGenerator.java:23–31,45–160`：生成 absolute slot↔item 映射、row→group 映射、虚拟条目和当前页 mask。`78–105` 整组折叠仅保留一行能放下的结果；`107–134` crafting+DEFAULT 显示紧凑结果；`184–218` TODO_LIST 中折叠配方显示聚合影子材料。`225–263` 要求新组换行，TODO_LIST 的首列放产物／独立物品，材料接在同一配方后。
7. `bookmark/BookmarkGridGenerator.java:303–357` 负责分页和构造槽；`359–389` 计算槽外框，折叠整组为绿色、折叠配方为紫色。细组括号和槽外框是不同渲染层。
8. `bookmark/BookmarksGridSlot.java:66–105,133–191`：保留 realAmount、shiftAmount、calculatedAmount 及倍率；默认显示计算数量，Shift 查看需求／净量，Ctrl 查看显式值，Shift 优先于 Ctrl。`236–247` 材料绿、产物蓝、余料紫；`265–299` 绘制数量、配方倍率和配方标记。

## NEI 精确操作对照

以下键位指默认配置；用户可以重新绑定。`NEIClientConfig.java:884–891` 注册 A、F、V、L、T、B 等 bookmark 键。

| 命中位置／操作 | NEI 语义 | 源位置 |
| --- | --- | --- |
| 页码中区左键 | 默认组 0 切 DEFAULT/TODO_LIST | BookmarkPanel.java:699–713 |
| 页码中区右键 | 默认组开关 crafting 计算 | BookmarkPanel.java:699–713 |
| 页码中区 Alt+左键 | 默认组整组折叠／展开 | BookmarkPanel.java:702–704 |
| 非默认组左侧 7px 括号单击左键／右键／Alt+左键 | 松开时分别切视图／开关链计算／折叠整组；有拖动状态时优先结束拖动 | BookmarkPanel.java:989–1023 |
| 槽 Alt+左键 | 已折叠整组先展开；否则在 crafting 组切该配方／聚合子链折叠 | BookmarkPanel.java:751–766；BookmarkGrid.java:130–160 |
| 括号左拖 | 从普通行创建组，或调整已有组范围；超过原行或按住超过 250ms 才建立选择终点 | BookmarkPanel.java:565–573,676–695,995–997 |
| 括号右拖 | 将覆盖行归到默认组，即解除分组；必要时拆开原组 | BookmarkPanel.java:682–688；GroupingItem.java:78–115 |
| 括号 Shift+左拖 | 移动整个非默认组，可在其他 namespace 继续移动 | BookmarkPanel.java:653–672,574–582,1037–1045；BookmarkGrid.java:781–800 |
| 槽 Shift+左拖 | 移出原槽或超过 250ms 后启动排序；TODO/crafting 的结果拖动可带整个配方，已折叠配方带依赖子链 | BookmarkPanel.java:592–604；SortableItem.java:31–43,226–247 |
| Ctrl+滚轮（槽） | 独立条目改倍率；配方条目改整配方倍率；折叠整组改整组 | BookmarkPanel.java:1052–1061；BookmarkGrid.java:1215–1263 |
| Ctrl+滚轮（括号或默认组页码） | 改整组数量；链组只调整输出配方；普通组按原数量比例缩放 | BookmarkPanel.java:1063–1076；BookmarkGrid.java:1154–1212 |
| Ctrl+Alt+滚轮 | 槽使用 getScrollMultiplier；组使用数量控件值，0 时按 64。并非固定 16 | BookmarkPanel.java:1056–1058,1068–1072 |
| Shift+滚轮（有候选材料提示的槽） | 轮换 ingredient permutations，替换原始条目所选材料 | BookmarkPanel.java:1081–1107 |
| A（槽）／Shift+A（槽） | 切换收藏或删除；Shift 指完整配方。折叠组／折叠子链保护普通 A，Shift+A 删对应组或依赖子链 | ShortcutInputHandler.java:166–168,354–412；BookmarkPanel.java:318–371 |
| Shift+A（括号／默认组页码） | 删除整组 | ShortcutInputHandler.java:86–94 |
| Shift+F（物品） | 找到所选默认配方树，作为新的 TODO_LIST+crafting 组收藏；不是开关单个收藏 | ShortcutInputHandler.java:162–164,336–348；recipe/GuiFavoriteButton.java:210–215,217–284 |
| 配方收藏按钮 Shift+A | 与该按钮的 saveRecipeInBookmark 对接，同样建立链组 | recipe/GuiFavoriteButton.java:129–139,210–211 |
| V／Shift+V（组） | 从支持的容器取组材料；Shift 会扣除玩家已有量。带配方时还按容器库存重新计算需求 | ShortcutInputHandler.java:96–98；BookmarkPanel.java:1131–1232 |
| V／Shift+V（物品槽） | 取悬停物品数量；不是自动执行整条链 | ShortcutInputHandler.java:453–468 |
| Shift+合成键（组或结果） | 启用 autocrafting 时执行该链／配方，默认忽略玩家现有量；再按 Ctrl 才考虑已有量（补缺） | ShortcutInputHandler.java:111–125,174–176,266–304 |
| Ctrl+L／Ctrl+Shift+L | 发送物品／完整配方或组的聊天链接 | ShortcutInputHandler.java:100–103,150–151,214–245 |
| T（crafting 组） | 打开独立 GuiCraftingTree | ShortcutInputHandler.java:105–109 |

分组边界不会随意切断配方：`GroupingItem.java:118–190` 会扩展到整配方／折叠子链边界；`BookmarkGrid.java:664–703` 再将显示行映射回实际条目。`SortableItem.java:108–209` 仅允许 TODO_LIST、未折叠、非材料结果进行受约束的跨组插入，同时防止重复配方。

## EMI 最小移植接口及当前接入边界

最小接口应分成以下几层；这不要求复刻 NEI 的旧 ItemStack 或 OpenGL 类型。

- 配方适配：稳定 recipe id、inputs/outputs/catalysts、每次用量、候选材料、概率／容器返还，以及默认 producer 解析；默认 producer 只在用户显式收藏链时展开。
- 收藏模型：有序原始收藏与稳定 identity、group membership、独立 todo/crafting/collapsed/collapsedRecipes、显式批数；更改后 revision 与持久化。EMI 当前入口为 `EmiBookmarkGroups.addRecipe(recipe, true)`。
- 计算投影：按组输入原始收藏与已选候选，输出可视 rows（ingredient、recipe、anchor、input/output、amount、batches），保留输出／中间物／余料与原条目映射；实际合成执行按原始 anchor，不按合成虚拟槽对象。
- 布局／渲染：页容量、无效格、row/group 和 cell/anchor 双向命中映射，7px gutter、紧凑／配方行模式、整组与子链折叠、数量／倍率及提示。修改模型或库存后使布局缓存失效。
- 输入命令：切换视图／链模式／整组和配方折叠、建组／解组、移动组／槽、调批数、显式收藏链、取料、执行合成；鼠标拖动要保留独立状态和提交边界。
- 执行适配：容器库存快照与支持能力、配方填充与输出获取、tick 驱动的执行状态／失败原因；取料和合成必须分别建模。

本次 API widget 接入：配方上下文 `SlotWidget` 的 `slotInteraction` 在普通回退之前识别“收藏绑定额外加 Shift”，调用 `EmiBookmarkGroups.addRecipe(recipe, true)`；`canResolve()` 的配方解析界面保留其专用快捷键行为。没有配方上下文的槽不在此入口生成链；普通收藏继续原 `EmiScreenManager.stackInteraction` 路径。收藏键的鼠标和键盘绑定共用该入口，匹配保留收藏绑定本身的 Ctrl/Alt 修饰键并合并 Shift。中英文 `emi.bookmarks.*` 提示一起加入。

EMI 组链合成使用独立可配置绑定 `EmiConfig.craftBookmarkGroup`（`key.emi.craft_bookmark_group`），默认 **Shift+C**；默认 **Ctrl+Shift+C** 扣除背包已有物品，只生产缺少部分。NEI 的“Shift+合成键／Ctrl+Shift+合成键”是上表的原操作语义，并非 EMI 直接复用 `craftAll`：EMI 的 `craftAll` 原默认 Shift+左键，会与 NEI 式 Shift+左拖组／槽排序冲突，因此组链只使用此独立绑定，保留 Shift+左拖排序与普通单配方原合成快捷键。重新绑定后以配置为准。

当前接入及与 NEI 的差异：

- `EmiBookmarkGroups` 使用组级 `Ledger` 的 `Stock`／`Flow` 账本，依照 `CraftingTreeModel` 的整批预算与依赖顺序结算库存、概率消费／产出、可复用启动材料和容器返还。普通配方行与折叠盒均投影同一账本的边界，盒内产消抵消、跨盒物流保留，未用返还物标记为 `REMAINDER`；共享库存不会按盒重复扣除。概率按期望量聚合后取整，并保留单槽启动需求，不保证随机配方实际成功次数。
- 每行保留 `explicitAmount`（显式收藏批数）、`calculatedAmount`（未扣库存的计划）、`remainingAmount`（扣库存后的计划）。默认显示计算量，Shift 看剩余量；合成计算组在 Ctrl 调量期间保持联动计算量，关闭计算时 Ctrl 看显式量，Shift 优先；tooltip 同列三套值。同组兼容配方自动关联多候选输入，按收藏顺序选择，显式候选优先，不借用其它组的配方。执行用 `executionRows`，不受 todo／折叠显示影响。此投影尚不等于 NEI 全部 `recipeRelations` 和独立 shadow-box 重算规则。
- 普通及折叠输入保留原始 `source`／`sourceAnchor`，Shift+滚轮修改并持久化原配方的具体候选；不同来源不做有损合并。`effectiveRecipe` 仅包装所选输入，保留插件原对象用于 handler 查找、类型／槽位与支持检查；实际填充 stack 从 effective 输入生成，已不再任由 tag 回退选择其它候选。`getCatalysts()` 仅转发元数据，不凭空增加消耗；动态损坏工具替换和流体容器换算仍未实现。
- Ctrl+Alt+滚轮使用配置 `bookmarkQuantityStep`，默认64，最小1；没有 NEI 的运行时数量控件。槽与括号均可调量。普通排序／建组拖动越出原槽／行或按住250ms启动；Shift+左键在独立组括号上立即启动整组移动，显示跟随鼠标的可见组预览。对照 NEI BookmarkPanel.mouseDragged、BookmarkGrid.moveGroup、SortableGroup，向上仅在目标组首行前、向下仅在末行后实时换位，禁止拆开目标组；移动保留全体 anchor、候选、倍率及折叠状态。侧栏上下边缘停留350ms可跨页继续选择／排序；仍以原始 anchor 和整配方／折叠依赖为提交单位，不能据此声称已复刻 NEI 跨 namespace 及全部插入约束，长按与跨页拖动仍需专门实测。
- 默认 T 在组括号或结果上打开所选配方与候选的临时组树。它不查询全局默认 producer，不改 `BoM.tree`、当前保存计划或全局库存模式；保存／删除／复制／导入／下一步禁用，关闭回到原界面。它是隔离快照，不是保存计划的编辑入口。
- 默认 V／Shift+V 已接 `BookmarkMaterialTransfer`：取组的基础缺料及无配方独立收藏，Shift 扣除玩家已有量；它不是执行合成链。支持范围严格限原版 `GenericContainerScreenHandler` 9×1至9×6、普通存储 Slot和36格玩家栏、仅物品，拒绝初始非空光标和特殊容器。EMI服务端路径通过现有fill协议把真实源栈的精确数量移入空玩家槽，等待源／目标raw ack且光标保持空；vanilla路径左键拿源、右键逐个放入并归还余量，每步等raw槽／光标ack，不把预测当完成。vanilla可能因预测匹配而不发包，100tick超时后尝试归还自有光标；归还也有100tick上限并明确报告剩余光标。每tick至多一个动作、总计4096；不丢物、不QUICK_MOVE、不改客户端库存。EMI服务端精确取料已有真实世界PASS；vanilla无服务端EMI取料路径仍缺世界完成证据，详见 `tests/bookmark-transfer/README.md`。聊天链接尚未移植。

### BookmarkCrafting 执行器边界

公共调用及回归步骤见 `tests/bookmark-crafting/README.md`：

- `start(Group, boolean useInventory)`、`tick()`、`isRunning()`、`cancel()`、`fill(Group, boolean all, boolean toInventory)` 与 `status()`；状态提供 `running/result/reason/completed/operations`。`useInventory=true` 抵扣已有中间物，false不扣请求生产量；两者仍要求实时材料可用。返回true表示接受，不等于完成；以 `completed/budget_complete` 判断预算完成，状态不持久化。
- 自动执行和 NONE填充均只支持使用默认 `StandardRecipeHandler.craft` 的标准handler；覆写craft、自定义／非标准handler拒绝。自动执行还要求立即输出槽，不控制机器。handler查找和槽位判断用original配方，具体材料栈用effective配方，保留插件identity及选中候选。
- 自动每次精确填一批材料，避免QUICK_MOVE因多余入格材料重复制作。非空网格仅允许对应有效配方输入／其返还物，须可拿取并有背包退回空间；foreign／locked grid拒绝，已有输出也须匹配。输出、旧网格和返还物须有容量。自有非空网格并非一律拒绝，但不恢复原布局。
- 所有输出必须是不同种类、正数量、确定性的物品，所有非空输入也须正数量且确定性；自耗／同时作为返还物的输出、概率／非物品输出、重复输出及没有明确消耗输入的催化剂-only配方拒绝。模型能估算概率，不代表runner能自动执行概率配方。
- 派发后只读主线程原始服务器inventory／slot包维护的库存镜像；每个输出相对派发前必须恰增一批，且观察到后续反馈，才扣预算并发下一批。多输出全部确认；超过一批的增量以 `ambiguous_output_change` 停止。客户端预测／fingerprint不能确认完成，100玩家tick未确认以 `server_ack_timeout` 停止；外部并发移动同类输出仍可能造成歧义。
- 最多256个选中配方、4096次请求，循环拒绝；不查询默认配方、不建线程、不控制机器、不重开界面。handled screen／handler／syncId或光标条件失效时停止。`fill(group,true,false)` 最多一次64批NONE填充、保持runner idle并报告 `filled/fill_only`，不把入格当产出。

## 验证状态

2026年11月3日：主agent在Java17下重新执行独立回归，合成模型79、收藏分组62、自动合成调度59、计划290、生命周期7，共497项通过（包含普通无配方收藏的executionRows空recipe回归）；transfer checks亦通过，但本轮未提供可引用的断言总数。隔离fixtures验证当前生产源码逻辑，不等于Minecraft网络测试。最后标准文字渲染修改后的Fabric／Forge离线构建也已通过，主agent确认两包生产类、mixin及可选smoke隔离；本次文档编辑未运行Gradle。

最新严格真实Fabric标题页／RecipeScreen smoke已通过，证据为 `fabric/run/crafting-tree-smoke/screenshots/crafting-tree-smoke/1793715023124/PASS.txt`、13张PNG及roundtrip JSON。真实manager Ctrl滚轮和Alt整组折叠／展开再次通过；Win32前台HWND与GLFW窗口相等，Robot键由native callback与`EmiInput`轮询确认。`RowFavorite` 数量改用标准Minecraft文字阴影、紧凑单位与17px范围缩放；已人工查看 `08-bookmarks-todo.png`，白块消失且1、4、8、12、3数量清晰可见，未修改原版MicroTextRenderer。较早 `1793714081752` 严格PASS与 `1793711344599`／`1793713444210` 严格FAIL保留历史：后者OS已按Ctrl但实际前台HWND不同，`GLFW_FOCUSED`缓存true不足以证明前台。旧 `1793711650627` 模型模式仅属旧版；另已读回新版 `1793714208968/PASS-MODEL-MODIFIERS.txt`，公开模型API数量／折叠及其它正常UI阶段通过，但明确 `CtrlAltUI UNVERIFIED`，不能代替最新严格输入PASS。

最新真实Fabric整合服务器七场景全部通过，证据为 `fabric/run/crafting-world-smoke/screenshots/crafting-world-smoke/emi-world-smoke-1793714835690/PASS.txt`及截图／权威库存snapshot：玩家2×2链2/2、工作台3×3链3/3、显式birch且oak不动2/2、foreign grid拒绝且服务端不变、自有非空grid恰一批1/1、临时 `EmiClient.onServer=false` 的真实vanilla clientFill／ClickSlot路径1/1，以及EMI服务端精确取料64取3（源61、玩家3、光标0，`completed=3/operations=1`）。vanilla标记仅选择真实填充路径，不等于卸载服务端EMI。旧 `emi-world-smoke-1793713632747` 六场景证据保留历史，最新运行已重跑六项并加取料。

尚无独立远程服务器、Forge游戏运行或其它机器handler验证；vanilla无服务端EMI的取料路径、跨页长按拖动与完整计划导入／导出仍未由上述世界场景覆盖。严格物理输入PASS与整合世界API／packet ack PASS范围互补，不覆盖物理侧栏启动合成／取料键。
