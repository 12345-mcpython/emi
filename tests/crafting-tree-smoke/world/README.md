# 独立真实 world/container 合成 smoke

只通过本目录的 init 脚本显式启用，不加入普通 build 或统一 Gradle 测试任务。生产文件不需为 fixture 新增测试开关、配方、物料或 `BookmarkCrafting.Access` 替身。

```powershell
$env:JAVA_HOME = 'D:\java\zulu21.52.203-ca-jdk21.0.12.1-win_x64'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat --offline -I tests/crafting-tree-smoke/world/init.gradle :fabric:craftingWorldSmokeClasses
.\gradlew.bat --offline -I tests/crafting-tree-smoke/world/init.gradle :fabric:runClient
```

两条命令必须顺序执行；不要与任何其它 Gradle 构建并发。没有本地游戏资源、图形桌面或原生库时应报告实际障碍，不把编译成功或退出码当作 smoke 通过。

运行目录固定为 `fabric/run/crafting-world-smoke`。fixture 在任何世界创建或持久化前检查 `client.runDirectory` 与进程 cwd，必须同为该隔离目录。每次自动创建新的 `emi-world-smoke-<毫秒时间>` flat survival 世界，仅写该隔离目录下的 `saves`，不打开、删除或更改普通 world 存档。世界保留以便复查。最多等待四分钟，不在客户端 tick 中 sleep。

实际流程：

1. 等待真实 TitleScreen 资源加载结束，调用原版 `IntegratedServerLoader.createAndStart`，通过动态 registry 选择 `WorldPresets.FLAT`，使用 survival/peaceful、不作弊、不刷怪、固定 seed。
2. 等待真实 `client.world/player/getServer` 及自然发生的 `EmiReloadManager.isLoaded()`。不强改 reload status，不替换 recipe manager、serializer 或 handler。
3. 只在真实 integrated-server 线程清空测试玩家库存并发一根原木；等待网络同步。客户端打开真实 `InventoryScreen` 与 syncId=0 玩家 2×2 菜单。
4. 从已加载的 EMI manager 选择真实原版 `minecraft:oak_planks`、`minecraft:crafting_table`，通过公开 bookmark 模型建组，并通过真实 `cycleIngredient` 显式选择 oak 标签候选，再调用生产 `BookmarkCrafting.start(group, false)`。不直接写客户端槽，不调用 `Runner/Access` 测试 seam，不假造产物。
5. 等待 runner 停止，要求公开 `Status` 为 `completed/budget_complete`，`completed=operations=2`（每一批都获得真实 packet ack），且客户端收到一个工作台。再提交服务器线程读取 authoritative 玩家库存及 handler slots；必须恰好 table=1、log=0、planks=0、chest=0，合成网格/结果/光标空，syncId=0。原版填充、拿取、数据包和生产 ack runner 都必须正常工作。
6. 只在 server 线程为下一场景准备两根原木，并在玩家位置实际放置工作台，通过 block 的真实 `NamedScreenHandlerFactory` 打开 `CraftingScreenHandler`。等待真实 OpenScreen/slot 同步，客户端必须是 `CraftingScreen` 且 syncId>0。
7. 选择真实 chest/oak_planks 组，跑 `BookmarkCrafting.start`，要求两批木板后合成一个箱子；公开 `Status` 为 `completed/budget_complete`，`completed=operations=3`。客户端与 server authoritative inventory 对比，最终 chest=1、其余测试物料=0，网格/结果/光标空、syncId一致。
8. 同一真实工作台内继续四个额外场景：显式选 birch 候选和 birch_planks producer（oak/birch两根原木共存），必须只消费 birch、保留 oak；foreign cobblestone 网格必须以 `foreign_or_locked_grid` 拒绝且服务端不变；自有配方网格预置单根原木、背包另有单根原木，只跑一批后必须恰有4木板与1原木、网格空；临时 `EmiClient.onServer=false` 选择已有的 vanilla clientFill/ClickSlot 路径，必须同样获得一批真实 raw packet ack 与服务端4木板。这最后一项不是实际卸载服务端 EMI，而是显式选择真实 vanilla 填充路径，结束恢复原标记。
9. 保存六场景 framebuffer PNG、权威 snapshot 和结果标记，调用 `scheduleStop()` 正常关闭服务器与客户端。

证据目录：

```text
fabric/run/crafting-world-smoke/screenshots/crafting-world-smoke/emi-world-smoke-<毫秒时间>/
  01-inventory-2x2-server-ack.png
  02-table-3x3-server-ack.png
  03-extra-server-ack.png  # birch候选
  04-extra-server-ack.png  # foreign grid拒绝
  05-extra-server-ack.png  # 自有非空grid单批
  06-extra-server-ack.png  # vanilla clientFill路径
  PASS.txt 或 FAIL.txt
```

只有 `PASS.txt` 和六场景权威库存 snapshot 才代表本 fixture 通过。此测试覆盖公开 `BookmarkCrafting.start` API 到真实 vanilla server handler/slot 同步的链，不声称物理 Ctrl/Alt、侧栏点击入口、独立远程服务器、Forge 游戏运行或其它机器 handler 已通过。

2026年11月3日21:47实际验证：六场景全部通过，产物位于隔离目录 `screenshots/crafting-world-smoke/emi-world-smoke-1793713632747/`，Java17离线运行32秒正常退出。两主场景 `completed/operations` 分别2/2、3/3；birch2/2、自有grid1/1、vanilla clientFill1/1；foreign grid明确拒绝且服务器物料不变。已读回 PASS.txt，人工查看工作台截图确认唯一箱子与空grid。左书签若干图标呈白块的视觉问题尚待另查；此 PASS 不代表所有视觉细节完美。较早的两主场景通过产物 `emi-world-smoke-1793713234432/` 仅覆盖旧两场景，不混用为六场景证据。

2026年11月5日：以上为 1.20.1 记录。本 fixture 的元数据已随代码更新到 1.21.1（Java 21、`:fabric:`），但移植后尚未重新编译，也未在 1.21.1 客户端重跑世界场景；固定键位/界面 API 若有变化需在首次运行时修正。
