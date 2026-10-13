# Meteor 0.5.8 可选适配候选

目标固定为 MC 1.21.1 / Meteor 0.5.8，参考源码提交 `37c5c5c640ba9c66721e824c62e84bf1223c9cd6`。这轮已加入桥接适配代码、独立启动配置及离线回归；尚未完成官方 JAR 的实际预检、Gradle 构建、Mixin 注入或游戏共载。当前环境访问官方 Maven JAR 返回 HTTP 403，不能将合成测试夹具当成真实 Meteor。

## 已加入的入口

| 项目 | 候选行为 | 限制 |
| --- | --- | --- |
| 可选版本门控 | 无 Meteor 时跳过；只给 exact `0.5.8` 应用本组 Mixin | 其他版本记录并跳过，不猜测其签名；启动工具拒绝错误版本的候选 JAR |
| KillAura | 在现有筛选前排除断连、死亡、未知状态及非敌对 ER 代理 | 保留原模块距离、墙体、类型等过滤，不自动选择代理类型或开启模块 |
| Nametags | 通用生物标签显示宿主名、真实 ER 当前 / 最大 HP，隐藏代理的虚拟吸收血量 | 无宿主数据时显示未知；实际渲染与注入位置待游戏验证 |
| Health 排序 | ER 用生命比例 ×20，普通 MC 生物保持原始 HP | 这是一条明确的混合排序规则，不是改写实体生命值；未知 ER 排到最高 HP 端，由 Aura 筛选排除 |
| LB 共用辅助层 | 共用 `HostEntityView` 检查宿主连接、生命数据和敌对性 | 保留 LB 自己的 hostile / invisible 策略；不移植整套客户端 |
| 独立启动配置 | `-Meteor` 使用 `productionMeteorClient`、`run-meteor` 和原始 Meteor 发布 JAR | 与 `-LiquidBounce` 互斥；不自动复制原来的存档和配置 |
| JAR 预检 | 元数据、固定方法描述符、常量池引用和本地 SHA-256 锁定 | 结构检查不能替代 Mixin 审计；首次本地摘要不是官方独立可信校验值 |

Meteor 的 Box ESP 可直接利用代理已有的 AABB；Shader 外轮廓依赖实体模型，而代理没有正常 ER 模型，所以本轮不承诺 Shader 描边。Nametags 修补的是 `renderGenericLivingNametag`；不会把真实 ER 模型植入 MC 渲染管线。

目标方法的 intermediary 描述符以 [Yarn 1.21.1 固定映射](https://github.com/FabricMC/yarn/tree/33ac4315404792c41a162869b19161f475b89bfb/mappings/net/minecraft/entity)核对。`getHealth` / `getMaxHealth` / `getAbsorptionAmount` 分别对应 `method_6032` / `method_6063` / `method_6067`，EntityType 名称为 `method_5897`。最终发布 JAR 仍需通过真实预检和游戏启动确认。

## 准备与启动

先完成 [Windows 构建与安装](installation.md)，升级本轮配套 native DLL。仓库根目录执行：

```powershell
python tools/prepare_meteor.py
.\Launch.ps1 -Meteor
```

默认下载地址是官方 Maven 的 0.5.8 发布坐标。如果下载失败，可使用手动从官方渠道取得的同版本发布 JAR：

```powershell
python tools/prepare_meteor.py --jar 'D:\Downloads\meteor-client-0.5.8.jar'
.\Launch.ps1 -Meteor
```

准备工具验证版本和适配入口，将 JAR 放在忽略目录 `runtime/downloads/`，生成同目录 `.lock.json`。首次摘要记录用于后续检测文件变动，不证明初始来源可信；如有独立可信摘要，传 `--expected-sha256`。已锁定文件发生变化时拒绝替换，只有显式 `--replace` 才重新准备。启动脚本和 Gradle 都复核摘要。

`run-meteor` 有独立 options、日志、存档及 Meteor 设置。首次进入会创建自己的桥接世界，不自动读取 `run/saves`；需要复用既有世界时，关闭两端后手动复制并保留备份。`-Meteor` 与 `-LiquidBounce` 不能同时指定；Core 不带上述开关。Baritone 作为独立依赖另行评估，本轮没有打包它。

冻伤一次伤害默认关闭，需要专门混合受击测试时可加：

```powershell
.\Launch.ps1 -Meteor -ExperimentalFrostBurst
```

这不是 Meteor 必需参数；含义见 [状态映射](host-state-mapping.md)。

## 第一轮实机验收

1. 冷启动与 F8：确认可选 Mixin 成功注入、两端正常接管，日志无 missing target / injection failure；再启动 Core 对照。
2. ESP / Nametags：先用 Box，按界面选择 `erbridge:er_entity` 类型；分别观察敌人、友方、死亡代理、远近目标及断连状态，核对宿主 HP 与标签。
3. KillAura：显式选择代理类型；命名实体过滤可能排除所有带宿主名的代理，按测试需要调整。先单目标、短距离、无穿墙，验证敌人可选，友方、死者、断连代理不可选。
4. Health 排序：同时放一个普通 MC 生物和两个不同最大 HP 的 ER 代理，检查约定的比例排序，确认没有修改实体 HP 或实际攻击量。
5. 返回原 LB 配置进行同一目标基线，确认共用 `HostEntityView` 没有放开隐藏 / 非敌对目标。

上述清单全部尚待实机执行。这里也没有实现“按技能树动态限制 Meteor 的全部模块”；具体拆分和需要 fork 的边界见 [能力成长策略](ability-progression-strategy.md)。

## 自动化检查

```powershell
python tools/test_meteor_preparation.py
python tools/test_meteor_preparation.py --meteor-jar runtime/downloads/meteor-client-0.5.8.jar
```

第一条已通过：编译真实适配处理函数与最小平台桩；检查版本门控、目标拒绝、标签 HP、排序、普通 MC 保留行为和 LB 共用逻辑；合成 JAR 验证结构预检的成功与拒绝路径。第二条需要真实官方 JAR，当前未执行。两者都不是实际 Mixin 注入或游戏渲染测试。

## 固定参考

- [Meteor KillAura](https://github.com/MeteorDevelopment/meteor-client/blob/37c5c5c640ba9c66721e824c62e84bf1223c9cd6/src/main/java/meteordevelopment/meteorclient/systems/modules/combat/KillAura.java)
- [Meteor Nametags](https://github.com/MeteorDevelopment/meteor-client/blob/37c5c5c640ba9c66721e824c62e84bf1223c9cd6/src/main/java/meteordevelopment/meteorclient/systems/modules/render/Nametags.java)
- [Meteor SortPriority](https://github.com/MeteorDevelopment/meteor-client/blob/37c5c5c640ba9c66721e824c62e84bf1223c9cd6/src/main/java/meteordevelopment/meteorclient/utils/entity/SortPriority.java)
