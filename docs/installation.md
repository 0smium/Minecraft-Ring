# Windows setup

[Back to Minecraft Ring](../README.md)

This guide describes the current source-build workflow for Minecraft Ring
0.3.6 (split Fabric candidate). It targets Windows x64, Elden Ring App Ver. 1.17.1 (`eldenring.exe`
2.7.1.0), and Minecraft Java Edition 1.21.1.

## Toolchains

Install Python 3 and place the extracted build tools in the following layout:

```text
Minecraft-Ring/
  .tools/
    compiler/<llvm-mingw-folder>/bin/clang++.exe
    java/jdk-25.0.4.1+1/bin/java.exe
    gradle/gradle-9.7.1/bin/gradle.bat
```

Use a Windows x64 LLVM-MinGW distribution with `clang.exe` and `clang++.exe`.
The native build selects a compiler under `.tools/compiler/`; the launcher
uses the fixed Java and Gradle paths shown above.

Gradle 9.7.1 and Fabric Loom 1.18.2 use JDK 25. The current project also selects
JDK 25 as its Java toolchain and compiles with `--release 21`; a separate JDK 21
installation is not required for this launcher. The launched development client
runs through the project's Gradle configuration.

Minecraft, Fabric Loader 0.19.5 and Fabric API 0.116.17+1.21.1 are downloaded
by Gradle on the first build. The wrapper pins Gradle 9.7.1. Tool downloads,
caches and local settings are excluded from Git.

## Build

Core and ordinary Character builds need no Skills, LB or Meteor JAR. Only the
B01 profile requires `python tools/prepare_b01_skills.py`; see [B01 integration](b01-integration.md).

From the repository root:

```powershell
python tools/build_native.py
```

The output is `dist/dinput8.dll` (loader) and `dist/erbridge_core.dll` (native
bridge). To build the Fabric mod without launching Minecraft:

```powershell
$env:JAVA_HOME = Join-Path (Get-Location) '.tools\java\jdk-25.0.4.1+1'
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.tools\gradle-home'
.\bridge-base\elden-ring\mc-bridge\gradlew.bat -p .\bridge-base\elden-ring\mc-bridge --no-daemon --no-configuration-cache build
```

The remapped mod is written to
`bridge-base/elden-ring/mc-bridge/build/libs/er-bridge-0.3.6.jar`.
Add `-Pcharacter=true` to build the independent
`character-bridge/build/libs/er-character-bridge-0.3.6.jar` alongside Core.
`-Pb01=true` includes Character plus its pinned Skills adapter/data. Both character
variants use the same mod ID; install only the selected one with the matching Core.
After dependencies are cached, add `--offline` for an offline build.
See [module boundaries and profiles](character-modules.md).

## Install and launch

1. Close Elden Ring. Run the installer with your actual game directory:

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File .\Install.ps1 -GameDir 'D:\SteamLibrary\steamapps\common\ELDEN RING\Game'
   ```

2. The installer copies the native DLLs, writes `steam_appid.txt`, backs up
   `%APPDATA%\EldenRing`, and moves recognized existing mod files into a
   timestamped `backups/` directory. It records those paths in the local,
   ignored `installation.json`. Keep that record and its backup together.

3. Start Steam for your Elden Ring installation, then double-click `start.bat`
   or `Play.bat` for Core-only. Add `-Character` for character features or
   `-B01` for Character + Skills + fixed LB. The launcher builds and resolves
   the selected profile before starting `eldenring.exe` with the bridge marker,
   then waits for native readiness and starts Minecraft.

4. Choose **Continue** in Elden Ring. Minecraft automatically creates or opens
   the local `ER Bridge` world and connects to the host player. New worlds start
   in creative mode with commands enabled.

Keep the launcher console open while Minecraft is running. A second launch
recognizes an already running bridge client instead of deliberately starting
another one. The bridge is for offline play and will not activate with Easy
Anti-Cheat present.

## Surface resources candidate

Fabric 0.3.5 adds sparse Minecraft trees, flowers, grass, berries and stone outcrops
to verified open ground in ER Bridge worlds. Generation receipts are saved with
the world so harvesting and reconnecting do not respawn a site. Use
`/erbridge resources status`, `off` or `on`; disabling generation keeps existing
blocks. This is an untested in-game candidate; see [surface resources](surface-resources.md).
Deploy the paired 0.3.6 native candidate for the new armor/talisman equipment capabilities.
Shared-memory layouts remain unchanged; the new talisman capability bits still require
the matching Java reader. Character adds sleep action restrictions; see [sleep actions](sleep-actions.md).

## Optional native block collision candidate

The native-block collision experiment is opt-in through `-NativeBlockCollision`,
including with `-B01 -CheckOnly`. It requires the paired DLLs and a full ER restart;
it is not enabled by ordinary launches. See [native block collision](native-block-collision.md)
for limits and pending manual checks. Experimental native bodies prevent Core hot reload
until ER exits.

## Optional Meteor candidate

The fixed MC 1.21.1 / Meteor 0.5.8 candidate has a separate preparation and
launch path:

```powershell
python tools/prepare_meteor.py
.\Launch.ps1 -Meteor -CheckOnly
.\Launch.ps1 -Meteor
```

It uses `run-meteor` for its world, logs and client configuration, and cannot
be combined with `-LiquidBounce` or `-B01`. Preparation checks the fixed official
SHA-256, release metadata and adapter signatures, then records a local pin.
`-CheckOnly` builds the selected JARs and resolves their runtime dependencies
without starting games or changing game configuration; it does not verify native deployment. This candidate has passed
Windows native/Fabric builds, official release-JAR preflight and eight real
Mixin injection calls in an offline transformer. Game startup, module
initialization and rendering are pending. See [Meteor integration](meteor-integration.md)
for the manual-JAR fallback and acceptance steps.

## Window settings

Minecraft must stay in windowed mode. The launcher writes `fullscreen:false`,
and the bridge applies its borderless layout and follows Elden Ring's client
area. Borderless Fullscreen and b100lib are no longer dependencies. Legacy
`startInFullscreen` settings are disabled at launch.

The Windows input window uses constant opacity when Elden Ring draws Minecraft's
frames, and framebuffer transparency for the separate overlay mode. It leaves
one screen pixel at the bottom to keep OpenGL desktop composition active. F11
is not needed for setup.

## Updating and removal

Close both games before updating. Pull the current source, rebuild both sides,
and copy the new native files to the same locations used by the installer:
`dist/dinput8.dll` to `<Game>\dinput8.dll` and `dist/erbridge_core.dll` to
`<Game>\erbridge\erbridge_core.dll`. The launcher rebuilds the Fabric mod.
Keep both sides on the same revision because their shared protocol must match.

For an existing installation, retain `installation.json` and its original
backup. Running the installer again creates a new backup and replaces that
record, so it should not be used as the routine update step if you want to
restore the original mod setup later.

To disable the bridge, close Elden Ring and run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\Restore.ps1
```

The script archives the installed bridge and restores the mod files recorded
in the installation backup. It does not apply save backups. Minecraft worlds
remain under `bridge-base/elden-ring/mc-bridge/run/saves/er-bridge/`.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Native build cannot find the compiler | Verify `.tools/compiler/<folder>/bin/clang++.exe` exists. |
| Gradle cannot start | Verify JDK 25 and Gradle 9.7.1 paths; inspect the launcher console. |
| First Fabric build cannot resolve dependencies | Run with network access before trying `--offline`. |
| Launcher reports that the native bridge did not load | Close Elden Ring, use `start.bat`, verify the supported executable version, and read `runtime/er-bridge.log`. |
| F8 does not return to Minecraft | Focus Elden Ring, release F8, then press it again; verify both bridge processes are still alive. |
| Black or misaligned Minecraft window | Keep Minecraft windowed, restore normal camera/compositing modes if changed, and collect both logs with GPU and driver information. |
| A passage or platform has incorrect collision | Report the location and movement that reproduces it, with logs. Sampling and moving-platform behavior remain experimental. |

`python tools/status.py` reports bridge state and heartbeat activity when a
shared-memory file exists. Native logs are in `runtime/er-bridge.log`; Minecraft
logs are in `bridge-base/elden-ring/mc-bridge/run/logs/latest.log`. Native faults
may also produce `runtime/eldenring-crash.txt` and `runtime/eldenring-crash.dmp`.
See [development and diagnostics](development.md) for regression checks and
what to include in a bug report.
