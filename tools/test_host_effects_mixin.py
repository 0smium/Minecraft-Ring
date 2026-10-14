"""Verify the remapped hidden-effect accessor on the actual MC 1.21.1 class, without games.

Run after the normal Fabric build; uses only the existing project JDK and cache.
"""
import json
import os
from pathlib import Path
from test_lb_capability_adapter import invoke, single

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / "bridge-base/elden-ring/mc-bridge"
OUTPUT = ROOT / "runtime/experiments/host-effect-mixin-check"


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    modules = ROOT / ".tools/gradle-home/caches/modules-2/files-2.1"
    minecraft = ROOT / ".tools/gradle-home/caches/fabric-loom/minecraftMaven/net/minecraft"
    dependencies = [single((modules / group / name / version).glob(f"*/{name}-{version}.jar"), name)
                    for group, name, version in [("net.fabricmc", "sponge-mixin", "0.17.4+mixin.0.8.7"),
                        ("io.github.llamalad7", "mixinextras-fabric", "0.5.5"), ("net.fabricmc", "fabric-loader", "0.19.5")]]
    for part in ("clientonly", "common"):
        dependencies.append(single(minecraft.glob(f"minecraft-{part}-intermediary/1.21.1-loom.*/minecraft-{part}-intermediary-*.jar"), part))
    dependencies.extend(p for p in modules.rglob("*.jar")
                        if not any(marker in p.name for marker in ("-sources", "-javadoc", "-natives-")))
    config = {"required": True, "package": "dev.ermc.bridge.mixin", "compatibilityLevel": "JAVA_21",
              "mixins": ["MobEffectInstanceAccessor"], "injectors": {"defaultRequire": 1}}
    (OUTPUT / "host-effect-offline-mixins.json").write_text(json.dumps(config), encoding="utf-8")
    services = OUTPUT / "META-INF/services"
    services.mkdir(parents=True, exist_ok=True)
    for service, implementation in [("IMixinService", "Service"), ("IGlobalPropertyService", "PropertiesService")]:
        (services / ("org.spongepowered.asm.service." + service)).write_text("OfflineMixinProbe$" + implementation + "\n", encoding="utf-8")
    classpath = os.pathsep.join(str(p) for p in dict.fromkeys([
        OUTPUT, PROJECT / "build/libs/er-bridge-0.3.3.jar",
        PROJECT / "character-bridge/build/libs/er-character-bridge-0.3.3.jar", *dependencies]))
    jdk = single((ROOT / ".tools/java").glob("*/bin/javac.exe"), "JDK").parent
    invoke(jdk / "javac.exe", ["-encoding", "UTF-8", "-classpath", classpath, "-d", OUTPUT,
           PROJECT / "src/testFixtures/lb-capability/OfflineMixinProbe.java",
           PROJECT / "src/testFixtures/effect-mixin/HostEffectMixinProbe.java"], OUTPUT / "compile.log")
    invoke(jdk / "java.exe", ["-classpath", classpath, "HostEffectMixinProbe"], OUTPUT / "transform.log")
    print((OUTPUT / "transform.log").read_text(encoding="utf-8", errors="replace"))


if __name__ == "__main__":
    main()
