# 成长路线研究：从加数值到选玩法

2026-10-15。0.3.4追加Speed的第一条真实玩法分支；0.3.7加入Fly精确操控候选。本页区分已经实现、离线检查通过的候选和仍未开放的方向，实机效果留待集中验证。

## 本批已经改变什么

0.3.4完整保留原29节点的ID、前置与价格，不动已经实测的Speed II/真实点数。在Speed I旁追加`speed_hop_1`，成本6点，授予原LB的`LegitHop`选项。地面路线仍是原来的4+4+12点；连跳只做单阶，因为原LegitHop没有第二组可分级参数。学会两路后在同一个Speed模块选择模式，不叠加两个算法。

![由当前节点数据生成的布局预览](diagrams/growth-tree-layout.svg)

这是由实际32节点生成的矢量预览，不是游戏截图。步法支线从同一Speed I分开，飞行从Fly I分开，Skills负责前置、6点消费和退款；并非把旧Speed II改名成新路线。

## 本次可测试的Speed分支

- `speed_ground`：8点，Speed II，仅Custom，地面速度上限0.33。
- `speed_hop`：10点，Speed I加连跳，可选Custom/LegitHop；LegitHop不继承Custom的速度滑块。
- `speed_routes`：14点，Speed II加连跳，用来检查模式来回切换及退款。

沿用`/erbridge progression preset apply <名称>`和`preset clear`，预设不自动启用模块。应用前的Speed II与真实额度由原日志备份并恢复。正常学习也可购买连跳，原9点存档仍花8余1，不补发、不迁移。

授权现在按同一模块的独立已学分支合成允许模式；服务端仍发送各节点所属能力的独立授权。未学连跳时原自动跳跃处理保持关闭；学会且实际选中顶层LegitHop后，才放行原`SpeedBHopBase`处理。OnlyInCombat等隐藏替代路径不能借用该权限。切换使用原LB `setByString`的停用/启用流程；退款当前连跳先停Speed，再选回Custom，不自动重开。地面速度上限继续取原世界覆盖与已学地面等级，不因多学一条路线而提高。

离线覆盖了真实Skills30节点购买/退款、9点Speed II兼容、全部预设恢复、策略包往返、原LB Choice切换及GUI模式过滤。LB模块启用的最终副作用使用明确的测试替身；没有启动游戏或证明坡地连跳已通过。集中测试需在安全平地先比对两路，再看低顶/坡地、停止移动及退款退出；YPort仍不开放。

## 固定 LB 中实际有什么

依据只读源码 `e5a0dbf5458b063d3028e69e04762b8b25b998b5`，不引入新版算法。

| 候选 | 已有实际行为 | 设计判断 |
| --- | --- | --- |
| Speed Custom 地面路线 | 原 Strafe 有速度、方向强度等设置；目前关闭 BHop 和垂直修改 | 保留低风险、可预期的地面疾行路线 |
| Speed LegitHop / Custom BHop | `SpeedBHopBase` 在着地且移动时触发原跳跃输入 | 本批只给已学LegitHop放行；Custom仍手动跳。斜坡、楼梯、低顶与受击待测 |
| Speed YPort | 速度硬编码 0.4，向上 0.42，一 tick 后向下 1；没有可直接分级的速度参数 | 目前先列实验。急速向下与已有地形采样/平台问题相冲突；不能用正常速度滑块假装已限制它 |
| Fly Creative | 设置 flySpeed、冲刺速度、最大速度；可强制持续飞行 | 适合巡航路线。本版入门已经能无限悬停，不能再把“无限悬停”当作未学的后期收益 |
| Fly Vanilla | 横向、纵向、冲刺速度各自独立；松键的升降由 Glide 控制 | 可做精确悬停/升降路线；数值含义与 Creative flySpeed 不同，不能直接拿 0.1 对 0.1 比速度 |
| Fly AirWalk / 特定反作弊模式 | 修改脚下碰撞、onGround或依赖特定服务器行为 | 不作为成长奖励直接开放，以免破坏桥接的碰撞/坠落语义 |

参考：[SpeedGeneric](https://github.com/CCBlueX/LiquidBounce/blob/e5a0dbf5458b063d3028e69e04762b8b25b998b5/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/movement/speed/modes/SpeedGeneric.kt)、[SpeedCustom](https://github.com/CCBlueX/LiquidBounce/blob/e5a0dbf5458b063d3028e69e04762b8b25b998b5/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/movement/speed/modes/SpeedCustom.kt)、[FlyGeneric](https://github.com/CCBlueX/LiquidBounce/blob/e5a0dbf5458b063d3028e69e04762b8b25b998b5/src/main/kotlin/net/ccbluex/liquidbounce/features/module/modules/movement/fly/modes/FlyGeneric.kt)。

## 建议的两条首发分支

先做玩法差别明确的 Speed 和 Fly，保留其他能力的实用基础，不给所有单开关功能硬凑三档。

| 能力 | 路线 A | 路线 B | 取舍 |
| --- | --- | --- | --- |
| Speed | 地面疾行：低跳动、稳定转向 | 连跳：以持续起跳和空中衔接穿行 | 低顶、狭窄地面偏地面；开阔路线偏连跳，配 Step/AirJump 的成本不同 |
| Fly | 巡航：较高水平/冲刺速度，普通升降 | 精确操控：独立升降、低速定位与悬停 | 长距离赶路与建筑/窄处定位的选择，不能把 B 设计成 A 的全面升级 |

“自由飞行”如果指更长续航、摆脱高度限制或空中充能，需要单独加入真实的资源/时间/高度约束。固定 LB 的 Creative 并不提供这些限制。本批没有偷偷增加一个飞行计时器，也没有把 BypassVanillaCheck 当作自由度奖励。

成本候选沿用约 100–120 点的通关目标：

- 全部基础仍以 76 点为预算基准。
- Speed 公共入门 4 点已包含在基础中。地面后续 4+12；本批连跳单阶额外6点。
- Fly 公共入门14点已包含在基础中。巡航后续6+20；精确操控候选8+18，两条各需额外26点。
- 广修 + 地面精通 + 一条Fly路线约118点；改选单阶连跳约108点。广修 + Speed/Aura 两项精通仍为116点。
- 可以投入更多点学第二路线，但同一模块一次只选一种模式；通过机会成本鼓励专精，先不做不可逆职业锁。

0.3.7已将Fly预算落实为独立分支：先投入40/60点，再分别花8/18点。原基础76点、广修加Speed/Aura精通116点不变；原232点 + 连跳6点 + 精确飞行26点 = 全满264点。保持100–120点广修并选择1–2项专精的目标，不要求兼修所有路线。

## 实现需要改哪里

本次使用独立分支能力和`ModuleCapability`合成同一模块的模式，不再要求同一模块只能对应一个线性rank；Skills继续拥有消费/退款，账本版本保持2。Fly已经复用这套分支结构，并加入flight_precision（102点）和flight_routes（128点）两个可撤销测试预设；不自动选择模式或开启模块。

Fly Vanilla与Creative有一个关键差异：Vanilla直接设置原MC速度，不维护`abilities.flying`。0.3.7增加公共飞行状态接口与本机宿主活动声明/确认：只有已授权并实际启用的Vanilla计作飞行，相机、平台和落地使用同一分类；不伪造vanilla flying标志，Creative原许可链保留。精确I水平0.10–0.25、升降0.10–0.20格/tick，II放宽到0.40/0.30；Glide固定0、SprintSpeed关闭、BypassVanillaCheck关闭。精确操控的收益是独立低速定位，不把Creative已有的悬停再卖一次。新增生命周期与网络候选仍须集中验证，不能由目录/Skills离线通过替代实际飞行。

[Skills连接/状态文档](https://puffish.net/skillsmod/docs/creators/configuration/roots)支持这类数据图谱，但不意味着本项目已完成路线授权。

## 与建造和战斗成长的关系

建造路线应获得“能采集、能放置且能挡住敌人的建筑”，而非只有放置速度。资源采集/原生碰撞一旦落地，可以形成资源→搭建掩体→较安全战斗的投入回报；这比继续加 Scaffold 隐藏参数更有策略价值。战斗则保留 Aura距离与Velocity抗击退的不同投入，不把所有自动功能捆绑免费开启。相关机制见 [EldenCraft研究](eldencraft-physics-research.md)。
