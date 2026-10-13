# PR #4：状态映射与 Meteor 的 Windows 集成

2026-10-13。用户要求继续开发其他 PR，之后集中测试；本轮没有启动游戏、部署 DLL、修改存档或发放卢恩。推进 [PR #4](https://github.com/0smium/Minecraft-Ring/pull/4)，保持草稿，以 `feat/b01-preparation` 为基分支。

## 版本与改动

- PR #4 原提交 `1b33eeadf5bf29adb7b8258cb3e9d05feca85f66`；合入已实测的 B01 分支 `faddbab50b9f1eb4a691cab764c9322ae081e660`，合并提交 `5ead307`。
- 本轮代码 / 检查工具提交：`4652374c84c8ff9c549eddae3ca222c0a39d741c`。分支 `feat/effects-meteor-prep`；未合并 main，未向 upstream 推送。
- 沿用之前已确认的 App 1.17.1 / exe 2.7.1.0；本轮游戏关闭，没有重新核对标题。MC 1.21.1、Python 3.13.6、JDK 25.0.4.1+1、Gradle 9.7.1、项目内 LLVM-MinGW。
- HEFX v2 保留既有 Poison，并增加腐败 / 冻伤 / 睡眠 / 发狂身份与映射；冻伤一次伤害仍默认关闭。合并保留 HPRG 原地址 / 布局与 R09 启动失败拒绝逻辑。
- Meteor 适配共用 `HostEntityView`，覆盖 KillAura 无效 / 非敌对代理拒绝、Nametags 真实 ER 名称 / HP，以及约定的生命比例排序。此处没有实现 Meteor 的技能授权。
- 启动器保持 LB / B01 流程，Meteor 使用独立 `run-meteor`；PowerShell 与 Gradle 均拒绝 Meteor+B01 混用。新增 `-CheckOnly`，可检查 profile 与制品而不启动游戏或改配置；它不验证 DLL 部署或画面。

## 真实 Meteor 制品

从[官方 Maven](https://maven.meteordev.org/releases/meteordevelopment/meteor-client/0.5.8/meteor-client-0.5.8.jar)取得 0.5.8 发布 JAR，4,542,953 字节；计算摘要与[同坐标官方 SHA-256](https://maven.meteordev.org/releases/meteordevelopment/meteor-client/0.5.8/meteor-client-0.5.8.jar.sha256)一致：

`090ad8f02273a67eca753ef37ccee2256e4dd3d715501ed75433ec8f57d7c5e9`

此前默认 Python User-Agent 请求返回 403；加上标识下载工具的 User-Agent 后正常下载。准备脚本默认固定官方摘要，错误摘要拒绝进入本地制品锁。制品及清单位于忽略目录 `runtime/downloads/meteor-client-0.5.8.jar` / `.lock.json`。

JAR 元数据明确支持 MC 1.21 / 1.21.1，`meteor-client:commit` 为空。参考源码标签 `v0.5.8` 指向 `37c5c5c640ba9c66721e824c62e84bf1223c9cd6`；这不等于 JAR 内含构建提交证明。未使用第三方存档、最新版或自行重编译的 Meteor 替代发布版。

## 自动检查

全部在游戏关闭时执行。真实类转换不初始化模块，平台桩回归不运行 MC 引擎。

| 检查 | 结果与证据 | 日志（相对仓库，均不提交） |
| --- | --- | --- |
| `python tools/build_native.py` | 通过；Windows loader / core 均构建 | `runtime/experiments/pr4-native-build.log` |
| Gradle `build`，`-PmeteorProduction=true`，提供固定 JAR / 摘要 | 通过；真实 main / client 编译、重映射与既有 B01 检查 | `runtime/experiments/pr4-fabric-build.log` |
| Gradle `build`，`-PlbProduction=true -Pb01=true` | 通过；B01 profile 保留，16 组领域回归及新 JVM 还原通过 | `runtime/experiments/pr4-b01-build.log` |
| `python tools/test_host_effects.py` | 通过；真实 C++ 读写 → Java v2 / v1 解码，状态租期、叠加、隔离、清理，冻伤开关两种模式 | `runtime/experiments/pr4-host-effects-first.log` |
| `python tools/test_host_progression.py` | 通过；HPRG 读链与跨语言布局，发布不覆盖 HEFX v2 | `runtime/experiments/pr4-host-progression.log` |
| `python tools/test_incoming_hit_startup.py --regression-ref 376d148` | 通过；实际失败路径在接管前退出，同一断言拒绝旧代码 | `runtime/experiments/pr4-incoming-startup.log` |
| `python tools/prepare_meteor.py` | 官方下载与摘要通过 | `runtime/experiments/pr4-meteor-artifact.log` |
| `python tools/test_meteor_preparation.py --meteor-jar runtime/downloads/meteor-client-0.5.8.jar` | 通过；真实适配函数配平台桩、结构拒绝测试，以及官方 JAR 结构预检 | `runtime/experiments/pr4-meteor-preflight.log` |
| `python tools/test_meteor_adapter.py` | 通过；实际 Mixin / MixinExtras 对官方 JAR 注入 8 处：KillAura 1、Nametags 5、SortPriority 2；共享类可链接 | `runtime/experiments/pr4-meteor-transform.log`，详细文件在 `runtime/experiments/meteor-adapter-check/` |
| `python tools/test_lb_capability_adapter.py` | 通过；14 处实际 B01 注入、Kotlin 公共 range 工厂与实际 Value 读写 / 还原回归 | `runtime/experiments/pr4-lb-capability.log` |
| Windows PowerShell 5.1 `Launch.ps1 -CheckOnly`，分别 Core / B01 / Meteor | 三种 profile 检查通过；B01 同时验证固定 LB / Skills 摘要；没有调用游戏启动 | `runtime/experiments/pr4-launch-{core,b01,meteor}-check.log` |
| 同时指定 Meteor / B01 | PowerShell 与 Gradle 均按预期拒绝 | `runtime/experiments/pr4-launch-mixed-rejected.log`、`runtime/experiments/pr4-gradle-mixed-rejected.log` |

构建时指定 `.tools/java/jdk-25.0.4.1+1` 为 `JAVA_HOME`，`.tools/gradle-home` 为 `GRADLE_USER_HOME`，使用项目内 Gradle、`--no-daemon --no-configuration-cache --offline`。Meteor 构建参数示例：

```powershell
.\.tools\gradle\gradle-9.7.1\bin\gradle.bat -p bridge-base/elden-ring/mc-bridge --no-daemon --no-configuration-cache --offline -PmeteorProduction=true "-PmeteorJar=$PWD/runtime/downloads/meteor-client-0.5.8.jar" -PmeteorSha256=090ad8f02273a67eca753ef37ccee2256e4dd3d715501ed75433ec8f57d7c5e9 build
```

## 构建产物与部署状态

| 文件 | SHA-256 |
| --- | --- |
| `dist/erbridge_core.dll`（候选） | `51415B6068BFF4D0A60A815EE9E423155AC2858AD62B2E2F1703C19331D8A504` |
| `dist/dinput8.dll`（候选） | `BDCA79F11F17452E9C7AD30E8FF644F5D6A017E15AA52F6B39F7BC16C60C1B3C` |
| `bridge-base/elden-ring/mc-bridge/build/libs/er-bridge-0.3.0.jar`（候选） | `58A871F39133CBEB62FBB6DDC61772D20F3755BAF7ECE7DFFD39EEC431F74BFB` |
| `D:/Steam/steamapps/common/ELDEN RING/Game/erbridge/erbridge_core.dll`（仍为上一轮安装版） | `D6BE349A579747ED8B7979A6D993471493A0155A0F83E6A8DC4E7ACC5887BDF1` |
| `installation.json`（保留） | `8551CE1521C33E08CBFE909CAA5BC7A92AF9369E1FAD591AA1789071CFFDF3C2` |

候选 DLL 尚未复制到游戏。首次部署 HEFX v2 时同时使用配套 Java；旧 Java 不支持 v2。原始安装备份与本地存档保持；本轮不重跑 Install.ps1。

## 下一次集中实测

1. **先准备 B01 升级资源。** 备份当前法环存档及 MC 世界；按赐福实际升级费用准备足够测试卢恩，不再要求用户刷取。仍在赐福正常升级，以真实等级变化验证发点；不能直接改等级或给技能点来替代这条验证。本轮尚未实现 / 执行卢恩发放。
2. 一次更新配套 DLL / JAR，先用 `-B01` 验证真实升级 → 一次发点 → 购买 Speed / Aura → 生效 → 退款 / 撤销 → 重连不重复发点。此前实测只确认绑定、0 点锁定、重载及恢复 free；未完成的部分不标通过。LB HUD Blur 保持关闭。
3. 在同一安装版上验证新增状态的真实活动 ID、MC 图标、伤害 / 减速和清理。冻伤爆发先保持关闭；只有拿到同帧归因证据才决定后续规则。没有触发条件的种类保留未测，不为凑齐清单无限调试。
4. 退出 LB 客户端后，以独立 `-Meteor` profile 检查共载 / F8，再测 Box、Nametags、KillAura 及 HP 排序。保留最终画面录屏；不将这一轮当作 Meteor 成长授权验证。

本轮结果：Windows 构建和离线集成通过；新增状态、Meteor 实机共载、升级消费链仍待人工测试。没有新增实机“通过”结论。
