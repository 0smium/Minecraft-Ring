# 法环采石、地形破坏与地下挖矿可行性

2026-10-14，基于0.3.4候选、固定EldenCraft源码、当前Smithbox源码和本机游戏文件的只读调查。**没有启动游戏、修改地图、删除原生碰撞或实现新的挖矿玩法。**

用户随后澄清首选目标是**法环地表外观 + 同坐标MC自然地下 + 局部洞口视觉覆盖**，不要求原生山体真的开洞。后续主线见[混合地下方案](underground-overlay-plan.md)；本页原生模型/碰撞切除的条件不作为该方案前置。

本页针对原生地形切除的结论：采石掉落可以独立做；在指定区域提供真正可挖的矿层也有现实路径。任意法环山体的原生网格像MC一样逐格挖穿，当前还没有画面、物理和生命周期的完整方案。发现了值得研究的原生局部遮罩类型，因此不能简单断言不可能；也不能把已有“新增MC碰撞体”当成已经能从法环地形减去体积。用户澄清的混合地下方案绕过了原生网格切除需求，另按上述新方案推进。

## 一块“法环石头”其实跨越哪些系统

| 层 | 当前情况 | 挖出洞需要什么 |
| --- | --- | --- |
| 可见画面 | 原生MapPiece/Asset模型与材质；合成器仅叠加MC画面 | 移除/裁切相应可见面并补内壁，或让一块明确空间完全由MC渲染 |
| 原生物理 | Havok body、复合/压缩网格及过滤层 | 同步改变该局部碰撞，不能把整个大body当作一格删除 |
| MC碰撞缓存 | TerrainBlock/TerrainShapeBlock由原生射线反复采样生成，不是完整地下体积 | 原生仍存在时，删除代理格可能被重采样补回；必须明确哪片空间由MC拥有 |
| 掉落与持久化 | 原版石头/矿石已有工具、硬度、掉落和区块保存 | 明确材料来源、唯一发货、矿点初始化与已挖空气，不能重进补矿 |
| 地图规则 | 原生导航、加载关联、事件和死亡区域仍按原地图 | 逐项处理；能通行不等于AI会绕路或地下已没有死亡面 |

当前射线得到的是所经过的表面，而非岩层体积。一次地面命中无法说明下面100格是岩石、原生洞穴、另一层地图还是未知空域。生成煤铁矿脉可以由MC规则完成，但不能把这种新生成数据描述成“读出了法环原本的地下矿石”。

## 这次新增的证据

### EldenCraft 的 mine terrain 是采集，不是开洞

固定 `TerrainMining.harvest` 使用原版掉落、工具耐久、经验与饥饿消耗，并记录该格耗竭；没有把原生表面或代理格改为空气。默认十分钟耗竭只存在静态 `SPENT` HashMap，换服务器实例会clear，没有该耗竭表的磁盘读写。我们若采用此机制，应另外做持久矿点/耗竭规则，不能把重进重置当成矿洞保存。见[固定TerrainMining源码](https://github.com/SocketByte/EldenCraft/blob/ba7591cdde9fb16e17552cfe53546f44d0fd856e/minecraft/src/client/java/dev/eldencraft/bridge/client/TerrainMining.java)。

已有本项目攻击原生地形的路径会生成poke弹，使用`AtkParam.atkObj`伤害原生可破坏物。罐子、木箱等作者预设物件可以沿这条路径研究采集；弹体生成或命中不等于实际销毁。发MC材料前仍需识别资产实例、确认破坏并去重，不能每击打一次任意地面就无限掉石头。

### 可见地图片与碰撞不是一一对应的MC格子

本机只定点读了Data2中的 `/map/mapstudio/m60_42_36_00.msb.dcx`，压缩含padding为35,216字节，解压1,078,168字节。按MSBE73头表只读统计：

- MapPiece：4个模型、4个实例。
- Collision：9个模型、8个普通实例，另有2个ConnectCollision实例。
- Asset：70个模型、687个实例。

可见模型为`m423600/602/603/604`，碰撞有`h423600/601/650…655`等。这个样本证明不能按同编号或数量假定显示和碰撞一对一，更不能假定一个body就是一米立方体。本次没有读取这些模型的全部顶点，也没有据此断言每片物理的实际范围。

本地Soulstruct2.6.0完整解析该MSB在`MSBOtherRegion`遇到非零subtype_offset错误，因此未尝试绕过校验后写回。计数只依赖固定[MSBE头表](https://github.com/soulsmods/SoulsFormatsNEXT/blob/ee1dd61958f60bdc51ce3da548e9a90a8ab39905/SoulsFormats/Formats/MSB/MSBE/MSBE.cs)、[模型类型](https://github.com/soulsmods/SoulsFormatsNEXT/blob/ee1dd61958f60bdc51ce3da548e9a90a8ab39905/SoulsFormats/Formats/MSB/MSBE/ModelParam.cs)和[Part类型](https://github.com/soulsmods/SoulsFormatsNEXT/blob/ee1dd61958f60bdc51ce3da548e9a90a8ab39905/SoulsFormats/Formats/MSB/MSBE/PartsParam.cs)。

### 离线改出一个矿区，已有实际工具链基础

[Smithbox2.2.5发布说明](https://github.com/vawser/Smithbox/releases/tag/2.2.5)明确支持ER/NR的Map/Asset碰撞生成，并可从FLVER生成碰撞。固定源码`cbd477a8fd6d436b3e011c8548e1de8fd8876918`的ER分支确实构建`hkGeometry`、BVH与`hknpExternMeshShape`，可替换原压缩碰撞网格；不是仅有一个尚未实现的按钮。

这支持“预先做出空矿坑/矿洞，再填入MC岩层”的方案，但没有提供运行时逐格挖掘。新生成extern mesh缺少FromSoftware特殊hit-material行为；水、熔岩、脚步材质等不能默认保留。可见FLVER的洞口/内壁需要另行制作，碰撞生成不会替我们切开画面。编辑结果应放项目覆盖层，不改原BDT；与当前启动方式的加载兼容仍需独立验证。

源码：[生成器入口](https://github.com/vawser/Smithbox/blob/cbd477a8fd6d436b3e011c8548e1de8fd8876918/src/Smithbox.Program/Editors/Havok%20Editor/Tools/Collision%20Generator/CollisionGeneratorTool.cs)、[HKX3构造](https://github.com/vawser/Smithbox/blob/cbd477a8fd6d436b3e011c8548e1de8fd8876918/src/Smithbox.Program/Editors/Havok%20Editor/Utilities/HavokShapeGenerator_HKX3.cs)、[项目保存路径](https://github.com/vawser/Smithbox/blob/cbd477a8fd6d436b3e011c8548e1de8fd8876918/src/Smithbox.Program/Editors/Havok%20Editor/Data/HavokBank.cs)。

不要混用其他游戏工具的支持范围：固定Soulstruct-Havok的[MapCollisionModel](https://github.com/Grimrukh/soulstruct-havok/blob/bf2d41fc83de4a43bd3ed3df8605741f143cad2c/src/soulstruct/havok/fromsoft/shared/map_collision.py)主要处理DeS/DS1的MOPP碰撞；不能据其导出成功就宣称ER2018碰撞已支持。

### 运行时局部切除有线索，尚无可用接口

新增只读工具 `python tools/inspect_terrain_shape_types.py`，验证完整EXE SHA后扫描RTTI。当前2.7.1.0中14个目标类型均存在，其中：

| 类型 | Type descriptor RVA | 候选vtable RVA |
| --- | --- | --- |
| hknpCompressedMeshShape | 0x3D248B8 | 0x2EEEA68 |
| hknpMaskedShape | 0x3D24748 | 0x2EE6B88 |
| hknpMaskedCompoundShape | 0x3D25600 | 0x2EEFC18 |

另有hknpShapeKeyMask、压缩网格KeyMask、hknpCompoundShapeKeyMask、fsnpCustomParamCompressedMeshShape等。**类型存在不能证明地图正在使用它，更不能证明我们已能修改它。** 本次没有验证每实例mask、构造/设置/撤销函数、原生缓存失效通知或运行时布局；旧Havok2014序列化偏移不能直接套到此EXE。

rich ray能返回body和shape key，但当前API丢弃了key。key不是MC方块坐标，也未建立它与MSB实例/可见FLVER三角面的对应。Havok单一body材质也不等于HitMtrlParam；逐三角材质未知时应保持未知。

即使以后能屏蔽几个碰撞三角形，也还缺可见面切割、矿洞内壁、剩余碰撞闭合、导航与存档。共享shape还可能被多个实例引用，需要每实例副本或遮罩，不能误改所有同模型对象。

## “整块转成MC石头后再挖”值得单独尝试

可以先选一个边界明确的独立岩石或预制小山块，取得完整模型/碰撞并离线体素化。接管时停用原实例的显示和碰撞，换成真正的MC石头；之后每次挖掘都只改变自己的体素和0.3.4原生刚体，已挖空间自然留下，原实例保持停用直到离开或恢复。

这比无界地图更容易定义完整的所有权和回退边界，但仍是未实现候选：必须证明这个显示实例和碰撞对象的对应与范围，确认模型是否封闭、如何填充内部；隐藏显示不能顺带隐藏其他共享实例，停用碰撞不能抹掉附近地板。需要完整几何，不能拿当前射线薄层缓存直接替换整座山。体素边缘会有MC风格与方格精度的代价。

第一目标应是一个独立岩石或事先划出的采石体积，不从任意脚下原生地面开始。若运行时实例禁用接口不可靠，可以在离线地图覆盖层中预先移除/改出空体积，再由MC填充；这就归入下表的预制矿区路线。

## 路线比较

下表是结合证据的实施判断，不是实机通过结果。

| 路线 | 能获得什么 | 能否向下挖 | 主要代价/边界 | 建议 |
| --- | --- | --- | --- | --- |
| 表面采石节点 | 挖掘动作、工具消耗、石料掉落 | 不能，原表面仍在 | 真实材料识别、持久耗竭/发货去重 | 可作为资源补充，不充当矿洞 |
| 已有洞穴空腔填MC石/矿 | 真方块挖空、开通道、光照/掉落/区块保存 | 在已有空腔内可以 | 不能越过原生洞壁；敌人与填料碰撞仍要验0.3.4候选 | **第一可玩切片** |
| 独立岩石整块转MC | 原实例停用后，体素岩石可逐格挖空 | 在被接管对象体积内可以 | 完整几何/体素化、实例与body对应、可逆停用尚未实现 | 最接近“法环石头变成可挖MC石头”的中间实验 |
| 预制采石场/地下体积 | 离线先改原生外壳和碰撞，再填MC岩层 | 在预留体积内可以 | 资产制作、原生加载/事件边界；不可任意全图开挖 | **同场景掘进的下一步** |
| 入口连接独立MC矿区 | 持久MC地下、矿脉、怪物、熔炼与制作 | 可以按MC规则扩展 | 要解除宿主坐标/生命/画面绑定；原生敌人先不跨入 | 要大规模下矿时采用 |
| 任意原生地形逐格实时切除 | 最接近全图Minecraft化 | 未证明 | 渲染+物理布尔/遮罩、内壁、流式加载、保存与导航都要解决 | 保留研究，不作为通关资源链前置 |

还有一个更小的变体：在原生地表上方铺可挖覆土/石层，挖到底是原生底板。能快速得到采石场体验，但不能宣传成挖穿法环地下。

## 为什么不能仅关深度或把角色往下传

本项目compositor读取的是已经完成的ER颜色/深度，再叠加MC像素。关深度测试只会让MC穿墙显示，不会删除原生山体，也无法恢复被原生表面遮住、根本没有出现在输入图像中的像素。局部洞口遮罩是候选视觉技巧：由MC补画洞口后的岩壁/内景，而非从现有ER帧“擦出真实洞穴”；物理与角色归属仍须另解。

独立矿区也不能仅执行`/tp`：`CameraSync.ready`当前不查维度，可能把地下MC位置继续写给隐藏法环角色；TerrainManager的启动/hostLife/F8回归会拉回overworld，Overlay仍按整个Bridge存档启用。生命桥接、原生效果/攻击、敌人代理、天气与碰撞发布也必须明确暂停。

因此独立矿区需要“地表→进入中→矿区→返回中”的统一状态，先解除绑定，再进入普通MC显示与持久维度。ER需停在经验证的安全锚点；当前没有已实现的整个ER世界暂停接口，不把固定角色或控制日夜时钟当作暂停游戏。初版可将F8定义为结束矿区并返回ER；宿主死亡/换区/重启则使用新的有效位置，不套用旧锚点。

## 推荐下一步

先做一处已确认有净空、没有剧情物件的洞穴矿脉，例如一个小石块体积内埋煤/铁，留安全入口。该区域只放普通MC方块，复用原版硬度、工具、掉落、熔炼和保存；记录矿点ID、ER区域/锚点、生成版本与已初始化标记。初始化只发生一次，重进不能把已挖空气填回。

一次验收覆盖“挖出通道→取得石料/矿物→制作/熔炼→退出重进保持”。先不要求ER敌人钻入新矿洞，也不改死亡逻辑。若需要更深，选预制矿区或独立维度；两者都能让成长/资源循环继续，不必等任意山体切割研究完成。

原生切割另保留一个有止损点的实验：先只读采一块小岩石/墙的body代次、shape类型、key、范围与共享情况；能完整对应到实例后，才考虑每实例可逆遮罩，并另验可见面。若只能整body删除、无法还原，或无法处理可见模型，就停在研究，不把穿模算作挖洞。

本轮证据与操作记录见[研究记录](experiments/2026-10-14-terrain-mining-research.md)。
