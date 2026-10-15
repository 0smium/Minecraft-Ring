# 2026-10-12：玩法准备度 review

评审提交：[`3e66a0b2780b72b60eeb0197bbc01c28db47be0b`](https://github.com/0smium/Minecraft-Ring/commit/3e66a0b2780b72b60eeb0197bbc01c28db47be0b)\
分支：`dev/lb-poc`\
范围：新增实验记录、LB 启动接法、资源/交互、敌人生命周期、伤害/死亡、建筑碰撞与未来玩法接口。不是对本分支所有改动的完整正确性审计。\
验证方式：读取固定提交源码和已提交实验记录；本次未启动游戏、未执行构建或实机测试。\
关联：[玩法路线与 session 分工](../gameplay-roadmap.md)

## 结论

继续原 LB + Core 工作线，同时把内容桥接和玩法作为独立任务。现有代码足以支持小范围玩法探索，但不能据此宣称已支持通用刷 Boss、法环背包、掉落经济或多人 UHC。下面的问题按依赖它的功能处理，不要求先修完全部条目才继续 LB。

## 已取得的成果

按 [检查点](../experiments/20261012-checkpoint.md)和 [LB UI 记录](../experiments/20261011-lb-ui-preflight.md)：环境与合成已运行；退出恢复、创造悬停、首次接管静止和飞行脱离平台有用户通过记录；固定 LB 的 ClickGUI/HUD 已在最终合成录屏中可见。GUI 打开时 F8 不切换是用户接受的限制，不再作为待修缺陷。

LB 开发映射运行曾在跳跃时崩溃；分支已转为 `ClientProductionRunTask` 加载原始固定 JAR。后续沿用已验证接法，不照旧方案重新安装一套 `modRuntimeOnly` 重映射实验。

站立升降平台仍失败。三次同条件攻击、末影珍珠、世界特效、FlyCreative 和 Velocity 尚未完成正式验证。新的原版 Poison 映射已构建但尚未部署通过；不能把第十三轮旧 status 方案的失败与新候选混成同一次实测。

## R01：多人受击、死亡和状态没有按玩家归属

**分类：多人功能上线前必须处理。状态：待处理；不阻塞当前单人 LB。工作线：A/B，C 提供对局需求。**

`CombatBridge.onServerTick` 将一个宿主伤害转发给服务器玩家列表；`LifeBridge.onServerTick` 将一个宿主死亡转发给所有存活玩家；`HostEffectsBridge` 对多个玩家使用同一份宿主效果输入。这是单宿主模型，不是可靠的多玩家状态对应。

后果：直接开放 LAN 或连接多人服可能让一人受击、死亡或中毒影响其他人。建议明确玩家 UUID 到本地宿主/会话的归属，处理各自伤害、效果与重生。独立服务器还需要网络传输、场景坐标一致性和共享敌人权威方案，不能直接读取远端共享内存。

验收：两人各自受击/死亡互不串联；共享敌人仅结算一次；换图、断开后旧事件不作用于另一玩家。尚未进行该验证。

## R02：代理死亡不会自动触发 MC 战利品，卸载不能算击杀

**分类：资源与击杀奖励上线前必须处理。状态：待处理。工作线：B 发布事件，C 配置与结算。**

`EntityBridge.onServerTick` 读取宿主死亡标志后记录并 `proxy.discard()`；超出发布范围等未见实体也移除代理。没有通过普通 MC `LivingEntity` 的死亡掉落流程产生战利品。只监听普通 MC 生物死亡，可能漏掉宿主敌人；只监听代理移除则会把卸载当成击杀。

建议提供明确的宿主死亡/遭遇完成事件，并记录遭遇 ID、实例身份、会话与处理结果；以最终死亡为准，不由 MC 估算攻击伤害归零。多阶段 Boss 需要遭遇级完成判据。掉落、雷效和公告使用同一结算身份，重连不重复发放。

验收：击杀发一次，离开采样范围不发，切图/重连不再发；允许下一局或原生重生时按规则重新结算。

## R03：调试士兵生成器不能直接推广成任意 Boss/红灵刷怪器

**分类：遭遇玩法上线前必须验证。状态：待实测/待扩展。工作线：B。**

`game.cpp::request_test_enemy` 固定写入 `c3000`、NpcParam `30000014` 和 AI `30000000`，在相机前方请求生成士兵。注释明确无地图/事件实体，换图后消失；并不证明任意 Boss 的加载、多阶段、导航与事件行为可用。

另一个风险是 `remove_test_enemy` 遍历整个 debug set 清理角色，函数内假定其中所有角色都属于本工具。这个调试假定不适合作为新玩法的清理接口。建议在新遭遇接口中记录自身实例，只清理这些实例；生成返回确认或失败，不能仅清除请求位就标记成功。

验收：一个模板的生成、目标确认、战斗、真实死亡、重入、清理全部闭环；另一独立调试角色不被误清理。之后逐个增加 Boss/红灵模板。

## R04：建筑尚未成为法环原生防御工事

**分类：防守/困怪玩法上线前必须验证。状态：已知限制。工作线：A/B。**

当前路径将法环碰撞射线结果生成 MC 地形；检查现有协议与执行路径，未发现把新 MC 方块注册到法环物理世界及导航的通路。MC 内有碰撞不代表 ER 敌人、原生投射物和攻击会被拦住。

建议首局建筑用于探索路线、补给和展示；防御另做小实验，分别观察敌人移动、原生投射物和攻击命中。不要仅凭 Steve 站得住就判定城墙能挡 Boss。任意挖掘法环地图还需要处理原生几何/显示/碰撞，属于独立扩展。

## R05：原生交互已有入口，资源身份与确认仍不足

**分类：场景采集上线前必须处理。状态：待处理。工作线：B/C。**

`ErBridgeMod` 禁止直接破坏宿主代理地形，攻击代理时调用 `TerrainManager.strike`，原生 `service_damage` 向命中位置发出 poke 攻击以破坏原本可破坏的物件。`service_action` 请求执行当前原生提示，但不是包含物品 ID、数量和领取归属的拾取事件流。

不能把挥镐、请求被处理或 HP/库存的任意变化直接当作成功采集。建议首局先加入明确的 MC 资源点；原生采集逐类补充对象身份、成功确认、刷新规则和持久去重。拾取数量变化的原因未知时不自动兑换。

验收：同一箱子重复敲击不会重复发资源；未打碎不发；休息刷新/新局是否重新可采按规则执行。

## R06：双装备需要明确结算链，背包 UI 不等于效果已接入

**分类：装备功能上线前必须处理。状态：待处理。工作线：B，C 定义平衡。**

`CombatBridge` 输入为宿主实际 HP 损失，可能已经包含法环防御，再换算并调用 MC `player.hurt`。不能把同一法环防御再次当独立乘区重复加入。反向 `game.cpp::er_damage/apply_hit` 使用 MC 伤害与敌人最大 HP 换算，未读取玩家当前法环武器来实现其原生攻击属性。

建议库存归原生侧，MC UI 发操作请求并确认；一件物品的效果只在指定侧结算。先验证一个装备和一个消耗品，区分“界面显示正确”“装备/扣除成功”“实际能力正确”。转换成可拾取 MC 凭证时补领取记录，不制造两份独立可用库存。

## R07：UHC 的死亡与治疗规则会与现有 Core 默认行为重叠

**分类：UHC/远征规则上线前必须处理。状态：待处理。工作线：C 与 A/B 协调。**

`LifeBridge` 开启立即重生并在死亡后驱动两边复活/回归；`TerrainManager.onServerStarted` 开启 keepInventory。UHC 若仅设置 hardcore 或死亡掉落规则而不协调上述路径，会出现规则相互覆盖。

建议由模式规定淘汰/失败时的状态，停止本局遭遇和结算，并与 Core 生命期同步配合。玩法退出恢复自身改动前的规则。[战斗实验](../experiments/20261012-core-combat-baseline.md)尚有 naturalRegeneration 原值 true 待恢复的记录；不要把该临时实验设置默认为以后所有模式的规则。

## R08：持久地形缓存是后置优化，不成为这轮玩法前提

**分类：可后置建议。状态：已知限制。工作线：A，待性能/路线证据触发。**

地形细形状已通过 `TerrainShapeBlockEntity` 存入 MC 世界；采样覆盖与已确认空气等元数据没有同等完整的持久恢复。`TerrainSessionCache` 在当前源码中无调用，`TerrainPrefetch` 仍有两秒刷新条件。

后续可以研究静态缓存＋动态区失效；一次生成不是任意地形可破坏的解决方案。本轮先保留采样，只有实际卡顿/缺地形阻塞所选路线时再处理，不让全图预生成取代 LB 与首局玩法目标。

## 建议的处理顺序

1. A 继续原 LB 实验和检查点范围，停止围绕同一非阻塞平台问题无限试改。
2. B 先闭合 R02/R03 所需的一个遭遇，再加 R06 的一个真实物品操作；R05 可先用 MC 资源点绕开原生采集缺口。
3. C 先完成单区域标点—触发—击杀—雷效—掉落—缩圈—结算，解决 R07，记录尚未接通的宿主能力。
4. 多人时处理 R01；防御建造时处理 R04；明确性能需求后处理 R08。

这里是功能依赖顺序，不是修改原实验验收标准；接口/规则的模拟开发可并行，实际游戏一次验证一套配套制品。

## 固定源码入口

- [EntityBridge](https://github.com/0smium/Minecraft-Ring/blob/3e66a0b2780b72b60eeb0197bbc01c28db47be0b/bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge/entity/EntityBridge.java)
- [CombatBridge](https://github.com/0smium/Minecraft-Ring/blob/3e66a0b2780b72b60eeb0197bbc01c28db47be0b/bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge/entity/CombatBridge.java)
- [LifeBridge](https://github.com/0smium/Minecraft-Ring/blob/3e66a0b2780b72b60eeb0197bbc01c28db47be0b/bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge/LifeBridge.java)
- [HostEffectsBridge](https://github.com/0smium/Minecraft-Ring/blob/3e66a0b2780b72b60eeb0197bbc01c28db47be0b/bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge/HostEffectsBridge.java)
- [ErBridgeMod](https://github.com/0smium/Minecraft-Ring/blob/3e66a0b2780b72b60eeb0197bbc01c28db47be0b/bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge/ErBridgeMod.java)
- [game.cpp](https://github.com/0smium/Minecraft-Ring/blob/3e66a0b2780b72b60eeb0197bbc01c28db47be0b/bridge-base/elden-ring/er-bridge/src/game.cpp)
- [协议](https://github.com/0smium/Minecraft-Ring/blob/3e66a0b2780b72b60eeb0197bbc01c28db47be0b/bridge-base/elden-ring/er-bridge/include/bridge_protocol.h)
- [TerrainManager](https://github.com/0smium/Minecraft-Ring/blob/3e66a0b2780b72b60eeb0197bbc01c28db47be0b/bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge/TerrainManager.java)
- [地形细形状保存](https://github.com/0smium/Minecraft-Ring/blob/3e66a0b2780b72b60eeb0197bbc01c28db47be0b/bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge/TerrainShapeBlockEntity.java)
- [TerrainPrefetch](https://github.com/0smium/Minecraft-Ring/blob/3e66a0b2780b72b60eeb0197bbc01c28db47be0b/bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge/TerrainPrefetch.java)
- [TerrainSessionCache](https://github.com/0smium/Minecraft-Ring/blob/3e66a0b2780b72b60eeb0197bbc01c28db47be0b/bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge/TerrainSessionCache.java)
