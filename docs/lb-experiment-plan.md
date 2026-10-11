# Minecraft-Ring × LB 实验计划 v0.3

2026-10-11 · @John Titor  
主仓库：https://github.com/0smium/Minecraft-Ring  
配套文档：[开发方案 v0.2](lb-development-plan.md)

> 本版整合 v0.2 的三个关卡、源码复核修正和提前进行 GUI/视觉展示的要求。当前所有运行实验均未执行。本轮只更新文档；安装、启动和代码修改由后续开发任务执行。

## 1. 目标与关卡总览

先确认 Core，再限时验证 LB 共载。共载通过后，先取得 GUI/视觉展示结果，再测 Fly 和 Velocity。共载不过就停止 LB 调试，转保底模块。

| 关卡 | 时间盒 | 通过判据 | 不过怎么办 |
| --- | --- | --- | --- |
| 0：Core 基线，不加载 LB | 半天 | 启停两次无残留；创造飞行悬停 120 秒；站立随平台、飞行不被平台带走；相同攻击条件下实际 HP 损失与击退可重复观察 | 悬停死亡先查死亡路径，不进关卡 1；其他阻塞先定位 Core |
| 1：LB 共载，行为模块关闭 | 不超过一天 | 进入桥接世界并连上 ER；画面合成正常；LB 界面能开关；界面开关状态下 F8 不串输入；无阻塞注入的 Mixin 错误 | Mixin 冲突 2 小时无解转保底；JCEF 准备受阻按依赖失败记录并转保底 |
| 1.5：GUI 与视觉展示 | 半天预算 | 至少一组 GUI/HUD 和一项世界空间效果完成验证，最终录屏包含法环背景与 LB 效果 | 单项失败记录并跳过，不阻塞关卡 2；基础 UI/合成不能运行仍按关卡 1 处理 |
| 2：Fly、Velocity 单项 | 一天 | FlyCreative 悬停与平台行为正确，双方有效飞行状态一致，关闭后恢复；Velocity 不改变同等攻击的伤害结算，速度/位移变化可解释 | 记录失配层，只修本轮阻塞；不扩展全模块迁移 |

时间盒是排障预算，不是保证完工的工期。阶段耗尽时产出记录，明确通过、失败或受阻，不无限延长。半天的视觉预算不要求完成所有候选模块；没有 world-space 效果通过时，记录为“视觉部分通过”，仍继续关卡 2。

每次实验记录实际项目提交、原始代码基线、LB 制品哈希、配置、步骤、结果和日志路径；没有记录的不算通过。状态使用：未执行 / 通过 / 失败 / 受阻 / 不适用。

## 2. 基线、配置和仓库

- Bridge 原始代码：`711015afa67ab25c7b9597c68038718d1cef322e`，源码版 0.3.0。
- ER：App 1.17.1、exe 2.7.1.0；运行前核对实际安装版本。
- MC：1.21.1；Bridge Loader 0.19.5、Fabric API 0.116.17+1.21.1。
- LB：v0.22.0 / Build #3530，提交 `e5a0dbf5458b063d3028e69e04762b8b25b998b5`。
- Kotlin 依赖候选：Fabric Language Kotlin `1.13.0+kotlin.2.1.0`。

详细工具链与来源见总方案。当前只开发 `0smium/Minecraft-Ring`；LB 先作为外部固定依赖和只读源码参考，不创建额外仓库。

| 配置 | 定义 |
| --- | --- |
| A | Core，不加载 LB |
| B | Core + LB，移动/战斗/网络等行为模块关闭；GUI/HUD按验证需要开启并记录 |
| C | B + 单个待测模块；记录具体模式和参数 |
| D | Core + 保底模块；与 LB 路线分开记录 |

“模块全关”不应误解成连 GUI/HUD 验证都不允许。LB 的默认模块状态需要检查，不能只口头说“全关”。每一步保留实际配置。

若在新电脑克隆：

```powershell
git clone https://github.com/0smium/Minecraft-Ring.git
Set-Location Minecraft-Ring
git remote add upstream https://github.com/siddoff/Minecraft-Ring.git
git remote set-url --push upstream no_push://upstream-disabled
git fetch origin
git switch -c dev/lb-poc origin/docs/lb-plans-20261011
```

已有副本先检查 `git status` 和 `git remote -v`，不覆盖改动或重复添加 remote。上面的起点是本次文档分支；文档合入自己的 main 后可从更新后的 main 开始。实际开发提交另行记录，不继续把 HEAD 写成原始代码哈希。

## 3. 关卡 0：Core 基线

1. 按上游 [Windows 安装文档](installation.md)构建并启动未修改 Core。`Install.ps1` 会备份存档并移动识别到的已有模组文件；初次安装后保留 `installation.json` 和对应备份，不把重跑安装器作为日常更新方法。
2. 启动和退出两次，记录隐藏角色、输入焦点和控制释放情况。若本轮实验产生冻结/残留，先处理到能开展下一次对照。
3. 创造模式开启原版飞行，在空旷处悬停 120 秒（旧 T03）。到升降平台验证：站立时随平台移动，飞行悬停时不被自动带走（旧 T04）。
4. 查看 `runtime/er-bridge.log`。`letting it die` 只表示连续 HP 钳制判断触发；`the player took` 只表示观察到 HP 下降。二者不能单独确定是敌人、坠落计时、死亡区还是其他机制。
5. 切 `/gamemode survival` 测击退：同一敌人的同一招式，每次恢复 HP 和站位，等待受伤无敌结束，装备、盾、状态保持一致。先收集三次可比较样本；记录受击前后实际 HP、客户端与服务器位置、ER 位置和位移观察窗口。
6. `Elden Ring hit` 日志里的 Minecraft damage 是传入伤害函数的值，不能替代实际 HP 损失。护甲、盾、无敌帧可能改变实际结算。
7. 扔末影珍珠等 MC 投射物作基线回归，记录法环地形上的命中/落点行为，供关卡 1 对照。
8. 若悬停死亡，先添加必要只读诊断。社区 `CSChrFallModule` 的 `fall_timer`、`force_max_fall_height`、`disable_fall_motion` 可作研究线索；偏移与对象关系必须在目标 exe 验证，不能仅由另一版本结构体推导后直接写入。先结合 HP、角色身份、控制状态、坠落状态和动作定位原因。

主要诊断位置：

- `runtime/er-bridge.log`，异常时的 `runtime/eldenring-crash.*`。
- `bridge-base/elden-ring/mc-bridge/run/logs/latest.log`。
- `python tools/status.py` 的桥接状态。
- 安装的游戏版本、Java、GPU/驱动信息。

对应总方案问题：B01、B02、B03、B07。关卡 0 不承担全面状态伤害、抓取或高速碰撞验证。

## 4. 关卡 1：LB 共载

限时一天。目标是 LB 和 Fabric 桥接处于同一 MC 实例，ER 仍是另一个游戏进程。

1. 从 [LB 官方 Build #3530](https://liquidbounce.net/download/3530)获取制品；发布下载可能为含 JAR 的 ZIP，解包后记录实际 JAR 的 SHA-256。
2. 在 `mc-bridge/build.gradle` 将该 JAR 与 Fabric Language Kotlin 加为 `modRuntimeOnly`，作为首轮候选配置。该写法尚未实测；预期通过 Loom 的 mod 依赖处理完成开发映射重映射，并检查嵌套依赖是否齐全。Bridge 的 Loader/API 保持基线版本，不安装第二份。
3. 原启动器使用 `remapJar runClient`，不能把它当成普通生产实例的 mods 目录。依赖解析、Mixins、Access Widener、嵌套库等失败分别记录，不一概叫“映射不兼容”。
4. 缺依赖时 LB 才下载 JCEF；缓存齐全可直接初始化。下载准备期间客户端不能正常继续使用；下载/初始化错误可弹窗并结束 MC。可提前在同一配置所用的依赖目录准备缓存；不要假定另一个启动器或实例下载过就自动共享。失败记为“依赖准备受阻”。
5. 检查配置 B：进入 `ER Bridge` 并连上法环；画面正常；打开关闭 ClickGUI；界面打开与关闭时都来回 F8；不串键、不丢鼠标控制；日志无阻塞启动或功能的 Mixin 错误。
6. 与关卡 0 对照末影珍珠、基础移动和普通受击。无启动错误不代表所有注入功能正确。
7. 已核对：`711015a` 的 `erbridge.mixins.json` 未注册 `BlockGetterMixin`、`CollisionGetterMixin`、`EntityCollisionMixin`、`ServerPlayerTeleportMixin`。LB 的 `BlockGetter.clip` Overwrite 不能据此认定正在与该停用注入冲突；本轮不为测试启用这些旧路径。

**止损：** 已定位的 Mixin 冲突在两小时内无法解决，就停止并转保底；JCEF 准备受阻按本轮约定同样转保底。总投入不超过一天。依赖失败、功能冲突和时间耗尽使用不同结论，不据网络失败宣称架构不可行。

## 5. 关卡 1.5：GUI、视觉和视频展示

共载通过后提前执行，预算半天。每次只新增一个模块/效果；先保证画面可见、可操作、可录制，再处理装饰性设置。

| 批次 | 候选功能 | 展示内容 | 通过标准/限制 |
| --- | --- | --- | --- |
| 第一批 | ClickGUI | 在法环画面上打开 LB 面板、搜索与调参数 | 背景合成正确，鼠标不偏，缩放和 F8 正常 |
| 第一批 | HUD：模块列表、按键、通知 | 操作和开关的实时反馈 | 状态对应实际模块，文字清楚，无截断/残留 |
| 第一批 | Breadcrumbs | 移动轨迹 | 轨迹与 MC 位置一致，转镜头不漂移，关闭/切世界可清理 |
| 第一批 | JumpEffect | 跳跃光圈等效果 | 在正确位置产生并消失，不遮住整个宿主画面 |
| 第二批 | ESP 的 Box 模式、Tracers | 法环敌人方框与追踪线 | 筛选正确、位置跟随、死亡后消失；穿墙效果单独记录 |
| 第二批 | Nametags：名称、距离 | 敌人上方标签 | 名称、距离及单位明确；内部代号不冒充已完成本地化 |
| 补充项 | 法环 HP、TargetHUD | 目标生命变化 | 真正读取宿主 HP，目标切换/死亡正确 |
| 补充项 | Trajectories、ItemChams | MC 弓箭/珍珠轨迹和手持物品效果 | 限 MC 路径，落点与基线一致，不声称覆盖 ER 原生投射物 |

### 5.1 三个公共适配点

**视觉筛选。** LB 的 ESP、Tracers、Nametags 共用 `shouldBeShown` / TargetConfigurable；当前 ErEntity 不是它接受的具体生物类型。集中适配视觉入口，让有效代理按敌人/NPC等所需分类参与显示；默认展示敌人，并保留存活/移除检查。不要仅为显示把所有代理伪装成僵尸，也不要顺带修改自动攻击策略。

**显示数据。** Bridge 已有 ErEntity 的宿主 `hp()/maxHp()` 字段；LB 默认读取 MC health。名称和距离可先测，HP/TargetHUD 通过显示适配读取宿主数据。初测 Box 使用静态颜色，避免未经适配的健康颜色误导。TargetHUD 的目标选取需要另验，不强制启用 KillAura 才能展示。

**绘制与合成。** MC 世界层会按 ER 深度被遮挡，HUD 层直接叠加。世界空间 ESP 即使在 MC 内关闭深度测试，也不保证最终穿过 ER 墙面；分别记录“正常可见”“遮挡时可见”的效果。ErEntity 正常不绘制怪物网格，所以 Glow/Outline/Chams 不作为本轮必达项。

### 5.2 GUI 与录屏验收

1. GUI 打开 → 搜索 → 修改一个参数 → 关闭 → F8 切换，重复确认鼠标位置和输入归属。先关闭界面模糊背景，透明合成稳定后再测试模糊。
2. 分别开启 HUD、轨迹、跳跃效果；行走、跳跃、转镜头并关闭效果，观察对齐、透明度和清理。
3. 代理类效果选择一个稳定场景，观察敌人移动、受伤、死亡以及离开采样范围后的变化；未适配血量时隐藏 HP 展示。
4. 用最终准备发布视频时的录制方式录 30–60 秒。检查成片包含法环背景、LB UI 和效果；不能只看预览窗口。当前桥接把 MC 分层传给宿主合成，仅录错窗口可能漏掉一部分画面。
5. 记录配置 B 与每个 C 的帧率/帧时间变化；不给未知硬件预设 FPS 保证。保存展示配置，重启后检查可复现性。

建议第一段素材顺序：法环场景 → 打开 LB 面板 → 开启轨迹与跳跃效果 → 敌人方框/标签 → 后续补入已通过的 Fly。Velocity 在关卡 2 后用同条件受击对照展示。

只有 GUI/HUD 通过也保留为有效成果；单个视觉功能超过预算就记为待适配，不阻塞 Fly/Velocity。如果失败已破坏基础合成或输入，则属于关卡 1 未通过。

## 6. 关卡 2：Fly 与 Velocity

### 6.1 FlyCreative 优先

1. 区分“MC 创造模式”和“LB Fly 的 Creative 模式”。关卡 0 的创造悬停只能证明 Core 基线；关卡 2 还要验证预期游玩模式下的 LB 能力，建议单独记录生存模式结果。
2. 先关闭 BypassVanillaCheck；记录 ForceFlight、速度和其他选项，使用保守速度。不要同时启用 Velocity/Speed。
3. FlyCreative 的实现修改客户端能力，没有在该模式实现中显式完成集成服务器的对应同步；其他原版/模组路径是否产生同步要实测。两侧分别记录 `allowFlying`、`flying` 和实际平台绑定。
4. 平台服务端使用 `TerrainManager.updateMovingSupport` 的 ServerPlayer；客户端读取点为 CameraSync、MovingPlatformClient。潜在异常是客户端悬停，而服务端仍尝试贴回平台，但这是待复现假设。
5. 重复 120 秒悬停、升降、平台附近悬停、关闭后落地。通过标准是两侧的**有效飞行判断**一致，且不会被错误吸附；如果后来增加独立飞行声明，分别记录原版字段与声明，不能混写。
6. `FlyCreative.disable()` 直接清除 `allowFlying/flying`，没有恢复原值；测试后检查原版权限。必要时切换游戏模式恢复测试条件，并记录这一现有限制。

### 6.2 Vanilla 与统一状态仅在需要时做

Vanilla 只改速度、不设原版标志，并默认周期性把竖直速度改为 -0.04；先关 BypassVanillaCheck。不要在 Creative 首测未完成前同时迁移所有 Fly 模式。

统一“是否飞行”需要状态到达集成服务器，而非仅改三个函数名。NativeTerrainCollision.recoverLanding 的相关分支当前不在生效调用路径，不把它作为首轮修改目标。

集成服务器位于同 JVM，可将线程安全的共享声明作为原型选项：至少按玩家/世界区分，保证 volatile/原子或等效同步的可见性；在模块关闭、控制交还、断开、卸载世界时清理。涉及多个字段时以一致快照交接。若该方案不可靠，改用 MC 客户端到集成服务器的消息；本轮不因此重做 native 协议。

### 6.3 Velocity Modify 三组对照

1. 三组设置：关闭；Horizontal/Vertical 均 0.5；两项均 0。Chance 固定 100，Filter 为 Always，OnlyMove 关闭，其他选项记录。
2. 同一敌人同一招式；每次恢复 HP、站位并等待无敌结束，保持装备、盾、状态一致。三次样本作为初筛，不把不同招式混算。
3. 记录受击前后实际 HP、原始/处理后速度事件、MC 客户端和服务器位移、ER 位置，以及用于比较的时间窗口。逐级验证事件有没有进入 LB。
4. 通过标准：相同攻击条件下不改变伤害结算；速度更新按设置处理；最终位移变化可解释，50% 不要求位移恰好减半。
5. 全零设置取消收到的速度更新，不是每帧把当前速度清零。ER 抓取、击倒、动画位移不自动属于这一能力。

Fly 和 Velocity 都通过后，Speed、Sprint、AirJump 才分别阅读源码和独立验证。当前没有宣称这三项已经完成审计。

## 7. 保底路线

关卡 1 触发止损后停止 LB 调试，在主仓库中开发小型独立模块，仍复用公共桥接入口：

- Fly：每 tick 计算移动，通过统一飞行声明让两侧知道外部正在控制竖直移动。
- Velocity：核对 MC 1.21.1 的 `handleSetEntityMotion` 等实际入口，只处理本地玩家的对应速度更新，取消或缩放并保持伤害流程。该类包不天然携带“这是击退”的完整语义，记录可能涉及的其他速度来源。
- UI：简单设置页/热键、当前模式 HUD 和开关提示，使保底也能录制演示。
- Speed、Sprint、AirJump 暂不做；Fly/Velocity 稳定后再决定。

保底代码独立实现行为，不复制 LB 源码；保留项目原有许可与来源记录。保底产物和 LB 兼容结果分开命名、分开记录。不要为了完成保底再创建第二个仓库或复刻整个客户端。

## 8. 本轮边界与剩余不确定性

不做：全面会话 epoch/租约重构（B01、B06）、正式打包、Nightreign、完整碰撞与抓取场景、全部视觉模式和 LB 全模块迁移。旧 T12/T13 后置。

例外：退出后仍冻结/隐藏角色、旧伤害或旧命令重放等已干扰下一次实验时，可局部修复。状态伤害暂不用于 Velocity 基线，以免 B04 污染结论。

ExhibitionClient 仅作功能点子参考，不进入本轮构建/移植。没有明确许可的第三方代码不直接并入本项目。

需要实际运行回答：

- 本地连接的服务端速度更新是否完整经过 LB 包事件，是否被其他逻辑再次覆盖。
- JCEF、HUD、世界特效在捕获时机、透明度和宿主合成中的表现。
- F8 在各种界面、输入焦点与暂停状态下的行为。
- FlyCreative 在客户端和集成服务器的实际状态是否一致。
- 通用类 Mixin 对服务端实例的具体影响。`client` 表示客户端运行环境加载，不自动代表所有修改只作用于客户端玩家；需看具体实例/线程条件。
- 实体视觉筛选、HP 显示、采样丢失与宿主深度对标记的影响。
- Speed、Sprint、AirJump 的实现细节和适用模式。

## 9. 实验记录模板

每次实验在 `docs/experiments/` 保存精简 Markdown 记录；原始日志/视频放工作目录，Git 中记录可定位的路径或交付链接。未执行项目不填“通过”。

```text
实验编号 / 日期：
负责人：
关卡 / 功能：
状态：未执行 | 通过 | 失败 | 受阻 | 不适用
主仓库实际提交：
未提交改动摘要：
Bridge 原始代码基线：
LB 版本 / JAR SHA-256：
游戏 exe / MC / Loader / API / Java：
配置：A | B | C | D
启用模块、模式和参数：
场景、敌人招式、装备、游戏模式：
操作步骤和观察时间窗口：
预期结果：
实际结果：
相关 MC HP / client/server 位置 / ER 状态：
相关 GUI / 渲染 / 录屏表现：
帧时间或帧率观察：
日志 / 视频路径：
初步责任层：依赖 | LB | MC client | MC server | Bridge | ER | 未知
下一步最小任务 / 是否触发止损：
```

第一次执行只需产出：Core 基线记录、共载结果、视觉展示清单和样片情况，以及 Fly/Velocity 的单项结果。失败和受阻只要定位清楚、记录完整，同样是本轮有效产出。

## 10. 源码定位

完整固定版本链接见[总方案资料索引](lb-development-plan.md#13-资料索引)。优先查看：

| 问题 | 源码入口 |
| --- | --- |
| 依赖、开发运行 | Bridge/LB `build.gradle`，Bridge `Launch.ps1` |
| JCEF 与退出 | LB `JcefBrowser.kt`、`BrowserManager.kt`、`ErrorHandler.kt` |
| 飞行与速度 | LB `FlyGeneric.kt`、`VelocityModify.kt` |
| 平台与原生生命 | Bridge `CameraSync.java`、`MovingPlatformClient.java`、`TerrainManager.java`、`game.cpp` |
| 实际伤害 | Bridge `entity/CombatBridge.java` |
| GUI/HUD、自身特效 | LB `ModuleClickGui.kt`、`ModuleHud.kt`、`ModuleBreadcrumbs.kt`、`ModuleJumpEffect.kt` |
| 实体显示 | LB `CombatExtensions.kt`、`ModuleESP.kt`、`ModuleTracers.kt`、`NametagTextFormatter.kt`；Bridge `ErEntity.java` |
| 合成与代理网格 | Bridge `FramePassthrough.java`、`ErEntityRenderer.java`、`compositor.cpp` |
| 实际启用的注入 | Bridge/LB 的 `*.mixins.json`，不能只搜索类文件 |
