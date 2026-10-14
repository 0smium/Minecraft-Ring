# 2026-10-14：地形破坏与挖矿只读研究

起点：`codex/b02-character-slice@3a3b432d7ae843d01296c6f78ceed704c184c652`，已有0.3.4候选。用户要求探索采石、破坏法环地形和地下挖矿及替代办法。本轮只增加研究文档和磁盘RTTI探针，不改游戏功能版本。

## 实际操作与结果

| 操作 | 结果 | 限制 |
| --- | --- | --- |
| 阅读固定EldenCraft TerrainMining/SharedWorldClient/原生ray | 采集使用MC掉落和工具，原生地形不删；耗竭HashMap无磁盘持久；射线有body/key | 未运行采集或证明key可写 |
| 浏览Smithbox官方发布、读取固定生成器与保存源码 | ER的HKX3/hk2018支路具备FLVER→extern mesh与项目覆盖层保存 | 未安装/运行编辑器、未导入本机地图；特殊hit material不随生成保留 |
| 从Data2定点读取一个MSB | MSBE73头表区分MapPiece/Asset/Collision/ConnectCollision，计数与命名无一一对应保证 | 未读取整地图网格；未写回MSB |
| 尝试本地Soulstruct2.6.0完整MSB读取 | 在MSBOtherRegion非零subtype_offset处失败，保留为工具兼容问题 | 不绕过检查后导出；只按固定格式读头表 |
| `python tools/inspect_terrain_shape_types.py` | 完整支持EXE SHA通过，14/14目标RTTI类型存在，含mask/shape-key类型 | 仅磁盘类型线索，未验证活地图类型、API、偏移或真实挖洞 |
| 审本项目compositor、坐标、地形与生命链 | 普通方块矿脉可复用；独立矿区需要显式控制/显示/生命状态 | 未更改现有代码、启动游戏或传送角色 |

没有新运行时偏移、原生函数调用、地形删除、地图部署或游戏测试。游戏文件/存档没有写入，没有computer use。

## 固定来源

- EldenCraft：`ba7591cdde9fb16e17552cfe53546f44d0fd856e`，仓库外只读参考。
- fromsoftware-rs：`59fbd3b3b7daaf14aca47c9f73530493dba6bc79`，几何管理结构为研究线索。
- Smithbox：官方2.2.5发布说明；生成器源码固定`cbd477a8fd6d436b3e011c8548e1de8fd8876918`。
- SoulsFormatsNEXT：`ee1dd61958f60bdc51ce3da548e9a90a8ab39905`的MSBE参数头表/模型/Part。
- Soulstruct-Havok：`bf2d41fc83de4a43bd3ed3df8605741f143cad2c`；其旧map_collision工具不能直接作为ER导出支持的证明。
- 本机EXE2.7.1.0 SHA-256：`1a3547101327f65d0c76da2f9190ac0aa66871ea42bae2aecc61e11a8b597891`。

## 本地证据

- `runtime/experiments/mining-research/shape-types.json`、`shape-types-check.log`：可由新工具重复生成，只含类型/候选RVA元数据，不含游戏二进制。
- `runtime/experiments/mining-research/world_geom_man.rs`：外部参考摘取，不提交。
- `D:/Dev/Eldenring/voxel-assets-reference/`：本轮固定外部源文件、树索引和`msb-probe.py/json`。原始游戏资产没有另存。
- MSB：`/map/mapstudio/m60_42_36_00.msb.dcx`，仅定点读取Data2中的35,216字节；解压1,078,168字节。
- 压缩含padding SHA-256：`8c81077cb9a3f5d59a10bb7fccd582925c63a028c8b4d6a37e756dafb9857b5f`；解压SHA：`a01f6166f8fa3e57734f9ee60978c08ef503e505ed790396ceabcfc9b6ebbed5`。
- 该样本MapPiece模型/实例4/4，Collision模型9、普通实例8、ConnectCollision实例2；Asset模型/实例70/687。不据此推导实际碰撞范围或全部游戏地图结构。

## 交付判断

详细方案见[地形挖矿可行性](../terrain-mining-feasibility.md)。建议第一可玩切片为既有洞穴内的普通MC矿层，下一步选择预制原生空腔或独立MC地下区。任意实时山体切割保留研究；局部碰撞mask线索不等于视觉/物理/内壁/导航已经闭环。

本轮不运行游戏构建，因为只读工具与文档没有改Fabric/native行为。当前0.3.4制品及上一轮未部署状态保持；没有将未观察的结果写为通过。
