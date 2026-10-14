# Third-party notices

Minecraft Ring is a Windows adaptation of the Elden Ring portion of
[minecraft-crossover-bridge](https://github.com/justbustin/minecraft-crossover-bridge)
by justbustin. The original MIT notice is preserved in
[bridge-base/LICENSE](bridge-base/LICENSE).

[MinHook](https://github.com/TsudaKageyu/minhook) is bundled as source under
`bridge-base/elden-ring/er-bridge/third_party/minhook/`. Its license, including
Hacker Disassembler Engine notices, is preserved in
[LICENSE.txt](bridge-base/elden-ring/er-bridge/third_party/minhook/LICENSE.txt).
Preserve these notices when distributing compiled bridge DLLs as well.

The Gradle wrapper is build infrastructure distributed under the
[Apache License 2.0](https://github.com/gradle/gradle/blob/master/LICENSE).
Minecraft, Fabric Loader, Fabric API and other downloaded dependencies retain
their respective licenses.

[Pufferfish's Skills](https://modrinth.com/mod/skills) 0.19.2 is an optional local
B01 dependency. Its artifact declares All Rights Reserved; the JAR is downloaded
from the official release and is not embedded in the bridge or committed here.
The Bridge skill-tree definitions and adapter are maintained in this repository.

The Windows overlay is managed by the bridge itself. Borderless Fullscreen and
b100lib, used in an earlier local setup, are no longer required or bundled.
Downloaded LLVM-MinGW, JDK and Gradle toolchains are excluded from this repository
and retain their own licenses.

[SkyCraft](https://github.com/chasmlol/SkyCraft) and
[ArkWeb](https://github.com/luki-1/ArkWeb) were studied as architectural references.
Their reference checkouts are excluded from this repository.

[EldenCraft](https://github.com/SocketByte/EldenCraft) at
`ba7591cdde9fb16e17552cfe53546f44d0fd856e` (MIT) and
[fromsoftware-rs](https://github.com/vswarte/fromsoftware-rs) at
`59fbd3b3b7daaf14aca47c9f73530493dba6bc79` were read as references for
native collision lifecycles and menu observation. Their checkouts and binaries
are not bundled. Menu field semantics and short signature metadata were
independently checked against the user's local executable; the read-only
inspection helper does not distribute game bytes or call those functions.

Game installations, save files, downloaded toolchains and executable dumps are
not included. Game names and assets belong to their respective owners. The MIT
license covers the bridge code; it does not grant rights to either game.

The separate offline helper `tools/export_host_item_assets.py` uses
[Soulstruct](https://github.com/Grimrukh/soulstruct) 2.6.0 (GPL-3.0-or-later)
and is licensed GPL-3.0-or-later, as marked in that file. It is not linked or
bundled into the Fabric/native bridge. Archive format research also used
[ER.BDT.Tool](https://github.com/Ekey/ER.BDT.Tool) and
[SoulsFormatsNEXT](https://github.com/soulsmods/SoulsFormatsNEXT). These local
references, dependencies, extracted game text and original images are excluded
from Git and from published JARs. The helper reads a user's own game installation.
