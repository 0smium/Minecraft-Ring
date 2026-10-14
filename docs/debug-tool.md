# 怪物与碰撞调试工具

2026-10-14 已准备 **Tarnished Tool v1.2.1**，尚未启动、附加到法环或验证与 Bridge 共载。它是独立 EXE，不是要替换现有 `dinput8.dll` 的游戏模组。

- 本机路径：`runtime/tools/tarnished-tool-v1.2.1/TarnishedTool.exe`
- 大小：7,586,816 字节。
- SHA-256：`a3b31eb8c4383b55a2ce0d92f90b67ab823df1f9f2ac197cdd9fd4379954025a`，与 GitHub release 资产的 `digest` 相同。
- [官方 v1.2.1 发布说明](https://github.com/borgCode/TarnishedTool/releases/tag/v1.2.1)明确新增 App 1.17.1 支持。发布 tag 指向 `dc499cfc4af713e8a91975944d637e17d7e3ab6b`。README 的旧版范围停在 1.16.1，以这次 release 的版本说明为准。
- 工具项目目标为 .NET Framework 4.8；本机检测到 v4 Full Release `533509`。没有为此安装或修改系统运行库。

## 后续实际使用

1. 用本仓库 `start.bat` / `Play.bat` 的离线流程启动法环与 Bridge。
2. 需要怪物调试时，手动打开上面的 `TarnishedTool.exe`。它会自动寻找 `eldenring.exe`，约每 2 秒重试附加；不是在启动 Bridge 时自动运行。
3. 第一轮先查看 **Character List / Target** 中的实体、参数和动作，再根据测试目标开启 **Rendering / Hitbox View**、AI 查看等功能。实际菜单位置和叠加显示尚未由本机操作确认。
4. 恢复正常受击/移动基线前，关闭为该轮测试启用的功能。工具对 No Death、No Damage、No Clip、Game Speed、FPS Limit、人物坐标及参数的修改可能和 Bridge 正在管理的状态重叠；这些应逐项测试并写入当轮配置。

默认“Activate On Launch”关闭；设置存于 `%APPDATA%/TarnishedTool/settings.txt`，再次使用时要按实际保存的设置核对，不能假定一直是初始值。

## 共载边界

它不占用 `dinput8.dll` 文件名，因此无需替换本仓库 loader。源码通过 `OpenProcess` / `ReadProcessMemory` / `WriteProcessMemory` 访问已运行的 `eldenring`；部分功能使用远程内存和代码 hook。独立 EXE 的文件布局不冲突，并不代表所有 hook/状态功能已经兼容。

当前没有启动工具、调用注入或写入游戏；游戏目录中的 `dinput8.dll` 与 `erbridge/erbridge_core.dll` 没有被该准备操作修改。普通基线继续只使用 Bridge；外部工具仅在明确的怪物/碰撞调试轮次手动启用，日志记录当时开启的选项。碰撞挤压仍需要观察原生 hitbox 与 MC/Bridge 代理的位置，下载工具本身不算这项验证通过。

Hexinton 没有另行下载或安装。本仓库原有的 debug spawn/清理入口仍保留，它与此工具是两套独立工具。

源码依据：[MemoryService 自动附加](https://github.com/borgCode/TarnishedTool/blob/dc499cfc4af713e8a91975944d637e17d7e3ab6b/TarnishedTool/Services/MemoryService.cs)、[HookManager](https://github.com/borgCode/TarnishedTool/blob/dc499cfc4af713e8a91975944d637e17d7e3ab6b/TarnishedTool/Memory/HookManager.cs)、[设置及默认值](https://github.com/borgCode/TarnishedTool/blob/dc499cfc4af713e8a91975944d637e17d7e3ab6b/TarnishedTool/Utilities/SettingsManager.cs)。下载文件、源码研究快照和清单只保留在忽略的 `runtime/`。
