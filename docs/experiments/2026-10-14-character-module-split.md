# 2026-10-14：按 review 拆出独立角色模组

## 提交、范围与结论

- 分支：`codex/b02-character-slice`，继续 draft PR #6，base 仍为 PR #5。
- Review 基线：`5401039fc61cceb08d65b245b7a964a4219e9662`。
- 本轮代码：`7040c0e2e862355a90f4c24e1b762600b85edc20`。
- 版本：MC 1.21.1 / Loader 0.19.5 / Fabric API 0.116.17+1.21.1 / Loom 1.18.2 / JDK 25.0.4.1+1 / Gradle 9.7.1；Fabric Core 与 Character 为 0.3.1。
- 法环目标继续为 App 1.17.1 / exe 2.7.1.0；本轮没有启动游戏重新核对标题。
- **构建和离线检查通过，未部署、未启动 ER/MC、未新增实机通过项。**

角色 UI、原版 Horse 管理、效果映射/租期、成长账本与命令、LB 能力守卫已移入真实 `character-bridge` 子项目。独立 Fabric mod ID、入口、Mixin 和 JAR；Core 通过公开运动主体与生命周期事件提供服务，不再调用角色实现。HINV/HPRG/HEFX 采样/传输/校验留在 Core，协议与存档路径未改。

Skills API 实现及数据只在 `-Pb01=true` 编译。Core-only 不配置角色项目，普通 Character 不要求 Skills/LB/Meteor。LB/Meteor 的实体兼容仍是 Core 中按存在与固定版本门控的可选适配，未引入编译依赖；成长守卫归角色模组。

迁移中确认并修复两处集成问题：开发 `runClient` 必须显式带角色源集输出；世界就绪及网络断线必须通过有顺序的公共事件。Fabric 的 Netty 断开通知现在交回相应游戏线程，迟到的旧客户端通知不清除新连接状态。

## 操作与证据

所有 Gradle 调用使用仓库固定 JDK/Gradle 和 `.tools/gradle-home`，禁止 configuration cache；没有执行 `runClient` 或生产客户端任务。

| 操作 | 结果 | 本地日志 / 证据 |
| --- | --- | --- |
| Core `clean build verifyLaunchProfile` | 通过，无角色/Skills 编译依赖 | `runtime/experiments/character-split-core-build.log` |
| `-Pcharacter=true build verifyLaunchProfile` | 通过，两个真实 JAR；初次预检跨项目锁错误已修为子项目各自解析 | `runtime/experiments/character-split-character-build.log` |
| 固定 LB + `-Pb01=true build verifyLaunchProfile` | 通过，包含 Skills 适配/数据 | `runtime/experiments/character-split-b01-build.log` |
| `Launch.ps1 -B01 -CheckOnly` | 最终代码构建、依赖解析、开发角色 classpath 核对通过 | `runtime/experiments/module-split-launch-b01.log` |
| `Launch.ps1 -Character -CheckOnly` | 通过，无 Skills 角色组合 | `runtime/experiments/module-split-launch-character.log` |
| `Launch.ps1 -Character -Meteor -CheckOnly` | 通过，独立 Meteor 配置；不是游戏启动 | `runtime/experiments/module-split-launch-meteor-character.log` |
| 实际暂移 LB/Skills/Meteor 三个原始 JAR，先 clean 再 Core-only / Character 预检 | 两组重新编译通过；finally 恢复，三文件 SHA 相同 | `runtime/experiments/module-split-no-optional-{clean,core,character}.log`、`module-split-without-optional-jars.json` |
| `test_character_split.py`，含 Core-only / 普通角色 / B01 | 实际 JAR 类归属、无重复类、版本/元数据/Mixin、排除可选模组的入口类型解析通过 | `runtime/experiments/module-split-{b01,character}-boundary.log`、`module-split-no-optional-{core,character}-boundary.log` |
| `test_bridge_lifecycle.py` | Core/Character 两种注册先后各 30 项通过；两个线程/迟到断线负对照抓住错误 | `runtime/experiments/bridge-lifecycle-check/` |
| 成长领域、配置、payload、Skills 真实数据 | 16 组领域与新 JVM 恢复通过；真实 Skills 解析/消费/退款/NBT、payload 通过 | 构建的 `b01PreparationCheck` 输出、`runtime/experiments/module-split-skills.log`、`module-split-b01-payload.log` |
| LB 真实注入及 Value、Core+角色两份配置共享包 | 14 处能力注入、Kotlin range/Value 与 helper 解析通过 | `runtime/experiments/lb-capability-check/`、`split-shared-package-check/shared-package.log` |
| Meteor 真实注入 | 8 处通过 | `runtime/experiments/meteor-adapter-check/` |
| 既有状态租期/原版 merge/accessor、坐骑、宿主归属与地形检查 | 通过；坐骑回收 32 项、归属 39 项；均非游戏会话 | `runtime/experiments/mount-{vanilla,lifecycle}-check/`、`module-split-terrain-{passages,clearance}.log`；状态/归属另见本轮工具输出 |

类型解析探针不执行 Fabric 模组初始化，不打开世界，也不验证 GUI、F8、技能购买或马的真实 tick。生命周期检查使用真实 Fabric Event 实现及生产注册代码，实体/网络执行器是显式测试替身。不能把这些结果改写为实机通过。

## 制品与安装状态

本地快照在 `runtime/experiments/module-split-artifacts/{b01,character}/`。两种角色制品同名、同 mod ID，只选择对应 profile 的一个；构建会重新生成它们。

| 制品 | SHA-256 |
| --- | --- |
| Core `er-bridge-0.3.1.jar` | `11581c7fa60ddf63e0522261dced57779bb3fd4aa2dd451ce6f0f5ae6f5c0e87` |
| 普通 `er-character-bridge-0.3.1.jar` | `ff359c1aacd30f3fea57930cfd713c8ba87fe24c2553ef8d1677e2708ee87073` |
| B01 `er-character-bridge-0.3.1.jar` | `67abb2622cc8843d87c59036d3679d3f33979d20c95a057bc980e7fb79d96fc0` |
| 既有候选 `dist/erbridge_core.dll`，本轮未改/未重建 | `99FDB70E9BB6D11414FC8CDDA79054392C3EB3F6283B55F9F1ED596951FEC140` |
| 既有候选 `dist/dinput8.dll` | `52B86C85711B954EE05A4FB24687C97C9AB22DBBA4AB7531333B6A41518B2FF2` |
| 实际 Game/erbridge 安装的旧 Core DLL，本轮未替换 | `D6BE349A579747ED8B7979A6D993471493A0155A0F83E6A8DC4E7ACC5887BDF1` |
| `installation.json`，本轮未改 | `8551CE1521C33E08CBFE909CAA5BC7A92AF9369E1FAD591AA1789071CFFDF3C2` |

LB 仍固定 v0.22.0 / #3530，Skills 0.19.2，Meteor 0.5.8；未改下载制品，未将日志/JAR/DLL/存档提交到 Git。

## 后续与未测

本轮落实 review 的模组边界、可选依赖及预检问题。独立模组首次真实加载仍保留在集中测试；卸除客户端后账本兼容也不能仅凭路径保留就写为实机通过。

接下来在角色模组推进原版容器装备页及一件真实装备的装配/卸下确认；沿现有策略泛化能力描述、设置映射及睡眠主动行为限制；验证 ER 骑乘许可后再接正常马哨。三者当前均未实现完成，没有猜偏移或改写原版马物理。

B01 真实升级/购买/退款、状态组合、背包、马及 Meteor 仍集中验收。下一次实际启动前先部署配套 DLL，再准备新鲜存档备份及已有测试卢恩工具；不要求用户刷卢恩。安装和启动组合见 [角色模组说明](../character-modules.md)。
