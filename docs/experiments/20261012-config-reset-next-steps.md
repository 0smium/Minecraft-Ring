# 2026-10-12：离线重置LB配置与下一步

用户要求重置LB config，暂不启动游戏，只思考后续。执行时ER与实际Fabric客户端均已关闭；仅Gradle后台Java仍存在，没有启动、构建或操作游戏。

工作提交 `e20f7a78198f1bc7aa040f3b35f74a4d07cf55f6`，固定LB0.22.0/Build3530/原JAR SHA-256 `1824011D91046F8F8B388CFA63782F38CDF754F12CB997317EAB8E69E67EF20D`。代码及已部署制品未修改。

## 配置重置

按固定源码ConfigSystem.loadAll的缺文件默认加载规则，将实际 `mc-bridge/run/LiquidBounce` 中的8份旧设置移到本地备份：modules、combattargets、visualtargets、commands、storage、tabs、theme、lang.json。覆盖模块开关/参数/按键、选敌和GUI/HUD设置；下次启动由LB构造默认值并保存，不声称本次已启动验证默认配置。

备份 `runtime/backups/lb-config-reset-20261012-114032/`，逐文件SHA-256校验通过；`reset-manifest.json`及`runtime/experiments/lb-config-reset-latest.json`记录操作（America/New_York）。account、accounts、friends、proxy.json保持且哈希未变；MCEF、主题资源、脚本、已下载JAR、MC options、世界、存档和桥接安装保留。备份含本地设置，不提交Git。

纯LB默认值不等同于已验证的桥接展示配置。下一次准备时建立最小Bridge配置：HUD、ESP Box/静态色、Nametags按需要开启，Minimap关闭；Fly/Velocity分别单项测试，其它战斗/移动模块不混入对照。此次仅恢复默认，不提前写入这份预设。

## 下一步建议

目标仍是真实MC参与ER世界，并使用固定LB的可玩原型。公共目标/实际AABB、HP标签、真实攻击、Poison和基本视觉已贯通；本session继续能力适配和必要Core修复，不全面研究或迁移LB所有功能。

1. **先补FlyCreative服务端权限。** 已有证据显示生存模式client/native声明Flying而server mayfly/flying=0；仅接通开启/关闭与有效权限同步，验证悬停和关闭恢复，按真实有效状态处理摔伤。
2. **再补普通受击基线和Velocity。** 同一无毒敌人、同一招式、固定装备、恢复HP/站位、等无敌结束，取得3次实际HP与位移；随后单开Velocity验证击退变化与伤害不变。
3. **组成可录制的实际玩法。** 建造/移动、KillAura真实扣HP、Box/HP标签和已通过飞行组合成短流程，顺手记录珍珠命中/落点。按实际结果收尾，不把全模块逐项验证作为前置。

暂留站立升降平台、独占全屏额外点击、无nameId怪物的编号、其他状态与Scaffold局部几何问题。它们保留已知缺陷，不重新吞掉本轮时间盒。关卡0和Velocity完整验收仍未通过；无边框/窗口作为下一次固定测试环境，已通过遮挡/GUI/基础攻击不重做整套。
