# 2026-10-14：先完成地表基础资源

用户要求先做树、花等基础资源，再探索同坐标地下。起点`7c37f962622f550e50581928b0a48a92162faa84`；实现提交 **`505a2843590886d4846e2f16ece8bb8d9533c643`**，分支`codex/b02-character-slice`，PR #6。Fabric Core/Character **0.3.5**，MC 1.21.1、JDK 25、Gradle 9.7.1；LB #3530和Skills0.19.2版本未变。没有下载新内容模组。

## 变更与配置

- 原先只有虚空世界/法环碰撞代理，没有树花生成。本批新增普通MC橡树/桦树、草花、浆果、露天石堆及少量煤，使用原版方块和掉落规则。
- ER Bridge世界默认启用，本机有效MC接管时运行；稳定网格候选、地面重复观测、完整占地与树冠净空、实体避让、已加载区块限制。几何判断不能完全识别屋顶、静止平台、水面。
- 世界SavedData保存完成收据；采掉后、切区或正常保存重进不重新生成原始候选。开关通过`/erbridge resources status|on|off`保存；关闭保留现有内容，无清空补货入口。
- 每半秒最多一处完整资源；每秒最多一个候选补采请求，FIFO最多128列。复用原地形调度器与2048射线单批/原生2ms帧预算，不增加原生协议/偏移。补采与生成分别推进游标，避免部分候选永远轮不到。
- 精细地形缓存重放现在保留普通MC方块，避免树干/花/玩家建筑被旧碰撞覆盖。
- 地下仍为设计，没有生成矿洞；没有把法环原有植被改成可砍对象。详见[内容与限制](../surface-resources.md)。

## 已执行的离线验证

| 操作 | 结果 | 证据边界 |
| --- | --- | --- |
| Core-only、普通Character、B01 Gradle build | 通过 | 构建，不是加载游戏 |
| 最终Core与两种Character制品边界/元数据/可选依赖类链接 | 通过 | 未初始化Fabric运行时、LB GUI或真实世界 |
| `test_surface_resource_plan.py` | 规划器69,865条属性断言与生产游标回归通过 | 稳定候选、负坐标、布局、叶连通；17/20候选调度、时间预算不消费未处理项 |
| `test_surface_resource_state.py` | 76项通过 | 真实SavedData与二进制NBT往返、收据/开关/畸形格式、采样稳定与失效、8192观察上限 |
| `test_surface_resource_blocks.py` | 646项通过 | 真实MC块状态/形状，内存世界的净空、植物生存、树苗回种/骨粉目标与叶衰败资格；未执行实际长树/掉落 |
| `test_terrain_prefetch.py` | 通过 | 生产补采方法：完整5×5、请求去重、FIFO淘汰、移动支撑、共用射线预算 |
| `test_terrain_passages.py` / `test_terrain_regeneration.py` | 通过 | 保留普通方块、原生unknown不删碰撞、保存形状回归；移除新保护的临时副本可复现失败 |
| `git diff --check` | 通过 | 文本检查 |

## 制品与本地日志

最终Core JAR SHA-256：`a59805d6381059ce2d1416a26f28a28a9fe034eb130db650d472a9958f68d3ec`。

B01 Character JAR：`26796017eb8d1d72a1b8ca7c48c455ccb47d4c5e529863ab0ff9fcd31d565ce2`；普通Character JAR：`feed9a757473242e33d3e22387be09da44dba91d6baa3961bc56a7c421a9651e`。

候选制品与包含实际提交的摘要保存在`runtime/experiments/surface-resources-artifacts/{b01,character}/`及`manifest.json`，不提交Git。仍配套上一批0.3.4原生候选：Core DLL `8e2412e1da5d058ebc7833670fdba9a9feba63050d772b0ec578ae4dfa699e3b`、proxy `d1143717b828c0ce829cf56b9853c99b339c0885213f8dfd1725ddbae5ffc1cf`，本轮未改DLL。

日志均在本仓库本地：

- `runtime/experiments/surface-resources-{build,core-build,character-build}.log`
- `runtime/experiments/surface-resources-{b01,core,character}-boundaries.log`
- `runtime/experiments/surface-resource-{plan,state,blocks}-check/`（编译和检查输出）
- `runtime/experiments/surface-resources-terrain-prefetch.log`；其余旧地形工具的临时harness位于`build/`。

## 实机状态与下一轮

**本轮未部署、未启动游戏、未修改实际世界/存档/LB配置，没有使用computer use。** 实际安装仍为0.3.2，Core DLL SHA `2d214812773bc4606507e6a51a365e70bd2044b29701a54ae229eaf09c72c3ef`；installation.json SHA `8551ce1521c33e08cbfe909caa5bc7a92af9369e1fad591aa1789071cffdf3c2`保持。

待用户集中验证：普通野外生成位置/合成画面、砍树与木镐采石制作、花草/浆果采收、叶子衰败和树苗长成、正常保存重进不补刷，以及水边/坡地/门口误生成。当前不能把这些观察写成通过。地表基础内容先完成这轮闭环，地下仍后置。
