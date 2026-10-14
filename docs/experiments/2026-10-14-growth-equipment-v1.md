# 2026-10-14：成长第一版、装备附加页与本地原图

代码提交：`f14ebded1ffbd651bafef4bcca6fa732f450a553`，`codex/b02-character-slice`，继续 draft PR #6。配套 Fabric Core/Character **0.3.2**；MC 1.21.1、Loader 0.19.5、API 0.116.17+1.21.1、JDK 25.0.4.1+1、Gradle 9.7.1。LB 固定 v0.22.0/#3530 与 Skills 0.19.2 未替换。

用户本轮明确选择 **“先提交，这轮不启动”**。因此没有启动 ER/MC、没有部署候选 DLL、没有实际消费技能点或执行原生换装；全部游戏验收仍为未测。

## 交付

- [成长 v1](../growth-v1.md)：6 类、14 能力、29 节点；基础全学 76 点，基础加 Speed/Aura 双专精 116 点，全满 232 点。复用原 Skills 消费与 LB 模块；飞行、无限跳和精通有已投入点数门槛。
- [完整 LB 目录](../lb-growth-catalog.md)：固定版本 193 个正式注册模块，逐栏目标为成长、免费或挑战模式暂不开放。稳定能力 ID、共享数据目录、通用模块/值/模式守卫与 `b01_policy_v2`；以原 LB 范围和服务端范围的交集限制实际执行。
- 五套预设 `starter / explorer / fighter / journey / flight`。使用独立测试点来源，持久保留原节点；清除预设恢复原学习并保留期间真实升级点。旧 6 节点成本改变需要显式 `migrate`，不静默清账。
- [装备 v1](../equipment-v1.md)：原版容器附加页，I 或原物品栏 ER 按钮打开；保留 MC 背包与四防具。ER 四防具可发送装配/卸下请求；护符开放数来自当前 ER 数据，护符操作仍只读。
- 原生装备通过当前 EXE 的合法装配/卸下函数执行，带身份、场景、期限、库存令牌、nonce 与真实重读确认。专用菜单意图带用户看到的物品身份和页面版本，旧页面点击拒绝。投影格不可取出、拖入、复制或消费；鼠标持物时不转页。
- 从本机游戏只读提取 6,604 条中文物品元数据、2,663 个原生图标，缩为 64px，显示在 16px 槽位；动态缓存有上限，原始游戏资产不进入 Git/JAR。
- [TarnishedTool](../debug-tool.md) v1.2.1 已下载到独立目录并核对官方摘要，未运行、未附加；Hexinton 未安装。

## 验证与边界

| 验证 | 结果 | 日志 |
| --- | --- | --- |
| Windows native build | 通过，含零数量已拥有防具修正 | `runtime/experiments/growth-equipment-native-build.log` |
| B01 + 固定 LB `build verifyLaunchProfile` | 通过，包含最后游标物品入口检查 | `runtime/experiments/growth-equipment-fabric-build.log` |
| 普通 Character `build verifyLaunchProfile` | 通过，不编译 Skills API 实现 | `runtime/experiments/growth-equipment-character-build.log` |
| 两种真实 JAR 边界/隔离类型解析 | 通过 | `runtime/experiments/growth-equipment-{boundary,character-boundary}.log` |
| 成长领域和独立 JVM 恢复 | 16 组通过 | 构建日志 `b01PreparationCheck` |
| 目录、预算、阶段、坏配置与恢复日志 | 41 项通过 | `runtime/experiments/growth-catalog-check.log` |
| 固定 Skills 真类解析、五预设正式购买、退款/恢复/NBT | 通过；没有 GUI 或真实玩家回调 | `runtime/experiments/growth-skills-check.log` |
| policy_v2 真 MC codec + 27 类坏包、旧配置/绑定存储 | 通过 | `runtime/experiments/growth-{payload,files}-check.log` |
| 固定 LB 真字节码注入与 Value/RangedValue、IntRange、AirJump 枚举 | 13 个注入与值检查通过；更窄服务端上限、升级保留选择、降级、导入/脚本均覆盖 | `runtime/experiments/lb-capability-check/` |
| HINV/装备原生 guards、确认与去重 | 生产代码私有内存 fixture 通过，不调用实际游戏换装 | `runtime/experiments/equipment-{native,inventory}-check.log` |
| HINV/装备 Java mailbox | 62 + 20 项通过 | `runtime/experiments/equipment-java-check.log` |
| 真 MC ItemStack/CustomData/lore/投影 Slot、Intent 编解码 | 14 项通过 | `runtime/experiments/equipment-projection-check/` |
| 飞行权限租期 | 28 项通过；死亡旧实体同 tick 再授予问题已修，旧代码反证失败 | `runtime/experiments/flight-ability-check.log` |
| 模块生命周期注册顺序 | 两种顺序各 30 项通过 | `runtime/experiments/growth-lifecycle-check.log` |
| 原图/中文导出 | 成功；实际预览过一张头盔 PNG | `runtime/experiments/host-item-assets-export.log` |

LB 值检查使用实际固定类及显式绑定替身，未初始化每个真实模块 singleton。Skills 检查使用框架数据类，但不运行游戏玩家。装备原生 fixture 的 Invoke 只改变私有模拟对象。以上不等于模块运动、WebGUI 刷新、原生外观或换装保存已经通过。

最后两个游标持物门槛是在容器 fixture 后加入，随后重新构建及类型边界检查通过；未把尚未执行的真实游标交互写成通过。

## 候选制品

| 文件 | SHA-256 |
| --- | --- |
| `dist/erbridge_core.dll` | `2d214812773bc4606507e6a51a365e70bd2044b29701a54ae229eaf09c72c3ef` |
| `dist/dinput8.dll` | `a832b00032e9d898e997fc50bd55f907919f04c756c42a097f16c0bf165015b0` |
| Core `er-bridge-0.3.2.jar` | `933e43f008cbad0c48d347caec1d728b0f7db989da30c3032191451cc4db1374` |
| B01 Character `er-character-bridge-0.3.2.jar` | `3fc042f4e8165d7fb60765a6c8b861200da227c5cf846baa3dabd4719aaec161` |
| 普通 Character `er-character-bridge-0.3.2.jar` | `27337d1ea5d1eaf062f3f7889baf3daac89900448365e4c3e9ffe753ebab5fef` |
| 本地 `runtime/host-assets/items.json` | `2c50fe28a5236700dfcaf344099e3be1236f366f3df69df697217c761245831b` |
| TarnishedTool v1.2.1 独立 EXE | `a3b31eb8c4383b55a2ce0d92f90b67ab823df1f9f2ac197cdd9fd4379954025a` |

B01 双 JAR 快照位于 `runtime/experiments/growth-equipment-artifacts/b01/`。最后普通 Character 构建会覆盖同名工作区角色 JAR；`-B01` 启动预检会按其 profile 重建。版本提高到 0.3.2，避免旧 0.3.1 角色 JAR 与新的能力 ID API 配错。

实际游戏目录仍是旧 Core DLL，SHA `d6be349a579747ed8b7979a6d993471493a0155a0f83e6a8dc4e7acc5887bdf1`；`installation.json` 摘要仍为 `8551ce1521c33e08cbfe909caa5bc7a92af9369e1fad591aa1789071cffdf3c2`。本轮没有改安装备份、游戏文件或存档。

## 下一次集中实测

先在两游戏关闭时备份并更新配套 DLL，再使用 `-B01`。检查安全接管/技能树后，预览预设，选 `starter` 或 `journey` 应用；验证未学模块拦截、学成启用、范围、模式偏好及 clear 恢复。实际 ER 升级发点仍需验证；已有补卢恩工具可用于该项。

随后只选一件现有头盔/上衣完成装配、原生菜单对照、卸下和重进保存核对，并核对 MC 防具与护符槽数。防具第一件通过后再扩展其他装备，不把四槽共用代码当成四件全部实测。

怪物身体/攻击盒与挤压测试单独启动已准备的 TarnishedTool，记录实际开启功能；没有安排本轮自动注入。骑乘区域许可、睡眠完整禁行动、Meteor 成长和武器/护符写入仍未实现。
