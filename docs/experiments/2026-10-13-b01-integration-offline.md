# B01 真实接线候选：Windows 离线交付

日期：2026-10-13（America/New_York）。分支：`feat/b01-preparation`，延续 PR #3。
准备内核基线：`bb09e360b99982a39ddedeff46c4f6f81c8c6ec6`。
本轮实现提交：`0630f6872e14053f2a505a065927c8829c540f5a`。
操作与使用边界见 [B01 接入说明](../b01-integration.md)。

## 范围和结果

已接通原生 HPRG 采样、服务端角色绑定/累计点数、真实 Skills 节点购买适配、S2C 能力策略以及固定 LB Speed/Aura 限制。
**状态：构建和离线检查通过；游戏内验收待人工操作。** 没有启动 MC/ER，没有把候选 DLL 部署到游戏目录，也没有使用 computer-use。
没有把模拟等级或离线 JAR 探针写成真实升级、GUI 或技能效果通过。

## 版本与产物

| 项目 | 实际值 |
| --- | --- |
| Windows 工作目录 | `D:\Dev\Eldenring\Minecraft-Ring` |
| ER 目录 | `D:\Steam\steamapps\common\ELDEN RING\Game` |
| ER App / exe | App 1.17.1 为此前用户标题确认；exe 2.7.1.0，本轮仅核对磁盘文件 |
| exe SHA-256 | `1A3547101327F65D0C76DA2F9190AC0AA66871EA42BAE2AECC61E11A8B597891` |
| 构建工具 | LLVM-MinGW（仓库本地工具）、JDK 25.0.4.1+1、Gradle 9.7.1、Loom 1.18.2 |
| MC / Loader / API | 1.21.1 / 0.19.5 / 0.116.17+1.21.1 |
| LB | v0.22.0 / Build #3530；参考源码 `e5a0dbf5458b063d3028e69e04762b8b25b998b5`，位于主仓库外 |
| LB 原 JAR SHA-256 | `1824011D91046F8F8B388CFA63782F38CDF754F12CB997317EAB8E69E67EF20D` |
| Skills | 0.19.2 / MC 1.21 Fabric，Modrinth `ppOVyisF` |
| Skills JAR SHA-256 | `276CE0B5F15BCFEAFE632C8EABE0D2D3104B9B0362897E66FD049BCD217BA915` |
| 候选 Core SHA-256 | `207603EB9F830BBB2B6C65868D1F0BBB70040841FF1FB5A29AFE43967B3FF604` |
| 候选 Fabric SHA-256 | `DEBB67D1F0B6E8BEA1C864D7D663535BD2F2FDF68D8F9F7252739E7351E6F80F` |

候选在 `dist/erbridge_core.dll` 和 `bridge-base/elden-ring/mc-bridge/build/libs/er-bridge-0.3.0.jar`。
实际安装 DLL 仍为 `D33C8B851996894D052E39571754A215FE720E9D51BA633AEF0B8CE20D7DE323`。
`installation.json` SHA-256 仍为 `8551CE1521C33E08CBFE909CAA5BC7A92AF9369E1FAD591AA1789071CFFDF3C2`。
原安装备份、LB 配置重置备份与游戏存档均保留；这些文件、下载 JAR、产物和日志不提交 Git。

## 配置与实现选择

- 独立 HPRG v1 位于 `0xD00`，大小 `0x58`，HEFX v1 不变。新等级字段依据当前目标 exe 磁盘签名/调用点/RTTI 核对，运行时仍需首次观察确认。
- 名称只作弱一致性检查；持久 UUID 由 MC 服务端显式绑定生成。一个 MC 玩家/世界只支持一个 ER 角色；同名重建无法自动识别。
- 首次等级基线、初始 0 点、之后每级 1 点。真实框架管理消费，Bridge 只对账累计 `erbridge:er_level`。
- 默认自由模式；挑战需显式命令。Speed 使用真实 LB Custom.Strafe，三档上限 `.30/.33/.36` blocks/tick；Aura 为 `2.5/3.0/3.5` 格，每节点 1 点。
- 未改成自研同名移动算法。未做任意客户端的服务端反作弊；旧多人战斗/生命归属问题仍未验收。
- LB 原滑块仍是静态范围；实际约束与锁定状态由 Bridge HUD/status 展示。模块生命周期代码已接入，但实际 enable/disable 与画面效果需实机确认。

## 已执行的检查

日志路径均相对仓库根，保存在本地 `runtime/experiments/`。

| 操作 | 结果和证据范围 | 日志 |
| --- | --- | --- |
| `python tools/build_native.py` | 通过，产生候选 DLL；未部署 | `native-b01-progression-build.log` |
| `python tools/test_host_progression.py` | 通过，实际 C++ reader/publisher 与生产 Java decoder 互测；签名/对象链、UTF-16、等级范围、seqlock、加载清空、HEFX 未覆盖 | `native-progression-test.log` |
| `python tools/test_b01_preparation.py`，以及 Gradle 内同一入口 | 通过，16 组真实内核场景与独立 JVM 恢复；不是真实玩家连接 | `fabric-b01-final-build.log` |
| `python tools/test_b01_files.py` | 通过，Windows 原子写/失败清理、Unicode 绑定、旧绑定保护、配置/版本、40 次保存与第二 JVM 恢复 | `b01-files-test.log` |
| `tools/test_b01_skills.py` | 通过，实际 Skills JAR 解析 6 节点；正常前置/余额/扣点/拒重复、累计来源、退款、NBT 恢复 | `b01-skills-probe.log` |
| `tools/test_b01_payload.py` | 通过，真实生产 CODEC 7 个正常往返与 31 个拒绝案例；不替代玩家认证和网络实测 | `b01-payload-probe.log` |
| `python tools/test_lb_capability_adapter.py` / 原版 LB3530 + 实际 MixinTransformer | 通过，7 类共 14 个实际注入点、helper 类加载；没有实例化真实 ModuleSpeed/Aura | `lb-capability-check/OfflineMixinProbe.log` |
| 转换后真实 LB Value 执行 | 通过，普通/inner setter、restore、Gson 导入、读取钳制、降限、旧 revision、锁定/自由模式 | `lb-capability-check/RuntimeValueProbe.log` |
| Gradle `build -PlbProduction=true -PlbJar=... -Pb01=true --offline` | 完整构建通过；没有调用 runClient / productionLbClient | `fabric-b01-final-build.log` |
| PowerShell parser / `git diff --check` | 通过 | 终端输出 |

首次完整 Gradle 构建在 `test` 阶段失败：准备内核采用 standalone main 断言，Gradle 9 默认要求发现 JUnit 测试。
已将真实双 JVM 检查接到 `check`，仅对该 JUnit discovery 设置关闭“无测试即失败”；重新完整构建通过，没有跳过实际内核检查。
复核还修正了异常技能余额和超预算存储不能生成非法同步包的问题。

## 下一轮人工步骤（尚未执行）

1. 候选部署并用 `Launch.ps1 -B01` 启动后，确认标题版本、正常进入和 HUD；`status` 的等级应与法环角色菜单一致。
2. `/erbridge progression bind`，检查原有高等级作为基线且 0 点；升一级后只增加 1 点。退出重连后不重发。
3. K 打开技能树，正常消费节点；开启挑战模式后，分别检查锁定项、Speed/Aura 实际执行和有效上限。需要更多点时正常升级，不在测试脚本伪造法环等级。
4. 退款后确认正常停用；配置降限/reload 后实际值不能超过新上限。加载/断线时能力暂停，恢复后不擅自重新开启。

真实等级读取、三模组共载/技能树 GUI、模块运行与停用清理、热重载、重连闭环均保持“待验证”。本轮不恢复平台与全屏切换的测试循环。
