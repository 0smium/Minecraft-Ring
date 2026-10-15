# 2026-10-14：成长与装备集中验证

用户在上一轮提交后授权“准备集中验证”。本轮从 `codex/b02-character-slice` 的 `c6585e65d96289d7c13eb925f7cefe7e07564ecc` 启动，功能提交为 `f14ebded1ffbd651bafef4bcca6fa732f450a553`。由用户操作游戏，代理仅部署、启动、读取日志并记录；未使用 computer use。

## 准备与部署

- 游戏启动前确认 ER/MC 均关闭。EXE 2.7.1.0，完整 SHA-256 `1a3547101327f65d0c76da2f9190ac0aa66871ea42bae2aecc61e11a8b597891`；标题 App 版本仍需本轮用户确认。
- `Launch.ps1 -B01 -CheckOnly` 通过，重新生成并核对 Core/Character 0.3.2 配套 JAR，固定 LB #3530 与 Skills 0.19.2。
- 当前 Steam 账号与存档目录匹配；关闭游戏时通过测试卢恩工具备份主存档和 `.bak`。完整 MC 世界、LB modules 配置、旧 DLL 和旧日志另存本地备份。未重跑安装器，`installation.json` 摘要仍为 `8551ce1521c33e08cbfe909caa5bc7a92af9369e1fad591aa1789071cffdf3c2`。
- 已部署候选 `dinput8.dll`（`a832b00032e9d898e997fc50bd55f907919f04c756c42a097f16c0bf165015b0`）和 `Game/erbridge/erbridge_core.dll`（`2d214812773bc4606507e6a51a365e70bd2044b29701a54ae229eaf09c72c3ef`），复制后摘要匹配。
- Core JAR SHA-256 `933e43f008cbad0c48d347caec1d728b0f7db989da30c3032191451cc4db1374`；B01 Character `3fc042f4e8165d7fb60765a6c8b861200da227c5cf846baa3dabd4719aaec161`。
- 启动前世界是自由模式；既有绑定基准/最高等级均 164，Skills 无已学节点及点源，无预设恢复日志。无需重新 bind 或 migrate。HUD Blur、Minimap 已关闭，行为模块均关闭。
- Steam 后通过 `start.bat -B01` 启动项目离线流程。TarnishedTool 未启动/附加。
- 首次启动 ER 在 Core 初始化前退出，MC 未启动；对应时段 Steam 仍在登录，未见新的原生崩溃文件。Steam 登录成功后按同流程重试；退出原因尚不能仅凭时间关系确认。

本地路径：

- `runtime/backups/growth-equipment-20261014-080614/`：本轮 MC/配置/旧 DLL 备份。
- `runtime/backups/test-runes-20261014-080613-157ee9/manifest.json`：本轮关闭 ER 时创建的存档备份，随后用于本轮测试卢恩发放。
- `runtime/experiments/growth-equipment-live-deployment.json`：实际部署与摘要。
- `runtime/experiments/growth-equipment-live-preflight.log`：预检。
- `runtime/experiments/growth-equipment-live-launch.{stdout,stderr}.log`：启动日志。
- `runtime/experiments/growth-equipment-live-launch-2.{stdout,stderr}.log`：Steam 登录成功后的重试日志。
- `runtime/er-bridge.log`、`bridge-base/elden-ring/mc-bridge/run/logs/latest.log`：实时原生/MC 日志。

## 实测结果

| 项目 | 状态 | 观察 |
| --- | --- | --- |
| 0.3.2 Core + Character + Skills + LB 实际加载 | 启动日志通过 | ER PID 28868，Core generation 1 loaded (ok)；MC PID 3960，列出两个 0.3.2 模组和 Skills 0.19.2；Skills 数据包加载成功，Steve 已进入 ER Bridge。法环 Continue 后接管另验 |
| Continue 后场景、HUD、输入与安全接管 | 接管日志正常，完整观察待补 | 共享内存 player_valid/camera_override/compositing 均 true；用户已能打开两页。未单独确认 30 秒静止项 |
| 六类技能树、零点锁定 | 零点拦截通过；UI 有缺陷 | 用户确认未学时开不了 Speed/Aura；树太线性并有三项紫黑图标 |
| journey 预设应用与 Speed 执行 | 通过（用户观察 + 应用日志） | 08:21:00 正式应用，测试额度 116、消费 116；用户确认其他测试正常，包括学成后 Speed 开启起效。其余 13 能力不能据此全部标通过 |
| journey 预设 clear 恢复 | 通过 | 08:24:06 撤销测试点；08:24:09 Lv.164、0/0、全部 rank0；用户确认正常 |
| 一件真实防具装配、卸下、图标及护符容量 | 展示有缺陷；装卸受阻 | HINV count=1024/total=1106、TRUNCATED，writableSlots=0。原生容量值其实为 4，但已采样的已装备护符仅 1 条，需先解决截断与显示 |
| 正常退出/重进后的装备保存 | 未测 | 留到装卸完成后 |
| 测试卢恩 | 原生发放及读回通过 | 08:23:26 单次 +2,000,000；4,800 → 2,004,800，等级仍 164；菜单人工对照待后续升级 |
| 真实升级、购买发点 | 通过，按实际 173 级记录 | 08:25:03 Lv.165、1/1；随后原生查询为 Lv.173、等级账本 baseline164/highest173；Skills 真点源9，已学 Speed I/II（消费8，应余1）。用户确认购买后可用 |
| 退款撤权、真实节点预设恢复 | 通过 | 用户确认退款停用并锁住 Speed、预设 clear 后正确恢复。08:31:40 Lv.173/9点/rank0；08:32:25 恢复 Speed rank2、1/9点，测试来源0 |
| 生存模式 Fly 权限授予/撤销 | 通过（用户观察 + abilities 日志） | 用户确认悬停稳定、clear 后正确恢复；mayfly 0→1→0，flySpeed .05→.1→.05；最终 Speed II、1/9点 |
| MC 退出重进的技能/点数保存 | 通过 | 08:40:57 重进 status=Lv.173、1/9、Speed II、Fly rank0；08:41:09 mayfly=0、flySpeed=.05；用户确认正常，未重复发点 |

构建与离线 fixture 的既有结果见 [上一轮记录](2026-10-14-growth-equipment-v1.md)，不代替此表的游戏验收。

## 第一组用户反馈与后续修订

用户明确要求“先记下，然后继续测试”，因此本轮先保留候选收集证据，不为这些展示问题中途重启。

- 技能树：当前布局过于线性，希望更有构筑感。调整布局/分支关系，后续按当前点数预算重新核对；不把已经显示节点当作购买/授权通过。
- 紫黑技能图标：08:09:51 日志确认 `minecraft:textures/item/shield.png`、`compass.png`、`bricks.png` 不存在。应按实际资源类型修正生成目录与图标表示。
- 统一入口：以 E 打开原版物品栏，再点 ER 进入；不额外占用 I，避免和 LB 功能绑定争键。
- ER 页只展示 ER 装备与物品，不重复显示 MC 防具和背包；两套实际库存/装备仍独立保留。扩大图标和页面容量，减少频繁翻页，改善透明背景下的辨识。
- 原图覆盖：防具原图被皮胸甲、护符原图被金粒等 MC 占位物遮住；应正确处理绘制顺序或取消占位图绘制，缺图仍保留明确回退。
- 护符容量：用户原生角色已有 4 槽，装备页仅看到 1 个，记为实际展示缺陷。随后只读共享快照确认 footer 的 talismanSlots=4，并非已证明容量链算成 1；已采样的已装备护符仅 slot18 一条，其他装备可能被截断漏掉。
- 操作方向：扩为可拖拽装卸、可丢弃的 ER 大物品栏。丢弃须调用法环真实丢弃/留置流程、实际数量及结果同步；当前投影格不可取出，不表示此需求已经实现。武器、护符写入亦不因 UI 意图而视作已完成。

随后用户完成 challenge 的零点拦截 → journey 预设 → status/K → Speed 短测，回答未学时“开不了”、“其他测试都正常”。08:21:00 日志记录正式应用 journey、测试点 116、消费 116。日志未见独立 status 输出，不补造逐项参数截图或数值调节结果。

第二组界面要求：挑战模式 ClickGUI 隐藏未学模块，学成后才出现；模式和可调参数同样按学习结果逐步展示，减少无关设置。后续从现有统一授权映射到 WebGUI 列表/设置描述，持续保留底层执行限制；免费 HUD/ClickGUI 与自由模式照常可见。用户只要求记录后继续当前验证，本轮尚未实现此显示过滤。

固定 LB 源码只读核查：最小切入点是 `ModuleFunctions.kt` 的 `getModules` 列表和 `acceptGetSettingsRequest` 的 interop JSON；只过滤界面响应，不能更改 ModuleManager 注册表、持久化 Hidden 或全局共享配置序列化器。原主题仅在挂载时获取模块和设置，没有监听 `ValueChangedEvent`；授权变化须合并调用 `ModuleClickGui.reloadView()` 或补等效事件刷新。单选模式如需隐藏下拉且仍展示子参数，还需保留原配置树路径并调整主题显示。此处是后续实现方案，尚未实装。

原生测试卢恩发放日志：`runtime/experiments/growth-equipment-live-runes-award.log` 和 `runtime/experiments/test-runes/20261014-082326-2cf2ae.jsonl`；status=0、actual_delta=2000000、before=4800、after=2004800、level=164。只发放一次，未直接改等级/MC 点数。后续验证序列为 clear 后 0 → 165 级 1 → 168 级 4 → Speed I 购买后 0。

用户随后回答升级购买步骤“正确”，并报告已学 Speed 可用。只读核对发现实际进一步升到 **173 级**、购买了 **Speed I + II**，因此以实际证据记录：等级额度 9、已消费 8、预计剩余 1，而非把原先计划中的 168 级/剩余 0 当成最终状态。08:30:19 原生查询 level173、余额196056、delta0，日志 `runtime/experiments/test-runes/20261014-083019-4d4a39.jsonl`；真实 Skills 存档 `unlocked_skills=[speed_2,speed_1]`、`points={erbridge:er_level:9}`。未回滚等级或余额。

第三组用户反馈：Speed 已开时，K 技能树快速开关不影响模块，停留一会再 Esc 会关闭 Speed。源码链路支持暂停恢复竞态：SkillsScreen 使用默认暂停，集成服务器暂停期间不发 B01 心跳；客户端 `receivedAt` 仍计墙钟，只在暂停期间跳过 3 秒检查；退出菜单后首个 tick 可能先判超时并调用模块停用，随后新授权不自动重开。尚未加诊断复现，先记录为与用户现象相符的候选原因；后续应正确处理同一连接的暂停时间/恢复握手，并保留实际断连、死亡、退款的撤权。

原生交互需求：MC 中触发赐福休息/升级等原生交互后自动交给法环，离开原生菜单时自动回到 MC，减少正常流程的 F8。需依据原生交互/菜单生命周期跟踪进入和离开，逐项覆盖赐福、对话及其他适用界面；不要把用户主动 F8/Alt+Tab 也强制拉回。当前记录需求，尚未实装或测试。

退款/恢复组已完成：08:31:32 正式退款；08:31:40 status=Lv.173、9/9、所有 rank0。08:32:02 starter 正式应用，+14 测试点/消费14；08:32:16 clear；08:32:25 status=Lv.173、1/9、speed rank2、上限0.33，其余 rank0。恢复日志 phase=ready、testPoints=0，原节点 speed_1/speed_2。用户回复“正确”，确认撤权和恢复行为。

Fly 组已完成：08:33:40 生存 abilities mayfly=0/flySpeed=.05；08:33:50 flight 预设 +102 测试点/消费102；08:34:21 mayfly=1/flySpeed=.1；08:34:34 clear，08:34:37 mayfly=0/flySpeed=.05；08:34:43 status=Lv.173、1/9、speed rank2、fly rank0。用户确认 10 秒低空悬停及落地后恢复正确。三次服务端 `flying` 字段读数均为0，故只据此确认服务器许可授予/回收；稳定悬停效果依据用户观察，不将字段写成 flying=1。

用户随后正常退出 MC（原 PID3960 已结束），ER PID28868 保留。完整日志归档到 `runtime/experiments/growth-equipment-live/minecraft-round1-complete.log` / `native-round1-complete.log`；Skills 存档只读快照 `skills-after-round1.dat`。同版本重启日志 `runtime/experiments/growth-equipment-live-rejoin.{stdout,stderr}.log`。

重进遇到启动器缺陷：原 `start.bat -B01` 预检通过，但 `productionLbClient UP-TO-DATE` 跳过运行，日志显示 BUILD SUCCESSFUL 而没有新 MC 进程。保留同一配置/JAR，随后以相同项目 Gradle 运行 `productionLbClient --rerun`，仅强制运行任务，不改变代码或部署。一次性启动脚本 `runtime/experiments/growth-equipment-rejoin-run.ps1`，日志 `growth-equipment-live-rejoin-rerun.{stdout,stderr}.log`。后续启动器应确保运行任务每次实际执行。

`--rerun` 后 MC 新进程 PID27180 在 08:39:53 进入 ER Bridge，08:39:59 恢复合成与输入。原生 PID28868 不变；Core/Character JAR 摘要与本轮启动前完全一致。已请用户核对 status、abilities、Speed II/剩1点及 Fly 锁定，再恢复 free 并正常退出两游戏。

重进实测通过：08:40:57 Lv.173、1/9点、speed rank2、fly rank0；08:41:09 mayfly=0、flying=0、flySpeed=.05。用户确认“正常且退出”，两游戏进程均已结束；MC 日志正常保存，原生在 MC 退出后回收合成画面。最终日志归档为 `minecraft-rejoin-complete.log` / `native-complete.log`。

退出后配置仍为 challenge=true（未见 free 命令日志），因此确认两游戏关闭后，备份原配置并原子写回测试前的自由模式，revision 5→6；绑定、等级、真实点数和已学 Speed I/II 均保留。备份 `runtime/experiments/growth-equipment-live/b01-before-free-restore.properties`。本轮最终角色 Lv.173，真实额度9、已消费8、余1，测试点0；不回退本次真实升级。

## 下一批修复顺序

1. 修正单人暂停/恢复时策略租约误过期，以及启动任务被 Gradle 判成 UP-TO-DATE 而漏启动。
2. 消除完整库存的 1024 条限制，保留截断拒写与身份/token校验；同步修正原图绘制，并按 E→ER、较大格子/更多物品、不重复 MC 页的要求调整界面，再验证一件真实防具装卸/保存。
3. ClickGUI 按解锁显示模块、模式和设置，修正技能图标与树布局。参数显示过滤不修改持久配置，不替代已有执行约束。
4. 在核实原生菜单状态后实现赐福等交互自动交接。拖拽/原生丢弃、武器/护符写入按实际原生接口继续扩展，不靠投影物品伪造结果。

本轮没有修改实现代码。成长确认范围为上述明确记录项；AirJump、Step、Scaffold、Velocity 等新分阶效果，其他 profile、状态组合、骑乘和真实装备装卸未因本轮通过而自动验收。

自动原生交接静态核查：已有 R action 请求/确认和 Overlay 双向切换，但成功交互目前只重采地形，HOST_BUSY 不包含赐福菜单。CanExec=0 还可能是对话/拾取，不能直接当作赐福菜单状态；需补验证过的菜单进入/结束信号及 MC 发起交互归属。

### 本轮阻塞证据

HINV v2 READY，flags=3（MC_CONTROL | TRUNCATED）、count=1024、total=1106、writableSlots=0、talismanSlots=4。当前固定容量无法容纳此角色完整库存；保护逻辑正确停止写入，但因此不具备本轮一件防具装卸的验收前提。用户不需要反复点击。先完成成长测试，再修复库存采样/展示；不要仅取消截断保护来放行写入。

测试卢恩只读查询日志：`runtime/experiments/growth-equipment-live-runes-query.log`，原生结果 status=0、level=164、before=after=4800、actual_delta=0。

只读 UI 证据保存至 `runtime/experiments/growth-equipment-live/inventory-ui-evidence.json`。`HostEquipmentMenu` 始终创建 4 防具 + 4 护符格；当前容量 4，因此 Screen 不画锁定 ×，但装备列表只能从已采样 entries 查到 nativeSlot18 的一个护符。尚未读取截断尾部，不将“另外三个一定在尾部”写成确定事实。

图层原因已在代码确认：`super.render` 先按原版 Slot 在 z+100 绘制皮甲/金粒；后续 `HostItemIcons` 在 z0 绘制，开启的深度测试仍使其被占位模型遮挡。后续应替换 ER 投影槽的物品绘制，而不是只增加后画的图片。

08:21:19 另见一次 LB `Direction must not be zero` 异常，栈来自 Scaffold 选取方块放置面（`LineSegment → Face.getEdges → NearestRotationTargetPositionFactory → ScaffoldNormalTechnique`）。作为地形面与 Scaffold 兼容问题留待复核；用户未报告此时崩溃，不因此重测已完成的 Speed 授权。
