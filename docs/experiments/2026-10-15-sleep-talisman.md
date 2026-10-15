# 2026-10-15：睡眠操作约束与护符装卸

用户选择继续开发两项既有PR缺口，实机留到之后集中验证。起点`d5b72b100eb0ae56cd81f8a590b8d786f2ef3624`；实现提交 **`c8b3fd0d1eb0e5b91c9daf9fbbe31b4fd9e5fbb5`**，`codex/b02-character-slice` / PR #6。Core与Character **0.3.6**，MC1.21.1、固定LB v0.22.0/#3530、Skills0.19.2、JDK25、Gradle9.7.1。目标仍为App1.17.1 / EXE2.7.1.0；未启动游戏复核标题版本。

## 实现

- **睡眠来源**：沿原HEFX校验后发布owner/epoch/sequence/sleeping，不从药水图标猜状态；普通Character即可生效，自由/挑战共用。宿主状态无效、失去接管、换生命/玩家、断连和停止时释放。
- **动作与LB**：限制主动移动输入、跳、攻击、使用、挖掘及原生R/地形攻击；新增坐骑召唤和装备意图也消费公共规则。保留镜头、菜单、F8、原始速度、重力、受击和击退。固定LB按行为类别抑制运行，保留Enabled/参数及HUD/ESP；延迟序列和计划任务在睡眠期间取消/丢弃，醒来不集中补发。
- **暂停与飞行**：客户端3秒有效运行时间租期，单机菜单暂停不计时；睡眠撤销flying和本功能持有的生存飞行许可，醒来按当前权限重新授予，不强行恢复旧flying。
- **护符**：已解锁原生槽17–20中的已有护符可装配、替换、卸下；尊重真实皮袋容量、同实例/同ID与原生参数同系互斥。沿游戏函数执行一次并读回；空槽须同时确认原生索引-1和handle0。完整身份/token/nonce/期限保护保留。
- **UI与配套**：护符拖拽/右键卸下，掩码拆两段16位传输，类别交叉/锁槽/旧epoch拒绝。Java拒绝睡眠中新意图，native以真实sleep状态再次拒绝在途写入。HINV v3、装备mailbox v1布局未变，但新增能力位要求本批配套DLL/JAR。

详细行为与限制见[睡眠](../sleep-actions.md)、[装备页](../equipment-v1.md)、[护符原生证据](2026-10-15-talisman-native.md)。没有改变技能点、模块算法或存档；本轮不扩展Meteor模块守卫、正常马哨、武器/丢弃和地下生成。

## 执行与结果

| 检查 | 结果 | 实际范围 |
| --- | --- | --- |
| Windows native DLL构建 | 通过 | 新护符参数、执行门控与现有桥接链接成功；没有加载DLL |
| Core-only / 普通Character / B01 Gradle build与实际JAR边界 | 通过 | 版本/元数据/入口类/可选依赖隔离；没有初始化游戏世界 |
| `test_sleep_action_server.py` | 143项通过 | 生产server/owner/动作规则，显式MC/Fabric/network替身；所有者、生命周期、序号/心跳、停止使用不放箭、停飞保留速度 |
| `test_flight_ability.py` | 33项及旧死亡重授权回归通过 | 睡眠撤销/醒来授权，不自动恢复flying；真实能力记录与明确平台替身 |
| `test_host_effects.py --vanilla` | 两种frost配置、HEFX/租约/隐藏效果通过 | 源无效/F8/中毒图标/死亡等不会残留睡眠锁；真实MC隐藏效果合并保持 |
| `test_sleep_action_mixins.py` | 10个真实MC/LB类、36处hook及policy fixture通过 | 包括Core攻击标记的allowed提前返回、Character取消攻击、普通输入/交互、LB事件/序列/计划任务、九类别及暂停租期 |
| `test_lb_capability_adapter.py` | Kotlin range、实际Mixin、Value/Choice回归通过 | 保留已有成长授权路径；不是完整LB模块运行 |
| `test_host_equipment.py` / `test_host_inventory.py` | 通过 | 完整支持EXE SHA及新增原生函数/参数签名；生产native访问/发布/执行在私有内存，包含容量、冲突、空槽、unknown、去重和防具回归 |
| `test_inventory_client.py` | 115项HINV + 44项装备协议通过 | 包含实际C++四护符publisher字节进入Java |
| `test_equipment_projection.py` | 56项通过 | 真实MC投影/槽位、身份/epoch、容量/类别、高低16位同步与ghost不可取；没有真实菜单/原生写入 |
| `git diff --check` | 通过 | 文本检查 |

真实Mixin检查发现HEAD回调顺序不能当作保护前提；最终Core在写攻击标记前也读取公共动作规则，Character保留独立取消攻击。最终检查确认两者共存，不依赖priority碰巧决定的先后顺序。

## 制品与日志

| 本地制品 | SHA-256 |
| --- | --- |
| Core DLL | `b09dffc81768c231e32f6cc6dc9d7740ee2c78ad36618aa8ca70f5e8be30ca9a` |
| proxy DLL | `8c107eea4bd4decced398ddf4db789d3ad58922427fe4b0d90f7b4278684ed58` |
| Core JAR | `76bf480744c997637b8ebc2c5b2541b13e9188adcc4efb1242401e10780c3ee0` |
| B01 Character JAR | `6efde8c0a9293ab2010fd8f5be634261d03e87c76b8a8c47e7d12557cb4ed5ad` |
| 普通Character JAR | `7e52006e82633fb64767fefcd9f03309e5c577afd74ec1e5708ef81bb82a2f39` |

制品保存在`runtime/experiments/sleep-talisman-artifacts/`，两种Character分别在`b01/`和`character/`，`manifest.json`记录实现提交；不入Git。

本地日志：`runtime/experiments/sleep-talisman-{native-build,build,core-build,character-build}.log`、`sleep-talisman-{b01,core,character}-boundaries.log`、`sleep-action-server-check.log`、`sleep-flight-check.log`、`sleep-effects-check.log`、`sleep-action-mixin-check/`、`lb-capability-check/`、`talisman-{native,inventory}-check.log`、`sleep-talisman-equipment-projection.log`。部分单元工具使用退出即清理的临时fixture，不产生游戏日志。

## 待实机

没有部署、启动游戏、真实装卸或访问运行中的游戏进程；没有computer use。实际安装仍为0.3.2 Core DLL `2d214812773bc4606507e6a51a365e70bd2044b29701a54ae229eaf09c72c3ef`，installation.json摘要`8551ce1521c33e08cbfe909caa5bc7a92af9369e1fad591aa1789071cffdf3c2`未变，原始备份保留。

集中验证先做已有防具闭环，再测真实护符装配/替换/卸下/冲突拒绝与保存；睡眠需确认真实宿主状态触发、普通操作/已启用Speed与Aura暂停、受击/击退/重力、菜单暂停及醒来恢复。Velocity睡前已缓存的包可能被上游取消序列丢弃，本实现不宣称挽回；Meteor行为守卫和骑乘组合仍待适配/验证。未观察的结果不记通过。

地表资源沿用户安排先实测分布、排除不合适区域，再接原版biome/树木生成器；当前仍为0.3.5加入的自定义模板自动布置，地下继续后置。
