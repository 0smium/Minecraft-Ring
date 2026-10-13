# 2026-10-13：增量代码、Meteor 与背包桥接 review

评审基线：`dev/lb-poc` / [`e2188391b5ba1257a9853920244909decf3bd97f`](https://github.com/0smium/Minecraft-Ring/commit/e2188391b5ba1257a9853920244909decf3bd97f)。增量从 `3e66a0b2780b72b60eeb0197bbc01c28db47be0b` 起，11 个提交、32 个文件。比较源码：LiquidBounce `0.22.0` / Build #3530，提交 `e5a0dbf5458b063d3028e69e04762b8b25b998b5`；Meteor `v0.5.8`，提交 `37c5c5c640ba9c66721e824c62e84bf1223c9cd6`。

验证：静态阅读上述固定源码及已提交实验记录；本次运行 `python tools/test_native_depth_resize.py` 通过。该测试使用 CPU/D3D stub，不是 Windows GPU 或游戏实测。本次没有运行 ER、MC、LB 或 Meteor，没有完成 Fabric/native 完整构建，也没有下载并验证 Meteor 发布 JAR。当前环境无 `javac`，未重跑依赖 Windows JDK 路径的 `test_overlay_loading.py`。

关联：[前一轮玩法准备度](20261012-gameplay-readiness.md)、[地图与版本](20261012-map-worldgen-version.md)、[工作线规划](../gameplay-roadmap.md)。旧 review 保留原基线，本篇补充最新结论。

## 结论

这轮还没有把底座做成只能服务 LB 的专用项目。名称、真实命中、合成深度和输入生命周期的修复可复用；LB 私有实现适配有独立 Mixin 配置、可选加载和版本门控。需要控制的是后续继续逐模块补丁的趋势：宿主实体语义、能力授权和生产启动配置应该成为公共能力，LB/Meteor 各保留薄适配层。

Meteor 有对应 MC 1.21.1 的 `v0.5.8`，具备作为另一个可选客户端接入的源码基础；当前仓库没有 Meteor 启动配置或适配，也没有共载验证。不能把“Fabric 能加载”写成“所有功能已在法环生效”。继续固定 1.21.1 即可进行这项试验，无须先升级 MC 或退到 1.8。

背包应作为下一项内容桥接成果。建议先在现仓建立职责独立的内容模块，验证读取与一次操作闭环；接口和发布边界稳定后再决定拆仓。此建议不创建新仓库、不替换 LB 工作线，也不要求先补完全部兼容模块。

## 1. 新改动是否过度定制

| 改动 | 源码/记录判断 | 建议 |
| --- | --- | --- |
| 原生命中观察 `incoming_hits.h` | 用版本校验过的 HP 应用路径确认真实受击，避免把未知 HP 减少都当攻击；这是通用桥接能力 | 保留，补 R09 失败状态传播 |
| NPC 名称读取 | 读 NpcParam 名称 ID 与当前语言 FMG，沿既有 `name` 字段发布；无有效名字仍回退编号 | 保留在 ER 适配层；不为每个模组复制名称表 |
| 深度资源/resize 生命周期 | 用 fence 保留仍在 GPU 使用的资源并清理旧 tracker，解决切分辨率后的遮挡问题 | 保留在 Core，不属于 LB 定制 |
| F8 焦点交接 | 检查真实前台，有界重试，GUI 打开时保留鼠标；对各类 MC GUI 都有意义 | 保留；独占全屏仍需点击是已知未解决项 |
| LB 选敌、Box、HP 标签 | 在 `integrations/liquidbounce`，`@Pseudo` + 字符串目标 + `LiquidBounceMixinPlugin`，无 LB 时跳过；支持版本限定 `0.22.0` | 合理的版本适配，暂不 fork LB；不要推广私有 Kotlin 方法名为公共接口 |
| LB Minimap 扫描门控 | 仅桥接世界且小地图关闭时暂停无用扫描，有 JFR 与运行记录；反射 helper 放在 Mixin 保留包外 | 保留为 LB 兼容修复；其私有方法/字段绑定升级时重验，不能复用于 Meteor |
| 启动入口 | `Launch.ps1 -LiquidBounce`、`productionLbClient`、FLK 和固定 JAR 摘要是一条可复现的已知路径，但还不是通用模组 profile | 接第二种客户端时再做最小泛化；详见 R11 |

名称功能的具名角色标签尚未人工验证；小地图已有“后台门控生效”和用户体感改善记录，没有受控 FPS 增益证据。基础 Box/Tracers、KillAura 真实扣血、普通受击与修正 HP 标签已有记录；标准三次受击、Velocity、FlyCreative 服务端权限、穿墙效果等不能算全部通过。事实分别见[攻击适配](../experiments/20261012-lb-target-combat-adaptation.md)、[名称与性能](../experiments/20261012-name-tables-performance.md)、[小地图与焦点](../experiments/20261012-minimap-focus-performance.md)、[最新下一步](../experiments/20261012-config-reset-next-steps.md)。

## 2. Review 问题

### R09：受击观察初始化失败后仍允许接管，普通受击会丢失

**优先级 P1。分类：可靠战斗流程前应处理。状态：源码确认的失败路径，未实机复现。工作线 A/Core。**

证据：`incoming_hits.h:104–123` 在签名不匹配、创建/启用 hook 失败时返回 `false`；`game.cpp:2664` 忽略这个返回值。随后 `stand_in` 在 `game.cpp:1080–1120` 只将 `hits.count` 对应的损失记为命中，其他 HP 差记为 residual，仍恢复原生角色满 HP。`CombatBridge.java:106–109` 对 residual 仅记录日志，不施加 MC 伤害。因此这条失败路径下，接管状态可继续运行，而普通攻击不会进入 MC 伤害结算。这里不声称已记录的成功实测发生过该故障。

建议：初始化结果进入明确的受击能力状态；该能力不可用时拒绝/暂停依赖它的战斗接管并给出可见原因，保留可用的非战斗能力。不应恢复“所有未知 HP 减少都算攻击”的旧猜测。运行后 hook 可能被宿主代码恢复是已有记录的风险，安装成功日志不能充当全程可用证明。

验收：在受控测试中让观察初始化失败，确认不会得到“正常可战斗但不扣 MC 血”的状态；正常路径再取得同条件三次真实受击。Fly/Velocity 的完整基线沿原计划执行。

### R10：真实 HP 已公开，但通用消费者仍读 MC 代理血量

**优先级 P2。分类：新 HUD/选敌功能使用前处理。状态：既有代理设计暴露出的扩展缺口，不是本轮新造的数据丢失。工作线 A/B。**

`ErEntity.java:112–125` 已有公共 `hp()/maxHp()` 与同步字段，不能说核心完全没有真实 HP 接口。但它未同步或覆盖 `LivingEntity.getHealth()/getMaxHealth()`；本轮只在 LB `NametagHealthMixin.getHealthText` 显示宿主 HP。LB `TargetTracker` 的 Health 排序仍调用 `getActualHealth()`，默认回到 MC health；Meteor 的普通生物 Nametags 也读 vanilla health。于是“标签正确”不意味着其他 HUD、血量排序或其他模组都正确。

建议：以现有字段为基础定义稳定的宿主实体只读视图，包含身份/有效性、敌对性、显示名、当前/最大 HP、AABB，让两种适配器共用；随后按实际启用功能接 HP/名称/排序。不要为了显示数值直接把千血 Boss 写成普通 MC 血量并意外改变伤害或死亡权威。是否提供通用 MC 归一化血量视图需明确语义并单独验证，当前不要求重写全部属性系统。

验收：同一只宿主敌人受击时，两种客户端标签和启用的血量排序使用同一宿主数据；未知/断连不显示为已确认满血；NPC、死亡、卸载的语义一致。

### R11：第二种客户端缺生产启动配置与兼容验证

**分类：Meteor 接入前必须处理。状态：功能缺口，不是当前 LB 回归。工作线 A。**

`Launch.ps1:1–24` 仅有 Core/LB 两种选择；`mc-bridge/build.gradle:38–75` 把生产映射运行、额外 JAR 和 FLK 绑在 LB task。固定哈希与原始 JAR 是合理的复现措施；问题在于尚未抽出“生产环境 + 可选模组集合”这一层。

建议新增 `core`、`lb-0.22.0`、`meteor-0.5.8` 三个可选择 profile，共用生产启动机制，各自固定制品摘要、依赖、配置和按键。保留现有 LB 别名，配置/run 目录隔离；存档共享或复制须显式决定，不能同时写同一份世界。一般 release 模组应在生产 intermediary 环境加载，不能重走已失败的 LB 开发映射混装路径。Core 的 Mojang 源码命名与 Meteor 的 Yarn 源码命名在正确 remap 后并不要求二选一。

先验证 Core+Meteor；Core+LB+Meteor 同时加载列为后续独立组合。二者都改输入、旋转、移动、渲染、物品和数据包，即使 Fabric 依赖解析通过或没有声明互斥，也不能推导实际无冲突。

### 已知限制与本轮范围

命中在一个 MC poll 内聚合且只保留最后方向、单宿主伤害广播给所有玩家，已在攻击适配记录和前一轮 R01 中明确；不重复报成本轮新增回归。真实背包/装备语义、死亡战利品、独立地图和多人权威仍未实现。新 `NO_HANDS` 返回两个空手槽是敌人代理兼容修复，既不是玩家法环背包接入，也不是敌人真实装备同步。

## 3. Meteor：版本与实际功能差异

`v0.5.8` 的 `gradle.properties` 固定 `minecraft_version=1.21.1`、Loader `0.15.11`；其 `fabric.mod.json` 要求 Java ≥21、Loader ≥该值，并接受 MC 1.21/1.21.1。当前 Core 的 MC 1.21.1、Loader 0.19.5 和 Java 编译目标 21 满足这些声明条件，但实际共载、依赖版本与图形 hook 仍需验证。Meteor 官方不直接支持旧 MC 版本，因此应把此版本当项目自维护的固定兼容对象，不能依赖当前最新 add-on 自动兼容。

以下比较固定版源码，不按宣传模块总数判断“哪个更多”，也不声称另一边完全没有相似能力。

| 能力 | 当前 LB 0.22.0 | Meteor 0.5.8 | 对本项目的意义 |
| --- | --- | --- | --- |
| 战斗/移动 | KillAura、Scaffold、Fly、Velocity，含多种模式与服务器适配 | KillAura、Scaffold、Flight、Velocity；CrystalAura、BedAura、AnchorAura、Surround 等组合 | 很多重叠；每种能力仍依赖已有 MC→ER 路径，不是直接操控法环招式 |
| 世界/资源自动化 | AutoBuild、AutoFarm、Nuker、AutoTool、PacketMine | HighwayBuilder、VeinMiner、AutoSmelter、AutoBrewer、Nuker；Baritone 可用时注册 Excavator、InfinityMiner | Meteor 有更多贴近挖矿、工程和补给的现成工具，适合以后真实 MC 资源区；不能挖出当前不存在的矿脉，也不会自动改写法环原生岩壁 |
| 背包辅助 | InventoryCleaner、ChestStealer、AutoArmor、AutoShop、AutoBuff | InventoryTweaks、AutoReplenish、AutoTool、AutoArmor、AutoMend 等 | 都操作 MC ItemStack/槽位；ER 武器不会自动被当成可换装剑，圣杯瓶也不会自动被识别成金苹果/食物 |
| 可视化 | ESP、Nametags、Tracers、StorageESP、BlockESP 与浏览器 HUD | ESP、Nametags、Tracers、StorageESP、BlockESP、TunnelESP、Waypoints 与自绘 GUI/HUD | Meteor 可作为第二套工具界面；透明合成、深度、按键、F8 焦点要实测，不能仅凭没有 JCEF 就宣布性能更好 |
| 扩展入口 | Graal JavaScript 脚本与 LB 配置/事件体系 | `MeteorAddon`，Fabric `meteor` entrypoint，可注册模块；Starscript 提供文本表达式 | 薄 Meteor adapter/add-on 可调用公共 Bridge API；Starscript 不等于完整 JavaScript 模块脚本，也不应把公共背包逻辑写死为某一客户端插件 |

Baritone 是独立依赖：该 tag 的 Gradle 使用 `modCompileOnly`，没有将它纳入 `modInclude`；`PathManagers` 检查 `baritone.api.BaritoneAPI`，缺失时可落到 `NopPathManager`；`Modules.initWorld` 仅在可用时注册 Excavator/InfinityMiner。应另选兼容 MC 1.21/1.21.1 的 Baritone 制品并验证，不能承诺只装 Meteor 就已有完整自动寻路/挖矿。

其他模组也可按同版本 Fabric 的方式评估。纯 MC GUI/资源/物品功能较容易复用；新增注册内容的模组可能同时需要逻辑服务端支持；Forge/NeoForge 模组、Paper/Spigot 插件不能直接当 Fabric JAR 放入。本 review 没有审核所有模组组合。

## 4. Meteor 最小适配清单

1. **生产共载入口。** 按 R11 固定原始 JAR 与依赖，先不混入 LB。使用 Core+Meteor 的独立配置；GUI 热键与 F8 分开，建立最小模块集。
2. **实体类型与敌对语义。** Meteor `KillAura.entityCheck` 通过 `entities.contains(entity.getType())` 筛选，默认只含玩家。设置 UI 从实体注册表生成，源码上可加入 `erbridge:er_entity`，未必需要像 LB 那样补 Java 类别识别。但当前 NPC 与敌人共用该 EntityType，只按类型勾选会选中中立 NPC；必须再检查已有 `isTargetableEnemy()`、连接/会话有效性与宿主 HP，保留 Meteor 自身距离/墙体/武器/冷却规则。`ErEntity.hurt` 虽会拒绝 NPC 伤害，却不能避免客户端白打和占用目标。
3. **Box 优先验证。** Meteor `ESP.drawBoundingBox` 的 Box 路径直接读 `entity.getBoundingBox()`，可利用现有 AABB；默认 ESP 模式却是 Shader，默认实体列表仍仅玩家。ER 代理没有实际怪物模型，模型轮廓 Shader/Wireframe 不能自动给原生法环怪物描边，第一版设 Box 并选正确实体。这里不需要预先照搬 LB 的 Box 私有 lambda 补丁。
4. **名称与 HP。** Meteor `renderGenericLivingNametag` 用 `entity.getType().getName()`，而非代理的 CustomName；血量用 `getHealth()/getMaxHealth()`。所以连已经读取的法环名字也不会自动正确显示。适配已有 CustomName + 宿主 `hp()/maxHp()`，保留不认识名字时的编号与未知 HP 状态。可用 add-on 自有标签，或做固定版本窄 Mixin；以避免重复绘制为验收项。
5. **攻击、飞行、击退分别验收。** Meteor KillAura 最终仍调用 MC `attackEntity`，有望复用现有 `ErEntity.hurt → EntityBridge`，不应另写一套原生攻击。Flight Abilities 模式同样只直接改客户端 flying/allowFlying，不能修复已知服务端权限缺口；Velocity 主要沿 MC 速度包/运动处理，也不证明能取消一切法环原生受击位移。复用公共能力授权、受击和有效运动状态，并逐项对照真实结果。

最小验收：Core+Meteor 能进桥接世界；GUI/HUD 可见且关闭后输入恢复；一个敌人的 Box、实际名字/回退名、HP 跟随；一名中立 NPC 不被攻击；击杀后目标消失；切回 Core/LB profile 不残留 Meteor 状态。随后才测 Flight、Velocity 和物品自动化。上述均未在本次运行。

## 5. 背包是否另开仓库

建议目前先拆组件，在现仓推进第一条真实背包流程。现在 DLL、共享协议、MC 模组仍联动，立刻跨仓会增加“哪组版本配套”的负担，不能代替职责解耦。内容桥接应能在无 LB、无 Meteor 时运行。

| 边界 | 保留职责 | 首版不承担 |
| --- | --- | --- |
| ER Core | 版本校验、游戏线程上的背包读取/合法操作、生命周期与结果发布 | 具体 LB/Meteor 类、MC GUI、自动换装策略 |
| 内容 Bridge 模块 | 快照/请求数据、ER 物品身份、只读页面、确认后的装备操作 | 第二份原生库存、UHC 掉落规则、客户端私自确认成功 |
| LB/Meteor adapter | 读取公共实体/物品语义，把模块意图交给受支持的 Bridge 请求 | 内存地址/偏移、直接增减 ER 物品、把普通 MC clickSlot 当成原生操作 |
| MC 玩法模块 | 获得/兑换规则、资源经济、对局限制与结算 | 猜测一段库存变化一定代表拾取成功 |

第一步用一个只读背包页面显示 ER 物品类别/ID、会话内稳定实例标识、数量、强化/变体和已装备槽；快照带角色/会话世代、版本/序号与可用状态。字段取不到要显示未知，不能拿模型 ID 代替物品身份。休息/传送、死亡/读档和断连时使旧快照失效。数据读取由 Core 对目标游戏版本验证，MC 不新增散落的裸内存读取。

第二步只选一种装备槽做闭环：选择已有物品 → 携带快照身份的请求 → 原生合法执行 → 明确成功/失败或结果未知 → 重读确认。超时/重连不盲目重放；不克隆一套可被 MC 容器随意增减的“真实法环物品”。只读展示槽在逻辑服务端拒绝移动/丢弃等未支持动作，避免 InventoryTweaks/ChestStealer 把虚拟展示当真实库存。法环物品仍由 ER 管，普通 MC 方块/矿物由 MC 管。

消费品、拾取/兑换、自动换装随后逐类添加。特别是圣杯瓶、护甲、饰品与 MC 护甲/药水共同生效时，要指定最终扣数量和回血/减伤在哪侧执行，避免一次操作扣两次或疗效结算两次。前一轮 R06 仍适用。

适合拆成新仓库的时点：内容模块已有独立制品/版本、明确的 Core API 与兼容版本表、能用模拟后端开发 UI，且第一条实机闭环已通过；此时可新建 `er-content-bridge` 类仓库依赖发布的 Core API。现在不复制 Core 源码、不 fork LB/Meteor、不创建只有目录的空仓库。

## 6. 接下来做什么

沿现有 LB 计划收尾的短任务：补 R09 受击能力失败路径，完成已定位的服务端飞行授权，并按既定方法取得三次受击/Velocity 对照。窗口/无边框作为固定环境；升降平台、独占全屏额外点击和全套模块验证继续作为已知独立项，不再无限扩大当前门槛。

下一项面向用户的新成果优先选**法环背包只读页面**：能在 MC 里看到当前原生装备和物品，原生拾取/消耗后能刷新，断连/换角色不串数据。这直接推进内容目标；之后做一个装备操作闭环。

Meteor 可作为紧随其后的独立兼容试验，或单独分支的有限任务：固定 0.5.8 生产启动 → Box/名称/HP/选敌 → 一次真实攻击。不要求它先覆盖全部功能，也不让 Meteor 的加入替换已投入的 LB 验收。两者都能调用同一份背包/实体接口后，再判断哪套客户端更适合实际玩法。

## 7. 固定源码与核查入口

本仓固定到 `e218839`：[原生命中](https://github.com/0smium/Minecraft-Ring/blob/e2188391b5ba1257a9853920244909decf3bd97f/bridge-base/elden-ring/er-bridge/src/incoming_hits.h)、[game.cpp](https://github.com/0smium/Minecraft-Ring/blob/e2188391b5ba1257a9853920244909decf3bd97f/bridge-base/elden-ring/er-bridge/src/game.cpp)、[ErEntity](https://github.com/0smium/Minecraft-Ring/blob/e2188391b5ba1257a9853920244909decf3bd97f/bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge/entity/ErEntity.java)、[CombatBridge](https://github.com/0smium/Minecraft-Ring/blob/e2188391b5ba1257a9853920244909decf3bd97f/bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge/entity/CombatBridge.java)、[LB 适配目录](https://github.com/0smium/Minecraft-Ring/tree/e2188391b5ba1257a9853920244909decf3bd97f/bridge-base/elden-ring/mc-bridge/src/client/java/dev/ermc/bridge/client/integrations/liquidbounce)。

Meteor 固定到 `37c5c5c6`：[版本属性](https://github.com/MeteorDevelopment/meteor-client/blob/37c5c5c640ba9c66721e824c62e84bf1223c9cd6/gradle.properties)、[依赖声明](https://github.com/MeteorDevelopment/meteor-client/blob/37c5c5c640ba9c66721e824c62e84bf1223c9cd6/src/main/resources/fabric.mod.json)、[模块注册](https://github.com/MeteorDevelopment/meteor-client/blob/37c5c5c640ba9c66721e824c62e84bf1223c9cd6/src/main/java/meteordevelopment/meteorclient/systems/modules/Modules.java)、[KillAura](https://github.com/MeteorDevelopment/meteor-client/blob/37c5c5c640ba9c66721e824c62e84bf1223c9cd6/src/main/java/meteordevelopment/meteorclient/systems/modules/combat/KillAura.java)、[ESP](https://github.com/MeteorDevelopment/meteor-client/blob/37c5c5c640ba9c66721e824c62e84bf1223c9cd6/src/main/java/meteordevelopment/meteorclient/systems/modules/render/ESP.java)、[Nametags](https://github.com/MeteorDevelopment/meteor-client/blob/37c5c5c640ba9c66721e824c62e84bf1223c9cd6/src/main/java/meteordevelopment/meteorclient/systems/modules/render/Nametags.java)、[AddonManager](https://github.com/MeteorDevelopment/meteor-client/blob/37c5c5c640ba9c66721e824c62e84bf1223c9cd6/src/main/java/meteordevelopment/meteorclient/addons/AddonManager.java)、[构建依赖](https://github.com/MeteorDevelopment/meteor-client/blob/37c5c5c640ba9c66721e824c62e84bf1223c9cd6/build.gradle)。

LB 固定到 `e5a0dbf5`：[模块注册](https://github.com/CCBlueX/LiquidBounce/blob/e5a0dbf5458b063d3028e69e04762b8b25b998b5/src/main/kotlin/net/ccbluex/liquidbounce/features/module/ModuleManager.kt)、[目标排序](https://github.com/CCBlueX/LiquidBounce/blob/e5a0dbf5458b063d3028e69e04762b8b25b998b5/src/main/kotlin/net/ccbluex/liquidbounce/utils/combat/TargetTracker.kt)、[FlyGeneric/FlyCreative](https://github.com/CCBlueX/LiquidBounce/blob/e5a0dbf5458b063d3028e69e04762b8b25b998b5/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/movement/fly/modes/FlyGeneric.kt)。[Meteor 官方 FAQ](https://meteorclient.com/faq)用于核对旧版本支持政策，具体兼容结论以该 tag 源码为准。
