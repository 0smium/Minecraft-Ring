# 2026-10-12：Core 三次受击与末影珍珠基线

状态：未执行。用户选定风车村小怪平A，尚未取得三次同招式实际 HP 与击退样本；末影珍珠也待测。站立升降平台仍失败，关卡 0 未通过，见 [Core 续测](20261012-core-recheck.md)。

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

- 上一轮最终留档：`runtime/experiments/core-11-final-native.log`、`core-11-final-minecraft.log`。
- 本轮启动：`runtime/experiments/core-launch-12-combat-baseline.log`。
- 启动快照：`runtime/experiments/core-12-startup-native.log`、`core-12-startup-minecraft.log`、`core-12-startup-status.json`。
- 后续人工操作日志：`runtime/er-bridge.log`、`bridge-base/elden-ring/mc-bridge/run/logs/latest.log`，取得反馈后另存。

日志、JAR、视频、游戏文件和存档均不提交 Git；只提交代码与实验 Markdown，不推送 upstream、不合并 main。
