# 法环装备附加页第一版

本版与成长树配套使用 Core + Character **0.3.3**，原生装备服务需本批 DLL。0.3.2首次实测发现1106条库存被截断为1024条并停止装卸，见[集中验证记录](experiments/2026-10-14-growth-equipment-live.md)。本批HINV v3完整采样上限8192，保留截断拒写和身份/token校验；页面与图层已修。实际换装、外观、负重/防御刷新与保存保持仍需下一轮人工验收。

## 页面与操作

在 MC 控制状态按 **E** 打开生存或创造物品栏，再点 **ER** 按钮。移除额外的 I 绑定。鼠标拿着物品时不转页，先放回原格。ER 页只展示法环四件防具、四个护符位置和物品列表；MC背包/护甲仍留在原版页，不重复显示或删除。

每页50格，原图26px，支持全部/防具/护符/武器/道具筛选；小视口整体适配。原生图片直接替换投影格绘制，缺图用明确的问号，不再由皮甲或金粒模型遮挡。物品说明使用中性载体，不带无关的MC护甲属性。

- 将列表防具拖到对应头、身、手、腿槽装配；也可先点击装备槽，再点对应防具。
- 将已装备防具拖回物品列表或右键装备槽卸下。等待“法环已确认”，显示根据真实原生库存刷新。
- 护符当前只读。开放数量来自真实角色的基础槽数与已解锁额外槽，未知或尚未取得皮袋的槽显示锁定，不按“当前戴了几枚”推测。
- 武器装配、消耗品使用、护符装配、原生丢弃和施法尚未接入；这些物品可浏览。Q/拖出页面不会伪造丢弃或生成MC复制品。

ER 格子是原生库存的展示，拖动仅形成原生装卸意图，不使用 MC carried stack，也不能取出/复制/消费为 MC 物品。页面请求包含玩家实际看见的物品 ID、库存槽、handle 和页面版本；服务端核对当前拥有者及页面，原生侧再核对真实库存与角色。翻页、换角色或库存变化会拒绝旧拖拽/点击，不会按旧格子序号装入另一件物品。

装配和卸下调用当前版本的原生函数，不直接改装备索引。四防具槽共用同一路径；成功必须重新读到正确装备。结果未知时只观察新快照，不自动再次调用。细节和函数证据见[原生装备记录](experiments/2026-10-14-native-equipment-intents.md)。

ER 装备继续由 ER 处理其原生属性，MC 防具仍是原 MC 库存物品；展示用 ItemStack 不另加一份 ER 防御到 MC 属性。负重、动作与 MC 能力之间更完整的属性组合不是本批已经完成的内容。

## 原生缩略图与中文名

已从本机 `regulation.bin`、Data0 的中文 FMG 和 `MENU_Knowledge` 原图导出 **6,604 条物品元数据、2,663 个原生图标**。图片缩为 64×64 透明 PNG，页面按26像素显示。没有重画原生图片，也没有把原生资产打进 JAR 或上传 Git。

位置：`runtime/host-assets/items.json`、`runtime/host-assets/icons/<iconId>.png`。图片按需读取，最多保留 256 个 GPU 纹理；打开装备页时重读资源。缺少提取数据时用 ID 和明确的问号，不影响原生库存校验。防具目前用参数表的默认男性版图标；不同体型专用图标选择尚未接宿主体型信息。

本机已经准备好资源。换电脑或更新游戏后可重新提取（只读游戏目录）：

```powershell
python -m pip install --target .tools/python-host-assets soulstruct==2.6.0 pycryptodome Pillow
python -X utf8 tools/export_host_item_assets.py
```

需要 Python 3.13+。工具只按索引读取指定资产，不解包整款游戏；Oodle 解压使用游戏自带 DLL。`items.json` 记录 regulation/Data0 头部 SHA-256；当前提取日志是 `runtime/experiments/host-item-assets-export.log`。

文件格式依据：[Soulstruct](https://github.com/Grimrukh/soulstruct)、[ER.BDT.Tool](https://github.com/Ekey/ER.BDT.Tool)、[SoulsFormatsNEXT BHD5](https://github.com/soulsmods/SoulsFormatsNEXT/blob/ee1dd61958f60bdc51ce3da548e9a90a8ab39905/SoulsFormats/Formats/BHD5.cs)。装备槽与皮袋容量以当前游戏读取为准；通常初始一槽、三只皮袋增加至四槽，相关获取机制可对照 [Talisman Pouch](https://eip.gg/elden-ring/db/talisman-pouch/)，不会在代码中用攻略进度替代实际解锁状态。

## 集中验证的一件装备闭环

准备一件已经拥有的头盔或上衣，在安全固定地面完成一次：ER 页选槽 → 选防具装配 → 等待确认 → F8 核对法环实际装备、负重和防御 → 回 MC 右键卸下 → 再核对 → 正常退出并重进存档检查保持。正常使用无需 F8；这一轮 F8 只是人工对照证据。

同时观察原 MC 护甲是否保持、原生图标/中文名是否对应、护符锁定数是否与法环相同。若返回未知或失败，保留日志并报告原文，不把 UI 图标变化独立当作原生成功。
