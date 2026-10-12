# 2026-10-12：KillAura、敌人标记与普通受击局部适配

状态：基础功能通过用户观察与日志核对：敌人 Box/Tracers、KillAura 的真实 HP 扣除、普通受击，以及修复后的宿主 HP 标签显示跟随。关卡 0 的标准三次受击、Velocity、穿墙效果与本轮新录屏未完成，不扩写为全面验收。用户明确新增 KillAura 与 ESP 适配范围；本轮不再试改平台，也不扩成全部属性翻译。

## 基线与范围

- 开发分支 `dev/lb-poc`，起点 `34d836deead076f77b129986d20df0e71c58f190`，原始 Bridge 基线 `711015afa67ab25c7b9597c68038718d1cef322e`。
- 本轮构建与启动时为上述 HEAD 加本文列明的未提交代码；收尾实现提交 `1475531aea27e43a92a7df27d9559fbe1d211236`，对应最终手槽修复后的源码，制品哈希按各次部署分别记录。
- LB 固定 v0.22.0 / Build #3530；只读参考提交 `e5a0dbf5458b063d3028e69e04762b8b25b998b5`。原始 JAR SHA-256 `1824011D91046F8F8B388CFA63782F38CDF754F12CB997317EAB8E69E67EF20D`，不修改或升级 LB 本体。
- ER exe `2.7.1.0` / App `1.17.1`，MC `1.21.1`、Loader `0.19.5`、API `0.116.17+1.21.1`、FLK `1.13.0+kotlin.2.1.0`。JDK `25.0.4.1+1`、Gradle `9.7.1`、Loom `1.18.2`，Java 输出目标 21。
- 当前 session 继续负责 A：真实 LB 与 Core 的局部兼容。本轮交付敌人识别、Box、HP 标签、普通受击回归；原生连招/翻滚、装备/物品桥接、遭遇生成及 UHC 玩法不在本轮。

## 如何接入目标与碰撞箱

| 现有事实 | 本次接法 |
| --- | --- |
| `ErEntity` 是通用 LivingEntity，不属于 LB 接受的 Hostile/Monster 类型 | 可选 Mixin 在 `TargetConfigurable.isInteresting` 接入敌人代理；保留外层标签/好友规则与 Hostile/Invisible 设置 |
| 代理已同步宿主 ID、敌人种类、HP/maxHP 和实际 AABB | 仅桥接世界、连接存活、ID 非零、数据有限且正 HP/maxHP 的存活敌人参与；NPC、已死/移除和未完整同步的代理排除 |
| KillAura 用目标 AABB 测距离和射线，发送标准 MC attack 包 | 不另造攻击器：服务端 `Player.attack → ErEntity.hurt → EntityBridge.onHit → native apply_hit → ER HP`，既有冷却和 ER 无敌仍生效 |
| ESP Box 默认用 dimensions 构造方形底面 | 在固定版 Box 渲染入口换成代理实际矩形 AABB，相对坐标与 0.05 边距保留 |
| Nametags 默认显示 MC 的 20 HP | 只替换代理标签的健康文本为 `当前 / 最大 ER HP`；未知显示 `ER HP ?`，不改 MC 生命或伤害公式 |
| Tracers/Nametags/ESP 共用视觉筛选 | 共用目标适配，无需各写一套敌人列表 |

兼容代码位于 Fabric client 的 `integrations/liquidbounce/`，独立配置 `erbridge.liquidbounce.mixins.json`。无 LB 或非 0.22.0 时由插件跳过；启动器仍核对固定 JAR 哈希。生产方法选择器按实际 Build #3530 字节码核对，Loom 将 Java 的 MC 类型转为 intermediary。不能把源码 Yarn 名直接塞进生产选择器。

限制：初测 Box 用静态颜色，目标优先级选 Distance。TargetHUD、健康排序及其他颜色模式的默认 MC health 尚未翻译；Glow/Outline/Chams 依赖 MC 模型，而 ER 原生网格没有复制到 MC。世界标记仍受宿主合成深度影响，不能据 MC 内的关闭深度测试宣称已穿 ER 墙。

## 为什么之前开 LB 后看起来不挨打

实际是 Core 分类回归，生存模式也复现，与 LB 的加载开关无关。此前把 `ChrIns + 0x180` 当作普通攻击的必有来源；目标版本的该字段包含受死亡条件限制的归属写入。NoDead/回满机制下实际 118/278 等攻击没有来源，先被分入未知通道；撤销未知通道自定义伤害后，只剩记录。不能把所有未知 HP 下降重新猜成攻击。

本次在目标 exe 交叉核对普通命中链 `0x448910 → 0x448A9B → 0x436AE0`：输入是已结算的命中扣血，八参数 ABI、函数入口、调用点和栈参数均有目标指令证据。Poison/SpEffect 与 fall 使用另一函数 `0x436A40`。新观察钩子只在逐字签名匹配时启用，只捕获当前接管角色和生命期、原函数真实扣掉的 HP，并原样调用原函数一次。

游戏 tick 分开发布 confirmed 命中量和 observed 总差分，未知残差为 `max(0, observed - confirmed)`。Poison 仍由真实效果映射原版 Poison，不从残差重复扣血。来源字段只作方向提示；没有提示时，确认攻击之后才允许最近敌人作明确标注的近似方向。没有可用敌人时 kind=0，MC 使用单独的物理伤害类型 `monster_undirected`，仅带 no_knockback，保留护甲等原规则；避免 MC 无来源攻击自动产生随机方向击退。

本项不新增共享协议版本，不改伤害平衡公式，不禁用死亡逻辑。现有协议在一个 MC poll 间隔聚合多次命中、使用最后方向的限制保留；多人伤害广播也不是本轮修复范围。函数 text hook 可能被游戏代码恢复机制覆盖，必须用运行时真实命中验证，不能只凭安装日志或编译成功判通过。

## 构建与验证记录

- Fabric 编译通过；首次 `remapJar` 因运行中的 MC 锁定旧 JAR 失败。保留旧实例，用本地临时 init-script 仅改输出名后，完整 build 成功：`build/libs/er-bridge-0.3.0-lb-target-candidate.jar`。不修改仓库构建配置或覆盖运行中的制品。
- 日志：`runtime/experiments/fabric-lb-target-compat-build.log`（锁定失败）、`fabric-lb-target-compat-candidate-build.log`（成功）；native 构建日志 `native-lb-combat-hit-build.log`。
- 候选生产签名已核对：Target callback 使用 `class_1297`；Box 使用 `class_238`/`class_1309`，目标 inflate 调用处唯一 LivingEntity local 为 slot 10；Nametag shadow 为 `class_1297`，文本为 `class_2561`。
- 三处实际离线 Mixin transformation 3/3 通过，日志 `runtime/experiments/lb-mixin-transformation-probe.log`。探针直接使用原固定 JAR、候选成品与本地 Mixin/MC 依赖，Box 已生成 ALOAD 10；未启动游戏、未执行版本插件，不能替代 live 共载和画面验证。
- Native 构建通过，候选 Core SHA-256 `854DBA2D802568C9071831AA596A1F9383568BB0EF1CB96FEF05A67A0E13E74D`。新增五段逐字签名与本机目标 exe 磁盘字节匹配；反汇编证据 `runtime/experiments/incoming-hit-{target-findings.json,caller-448a54-disasm.txt,callee-436ae0-disasm.txt,handler-448910-disasm.txt}`。实际命中覆盖仍待本轮运行确认。
- 人工步骤待部署后执行：先 Box + Nametags，看开阔近距离的一只普通怪；再持剑开 KillAura，Range=3.0、Hostile=true、Distance、Criticals Ignore、保留默认冷却，暂停 Speed/Scaffold/Fly/Velocity 等行为模块。核对真实 HP 下降与死亡后消失，并确认 NPC 不进入目标。
- 普通受击单独进行：关 KillAura、survival、无 MC 甲盾、无 Poison，回满血后吃一次普通攻击；由用户看实际掉血和击退，日志核对 confirmed 与服务器 actualHP。临时改自然回血则收尾恢复原值 true。不将挥手、锁定框或队列 sent 当成伤害成功。
- 本轮人工结果按下文填写；日志、配置、视频、JAR 和游戏文件只放本地忽略目录。

## 第十五轮部署与启动

用户确认完整退出后，再检查 ER 与 MC 进程均关闭。旧运行日志另存 `core-14-final-before-15-{native,minecraft}.log`，旧 DLL/JAR 另存 `core-before-15-lb-targets.dll` / `fabric-before-15-lb-targets.jar`，不删除原始安装备份。

标准输出名下的最终 Fabric build 通过，日志 `runtime/experiments/fabric-lb-target-compat-final-build.log`；JAR SHA-256 `7478FC7BCC1E88EE27082D92D09E0CD687B9F6C5CA20FB30B2678DC4DF9BBA2A`，与候选成品一致。只复制 Core 到实际 `D:/Steam/steamapps/common/ELDEN RING/Game/erbridge/erbridge_core.dll`，不重跑安装器。exe 再读为 `2.7.1.0`；原 `installation.json` SHA-256 仍为 `8551CE1521C33E08CBFE909CAA5BC7A92AF9369E1FAD591AA1789071CFFDF3C2`。

保存原 LB 三份配置为 `lb-15-original-{modules,combattargets,visualtargets}.json`。准备配置只启用 HUD、TranslationFix、ESP（Box、Static）、Nametags、Tracers；KillAura 先关闭，Range=3、WallRange=0、Priority=Distance、Criticals=Ignore，原 ClickScheduler 冷却开启且 Minimum=1.0。Hostile 两类目标均 true；其余选项保留。准备配置另存 `lb-15-prepared-*.json`，modules SHA-256 `8F9D3CAA32BCA16D5C3A12044413DDEAA1AA129ECC90043E959BB162E8F56423`。暂停用户原先探索的行为模块仅作为本轮对照，不宣称这些模块不兼容。

通过 `start.bat -LiquidBounce` 启动。ER PID `41168`，MC PID `34640`，启动器 session `63769`；启动日志 `runtime/experiments/core-launch-15-lb-targets.log`。08:26:19 Core generation 1 正常加载，`confirmed incoming-hit HP observation enabled`；08:26:31 optional LB target compatibility enabled，08:26:39 intermediary 环境，08:26:58 Steve 自动加入 ER Bridge。至进入世界未见阻塞注入错误。此时 ER 在标题，player_valid=false；连接成功不当成功能验收。

已向用户发出一次三项短测：Continue 后看敌人标记，再持剑启用 KillAura，最后关闭 KillAura/生存模式无甲盾回满血吃一次攻击；没有要求重复平台或开展三组 Velocity。用户回复“都可以”。本轮没有修改 naturalRegeneration，原值 true 保留。

## 实际战斗与名称标签结果

| 功能 | 证据与结论 |
| --- | --- |
| Box、Tracers | 用户三项回复“都可以”，基本显示可用；未取得本轮视频或单独遮挡对照 |
| KillAura | 用户确认可用；08:28:54–58 `c3650` 实际 ER HP 从 323/477 连续扣到 9，再归零，08:32:22 后续 `c4640` 的死亡也被代理同步；不是仅挥手或 queue sent |
| 普通受击 S06 | 08:28:42/47/50/53 分别捕获 86/85/85/86 真实 HP 损失，均 confirmed=1、residual=0，来源 hint 为空但仍正常进入 MC 伤害；用户确认掉血和击退可用 |
| 实际 MC HP | 前几次有吸收生命；08:31:59 一击 actualHP 20→15.7942295、absorption 0→0、hurt=true，速度变为约 `(1.008, 0.4, 0.444)`；伤害恢复已确认，不能把有其他效果/回血的游玩样本当裸装三次基线 |
| Nametags 首版 | 虽收到总体可用反馈，日志发现 `NametagInfo.createItemList` 无条件取两件手持物；ErEntity 继承的空 hand iterable 导致每帧 `NoSuchElementException`，本项不能据总体回复直接判通过 |
| Nametags 修正版 | ErEntity 补成两个 EMPTY 手槽，保留四个 EMPTY 护甲槽；用户随后明确“显示并跟随”，修正版日志无该异常，宿主 HP 显示基本通过 |
| 名称 | 仍为内部模型代号 `c####`，用户已指出；native 当前只格式化 modelId，还未映射游戏名称文本，不冒充已完成本地化 |

只读运行核对 `data+8` 等于当前玩家：三个 vtable 校验通过，读取前后角色稳定，HP832/832；证据 `runtime/experiments/lb-15-data-owner-readonly.json`。新命中入口不仅安装成功，也有上述真实捕获，覆盖在本轮观察期有效；不能承诺跨版本或长期不会被代码恢复。

用户随后探索其他组合。持久化配置显示 Range=5.47、Speed、TargetStrafe、KillAura 开启，Criticals=Ignore；实际攻击有未满冷却和自然暴击。保留为真实游玩结果，不改写成全程 Range3/纯模块条件；也不因本轮可用就宣称所有参数、自动格挡或原生动作兼容。NPC 排除是代码约束，本轮未取得明确的友好 NPC 对照反馈。

证据：`runtime/experiments/lb-15-targets-{native,minecraft}.log`、`lb-15-targets-combat-extract.txt`、`lb-15a-final-{native,minecraft}.log`、`lb-15a-final-modules.json`；包含错误的日志留档，未提交 Git。

### 仅重启 MC 的手槽修复

这是现有代理接口的七行修复，返回两只空手，不读取或伪造法环装备，也不在 LB 标签里吞异常。候选与标准输出最终 build 均通过，日志 `fabric-lb-target-compat-hands-build.log` / `fabric-lb-target-compat-hands-final-build.log`。最终 Fabric JAR SHA-256 `8F1133CCEF8BD11A9ED326BED1EB4AFCDA9CF5D8700766F701F9C49110EE999E`。

用户确认 MC 退出、ER 保持运行后，通过 `start.bat -LiquidBounce` 重启，ER 仍 PID41168，同一 Core 哈希不变；新 MC PID4044，启动器 session64222。08:34:37 optional 兼容开启，08:35:06 Steve 自动加入；bridge 状态 live/player_valid/camera_override/compositing 均 true。没有重复完整重启 DLL 或战斗测试，只补标签观察。

修正版证据 `runtime/experiments/lb-15b-tags-{native,minecraft}.log` 与 `lb-15b-tags-modules.json`，启动日志 `core-launch-15b-lb-targets.log`。已有 `Invalid standard cursor` 警告仍存在，但用户标签显示、跟随与游戏运行正常；本轮不追加无关 GUI 修改。

本轮未要求用户录制新视频，录屏仍待补。下一项局部差异是名称 ID/文本映射；FlyCreative 权限 S08 与标准 Velocity 对照保持未完成，不顺势扩大全部属性、装备或 Boss 生成范围。

### 名称表调研：有入口，尚未适配

当前 `publish_entities` 主动把 `ChrIns+0x64` 模型编号格式化为 `c%04d`；协议 `emId` 保存的是 `ChrIns+0x60` 的 NpcParam 行号。这两个都不是名字文本 ID，标签问题不是中文编码或 LB 丢字。

`NpcParam.nameId` 是 NPC 名消息 ID，定义允许 -1；Boss 的 `DisplayBossHealthBar` 事件还可单独传 nameId。资料为 [Paramdex NpcParam](https://github.com/soulsmods/Paramdex/blob/master/ER/Defs/NpcParam.xml) 和 [Boss 血条指令](https://soulsmods.github.io/emedf/er-emedf.html#DisplayBossHealthBar)。仓库已有 MsgRepository/FMG 读取与 UTF-16→UTF-8 转换，当前只用于交互提示，可复用其读取入口；本轮没有新增未验证名称偏移。

后续最小接法：验证角色的有效 nameId（或 Boss 血条 nameId）→读取当前语言 NpcName 文本→发布 `o.name`。缺失继续显示明确的内部编号；没有统一的模型 ID→官方中文名保证，普通敌人和区域变种是否有可用名字需按实际数据确认。本轮只记录，不再重启 DLL 或人工测名称。
