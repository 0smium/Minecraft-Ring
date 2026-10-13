# 本地测试卢恩

用于 B01 的真实升级测试：先补测试货币，再在赐福正常升级，让现有等级观察和技能点逻辑接收真实变化。工具不改等级或 MC 技能点，不启动游戏。

只支持已核对的 App 1.17.1 / `eldenring.exe` 2.7.1.0，完整 EXE SHA-256 为 `1a3547101327f65d0c76da2f9190ac0aa66871ea42bae2aecc61e11a8b597891`。需要本轮配套原生 DLL、Python 3 和 `psutil`；专项磁盘检查另使用 `pefile`。当前 Windows 环境已有依赖。

## 操作

1. **法环完全关闭时备份。** 默认查找 `%APPDATA%/EldenRing/*/ER0000.sl2`，只有一个账号时可省略路径；多个账号必须明确当前 Steam 账号对应文件。

   ```powershell
   python tools/grant_test_runes.py --backup-only --save-file "$env:APPDATA\EldenRing\<SteamID64>\ER0000.sl2"
   ```

   复制主存档及存在的 `.bak`，校验摘要，输出 `runtime/backups/test-runes-*/manifest.json`。复制期间锁定文件禁止写入；发现 ER 已运行或复制中启动时，不生成可用于发放的 manifest。保留原文件，不做还原。

2. 配套部署后按项目离线流程启动，进入存档，在安全地点保持角色可用。工具默认只查余额：

   ```powershell
   python tools/grant_test_runes.py
   ```

3. 指定本轮备份和正数金额才发放，例如准备 200 万测试卢恩：

   ```powershell
   python tools/grant_test_runes.py --amount 2000000 --backup "<上一步的manifest.json>" --save-file "$env:APPDATA\EldenRing\<SteamID64>\ER0000.sl2"
   ```

   备份须在当前 ER 进程启动之前且不超过 24 小时，文件路径、长度和摘要必须匹配；超过时限则下一次关闭游戏后重新准备。单次最多 500 万，不能超过游戏余额上限。工具先查询，再以该身份、等级和余额提交一次发放；输出前后余额、实际增量和日志路径。

4. 在法环角色菜单核对卢恩，在赐福正常升一级，再回 MC 检查一次发点、技能购买与退款。真实操作未发生前，不把该链路标为通过。

## 实现与故障边界

专用 mailbox 命令 `11` 接收固定字段，不接收内存地址，既有共享内存块不移动。游戏线程调用已核对的 `PlayerGameData` 原生增减函数（RVA `0x25E0E0`），保留累计卢恩和饱和标志处理；不是裸写余额。固定参考布局见 [fromsoftware-rs](https://github.com/vswarte/fromsoftware-rs/blob/59fbd3b3b7daaf14aca47c9f73530493dba6bc79/crates/eldenring/src/cs/player_game_data.rs)，函数、owner 调用点与上限常量另经当前 EXE 磁盘字节核对。

执行前复核代码签名、进程/核心会话、生命期、角色弱指纹、等级、余额与截止时间；加载/死亡时拒绝。查询金额为零，不调用增卢恩函数。仅在明确 `--amount` 时才可能发放，不设自动发点或游戏启动奖励。

发放请求在允许时间窗内保留至多 16 条结果，查询不能驱逐未过期的发放；相同 nonce 与载荷返回原结果，同 nonce 改载荷拒绝，缓存满则拒绝新发放。CLI 请求有效期 3 秒，过期不迟到执行，超时或读回不一致不自动重发，也不使用原始 WRITE 保底。遇到未知结果先核对余额及日志，不按错误提示再连点发放。

日志位于 `runtime/experiments/test-runes/*.jsonl` 和原生日志。备份、账号路径、存档、共享内存和日志均不提交 Git。关闭游戏的备份已在本机执行；实际发放仍待下一批实机检查，见[集成记录](experiments/2026-10-13-host-test-readiness.md)。
