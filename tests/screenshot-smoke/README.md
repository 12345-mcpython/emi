# 可选 Fabric 客户端截图 smoke

该测试仅用于 Minecraft 1.21.5/Fabric 开发客户端，覆盖两件事：

1. `EmiScreenshotRecorder` 的离屏截图链路：标题页稳定后请求一张 64x32 的截图（红色矩形 + 一个石头物品 + 一行文字），读回 PNG 校验尺寸、透明背景、矩形颜色、物品像素和文字像素。
2. BoM 界面的背景顺序：打开真实 `BoMScreen`，截取客户端帧缓冲，检查左上角 “plans” 标题（`0xFFB6C7E8`）是否仍是原色。若菜单模糊/背景在 `super.render` 里最后才执行，标题会被糊掉并压暗，像素检查就会失败。

测试目录不是发布模组的一部分，普通 `build` 不会编译它。

## 启动

```powershell
.\gradlew.bat --offline -I tests/screenshot-smoke/init.gradle :fabric:runClient
```

仅编译（不启动客户端）：

```powershell
.\gradlew.bat --offline -I tests/screenshot-smoke/init.gradle :fabric:compileScreenshotSmokeJava
```

init 脚本仅通过显式 `-I` 生效，创建 `screenshotSmoke` sourceSet 与 `emi_screenshot_smoke` Loom mod，并给 `runClient` 加入 `-Demi.screenshotSmoke=true` gate；缺少该系统属性时 entrypoint 不做任何事。客户端使用隔离目录 `fabric/run/screenshot-smoke`，窗口 1280x800，JVM 最大堆 2 GiB。需要可用图形桌面和 OpenGL，不能当作 headless 单元测试。

## 断言与产物

截图尺寸必须是 `64 * GUI scale` x `32 * GUI scale`；`(0,0)` 必须完全透明；矩形中心必须是 `0xFFFF0000`；石头物品区域必须至少有一个非透明像素，文字区域必须至少有一个不透明近白像素。随后 BoM 标题区域必须有至少 20 个接近 `0xFFB6C7E8` 的像素。全部通过后写 `PASS` 并自动退出，否则写 `FAIL <原因>`：

```text
fabric/run/screenshot-smoke/
  screenshots/emi/smoke/capture.png
  screenshot-smoke-result.txt   (PASS 或 FAIL ...)
```

日志中的证据行形如：

```text
SCREENSHOT SMOKE size=192x96 expected=192x96 corner=0x0 rect=0xffff0000 itemPixels=true textPixels=true
SCREENSHOT SMOKE export checks passed, opening the BoM screen
SCREENSHOT SMOKE BoM header pixels=540 brightest=0xffb6c7e8
SCREENSHOT SMOKE PASS PASS
```

实测对照：修掉背景顺序前同一位置是 `BoM header pixels=0 brightest=0xff3a404b`（被模糊并压暗），修好后是 `pixels=540 brightest=0xffb6c7e8`。

该 smoke 覆盖 1.21.5 的 `RenderTarget` 目标重定向、正交投影/模型视图设置、批次 flush、`copyTextureToBuffer` 回读，以及 BoM 背景/模糊顺序；它不覆盖第三方配方渲染器里的 scissor、GUI 缩放配置项、真实配方内容和真实世界背景。
