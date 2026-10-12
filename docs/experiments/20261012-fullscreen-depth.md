# 2026-10-12：全屏切换后的宿主深度丢失

状态：深度资源修复已构建、部署并通过本轮人工遮挡/标记检查；切换后需要额外点击的焦点交接仍待处理。用户明确原故障为“MC木板穿过法环墙壁”。

## 运行基线与观察

- 分支 `dev/lb-poc`，工作起点 `073e6df272443158da1933e627f559648a89ea79` 加未提交名称读取与MC帧预算上限修改；部署版本见[名称和性能记录](20261012-name-tables-performance.md)。
- 实际第17轮实现提交 `01aaeee2105b11defa207951a674fe26abb789be`，其父提交 `bfa28c84bfcf99e5acb796dbc91e8158ccfb4863` 包含名称读取与MC帧预算。先构建部署，再提交这份实现；日志制品按SHA匹配。
- 本轮 ER PID44940，MC PID42156；App1.17.1/exe2.7.1.0、MC1.21.1、固定LB0.22.0/3530，原JAR SHA-256 `1824011D91046F8F8B388CFA63782F38CDF754F12CB997317EAB8E69E67EF20D`。
- 用户改 NVIDIA eldenring.exe 程序项最高帧率60→120后，游戏期间多次切独占全屏/窗口及1600×900、2560×1440，随后发现木板穿墙。具体切换次数没有人工统计。
- 游戏日志与MC捕获仍连续，没有GPU共享重建失败；不能仅由HUD正常推断深度正常。

## 两个问题分别定位

**木板穿墙。** `depth_tracking.h` 将发现的深度资源 AddRef 放入最多32项的数组，仅 shutdown 释放；resize释放的是合成拷贝、旧候选和back buffers，没有清理实际追踪表。当前进程的32条 `depth: tracking` 在09:23:57达到容量，最后一次occlusion enabled为09:23:59；日志截至09:45统计共21次 `swapchain resizing`，最后09:31:50，但容量达到32后不再登记新资源。旧条目超过2秒不再可用，`depth_snapshot`失败使`useDepth=0`，法环墙壁不遮挡MC世界层。这与用户现象及日志时序一致，修复应处理资源生命周期而非关闭遮挡。

**ESP不显示。** 只读API查到当前ESP启用但Mode=Outline、ColorMode=Rainbow，Nametags及Tracers关闭。已适配的是Box；代理不绘制真实ER模型，Outline/Glow没有可描边的MC网格。没有发现LB世界渲染监听因resize失效的证据。09:41通过固定LB本地API恢复Box/Static并开启Nametags、Tracers，随后GET核对三项均启用；实际画面恢复仍需用户观察，不写为通过。其余模块和参数保留，前配置已保存。

## 修复与验证边界

原生候选已实现：resize在成功GPU idle后清追踪表及旧command-list记录，重复resize即使尚无back buffers也清理。深度快照在锁内AddRef，提交后由RingSlot持有到fence完成；合成与resize资源事务串行，同线程嵌套Resize1→Resize及递归Present不会重入清理。保持原深度格式、投影、遮挡算法和游戏设置；不加入第三方FPS DLL、不禁用死亡/物理逻辑。

`python tools/build_native.py`通过，Core SHA-256 `D33C8B851996894D052E39571754A215FE720E9D51BA633AEF0B8CE20D7DE323`，日志 `runtime/experiments/native-fullscreen-depth-build.log`。独立源码复核未发现新增阻塞引用/锁问题。`python tools/test_native_depth_resize.py`使用真实tracker和真实compositor函数、CPU D3D stubs，通过48次重建、旧记录失效、快照跨reset到fence的引用、等待超时保留及嵌套Resize。它验证资源契约，不替代真实GPU与人工画面观察；日志 `runtime/experiments/native-depth-resize-tools-test.log`。若GPU idle超时则保持原资源、拒绝本次resize，shutdown超时保留引用而非释放在途资源；异常关闭路径可能保留COM资源，未把它写成日常已观察现象。

构建通过后只安排一次验证：重启后看法环墙是否遮住MC木板；切换一次窗口/全屏、回到MC控制后再看同一遮挡和Box/标签。未观察到的结果保留待验证，不追加战斗或平台测试。

## 第17轮部署

用户回复“已退出”，确认ER与MC已关闭后，备份 `core-before-17-fullscreen.dll`、`core-16-final-before-17-{native,minecraft}.log`、`mc-options-before-17.txt`。更新实际 `Game/erbridge/erbridge_core.dll` 并核对上述D33C8B85哈希；installation.json仍为 `8551CE1521C33E08CBFE909CAA5BC7A92AF9369E1FAD591AA1789071CFFDF3C2`，未重跑安装器。

首次17a通过start.bat启动时，启动器自动remapJar，将尚未打算部署的Minimap候选也构建进去；辅助类处于Mixin保留包，出现IllegalClassLoadError。编译/离线注入通过不足以证明实际类加载可用。这是本次启动操作失误，不能算性能优化验证。通过LB自身 `/api/v1/client/exit` 调用scheduleStop正常退出MC，ER保持运行；将两份候选源移动到ignored的 `runtime/experiments/minimap-candidate-paused/` 并取消Mixin注册，再用start.bat启动17b。不修改游戏控制或强杀存档进程。17a制品哈希为 `0EB8DDAFA134D825C843F8CB0E24CE2DD247A49E9DB1DE9D61059FFDED9E16B3`，异常日志保留 `core-17a-unintended-minimap-{start,minecraft}.log`。

17b继续同一修正版ER PID39704，MC PID37264、启动器session54149，启动日志 `runtime/experiments/core-launch-17b-fullscreen-depth.log`。实际加载Core哈希与上述一致；Fabric恢复到 `EF689E1FE5761880027ABE9169615C1B955311D2E01B2A8CC958244BE0647EEF`，ZIP目录和Mixin名单确认没有Minimap候选。LB共载、ER Bridge连接、帧捕获/发布继续，09:55:07原生日志已有 `depth: occlusion enabled`。这轮不修改视距、驱动或游戏画质。

用户对“墙是否遮挡木板、Box/标签是否显示、切一次全屏后再看”回复“可以 显示”，本轮两项基本观察记录为通过，同时反馈“切换还是比较麻烦，还得切出来再点一下”。不据此宣布全屏焦点问题已解决。日志截至10:04:51已出现15次tracker reset，累计新登记108资源；10:02:58仍重新启用2560×1440的遮挡，GPU idle timeout0次。该证据跨过旧的累计32限制，但不替代更长时间稳定性测试；摘要 `runtime/experiments/render-17-verified-findings.json`。

17b有一次Scaffold `Direction must not be zero`（09:58:27），记录为局部碰撞面/选放置目标兼容差异，未扩展本次修复。没有再次出现Minimap类加载异常，也不声称所有LB模块无异常。

证据路径：`runtime/er-bridge.log`、`mc-bridge/run/logs/latest.log`、`runtime/experiments/render-16-depth-exhaustion-findings.json`、`render-16-current-native.log`、`render-16-control-readonly.json`、`render-16-{modules,ESP,Nametags,Tracers}-readonly.json`、`render-16-ESP-restored.json`、`render-16-visual-modules-restored.json`及`fullscreen-17-minecraft-findings.json`。只读稳定快照确认NO_DEPTH_TEST=false、DEBUG_DEPTH=false、debugFlags=0；不是用户主动关遮挡。日志、API配置快照和构建制品不提交Git。
