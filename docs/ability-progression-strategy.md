# 能力成长与客户端适配策略

本轮范围：把客户端模块拆成可授予、可计量、可撤销的游戏能力。以下等级与数值是首轮 PvE 试验方案，不是已验证的平衡结论。B01 已接通 ER 等级读取、角色绑定、Pufferfish 技能树和固定 LB 的 Speed / Aura 授权；实机已确认 164 级绑定、0 点锁定与重载，实际升级发点、购买和撤销仍待测。本文其余能力及 Meteor 的成长授权尚未接入，不能将目标策略当作当前实现。

## 归属与执行

保持一个主仓库：桥接协议、角色成长、服务器权限及 ER 实现都在这里。LiquidBounce / Meteor 只提供操作与显示适配，不成为角色存档或等级的权威。先用可选适配层接入，等必须修改客户端公共设置、配置加载、界面生成时再创建各自 fork；不复制整个客户端到 bridge 仓库。

ER 等级的已确认最高值每增加 1，获得 1 技能点；重连、读回旧等级、重复事件不得再次给点。已接通的真实等级读取不等于实际升级流程已验证，模拟发点检查仍只作回归。角色身份不能只用本机玩家 UUID，应结合宿主实例、存档角色和会话绑定。

技能 I / II / III 暂定分别花 1 / 2 / 3 点，累计 6 点。所有能力都具备 `unlockedModes`、`caps`、`budget`、`cooldown`、`scope`、`policyRevision`；字段按需启用。数值由服务器产生，客户端仅请求分配点数或动作。UHC 可以对当局能力另设上限、点数预算及禁用项，永久成长不能自动突破比赛规则。

## 第一批能力拆分

| 能力 | I → II → III 的候选成长 | 实际接入位置与约束 |
| --- | --- | --- |
| Speed | 地面基础速度倍率 1.10 → 1.20 → 1.35 | 服务器移动预算；仅开放正常速度模式。LB 与 Meteor 的设置单位不同，先统一到每 tick 位移，再做各自转换。Timer 不能随 Speed 解锁。 |
| Aura | 单目标攻击距离 2.5 → 3.0 → 3.5 格 | 保留武器冷却、视线和有效目标规则；关闭穿墙、多目标及绕过模式。PvP 独立限制至 3.0；ER 大体型目标另按有效碰撞面判距。升级距离不同时升级攻速。 |
| Scaffold | 2 → 4 → 6 块/秒；脚下搭桥 → 垂直搭建 → 短距离延伸 | 消耗真实物品，服务端限制放置距离、合法位置和速率。LB Normal / Expand / Tower 分开授权；Meteor `blocks-per-tick` 不能直接当块/秒。法环碰撞镜像不可充当无限方块来源。 |
| FastMine | 合法挖掘效率 1.25 → 1.50 → 2.00 倍 | 仅对真正可破坏的 MC 方块生效。LB 模块名为 FastBreak，降低 BreakDamage 阈值和清零冷却都要受控；Meteor SpeedMine 的 Normal / Haste / Damage 分开。优先服务器挖掘效率，不开放挖掘报文绕过。 |
| AirJump | 每次离地后额外跳 1 → 2 → 3 次 | 服务端确认落地才补次数；液体、梯子、坐骑、飞行分别处理。LB DoubleJump 是候选入口，GhostBlock 另算能力；Meteor 持续空跳必须加计数。不能信任客户端单个 onGround 位。 |
| Fly | 飞行 3 → 6 → 10 秒；冷却 15 → 12 → 10 秒；水平 3 → 4 → 5 格/秒 | 前置 AirJump II，垂直上限 2 → 2.5 → 3 格/秒。授予真实服务端飞行权限及能量，退出撤销自己授予的状态；降落与摔伤明确处理。禁止用反踢报文代替权限。 |
| Criticals | 自动起跳辅助冷却 8 → 6 → 4 秒 | 首批只开放真实起跳的辅助，不影响玩家原本手动打出的暴击；一次攻击只有一次倍率。Packet / NoGround / Blink 不入首批。MC 暴击不等同于 ER 弹反处决。 |
| TargetStrafe | 按住绕目标 → 可调 2–3 格半径 → 短时自动追踪 | 前置 Speed I；只改方向，不能额外授予速度、飞行或穿墙。LB Motion 依赖 Aura 目标；Meteor 0.5.8 主仓库未找到同名模块，应放在公共移动能力里实现，不能假设能直接开开关。 |
| AntiVoid | 每补给周期 1 → 2 → 3 次救援；冷却 90 → 60 → 45 秒 | 前置 AirJump I；服务器保存同区域、8 格内最后有效落点，验证后消耗次数回救。Meteor 的世界底部判断不覆盖 ER 的死亡面；其调用 Flight 的模式不得顺带获得未学习的 Fly。无合法落点时拒绝。 |

以上数值用于跑第一轮体验，不直接硬编码为不可更改的规则。距离需要区分代理中心、碰撞面与 ER 实际可命中部位；尚未完成敌人碰撞修复时，不能用增大 range 掩盖近身穿模。

## 后续能力清单

| 组 | 可拆分内容 | 先决条件 |
| --- | --- | --- |
| Step / SafeWalk | 台阶高度、边缘保护开关 | 地面与碰撞可信，不能吞掉洞口或移动平台的实际危险 |
| Velocity | 水平 / 垂直抗击退比例 | 在服务器一次伤害路径应用，和护甲分开；先完成现有击退基线 |
| NoFall | 落差减伤、有限缓降 | 真实落地判定、能量和冷却；不伪造落地包 |
| AutoTool / AutoArmor | 工具切换、装备切换速度 | 明确 MC / ER 装备属性各自结算位置，不能重复加成 |
| InventoryTweaks | 整理、补充快捷栏 | 背包事务和物品身份稳定后接入 |
| ESP / Tracers | 探知半径、敌人类别、信息精度 | 服务器按范围提供可见信息；仅藏 UI 无法阻止已传输信息被读取 |
| VeinMine / Nuker | 连锁上限、工具耐久和体力 | 可破坏资源层与掉落权威完成，禁止挖掉宿主碰撞镜像 |
| Surround / AutoBuild | 蓝图尺寸、放置预算 | 复用 Scaffold 的服务端放置校验 |
| Baritone | 路径长度、自动采集任务数 | 需独立依赖与导航适配；ER 动态敌人、宿主死亡面不是普通 MC 障碍 |
| ER 战技 / 法术 / 弹反 | 学习许可、资源消耗、动作窗口 | 宿主动作代理、前后摇与取消规则；不能把 MC Criticals 直接当作战技实现 |

## 组合、撤销与热更新

移动按状态先后处理：死亡 / 控制交还 → 载具 / 宿主动作 → 救援 → 飞行 → 空跳 → 地面移动；TargetStrafe 提供方向，Speed 提供被许可的地面速度，最终仍受一个位移预算约束。优先级不表示 AntiVoid 可以无限打断坠落：次数和合法落点先校验。Fly 期间不得免费刷新 AirJump。

状态效果在最终移动预算中生效：冻伤、睡眠减速不能被 Speed 无条件覆盖。装备、药水、技能倍率采用明确公式并限定最终上限。Aura / Criticals 共用服务端攻击冷却；Scaffold / AutoBuild 共用放置预算。动作请求需携带当前会话、策略版本与请求编号，重复请求不重复扣点或执行。

策略可以热更新，无需重启游戏；但“隐藏一个模块”不是完整热更新。建议事务顺序：服务器校验并持久化分配 → 发布新策略版本 → 客户端暂停受影响动作 → 夹紧设置并撤销旧能力状态 → 重建 ClickGUI / TabGUI 可见项与范围 → 恢复仍合法的动作并确认版本。失败保留上一版或停用能力，不半更新。

必须覆盖四类入口：GUI / 快捷键 / 命令、配置载入、模块启停及每 tick 执行、服务端动作接收。客户端实现了所有这些限制，服务端仍必须按自己的策略验收。热重载不能改变服务器规则文件的授权边界。

固定源码中的具体难点：

- Meteor `Module.toggle()` 在调用模块 `onActivate()` 前已经登记 active、触发设置激活并订阅事件。只在模块回调内“发现未解锁再关掉”太晚，应在启用入口拒绝，并保留执行阶段守卫。
- Meteor `Module.fromTag()` 先加载设置再切换激活状态。配置载入要作为整体校验，不能只限制设置界面。
- Meteor `Setting` 的 `set`、`parse`、`reset`、`fromTag` 有不同调用路径；其中 `DoubleSetting.load()` 会调用 `set()`，不能笼统说所有 NBT 载入都绕过校验。`DoubleSetting` 的 min / max 与滑块边界是 final，动态等级上限需要外层策略约束并刷新界面，或在独立 fork 提供动态边界接口。
- 撤销不仅是把 active 写为 false：需还原飞行权限、速度属性、客户端 Timer、临时 Haste、按键模拟、目标锁和正在排队的动作。只回收本能力添加的部分，不能覆盖药水或其他模组已有状态。

## 后续批次与验收

| 批次 | 一起实现 | 同一次验证覆盖 |
| --- | --- | --- |
| A | Speed、Aura、AirJump；真实等级事件与技能页；LB / Meteor 策略适配 | 升降级、点数去重、配置绕过、F8、死亡、断连、两名角色隔离、减速与 Speed 叠加 |
| B | Scaffold、FastMine、AutoTool；资源层与物品消耗 | 同 tick 放置 / 挖掘预算、工具耐久、库存竞争、不可破坏碰撞镜像、固定种子重载 |
| C | Fly、AntiVoid、TargetStrafe、Criticals | 飞行权限收回、落地与救援、空跳补充、敌人贴身碰撞、一次伤害及多能力速度上限 |
| D | UHC 局规则、两端会话路由与观战 | 单人双 MC 客户端 + 模拟宿主先验路由，再进行真实双 ER 主机测试；当前单一共享内存连接不等于联机完成 |

每批都同时覆盖正常路径、撤销路径、配置旁路和组合冲突；不等到所有模块做完才检查服务端执行。现阶段新增能力先形成可测试的领域规则，再分别接入客户端和游戏 API。

## 本轮对照源码

- [LiquidBounce 固定提交](https://github.com/CCBlueX/LiquidBounce/tree/e5a0dbf5458b063d3028e69e04762b8b25b998b5)：本地检出的 MC 1.21.1 版本；能力名称与模式以该版本为准。
- [Meteor 0.5.8 固定提交](https://github.com/MeteorDevelopment/meteor-client/tree/37c5c5c640ba9c66721e824c62e84bf1223c9cd6)：`systems/modules/movement`、`combat`、`world` 中上述模块。
- [Meteor Module.java](https://github.com/MeteorDevelopment/meteor-client/blob/37c5c5c640ba9c66721e824c62e84bf1223c9cd6/src/main/java/meteordevelopment/meteorclient/systems/modules/Module.java)
- [Meteor Setting.java](https://github.com/MeteorDevelopment/meteor-client/blob/37c5c5c640ba9c66721e824c62e84bf1223c9cd6/src/main/java/meteordevelopment/meteorclient/settings/Setting.java) 与 [DoubleSetting.java](https://github.com/MeteorDevelopment/meteor-client/blob/37c5c5c640ba9c66721e824c62e84bf1223c9cd6/src/main/java/meteordevelopment/meteorclient/settings/DoubleSetting.java)
- [已有 B01 实现与范围](b01-preparation.md)、[Meteor 本轮接入](meteor-integration.md)。
