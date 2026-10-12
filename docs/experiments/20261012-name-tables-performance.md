# 2026-10-12：名称表接入与持续低帧率定位

状态：名称读取链已实现、构建并部署；驱动程序项的 60 FPS 限制已由用户改为 120，重启后标题实际约 120 FPS。用户反馈稍有改善、仍感觉卡，普通怪仍显示编号。随后全屏切换暴露宿主深度追踪容量耗尽，木板穿墙；第17轮修复后人工确认遮挡与标记恢复，继续局部性能工作。具名角色标签尚无人工验证，普通怪缺少名称 ID 时保留编号，不假造中文名。

## 基线、实现与制品

- 分支 `dev/lb-poc`，本轮起点 `073e6df272443158da1933e627f559648a89ea79`。原始 Bridge 基线 `711015afa67ab25c7b9597c68038718d1cef322e`；第16轮名称与MC帧预算对应实际实现提交 `bfa28c84bfcf99e5acb796dbc91e8158ccfb4863`，当时构建部署后再提交。
- ER App `1.17.1` / exe `2.7.1.0`，固定 LB v0.22.0 / Build #3530 / 参考提交 `e5a0dbf5458b063d3028e69e04762b8b25b998b5`，原 JAR SHA-256 `1824011D91046F8F8B388CFA63782F38CDF754F12CB997317EAB8E69E67EF20D`。MC `1.21.1` / Loader `0.19.5` / API `0.116.17+1.21.1` / FLK `1.13.0+kotlin.2.1.0`。
- 新 Core SHA-256 `13DF34AD9558D4733978D535966BA079732D772E1BF7F148242F2E54A874E161`，Fabric JAR SHA-256 `EF689E1FE5761880027ABE9169615C1B955311D2E01B2A8CC958244BE0647EEF`。native 与 Fabric build 均通过，日志 `runtime/experiments/native-name-table-build.log` / `fabric-highfps-build.log`。

## 名称：真实 ID 到当前语言文本

当前实体模型编号、NpcParam 行号和名称文本 ID 是三个不同编号。此前 `o.name` 直接格式化 `c####`；本次通过实际 NpcParam 表索引 **6**、行内 `nameId +0x0C`，依次查当前语言 NPCName FMG **18 / 328 / 428**，发布 UTF-8 名称给原有 MC 代理。LB 继续使用 CustomName，不改选敌、HP、实体 ID 或攻击路径。

七组独立目标指令与本机 exe/live 一致，包含仓库索引、holder stride、nameId 读取、正 ID 判断、base/DLC 分类及 MsgRepository/FMG 布局。可选 gate 任一失败即关闭名称读取、保留模型编号。PARAM 版本核对为 9；不使用 PARAMDEF XML 的 Index64 作运行时索引。

只读正样例：NpcParam `20100000 → nameId 121600 → “半狼”布莱泽`；`20300000 → 120000 → “满月女王”蕾娜菈`。当前场景小怪 3350/3450/3460/3661 等 nameId 为 0，目标代码也仅接受正 nameId；这些继续显示 `c####` 是数据缺失回退。Boss 的事件/血条名覆盖和目标辅助 `0x77` 表尚未接入，不声称所有 Boss、区域变种和普通怪都有正式名。

名称字段沿原协议 48 字节，按完整 UTF-16 scalar 转 UTF-8，最多 47 字节且不切半个中文/emoji。256 条正负缓存随实际 PARAM/MsgRepository/FMG 资源身份和加载阶段清理，2 秒过期处理同地址重建；不按每帧重扫名称表。

复核发现旧通用 `param_row` 会检查完整 7045 行目录，每个类型/刷新约 331 次 RPM。本次名称使用专用逐项二分读取，最多约 13 个目录项，再读四字节 nameId；旧 combat 查询契约保留，避免引入同步刷新突发。溢出或坏读直接回退编号。

证据：`runtime/experiments/name-research/target-name-field-verification.json` 与该目录中的只读脚本/反汇编。固定参考为 [fromsoftware-rs 仓库索引](https://github.com/vswarte/fromsoftware-rs/blob/59fbd3b3b7daaf14aca47c9f73530493dba6bc79/crates/eldenring/src/cs/solo_param_repository.rs)、[NpcParam 定义](https://github.com/vswarte/fromsoftware-rs/blob/59fbd3b3b7daaf14aca47c9f73530493dba6bc79/tools/param-generator/params/eldenring/NpcParam.xml)、[Smithbox FMG 分类](https://github.com/vawser/Smithbox/blob/cbd477a8fd6d436b3e011c8548e1de8fd8876918/src/Smithbox.Data/Assets/FMG/ER/Fmg%20Descriptor%20Registry.json)。参考本身不替代本机验证。

## 性能取证：不是传输积压

用户描述为“持续低帧率、不够流畅”，没有要求增加画质或换版本。硬件读取为 Ryzen 9 8945HX、32 GiB RAM、RTX 5070 Laptop GPU；实际游戏窗口 1600×900、现有 LOW/RT关闭，未盲目降低画质。

| 取证 | 结果与边界 |
| --- | --- |
| 08:43–48 五分钟日志 | MC 渲染约 69–89 FPS，捕获/发布约 44–60；pending/host-buffer skips 均 0。重复 host frame 会主动不捕获，不能把差值全当丢帧 |
| 同期 native | 平均约 54 FPS；bridge task 约 1 ms，合成 CPU约 0.21 ms。QPC 不覆盖完整异步 GPU 工作，且 copy/wait 有包含关系，不相加当总 GPU 成本 |
| 20 秒 GPU/进程采样 | GPU利用率中位32%（26–43），VRAM约4.9/8.15GiB，约45W；ER约5.78核、MC约0.66核。非严格场景控制，不据此判定唯一CPU/GPU根因 |
| 20 秒 JFR | Minimap链167/889 Java样本；render frustum97/423；服务器tick均值约4.11ms。样本计数有重叠，不当wall-clock百分比；GC最大暂停约41ms不足以解释持续低FPS |
| F8 静止对照 | MC前段FPS中位59.5；ER控制段59.9；回MC段59.95。切换边缘排除，静止场景不能代表移动采样或激烈战斗 |

LB 小地图是明确的额外负担：本世界 height4064/minY−2032，固定版 calculateHeight 从 getHeight−1 而非真实 topY 开始，空/CLEAR 列可能扫描约6095层；碰撞代理没有有意义的地图材质色。关闭 Minimap 子项只停止绘制，HUD 的后台订阅仍扫描，所以未把简单关子项误报为消除该热点。高度不能直接缩小，后续可局部门控扫描或改为正确高度/表面查询。

其他已定位局部项：大竖直 section 数放大 renderDistance12 的原版裁剪；TargetStrafe 也有移动模拟样本；内部 DevCommands 每20Hz检查缺文件、主题和closed websocket异常有额外工作。没有证据把它们单独说成这次60上限根因，也没有批量关闭用户模块。

取证放在 `runtime/experiments/perf-16-{native-findings,minecraft-findings,gpu-samples,process-samples,ab-findings,framepacer-readonly,game-monitor-power-readonly,fps-unlock-compatibility}.*`；JFR 已结束，无持续录制/heapdump。游戏帧率统计与实际 Present 间隔同时核对，ER 控制段 300 Present约5.01秒，即约60Hz。

## FPS 解锁源码、代价与这次真正的 60 限制

用户要求搜索原作者补丁。核对 [uberhalit FPSUnlockAndMore](https://github.com/uberhalit/EldenRingFpsUnlockAndMore) 与 [UnlockTheFps 固定源码](https://github.com/a492219408/EldenRing-UnlockTheFps/blob/4785ee08e6be242d6a25a78ed4be8551180f18b0/src/patch.rs)。后者修改帧间隔 `1/targetFPS`、独占全屏60Hz fallback，并以额外DXGI hook选择高刷新模式；作者[兼容记录](https://github.com/a492219408/EldenRing-UnlockTheFps/blob/4785ee08e6be242d6a25a78ed4be8551180f18b0/docs/compatibility.md)明确至2.7.0.0，本机2.7.1.0另外只读核对两处签名均唯一匹配。

Core 的原144补丁不仅字节还在，实际 CSFlipperImp 对象的 +0x1C就是1/144；窗口所在物理屏幕165Hz、AC接电。不是误落到60Hz虚拟显示或补丁错过初始化。Core Present已传syncInterval0，当前是窗口模式；没有理由叠装一个处理同样位置及全屏路径的新DLL。

用户随后检查并明确 **eldenring.exe 程序项的 NVIDIA 最高帧率为60**，按具体步骤改为120并回复“已改120”。重启后标题Present/host约120，外层限制已取得实际改变支持；场景内上限与低帧还需观察。

代价与范围：提高帧率会增加每秒CPU/GPU渲染工作，60→120把单帧预算从16.7ms降为8.3ms，不保证任何场景都达到120。显示同步设置可能造成撕裂或刷新率限制；叠加DXGI hook有额外兼容成本。原作者的全局GameSpeed修改是另一功能，不把其过场崩溃警告套到纯FPS解锁，也不宣称已证明120会破坏物理/动画。我们继续现有离线流程，不修改全局timescale、不安装新FPS DLL/ME3、不改驱动全局项。

## 本轮部署与高帧率图层配套

用户完整退出后保留旧 DLL/JAR、日志和 options：`core-before-16-names.dll`、`fabric-before-16-highfps.jar`、`core-15-final-before-16-{native,minecraft}.log`、`mc-options-before-16.txt`。只更新实际 Game/erbridge/erbridge_core.dll，未重跑安装器；installation.json 哈希仍 `8551CE1521C33E08CBFE909CAA5BC7A92AF9369E1FAD591AA1789071CFFDF3C2`。

原 MC 自适应预算上限96会成为120Hz宿主图层瓶颈。仅把预算上限扩到180，保留 hostFPS×1.4+6 的相位余量与每秒±3平滑；60Hz宿主仍取约90，不默认强制180。运行实例 maxFps120→180、enableVsync true→false，其他 MC 设置保留，使不可见输入窗口不额外等显示同步；法环最终呈现仍由现有链处理。该改动不能让硬件凭空达到目标，验收看实际host/捕获/发布和体感。

启动 `start.bat -LiquidBounce`，日志 `runtime/experiments/core-launch-16-names-highfps.log`，启动器session98845，ER PID44940、MC PID42156；固定LB optional适配仍正常开启。09:17:53起标题连续约119.8–120.1 FPS；09:18:14 Steve自动加入，ER尚在标题，所以此时captured=0不算传输失败。随后请用户Continue后静止30秒/走动转镜头；有具名NPC可F9观察，不要求专门跑图。

用户反馈“好像改善一点了”，但仍感觉卡；F9 和 LB Nametags 看普通怪仍是 c3661/c3451，与只读 nameId=0 结果一致，未取得具名 NPC 的正向视觉样本。09:32–33 场景 host 约66.8–70 FPS，MC rendered约98–105、captured/published约67–73，skips0；用户期间切换独占全屏、分辨率和F8，不能当固定场景性能对照，也未达到稳定120。

用户在独占全屏后第一次F8出现HUD但控制仍在ER，重切后自行恢复；该输入现象记录为待定位。之后明确报告“MC木板穿过法环墙壁”及ESP等不显示，处理记录见[全屏后的深度丢失](20261012-fullscreen-depth.md)。09:24后宿主深度缺失，后段帧率省去了有效遮挡工作，不能据此验收正确合成性能。

LB Minimap后台扫描候选在第17轮启动器自动重构建误带入后，暴露辅助类位于Mixin保留包的类加载错误；正常退出MC并隔离。后续将helper移出保留包，第18b实际共载时已确认Minimap关闭后后台扫描暂停，用户反馈流畅度明显改善，见[小地图与鼠标记录](20261012-minimap-focus-performance.md)。没有修改视距；非固定场景FPS不写收益百分比。

FPS补丁源码资料、日志、GPU/JFR/配置、游戏文件都不提交Git；只提交自有桥接源码和Markdown。
