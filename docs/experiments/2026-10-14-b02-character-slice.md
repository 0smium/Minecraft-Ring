# 2026-10-14：背包只读页、MC 马与 R03

用户要求继续开发，实机后面集中测。分支 `codex/b02-character-slice`，基于 PR #5 `02e06dd22616a29b83f38bd05be5e24c820194de`；本轮代码提交 `227949643194562955cfa38f24157834ad433dde`。PR #5 检查时无新增在线 review 评论；范围来自规划 PR #2 的 B02 与已记录的 R03。

实际交付是[角色操作候选](../b02-character-slice.md)：I 键真实背包只读页、原版 MC Horse 管理员骑乘命令，以及仅针对自身确认实例的调试怪清理。未实现装备写入、物品使用或施法，不把接口和假数据当作这些能力已完成。

## 固定环境与操作

- Windows x64；沿用此前人工确认的 App 1.17.1，磁盘 exe 2.7.1.0；完整 exe SHA-256 `1A3547101327F65D0C76DA2F9190AC0AA66871EA42BAE2AECC61E11A8B597891` 本轮读取检查通过。
- MC 1.21.1、Fabric API `0.116.17+1.21.1`、Python 3.13.6、JDK 25.0.4.1+1、Gradle 9.7.1、项目内 LLVM-MinGW。
- 构建配置为 `-PlbProduction=true -Pb01=true`，固定 LB #3530 JAR；保持原有 Skills 制品。此项是构建，不是加载 LB 运行。
- 没有启动 ER/MC、发放卢恩、调用物品写入、复制候选到游戏目录或重跑安装器。上轮备份保留；下次开测前再核对新鲜度。

## 实现与复核

HINV 只读链对照固定 [fromsoftware-rs@59fbd3b](https://github.com/vswarte/fromsoftware-rs/tree/59fbd3b3b7daaf14aca47c9f73530493dba6bc79/crates/eldenring/src/cs) 的 `player_game_data.rs` / `item_id.rs`，并逐字核对本机 exe 的九段访问指令与两类 RTTI。列表读取有长度/容量上限、前后复读及 owner 校验；完整采样最多 2 Hz，失效立即清空。HINV 头含 Unix 毫秒，Java 和宿主时间域一致。

MC 页显示原始 ID / 类别 / 数量 / 装备槽；名称表、图片和写操作留待后续。只读页支持搜索与分页，1024 条截断明确显示。跨语言检查保留稀疏槽索引与零数量，并拒绝坏版本、越界、重复槽/装备位、过期/错角色/旧进程样本。JDK 25 原子读取使用 direct/mapped buffer，heap buffer 直接拒绝。

MC 马使用实际 Horse API、原版移动/碰撞/摔伤；跟随位置和地形采样改用管理马的脚底，摄像机保持骑手位置。鞍从背包借出，清理归还原物品；复核补上死亡、removed、PlayerList 旧对象拒绝归还，防止把物品塞回不会被保存的旧玩家。背包满或菜单游标未落定时保留实体和装备。

R03 清理由全表删除改为自身实例跟踪；请求取消、上下文变化和读取不完整时弃权。源码与测试均保留“外部同模板强行并发无法区分”的边界；未以该有限修复宣称任意刷怪器兼容。

## 检查结果

| 操作 | 结果 | 日志（本地，不提交） |
| --- | --- | --- |
| `python tools/test_host_inventory.py` | 通过；实际磁盘 SHA/九段指令/RTTI；真实 C++ reader/publisher 对内存桩，长度尾部排除、零数量、装备匹配、复读拒绝、截断、失效，以及 2 Hz feed | `runtime/experiments/b02-inventory-native.log` |
| `python tools/test_inventory_client.py` | 通过 56 项；真实 C++ publisher 字节交给生产 Java 解码，身份/时效/版本/筛选与异常输入 | `runtime/experiments/b02-inventory-java.log` |
| `python tools/test_debug_spawn_ownership.py` | 通过；真实原生函数和 tracker 配私有对象桩，外部已有怪、无归属、复用地址、迟到/取消/超时、切图、同帧冲突等 | `runtime/experiments/b02-debug-spawn-check.log` |
| `python tools/test_mount_vanilla.py` | 通过；实际 MC/Fabric API 专项编译，真实 ItemStack/components 容量检查及 S2C/C2S codec | `runtime/experiments/mount-vanilla-check/compile.log`、`vanilla.log` |
| `python tools/test_mount_lifecycle.py` | 通过 32 项；实际清理函数配玩家/马/菜单/连接桩，ItemStack/SlotAccess/SimpleContainer 为真实 MC 类；死亡/旧对象、满包、重连一次归还、GUI游标、普通马隔离和死马不重复返还 | `runtime/experiments/mount-lifecycle-check/compile.log`、`lifecycle.log` |
| `python tools/test_local_host_routing.py --fabric-events --regression-ref b8615e1` | 通过 39 项；保留注册后 JOIN 和玩家归属，旧代码按预期失败 | `runtime/experiments/b02-host-routing.log` |
| 地形通道与净空检查 | `test_terrain_passages.py`、`test_terrain_clearance.py` 通过；步行原有路径保持 | `runtime/experiments/b02-terrain-passages.log`、`b02-terrain-clearance-check.log` |
| `python tools/test_incoming_hit_startup.py --regression-ref 376d148` | 通过；R09 失败在新增读取器初始化前退出 | `runtime/experiments/b02-r09-check.log` |
| `python tools/build_native.py` | Windows native 构建通过 | `runtime/experiments/b02-native-build.log` |
| Gradle `build`，固定 LB / B01，`--no-daemon --no-configuration-cache --offline` | 完整 Fabric 构建通过；16 组 B01 内核检查和独立 JVM 恢复通过 | `runtime/experiments/b02-fabric-build.log` |

这些结果不包含真实 Horse 的世界 tick、实际骑行/跳跃、画面合成或法环实时背包核对。未新增任何实机“通过”项。

## 候选制品

| 文件 | SHA-256 |
| --- | --- |
| `dist/erbridge_core.dll` | `99FDB70E9BB6D11414FC8CDDA79054392C3EB3F6283B55F9F1ED596951FEC140` |
| `dist/dinput8.dll` | `52B86C85711B954EE05A4FB24687C97C9AB22DBBA4AB7531333B6A41518B2FF2` |
| `bridge-base/elden-ring/mc-bridge/build/libs/er-bridge-0.3.0.jar` | `E54AB8DC3EC8B3C827B9770432E267FE94873B2DD823A78067342A150D49CBBB` |

部署仍需 DLL / JAR 配套，保留 `installation.json` 与原始备份。下一批先准备新鲜备份、测试卢恩和一个 MC 鞍，再按 B01 → 背包 → 坐骑 → 状态/Meteor 的相关清单集中观察；不能用本轮构建替代升级消费链或实际骑乘结果。
