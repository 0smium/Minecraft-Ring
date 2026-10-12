# 2026-10-12：Core 三次受击与末影珍珠基线

状态：部分已观察，三次可比较受击样本未完成。第十三轮自定义 status 去击退方案仍把中毒扣到死亡，语义验证失败。用户要求集中翻译真实 ER 状态，已改为读取活动 Poison、由 MC 原版效果结算；新候选已构建，尚未部署或人工验证。末影珍珠待测。站立升降平台仍失败，关卡 0 未通过，见 [Core 续测](20261012-core-recheck.md)。

## 实际制品与观察方式

- 分支 `dev/lb-poc`；只读受击日志代码提交 `f321f5eb7055bacfe5e43f5144fe6fa083a5fc17`。
- Fabric `build` 退出 0，`BUILD SUCCESSFUL in 9s`；日志 `runtime/experiments/fabric-combat-baseline-observer-build.log`。JAR `bridge-base/elden-ring/mc-bridge/build/libs/er-bridge-0.3.0.jar` SHA-256：`3CDBD6606FF20C8BB889F14112FFB22738F84B2778171BD086AAC9402F7847D5`。
- 实际第十二轮配置 A：`start.bat` / `runClient`，不加载 LB。启动 HEAD `d8238ad03a3bdd8c726eb68bd7f813249b27bf48`，工作树干净。用户回复退出后，确认第十一轮 MC 已关闭；保留 ER PID `22560`，仅重启 MC，新 MC PID `35376`。不重跑安装器。
- 沿用 exe `2.7.1.0` / 用户已确认 App `1.17.1`，MC `1.21.1` / Loader `0.19.5` / API `0.116.17+1.21.1` / Java `25.0.4.1+1`。原生制品沿用 Core 续测记录，不改原生运动、死亡或伤害逻辑。
- 服务器受击日志记录 `player.hurt` 前后实际 HP、吸收值、位置和速度；实际 HP 损失与原日志传入伤害值分开。客户端收到血量下降的 END tick 及其 +5/+20 tick 记录位置、速度，分别排队读取服务器状态。
- `hostHitCount` 是累计值，桥接一次轮询可能聚合多 hit。客户端 health-drop receipt 也不能当单次攻击计数；治疗取消后续窗口，重叠下降标记覆盖。+5/+20 相对客户端收到血量下降，而不是撞击帧；服务器 `actualTick` 是实际读取时刻。ER localXYZ 来自最新已发布帧快照，不标为精确受击瞬间。
- 改动仅 getter、快照、日志和服务器线程排队读取；没有修改 HP、击退、位置、输入或 native 偏移。编译及只读审查通过，不等于人工受击基线通过。

## 待执行操作

1. 风车村固定同一只小怪、同一个平A动作。MC 生存模式，卸下护甲和盾；法环装备不变。装备尚待用户确认。
2. `naturalRegeneration` 原值为 true（01:12:28 查询日志），01:12:32 已设为 false；实验完成后恢复 true，目前尚未恢复。
3. 每次恢复满血及原站位，等待至少 5 秒使无敌结束；只吃同一招一次，命中后约 1 秒不走跳，以观察击退。多次命中或不同动作不计为可比较样本。
4. 记录三次服务器实际 HP 前后值/损失、客户端及服务器位置窗口、ER 已发布位置和用户击退观察。数值待实测，不用转发伤害值填表。
5. 三次完成后，在安全固定地面投掷末影珍珠命中 ER 地形，记录碰撞、落点、传送和伤害，暂未执行。

| 样本 | 同一招单次命中 | 实际 HP 前→后 / 损失 | 击退与位置窗口 | 结果 |
| --- | --- | --- | --- | --- |
| 1 | 待确认 | 待测 | 待测 | 未执行 |
| 2 | 待确认 | 待测 | 待测 | 未执行 |
| 3 | 待确认 | 待测 | 待测 | 未执行 |

## 第十二轮启动证据

01:10:03 Fabric 列表 56 个模组，含 erbridge / API / Loader，没有 LiquidBounce、MCEF 或 FLK。01:10:25 自动打开 ER Bridge，01:10:29 Steve 加入，01:10:35–36 恢复合成和输入窗口。启动快照为 `live=true / player_valid=true / camera_override=true / compositing=true`；尚无本轮三次受击结果。

准备期间，回血命令实际输入的效果名为 `minecraft`；01:12:43 报找不到 `minecraft:minecraft`。本地 MC 1.21.1 的 EffectCommands 和 MobEffects 字节码确认 give 参数及 instant_health 注册，完整命令有效；改给用户可省略命名空间的 `/effect give @s instant_health 1 10`。01:13:26 出现一次 creative/invulnerable 受击，实际 HP 20→20，hurt=false，不计入生存三次样本。准备阶段日志保存为 `runtime/experiments/core-12-preparation-minecraft.log`。

## 第十二轮：中毒被当普通攻击处理

用户反馈前段为 ER 中毒，后段才是敌人攻击。01:15:21 切 survival 后，01:15:22–27 连续每秒约 7 ER HP 被转为 `Elden Ring hit ... from c3070`，实际 MC 损失约 `3.252403` 每次。首条 HP `20→16.747597`，速度 `(0,-0.0784,0)→(0.237112,0.3608,0.322146)`；对应水平约 0.4 的普通攻击击退。整份快照有 87 次 ER 7 HP 转发，其中包含创造阶段，不能全部算生存实际扣血。

源码检查确认 native 将所有 HP 下降增加 hitCount / totalDamage，完全没有写既有 totalStatusDamage；攻击者记录无效时仍取最近敌人作方向。旧日志没有输出原始 by，不能断言上述每次到底使用了真实记录还是 nearest fallback，但状态下降被错误转发为普通 hit 已由日志与用户观察支持。

后段普通受击保留原始样本，不冒充三次基线：

| 日志时间 | ER HP 下降 | MC 实际 HP 前→后 / 损失 | 限制 |
| --- | --- | --- | --- |
| 01:18:06 | 110 | 20→13.033654 / 6.966346 | 有 +20 tick 窗口，招式及输入仍待人工确认 |
| 01:18:11 | 110 | 20→13.033654 / 6.966346 | 有 +20 tick 窗口，不能单由日志认定同一动作/站位 |
| 01:18:15 | 120 | 13.033654→5.7067313 / 7.326923 | 未恢复满血且伤害不同，不计可比较样本 |
| 01:18:20 | 120 | 20→12.673077 / 7.3269234 | 随后治疗及另一次 20→18 下降打断窗口，不合并成纯击退 |

01:18:34–37 又出现多次 110 HP 攻击直至死亡，不符合每轮单次命中。当前三次表保持未完成；既有实际数据有效留档，但不宣称关卡通过。证据：`runtime/experiments/core-12-combat-first-native.log`、`core-12-combat-first-minecraft.log`、`core-12-combat-first-status.json`。

## 分类候选：第十三轮已部署，后续语义验证失败

代码提交 `27cbc68779ddd77fe03016eb22eaca5a7ba29de2`，只修既有伤害事件分流并加实际状态扣血日志：

- 沿用原先使用的攻击者字段 `0x180` 与无效句柄判据；没有增加或猜测新偏移。无攻击者记录的 HP 下降累计到 totalStatusDamage，不增加普通 hit、不借最近敌人的方向。已有 MC status 类型的 no_knockback 标签在本地 MC 1.21.1 字节码中有明确检查。
- 有效攻击者即使发布表查不到，仍保留普通攻击及原位置 fallback；既有普通攻击公式和击退不改。接管建立新 HP 基线时清掉此前 ER 控制期间的攻击者记录，避免旧记录污染首次状态下降。
- 两个通道及 MaxHP 同处既有 seqlock 写入边界；状态累计不混入下次普通攻击的 totalDamage 差分。MC 状态通道原每 20 tick 结算、按 ER HP/maxHP 比例换算，金额和频率未改；新增日志直接读实际 HP/速度前后值。
- 当前只是“无攻击者记录的伤害”，尚未读取 ER 的具体 Poison 状态/持续时间，不能宣称完成 MC Poison 图标或效果映射。直接同时添加原版 Poison 会额外扣血且套入不同结算。攻击与中毒落在同一原生采样时仍无法拆开；缺少攻击者记录的真实攻击是否存在，需要普通攻击对照验证。

Native 构建退出 0，日志 `runtime/experiments/native-status-classification-build.log`；Core DLL SHA-256 `C4480614AB586F65A944AD5C94687918C99A6A3C06BF470B87FB4D7C3B76459C`。Fabric `build` 退出 0（6 秒），日志 `runtime/experiments/fabric-status-classification-build.log`；JAR SHA-256 `9250C9F4B053150DB8261D910CF49DE8EEE87FB89F1E449F5F123EBB3F89EBF7`。该构建的检查通过；第十三轮后续人工反馈与死亡日志判定语义失败，见下文。

### 第十三轮：实际部署与启动

用户反馈已退出。MC 已关闭，ER 正在结束退出，随后再次确认 ER 进程消失才复制 DLL；未终止游戏进程。旧 Core 另保存在 `runtime/experiments/core-before-status-classification.dll`，不改原始安装备份。仅更新实际 `D:\Steam\steamapps\common\ELDEN RING\Game\erbridge\erbridge_core.dll`，源 DLL 与新运行加载副本 `Game/erbridge/loaded/core_15368_1.dll` 的 SHA-256 均与上述候选一致。

第十三轮启动 HEAD `480aa0c055d765d10804f4967a856987dc90d0c2`，工作树干净，按无 LB 的 `start.bat` 启动；ER PID `15368`、MC PID `3724`。Steam PID `34636` 在运行。实际 exe 再读仍为 `2.7.1.0`，沿用用户此前标题 App `1.17.1` 的确认，本轮未另取得 App 确认。MC 01:29:06 列表 56 mods，无 LB/MCEF/FLK；01:29:17 加入 ER Bridge。随后快照 `live=true / player_valid=true / camera_override=true / compositing=true`，已经接管合成。

`installation.json` 仍在主仓库根目录，SHA-256 `8551CE1521C33E08CBFE909CAA5BC7A92AF9369E1FAD591AA1789071CFFDF3C2`；原备份 `backups/20261011-095120` 保留，未重跑安装器。已安装 loader 未替换，SHA-256 仍 `F732F6AFADB5638795882A562CF83CB3E12D95C63432123E3B7881CF057EB96C`。

当时安排在固定地面、远离敌人，生存模式且满血，中毒生效后松键观察 10 秒。反馈指出中毒仍致死，不能继续把本方案当正确的 Poison 基线。naturalRegeneration 原值 true，实验过程设为 false，尚未恢复。

- 第十二轮最终留档：`runtime/experiments/core-12-final-native.log`、`core-12-final-minecraft.log`。
- 第十三轮启动：`runtime/experiments/core-launch-13-status-classification.log`。
- 第十三轮启动快照：`runtime/experiments/core-13-startup-native.log`、`core-13-startup-minecraft.log`、`core-13-startup-status.json`。

- 上一轮最终留档：`runtime/experiments/core-11-final-native.log`、`core-11-final-minecraft.log`。
- 本轮启动：`runtime/experiments/core-launch-12-combat-baseline.log`。
- 启动快照：`runtime/experiments/core-12-startup-native.log`、`core-12-startup-minecraft.log`、`core-12-startup-status.json`。
- 后续人工操作日志：`runtime/er-bridge.log`、`bridge-base/elden-ring/mc-bridge/run/logs/latest.log`，取得反馈后另存。

日志、JAR、视频、游戏文件和存档均不提交 Git；只提交代码与实验 Markdown，不推送 upstream、不合并 main。

## 第十三轮结果：自定义 status 中毒语义失败

用户明确指出 MC 原版 Poison 不会单独致死，而本轮已被毒死，并要求规范 ER 状态到 MC 的集中翻译。日志确认 01:33:39、01:36:40 都发生 `Steve died (erbridge.status)`；后一条服务器实际 HP `0.060095787→0`，随后经既有 LifeBridge 同步法环死亡。此前状态扣血前后位置与速度相同，支持这次没有状态攻击击退，但“无击退”不等于原版 Poison 行为。该路径不是原版效果，结果记为失败，不再对直接 status 扣血打补丁。

证据：`runtime/experiments/core-13-poison-native.log`、`core-13-poison-minecraft.log`、`core-13-poison-status.json`。本轮用户扩展了工作范围：先实现真实状态的集中映射，以 Poison 为第一条规则；不是原计划的普通三次受击通过记录。

## 集中映射候选：已构建，部署与人工结果待验证

实现提交 `580d70a0ec14899febbe28c219f64d42d30c36dc`，分支 `dev/lb-poc`，仍用配置 A / Core-only。架构和具体规则见 [host-state-mapping.md](../host-state-mapping.md)。

- 原生层只读真实 SpecialEffect 活动链，完整扫描后识别 `stateInfo=2`，发布有效性、MC 控制归属、帧、生命期及原始效果 ID；坏指针、循环、超出 256 项或签名不符发布未知。可选独立 HEFX v1 小块 `0xC00 / 32 B`，不改 GameState 的 `0x114` 布局与总协议版本。
- 当前 exe `2.7.1.0` 的目标指令、live 字节、vtable/owner 与字段关系交叉核对成功。只读快照实际观察到 ID `4004`、`stateInfo=2`、180 秒效果，剩余约 89.658 秒。身份按真实类型判定，不把 7 HP 下降或无攻击者当成 Poison。证据：`runtime/experiments/poison-research/poison-live-snapshot.json`、`effect-field-code-verification.json` 及对应反汇编文本；下载参考固定提交见架构文档，不提交这些运行文件。
- MC `HostEffectsBridge` 集中映射为原版 `MobEffects.POISON` I；短租期按 25 tick 相位续期。明确解除、F8 交还控制、读数未知/过期、断连、生命期或玩家对象变化、服务器停止时，仅撤销桥接自有效果。既有 MC Poison 不接管，更强、更长或无限外部续期会放弃所有权，不删除外部效果。
- `CombatBridge` 不再把无攻击者 HP 差額转为额外 MC 伤害，只记录；普通敌人攻击换算和真实死亡/复活同步继续沿用。原版 Poison 的持续扣血、无击退与不会单独致死由 MC 自己结算，不靠禁用死亡。

| 检查 | 结果 / 实际制品 | 日志 |
| --- | --- | --- |
| Native 构建 | 退出 0；Core DLL SHA-256 `3869C54C33F8D2B0F2D98A88D780E5E5B86FE22AD8615599ED1840DC626A92EB` | `runtime/experiments/native-host-effects-build.log` |
| Fabric 构建 | 退出 0，`BUILD SUCCESSFUL in 19s`；JAR SHA-256 `28E459A52258C315D5B536016D1A80CC281B0B77E10D718609C3ADB8029CF6F2` | `runtime/experiments/fabric-host-effects-build.log` |
| 实际 Java 协议读取探针 | 8/8，通过，退出 0；有效 Poison、有效无毒、flags=0 未知及 magic/version/odd seq/非法 Poison ID/seq=0 五种拒绝；拒绝不覆盖上次输出 | `runtime/experiments/host-effects-wire-probe.log` |
| diff / 分层审查 | 通过；探针使用自建映射文件，未接触实际游戏共享内存 | 代码与本记录 |
| 新 DLL 实际加载 / 原版效果人工验证 | 未执行；当前游戏仍运行第十三轮旧制品 | 待部署后取得 |

保留限制：未知环境伤害本轮暂不翻译；攻击与中毒同一原生采样的 HP 差额仍可能混合；两个原生状态块不是统一原子快照，极窄异步换角色边界可能保留一帧旧效果，生命期/控制/时效检查随后撤销。同等级较短 MC 外部 Poison 合并无法完整追溯所有权，Core 对照不混入其他毒来源。F8 回到 ER 控制后仍遵守 ER 的中毒与死亡规则；MC 原版 Poison 不致死的判据限定在 MC 接管时。

下一次恢复实测时，在 MC 和 ER 完整退出后仅更新 Core DLL，不重跑安装器，核对真实加载 SHA。按用户后续要求通过 `start.bat -LiquidBounce` 共载固定 LB，便于顺手验证功能；Poison/普通攻击对照期间关闭影响移动、伤害或效果的行为模块，界面/HUD 可保留，并记录实际模块与参数。先在无敌人干扰的固定地面验证活动 Poison 图标、至少 30 秒扣血至低 HP 而不自行死亡、无横推/弹起及状态解除，再在无毒条件下继续普通攻击三次对照。当前测试仍暂停，原版效果尚未在游戏中观察，不能写为通过。属性差异集中记入 [局部清单](../host-state-mapping.md#局部差异清单先记后修)，后续逐项修复。naturalRegeneration 尚待实验结束恢复 true。
