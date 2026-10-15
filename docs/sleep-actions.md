# 睡眠与主动操作

0.3.6 候选实现；属于 Character 扩展，不依赖 B01 挑战模式。此页记录代码约束与离线核查范围，**尚未经过本轮游戏实测**。

## 状态来源和边界

- 服务端根据已认证的宿主睡眠状态发布 `er_character_bridge:action_lock_v1`，包含本机玩家 UUID、会话 epoch、递增 sequence 和 sleeping。变化即发，正常每 10 tick 刷新。
- 客户端校验当前连接、当前玩家和 UUID，拒绝旧 epoch/sequence；不从 IPC、药水图标或成长策略另行推断睡眠。
- 3 秒没有运行期心跳则释放客户端限制。单机菜单暂停的时间不计入超时，避免 K/ESC 菜单停留后绕过睡眠。
- F8／宿主菜单交接释放客户端限制，但保留同连接的序号水位。断连再清空会话。服务端仍拥有角色状态和动作授权。

## 睡着时的行为

禁止主动移动加速、跳跃、攻击、使用物品／方块／实体、开始或继续挖掘。正在使用的物品直接停止，避免通过“释放使用”把已拉满的弓射出去；已开始挖掘则中止。

输入在本地玩家读取键盘／LB 修改之后清理，并在原版自动跳跃处理之后、物理移动之前再次清理。服务端和公共实体动作入口还有独立验证。没有每 tick 清空 `deltaMovement`、锁定绝对位置或取消整个物理 tick，因此受击、击退、碰撞推挤和重力仍由原来的物理流程执行。睡眠期间撤销当前 `flying`，不会在醒来时强行恢复旧飞行状态。

Core 写入代理攻击标记之前也检查动作授权，Character 再取消真正的攻击入口；因此不依赖两个 Mixin 的 HEAD 回调谁先执行。

镜头、物品栏／菜单、ClickGUI、F8 和退出操作仍可用。

## 固定 LiquidBounce #3530

- 在原 `ClientModule.getRunning` 执行条件上增加睡眠限制，保留已有 `Enabled` 和参数配置；不会把全部成长模块写成关闭，也不依赖“已学习能力”的 14 项清单。
- 抑制 Combat、Movement、Player、World、Exploit、Misc、Fun；Render 和 Client 保留，因此 HUD、ESP、ClickGUI 仍运行。睡着时尝试新开启行为模块会被拒绝。
- `EventManager` 的执行检查也处理覆盖 `getRunning` 的监听器，以及原本声明 `ignoreNotRunning` 的行为事件。保留固定版本的生命周期与 GameTick 清理：后者包括 `tickHandler` 的序列停用／重建和 Velocity 暂停计数，保证醒来后仍开启的合法模块能继续运行。
- 原 `SequenceManager` 在睡眠时取消行为模块的延迟序列，防止 Aura 已开始等待的攻击继续执行。Aura 最终攻击判定另有直接检查。
- 该版本的 `EventScheduler` 原本不检查 `running`，因此在它真正执行回调的 `process$lambda$4` 入口丢弃睡眠期间到期的行为任务；不会把它们留到醒来后一并执行。

不泛调用所有模块的 `disable()`：部分飞行／移动模式在 disable 中直接修改或清空速度，不符合保留外部击退的要求。

### 已知范围

Velocity 在睡眠发生前已经拦截并放进延迟协程的速度包，可能因 LB 原生序列取消逻辑被丢弃；本实现没有重放它们，也不声称能恢复睡前已被其他模块改写的运动。睡眠中新到的速度／受击事件应绕过 Velocity、NoFall 等行为模块的执行。此项需在人工测试中区分时间点。

本适配固定为官方 v0.22.0 / Build #3530，不是任意 LB 版本或第三方脚本的通用沙箱。HUD／ESP 保留可用不等于所有 Render 类模块已经逐项验证。

Meteor自动模块未在本批接入等价的执行约束。正常MC操作的公共限制仍适用，但不能据此承诺Meteor的直接改速/自动攻击也已完成适配。骑乘组合和实际宿主睡眠触发仍待集中验证；普通伤害或中毒图标本身不会触发这套限制。

## 离线检查与后续集中验证

`python tools/test_sleep_action_mixins.py` 使用已有生产 JAR、真实 MC 1.21.1 类和固定 LB JAR，验证注入目标、Core/Character 共存、类别／子配置约束、队列守卫和暂停超时。不会启动游戏。

2026-10-15 的 0.3.6 B01 候选已通过：10 个真实 MC/LB 类共 36 处生产 hook 调用（含 Core 原有 hook 和公共动作 helper），八个客户端交互入口均已覆盖；九类 LB 模块、子配置父链、计划任务、忽略 running 的包事件、tick 清理、醒来和暂停超时 fixture 通过。现有 `test_lb_capability_adapter.py` 的 Kotlin range、真实 Mixin 和 Value/Choice 配置回归也通过。日志位于本地 `runtime/experiments/sleep-action-mixin-check/` 与 `runtime/experiments/lb-capability-check/`。这些结果不覆盖真实网络传输、游戏内物理或模块完整生命周期。

人工集中验证只需一组：

1. 在安全地面开启 Speed／Aura，确认睡眠前有效；保留 HUD。
2. 进入真实睡眠状态，验证移动／跳跃／攻击／使用／挖掘被阻止，Enabled 勾选保留，镜头、菜单和 F8 可用。
3. 在可控条件下确认睡眠中仍能受到击退和重力影响；区分睡前已被 Velocity 延迟的包。
4. 醒来后确认原本开启的合法模块恢复，参数不被重置；重复一次菜单暂停和一次断连重进，确认没有残留限制。

以上均为待验证步骤，不作为通过记录。
