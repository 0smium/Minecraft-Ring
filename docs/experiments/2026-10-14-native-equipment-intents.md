# 原生防具装配/卸下候选（2026-10-14）

状态：代码与离线验证完成；没有启动游戏、没有部署 DLL、没有执行任何真实装备写入。实机装配、卸下、外观/负重/防御刷新及重新载入存档的保持情况仍待集中测试。此记录覆盖当前 `codex/b02-character-slice` 上由 `bd34acb` 后续开发的原生服务与 Core 传输部分；角色模组的页面和容器另行记录。

## 支持范围与原生证据

- App 1.17.1 / `eldenring.exe` 2.7.1.0；磁盘 SHA-256 `1a3547101327f65d0c76da2f9190ac0aa66871ea42bae2aecc61e11a8b597891`。
- 首版可写原生防具槽 12/13/14/15（头、身、手、腿），不能写武器、护符、道具或仓库。物品必须由当前本机宿主真实库存的有效索引、handle 和物品 ID 确认归属，防具的数量字段允许为 0。护符槽仅显示已开放数量；不发放皮袋、不自行解锁槽。
- 原生装配函数 RVA `0x249160`，完整 `0x1AC` 字节 FNV-1a64 `5A6372977F7AD099`；原生卸下函数 RVA `0x247160`，完整 `0x61B` 字节 FNV-1a64 `5B44F153BA58F3FF`。运行时验证完整函数及原生 UI 调用位置，失败即关闭装备写入。
- 定位起点：[The Grand Archives 固定提交的 equipItem 参考](https://github.com/The-Grand-Archives/Elden-Ring-CT-TGA/blob/7926205c5a2ed236dd31278c4f5579c964ceec35/CheatTable/CheatEntries/Open%20-%20The%20Grand%20Archives%20-%20Elden%20Ring/MiscWIP/Dependencies/Global%20Functions/equipItem_code.cea)。当前目标 EXE 唯一签名命中后，进一步检查游戏自己的 UI 调用链 `0x788E84..0x788EA3`：参数为 EquipGameData、装备槽、GaitemHandle 地址、库存全局索引、true/true/false。
- 卸下使用游戏自己的函数，游戏自行换上裸装占位并更新关联状态。裸装 ID 为 `0x10000000 + 10000 + (槽-12)*100`，来自目标代码 `0xD48F30`；不是向槽中裸写 `-1`，也不把裸装占位显示为可携带物品。
- 防具适配槽在原生端再次读取当前 `EquipParamProtector` v6：`protectorCategory` +0xD6，head/body/arm/leg 位 +0xDC。类型和单一装备位均必须对应目标槽。[Paramdex 定义](https://github.com/soulsmods/Paramdex/blob/master/ER/Defs/EquipParamProtector.xml)；参数表索引 1 的布局来源：[固定 fromsoftware-rs](https://github.com/vswarte/fromsoftware-rs/blob/59fbd3b3b7daaf14aca47c9f73530493dba6bc79/crates/eldenring/src/cs/solo_param_repository.rs)；所有字段为只读。

## 护符皮袋

目标代码 `0x247890..0x2478D9` 的实际公式是 `min(4, PlayerCommonParam[0].baseAccSlotSize + PlayerGameData.unlocked_talisman_slots)`。前者由参数表 144、行 0、+0x0D 读取；后者为当前 PlayerGameData +0xC6。此函数及参数表 getter 的目标字节也纳入运行时签名门控。基础值须为 1..4、额外数须为 0..3，任何读取失败发布 0（未知），页面应锁定未知槽。没有从当前已装备几件或库存中有几个皮袋反推解锁数。

## 传输与执行

- HINV v2 在原有 0x8040 字节之后附加 32 字节，增加内容令牌、zone、可写槽掩码、护符总槽数；Core 的 Java 读者也接受旧 HINV v1，但旧版没有写入能力。
- 内容令牌取自宿主指纹、容量、条目总数及全部已发布原生条目。不会每次采样自增。若条目被截断，写入关闭。
- 独立 gameplay mailbox 位于 `0x370000`，与 debug/rune mailbox 分离。请求 80 字节、结果 64 字节。请求含 PID、启动时间、Core generation、生命代次、角色指纹、zone、快照令牌、槽、条目索引/handle/ID、5 秒期限及 nonce。
- 仅在游戏线程执行，要求存活且 MC 正在接管、站地、未飞行。请求前两次重读原生库存与所有权，匹配后只调用一次游戏函数。调用后重新读取真实库存和装备映射，只有目标 handle/ID/index（或卸下后的原生裸装 ID）对应正确槽才返回成功。
- 调用后未能确认结果返回 `READBACK_UNKNOWN`，不自动重试。菜单刷新继续读取宿主实际快照。完成请求会要求库存 feed 立即重新采样，不伪造乐观库存。
- 保存 16 个未到期 nonce 的完整请求和结果；相同 nonce/相同参数返回原结果，参数冲突拒绝，缓存满则拒绝新请求，不能驱逐尚可重放的结果。切换生命周期的旧请求还会被会话/生命/zone 校验拒绝。
- 角色模组必须自行校验本机 owner 和当前 menu；Core API 不依赖角色模组、Skills、LB 或 Meteor。

## 验证

- `python tools/test_host_equipment.py`：磁盘 EXE SHA、装配/卸下完整函数哈希、实际 UI 调用及护符公式；生产 C++ Access/Mailbox 在私有内存 fixture 中覆盖版本、会话、生命、zone、物品、槽、快照、期限、装配/卸下读回、unknown、重复 nonce、冲突、缓存满及过期。
- `python tools/test_host_inventory.py`：生产库存 reader/publisher/feed，保留原有上限、槽位、双读、截断、失效、2Hz 限流覆盖。
- `python tools/test_inventory_client.py`：62 项实际 HINV Java codec 检查（含 v1 兼容与 v2 非法字段）及 20 项实际 equipment Java mailbox 检查。
- 测试中的 Invoke 只修改私有 fixture 模拟原生执行结果；没有调用实际游戏装备函数，不能据此标为实机通过。
- 本机证据与反汇编位于忽略目录 `runtime/experiments/equipment-research/`。测试日志位于 `runtime/experiments/equipment-native-check.log`、`equipment-inventory-check.log`、`equipment-java-check.log`。

下一轮只需在安全固定地面，用一件原本拥有的头盔或上衣完成：页面装配 → 法环菜单核对物品、负重和防御 → 页面卸下 → 法环再次核对 → 重新进入存档检查保持。若 native 读回为 unknown，先保留日志和新快照，不重复发送同一次操作来碰运气。
