# 2026-10-12：返回 Core 基线，第九轮

状态：Core-only 首次接管静止 60 秒未死亡（用户），落地修复本项复验通过。关卡 0 仍未通过，已请求一次平台下行取证。此前两轮退出恢复与创造悬停 120 秒通过；平台站立失败，平台飞行、三次相同攻击与末影珍珠基线仍未执行。此前结果见 [关卡 0 记录](20261011-core-gate0.md)，提前进行的 LB 界面预检及录屏见 [LB UI 记录](20261011-lb-ui-preflight.md)。

## 实际版本与配置

- 启动时项目提交：`3daea0922ca60c81579cfb8974506ea6567a492c`，分支 `dev/lb-poc`。首次接管落地等待代码为 `76eb64fa4e09be500c5823241e6b50991785015e`；本轮不新增运动或死亡修复。
- 配置 A：执行 `start.bat`，无 `-LiquidBounce`，使用 Gradle `runClient`。00:07:07 本轮 Fabric 列表为 56 个模组，包含 erbridge `0.3.0` / Fabric API `0.116.17+1.21.1` / Loader `0.19.5`，没有 LiquidBounce、MCEF 或 FLK；Java `25`，MC `1.21.1`。LB 制品、配置和浏览器缓存留在本地，未参与本轮加载。
- 实际 `eldenring.exe` 版本再次读取为 `2.7.1.0`；App 上轮用户确认 `1.17.1`，本轮标题画面待确认。
- 实际游戏目录：`D:\Steam\steamapps\common\ELDEN RING\Game`。
- 实际安装源及加载副本 `Game/erbridge/loaded/core_22560_1.dll` SHA-256 均为 `394F1B4E7CD5C036CF2F591F2ED62978927AF9EE8C0FD6BA3AB98C892975604A`。00:06:30 日志确认 read-only fall diagnostics enabled、core generation 1 loaded (ok)。
- `installation.json` SHA-256 仍为 `8551CE1521C33E08CBFE909CAA5BC7A92AF9369E1FAD591AA1789071CFFDF3C2`；原始备份 `backups/20261011-095120` 保留，未重跑安装器。

## 操作与已观察结果

1. 用户反馈上一轮已退出；本轮启动前确认 ER 和 Java 进程均不存在。上一轮最终原生及 MC 日志保存为 `runtime/experiments/core-08-final-native.log`、`core-08-final-minecraft.log`。用户这次只回复退出完成，不能据此另外声称角色恢复检查通过。
2. Steam 原先未运行，启动 Steam 后两次尝试 ER 很快退出，MC 未启动，原生日志只到 loader attached / shm mapped，没有初始化 Core。随后 Steam 完成登录，再次通过相同 `start.bat` 启动成功；未观察到新的 WER 崩溃记录。启动时序与 Steam 准备过程吻合，但未取得两个退出的调用栈，不把 Steam 未就绪写成已证明根因。
3. 第三次尝试 ER PID `22560`，实际加载落地等待修复版；MC PID `36000`。00:07:20 自动打开 ER Bridge、00:07:23 Steve 加入，00:07:25 Overlay ON。标题阶段只读状态为 `core_status=1`、`live=true`、`player_valid=false`、`compositing=false`，尚未接管角色。人工 Continue / 接管结果待反馈。
4. 用户按首次接管静止 60 秒的请求回复“没死”。原生日志 00:07:39 loaded 时 `ground=0`，00:07:41.306 `ground=1 / fallTimer=0`，00:07:41.364 settling→alive，00:07:41.979 才由 Steve 接管。00:07:42–00:08:23 的只读 life-trace 样本均为 HP `832/832`、`ground=1`、`fallTimer=0`、`MC=1`；诊断窗口结束后不能据无采样宣称 timer 持续为零。60 秒无死亡结论来自用户本轮操作反馈。本轮快照仍为 `player_valid=true / camera_override=true / compositing=true`。
5. 快照后段有用户继续游玩产生的若干受伤转发日志，但未固定同一敌人、同一招式、回血站位或实际 MC HP，因此不计入关卡 0 的三次受击基线。

## 本轮人工复验

1. 在标题确认 App `1.17.1`，选择 Continue，等待 MC 自动进入 ER Bridge 并接管。
2. 在安全位置保持 MC 控制，静止 60 秒；期间不 F8、不飞行、不进行平台测试。记录是否自行死亡，不能用此前已经 F8 交接过的 LB 运行替代首次加载验证。
3. 若稳定，再在安塞尔河入口井创造模式关闭飞行，站平台中央、不走跳，完整下行一次并保持游戏运行取日志。关注现有 `[platform-trace] position packet reset` 与支持失联的时间顺序；尚无新证据时不忽略位置纠正包或放宽支撑阈值。

## 证据路径

- `runtime/experiments/core-launch-09-core-landing.log`、`core-launch-09b-core-landing.log`：两次启动早退。
- `runtime/experiments/core-launch-09c-core-landing.log`：成功启动，持续记录本轮 Core `runClient`。
- `runtime/experiments/core-09-startup-native.log`、`core-09-startup-minecraft.log`、`core-09-startup-status.json`：人工操作请求时的启动快照。
- `runtime/experiments/core-09-landing-native.log`、`core-09-landing-minecraft.log`、`core-09-landing-status.json`：用户报告 60 秒未死亡时的日志与连接快照。
- `runtime/er-bridge.log`、`bridge-base/elden-ring/mc-bridge/run/logs/latest.log`：本轮运行日志，人工反馈时另存快照。

日志、视频、JAR、游戏文件与存档不提交；仅 Markdown 与必要代码提交到 `dev/lb-poc`，不推送 upstream、不自动合并 main。
