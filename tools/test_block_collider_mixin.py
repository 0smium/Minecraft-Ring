"""Transform the production placed-block revision Mixin on actual MC 1.21.1 bytecode.

Uses the existing remapped Core JAR, JDK and dependency cache. Does not build,
download dependencies, initialize a Minecraft world or start either game.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
from test_lb_capability_adapter import invoke, single

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / "bridge-base/elden-ring/mc-bridge"
OUTPUT = ROOT / "runtime/experiments/block-collider-mixin-check"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bridge-jar", type=Path, default=PROJECT / "build/libs/er-bridge-0.3.4.jar")
    args = parser.parse_args()
    if not args.bridge_jar.is_file():
        parser.error("Build the remapped Core JAR first")
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
              "mixins": ["PlacedBlockColliderRevisionMixin"], "injectors": {"defaultRequire": 1}}
    (OUTPUT / "block-collider-offline-mixins.json").write_text(json.dumps(config), encoding="utf-8")
    services = OUTPUT / "META-INF/services"
    services.mkdir(parents=True, exist_ok=True)
    for service, implementation in [("IMixinService", "Service"), ("IGlobalPropertyService", "PropertiesService")]:
        (services / ("org.spongepowered.asm.service." + service)).write_text("OfflineMixinProbe$" + implementation + "\n", encoding="utf-8")
    classpath = os.pathsep.join(str(p) for p in dict.fromkeys([OUTPUT, args.bridge_jar.resolve(), *dependencies]))
    jdk = single((ROOT / ".tools/java").glob("*/bin/javac.exe"), "JDK").parent
    invoke(jdk / "javac.exe", ["-encoding", "UTF-8", "-classpath", classpath, "-d", OUTPUT,
           PROJECT / "src/testFixtures/lb-capability/OfflineMixinProbe.java",
           PROJECT / "src/testFixtures/collision/PlacedColliderMixinProbe.java"], OUTPUT / "compile.log")
    invoke(jdk / "java.exe", ["-classpath", classpath, "PlacedColliderMixinProbe"], OUTPUT / "transform.log")
    print(f"Core JAR: {args.bridge_jar.resolve()}")
    print(f"SHA-256: {hashlib.sha256(args.bridge_jar.read_bytes()).hexdigest()}")
    print((OUTPUT / "transform.log").read_text(encoding="utf-8", errors="replace"))


if __name__ == "__main__":
    main()
