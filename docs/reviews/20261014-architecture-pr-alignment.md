# 2026-10-14：更正 B02 review，并收拢架构与 PR

审查点：`3f7447096556f11db4bad6066198e6306e5f205f`，继承 PR #3–#6。用户要求重新区分上游行为与自研逻辑，明确独立角色模组，复用现成配置/界面机制，结合实际骑乘限制 review。

本记录更正上一轮本地报告及聊天中容易误导的表述；当前决策见 [架构](../architecture.md)，PR 分工见 [当前状态](../current-development.md)。本轮只修改文档、导航和 PR 元数据/讨论，不把目标当成已改代码。

## 1. Speed：复用已经存在，上次的归因和建议需纠正

源码确认：

- `B01Files.Settings.policy()` 与 `tiers()` 生成三档数值区间；默认最大值为 0.30 / 0.33 / 0.36。
- `LiquidBounceCapabilityAccess.java:59–69,138–153` 设置 Custom/Strafe 辅助选项，并把用户值限制到已获准范围；不把当前速度固定为档位最大值。
- `CapabilityValueMixin` 已接 LB 的统一 Value set/get，能力由原客户端实现执行。Speed 没有在 Bridge 中另写一套算法。
- 固定 LB `e5a0dbf5458b063d3028e69e04762b8b25b998b5` 的 `SpeedCustom.kt:119–142`，在 CustomSpeed 分支调用 `player.strafe(speed, strength)`；`EntityExtensions.kt:211–240` 再设置水平 velocity。

“直接设置速度”是这个 LB 模式的上游行为，与“Bridge 每升一级强制固定速度”是不同事实。上一轮没有把两者解释清楚，造成了错误印象。这里撤回后一种理解，不要求回滚已经复用的 LB 算法。

MC 原版 Slowness 已通过 `MobEffects.MOVEMENT_SLOWDOWN` 使用。它影响正常移速属性，而上游直接改 velocity 的模块不保证遵守该属性；仅看到 MC 效果图标不能推定所有客户端模块都联动。当前是需要明确的兼容语义与实测项，不应借此再实现一个 Bridge 移动器。最终位移和击退影响尚未在本次实测。

用户要求的方向是：解锁原模块、放宽参数范围、继续由用户调参。泛化现有策略和 Value 守卫，不为每个技能复制实现。目前 `CapabilitySnapshot.Ability` 只有 SPEED/AURA，payload、配置与映射也含二选一硬编码，尚不能称为广泛可复用的完整策略层。

固定 LB 的 AutoConfig 可复用配置解析、保存和 ClickGUI 刷新；其 `ModuleAutoConfig` 主要按服务器选择配置，本身不负责技能授权。配置导入之后仍需要通用的范围、启用和执行守卫，不能只有一次性设置文件。

固定源码：

- [B01Files](https://github.com/0smium/Minecraft-Ring/blob/3f7447096556f11db4bad6066198e6306e5f205f/bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge/gameplay/runtime/B01Files.java)
- [LB 适配入口](https://github.com/0smium/Minecraft-Ring/blob/3f7447096556f11db4bad6066198e6306e5f205f/bridge-base/elden-ring/mc-bridge/src/client/java/dev/ermc/bridge/client/integrations/LiquidBounceCapabilityAccess.java)
- [上游 SpeedCustom](https://github.com/CCBlueX/LiquidBounce/blob/e5a0dbf5458b063d3028e69e04762b8b25b998b5/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/movement/speed/modes/SpeedCustom.kt)
- [上游 strafe](https://github.com/CCBlueX/LiquidBounce/blob/e5a0dbf5458b063d3028e69e04762b8b25b998b5/src/main/kotlin/net/ccbluex/liquidbounce/utils/entity/EntityExtensions.kt)
- [上游 AutoConfig](https://github.com/CCBlueX/LiquidBounce/blob/e5a0dbf5458b063d3028e69e04762b8b25b998b5/src/main/kotlin/net/ccbluex/liquidbounce/config/AutoConfig.kt)

## 2. 效果：继续用原版，明确“减速”和“失能”的差别

`HostEffectRules` 当前把冻伤设为 Slowness I，睡眠设为 Slowness IV + Blindness I；没有 Mining Fatigue，也没有完整禁行动。所以用户要求的“睡眠时静止只能挨打”目前没实现。

本轮用户提出 Blindness + 高等级 Slowness / Mining Fatigue 的近似效果，作为后续可配置目标；不要擅自把具体强度写成已验证平衡值。效果本身仍复用 MC，不自造一套中毒/减速系统。

这些效果不能单独保证禁止跳跃、普通攻击、使用物品或自动模块。需要统一临时状态策略处理主动输入和冲突能力，保留受击、击退、重力；解除依据真实宿主状态，不猜测醒来条件。用同一份状态约束 Speed/Fly/Aura 等，避免逐个功能各写一套睡眠分支。

## 3. 骑乘：撤回 R14-03 的 P2 定性，先确认场景准入

用户指出法环有骑乘区域限制，这应纳入正常马哨的功能定义。上一轮只从马 AABB 推出低顶风险，没有先确定允许骑乘场景，不足以列为已确认 P2 运行故障。将其改为“允许场景中的条件性验证项”，没有观测到卡位或窒息。

同时，`MountRuntime.command()` 的现有准入只检查本机主人、桥接世界、有效宿主、固定地面、载具/水/飞行等条件；没有读取 ER 的原生骑乘许可。因此不能反向声称“原版限制已经自动限制了当前 MC summon 命令”。

后续先确认可用的宿主许可/区域规则，区分允许、禁止、未知。正常马哨遵守该规则；若管理员需要越界实验，必须明确标为调试入口。仅在实际允许场景内验证原版马及骑手占用空间；玩家搭 MC 顶棚等另列组合用例，不预设真实地图必有某种障碍。

参考 EldenCraft 的 `TorrentPolicy.allowedArea` 有区域白名单，但这是该项目的近似实现，不是本项目当前版本已核验的完整原生规则。本次没有添加地图白名单、猜偏移或重写马物理。

## 4. 独立模组要求此前被弱化，现明确纠正

以前规划写“先分包，必要时再拆”，没有落实用户的独立 Bridge 模组要求。当前只有一个 Fabric mod，B02 的马、UI 与规则仍混在底座。这个架构问题保留，并升级为下一批明确交付条件：

- 同一 Git 仓库内新增独立角色 Fabric 模组，具有独立 Gradle 构建、mod ID、入口和 JAR；原底座可单独运行。
- 底座通过公共运动主体/生命周期/宿主能力接口提供服务，不依赖具体 Horse 管理或技能树。
- Skills、LB、Meteor 的版本适配与依赖隔离；Core-only 不因它们缺失而构建/加载失败。
- 复用 MC 容器页与装备槽、Pufferfish 图谱、原版效果和 Horse。新增代码集中在跨游戏映射与请求确认。

R14-02 的构建/预检不一致仍成立：Gradle 无条件要求 Skills，而启动器仅在 B01 检查。短期统一预检；随模块迁移解除 Core 的无关依赖。此前按安装文档准备完整的机器可能不触发，不把它说成所有现有启动均失败。

## 5. 背包附加页的实际范围

当前 `HostInventoryScreen` 是继承 `Screen` 的只读 ID 列表，不是装备菜单。背包页本身可复用原版容器与槽位，不需要重写 UI 框架；Trinkets 等提供额外槽位 API，但并不会自动执行 ER 换装。

近期用原版 menu/screen/slot 做额外装备页，正常 MC 背包保留；原生库存为唯一来源。UI 发装配/卸下意图，宿主执行确认后刷新。先跑通一件装备，再通过槽位描述扩展。真正需要连接的是物品身份、原生装备执行与确认、属性来源；不能把“画了一个槽位”当成完成换装，也不把整个需求夸大成要重写背包系统。

依据：[Fabric 容器机制](https://wiki.fabricmc.net/tutorial:screenhandler)、[1.21.1 API](https://maven.fabricmc.net/docs/yarn-1.21.1+build.3/net/minecraft/screen/ScreenHandler.html)、[Trinkets 官方说明](https://github.com/emilyploszaj/trinkets)。本轮没有安装新依赖或宣称它们已与合成窗口共载。

## 6. PR 整理与 review 标准

PR #1 的历史计划已在实现分支；PR #2 的独有文档迁入 #6，保留六篇历史 review，不覆盖新实现文档。用当前状态页与架构统一后续要求，再关闭两条被接替的纯规划 PR。#3–#6 保留叠加关系、draft 和未测事实，不改写代码历史或自动合并。

此后每项 review 明确标记“源码确认 / 上游既有行为 / 运行观察 / 新需求 / 条件性风险”。没有真实触发场景或不符合产品场景的问题不直接列作运行缺陷；每项适配先列原版/上游复用点，再说明确实需要的新逻辑。原生读取、实例归属、状态唯一来源等已有校验保留，不因为要求复用就删除。

本轮检查：固定仓库与 LB 源码、原版容器 API、EldenCraft 参考、PR 当前正文/依赖/评论；文档相对链接、旧规划迁移、非文档差异和 Markdown 格式。没有新增游戏实测，也没有重跑与文档修改无关的完整构建。
