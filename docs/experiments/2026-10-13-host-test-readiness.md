# 单宿主归属与测试卢恩：下一批实测准备

2026-10-13。用户要求继续按 PR 开发，后面集中实测。基于 PR #4 `5b34d91bd4490e6c2945af532fe93428f88e6206`，分支 `codex/b01-host-test-readiness`。当前 PR 无新增在线 review 评论；依据规划 PR #2 的 B01 玩家归属验收与上轮缺测试卢恩的实际阻塞推进。

代码提交：

- `a7e59faa50b26582b38727379dc67c1e04357b2a`：本机宿主受击、死亡、召回隔离与注册后 JOIN 接线。
- `1224757da3b8777b11bed992bd299f6d782dee7e`：显式游戏线程测试卢恩命令、备份工具、去重与回归。

PR #4 同期的外部隐藏药水保留修复独立见[效果记录](2026-10-13-effect-hidden-chain.md)。本轮没有启动游戏、查询活跃游戏 mailbox、发放卢恩或部署候选 DLL。

## 玩家归属

`LocalHostOwner` 使用集成服务器单人 profile 的 UUID 找当前玩家实例，不退回到玩家列表第一项。`CombatBridge` / `LifeBridge` 将本地 ER 受击、死亡只交给该玩家；来宾死亡不杀本地 ER。首次读到、宿主重启、失联、主人缺席或玩家实例替换时只建立累计事件基线，避免重放旧伤害或把计数归零当新死亡。合法的 `hostLife` 变化伴随死亡仍会传递。

召回、跨区域、加入与重生传送同样限定到主人；只有来宾在线时，不消费等待中的召回。地形采样仍可服务共享 MC 场景中的其他玩家。

交叉复核发现 Networking JOIN 触发时 MC 尚未把玩家写入 UUID 表，会被新的严格身份检查拒绝。已改为固定 Fabric API 的 `ServerPlayerEvents.JOIN`，在 `placeNewPlayer` 返回时执行；保留身份检查。实际固定版本为 Fabric API `0.116.17+1.21.1`，entity-events `1.8.0+2b27e0a419`，MC 1.21.1 字节码顺序已核对。

这只完成本机生命与召回的归属限制。来宾攻击代理、共享坐标/世界/gamerule、专用服网关与双 ER 会话仍未解决；没有真实 LAN 通过结论。

## 卢恩入口与本地备份

调用说明见[测试卢恩工具](../test-runes.md)。命令默认查询，显式金额才发放，单次最多 500 万；不改等级或技能点。原生游戏线程调用当前 EXE 的增减函数，保留累计卢恩处理，校验会话、生命期、角色、余额、签名与有效期。查询不会驱逐未过期的发放记录；缓存满拒绝新发放，超时不自动重试。

固定 EXE 2.7.1.0 SHA-256：`1A3547101327F65D0C76DA2F9190AC0AA66871EA42BAE2AECC61E11A8B597891`；沿用此前人工确认 App 1.17.1。增减函数 RVA `0x25E0E0` 的 149 字节 SHA-256 为 `c04206377d14de26edf27bc6b27d32a90c1c2f311336bc2b046f71768566aa94`，owner 调用点和上限常量也逐字节核对。

本机存在两个存档账号，工具默认拒绝盲选。按 Windows 当前 Steam `ActiveProcess.ActiveUser` 匹配到唯一存档目录后，已在确认 ER 关闭时运行 `--backup-only --save-file <匹配路径>`，主存档与 `.bak` 备份成功。未覆盖或修改源存档。

- 备份 manifest：`runtime/backups/test-runes-20261013-113230-f6cc28/manifest.json`。
- 备份命令结果：`runtime/experiments/test-runes-backup-result.json`。
- 当前账号选择仅保留本地：`runtime/experiments/test-runes-active-save.json`；账号路径与存档不入 Git。
- 下次启动前检查备份新鲜度；若超过 24 小时，趁 ER 仍关闭重新生成。实际发放、菜单余额和真实升级仍未验证。

## 检查与构建

| 检查 | 结果 | 日志（均忽略） |
| --- | --- | --- |
| `python tools/test_local_host_routing.py --fabric-events --regression-ref b8615e1` | 通过 39 条断言；真实 Combat/Life 类及真实 Terrain 函数配平台桩；原广播与旧 JOIN 注册分别被同一断言拒绝；固定 Fabric / MC 调用顺序核对通过 | `runtime/experiments/b01-local-host-routing-join.log` |
| `python tools/test_test_runes.py` | 通过；版本/身份/金额/余额/过期/查询/重放拒绝、16 槽容量和回收、备份损坏/游戏启动拒绝；旧 A 发放后查询 B、余额退回再重放 A 不重复调用 | `runtime/experiments/test-runes-offline-20261013.log` |
| 原生函数算术与累计值 | 将已校验函数拷贝到私有测试缓冲区，仅重定位其上限常量，执行边界检查；不在 ER 进程运行 | 同上 |
| `python tools/test_incoming_hit_startup.py --regression-ref 376d148` | 通过；R09 失败时在测试货币等可选入口之前拒绝初始化，原错误版本仍失败 | `runtime/experiments/b01-host-readiness-r09.log` |
| `python tools/build_native.py` | Windows native 构建通过 | `runtime/experiments/b01-host-readiness-native-build.log` |
| Gradle `build`，`-PlbProduction=true -Pb01=true`，固定 LB JAR | 通过；JDK 25.0.4.1+1 / Gradle 9.7.1，16 组 B01 内核检查及独立 JVM 恢复通过 | `runtime/experiments/b01-host-readiness-fabric-build.log` |
| LB、Meteor、效果 Accessor 离线注入 | 最终生产 JAR 上分别通过 LB 14 处、Meteor 8 处及实际 MC 效果 Accessor；没有启动模块或游戏 | `runtime/experiments/b01-host-readiness-lb-mixins.log`、`b01-host-readiness-meteor-mixins.log`、`b01-host-readiness-effect-mixin.log` |

构建使用项目内 LLVM-MinGW、Python 3.13.6；Gradle 设定项目 `.tools/gradle-home`，使用 `--no-daemon --no-configuration-cache --offline`。当前工具依赖 `psutil 7.1.3` 已有，无需安装 CE 或额外游戏插件。

| 制品 | SHA-256 |
| --- | --- |
| 候选 `dist/erbridge_core.dll` | `E9A235AB99959B588D711B62EE0A1C3088C8768917C54C01AFD127B38D62F9B2` |
| 候选 `dist/dinput8.dll` | `C659F399DB1ACA197A19363247A9BC77D808AFFB3C969FA710E281B1BF42B925` |
| 候选 `build/libs/er-bridge-0.3.0.jar` | `24E55B5F27110D8BE094EFF462A1CF071F512EBBA159F26DCCAD21D4D9A23B82` |
| 游戏仍安装的 Core | `D6BE349A579747ED8B7979A6D993471493A0155A0F83E6A8DC4E7ACC5887BDF1` |
| 保留的 `installation.json` | `8551CE1521C33E08CBFE909CAA5BC7A92AF9369E1FAD591AA1789071CFFDF3C2` |

下一批配套部署后：确认安全接管与初次入场 → 查询/补测试卢恩并核对菜单 → 正常升级、发点、购买、退款、重连 → 状态效果与 Meteor 项按已有清单集中观察。LAN 归属另需真实来宾，只完成单人时不标 LAN 通过。没有重跑安装器，没有将 DLL、JAR、存档或日志提交 Git。
