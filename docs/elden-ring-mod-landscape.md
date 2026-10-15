# 法环模组分类与 Minecraft-Ring 的参考方向

核查日期：2026-10-15。项目基线：`b947649d9c05fcf2f0c2a57ebfd2780bdb4950e4` / 0.3.7 候选。本次根据作者发布页、官方文档和公开仓库研究；没有安装模组、启动游戏、修改存档或改变现有玩法配置。

最有价值的四条参考线：**Reforged 的构筑取舍、Convergence 的探索成长、Shadow Tower 的短循环、SpeedFog 的有约束随机遭遇**。下面先记录外部项目已经做了什么，再列我们自己的设计建议；“值得参考”不表示已经与 Bridge 共载或允许直接搬用文件。

## 1. 大型改造与玩法分类

| 类别 | 代表与作者来源 | 已核实内容 | 对我们的参考价值（设计判断） |
| --- | --- | --- | --- |
| 系统总改、长期构筑 | [ELDEN RING Reforged](https://www.nexusmods.com/eldenring/mods/541) | Fortunes 职业/副职业；可重配的 Runeforging；探索收集物、营地与制作支撑成长；武器攻击获取 FP 再用于技能 | 让技能、装备、消耗品和行动互相支持。保留广泛学习，用少量专精选择确定当前角色擅长什么 |
| 职业路线、地图内容 | [The Convergence](https://www.nexusmods.com/eldenring/mods/3419)、[官方 Spell Runes](https://wiki.convergencemod.com/wiki/Spell_Runes) | 不同职业起点与路线；大部分法术由世界/Boss/地牢中的分阶符文取得，职业笔记提供线索；地牢和地图改造 | 技能树旁边还应有“去哪里取得下一项能力”的目标。探索奖励可以给配方、分支资格或稀有材料 |
| 主题总改、战斗循环 | [Graceborne](https://www.nexusmods.com/eldenring/mods/5207)、[作者演示](https://www.youtube.com/watch?v=0juhwQFr0fU) | 变形武器、枪反、快步、Rally 与 Bloodborne 风格治疗/美术 | 参考行动和回报的循环、清晰反馈及统一主题。具体动画/枪反需要原生动作接入，不能由 MC 代理命中直接冒充 |
| 生存、制作经济 | [Survival Mode](https://www.nexusmods.com/eldenring/mods/1381) | 可分别启用饥饿/口渴/温度、疾病、武器制作树和黑夜；武器由材料及基础武器逐级制作 | 木、石、花和食物要有持续用途；环境与出行准备产生选择。MC 已有饥饿、熔炼、配方，优先用现成功能完成采集→制作→补给 |
| 敌人/物品随机化 | [Item & Enemy Randomizer](https://github.com/thefifthmatt/SoulsRandomizers)、[Fog Gate Randomizer](https://www.nexusmods.com/eldenring/mods/3295) | 随机种子、对象分类、进度可达关系；雾门改变区域连接，配合难度与物品配置 | 随机内容先满足进度和场地条件；同一种资源在不同阶段/区域有不同分布。它们的区域关系不等于室内、屋顶或水体识别 |
| 短局路线、随机挑战 | [SpeedFog](https://github.com/rbignon/speedfog)、下一节的塔类模组 | 原有区域/雾门可组织成有分支的短路线；不同塔项目选择随机房间、商店或连续战斗 | 为未来远征/附加地牢提供可反复玩的短流程；长期主线角色与局内随机收益分开管理 |
| 合作与便利 | [Seamless Co-op](https://github.com/yuiamoroll/EldenRingSeamlessCoopRelease)、[Transmogrify Armor](https://github.com/ThomasJClark/elden-ring-transmog) | 前者提供持续合作体验，后者分离防具外观和属性 | 参考队伍进度/中断恢复、显示与权威装备的分工。MC 联机与 ER 敌人同步仍是两个问题 |

[Reborn](https://www.nexusmods.com/eldenring/mods/2202) 可作为保持法环探索/战斗/成长节奏的另一总改对照，但本次其 wiki 入口失效，未据旧报道补写机制。公开的 [Sovereign](https://github.com/keenanselbee/sovereign) 还有机制/平衡文档，列为后续阅读候选；本次未确认其整体复用许可。

大型总改的价值常在完整的获得路径、数值配套和反馈。借一个孤立能力时，也要想清它在我们这里的获得阶段、资源投入和使用场景。

## 2. 用户提到的“爬塔 rogue”：尚未锁定名称

用户表示不确定名字。因此保留几个有辨认特征的候选，不将其中任何一个认定为用户看到的项目。

| 项目 | 辨认线索与作者证据 | 随机/重开机制的证据边界 |
| --- | --- | --- |
| [夜幕之影](https://www.bilibili.com/video/BV1hVWhzEEsh/) | 中文介绍，神授塔改成 12 层挑战、塔顶救梅琳娜；作者早期 4.6 为固定 Boss，页面补充 5.3 已有随机 Boss、Buff、异常事件及战利品，合集列 5.7 随机连战 | 中文语境下较匹配；未确认独立局外技能树，未找到公开源码 |
| [Shadow Tower](https://www.nexusmods.com/eldenring/mods/2317?tab=description) | 三层随机地牢后接 Boss/商店，再继续循环；分级随机掉落、逐层加强；死亡换预生成随机角色 | 肉鸽结构最明确。作者提及死亡会影响本体角色通关状态，这种持久状态处理不能直接用于我们的长期角色 |
| [Aljoscha’s Rogue Tower of Ascension](https://www.nexusmods.com/eldenring/mods/7278?tab=description) | 巨大实体高塔、7 类内部环境、仅底层赐福、重爬须重打 Boss，有资源和配方；作者记录寻路/升降梯局限 | 标题有 Rogue，但未核实随机楼层/随机奖励；适合研究实体塔的空间组织及地图问题 |
| [Tower of Judgment／审判之塔](https://www.nexusmods.com/eldenring/mods/10595) | 2026-08-12 发布，约 200 场战斗挑战，胜利后进入下一关、资源有限；作者提到新地图碰撞限制骨灰 | 较新的候选；未确认随机生成、局内构筑或长期元成长，先分类为连续挑战 |

这些不是同一种玩法：有的重点在实体高塔，有的在随机房间，有的在连续 Boss。对我们当前最有用的是“小段探索→遭遇→补给/奖励选择→更难目标”的节奏。

Nightreign／黑夜君临是独立作品，不能当作本体模组；[Dark Souls: Nightfall](https://grimrukh.com/nightfall/) 则是 DS1 项目，不因同名检索结果混入法环爬塔列表。

## 3. 特别值得深读：SpeedFog

固定只读参考点为 `69453fe520972ef6290e26b588232e1569ad17a4`（2026-10-01）。它把原有区域和雾门组织成约一小时的有向分支路线，考虑连通、排除和预计路径时间；这可以启发我们的远征路线规划，但不是现成的 MC 地下生成器。[路线生成说明](https://github.com/rbignon/speedfog/blob/69453fe520972ef6290e26b588232e1569ad17a4/docs/dag-generation.md)

更直接的技术启发是 **先匹配敌人与场地，再随机选择**。其规则按实体区分体型、龙、二阶段、NPC、逃逸行为，并与场地条件匹配；缺资料或不存在可行分配时报告失败。不能把大怪或具有场景脚本的 Boss 当作任意坐标上的普通实体。[场地约束](https://github.com/rbignon/speedfog/blob/69453fe520972ef6290e26b588232e1569ad17a4/docs/boss-arena-constraints.md)

对 Minecraft-Ring 的建议是先建立少量经过证据确认的敌人/场地卡片：需要多大空间、是否依赖导航/事件、是否多阶段、能否重置、允许哪些建筑操作。现有 MC 方块碰撞成功也不能证明这些条件成立。它的区域 ID、数据来源和许可需单独核对，不直接当作我们的 `CoordMap.zone` 或生物群系标签。

## 4. 哪些能读代码或加入工作流

以下为项目声明的许可/公开状态记录，不表示本批已经导入代码。实际复制某个文件前仍核其固定提交及第三方部分。

| 项目 | 已核公开状态 | 最有用的具体入口与限制 |
| --- | --- | --- |
| [Smithbox](https://github.com/vawser/Smithbox) | 根项目 MIT；DSMapStudio 的后续分支 | 地图、PARAM、FMG、纹理、ER Havok 编辑。用于离线提取区域/实体/图标信息；能编辑文件不等于能在游戏线程调用物理 API |
| [SoulsIds](https://github.com/thefifthmatt/SoulsIds)、[Universe.cs](https://github.com/thefifthmatt/SoulsIds/blob/master/SoulsIds/Universe.cs) | Apache-2.0 | 追踪 Map/Entity/NPC/ItemLot/EventFlag/Shop/Text 的关系。可用于“敌人实例→参数→名字/掉落/事件”的离线证据表；不能保证每个模型都有唯一中文名 |
| [SoulsFormatsNEXT](https://github.com/soulsmods/SoulsFormatsNEXT) | GPL-3.0 | 文件格式解析参考；与采用其他许可的编辑器区分，不能把整条依赖链统称 MIT |
| [DarkScript3](https://github.com/AinTunez/DarkScript3)、[ESDLang](https://github.com/thefifthmatt/ESDLang) | 本次未确认项目整体明确许可 | 分别研究 EMEVD 关卡/Boss 事件及 ESD 交互/菜单状态机。先作为工具与语义参考；解析到事件 ID 不等于有稳定运行时读取接口 |
| [Soulstruct](https://github.com/Grimrukh/soulstruct)、[soulstruct-havok](https://github.com/Grimrukh/soulstruct-havok) | 包元数据标 GPL-3.0-or-later | Python 格式/碰撞批处理；ER 的具体格式支持须逐项确认，不能概括为完整地图挖掘方案 |
| [Elden Ring Debug Tool](https://github.com/Nordgaren/Elden-Ring-Debug-Tool)、[目标面板源码](https://github.com/Nordgaren/Elden-Ring-Debug-Tool/blob/main/src/Elden-Ring-Debug-Tool-ViewModels/ViewModels/SubViewModels/EntityViewModels/EnemyViewModel.cs) | GPL-3.0 | 独立观察目标 HP、异常积累、韧性、动画等。未证实包含我们需要的全部 hitbox/挤压可视化，1.17.1 也须另验 |
| [SpeedFog](https://github.com/rbignon/speedfog/blob/69453fe520972ef6290e26b588232e1569ad17a4/LICENSE) | 自身 GPL-3.0-or-later；外部依赖另论 | 先读路线和敌人/场地约束代码；其调用的其他随机化程序与导入数据不自动获得同一许可 |
| [SoulsRandomizers](https://github.com/thefifthmatt/SoulsRandomizers/blob/master/LICENSE.md) | 作者明确为自定义 source-available，非开源；限制派生代码/数据分发 | 可以读设计与依赖边界，不能因 GitHub 可见就整段移植。旧 FogMod 仓库针对 DS1，也不能冒充 ER Fog Gate 源码 |
| [Transmogrify Armor](https://github.com/ThomasJClark/elden-ring-transmog)、[许可](https://github.com/ThomasJClark/elden-ring-transmog/blob/main/LICENSE.txt) | 自身 MIT 风格授权，附第三方声明 | 有真实源代码可研究外观/属性分离和原生菜单接入；README 的旧版本承诺不能外推到当前 App |
| [Survival Mode 源码](https://github.com/Grimrukh/EldenRingSurvivalMode) | 公开代码，但本次未确认整体开放许可；Nexus 资源利用受限制 | 参考制作树、事件与可选机制拆分。该老版本黑夜功能明确锁定 1.08.1，不安装到当前实验 |
| [ModEngine2](https://github.com/soulsmods/ModEngine2)、[me3](https://github.com/garyttierney/me3) | ME2 MIT，已停止开发；me3 代码 Apache-2.0/MIT 双许可 | 学习隔离配置和文件覆盖组织；不因研究就替换现有可工作的启动链，加载器不会自动合并冲突内容 |
| [Seamless 发布仓库](https://github.com/yuiamoroll/EldenRingSeamlessCoopRelease) | Nexus 发布镜像，不是实现源码 | 参考产品流程及未来兼容实验，不能把它当作可直接取用的敌人同步库 |

大型总改公开下载不等于开放素材/实现：Reforged、Convergence、Graceborne 作者页均有修改或资产复用限制。本次只做规则与实现路径研究，未复制总改素材、参数包或脚本。

## 5. 对我们下一步设计的建议

以下均为研究后的提案，**尚未启用或改变现有消费与存档**。

### 5.1 保持百点成长，增加可辨认的主攻方向

保留真实升级每级一点、约 100–120 点学大部分实用能力并精通 1–2 项的目标。可以评估“学习记录”加“当前专精选择”：学习不被清空，专精在安全地点可重配，带来明确收益与机会成本。先用现有能力设计少数构筑，再决定是否需要额外规则。

| 构筑示例 | 可复用的现有候选 | 应形成的玩法 | 需要验证/设计的取舍 |
| --- | --- | --- | --- |
| 疾行猎手 | Speed 连跳、AirJump、近距 Aura、敌情标记 | 观察→接近→地面战斗→重新调整位置 | 低顶/狭窄地形限制连跳；不自动拥有高速巡航全部收益 |
| 空中工匠 | Fly 精确、Scaffold、AutoTool、侦察 | 找落点→稳定定位→采集/建造 | 真实方块消耗和低速定位；战斗自动化另需投资 |
| 地面守卫 | Step、Velocity、SafeWalk、建筑 | 准备场地→承受压力→利用掩体 | 原生墙挡身体/投射物/近战须分别成立；资源投入影响可用掩体 |

这些目前只能称组合方向，不能把“同时打开几个模块”直接当成已实现职业。若增加飞行预算、回收或专精栏，须提供真实规则和 UI，不用菜单文字假装已有机制；原飞行节点的已购权益也不能悄悄削减。

### 5.2 给地表材料明确用途

先让现有木材/石料形成工作台、工具、熔炉、火把和建造补给流程；花草/食物服务补给。之后按林地、湿地、矿区等经过确认的区域主题分布资源，接原版 Feature 和生物群系。暂时不为“像生存游戏”同时添加口渴、冷热和疾病多个计量条。

参考 Convergence，把少量特殊配方/分支资格放到可追踪的探索目标；普通等级点继续负责主体成长。首次地牢/Boss 奖励必须基于真实完成事件与单次领取记录，敌人从代理列表消失不能算完成。这个方向直接对应现有 B04 遭遇闭环。

### 5.3 先做一个有选择的短流程

后续可用一块稳定区域设计“补给点→两条风险/收益不同的支路→精英目标→选择奖励”，先完成可重玩的 15–30 分钟内容。长度是我们的设计目标，不是外部模组数据。

若再做爬塔或地下挑战，局内三选一祝福、代价和临时能力单独保存，结束/失败后清理；真实等级、技能与装备仍按主线规则保存。现有测试预设只能用于验证，不能直接充当正式局内成长存档。远征是开放世界主线的附加模式，不替换用户要求的开局到通关成长。

### 5.4 近期最值得先做的研究/实现切片

1. 用 Smithbox/SoulsIds 的关系结构研究少量区域和敌人元数据：先能解释一个地牢、一个普通敌人、一个完成事件，不建设全地图百科。
2. 结合现有资源禁区，得到首批可信资源允许/禁止区域；再接原版树形。单靠垂直射线无法补出地图语义。
3. 在已验证的一处场地完成真实遭遇→确认死亡→一次奖励，让成长有世界目标；先用已验证模板或预置激活，不跳到任意 Boss 动态生成。
4. 设计三种已有能力构筑的获取路线和对照场景，再决定新增专精规则；继续保留 0.3.7 待集中实测清单。

## 6. 版本与研究边界

| 项目 | 作者页本次显示 | 与本机 App 1.17.1 的关系 |
| --- | --- | --- |
| Reforged | 2.3.5.3，2026-09-24，[文件页](https://www.nexusmods.com/eldenring/mods/541?tab=files) | 作者明确支持 App/Calibrations 1.17.1；不能据此确认 Bridge 共载 |
| Convergence | 3.0.3，2026-10-09 | 本次未确认作者明确的 1.17.1 声明；不把第三方补丁当成官方承诺 |
| Graceborne | 0.2.7 Alpha，2026-08-31 | 作者列 1.17，不外推 1.17.1 |
| Reborn | 0.9.9.6，2026-05-07 | 未确认 1.17.1 |
| Survival Mode | 1.6.1，2023-01-05 | 黑夜组件明示仅 1.08.1，作为历史设计/源码参考 |
| Shadow Tower / Aljoscha 高塔 | 2.3.6（2023-11-30）/0.9.1（2025-08-20） | 均未确认 1.17.1 |

核查的是作者声明和可读资料，没有实机体验这些模组。Fog Gate 与新版 Item/Enemy Randomizer 的兼容声明也存在版本差异，不能把旧的“兼容随机化”说明当作任意新版组合都能用。未来如果安排共载，独立保存配置与版本证据，再核参数、事件文件和原生钩子冲突；本次不追加共载工作。

下一步仍以[当前开发状态](current-development.md)为准。本页提供可追溯的外部参照与设计候选，不把研究内容标成完成，也不自动增加现有功能的前置条件。
