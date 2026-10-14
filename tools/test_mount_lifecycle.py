"""Execute exact production cleanup bodies with lifecycle doubles and real MC ItemStacks.

This does not simulate a working Horse or start Minecraft/Elden Ring. The actual
release/collectRetired/returnEquipment/fits methods are extracted for every run.
"""
from pathlib import Path
import os
from test_lb_capability_adapter import single, invoke
from test_local_host_routing import function

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / "bridge-base/elden-ring/mc-bridge"
OUTPUT = ROOT / "runtime/experiments/mount-lifecycle-check"


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    classes = OUTPUT / "classes"
    classes.mkdir(exist_ok=True)
    production = (PROJECT / "src/main/java/dev/ermc/bridge/MountRuntime.java").read_text(encoding="utf-8")
    methods = "\n".join(function(production, signature) for signature in (
        "private static void release(", "private static void collectRetired(",
        "private static boolean returnEquipment(", "static boolean fits("))
    # Only redirect the fully qualified menu type to the explicitly labelled test double.
    methods = methods.replace("net.minecraft.world.inventory.HorseInventoryMenu", "HorseInventoryMenu")
    template = (PROJECT / "src/testFixtures/mount/MountLifecycleCheck.java.template").read_text(encoding="utf-8")
    source = OUTPUT / "MountLifecycleCheck.java"
    source.write_text(template.replace("/* PRODUCTION_METHODS */", methods), encoding="utf-8")
    cache = ROOT / ".tools/gradle-home/caches"
    dependencies = [single((cache / "fabric-loom/minecraftMaven/net/minecraft").glob(
        f"minecraft-{part}/1.21.1-loom.*/minecraft-{part}-*.jar"), f"named Minecraft {part}")
        for part in ("common", "clientonly")]
    dependencies += sorted(p for p in (cache / "modules-2/files-2.1").rglob("*.jar")
                           if not any(s in p.name for s in ("-sources", "-javadoc", "-natives-")))
    cp = os.pathsep.join(map(str, [classes, *dependencies]))
    java = ROOT / ".tools/java/jdk-25.0.4.1+1/bin/java.exe"
    invoke(java, ["-m", "jdk.compiler/com.sun.tools.javac.Main", "-proc:none", "-encoding", "UTF-8",
                  "-classpath", cp, "-d", classes, source], OUTPUT / "compile.log")
    invoke(java, ["-classpath", cp, "dev.ermc.bridge.mountfixture.MountLifecycleCheck"], OUTPUT / "lifecycle.log")
    print((OUTPUT / "lifecycle.log").read_text(encoding="utf-8", errors="replace"))


if __name__ == "__main__":
    main()
