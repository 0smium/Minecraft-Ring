# 2026-10-12：返回 Core 基线，第九轮

状态：Core-only 首次接管静止 60 秒未死亡（用户），落地修复本项复验通过。第九轮平台下行仍失败：开头、中段和末段均抖动、悬空或坠落，中后段较严重。已构建第十轮碰撞时序候选修复，等待现场复测。关卡 0 仍未通过。此前两轮退出恢复与创造悬停 120 秒通过；平台飞行、三次相同攻击与末影珍珠基线仍未执行。此前结果见 [关卡 0 记录](20261011-core-gate0.md)，提前进行的 LB 界面预检及录屏见 [LB UI 记录](20261011-lb-ui-preflight.md)。

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

## 第九轮平台结果与第十轮候选

用户完成安塞尔河入口井下行，反馈全流程仍有异常、中后段较严重。本轮 MC 日志有 30 条 `[platform-trace] position packet reset`。首条 00:11:26 / client tick 3193，既有 epoch `2461660`，`renderCarry=true / carriedY=-0.0625`；00:11:27 tick 3215 收到纠正后，同 tick START 出现 `epoch 0 -> 2461660 / carry=0 / landed=false`。00:11:30.329 native 才 lost feet gap、ack=0。位置包处理清掉乘坐状态先于这次 native 支撑丢失，后续出现 attachment height gap；不能据此宣称每一条位置包的服务端发送分支都已取证。

实际 MC 1.21.1 `ServerGamePacketListenerImpl` 字节码保存为 `runtime/experiments/platform-server-listener-bytecode.txt`：`handleMovePlayer` 先确保服务器线程，之后按 `requestY-lastGoodY` 调用玩家 move，随后验证新碰撞；创造模式仍可能因新碰撞发送纠正。桥接原先在 END_SERVER_TICK 更新地面并再次直接 setPos 玩家，而客户端在 START_CLIENT_TICK 已携带并发送移动包，两端可能使用不同地面快照，服务器额外 setPos 也不更新 listener 的 lastGoodY。

候选代码提交 `c7d0f528c878c600270085f4bbc4a766dce97bd5`，只改 Fabric：

- 在正常 `handleMovePlayer` 的 `ServerPlayer.move` 调用前刷新最新已验证平台碰撞面。此处已经过主线程、非法包、等待传送、乘客等早退；helper 另检查 bridge overworld、有效映射/区域、recall 稳定、活角色/MC 接管、非 busy/dead、同 epoch 既有接触、移动窗口和足迹距离。独立 snapshot 不覆盖常规 STATE，不重复消费 Motion travel。
- 去掉 END_SERVER_TICK 的服务器玩家额外 setPos/onGround/fallDistance 分支。玩家由客户端携带，正常移动包经 vanilla 处理位置、落地、摔伤和 lastGood 坐标；服务器仍跟踪平台并更新地面。没有忽略任何纠正包、放宽支撑阈值或修改 native 死亡逻辑。
- 保留现有 1/16 格碰撞高度。该量化本身可能留下阶梯感；本轮先验证反复纠正是否消失和实际站立是否稳定，不提前声称连续平台问题彻底解决。生存平台摔伤与飞行脱离仍需现场验证。

参考原版活塞的移动碰撞体和载具的乘坐偏移，但活塞短行程限幅、载具骑乘输入与自由站立不同。查阅 [Create 官方 ContraptionCollider](https://github.com/Creators-of-Create/Create/blob/7ff2760e4924d35d4bc2e22c4b19923f0178cd16/src/main/java/com/simibubi/create/content/contraptions/ContraptionCollider.java) 固定提交：连续平台位移、表面接触和双端同步可借鉴；直接安装 Create 不会识别法环平台。本候选没有引入 Create，也没有复制其额外网络包方案。

Fabric `build` 退出 0（26 秒）；现有 moving-platform 和 stable-terrain 回归退出 0。日志 `runtime/experiments/fabric-platform-packet-fix-build.log`、`test-platform-packet-fix.log`、`test-stable-terrain-packet-fix.log`。Fabric JAR SHA-256 为 `E0E6795E87C5D515D8058A1A932E6D1C4592C1C66A59BA78C68B205CD7DB29CE`。额外用本地实际 MC 1.21.1 `Shapes` 做几何探针（JShell exit 0）：旧地板阻挡下降并触发 new-collision，及时更新地板后允许 -0.375 位移；普通静态落地和侧墙阻挡仍有效，六项通过。输入/输出为 `runtime/experiments/platform-packet-geometry.jsh`、`platform-packet-geometry.log`，未新增测试框架。此探针验证碰撞时序机制，不能替代 live hook 执行或平台完整上下行。

用户退出 MC 后，确认 PID `36000` 已不存在；ER PID `22560` 保持运行。只通过 `start.bat` 重启 Core `runClient` 载入候选，不更新 DLL，不重跑安装器。第十轮启动日志为 `runtime/experiments/core-launch-10-platform-packet-fix.log`，启动时 HEAD 为上述 `c7d0f52`。MC PID `25244`：00:23:01 自动进入 ER Bridge，00:23:06 Steve 加入，没有阻塞 Mixin 注入错误；快照 `core_status=1 / live=true / player_valid=true / camera_override=true / compositing=true`。已请求一趟下行，实际碰撞刷新 hook 日志和用户结果待反馈，尚未声称平台修复通过。

## 本轮人工复验

1. 在标题确认 App `1.17.1`，选择 Continue，等待 MC 自动进入 ER Bridge 并接管。
2. 在安全位置保持 MC 控制，静止 60 秒；期间不 F8、不飞行、不进行平台测试。记录是否自行死亡，不能用此前已经 F8 交接过的 LB 运行替代首次加载验证。
3. 若稳定，再在安塞尔河入口井创造模式关闭飞行，站平台中央、不走跳，完整下行一次并保持游戏运行取日志。关注现有 `[platform-trace] position packet reset` 与支持失联的时间顺序；尚无新证据时不忽略位置纠正包或放宽支撑阈值。

## 证据路径

- `runtime/experiments/core-launch-09-core-landing.log`、`core-launch-09b-core-landing.log`：两次启动早退。
- `runtime/experiments/core-launch-09c-core-landing.log`：成功启动，持续记录本轮 Core `runClient`。
- `runtime/experiments/core-09-startup-native.log`、`core-09-startup-minecraft.log`、`core-09-startup-status.json`：人工操作请求时的启动快照。
- `runtime/experiments/core-09-landing-native.log`、`core-09-landing-minecraft.log`、`core-09-landing-status.json`：用户报告 60 秒未死亡时的日志与连接快照。
- `runtime/experiments/core-09-platform-native.log`、`core-09-platform-minecraft.log`、`core-09-platform-status.json`：用户报告完整下行异常时的快照。
- `runtime/experiments/core-09-final-native.log`、`core-09-final-minecraft.log`：仅退出 MC 后、本轮重启前的日志。
- `runtime/experiments/core-10-startup-native.log`、`core-10-startup-minecraft.log`、`core-10-startup-status.json`：候选修复重启后的连接快照。
- `runtime/er-bridge.log`、`bridge-base/elden-ring/mc-bridge/run/logs/latest.log`：本轮运行日志，人工反馈时另存快照。

日志、视频、JAR、游戏文件与存档不提交；仅 Markdown 与必要代码提交到 `dev/lb-poc`，不推送 upstream、不自动合并 main。
