# 2026-10-14：集中实测后的成组修复，0.3.3 候选

用户要求先推进修复、研究技能路线与 EldenCraft，再集中测试。本批从 `codex/b02-character-slice@ed6f2680a55ca15c7a824da51ab163da7cfac625` 开始；本文件所在提交记录本批最终源码。PR [#6](https://github.com/0smium/Minecraft-Ring/pull/6) 保持 draft，未合并 main 或推送 upstream。

**本批没有部署 DLL、启动 ER/MC 或操作游戏，也没有使用 computer use。** 下表均为构建、磁盘核查或私有 fixture；上一轮0.3.2实机证据保留在[独立记录](2026-10-14-growth-equipment-live.md)。

## 实现

| 问题 / 需求 | 本批代码变化 | 验收边界 |
| --- | --- | --- |
| 实际1106条库存被1024上限截断 | HINV v3改到独立0x380000区，上限8192，覆盖两个受限4096分区；原生复用成员缓冲避免大栈复制 | 保留截断拒写、完整内容token、身份/生命/区域/时限/nonce/结果读回；实际1106条尚待重读 |
| ER页太小、占位模型遮图、I占键 | E→ER入口；ER独立50格、26px原图与分类；四防具/四护符；图标替换原格绘制，缺图明确问号 | 未渲染游戏画面；不删除MC装备/背包；容量取原生解锁数据 |
| 拖拽装备 | 列表防具拖入对应槽；穿戴槽拖回列表或右键卸下；独立意图带页面epoch和原生物品身份 | 武器/护符写入、消耗品、原生丢弃仍未实现；不会取出MC复制品 |
| K久留后Speed被关闭 | 本地暂停期间冻结策略租约计时，跳过恢复首帧的暂停间隔 | 真正运行中失联、空策略、断连、退款仍撤权；不自动重开模块 |
| ClickGUI未学模块与参数太多 | 仅过滤LB REST列表/设置响应，保留免费模块、已学能力、合法模式/范围及必要父路径；授权变化合并刷新原GUI | 不修改ModuleManager、持久化Hidden或配置序列化；真实前端刷新待验 |
| 技能树紫黑图标、布局单调 | 使用Skills原版item图标；六区径向布局、层级框型和具体收益说明 | 14项29节点、ID/前置/价格不变；非线性玩法路线未实现 |
| 赐福等交互反复F8 | 新只读HINT v1与R请求/确认关联，观察原生菜单后交接；稳定可操作350ms且新帧才返回MC | 赐福退出rest flags清除时间待实机；未知/加载/死亡/切应用/手动F8取消自动流程 |
| 重进任务UP-TO-DATE跳过MC | productionLbClient和productionMeteorClient禁用up-to-date跳过 | CheckOnly仍只构建/预检，不启动游戏；实际启动待下一轮 |

HINV v3为0x40060字节，终点0x3C0060；装备请求区仍为0x370000；HINT为0x3D0000的64字节独立块。原0x360000库存区清为不可用，实时Java只接收v3；历史v1/v2解析明确保留旧1024布局，不能混读。全局桥接协议仍为1，必须配套更新本批DLL与Core。

HINT按支持EXE核对13组指令和5个RTTI身份，读取菜单blocking job、view active、Lua休息标志与action-ready。仅本次MC交互有匹配成功ACK、8秒内确实打开菜单时交接；不会凭输入锁或任意F8自动抢回。详见[交接说明](../native-interaction.md)。

## 构建、检查与本地日志

日志位于仓库忽略目录 `runtime/experiments/`，不提交。

| 操作 | 结果与范围 | 日志 |
| --- | --- | --- |
| `python tools/build_native.py` | Windows配套DLL构建通过 | `post-validation-native-build.log` |
| Gradle B01+LB `build verifyLaunchProfile` | 0.3.3生产Core/Character构建及依赖通过 | `post-validation-b01-build.log` |
| 普通Character / Core-only `build verifyLaunchProfile` | 两组合通过，未引入Skills强依赖 | `post-validation-character-build.log`、`post-validation-core-build.log` |
| `Launch.ps1 -B01 -CheckOnly` | 最终恢复B01制品并预检通过；无启动或配置修改 | `post-validation-final-preflight.log` |
| `test_character_split.py` 分别带 `--b01`、普通模式、`--core-only` | 实际JAR类归属、元数据、Mixin及可选依赖排除后的类型解析通过 | `post-validation-boundary-check.log`、`post-validation-character-boundary.log`、`post-validation-core-boundary.log` |
| `test_host_inventory.py` | 实际生产采样器1106/8192、稀疏/尾部已装备项、全内容token、ABI和EXE磁盘证据通过；大栈警告检查通过 | `growth-equipment-live/hinv-v3-native.log` |
| `test_host_equipment.py` | 第1105槽零数量拥有防具装卸、复用缓冲双读竞态、拒绝旧token和全部原保护通过 | `growth-equipment-live/hinv-v3-equipment.log` |
| `test_inventory_client.py` | C++产生wire上75项Java库存和20项装备codec检查通过 | `growth-equipment-live/hinv-v3-java.log` |
| `test_equipment_projection.py` | 最终制品33项真实MC ItemStack/Slot/意图/翻页/过滤/写入边界通过 | `post-validation-equipment-check.log` |
| `test_policy_heartbeat_lease.py` | 16项假时钟：暂停、恢复、运行超时、重连、空策略通过 | `policy-heartbeat-lease-check.log` |
| `test_lb_capability_adapter.py` | 固定正式LB15处注入、实际Value/枚举/import/范围守卫、24项GUI投影通过 | `post-validation-lb-check.log`、`lb-capability-check/` |
| `test_host_interaction.py` | 当前EXE SHA/13组指令/5RTTI、原生私有内存菜单采样与33项Java HINT检查通过 | `interaction-research/interaction-check.log` |
| `test_interaction_handoff.py` / `test_overlay_loading.py` | 39项交接策略及既有加载/焦点/取消路径通过 | `interaction-handoff-check.log`、`interaction-overlay-check.log` |
| 实际Skills0.19.2数据解析与成长catalog | 29节点、五套预设正式购买、前置/退款/NBT及41项策略/账本检查通过；76/116/232预算不变 | `growth-ui-skills-parser.log`、`growth-ui-catalog-check.log` |
| `generate_growth_content.py --check` | 技能数据与SVG预览同步 | 命令输出 |
| `inspect_eldencraft_reference.py` | 固定外部源码无改动、本机EXE SHA与17/17原生碰撞入口前缀匹配 | `eldencraft-compatibility.json` |

LB检查使用真实序列化器与明确的Choice JSON fixture，没有初始化模块单例/浏览器。装备fixture未调用活游戏原生函数，Skills fixture未进行玩家回调，HINT fixture没有看到真实赐福退出。不能将这些通过写成GUI、装卸、飞行、碰撞或自动交接已经实机通过。

最终独立只读审查覆盖HINT接线/取消、HINV地址隔离、装备旧epoch/身份防护、配方书按钮位置/窄屏隐藏、缩放后tooltip坐标及启动任务；未发现新的阻塞项。

## 版本与候选制品

环境沿用Python、LLVM-MinGW、JDK25、Gradle9.7.1；Fabric MC1.21.1，Core/Character **0.3.3**，固定LB v0.22.0/#3530与Skills0.19.2。本批未更新外部依赖或LB配置。目标EXE2.7.1.0，SHA-256 `1a3547101327f65d0c76da2f9190ac0aa66871ea42bae2aecc61e11a8b597891`；未启动游戏，故未再次观察标题App版本。

| 文件 | SHA-256 |
| --- | --- |
| `dist/erbridge_core.dll` | `0d3d13be92ddf61288654abc4773f2531a57f35915d77b695e7deb69deeeeafb` |
| `dist/dinput8.dll` | `11396c9655deec12d052be8c70a9cea86f6e4232426183ec5a80eb489f35169e` |
| Core `er-bridge-0.3.3.jar` | `ae1a355597f56b9b1d06e9176f54143e1b8fd4e1db3ec26ef67fe57e66fa32a5` |
| B01 Character `er-character-bridge-0.3.3.jar` | `b26d0d46e9a2fa39e8eb1362d534465659c0139174457488a4b992e6254f1785` |
| 普通Character同名JAR | `f5525f36e7afd21f9665f11ad71bfae5631d06148f4c7456d2d613274e82475a` |

B01与普通Character归档分别为 `runtime/experiments/post-validation-artifacts/b01/` 与 `character/`。当前build/libs最终恢复为B01组合；两种同名Character制品不能同时安装。

实际安装的 `D:/Steam/steamapps/common/ELDEN RING/Game/erbridge/erbridge_core.dll` 仍是上一轮 `2d214812773bc4606507e6a51a365e70bd2044b29701a54ae229eaf09c72c3ef`。`installation.json` 仍为 `8551ce1521c33e08cbfe909caa5bc7a92af9369e1fad591aa1789071cffdf3c2`，未重跑安装器。结束时ER与Minecraft KnotClient均未运行；本批不改世界/存档，现有自由模式revision6、Lv173/SpeedII/剩1点沿用上一轮。

## 研究结果与未实现项

- [成长路线](../growth-directions.md)：Speed地面疾行/连跳，Fly巡航/精确操控；列出固定LB的真实参数与模式差异，候选广修+双方向预算约116–118点。现有rank结构需改为已学节点授权再迁移；本批未开放新路线或YPort。
- [EldenCraft](../eldencraft-physics-research.md)：采集复用MC硬度/工具/战利品但不挖穿原生山体；放置物需要合并AABB并建立/回收ER原生Havok刚体。17/17磁盘匹配不代替线程、过滤器和生命周期验证。原生墙体与表面采集本批未实装。
- 原生丢弃、武器/护符写入、睡眠禁行动、骑乘准入、Meteor实机仍留独立任务。

下一轮只验本批：完整库存/原图/四护符 → 一件真实防具装卸/保存 → K暂停与挑战GUI过滤 → R赐福自动交接/返回 → MC同版本重进。已通过的升级主链不重复刷级；自动返回失败保留原生控制并记录，不据此无限扩大测试。
