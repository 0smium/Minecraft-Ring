# 2026-10-12：小地图后台扫描与鼠标交接

状态：Minimap门控已实际共载并生效，用户反馈“流畅度好很多了”；全屏回MC仍需回到桌面点一下，鼠标交接候选未解决该模式。帧率取样没有足够证据给出收益百分比。沿用户要求继续性能工作，不重新测试平台、战斗或完整属性映射。

## 起点与范围

起点 `fc8a74f3248d06d42a47efd8189f14dce79548a5`，第17b轮Core为 `01aaeee2105b11defa207951a674fe26abb789be`，实际加载SHA-256 `D33C8B851996894D052E39571754A215FE720E9D51BA633AEF0B8CE20D7DE323`；Fabric `EF689E1FE5761880027ABE9169615C1B955311D2E01B2A8CC958244BE0647EEF`。ER PID39704、MC PID37264，版本沿[名称/性能记录](20261012-name-tables-performance.md)，固定LB3530原JAR不变。用户已确认第17轮木板遮挡、敌人Box/标签可用，同时反馈F8回MC还需额外点击。

第18轮实际实现：Minimap门控 `31cb225d3d166c7a63b4f11f44e385ab03f194ad`，配套焦点/鼠标处理 `c8d2ab6b9329b48fb575ec29cc6fe20975690736`。先构建部署再提交；18b完整配套源对应后者，实际JAR见下方64E42623哈希。

第17b曾实际切到2560×1440，相对先前1600×900像素数2.56倍；后续20秒取样时共享尺寸已回1600×899，说明用户仍在切设置。性能比较需同位置、分辨率、控制状态和模块；未经控制的FPS不能当优化收益。保留renderDistance12、simulationDistance12；本轮先处理已在JFR出现的无用小地图扫描。

## Minimap局部候选

只在桥接世界且HUD的Minimap子项关闭时取消后台chunkUpdate/recordBlock；其它HUD组件继续，普通MC世界保持LB本来行为。桥接高度不能缩小。客户端tick用缓存MethodHandle读取开关，工作线程只读volatile gate；恢复子项或离开桥接时，在HUD仍拥有订阅的条件下用原LB清理/重新订阅重扫。

第17a轮意外带入的helper处于Mixin保留包，已记录类加载失败并隔离；本候选将helper移到父包 `dev.ermc.bridge.client.integrations`，Mixin仍在专用liquidbounce包，通过import调用。原JAR不改，Core-only仍不加载可选Mixin。

候选完整构建通过，SHA-256 `F808A3DC67223118938460AF1AA07D61C0B8EF4EAFEAB04852B620B0C254EFC3`，日志 `runtime/experiments/fabric-minimap-candidate-build.log`。普通JVM实际Class.forName(helper,true)及suppressed()调用通过；针对原始3530链接5个public MethodHandle和3个INSTANCE字段getter，3个实际Mixin注入点通过。**探针没有读取LB单例值、运行resolve或执行游戏tick**，不能当实际启动通过。日志 `lb-minimap-packagefix-{link,transformation}-probe.log`。

## F8返回后的鼠标

只读源码与本机MC1.21.1字节码确认：WindowsOverlay.showAboveHost无条件报成功；若初次focus尚未生效就关闭PauseScreen，grabMouse因window inactive返回。之后焦点获得不会自动补抓鼠标，存在需要点击的失败路径。当前只读前台快照已为MC，没有直接采到mouseGrabbed=false；将其作为与用户现象相符的待验证路径，不编造实时复现。

MC侧已实现：确认实际前台与window active，世界就绪、screen=null时补抓鼠标，成功后结束等待；首次ready起2秒、每100ms最多一次重试，切到第三方应用则取消。ClickGUI存在时不强抓鼠标，其F8原规则保留。没有追加native前台授权改动或ER重启。

完整Fabric构建通过，第18轮配套候选/实际JAR SHA-256 `64E426236513B6B4C50BA5688260001A66EEFE9C2FBCA68B168D4EBC03C86E7B`。`python tools/test_overlay_loading.py`使用实际新方法通过4组：原loading/heartbeat/F8/退出、延迟焦点/重抓/GUI/第三方取消/空前台/超时、原点击穿透/缓存、实际foreground分类；日志 `runtime/experiments/overlay-focus-regression.log`。可控API stub测试不替代本机焦点验证。

## 本轮记录

基线取样 `runtime/experiments/perf-18-before-minimap.json` 为20.01秒自动帧计数，host与MC发布均70.5FPS，共享尺寸1600×899，stalled intervals0、GPU buffers6、bridge flags79。用户没有专门静止配合，不作固定场景收益基线。

用户回复MC已退出。备份 `fabric-before-18-minimap-focus.jar`（原EF689E1F哈希）、`core-17b-final-before-18-minecraft.log`、`mc-options-before-18.txt`、`perf-18-hud-before.json`及`perf-18-modules-before.json`；HUD Minimap实际上原本已false，所以没有替用户改开关。用户问是否在HUD里关即可，说明固定3530关闭绘制未取消后台订阅，本次门控去除已取证扫描开销，并非扩展地图功能。

18a通过start.bat自动构建/加载上述64E42623制品，在10:21:18“Opening bridge world”处退出，process code−805306369/0xCFFFFFFF；用户确认未主动关闭。没有Java异常栈、hs_err或新crash-report，相关Windows事件未检出；退出后可用内存8.42GiB不足以证明或排除退出时内存原因。该退出保留原因未知，不猜测由Minimap/焦点引起，也不写启动通过。保存 `core-18a-exit-{launch,minecraft}.log`、`core-18-mc-exit-windows-events.json`。

限定同一制品重试一次，18b正常进入ER Bridge，MC PID46900、ER继续PID39704，启动器session95259，日志 `runtime/experiments/core-launch-18b-minimap-focus.log`。10:25:57出现 `LiquidBounce minimap background scan suspended: bridge component disabled`，GPU共享1600×899；Minimap仍false，后台门控实际生效，没有Minimap类加载/方法访问错误。一次只读Thread.print保存 `core-18b-thread-diagnostic.txt`，不是持续JFR。10:26:22焦点出现restored；启动时用户前台第三方应用的cancelled是防抢焦点路径，不算成功交接。

后采样 `perf-18-after-minimap.json`：20.01秒、host64.0FPS、MC发布63.4、共享1600×899、stalled0、GPU buffers6、flags591。期间已有F8和角色移动/飞行，区域也不同；不能由70.5→64.0下结论为改善或退化。只确认后台扫描取消与捕获继续，持续低FPS尚未解决。不继续无限增加优化补丁；后续应在固定场景先分别对照ER控制与MC控制、MC可见距离等再选具体热点。

18b另有GLFW `Invalid standard cursor 0x00000000`及JCEF默认OSR context menu错误，先记局部观察，未扩本次修复。没有把“门控生效”写成所有LB功能无异常。实际代码提交与人工F8/体感随后补录；配置、JAR、日志和录屏不提交Git。

用户最终回复：“全屏切换这个MC还是得回到桌面再点一下。流畅度好很多了。”因此门控运行状态与主观流畅度改善已有支持，未声称host固定120或帧率提升具体百分比；全屏无需点击的判据仍失败。保留焦点真实性/重抓/防抢前台的小修，不继续同轮猜改或反复启动；native前台激活权、独占全屏呈现与输入窗口归属留作单独定位。GUI打开时F8规则不变。
