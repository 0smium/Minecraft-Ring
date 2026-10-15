# 已拥有护符的原生装卸候选（2026-10-15）

状态：在 `codex/b02-character-slice` / `d5b72b1` 基础上扩展原生装备适配；生产 C++ 入口的离线回归通过。没有启动游戏、部署 DLL、访问运行中的进程或实际执行装卸。真实效果、负重和存档保持仍待集中验证。

## 支持范围

- 保留防具槽 12..15；新增当前角色已解锁的护符槽 17..20。武器、槽 21、仓库、物品发放和皮袋解锁不在此次范围。
- 可以将真实库存中已有的护符装到空槽、替换该槽护符、卸下。替换不会先发一次卸下请求，而是调用一次游戏的装配函数。
- 容量仍取当前 `PlayerCommonParam[0].baseAccSlotSize + PlayerGameData.unlocked_talisman_slots`，最大 4。未知容量不开放护符操作；执行前重读，容量改变拒绝请求。
- 不能把同一个实例、另一份同 ID 护符，或者相同 `accessoryGroup` 的护符装在另一槽。替换目标槽不计作冲突；两件不同 ID 且 group 为 -1 的护符不因 -1 而互斥。护符分组取当前加载的 regulation，未按 ID 尾数或本地展示名称猜测。

## 目标二进制与证据

支持 App 1.17.1 / `eldenring.exe` 2.7.1.0，完整磁盘 SHA-256：

`1a3547101327f65d0c76da2f9190ac0aa66871ea42bae2aecc61e11a8b597891`

继续复用已核查的装配 RVA `0x249160`（完整 0x1AC 字节）和卸下 RVA `0x247160`（完整 0x61B 字节），参数和原生防具操作相同。新增证据由此完整目标 EXE 的磁盘反汇编取得：

| 位置 | 实际意义 | 运行时门控 |
| --- | --- | --- |
| `0x788CE9..0x788CFB` | 原生 UI 将从 17 起的装饰品槽归为 raw item 类别 `0x20000000` | 0x13 字节 FNV-1a64 `036335D52C8CF836` |
| `0x247614..0x247643` | 原生卸下函数的装饰品分支：构造 handle=0、库存索引=-1，交给同一装配函数 | 包含在已有完整卸下函数哈希中 |
| `0xD238E0..0xD23936` | 原生同系判断：读取双方 signed 16-bit `accessoryGroup` +0x48；双方非 -1 且相等时返回冲突 | 完整 0x57 字节 `0F6C4C72CDCB869B` |
| `0xD23657..0xD23679` | 上述护符参数 getter 使用 SoloParamRepository 表索引 2，并经过两个 +0x80 资源指针 | 0x23 字节 `4E3549431A522F3C` |
| `0x788630..0x788669` | 原生 UI 遍历待比较物品，实际调用 `0xD238E0` 并据返回值选择冲突路径 | 0x3A 字节 `3F3F5C3CDD3DED2F` |

字段定义也与 [Paramdex 的 EquipParamAccessory v2](https://github.com/soulsmods/Paramdex/blob/master/ER/Defs/EquipParamAccessory.xml) 一致；表索引与 [固定 fromsoftware-rs `59fbd3b3`](https://github.com/vswarte/fromsoftware-rs/blob/59fbd3b3b7daaf14aca47c9f73530493dba6bc79/crates/eldenring/src/cs/solo_param_repository.rs) 一致。生产读者要求 Accessory 参数版本为 2、行存在且 group 合法。失败不会退回猜测分组。相关反汇编保存在忽略目录 `runtime/experiments/equipment-research/talisman-*.txt` 与 `group-d238e0.txt`，不提交游戏二进制或参数表。

## 读回与会话保护

- 保留 PID、启动时间、Core generation、hostLife、zone、角色指纹、完整快照令牌、nonce、期限和双读所有权检查；在游戏线程内执行一次。已有 16 项 nonce 缓存不变。
- `HostInventorySnapshot` 内部保存已经双读一致的 22 个原生装备索引和 handle；它们纳入不透明快照令牌。HINV v3 和请求/结果 v1 的共享内存布局均不变。
- 非空护符槽必须能在完整当前库存中匹配索引、handle、equipmentMask、类别和合法参数。坏索引、缺失条目、参数不可读都拒绝，不能当作空槽忽略。
- 卸下成功必须从原生读回同时确认 `index=UINT32_MAX` 和 `handle=0`。仅仅在展示条目中找不到目标槽不算成功。读回不完整或不符返回 `READBACK_UNKNOWN=-50`，不自动重试。
- 同物品/同系冲突返回 `CONFLICT=-52`；容量不足或未知仍返回 `SLOT=-48`。
- 发布的写入能力由同一轮真实护符容量派生；发布器还会裁剪超出该容量的位。4 槽时最大掩码 `0x1EF000`，容量未知时最多仅保留防具 `0xF000`。Java 页面需用两段 16-bit ContainerData 传递此掩码。

## 离线验证与尚未验证

- `python tools/test_host_equipment.py`：完整 EXE SHA、原有及新增原生签名、护符空槽 ABI；生产 Access/Mailbox 在私有内存中覆盖装配、替换、卸下、重复实例/同 ID 副本/同系拒绝、group=-1、容量未知/越界/中途改变、参数中途改变/缺失、坏占用映射、残留 handle 的 unknown、所有新增签名失配拒绝，并保留防具和 nonce 回归。
- `python tools/test_host_inventory.py`：8192 项完整库存、四个高索引护符、不可见原生槽变化也改变 token、掩码按容量裁剪、双读和生命周期/2 Hz 发布回归。
- 本地日志：`runtime/experiments/talisman-native-check.log`、`runtime/experiments/talisman-inventory-check.log`。新增 C++ publisher 数据：`native-inventory-wire-v3.bin.talismans` 与 `.talisman-unknown`，供实际 Java codec 联测。
- fixture 的 Invoke 只更改私有测试内存以模拟读回，不调用实际游戏函数。不能将这些结果记为实机通过。

集中验证时，先在安全固定地面完成已有防具闭环，再用自己已有的两件不同系列护符完成“空槽装配 → 同槽替换 → 卸下 → 法环菜单核对负重/效果 → 退出重进保持”。另用同系列不同档护符确认拒绝且原装配不变；如当前已解锁 4 槽，可特别确认第 4 槽的能力位未被容器 16-bit 传输截断。没有合适的冲突样本可记录该项待测，不发放测试护符。
