# EldenCraft：采集、放置与原生碰撞研究

2026-10-14。历史PR review已引用EldenCraft，见[review](reviews/20261013-actions-growth-world-debug.md)。0.3.3完成下面的固定源码与磁盘研究；0.3.4已按此实现默认关闭的[原生方块碰撞候选](native-block-collision.md)，尚未部署/实测。没有更换底座、升级MC或把对方DLL装进游戏。

后续[地形挖矿专项研究](terrain-mining-feasibility.md)增加了本机MSB、mask类型和Smithbox真实碰撞生成证据，并比较现成洞穴填矿、预制采石区、独立地下维度与任意实时切割；没有把采集掉落误算为山体开洞。

## 固定基线与本机证据

- 外部只读参考：`SocketByte/EldenCraft@ba7591cdde9fb16e17552cfe53546f44d0fd856e`，目录在本仓库外。
- 其原生侧目标 EXE 与本机相同：2.7.1.0，SHA-256 `1a3547101327f65d0c76da2f9190ac0aa66871ea42bae2aecc61e11a8b597891`。
- 新工具 `python tools/inspect_eldencraft_reference.py` 验证固定提交与本地源码没有改动，再检查 `native_colliders.rs` 的17组入口/调用点前缀，**17/17匹配**。结果保存在忽略目录 `runtime/experiments/eldencraft-compatibility.json`。
- 工具只读磁盘，不打开游戏进程、不调用这些函数。前缀匹配只是移植研究前提，不是碰撞实验通过。

## 三种机制分别是什么

| 机制 | 固定源码实际做法 | 当前 Minecraft-Ring |
| --- | --- | --- |
| 采集法环表面 | 隐藏地形单元映射MC材质，使用原版硬度、工具速度/需求、掉落表、耐久与饥饿消耗；记录耗竭，默认十分钟再生 | 当前禁止直接挖掉代理地形；攻击会向法环该位置发出原生击打，可破坏原生本来可破坏的物件。还没有这套表面材料采集循环 |
| 放置MC方块 | MC正常保存方块；根据真实碰撞形状生成AABB，合并共面的相邻盒子并限定更新范围 | 0.3.4已移入真实shape/合并/窗口与版本缓存，最多512近邻盒，输出独立MBCL快照 |
| ER原生碰撞 | 为合并盒创建Havok形状/刚体、加入世界，记录只属于本组件的对象，销毁或换场景时按身份回收 | 0.3.4已接原生构造/加入/销毁/引用与身份检查；实际碰撞层、性能及回收行为仍待验 |

采集不删除原生山体；已有原生复杂地形“挖洞”是另一项工作。普通敌人的身体、近战攻击、投射物、相机射线也不是一个碰撞过滤层，不能统称“hitbox通了”就验收全部。

## 可以直接借鉴的工程机制

1. **先合并盒子再提交。** `ColliderMerge`按轴合并相同截面的相邻AABB，消除拼接边缘并减少刚体数。台阶/半砖保留实际形状，不把所有方块当整块立方体。
2. **按窗口与修改版本缓存。** 原生只保留角色周边预算内的盒子；位置小幅改变不重新扫描整世界，方块变化或窗口变化才更新合并快照。
3. **明确原生线程和预算。** 参考实现要求在离线game task、Havok更新结束后工作；创建预算96、移除预算128，创建另有限时2ms。数值是参考，须对本项目帧时复测。
4. **只回收自己创建的对象。** 校验刚体/shape仍属于当前世界与本组件，世界切换时不解引用旧world；原实现有退役世界隔离和数量上限。
5. **过滤器需要分项验证。** 静态刚体能挡住怪物，不代表攻击/箭/法术过滤自动正确；导航网格也不会因新增墙体自动更新。

## 下一项可测试切片

先实现默认关闭的“一堵MC墙”原生碰撞实验：本项目方块形状→合并盒→版本化快照→当前世界Havok创建/更新/清理。最初只放一堵墙和一块地板，分别测玩家、普通敌人身体、箭/法术、近战及拆除后通行；切图/死亡/退出必须回收或明确隔离。被墙挡住但不会绕路应单独记录，不能误判成没碰撞。

采集则独立从两种有证据的原生表面材质开始，复用MC硬度/战利品规则并保持代理碰撞存在；未知材质不默认变成矿石。现有采样日志大量属性为0，因此需要先确认材质ID的有效性，不把任意地面映射为钻石或石头。

0.3.3交付研究边界，0.3.4已接原生墙体候选，表面采集仍未实现。下一轮先取本项目自己的实际碰撞证据；磁盘匹配、离线fixture或参考项目的演示均不能代替它。

## 来源

- [TerrainMining](https://github.com/SocketByte/EldenCraft/blob/ba7591cdde9fb16e17552cfe53546f44d0fd856e/minecraft/src/client/java/dev/eldencraft/bridge/client/TerrainMining.java)
- [ColliderMerge](https://github.com/SocketByte/EldenCraft/blob/ba7591cdde9fb16e17552cfe53546f44d0fd856e/minecraft/src/main/java/dev/eldencraft/bridge/ColliderMerge.java)
- [ColliderSnapshotCache](https://github.com/SocketByte/EldenCraft/blob/ba7591cdde9fb16e17552cfe53546f44d0fd856e/minecraft/src/main/java/dev/eldencraft/bridge/ColliderSnapshotCache.java)
- [native_colliders](https://github.com/SocketByte/EldenCraft/blob/ba7591cdde9fb16e17552cfe53546f44d0fd856e/native/src/native_colliders.rs)
- [原生版本与限制](https://github.com/SocketByte/EldenCraft/blob/ba7591cdde9fb16e17552cfe53546f44d0fd856e/native/README.md)

EldenCraft采用MIT许可；完整参考库留在仓库外。移植的Java合并/缓存与原生刚体组件已保留版权和MIT许可，见THIRD_PARTY_NOTICES及er-bridge/third_party/eldencraft/LICENSE；不提交游戏资产或对方二进制。
