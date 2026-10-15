# 0.3.7：资源禁区与精确飞行候选

2026-10-15，Windows 本地。用户要求继续推进无需实机操作的内容。本批没有部署、启动游戏或修改生产世界；实现与离线检查通过，实际生成覆盖、操控和模块生命周期均待集中验证。

## 提交与配套

- 分支：`codex/b02-character-slice`，继续作为 [PR #6](https://github.com/0smium/Minecraft-Ring/pull/6) 集成入口，不改变叠加 PR 顺序或合并 main。
- 基线：`572a1c01bab69746945790838479817427dce37c`；本批实现：`f9192a49fa77f3500d67929d67b2efbcadcf3c51`。
- Core / Character：0.3.7；MC 1.21.1、Fabric API 0.116.17+1.21.1、Loader 0.19.5、JDK 25.0.4.1+1、Gradle 9.7.1。
- 固定 LB v0.22.0 / Build #3530，源码参考仍为 `e5a0dbf5458b063d3028e69e04762b8b25b998b5`；Skills 0.19.2。未替换这些依赖或用户配置。
- 原生部分沿用 0.3.6 候选，未改偏移、协议布局或 DLL。实际安装仍为 0.3.2；后续集中部署需一起带上前批护符原生候选与本批两个 JAR。

## 资源禁区

新增本机宿主管理命令：

```text
/erbridge resources exclude here <1..256半径> [名称]
/erbridge resources exclude zone [名称]
/erbridge resources exclude list [页码]
/erbridge resources exclude remove <编号>
```

`here` 记录当前方块中心的水平圆形范围，覆盖全部高度；`zone` 覆盖当前桥接区域及稳定映射，可能是一大片开放世界，不能理解为仅当前房间。规则含 zone/region、锚点、比例和朝向，按世界存盘。新增要求有效 MC 接管、宿主存活且区域同步完成；列表/移除不依赖当前法环连接。最多 128 条，每页 8 条。

排除按整棵树冠的完整足迹相交判定，发生在原生地表补采排队前；新增规则撤回旧补采队列。没有删除已有资源、清空采集收据或改变候选种子；撤销规则也不会重刷采过的位置。

资源 SavedData 从 v1 扩为 v2，无损保留原开关和生成收据。未知版本、坏规则及不合法布尔字段均拒绝生成和保存，不能退回空账。交叉审查发现的 v1 `enabled=2/-1` 接受问题已修并覆盖。未硬编码未经观察的禁用地图编号；屋顶、水体和室内自动识别仍未实现。

## Fly 策略分支

旧 Fly I/II/III、Creative 参数及真实点数保留；新增两枚独立 `fly_precision_1/2`，前置 Fly I，先投入 40/60 点，再分别消费 8/18 点。挑战模式只对已学分支开放原 LB Vanilla。

| 分支档位 | 水平速度 | 升降速度 | 固定约束 |
| --- | --- | --- | --- |
| 精确 I | 0.10–0.25 格/tick | 0.10–0.20 格/tick | Glide=0，SprintSpeed 关闭，BypassVanillaCheck=false |
| 精确 II | 0.10–0.40 格/tick | 0.10–0.30 格/tick | 同上 |

收益是独立调节水平/垂直运动与低速定位，不把 Creative 已有悬停重复包装为解锁。Vanilla 直接设置速度，数值不能与 Creative 能力速度直接比较。两种模式不叠加；退款当前精确分支会停止模块、回到仍合法的 Creative，不自动重开。

目前共 14 个 LB 模块、16 个授权条目、32 节点、10 套预设。基础 76 点及 `journey` 116 点不变，全满变为 264 点；新增 `flight_precision` 102 点和 `flight_routes` 128 点对照。旧 9 个真实点、Speed II 花费 8/剩 1 的状态保持，账本无需迁移。新节点错开旧 Speed 路线，最小节点中心距离大于 40；仅离线布局检查，未观察游戏 UI。

## 活动与权限

Vanilla 不维护原版 flying 位，不能仅加入模式下拉框。新公共 `PlayerFlight` 分类由 Core 默认读取原版位，Character 在有效自定义活动期间补充；相机、平台、原生支撑/落地和坐骑防飞行召唤使用同一分类。Core 不引用 Character 或 LB。

客户端仅在桥接世界、有效 MC 接管、本人存活且 LB Vanilla 实际开启时发送声明，每 5 tick 刷新。服务端校验本机 owner、宿主生命周期、原生状态、新鲜帧和已学分支（或明确自由模式），先安装合法 mayfly，再确认。客户端未收到有效确认时，LB `getRunning` 门控阻止 Vanilla 执行。声明与确认各有 750ms 期限、递增序号与宿主身份，旧确认不能在停用/重启后复活授权。

停止、模式切换、退款、睡眠、F8、暂停、死亡、召回、断连和宿主变化释放自定义活动；恢复后重新确认。活动期间清除 MC 下落累计并拦截 MC 坠落伤害；不承诺避免法环必死线。旧 Creative 学习所持有的基础 mayfly 语义保留，自定义活动与其分开。

初稿把创造 flying 位永久清掉的问题已在交叉审查中修复：仅调整 `Player.travel` 的当次飞行判断，不写真实能力位；实际 Mixin 检查还断言该 hook 没有 `PUTFIELD`。没有复制 LB 运动算法或禁用全局死亡逻辑。

## 离线验证

| 操作 | 结果与边界 |
| --- | --- |
| Core-only、普通 Character、B01 构建及最终 JAR 边界 | 通过；实际 JAR 元数据、类归属与不加载可选客户端的类型解析通过，无 Fabric 启动 |
| `test_surface_resource_state.py` | 286 项通过：真实 SavedData/二进制 NBT、v1 迁移、范围相交、映射隔离、收据/撤销与坏档；当前命令源直接 javac 编译通过，未连接真实世界执行命令 |
| `test_growth_catalog.py` / `generate_growth_content.py --check` | 69 项目录/策略/账本断言、预算与生成物一致性通过 |
| `test_b01_skills.py` | 固定真实 Skills 解析 32 节点、全部预设购买/退款/NBT/clear 与旧 9 点 Speed II 兼容通过 |
| `test_b01_payload.py` | 所有预设、真实 MC 缓冲区往返与 27 个坏包通过 |
| `test_flight_ability.py` | 76 项生产服务端/客户端租期逻辑及旧死亡回归检出通过；MC/网络/策略入口使用明确替身 |
| `test_precision_flight_payload.py` | 当前生产 CODEC、真实 MC 缓冲区：6 次往返、44 个非法输入、37 字节布局与连续消息边界通过，未联网 |
| `test_sleep_action_server.py` | 原睡眠 143 项回归通过 |
| `test_sleep_action_mixins.py` | 10 个实际 MC/LB 类、38 处生产 hook 通过；新增两处 Player 飞行注入与无真实 flying 写入检查，非实际运动 |
| `test_lb_capability_adapter.py` | 原 Kotlin/Value/Mixin 检查通过；新增 130 项 Fly 参数/模式/退款及 92 项实际序列化 GUI 投影通过，模块生命周期使用计数 fixture |

新 GUI fixture 初次漏注册真实 LB `RangeAdapter`，范围结构不符而失败；补齐固定 LB 的序列化装配后通过，未因此修改生产投影逻辑。

日志根为忽略路径 `runtime/experiments/`：`precision-resource-{core,character}-build.log`、`precision-resource-build.log`、`precision-resource-{core,character,b01}-boundaries.log`、`precision-flight-check.log`、`precision-flight-mixin-check.log`、`precision-growth-check.log`、`precision-growth-generated.log`、`precision-policy-payload.log`；资源详见 `surface-resource-state-check/{compile,check}.log`，LB 成功详见 `lb-capability-check/RuntimeValueProbe.log`。`precision-lb-check.log` 保留修复前失败供追溯。Skills、睡眠 server 和精确 CODEC 的通过输出来自本批命令控制台。没有游戏日志、截图或录像可充当本批实机通过依据。

## 构建摘要

候选保存在忽略路径 `runtime/experiments/precision-resource-artifacts/`，`character/` 与 `b01/` 分开。原生依赖来自前批 `sleep-talisman-artifacts/`。

| 制品 | SHA-256 |
| --- | --- |
| Core 0.3.7 | `43d021f5af9156711586733d4dda27460b39361cf76635f2b68c388b2fe69bb2` |
| B01 Character 0.3.7 | `887ee6183bac208733d9ede97d473221e13619bd95770d2aa70c3fc6f1605667` |
| 普通 Character 0.3.7 | `2552131d5510f5aa00601cac13c82a4a276a875784e97a2102d899739ece958c` |
| 沿用原生 Core 0.3.6 | `b09dffc81768c231e32f6cc6dc9d7740ee2c78ad36618aa8ca70f5e8be30ca9a` |
| 沿用代理 dinput8 | `8c107eea4bd4decced398ddf4db789d3ad58922427fe4b0d90f7b4278684ed58` |

实际安装 DLL 仍为 `2d214812773bc4606507e6a51a365e70bd2044b29701a54ae229eaf09c72c3ef`，`installation.json` 仍为 `8551ce1521c33e08cbfe909caa5bc7a92af9369e1fad591aa1789071cffdf3c2`，均与前批一致。安装备份、存档和 LB 配置保留。

## 后续边界

原版树木/群系只完成[源码调查与接入方案](../vanilla-surface-features-plan.md)，另有只读资源探针。先完成现有分布与禁区实机，再用有界缓冲生成原版 oak/birch、校验完整修改后落地；整群系装饰和地下后置。

集中验证时追加资源禁区实际范围/保存重进、Fly 两路参数与停止/退款/F8/睡眠恢复。原防具/护符、界面、R 交接、原生建筑碰撞等仍按[当前状态](../current-development.md)一次组织；本批未解决或冒充通过这些旧缺口。
