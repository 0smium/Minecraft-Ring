# LB ClickGUI / HUD 提前预检

2026-10-11，Windows 本地，第 8 轮启动；日志时间为 America/New_York。

用户本轮明确授权：关卡 0 尚未完成时，提前载入固定 LB，只试 ClickGUI / HUD。此授权调整本轮实验顺序；Core 基线仍未通过，原有 Core 记录保留。本轮不启用 Fly / Velocity，也不据启动成功宣称平台、死亡路径或完整关卡 1 已通过。

## 版本与制品

| 项目 | 本轮实际值 |
| --- | --- |
| 主仓库分支 / 实际启动源码 | `dev/lb-poc`；首次 `0ddfcb27271b742229ebfba4a3bd39fd54deaa27`；菜单修复重启 `ca29fc8a4a9e16956a34757a37c908656bc17c9e`；正式运行 `b9e41eccd5ea9b168f3a83fbc9f3f93ed890c777` |
| Bridge 原始基线 | `711015afa67ab25c7b9597c68038718d1cef322e`，源码版 `0.3.0` |
| ER exe | `2.7.1.0`，沿用已核对的本地游戏安装 |
| MC / Loader / Fabric API | `1.21.1` / `0.19.5` / `0.116.17+1.21.1` |
| Java / Gradle / Loom | Java `25` / Gradle `9.7.1` / Loom `1.18.2` |
| LB | 官方 `v0.22.0` / Build `#3530` |
| LB 提交 | `e5a0dbf5458b063d3028e69e04762b8b25b998b5`，JAR 内 `git.properties` 匹配 |
| Fabric Language Kotlin | `1.13.0+kotlin.2.1.0` |
| MCEF / JCEF | `1.3.1-1.21.1` / `0e837c94ea090b6eb4b9bc65428ec28fd75d1d72` |

制品来自 [LB 官方 Build #3530](https://liquidbounce.net/download/3530)。下载 ZIP 为 `runtime/downloads/liquidbounce-e5a0dbf.zip`，SHA-256 与官网队列公布值一致：

```text
00B4324EBC728128E1FCA5F6DF270A61BB85DA8006450BC4F3DA1D3A45F40F7C
```

实际运行输入 JAR 为 `runtime/downloads/liquidbounce.jar`，SHA-256：

```text
1824011D91046F8F8B388CFA63782F38CDF754F12CB997317EAB8E69E67EF20D
```

本轮原生 Core DLL 的 SHA-256 为 `394F1B4E7CD5C036CF2F591F2ED62978927AF9EE8C0FD6BA3AB98C892975604A`。已核对 PID 8668 实际加载路径 `Game/erbridge/loaded/core_8668_1.dll` 的文件哈希，与构建产物和正确安装目录的源文件一致；native generation 1 加载成功，fall diagnostics enabled。首次落地修复的取证与人工状态见 [Core 记录](20261011-core-gate0.md)。

## 依赖接法与配置 B

JAR 的 `fabric.mod.json` 声明 MC `1.21` / `1.21.1`、Loader `>=0.16.5`、Fabric Language Kotlin `>=1.13.0+kotlin.2.1.0`；Fabric API 与 ViaFabricPlus 是 `suggests`。保持 Bridge 现有 Loader/API，没有为此次预检安装 ViaFabricPlus。

第一次尝试采用 `lbJar` / `lbNestedDir` 属性：固定 LB JAR 和 FLK 使用 `modRuntimeOnly`。Loom `1.18.2` 在开发运行的重映射产物中剥离父 JAR 的嵌套库声明，因此从同一固定 JAR 提取的 38 个嵌套 JAR 逐一作为 `modRuntimeOnly` 加入。嵌套库包括 MCEF、Graal/Truffle `24.0.2`、Netty `4.1.97` 等，完整清单和哈希在 `runtime/downloads/liquidbounce-build-3530-download.json`。该开发接法已实测启动，但在跳跃时出现方法映射错误，不能作为最终共载配置。

初始配置 `runtime/experiments/lb-ui-initial-modules.json` 显式开启 HUD、关闭 HUD Blur，并关闭 AutoConfig、LiquidChat、RichPresence。LB 展开配置后检查 `bridge-base/elden-ring/mc-bridge/run/LiquidBounce/modules.json`：实际顶层开启项为 **HUD + TranslationFix**，HUD `Blur=false`；AutoConfig、LiquidChat、RichPresence、Fly、Velocity 均关闭。ClickGUI 起始关闭，按键为 `Right Shift`、Scale `1.0`。

固定源码中的 [TranslationFix](https://github.com/CCBlueX/LiquidBounce/blob/e5a0dbf5458b063d3028e69e04762b8b25b998b5/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/exploit/ModuleTranslationFix.kt) 默认开启并隐藏，分类为 `EXPLOIT`。其[文本过滤实现](https://github.com/CCBlueX/LiquidBounce/blob/e5a0dbf5458b063d3028e69e04762b8b25b998b5/src/main/kotlin/net/ccbluex/liquidbounce/utils/client/SignTranslationFix.kt)将非原版翻译/按键文本替换为原始 key 或 fallback，保留样式与同级文本；该实现属于本地文本处理，不是移动或战斗模块，也不应描述为普通语言包修复。

## 已执行步骤与结果

| 项目 | 状态 | 已观察证据 |
| --- | --- | --- |
| 固定制品与嵌套库检查 | 通过 | ZIP 校验一致；JAR 版本/提交匹配；38 个嵌套 JAR 存在并可读 |
| 开发依赖与 `remapJar` 预检 | 通过 | `lb-dev-remap-preflight.log`：Loom `1.18.2`，`BUILD SUCCESSFUL in 51s`，7 个任务均 up-to-date |
| 本轮启动 | 正式运行版已连接（日志） | 执行 `start.bat -LiquidBounce`；ER PID `8668`，正式 MC PID `40396`，原始固定 LB/FLK/API 通过 `fabric.addMods` 加载 |
| 首次 JCEF 下载与初始化 | 通过 | 11:13:34 开始下载；11:15:08 解包完成；11:15:09 `Chromium Embedded Framework initialized` / `Browser is ready`；11:15:10 UI WebSocket 连接成功 |
| ER Bridge 世界连接 | 正式运行版通过到连接（日志） | 11:25:58 自动打开 ER Bridge，11:26:02 Steve 加入，11:26:09 GPU sharing / 原生合成；视觉与输入由用户确认 |
| ClickGUI / HUD 最终合成 | 通过本轮基础展示（用户及视频） | 用户反馈未发现异常；本地视频确认 ER 背景、LB HUD、ClickGUI、搜索 hud 与关闭面板后画面，详见录屏证据 |
| GUI 开关、鼠标 | 初步未发现异常（用户反馈） | 同上泛反馈，保留原话，不代替录屏验收 |
| GUI 关闭 / 打开时 F8 输入归属 | GUI 打开时不通过原判据；用户接受，不修 | 用户报告“开clickgui的时候F8不会切回来。这个应该不用修吧。我觉得挺好的”；采用 Esc 先关闭 GUI、再 F8 的操作流程 |
| Fly / Velocity | 未执行 | 本轮不启用 |

启动日志包含 `No matching environment detected`、可选 ViaVersion/Sodium 类缺失的 Mixin 警告、Graal fallback runtime 和字体图集缩减信息。进程继续到浏览器就绪；这些信息保留为待评估项，不能据此写成“所有注入已兼容”或“GUI 已通过”。

### LB 主菜单阻塞自动入场

11:13:51 MC 日志开启 Overlay，随后一直处于 `drawing nothing`、捕获/发布为 0。用户报告看不到 MC 窗口，实际 MC 没有启动集成服务端。固定 LB [IntegrationListener](https://github.com/CCBlueX/LiquidBounce/blob/e5a0dbf5458b063d3028e69e04762b8b25b998b5/src/main/kotlin/net/ccbluex/liquidbounce/integration/IntegrationListener.kt) 用 `VrScreen` 的 TITLE 路由替换原版 TitleScreen，而 Bridge 原本只识别原版主菜单和无障碍入门屏幕，故 WorldBootstrap 不会打开 `er-bridge`。法环角色已加载且 MC 无世界时，现有 Overlay 的 busy 规则让 MC 窗口完全透明；人工切菜单无法解决这个组合阻塞。

最小修复只让 `WorldBootstrap` 额外识别完整类名 `net.ccbluex.liquidbounce.integration.VrScreen` 且 literal title 为 `VS-TITLE` 的固定主菜单。实际 Build #3530 JAR 的 `javap` 与固定源码核对一致；不用 LB 编译依赖或反射，不匹配 ClickGUI、单人游戏列表或其他路由，保留 `noAutoWorld`、宿主存活和原有重试守卫。首次 MC 尚未进世界，核对进程路径与无集成服务端日志后仅结束 PID 28824；法环 PID 8668 保持运行，构建后从同一入口重启 MC。首次菜单现场保存 `runtime/experiments/core-08a-lb-menu-minecraft.log`、`core-08a-lb-menu-native.log`、`lb-ui-expanded-modules.json`。

修复提交 `ca29fc8a4a9e16956a34757a37c908656bc17c9e`。`remapJar` 构建成功（28 秒），日志 `lb-bootstrap-fix-build.log`；构建 Fabric JAR SHA-256 为 `FD3B0779F00210C31D3EBD0DD408559C8F6CD61EC527FE1193FC28F1695B3039`。再次执行 `start.bat -LiquidBounce`，日志 `core-launch-08b-lb-autoworld.log`，沿用法环 PID 8668。11:20:47 自动打开世界，11:21:02 Steve 加入，11:21:12 GPU sharing / native compositor 开始。最终 GUI/HUD 人工观察仍未完成。

### 开发映射的跳跃崩溃

11:21:21 MC 报 `NoSuchMethodError: float net.minecraft.world.entity.player.Player.method_6106()`，入口是 LB 的 `hookJumpEvent` → vanilla `jumpFromGround`。集成服务端随后正常保存退出，11:21:23 产生 crash report。此前 11:21:12–21 捕获/发布有数据，故不能把它说成没进世界或 JCEF 下载失败；也不能据这段短时运行认定共载通过。

停止继续修补第三方重映射 JAR，转用 Loom 正式运行任务加载原始固定 LB JAR、原始嵌套依赖与 Bridge 的 remapJar。保持同一版本、同一 run 配置，不升级 LB 或复制修改其源码。现场保存 `core-08b-dev-remap-crash-minecraft.log`、`core-08b-dev-remap-crash-native.log`、`lb-dev-jump-crash.txt`。本轮仍在关卡 1 一天 / Mixin 冲突两小时的预算内；若正式运行也有基础注入冲突，按文档限时处理，失败不写通过。

固定源码只读参考位于主仓库外 `D:/Dev/Eldenring/LiquidBounce-reference`，detached HEAD `e5a0dbf5458b063d3028e69e04762b8b25b998b5`，工作区干净、推送地址禁用。没有运行或构建该参考源码，也没有将最新版混入实验。

正式运行接法 `b9e41eccd5ea9b168f3a83fbc9f3f93ed890c777`：`-PlbProduction=true` 注册 Loom 1.18.2 的 `ClientProductionRunTask`，加载 Bridge `remapJar`、原始 LB JAR，以及独立非传递配置中的原始 FLK/Fabric API，由 Fabric Loader 解析原始嵌套 JAR；移除开发环境的 LB `modRuntimeOnly` 注入。Task 沿用 `run`、Steve、JDK 25、512MB–3GB 内存和已有 assets；原有 Core `runClient` 不变。`start.bat -LiquidBounce` 改用此任务；任务 dry-run 成功后实际启动，日志 `core-launch-08c-lb-production.log`。

实际正式 MC PID 40396，11:25:12 Loader 0.19.5 / MC 1.21.1，11:25:40 `Intermediary environment detected`，之前的 `No matching environment detected` 不再出现。11:25:58 自动开世界，11:26:02 Steve 加入，11:26:09 GPU sharing 与原生合成开始。`core-08c-production-connected-status.json` 记录 host 8668 / MC 40396、core_status=1、player_valid/camera_override/compositing=true；`lb-ui-production-modules.json` 仍只有 HUD + TranslationFix 开启，Fly/Velocity 关闭。启动后出现 `Invalid standard cursor 0x00000000` 和 JCEF 原生右键菜单缺少 window handle 的日志，未终止捕获；是否影响所需 GUI 鼠标操作待人工观察，不能擅自认定兼容。

人工初步反馈：上述正式运行后，按请求观察 MC 控制静止约 60 秒、右 Shift 打开 ClickGUI / 搜索 HUD / Esc 关闭、查看法环背景与 HUD / 鼠标操作，用户回复“好像没啥问题呢”。因此记录为初步未发现异常，保留反馈的概括性质，不虚构逐项测量或录屏。至该反馈的日志中正式运行继续捕获/合成，未新增 reported exception；用户未再报告自行死亡。仍需 GUI 打开 / 关闭时的 F8 往返与最终视频证据。现场另存 `core-08c-ui-manual-native.log`、`core-08c-ui-manual-minecraft.log`、`lb-ui-manual-modules.json`；本轮沿用已真正落地的同一 ER 进程，不能替代 Core-only 新启动 / 新加载的完整死亡复验。

该反馈后的配置文件快照发现开启项已变为 **HUD、NoJumpDelay、Speed、TranslationFix**（Fly/Velocity 仍关闭），与最初只开 HUD + TranslationFix 的配置不同；变更时刻和人工操作未逐项确认。不把这段运行写成纯 UI 配置 B，也不据此声称 Speed/NoJumpDelay 行为验证通过。已请用户先关闭 Speed/NoJumpDelay，再完成 F8 两种状态的检查；不擅自覆盖用户正在使用的配置文件。

F8 人工反馈：用户报告“开clickgui的时候F8不会切回来。这个应该不用修吧。我觉得挺好的”。用户明确接受此限制，故不追加键盘注入或放开 GUI 的游戏按键透传。操作流程为 Esc 关闭 ClickGUI，再按 F8。现有代码及实际 MC 1.21.1 字节码表明打开 Screen 时 vanilla 不发 `KeyMapping.click()`，Bridge 的 `consumeClick()` 接收不到 F8；LB 自己将按键交给浏览器。日志记录了关闭界面情况下 ER/MC 的往返，不据日志单独认定鼠标逐项通过。现场 `core-08c-f8-manual-native.log`、`core-08c-f8-manual-minecraft.log`、`lb-ui-f8-modules.json`。

后一个 F8 配置文件快照的开启项为 **HUD、KeepSprint、NoJumpDelay、Sprint、TranslationFix**，Speed 已关闭、Fly/Velocity 仍关闭。用户继续在 GUI 中调整了配置，尚未确认这些状态变更的具体时刻；仅保存文件快照，不将其作为速度、冲刺或跳跃模块的行为验收。

本轮结论：共载和 GUI/HUD 可用性已取得用户认可，并保存最终合成录屏；GUI 打开时 F8 往返未达到原文档判据，按用户明确豁免保留，不继续调试。完成本轮 GUI/HUD 基础展示，Breadcrumbs、JumpEffect 等世界空间视觉仍未执行，完整关卡 1.5 不写通过；关卡 0 平台等基线继续保持未通过。接下来回到不加载 LB 的 Core 基线，不提前测试 FlyCreative/Velocity。

### 最终合成录屏

用户提供桌面文件 `D:/Users/yanbiao/Desktop/QQ录屏20261011113335.mp4`。原样复制到忽略目录 `runtime/experiments/lb-ui-08.mp4`，保留原文件；原始/副本均为 36,691,384 bytes。SHA-256 `81DDA845299E1B1F79F09B64992222C730E9CF8716EE2E3D8F57E8FD17842D9C`，时长 30.594 秒，1598×898，528 帧，平均录屏帧率 17.2583 FPS（不是游戏帧率）。

仅从该视频提取五张完整原始帧做核对，位于 `runtime/experiments/lb-ui-08-frames/`，无裁剪编辑、无 computer-use。4.764 秒有 ER 背景、LB logo、模块列表、通知与 MC 方块；9.851 秒有 Scaffold Disabled 通知与方块阶梯；14.941 秒 ClickGUI/参数面板打开，部分被 Codex 窗口遮挡；21.017 秒在完整 ClickGUI 中搜索 `hud` 并定位 HUD；27.243 秒已关闭 ClickGUI，ER 背景、HUD 与游戏画面继续显示。证明本轮最终合成中界面和 HUD 可见，不据截图独立声称鼠标定位精度、行为同步或持续性能通过。

视频同时显示用户临时启用/停用 Scaffold，且 HUD 中有 NoJumpDelay、KeepSprint、Sprint；因此属于实际 GUI 交互与配置探索，不能改写为整个录屏期间所有行为模块都关闭的纯 B 配置，也不能把 Scaffold 的行为作为正式验收结果。原始视频包含中途切到 Codex 的片段，保留原样。视频、原始帧、配置与日志均不提交 Git。

## 人工下一步与证据路径

1. 法环已经选择 Continue；MC 重启后确认自动进入 ER Bridge 并连接。保持 MC 控制静止 60 秒、先不 F8，记录是否自行死亡；观察法环背景与 HUD 合成。
2. 按 Right Shift 打开 ClickGUI，搜索、修改一个 UI 参数、关闭；确认鼠标位置、透明度和文本可见。
3. 用户接受 GUI 打开时 F8 不切换；以后先 Esc 关闭 GUI，再 F8。保持 Fly、Velocity 关闭，不修这项接受的限制。
4. 本轮约 30.6 秒录屏已保存；下一步返回 Core-only 基线。Core 平台等结果不因本轮 UI 展示改成通过。

本轮日志与配置路径：

- `runtime/experiments/core-launch-08-lb-ui.log`：实际启动、依赖加载与首次 JCEF 准备。
- `runtime/experiments/core-launch-08b-lb-autoworld.log`：菜单修复后的 MC 重启，沿用同一法环进程。
- `runtime/experiments/core-launch-08c-lb-production.log`：加载原始 JAR 的正式运行尝试。
- `runtime/experiments/lb-dev-remap-preflight.log`：构建预检。
- `runtime/experiments/lb-bootstrap-fix-build.log`：LB 主菜单入场修复构建。
- `runtime/experiments/lb-ui-initial-modules.json`：初始模块配置。
- `runtime/experiments/lb-ui-production-modules.json`、`core-08c-production-connected-status.json`：正式运行版展开配置及实际连接状态快照。
- `runtime/experiments/core-08c-ui-manual-native.log`、`core-08c-ui-manual-minecraft.log`、`lb-ui-manual-modules.json`：用户初步 UI 反馈时的日志与配置文件快照。
- `runtime/experiments/core-08c-f8-manual-native.log`、`core-08c-f8-manual-minecraft.log`、`lb-ui-f8-modules.json`：F8 人工反馈后的日志与配置文件快照。
- `runtime/experiments/lb-ui-08.mp4`、`lb-ui-08-frames/`：用户提供的完整录屏副本与五张原始采样帧。
- `bridge-base/elden-ring/mc-bridge/run/LiquidBounce/modules.json`：运行展开后的实际配置。
- `bridge-base/elden-ring/mc-bridge/run/logs/latest.log`、`runtime/er-bridge.log`：MC 与 native 后续运行日志。
- `runtime/downloads/liquidbounce-build-3530-download.json`：来源、哈希、JAR 元数据与嵌套依赖。

下载 ZIP/JAR、JCEF 缓存、配置、日志与后续录屏均留在 Git 忽略的本地目录；仅必要代码和 Markdown 提交到 dev/lb-poc，不推送或合并。
