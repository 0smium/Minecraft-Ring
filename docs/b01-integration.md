# B01 游戏接入候选

开发分支 `feat/b01-preparation`，继续 [PR #3](https://github.com/0smium/Minecraft-Ring/pull/3)。
目标是让**真实法环升级 → 技能点 → 技能树购买 → 固定 LB 的 Speed/Aura 执行**形成最小闭环。
本轮已写入实际入口，完成离线检查；后续已开始 [R09 修复与首次共载](experiments/2026-10-13-r09-b01-smoke.md)，**成长闭环尚待人工验收**。
原准备内核及规则见 [b01-preparation.md](b01-preparation.md)。

2026-10-14 澄清：Speed 复用 LB Custom.Strafe，三档数值是可调范围的上限，升级不会把用户值固定为该上限。角色功能与通用策略要迁入独立 Fabric 模组，当前尚未迁移；当前架构和交付条件见 [architecture.md](architecture.md)，最新集成候选见 [current-development.md](current-development.md)。

## 这一轮负责什么

- 原生只读等级/名称采样；MC 集成服务器显式绑定角色、保存最高等级额度。
- Pufferfish's Skills 管理购买、前置、退款与已学节点；Bridge 只写累计来源 `erbridge:er_level`，不维护第二份消费余额。
- 服务端将能力等级/范围发送到对应玩家；客户端限制固定 LB 的设置与执行，并在加载、断线、过期、撤权时停止能力。
- 先覆盖 Speed/Aura 三档，不扩展 Meteor、状态全翻译、移动平台或全屏输入修复。其他 LB 模块仍是自由实验范围。

## 固定依赖和启动

| 项目 | 固定值 |
| --- | --- |
| ER | App 1.17.1 / exe 2.7.1.0；真实等级 164 已人工核对，持续升级消费链仍待验证 |
| MC / Fabric | 1.21.1，保持当前工程版本 |
| LB | v0.22.0 / Build #3530，原始 JAR，不内嵌 |
| LB SHA-256 | `1824011D91046F8F8B388CFA63782F38CDF754F12CB997317EAB8E69E67EF20D` |
| Skills | Pufferfish's Skills 0.19.2，Modrinth 版本 `ppOVyisF`，Fabric / MC 1.21 |
| Skills SHA-256 | `276CE0B5F15BCFEAFE632C8EABE0D2D3104B9B0362897E66FD049BCD217BA915` |

构建前在仓库根执行 `python tools/prepare_b01_skills.py`。下载结果仅留在被忽略的 `runtime/downloads/`。
Gradle 校验该 JAR 的 SHA-256，用于可选框架适配代码的编译；普通 Core/LB 启动不加载它。
完整候选构建用标准 Gradle `build`，仍使用 JDK 25 / Gradle 9.7.1。

需要人工验收时，按原项目离线流程部署候选 DLL、保持 Steam 运行，再执行：

```powershell
.\Launch.ps1 -B01
```

`-B01` 包含固定 LB 和 Skills；`-LiquidBounce` 仍是原来的 LB 实验配置。
首次离线交付没有启动或部署；后续部署、备份、启动阻塞与修复结果记录在上述共载记录中。

LB 重置配置后，关闭 `HUD → Blur`（其不透明后处理会把桥接背景盖黑）和 `HUD → In-built → Minimap → Enabled`（避免无用的后台扫描）。
Blur 的单开关对照已由用户确认恢复法环画面；保持其他行为模块关闭，再开始技能授权验收。

## 角色、世界和点数

原生新增独立 `HPRG v1` 块（`0xD00`，`0x58` 字节）。最初 B01 制品的 HEFX 为 v1；当前叠加状态映射分支使用 HEFX v2，HPRG 地址和格式保持不变。
等级和 UTF-16 名称字段经当前 exe 的磁盘签名、调用点和 RTTI 核对；运行时再次验证对象链、范围、字符串和重复读取一致性。
未知/加载态清空等级和身份载荷。Java 校验 seqlock、版本、生命周期、活跃心跳及采样新鲜度。

没有把姓名、指针、进程 ID 或未知字段当成可靠存档 ID。
`/erbridge progression bind` 为当前 MC 玩家分配并保存角色 UUID，姓名弱指纹仅用来拒绝明显切换。
**本轮一个 MC 玩家在一个 MC 世界只绑定一个 ER 角色；同名重建无法自动识别，换角色请使用新的 MC 世界。改名后也会锁定，不自动迁移旧点数。**

绑定与配置写入世界 `data/erbridge/`，等级额度在 `levels/<campaign>/<player>/<character>.properties`。
仅本机集成服务器的登录拥有者读取本机 ER；访客不会收到这个角色的点数或授权。
当前候选另已限制本机 ER 的受击、死亡与召回只作用于主人，并在 ER 重启/失联时重建事件基线；见[单宿主归属记录](experiments/2026-10-13-host-test-readiness.md)。来宾攻击代理、共享世界规则、远程网关与双 ER 联机仍未完成，不能据此宣称完整 LAN 支持。

首次有效等级作为起点，默认初始点数 0、此后每升一级 1 点。
最高确认等级只增不减；回档、断线和重复采样不会重复发点。
先持久化额度再向框架设置累计来源；框架对账失败时重试，包括同序号采样。
损坏/未知版本记录不能当作新角色重建。文件采用单服务器线程写入、同目录原子替换。

## 技能与执行限制

| 技能 | 三档上限 | 执行方式 |
| --- | --- | --- |
| Speed | 0.30 / 0.33 / 0.36 blocks/tick | LB `Custom.Strafe` 原实现，仅地面普通移动；关 BHop 自动跳、额外水平/垂直修改，Timer=1 |
| Aura | 2.5 / 3.0 / 3.5 格 | LB KillAura 单目标；禁穿墙、额外扫描/自动格挡/FailSwing/FightBot，按武器冷却攻击 |

各档 1 点，必须按顺序购买。没有额外经验、兑换点数或重复添加移动属性的奖励。
Skills 原生 **K** 键打开技能树，也可执行 `/erbridge progression skills`。

默认是**自由模式**。绑定后执行 `/erbridge progression challenge` 才让 Speed/Aura 接受技能授权。
`/erbridge progression free` 退出限制。进出挑战模式会先正常停用这两项；用户再自行开启需要的能力。
加载/失联时暂时停用，恢复连接后不自动替用户重新开启。

固定 LB 的 Value/Choice、激活和运行入口都有约束；GUI、配置导入与脚本的正常设置路径走相同规则。
内部字段写入也由执行读取守卫兜底；退款通过正常模块停用执行清理。
现有 LB 滑块元数据没有改成动态范围，**有效上限与锁定状态在 Bridge HUD 和 status 命令显示**，写入/运行按该范围执行。
这只是固定客户端适配，不是防止任意篡改客户端的服务端反作弊，也没有实现服务端移动和攻击速率的全面校验。

## 管理与验证

- `/erbridge progression status`：实际等级、点数、角色 UUID、能力范围和配置文件路径。
- `/erbridge progression refund`：通过框架退还本树所有已购节点，随后撤销能力。
- `/erbridge progression reload`：校验并原子保存新策略版本后切换；失败保留旧策略。禁止替换 campaign。
- 世界 `data/erbridge/b01.properties`：模式、默认发点曲线、三档 Speed/Aura 上限等。Speed 单位是 blocks/tick，不是倍率；已有角色保留原计分曲线。

离线检查入口：

```powershell
python tools/test_b01_preparation.py
python tools/test_b01_files.py
python tools/test_host_progression.py
```

另有实际 Skills JAR 的解析/扣点/退款/NBT 检查和 MC 网络编码检查，运行方法见对应 `tools/test_b01_*.py`。
真实 LB Mixin 转换/Value 路径检查使用 `python tools/test_lb_capability_adapter.py`（先构建候选并保留本地依赖缓存）。
这些检查不等同于双游戏共载。最终实机验收只围绕这条闭环：核对真实等级、绑定、升一级只发一次点、买两项技能并检查上限、退款停用、重连不重复发点。
真实升级消费链仍待集中测试。用户后续要求继续开发，因此已并行推进不依赖这条验收结果的 [B02 只读背包、MC 马与调试归属候选](b02-character-slice.md)；这不将 B01 的未测项目改成通过，也不恢复移动平台和全屏输入的反复试改。

测试升级缺卢恩时，使用[本地测试卢恩入口](test-runes.md)先备份、再补测试货币，仍在赐福正常升级；不用刷取资源，也不通过直接改等级或模拟 MC 点数替代该验证。
