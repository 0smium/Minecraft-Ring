# 2026-10-12：Poison 候选部署与 LB 伴随验证

状态：本轮部署与伴随验证收尾。Poison 图标、无横推/弹起、非致死扣血已获用户与日志支持；Breadcrumbs 显示正常并有合成录屏。常用移动按用户体验初步可用；普通攻击漏转、FlyCreative 两侧权限失配及下降摔死已登记。naturalRegeneration 已恢复 true。关卡 0、三次受击与 Velocity 不写通过，站立平台继续保持已知失败。

## 实际提交、版本与部署

- 启动 HEAD `013fc654e038ba8278b40d89bbde31ca1a9453a6`，`dev/lb-poc`，启动前工作树干净。Poison 实现为 `580d70a0ec14899febbe28c219f64d42d30c36dc`；本轮没有新改游戏代码或重新试改平台。
- 启动前 ER/Java/Steam 均未运行。实际游戏目录仍为 `D:\Steam\steamapps\common\ELDEN RING\Game`，exe 再读 `2.7.1.0`。App 沿用用户此前确认的 `1.17.1`，本轮标题版本未另得到明确回复。
- 只更新实际 `Game/erbridge/erbridge_core.dll`；旧 DLL 原样另存 `runtime/experiments/core-before-14-lb-poison.dll`，SHA-256 `C4480614AB586F65A944AD5C94687918C99A6A3C06BF470B87FB4D7C3B76459C`。未重跑安装器。
- 新 Core 源文件与实际加载副本 `Game/erbridge/loaded/core_25792_1.dll` SHA-256 都为 `3869C54C33F8D2B0F2D98A88D780E5E5B86FE22AD8615599ED1840DC626A92EB`。
- Fabric JAR `bridge-base/elden-ring/mc-bridge/build/libs/er-bridge-0.3.0.jar` SHA-256 `28E459A52258C315D5B536016D1A80CC281B0B77E10D718609C3ADB8029CF6F2`。本轮 `remapJar` 及编译任务 UP-TO-DATE，使用已构建配套制品。
- 原 `installation.json` SHA-256 仍为 `8551CE1521C33E08CBFE909CAA5BC7A92AF9369E1FAD591AA1789071CFFDF3C2`；原备份 `backups/20261011-095120` 保留；loader 未替换，SHA-256 `F732F6AFADB5638795882A562CF83CB3E12D95C63432123E3B7881CF057EB96C`。

## 启动、LB 与实际配置

启动 Steam 后确认本机登录态有效，再执行 `start.bat -LiquidBounce`。ER PID `25792`，MC PID `37724`，启动器继续运行。07:19:16 native 确认 `read-only host effects enabled`，generation 1 加载成功。

LB 使用已验证的 `productionLbClient` / 正式映射环境，固定 v0.22.0 / Build #3530，JAR SHA-256 `1824011D91046F8F8B388CFA63782F38CDF754F12CB997317EAB8E69E67EF20D`。MC `1.21.1` / Loader `0.19.5` / API `0.116.17+1.21.1` / FLK `1.13.0+kotlin.2.1.0` / MCEF `1.3.1-1.21.1`，Java `25.0.4.1+1`、Gradle `9.7.1`、Loom `1.18.2`。07:19:48 列表 107 mods；07:20:01 `Intermediary environment detected`；07:20:06 浏览器就绪；07:20:19 自动开 ER Bridge，07:20:21 Steve 加入。

启动前原 LB 开启项为 HUD、ItemESP、KeepSprint、KillAura、NoJumpDelay、Sprint、TranslationFix。原字节备份 `runtime/experiments/lb-14-original-modules.json`，SHA-256 `BFC8EE0CBCFF8F49895968E740D81C020DA29BBAEAFADB34767192A628493BD5`。只关闭其中五项，保留 HUD 与 TranslationFix；核对所有其他设置语义未变。准备配置保存 `lb-14-baseline-modules.json`，SHA-256 `87E4842DBA50590BCACBF0DC44B571D0C2155E48B0007048CF5B1C45B9739385`。

07:22:17 快照实际仍只有 HUD + TranslationFix，`player_valid / camera_override / compositing / live=true`。HEFX v1 稳定，flags=3（有效、MC 接管、无 Poison），frame `9864`、hostLife `41`、Poison ID `-1`。header 的 mcDeaths=4、hostDeaths=18 是累计计数，不当本轮新增死亡次数。

随后用户探索 Speed/Scaffold，反馈两者正常；07:27:19 持久化配置显示二者开启，Fly/Velocity/NoFall 关闭。Speed 模式 Custom、绑定 G，Scaffold 绑定 Z。启停精确时刻、Speed 参数效果和 Scaffold 具体操作未完整记录，属于初步体验，不当正式定量验收或纯 Core 对照。GUI 内 F8 沿用已接受的先 Esc 后 F8 操作。

保留启动警告：可选 ViaVersion/Sodium 类缺失、JNI/Graal 信息及 `Invalid standard cursor`；进程继续进入世界并合成。它们不自动判为兼容完成，也不因出现同类警告就追加修复。

## 日影城受击：无来源分类回归

用户反馈“怪物打我不掉血也没击退”。native 首次记录 07:23:27.811 的 278 HP 下降；07:23:56.788 记录 118 HP。至 07:27:32 的本轮读取范围共有 97 条 HP 下降，全部 `by=FFFFFFFFFFFFFFFF / status-unattributed`，普通 attack 为 0；MC 对应 97 条 `observed … not applied as Minecraft damage`，未进入桥接普通 `server hurt`。

07:24:04 曾切 creative，07:24:08 已切 survival，之后仍漏转攻击，因此后段不能归因为创造免伤。MC 仍有其他血量下降，例如 20→18.4、18.4→12.799999，来源未确认；不声称整轮完全没有任何伤害。

源码链：`27cbc68` 将缺少 `+0x180` 来源标记的 HP 下降从普通 hit 分流，`580d70a` 撤销未知通道直接扣血后，这些攻击只被记录。固定社区定义虽称该字段 `last_hit_by`，目标代码存在受 HP 条件限制的写入，不能将它当作每次普通攻击的必有标记。当前只能确认分类路径吞掉了这些实测损失，仍需另项确认可靠的普通攻击来源；不凭伤害大小猜身份，也不恢复所有未知伤害以掩盖问题。

本项登记到 [局部差异 S06](../host-state-mapping.md#局部差异清单先记后修)。受击三次与 Velocity 对照暂受阻。当前先留档，单独进行 Poison 与 LB 视觉观察，不边测边反复改 DLL。

## 当前人工步骤与未完成项

已取得用户“没死”的静止反馈。Poison 短测：关闭 Speed/Scaffold 和其他行为模块，仅保留 HUD；关闭 GUI，naturalRegeneration=false、survival，在 ER 毒池生效后离开毒池/敌人，instant_health 回满 MC HP，固定地面保持 MC 控制 30 秒，不 F8/不菜单。观察图标/绿色心、实际 HP 是否停在低值、击退和死亡，混入攻击如实记录。请求已发，结果待反馈。

### Poison 实际观察与摔落分流

用户随后回复：“没有。死的原因是我穿模摔死了。然后又测了一遍也没死 speed scaffold nofall好像都能用”。之后明确补充：“没有横推/弹起，有中毒图标”。没有要求再测，按用户真实观察补齐基本语义结果。

- 07:29:42、07:30:46 服务器实际记录 `Host poison effect 4004 -> vanilla Poison I`，分别为生命期 42/43，真实状态映射已在游戏中触发。
- 第二段 07:30:46 MC HP 20→19，随后每次下降 1；07:31:09 2→1。服务器 receipt +0/+5/+20 窗口仍为 1 HP。此后到 07:33:54 效果撤销前没有新的 health-drop receipt，也没有 Poison 致死日志；用户确认重测未死。非致死扣血行为取得本轮支持，不把未覆盖的解除/外部效果/断连情况整体验收。
- 07:28:04、07:30:01 的 MC 死亡原因均为 `fall`；后一条发生在 HP 尚为 4 时，并经既有 LifeBridge 同步 ER 死亡，随后撤销毒租期。与用户穿模摔死描述一致，碰撞缺口原因仍未定位，不能算毒致死，也不关闭死亡逻辑掩盖问题。
- 本轮过程中有走跳、搭建和模块探索，未形成完整的固定地面/模块关闭记录。07:30 后的一份配置有 Speed/Scaffold 开启，之后快照有 AirJump、NoFall 开启。Poison 图标和无横推/弹起由用户明确确认，日志支持非致死扣血；不将本轮扩写为所有清理条件或严格无干扰组合已通过。
- Speed/Scaffold/NoFall 仅按用户“好像都能用”记基本体验可用，不宣称定量效果、ER 原生坠落保护或所有模式已验收。AirJump 文件状态不等于用户验证反馈。

证据另存 `runtime/experiments/lb-14-poison-{native,minecraft}.log` 与 `lb-14-poison-modules.json`。07:33:54 的撤销日志合并了 inactive/invalid/stale/control released 多种原因，不认定为已证明 ER 自然解毒；后续游玩又出现 4004 映射。

Poison 在上述 07:27:32 的早期取证尚无成功日志，后续真实映射结果按本节追加记录区分。模块探索不污染固定条件受击表。

### Breadcrumbs：基础世界效果展示通过

用户开启后反馈“没啥问题，正常显示”，提供桌面 `D:/Users/yanbiao/Desktop/QQ录屏20261012073610.mp4`。原样保留并复制到忽略目录 `runtime/experiments/lb-14-breadcrumbs.mp4`；SHA-256 `84054DDBAA9BEC8897A67D12A3DFE611E4D1C62244E066753B02D7450AF14555`，59,463,356 bytes，27.006 秒，1598×898，572 帧，平均录屏约 21.18 FPS（不是游戏 FPS）。

三张完整原始帧保存 `lb-14-breadcrumbs-frames/`：5.401 秒清楚可见 ER 场景中的蓝色世界轨迹、LB HUD 与模块列表；13.503 秒可见 Poison I 图标、吃附魔金苹果；21.605 秒有 Scaffold 开启通知、MC 方块搭建与状态 HUD。用户正常显示结论与实际视频相符。

录屏为第一人称及模块探索，包含 NoFall/AirJump、后段 Scaffold，以及金苹果产生的 Resistance/Absorption/Regeneration，不改写为全程纯 UI 配置。轨迹显示的本轮基础展示记通过；停止淡出、定量性能和每个模块所有模式未逐项验收。相关标准 MC 功能优先复用，不继续为逐项检查而扩大当前轮。

### FlyCreative：已确认两侧飞行状态失配

本轮只筛查文档中的适配点。固定 LB Creative 模式修改客户端飞行权限；原版服务端按自身 mayfly 才接受 flying，Bridge native 使用客户端控制标志，而服务端平台/摔伤使用 ServerPlayer 状态。

- 用户 07:44:03 明确处于 survival。07:44:50.749–07:45:34.488 的只读控制采样有 219 个 Flying 样本：flags=535（MOVE_HUNTER + FLYING 等）、grounded=false、supportEpoch=0，发布 support=false。
- 07:45:03 `/data get entity @s abilities` 返回服务端 `mayfly=0 / flying=0`，与上述同时段实际 Flying 控制标志失配。
- 07:45:34 `Steve died (fall)`，随后既有 LifeBridge 同步 ER 死亡；Fly 关闭日志在 07:46:09，07:46:11 服务端再次读数仍为 mayfly/flying=0。用户报告 Fly 下降伤害、NoFall 模式防不住，随后总结常用移动基本没有问题。
- 保存配置确认 Fly.Mode=Creative、Speed=0.1、ForceFlight=true；BypassVanillaCheck 实际仍 true，NoFall 为 SpoofGround（FallDistance=1.7）。不改写成所有指示参数均严格执行。场景高度与移动也超过请求的 2–3 格短悬停，用户没有单独报告标准悬停结果。

结论：实际两侧权限失配与 MC 摔落已确认，FlyCreative 不整体验收。优先适配客户端飞行声明到服务端有效权限、开启时既有摔落累计、关闭/换模式时原值恢复；不要把 NoFall 的 SpoofGround 当作服务器飞行状态同步。下降摔伤的完整时序及每种 NoFall 行为仍需修复后验证，不声称 ER 原生坠落保护已通过。

取证为 `runtime/experiments/lb-14-flight-{native,minecraft}.log`、`lb-14-flight-modules.json`、`lb-14-flight-control-samples.jsonl`；采样只读实际共享内存，不写控制或操作游戏。本轮只登记适配点，没有追加修复版或反复长测。

## 本轮收尾与下一批局部修复

用户已执行 `naturalRegeneration=true` 并回复“已恢复”，恢复原实验前值。游戏保留运行，无需为写记录而重新启停。

A 工作线下一批集中在 S06 普通攻击漏转和 S08 FlyCreative 权限同步/恢复；Velocity 等可靠受击恢复后再做对照。穿模与其他属性继续记局部条件，必要时再修。基础 GUI/HUD、Breadcrumbs 和用户常用移动探索不再扩大成全部 LB 模块逐项适配。

## 证据路径

- `runtime/experiments/core-launch-14-lb-poison.log`。
- `runtime/experiments/lb-14-startup-{native,minecraft}.log`、`lb-14-startup-status.json`、`lb-14-startup-modules.json`。
- 原配置与准备配置：`lb-14-original-modules.json`、`lb-14-baseline-modules.json`。
- 新受击缺口：`runtime/experiments/lb-14-attack-gap-native.log`、`lb-14-attack-gap-minecraft.log`、`lb-14-attack-gap-modules.json`。
- Poison、飞行、视频及原始帧：见以上各节，均留在本地忽略目录。
- 上轮结束留档：`runtime/experiments/core-13-final-before-14-{native,minecraft}.log`。
- 后续实时文件：`runtime/er-bridge.log`、`bridge-base/elden-ring/mc-bridge/run/logs/latest.log`；人工反馈后另存。

只提交代码/Markdown；配置、日志、视频、JAR、游戏文件和存档保留本地，不上传 Git。
