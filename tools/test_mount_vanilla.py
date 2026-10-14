"""Compile the mount slice against cached real MC/Fabric APIs and test actual stacks/codec.

No Gradle, fake Horse entity, Minecraft server, Elden Ring or game window is started.
Requires the normal project build to have populated classes and named dependency caches.
"""
from pathlib import Path
import os
from test_lb_capability_adapter import single, invoke

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / "bridge-base/elden-ring/mc-bridge"
OUTPUT = ROOT / "runtime/experiments/mount-vanilla-check"


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    classes = OUTPUT / "classes"
    classes.mkdir(exist_ok=True)
    cache = ROOT / ".tools/gradle-home/caches"
    # Loom's project jars include Fabric's access wideners used by the existing block registration.
    dependencies = [single((PROJECT / ".gradle/loom-cache/minecraftMaven/net/minecraft").glob(
        f"minecraft-{part}-*/1.21.1-loom.*/minecraft-{part}-*.jar"), f"widened named Minecraft {part}")
        for part in ("common", "clientOnly")]
    dependencies += sorted(p for p in (PROJECT / ".gradle/loom-cache/remapped_mods").rglob("*.jar")
                           if not any(s in p.name for s in ("-sources", "-javadoc")))
    dependencies += sorted(p for p in (cache / "modules-2/files-2.1").rglob("*.jar")
                           if not any(s in p.name for s in ("-sources", "-javadoc", "-natives-")))
    cp = os.pathsep.join(map(str, [classes, PROJECT / "build/classes/java/main", PROJECT / "build/classes/java/client", *dependencies]))
    sources = [PROJECT / "src/main/java/dev/ermc/bridge" / f"{name}.java" for name in (
        "MountMotion", "MountPayload", "MountRuntime", "TerrainManager", "TerrainDetailManager", "TerrainTravelClearance", "ErBridgeMod")]
    sources += [PROJECT / "src/client/java/dev/ermc/bridge/client" / f"{name}.java" for name in (
        "MountClient", "CameraSync", "MovingPlatformClient")]
    sources += [PROJECT / "src/testFixtures/mount/MountVanillaCheck.java"]
    java = ROOT / ".tools/java/jdk-25.0.4.1+1/bin/java.exe"
    invoke(java, ["-m", "jdk.compiler/com.sun.tools.javac.Main", "-proc:none", "-encoding", "UTF-8",
                  "-classpath", cp, "-d", classes, *sources], OUTPUT / "compile.log")
    invoke(java, ["-classpath", cp, "dev.ermc.bridge.MountVanillaCheck"], OUTPUT / "vanilla.log")
    print((OUTPUT / "vanilla.log").read_text(encoding="utf-8", errors="replace"))
    print("PASS: mount slice compile against real named Minecraft 1.21.1 and Fabric APIs")


if __name__ == "__main__":
    main()
