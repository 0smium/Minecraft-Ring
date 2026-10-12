# 法环状态到 Minecraft 的集中映射

本轮先统一中毒这一条路径。宿主状态是输入，Minecraft 的原版效果是行为实现；宿主掉血差额是观测值，不是效果身份。此前无攻击者掉血被自定义 status 直接扣血，虽然不再击退，却仍能致死，不能叫原版 Poison。

## 职责与数据

| 层 | 职责 | 入口 |
| --- | --- | --- |
| ER 后端 | 在已验证的目标版本读取真实活动效果，完整读链失败即未知；不实施 MC 效果规则 | `host_effects.h`，由 `game.cpp` 游戏线程调用 |
| 协议 | 传递有效性、控制归属、采样帧、生命期及原始效果 ID | 可选 HEFX 小块，offset `0xC00`，version 1，32 字节 |
| MC 适配 | 核对输入有效、连接与生命期一致，在服务器线程添加或清理自己拥有的原版效果 | `HostEffectsBridge` |
| MC 原版 | 结算 Poison 的持续扣血、图标、粒子和无攻击击退行为 | `MobEffects.POISON` |

普通攻击继续由 `CombatBridge` 换算、由 MC 伤害机制处理护甲、盾、无敌和击退。真正死亡/复活继续经 `LifeBridge` 同步。本轮不重写上述两条路径。

当前 ER 后端只支持本机已核对的 exe `2.7.1.0`。`ChrIns + 0x178` 的 SpecialEffect 活动链、entry 的 param / ID / next 以及 param 的 `stateInfo + 0x156`，已用目标程序的原版效果查询指令和只读运行快照交叉核对；实际观察到 ID `4004`、`stateInfo=2`。启动时再次核对目标指令签名，读取时核对 vtable、owner 及完整链；任何不符发布未知，不猜测新版本布局。布局参考固定于 [fromsoftware-rs 的 SpEffect 定义](https://github.com/vswarte/fromsoftware-rs/blob/59fbd3b3b7daaf14aca47c9f73530493dba6bc79/crates/eldenring/src/cs/sp_effect.rs)，类型参考固定于 [EldenRingHKS](https://github.com/ividyon/EldenRingHKS/blob/8710f97408dd57bf1d6ef919f619cb2937919c39/c9997.hks)。参考资料本身不能替代目标版本验证。

## 第一条映射规则

| 明确的宿主输入 | MC 行为 |
| --- | --- |
| 完整读取有效，真实 Poison 活动，处于 MC 接管 | 添加原版 Poison I；不是额外调用 `hurt` |
| Poison 解除、生命期更换、控制交还或数据失效 | 撤销桥接自己安装的效果 |
| 无攻击者 HP 下降，但原因未识别 | 记录原始损失，不猜成 Poison，也不另扣 MC HP |

原版 Poison 不会单独致死。当前 MC 1.21.1 在 HP 大于 1 时每次扣 1；原本存在小数 HP 时最终可能低于 1，而不是精确停在 1。Poison I 周期 25 tick。这里采用 MC 的规则，不照搬 ER 每秒掉多少血，因此 ER HP 差额不能再重复结算。

桥接采用短租期，不保存永久毒：初始 100 tick，剩余不超过 25 时增加 75 tick；续期保持 25 tick 的相位。有效宿主活动状态维持租期，明确解除会立即清理。不能每 tick 重设固定时长，否则会破坏原版 Poison 的扣血周期。外部 MC 已有 Poison 不接管；原版同类型效果会合并，同等级外部续期不能完全追溯所有权，本轮 Core 对照不混入其他 Poison 来源。

## 扩展约束

后续属性或效果逐项加入同一适配入口，并先确定真实宿主身份、MC 对应行为、开始/结束条件和数据失效处理。不因图标相似或掉血大小匹配就套用状态；猩红腐败、火焰、出血等目前未实现。

未知环境伤害暂不翻译是本轮明确限制，不宣称已覆盖法环所有伤害。攻击和中毒发生在同一原生采样时，攻击 HP 差额仍可能混合，普通攻击基线需要无状态干扰。新读取布局必须在实际 exe 验证，并有可选能力检查；失败不使用旧偏移、也不把读取失败写成无毒。

实现、构建及人工结果见 [受击与状态实验记录](experiments/20261012-core-combat-baseline.md)。
