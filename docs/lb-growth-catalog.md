# LB 成长 v1 功能目录与适配边界

固定来源：LiquidBounce v0.22.0 / #3530，源码 `e5a0dbf5458b063d3028e69e04762b8b25b998b5`。本页对照其 `Category.kt`、`ModuleManager.registerInbuilt` 和每个模块的原始设置；运行规则以 Character 的 `data/erbridge/ability_catalog.json` 为准。

## 构筑栏目

| 栏目 | 已接入能力 | 用途与边界 |
|---|---|---|
| 机动 | Speed、Step、AirJump、Fly | 先地面/台阶，后双跳，最后飞行或无限跳；保留 LB 原模块 |
| 战斗 | KillAura、Velocity | 单目标短距离自动攻击与逐级抗击退；禁穿墙、攻击计时/包绕过 |
| 建造 | Scaffold、SafeWalk | 原版库存实际消耗，逐步缩短放置间隔并提高行进上限；禁 Tower、Blink、Telly |
| 生存 | NoFall、AutoArmor | MC 普通坠落规则与已有护甲管理；不许把宿主死亡线变成无敌 |
| 情报 | ESP、Nametags、Tracers | Box 与追踪线按开关解锁；只有 Nametags 使用真实距离参数分档 |
| 工具 | AutoTool | 仅 MC 工具选择；不生成物品，也不代替 ER 装备写入 |

## 可用映射

下表为源码已定位并纳入配置守卫的映射。离线构建/注入与值读写检查不等于每项已通过游戏验证。

| 能力 | 原 LB 模式 / 关键路径 | 约束 |
|---|---|---|
| Speed | `Mode=Custom`；`Mode/Custom/Strafe/Speed` | 原范围 0.1..10 与服务端授权相交；地面 Strafe，禁额外 Timer/水平/垂直修改 |
| KillAura | `Range`；`ClickScheduler/Cooldown/Minimum` | 攻击前再次测距离和满冷却；WallRange/ScanExtraRange=0；不改原攻击算法 |
| Scaffold | `Technique=Normal`、`Tower=None`、`Delay`、`SpeedLimiter/SpeedLimit` | 4→2→0 ticks 最低间隔，SpeedLimit 原范围0.01..0.4内；禁止Blink/Telly/HeadHitter |
| Fly | `Mode=Creative`；`Mode/Creative/Speed` | 原范围0.1..5内；SprintSpeed关闭、ForceFlight=true、BypassVanillaCheck=false；服务端原版mayfly授权 |
| AirJump | 枚举 `Mode=DOUBLE_JUMP` / `JUMP_FREELY` | 二阶允许集合扩充，合法双跳偏好保留；GhostBlock始终禁用，不杜撰次数/冷却设置 |
| Step | `Mode=Instant`；`Mode/Instant/Height` | 原范围0.6..5内；SimulateJumpOrder=0..0，避免附加跳跃包 |
| SafeWalk | `Mode=Safe` | 只有一阶，避免重复购买无实际效果的等级 |
| NoFall | `Mode=SpoofGround`；`Mode/SpoofGround/FallDistance` | 只有一阶；LB处理MC移动包，宿主死亡线仍生效 |
| Velocity | `Mode=Modify`；`Mode/Modify/Horizontal`、`Vertical` | 只处理正常MC速度包，按阶允许较低剩余击退；Delay=0..0，禁反向速度 |
| AutoTool | `IgnoreDurability=false` | 仅MC方块工具，原版自动选槽 |
| AutoArmor | 原模块默认模式 | 仅MC护甲；满背包时原LB有丢弃换装行为，测试前留空位 |
| ESP | `Mode=Box` | 当前合成支持的Box，一阶；不以Glow/Outline冒充完成 |
| Nametags | `MaximumDistance` | 原范围1..256内，逐级放宽，原生ER HP映射保留 |
| Tracers | 原模块 | 一阶；`ColorMode/Distance/CustomViewDistance`只是颜色渐变范围，不能拿来宣称限制显示距离 |

## LB 原生各栏目

以下按固定版正式 `registerInbuilt` 实际注册项目逐一归类。**成长**表示纳入当前14项；**免费**为明确的界面/装饰白名单；其他均在挑战模式关闭，等有实际适配规则后再开放。自由模式保持 LB 原有行为。开发版专属 DebugRecorder 不在正式 #3530 注册表中。

### COMBAT

| 功能 | v1 定位 |
|---|---|
| Aimbot | 未适配 · 挑战模式锁定 |
| AutoArmor | 成长 · 生存 · 护甲整理 |
| AutoBow | 未适配 · 挑战模式锁定 |
| AutoClicker | 未适配 · 挑战模式锁定 |
| AutoDodge | 未适配 · 挑战模式锁定 |
| AutoLeave | 未适配 · 挑战模式锁定 |
| AutoShoot | 未适配 · 挑战模式锁定 |
| AutoWeapon | 未适配 · 挑战模式锁定 |
| Backtrack | 未适配 · 挑战模式锁定 |
| Criticals | 未适配 · 挑战模式锁定 |
| CrystalAura | 未适配 · 挑战模式锁定 |
| FakeLag | 未适配 · 挑战模式锁定 |
| Hitbox | 未适配 · 挑战模式锁定 |
| KeepSprint | 未适配 · 挑战模式锁定 |
| KillAura | 成长 · 战斗 · 近战辅助 |
| MaceKill | 未适配 · 挑战模式锁定 |
| NoMissCooldown | 未适配 · 挑战模式锁定 |
| SuperKnockback | 未适配 · 挑战模式锁定 |
| SwordBlock | 未适配 · 挑战模式锁定 |
| TickBase | 未适配 · 挑战模式锁定 |
| TimerRange | 未适配 · 挑战模式锁定 |
| TpAura | 未适配 · 挑战模式锁定 |
| Velocity | 成长 · 战斗 · 稳固架势 |

### PLAYER

| 功能 | v1 定位 |
|---|---|
| AntiAFK | 未适配 · 挑战模式锁定 |
| AntiExploit | 未适配 · 挑战模式锁定 |
| AntiVoid | 未适配 · 挑战模式锁定 |
| AutoBreak | 未适配 · 挑战模式锁定 |
| AutoBuff | 未适配 · 挑战模式锁定 |
| AutoFish | 未适配 · 挑战模式锁定 |
| AutoQueue | 未适配 · 挑战模式锁定 |
| AutoRespawn | 未适配 · 挑战模式锁定 |
| AutoShop | 未适配 · 挑战模式锁定 |
| AutoWalk | 未适配 · 挑战模式锁定 |
| Blink | 未适配 · 挑战模式锁定 |
| ChestStealer | 未适配 · 挑战模式锁定 |
| Eagle | 未适配 · 挑战模式锁定 |
| FastExp | 未适配 · 挑战模式锁定 |
| FastUse | 未适配 · 挑战模式锁定 |
| InventoryCleaner | 未适配 · 挑战模式锁定 |
| NoFall | 成长 · 生存 · 落地保护 |
| NoRotateSet | 未适配 · 挑战模式锁定 |
| Offhand | 未适配 · 挑战模式锁定 |
| Reach | 未适配 · 挑战模式锁定 |
| SmartEat | 未适配 · 挑战模式锁定 |

### EXPLOIT

| 功能 | v1 定位 |
|---|---|
| AbortBreaking | 未适配 · 挑战模式锁定 |
| AntiHunger | 未适配 · 挑战模式锁定 |
| AntiReducedDebugInfo | 未适配 · 挑战模式锁定 |
| BungeeSpoofer | 未适配 · 挑战模式锁定 |
| ClickTp | 未适配 · 挑战模式锁定 |
| ClientSpoofer | 未适配 · 挑战模式锁定 |
| ConsoleSpammer | 未适配 · 挑战模式锁定 |
| Damage | 未适配 · 挑战模式锁定 |
| Disabler | 未适配 · 挑战模式锁定 |
| Dupe | 未适配 · 挑战模式锁定 |
| GhostHand | 未适配 · 挑战模式锁定 |
| Kick | 未适配 · 挑战模式锁定 |
| MoreCarry | 未适配 · 挑战模式锁定 |
| MultiActions | 未适配 · 挑战模式锁定 |
| NameCollector | 未适配 · 挑战模式锁定 |
| NoPitchLimit | 未适配 · 挑战模式锁定 |
| PingSpoof | 未适配 · 挑战模式锁定 |
| Plugins | 未适配 · 挑战模式锁定 |
| PortalMenu | 未适配 · 挑战模式锁定 |
| ResetVL | 未适配 · 挑战模式锁定 |
| ResourceSpoof | 未适配 · 挑战模式锁定 |
| ServerCrasher | 未适配 · 挑战模式锁定 |
| SleepWalker | 未适配 · 挑战模式锁定 |
| Teleport | 未适配 · 挑战模式锁定 |
| TimeShift | 未适配 · 挑战模式锁定 |
| TranslationFix | 未适配 · 挑战模式锁定 |
| VehicleOneHit | 未适配 · 挑战模式锁定 |

### MOVEMENT

| 功能 | v1 定位 |
|---|---|
| AirJump | 成长 · 机动 · 空中跳跃 |
| AntiBounce | 未适配 · 挑战模式锁定 |
| AntiLevitation | 未适配 · 挑战模式锁定 |
| AvoidHazards | 未适配 · 挑战模式锁定 |
| BlockBounce | 未适配 · 挑战模式锁定 |
| BlockWalk | 未适配 · 挑战模式锁定 |
| Clip | 未适配 · 挑战模式锁定 |
| ElytraFly | 未适配 · 挑战模式锁定 |
| ElytraRecast | 未适配 · 挑战模式锁定 |
| EntityControl | 未适配 · 挑战模式锁定 |
| Fly | 成长 · 机动 · 空中行走 |
| Freeze | 未适配 · 挑战模式锁定 |
| HighJump | 未适配 · 挑战模式锁定 |
| InventoryMove | 未适配 · 挑战模式锁定 |
| LiquidWalk | 未适配 · 挑战模式锁定 |
| LongJump | 未适配 · 挑战模式锁定 |
| NoClip | 未适配 · 挑战模式锁定 |
| NoJumpDelay | 未适配 · 挑战模式锁定 |
| NoPush | 未适配 · 挑战模式锁定 |
| NoSlow | 未适配 · 挑战模式锁定 |
| NoWeb | 未适配 · 挑战模式锁定 |
| Parkour | 未适配 · 挑战模式锁定 |
| ReverseStep | 未适配 · 挑战模式锁定 |
| SafeWalk | 成长 · 建造 · 谨慎行走 |
| Sneak | 未适配 · 挑战模式锁定 |
| Speed | 成长 · 机动 · 迅捷 |
| Spider | 未适配 · 挑战模式锁定 |
| Sprint | 未适配 · 挑战模式锁定 |
| Step | 成长 · 机动 · 越障 |
| Strafe | 未适配 · 挑战模式锁定 |
| TargetStrafe | 未适配 · 挑战模式锁定 |
| TerrainSpeed | 未适配 · 挑战模式锁定 |
| VehicleBoost | 未适配 · 挑战模式锁定 |
| VehicleControl | 未适配 · 挑战模式锁定 |

### FUN

| 功能 | v1 定位 |
|---|---|
| DankBobbing | 未适配 · 挑战模式锁定 |
| Derp | 未适配 · 挑战模式锁定 |
| HandDerp | 未适配 · 挑战模式锁定 |
| SkinDerp | 未适配 · 挑战模式锁定 |
| Twerk | 未适配 · 挑战模式锁定 |
| Vomit | 未适配 · 挑战模式锁定 |

### MISC

| 功能 | v1 定位 |
|---|---|
| AntiBot | 未适配 · 挑战模式锁定 |
| AntiStaff | 未适配 · 挑战模式锁定 |
| AutoAccount | 未适配 · 挑战模式锁定 |
| AutoChatGame | 未适配 · 挑战模式锁定 |
| AutoPearl | 未适配 · 挑战模式锁定 |
| BetterChat | 未适配 · 挑战模式锁定 |
| FlagCheck | 未适配 · 挑战模式锁定 |
| Focus | 未适配 · 挑战模式锁定 |
| InventoryTracker | 未适配 · 挑战模式锁定 |
| MiddleClickAction | 未适配 · 挑战模式锁定 |
| NameProtect | 未适配 · 挑战模式锁定 |
| Notifier | 未适配 · 挑战模式锁定 |
| PacketLogger | 未适配 · 挑战模式锁定 |
| Spammer | 未适配 · 挑战模式锁定 |
| Teams | 未适配 · 挑战模式锁定 |

### RENDER

| 功能 | v1 定位 |
|---|---|
| Animations | 免费 · 界面/装饰 |
| AntiBlind | 未适配 · 挑战模式锁定 |
| AttackEffects | 未适配 · 挑战模式锁定 |
| AutoF5 | 未适配 · 挑战模式锁定 |
| BedPlates | 未适配 · 挑战模式锁定 |
| BlockESP | 未适配 · 挑战模式锁定 |
| BlockOutline | 未适配 · 挑战模式锁定 |
| Breadcrumbs | 免费 · 界面/装饰 |
| CameraClip | 未适配 · 挑战模式锁定 |
| Chams | 未适配 · 挑战模式锁定 |
| ClickGUI | 免费 · 界面/装饰 |
| CombineMobs | 未适配 · 挑战模式锁定 |
| CustomAmbience | 未适配 · 挑战模式锁定 |
| DamageParticles | 未适配 · 挑战模式锁定 |
| Debug | 未适配 · 挑战模式锁定 |
| ESP | 成长 · 情报 · 敌情轮廓 |
| FreeCam | 未适配 · 挑战模式锁定 |
| FreeLook | 未适配 · 挑战模式锁定 |
| FullBright | 免费 · 界面/装饰 |
| HoleESP | 未适配 · 挑战模式锁定 |
| HUD | 免费 · 界面/装饰 |
| ItemChams | 未适配 · 挑战模式锁定 |
| ItemESP | 未适配 · 挑战模式锁定 |
| ItemTags | 未适配 · 挑战模式锁定 |
| JumpEffect | 免费 · 界面/装饰 |
| MobOwners | 未适配 · 挑战模式锁定 |
| MurderMystery | 未适配 · 挑战模式锁定 |
| Nametags | 成长 · 情报 · 敌情标记 |
| NoBob | 免费 · 界面/装饰 |
| NoFOV | 未适配 · 挑战模式锁定 |
| NoHurtCam | 免费 · 界面/装饰 |
| NoSignRender | 未适配 · 挑战模式锁定 |
| NoSwing | 未适配 · 挑战模式锁定 |
| ProphuntESP | 未适配 · 挑战模式锁定 |
| QuickPerspectiveSwap | 未适配 · 挑战模式锁定 |
| Rotations | 未适配 · 挑战模式锁定 |
| SilentHotbar | 未适配 · 挑战模式锁定 |
| StorageESP | 未适配 · 挑战模式锁定 |
| TNTTimer | 未适配 · 挑战模式锁定 |
| Tracers | 成长 · 情报 · 方向指示 |
| Trajectories | 未适配 · 挑战模式锁定 |
| TrueSight | 未适配 · 挑战模式锁定 |
| XRay | 未适配 · 挑战模式锁定 |
| Zoom | 未适配 · 挑战模式锁定 |

### WORLD

| 功能 | v1 定位 |
|---|---|
| AutoBuild | 未适配 · 挑战模式锁定 |
| AutoDisable | 未适配 · 挑战模式锁定 |
| AutoFarm | 未适配 · 挑战模式锁定 |
| AutoTool | 成长 · 工具 · 工具选择 |
| AutoTrap | 未适配 · 挑战模式锁定 |
| BedDefender | 未适配 · 挑战模式锁定 |
| Extinguish | 未适配 · 挑战模式锁定 |
| FastBreak | 未适配 · 挑战模式锁定 |
| FastPlace | 未适配 · 挑战模式锁定 |
| Fucker | 未适配 · 挑战模式锁定 |
| HoleFiller | 未适配 · 挑战模式锁定 |
| LiquidPlace | 未适配 · 挑战模式锁定 |
| NoSlowBreak | 未适配 · 挑战模式锁定 |
| Nuker | 未适配 · 挑战模式锁定 |
| PacketMine | 未适配 · 挑战模式锁定 |
| ProjectilePuncher | 未适配 · 挑战模式锁定 |
| Scaffold | 成长 · 建造 · 自动搭桥 |
| Surround | 未适配 · 挑战模式锁定 |
| Timer | 未适配 · 挑战模式锁定 |

### CLIENT

| 功能 | v1 定位 |
|---|---|
| AutoConfig | 未适配 · 挑战模式锁定 |
| LiquidChat | 未适配 · 挑战模式锁定 |
| RichPresence | 未适配 · 挑战模式锁定 |
| Targets | 免费 · 界面/装饰 |

## 实现与后续测试

统一入口是 `ModuleManager.addModule` 完成后的模块注册，不为每项复制 `<clinit>` Mixin。Value 的正常写、配置导入、脚本/backing write、执行读取，以及 ClientModule 的 activation/running 都受同一目录规则约束。未知模块及脚本新增模块在挑战模式默认拒绝，不能伪装成免费 HUD。

原生数值类型保留 Float/Int/IntRange/FloatRange，实际范围同时服从原LB范围、目录上界和服务器更窄的授权范围。升阶保留合法原值；降阶/退款立即限制读取并走原模块disable清理。模式可选集合与RangedValue元数据一并更新，使用LB ValueChangedEvent通知WebGUI，退出挑战恢复原模式清单/slider元数据。浏览器实际刷新效果仍需集中实测。

集中测试需覆盖：无点数启用被拒绝、导入高值、脚本/内部修改、升阶保留合法设置、降阶/退款撤权、断线与重连；随后按构筑预设分别测地面Speed、Scaffold、双跳/飞行、普通受击与Velocity、NoFall。不能以显示界面正常代替服务端或宿主战斗/移动生效。
