# 原版生物群系与树木接入：下一片实施边界

2026-10-15。**只读核查与实施方案；未接入、未启用新生成器、未启动游戏。** 按用户顺序，先实测当前地表资源并确定排除区域，再接原版树木和生物群系。地下挖掘继续后置。

## 已核实的原版入口

直接检查本机固定 Minecraft 1.21.1 的资源 JSON 和 Mojang mappings 字节码。共同 JAR SHA-256 为 `03deb8d22dcecf5743ad7ceabc1ff47647194a3410506028baa8112f60918fa9`；可用 `python tools/inspect_vanilla_surface_features.py` 重读数据，不启动游戏、不修改存档。

| 层级 | 1.21.1 实际入口 | 本项目用法 |
| --- | --- | --- |
| 单棵树的形状和叶片 | `ConfiguredFeature.place(WorldGenLevel, ChunkGenerator, RandomSource, BlockPos)` | 从 `Registries.CONFIGURED_FEATURE` 取 `minecraft:oak` / `minecraft:birch`，在缓冲区调用完整 `TreeFeature`。 |
| 一组位置筛选器 | `PlacedFeature.place` / `placeWithBiomeCheck` | `oak_checked` / `birch_checked` 只含树苗 `would_survive`；群系树木条目还含密度、方块散布、高度、水深、生物群系筛选。不能混为一谈。 |
| 生物群系装饰列表 | `BiomeGenerationSettings.features()` 的 `VEGETAL_DECORATION` 步骤 | 后续只允许已适配的树木、花草条目，不能批量执行整个列表。 |
| 全区块装饰 | `ChunkGenerator.applyBiomeDecoration` | 本片不调用；它包括多个生成步骤和结构相关路径，不适合在已生成的 ER 空区块补一次“树”。 |

原版参数不是当前手工模板：普通橡树树干高度为 4–6，桦树为 5–7；均用 `StraightTrunkPlacer`、半径 2 / height 3 / offset 0 的 `BlobFoliagePlacer`，无树装饰器。完整树在树干起点 `origin` 的 `y-1` 写根土，最高树叶可到 `origin.y + trunkHeight`；普通冠部横向最大半径为 2。净空检查还会读到树顶外一层，邻接与叶片处理另需读边界。不能仅按树干尺寸分配缓冲。

`TreeFeature.place` 会在放置完成后运行 `updateLeaves` 和 `StructureTemplate.updateShapeAtEdge`。应保留它们的最终块状态，叶片保持 `persistent=false`，不再用手写树冠与额外自制 distance 算法代替原版。树苗自然生长仍可继续走原版机制。

土壤有两个不同问题：`*_checked` 的树苗存活判定不认识 ER 碰撞代理；直接调用普通 `ConfiguredFeature` 也不能当作安全验证，因为 `StraightTrunkPlacer.setDirtAt` 会尝试把根下方改成 dirt。必须由桥接先证明这一格是允许种植的原生地面，在缓冲中提供土壤视图，并将根土写入列入最终变更检查。不得直接将所有碰撞代理加入原版土壤 tag。

## 最小下一片：单棵原版树，有界生成后再落地

1. 继续复用 `SurfaceResourceSites` 的稳定地表采样、载入与净空判断、移动平台排除，以及资源排除区域。用已有确定性候选位置和站点记录；第一片只替换橡树/桦树形状生成，花草、石堆先保留当前实现。
2. 在服务器线程先复制已加载区域的块状态、代理地面证据和必要流体信息，建立 **有界 `WorldGenLevel` 缓冲适配器**。所有读取看快照及本次暂存写入，所有 `setBlock` 只写变更表；未加载、越界读取或写入均使整棵候选失败，不能返回“空气”掩盖未知地形。
3. 起步允许的普通树写入框可取 `x/z ±2`、`origin.y-1..origin.y+7`，读取框另扩至 `x/z ±4`、`origin.y-2..origin.y+9`。这是针对固定普通 oak/birch 的保守起点，不是所有树的通用常量；用多种种子记录实际访问，任何超界都拒绝，不隐式扩大或申请区块。
4. 适配器也必须截获 `isStateAtPosition`、`getFluidState`、高度、邻接形状更新和 tick 调度。禁用真实 chunk 写口、方块实体、实体生成和真实 `ServerLevel` 逃逸；本片不允许含蜂巢、藤蔓、根系、实体行为的装饰器。`TreeFeature` 的临时写入不是原子操作，不能在真实世界生成后以返回 false 当作“无事发生”。
5. 在缓冲中为获批根位补出 dirt，调用注册表里的原版配置，以稳定的 world seed + mapping/zone + site + generator version 取得随机源。检查完整变更集：只允许指定树种的 log/leaves 与获批根位 dirt，限定数量、边界、支撑和无玩家方块覆盖。运行后再次校验源块/采样/区域规则未变化，才以现有服务器线程事务写回并保存站点完成记录。
6. 只接普通树时，传入现有 chunk generator 足够提供 API 参数，不能据此宣称已接原版群系树木分布。生物群系选择和 `PlacedFeature` 的适配是后续独立一步。

原生世界采样必须发生在调用 feature **之前**：先用固定树种的已知最大框申请有上限的 ER 采样任务，在正常射线调度中分帧完成；记录 map/host/life、采样范围、时间和几何证据，再复制成只读快照。生成过程中不得因 `getBlockState` 或高度查询临时发送更多 native 请求；没有证据的位置是 UNKNOWN，整棵等待/拒绝，不能当 AIR。落地前重新检查快照身份与新鲜度。

现有 `SurfaceResourceSites` 只是地面、局部墙探针和 40 m 头顶带的稀疏几何证据，并不是整棵树冠三维体积的已验证占用，也不能识别屋顶材质或法环水体。因此原版树接入还需按最大树冠补足有界净空证明或先限定已测区域；不能声称有了内存缓冲就解决这些误生成。缓冲只限制 MC API 的访问与写入范围，不会让任意 feature 的读取自动有界。超界、未知装饰器、未知 API 均拒绝；扩大支持范围前必须单独核查该 feature 的最大访问与采样成本。

离线可先完成：真实 `ConfiguredFeature` 在内存适配器上的多 seed 形状生成、负坐标与跨区块边界、树叶 distance/根土验证、失败无落地、缺块不申请、未知 API 调用拒绝。它不需要运行 ER 或生产 MC 世界；最终视觉、采集和屋顶/水边误判仍要游戏实测。

## 生物群系要分开接，不能只把 plains 改成 forest

目前 `WorldBootstrap` 使用一层 AIR 的 `FlatLevelSource`；`BridgeBiomes.onChunkLoad` 又把所有旧区块 palette 改成 plains。旧区块已经完成生成，仅换 generator 或 biome palette 都不会补出原版树。保留空地形生成器，通过受控资源 pass 渐进补装饰，避免重新生成区块或覆盖玩家建筑。

群系阶段先定义持久化的 ER 区域/坐标 → MC biome 映射，未分类仍用 plains，并记录映射版本。查询候选群系和写客户端 palette 必须共用同一规则；修改 `BridgeBiomes` 的强制 plains 行为后，再逐已加载区块迁移 palette、标记存盘和同步客户端。迁移 palette 不等于生成资源，也不能重置已采集站点。

原版 plains 的树条目是 `trees_plains`，选择普通/大橡树且带蜂巢概率；forest 使用 `trees_birch_and_oak`，birch_forest 使用 `trees_birch`。因此第一片普通 oak/birch 只能称“原版树形”，不能称完整原版森林。先为允许条目建立类型和边界白名单，再逐项开放大树、蜂巢等。

`BiomeFilter` 需要 `PlacementContext.topFeature`，调用含此筛选器的条目应走 `placeWithBiomeCheck`。它还会查询 **generator.getBiomeGenerationSettings(biome).hasFeature(topFeature)**；现有 FlatLevelSource 的生成设置不能因缓冲 `getBiome` 返回 forest 就视作已兼容。群系阶段需要受控的生成设置视图，返回选中 biome 的装饰设置，同时保证绝不调用该视图的地形/矿石/洞穴/结构生成入口。

`HeightmapPlacement`、`SurfaceWaterDepthFilter` 等也必须读取已证明的 ER 地表和水体，不能使用虚空区块的默认高度或以 MC 中没有水认定法环没有水。群系的花草列表中还有蘑菇、甘蔗、南瓜、地衣等；只有材料、落点和读写边界已经适配的 feature 才执行。

## 存档与现有资源兼容

- 保留现有站点的已生成/已采集记录；切换树形版本不能使同一位置重生另一棵树。为新候选记录 generator/biome mapping 版本，旧版站点仍参与占用判定。
- 区域排除作用于整棵树的变更范围，不能只检查根部；规则更新停止新增，不自动删除玩家可能已利用的树木、根土和建筑。
- 暂时缺块、未确认地表、玩家占用、生成超时都记作待定/本次失败；不永久标记为已生成。每 tick 的扫描及生成预算继续有上限。
- 不重放 vanilla 的全部 vegetation、更不重跑全 biome decorator；不改变原世界 seed、区块状态或已有物品实体。

核查证据：本机原版 `data/minecraft/worldgen/{configured_feature,placed_feature,biome}` JSON；字节码 `ConfiguredFeature`、`PlacedFeature`、`TreeFeature`、`TrunkPlacer`、`StraightTrunkPlacer`、`BlobFoliagePlacer`、`BiomeFilter`、`PlacementContext`。本地只读反汇编记录在忽略路径 `runtime/experiments/vanilla-tree-bytecode.txt`；原版资源和反汇编不提交 Git。
