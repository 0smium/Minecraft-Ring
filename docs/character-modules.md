# Core 与独立角色模组

2026-10-14，PR #6 集成候选。Fabric配套版本为0.3.4；保留完整库存HINV v3与菜单HINT v1，新增默认关闭的方块碰撞MBCL v1；需同步更新候选DLL。
实际拆成两个制品；0.3.2 B01+LB加载和重进已由用户实测，0.3.3本批仅完成构建与离线类解析。

| 制品 | 保留内容 | 依赖 |
| --- | --- | --- |
| `er-bridge-0.3.4.jar` / `erbridge` | 合成、输入、地形、实体代理、生命/普通受击、宿主传输校验、可选原生方块碰撞、公共API | MC/Fabric；无角色或能力端依赖 |
| `er-character-bridge-0.3.4.jar` / `er_character_bridge` | E→ER装备页、原版Horse管理、状态规则、14模块/30节点含连跳分支、LB守卫 | 精确配套Core 0.3.4；默认无Skills依赖 |
| 同一角色模组的 B01 构建 | 以上内容加 Skills 0.19.2 API 适配和技能树数据 | 编译时要求固定 Skills JAR；使用技能树时加载它 |

角色工程是 `bridge-base/elden-ring/mc-bridge/character-bridge`，具有自己的 Gradle 构建、Fabric 元数据、main/client 入口及 Mixin 配置。默认 Core 构建不配置这个子工程；`-Pcharacter=true` 或 `-Pb01=true` 才加入。没有把角色 JAR 嵌入 Core。

两种角色构建使用相同 mod ID 和文件名，不能同时安装；每次预检按 profile 重建并记录 SHA-256。缺少 Skills 或安装了不支持的版本时，角色功能仍在，Skills facade 报不可用，挑战模式不授予能力。普通角色构建即使发现 Skills，也不会假装含有其适配器。

## 构建和启动组合

以下命令从仓库根运行；先按 [installation](installation.md) 设置 JDK 25 和 Gradle 缓存。

```powershell
# 仅底座
.\bridge-base\elden-ring\mc-bridge\gradlew.bat -p bridge-base\elden-ring\mc-bridge --no-configuration-cache build
# 底座和角色，无 Skills/LB/Meteor
.\bridge-base\elden-ring\mc-bridge\gradlew.bat -p bridge-base\elden-ring\mc-bridge --no-configuration-cache -Pcharacter=true build
# 成长候选；提前准备固定 Skills
.\bridge-base\elden-ring\mc-bridge\gradlew.bat -p bridge-base\elden-ring\mc-bridge --no-configuration-cache -Pb01=true build
```

| 启动参数 | 组合 |
| --- | --- |
| 无 | Core-only |
| `-Character` | Core + Character |
| `-LiquidBounce` | Core + 固定 LB，含实体/显示兼容 |
| `-Character -LiquidBounce` | 加角色规则、背包及坐骑；不含 Skills 适配 |
| `-B01` | Core + Character B01 + Skills + 固定 LB |
| `-Meteor` | Core + 固定 Meteor，仍是未实测候选 |
| `-Character -Meteor` | 加角色规则；Meteor 成长授权仍未实现 |

所有参数可传给 `Launch.ps1` 或 `start.bat`。加 `-CheckOnly` 会构建所选制品、解析所需运行依赖，**不会启动 ER/MC**，也不会改游戏配置。正常启动同样先完成这个预检，再启动 ER。预检不检查已安装 DLL 是否与候选一致，也不宣称游戏加载已通过。

LB/Meteor 互斥，B01 隐含 LB。冻伤实验参数要求 Character。原 `run` / `run-meteor` 目录保留，启动配置不会复制或重置世界、LB 设置和存档。

## 公共服务与兼容

- `MovementSubjects.body(Player)` 默认返回玩家；角色模组注册真实受管理 Horse 的提供者。Core 从返回实体取得脚底、AABB、步高和落地状态，不认识 Horse 管理实现。
- `BridgeLifecycle` 提供世界就绪、地形前 tick、普通受击后/生命处理前 tick、召回前、控制交回和断连事件。角色规则订阅事件；加载先后不影响成长初始化或回收顺序。
- Fabric 的网络断线通知可能来自 Netty。Core 将回调交给服务器/客户端线程执行，并保留原玩家身份；迟到的旧客户端断线不清除新连接的策略。
- HINV/HPRG/HEFX 的读取、版本与身份校验留在 Core。仅角色规则迁出，不复制原生地址、采样器或另一套库存。
- 现有包名、命令、网络 payload ID、马标签、`erbridge:abilities` 及世界 `data/erbridge/` 路径保留。替换旧单 JAR 时移除旧版本，放入配套两个制品；启动器从源码生成对应组合。
- 原版受击、死亡、马运动、Skills 消费和 LB Speed/Aura 算法未重写。移除 Character 会关闭这些角色用例；有尚待回收的测试马时应先装回 Character 完成归还。

Gradle 项目依赖采用 Fabric 推荐的 `namedElements` 和客户端源集输出，见 [Loom 文档](https://docs.fabricmc.net/develop/loom/)。开发启动还显式加入角色输出到实际 classpath；生产 LB/Meteor 启动显式加载两个 remapped JAR。

## 验收边界

离线检查覆盖实际制品的类归属/重复类/元数据、排除可选模组后的入口类型解析、已有成长/状态/坐骑回归及公共事件顺序。详细命令、摘要与日志见 [本轮记录](experiments/2026-10-14-character-module-split.md)。

0.3.2 B01+LB加载、真实等级点数、Speed购买/退款/执行、预设恢复和生存Fly许可已[实测通过](experiments/2026-10-14-growth-equipment-live.md)。0.3.3三个构建组合与离线边界检查通过，详见[本批记录](experiments/2026-10-14-post-validation-fixes.md)；Core-only、普通Character和Meteor的首次实机仍待验。四防具真实装卸/保存以及本批界面和菜单交接尚未实测；睡眠禁行动与ER骑乘准入继续待开发。
