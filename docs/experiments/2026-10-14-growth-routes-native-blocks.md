# 2026-10-14：Speed分支与EldenCraft原生方块碰撞

按用户“先继续开发、后续一并验证；技能策略推进一些，重心放EldenCraft”的要求，从 `codex/b02-character-slice@66bb87b9877b732f0a11b94282c488790735e89e` 开始。本文件所在提交是最终源码记录，继续更新draft PR [#6](https://github.com/0smium/Minecraft-Ring/pull/6)，未合并main或推送upstream。

**本轮只开发、构建、磁盘只读核查和离线fixture；没有启动/部署ER或Minecraft，没有computer use，没有修改实际世界/角色/LB配置。** 前两轮0.3.2实机和0.3.3候选记录保留，不把本轮通过覆盖成实机通过。

## 实现结果

### 成长第一条真实分支

- 原29节点ID、成本、前置与账本version2保留。新增 `speed_hop_1`，成本6，前置 `speed_1`，独立授权 `speed_hop`；现14个LB模块、15授权条目、30节点。
- 已学分支合成同一模块的合法模式/参数；地面Custom仍按地面等级授权，连跳开放原LB LegitHop，二者不叠加速度。仅学会且选中顶层LegitHop才允许原自动跳跃路径。
- 模式切换走原Choice生命周期；退款正在使用的连跳会停Speed并回到Custom，不自动重新开启。OnlyInCombat等隐藏路径不能借此获得连跳。
- 基础76点、广修Speed/Aura双精通116点不变；全部含连跳为238点。新增 `speed_ground` 8点、`speed_hop` 10点、`speed_routes` 14点，现共8套预设。
- 真实9点且已学Speed II的旧数据仍消费8余1，不发新点、不迁移/重写真实存档。Fly精确/Vanilla及YPort继续研究，未硬接新飞行执行层。

详见[成长规则](../growth-v1.md)与[路线说明](../growth-directions.md)。

### 默认关闭的原生建筑碰撞

固定外部参考 `SocketByte/EldenCraft@ba7591cdde9fb16e17552cfe53546f44d0fd856e`，保留MIT许可。移植到本项目现有Core，不加载另一套底座。

- 集成服务器本机owner、ER Bridge世界与当前CoordMap门控；loaded-only真实VoxelShape、分区/修改缓存、相邻格和动态shape刷新。窗口最多48×32×48，每tick32768格扫描预算，合并后最多最近512盒。未完成扫描/缺失依赖输出incomplete，不保留旧障碍。
- 半砖、台阶、含水固体保留实际形状；排除terrain代理、空气和纯流体。轴合并不封住真实微缝，坐标转换包含region与Z翻转。
- MBCL v1：请求0x400000（0x60头+512×24），状态0x410000（0x78）。宿主/MC PID、启动时间、generation、life、zone、worldEpoch和750ms新鲜度均检查；当前MC还必须保持自己登记的pid/start，旧MC不能借用新MC身份发包或清空流。
- 当前EXE完整SHA、29处代码/调度数据签名、8个RTTI及实际离线状态验证。原生factory/cinfo/create/add/destroy/ref链，确认自己的BodyID/shape后回收；生命周期改变清理或隔离旧world，未知引用不交给worker线程析构。
- 每回调创建最多96、移除128，创建限时2ms。使用当前角色可站立静态地面材质/过滤模板；找不到则报告阻塞，不猜碰撞层。自有体的宽相位状态与待处理数写回诊断。
- 自有盒遮挡射线返回unknown；粗地形保留原列，精细与通行清理放弃未知批次，平台/通道不把unknown当作地面或空气。拆除完成后恢复采样，避免方块被再次生成成terrain。
- `-NativeBlockCollision`双端显式启用；普通启动默认关闭。实验需整次ER重启，启用时proxy拒Core热重载。F8、暂停、退出导致新鲜度/控制失效后清理；不承诺F8后原生墙仍持续存在。

参考使用113，本项目使用117。目标EXE固定六槽调度表中99(Havok post)与117同lane0且99在前；post等待线程池、回收graph并清`HavokMan+0xA8`。本项目保留117，并额外要求该graph指针为0才允许增删。静态证据摘要在 `runtime/experiments/collider-research/post-physics-evidence.json`；未上传完整反汇编，也未以此冒充活游戏线程验收。

边界、开关、诊断和下一轮步骤见[原生建筑碰撞](../native-block-collision.md)。表面材料采集、挖洞、导航网格更新尚未实现。

## 验证与日志

以下路径均相对本机仓库，`runtime/`忽略且不提交。

| 操作 | 结果与证据范围 | 日志 |
| --- | --- | --- |
| `python tools/build_native.py` | 最终Windows DLL构建通过 | `runtime/experiments/native-block-build.log` |
| 普通Character / Core-only `build verifyLaunchProfile`，最后 `Launch.ps1 -B01 -NativeBlockCollision -CheckOnly` | 三组合0.3.4构建与依赖通过，最终恢复B01制品，无游戏启动/配置修改 | `native-block-character-build.log`、`native-block-core-build.log`、`native-block-b01-preflight.log`（均在runtime/experiments） |
| `test_character_split.py` 三profile | 实际制品类归属、元数据、Mixin配置、可选依赖排除后的类型解析通过 | `runtime/experiments/native-block-{character,core,b01}-boundary.log` |
| `test_growth_catalog.py` / `test_b01_skills.py` / `test_b01_payload.py` | 57项catalog/账本；真实Skills30节点分支购买/前置/退款、9点兼容、8预设/NBT/clear；实际policy codec与27坏包通过 | `runtime/experiments/growth-routes-{catalog,skills,payload}-check.log` |
| `test_lb_capability_adapter.py` | 固定正式LB15处注入、24GUI投影、14模式/退款检查；使用实际ChoiceConfigurable与明确模块副作用替身 | `runtime/experiments/growth-routes-lb-check.log`、`lb-capability-check/` |
| `test_placed_block_colliders.py` | 35项真实MC shape/合并/缓存/预算，以及生产CoordMap非零region、Z翻转、缩放、wire bounds通过 | `runtime/experiments/placed-block-colliders-check/check.log` |
| `test_block_collider_mixin.py` | 从生产JAR的remapped注解读取真实MC目标；setBlockState的4个返回点均注入revision hook，handler含1次blockChanged调用 | `runtime/experiments/block-collider-mixin-check/transform.log` |
| `test_block_collider_wire.py` | 23项Java写入/登记/状态/未知射线；Java生成wire由生产C++解析，身份/超时/几何/完整空与incomplete/seqlock拒收通过 | `runtime/experiments/native-block-wire-check.log` |
| `test_native_colliders.py` | 实际EXE29段/8RTTI；生产C++驱动私有回调模拟创建/加入/删除/引用、替代对象、延迟销毁、预算、换world、offline/thread/graph gate与失败收尾；真实BCrypt文件门控通过 | `runtime/experiments/collider-research/native-colliders-check.log` |
| `test_terrain_passages.py` / `test_stable_terrain.py` | 生产refinement/travel接到unknown时保留原生几何；既有门洞/墙/楼梯/保存形状回归通过 | `runtime/experiments/native-block-terrain-passages.log`、`native-block-stable-terrain.log` |
| `generate_growth_content.py --check` | 实际30节点/8预设和SVG生成一致 | 命令输出 |

没有调用活游戏Havok函数，MC world由内存fixture代替，LB未初始化真实模块单例/浏览器。原生对象“complete”仅指候选宽相位注册收敛，不能代表各碰撞层实测通过。

交叉审查修复了旧MC能读取新登记并借其身份发包的缺口；其余已审接线、原生引用/回收与unknown传播未发现阻塞。阶段名字差异已进一步查实际调度并加graph门控。

## 版本、制品与未部署状态

目标EXE2.7.1.0，SHA-256 `1a3547101327f65d0c76da2f9190ac0aa66871ea42bae2aecc61e11a8b597891`。Core/Character0.3.4，固定LB v0.22.0/#3530、Skills0.19.2；Python/LLVM-MinGW/JDK25/Gradle9.7.1沿用，没有更新外部依赖。本轮没有观察标题App版本，因为游戏未启动。

| 候选制品 | SHA-256 |
| --- | --- |
| `dist/erbridge_core.dll` | `8e2412e1da5d058ebc7833670fdba9a9feba63050d772b0ec578ae4dfa699e3b` |
| `dist/dinput8.dll` | `d1143717b828c0ce829cf56b9853c99b339c0885213f8dfd1725ddbae5ffc1cf` |
| Core0.3.4 | `5324e35048bba36e001120a6a43643df7e886cb86a1bfea4488406ed5047d46a` |
| B01 Character0.3.4 | `3d1334fa9be9a33104dc2231acc9a583698ddbc23ec7c3bb618ec1b2d8c8b46d` |
| 普通Character0.3.4 | `da4a806e922d3751a8c66d15ff0e26aa7a13997000a5fa03f616c26963430548` |

普通Character归档在 `runtime/experiments/native-block-artifacts/character/`，最终build/libs为B01组合。实际 `Game/erbridge/erbridge_core.dll` 仍为0.3.2的 `2d214812773bc4606507e6a51a365e70bd2044b29701a54ae229eaf09c72c3ef`；`installation.json`仍为 `8551ce1521c33e08cbfe909caa5bc7a92af9369e1fad591aa1789071cffdf3c2`。未重跑安装器、未复制DLL、未改存档，实际配置仍自由模式revision6。

下一轮先完成前批装备/菜单/UI缺口，再用三套Speed预设对照；原生墙体通过独立开关验证desired/owned、普通敌人、投射物/近战、拆除和生命周期。用户负责实际操作，未看到的结果继续记未测。
