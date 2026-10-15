"""Real SavedData binary NBT and surface-evidence checks; no game or world starts."""
from pathlib import Path
import os
from test_lb_capability_adapter import single, invoke

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / "bridge-base/elden-ring/mc-bridge"
OUTPUT = ROOT / "runtime/experiments/surface-resource-state-check"


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    classes = OUTPUT / "classes"
    classes.mkdir(exist_ok=True)
    cache = ROOT / ".tools/gradle-home/caches"
    deps = [single((PROJECT / ".gradle/loom-cache/minecraftMaven/net/minecraft").glob(
        f"minecraft-{part}-*/1.21.1-loom.*/minecraft-{part}-*.jar"), f"widened {part}")
        for part in ("common", "clientOnly")]
    deps += sorted(p for p in (PROJECT / ".gradle/loom-cache/remapped_mods").rglob("*.jar")
                   if not any(s in p.name for s in ("-sources", "-javadoc")))
    deps += sorted(p for p in (cache / "modules-2/files-2.1").rglob("*.jar")
                   if not any(s in p.name for s in ("-sources", "-javadoc", "-natives-")))
    cp = os.pathsep.join(map(str, [classes, PROJECT / "build/classes/java/main", *deps]))
    java = ROOT / ".tools/java/jdk-25.0.4.1+1/bin/java.exe"
    sources = [PROJECT / "src/main/java/dev/ermc/bridge/world" / f"{name}.java"
               for name in ("SurfaceResourcePlan", "SurfaceResourceSites", "SurfaceResourceExclusion",
                            "SurfaceResourceData", "SurfaceResources")]
    sources.append(PROJECT / "src/testFixtures/world/SurfaceResourceStateCheck.java")
    invoke(java, ["-m", "jdk.compiler/com.sun.tools.javac.Main", "-proc:none", "-encoding", "UTF-8",
                  "-classpath", cp, "-d", classes, *sources], OUTPUT / "compile.log")
    invoke(java, ["-classpath", cp, "dev.ermc.bridge.world.SurfaceResourceStateCheck"], OUTPUT / "check.log")
    print((OUTPUT / "check.log").read_text(encoding="utf-8", errors="replace"))


if __name__ == "__main__":
    main()
