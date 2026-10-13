# PR #4 补修：宿主效果释放保留外部隐藏效果

2026-10-13，基线 `b8615e1`，修复提交 `178243715e9b48ad2a1c6c453001be5944bfabc2`。本轮重新审查 PR 时发现，线上 PR 没有新增 review 评论；此项来自当前源码与实际 MC 类的本地复核。

## 问题与修复

ER 睡眠映射为 Slowness IV 后，再加入 30 秒 Slowness I，原版会将较弱、较长的药水效果放进顶层的 `hiddenEffect`。顶层实例、强度和时长并未改变，所以桥接仍拥有它；原来的 `removeEffect` 会在睡眠结束、F8 或清理时一起删除外部药水。

修复在释放自有顶层前读取隐藏链，删除顶层后恢复原隐藏实例，跳过已经过期的节点。使用原实例保留多级链、剩余时长、永久时长与 ambient / particles / icon 属性。真实 MC 1.21.1 的复制构造会丢弃隐藏链，因此没有用复制构造恢复。持续续租不会重置隐藏药水的时间。

范围仍是桥接拥有的效果；同等级短续期不可归因，以及外部更强短效果把自有层复制进隐藏链的问题，仍按既有放弃所有权规则处理，未宣称实现完整效果来源追踪。

## 验证

| 操作 | 结果 | 日志 |
| --- | --- | --- |
| `python tools/test_host_effects.py --vanilla` | 通过；两种冻伤模式，实际 Bridge 管理器配平台桩的 F8 / 降级 / 断连 / 停止清理；另执行真实 MC 1.21.1 效果类的合并、递减、续租、多级与永久链检查 | `runtime/experiments/effect-hidden-chain-check.log` |
| `python tools/test_host_effects_mixin.py` | 通过；在构建后的生产 JAR 中，Accessor 对实际 intermediary `class_1293.field_21830` 注入成功 | `runtime/experiments/effect-hidden-chain-mixin.log` |
| Windows Fabric / B01 集成候选 `build` | 通过；该构建还包含同时开发的本机玩家归属候选，不能当作仅 PR #4 的独立发行制品 | `runtime/experiments/b01-host-readiness-fabric-build.log` |

Mixin 的字段名已对照本地 MC 1.21.1 官方 / intermediary 映射核对。测试只执行效果类与离线转换；没有启动 MC 窗口、法环或实际玩家实例，没有部署 DLL。

下一批人工验证可在宿主睡眠时加入较长的弱缓慢药水，再解除睡眠或 F8，确认外部剩余效果仍在。此项保持“已修复并离线验证，实机未测”。
