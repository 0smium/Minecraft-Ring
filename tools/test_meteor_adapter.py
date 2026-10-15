"""Apply Bridge Mixins to the actual official Meteor 0.5.8 JAR, without starting games.

Run after the regular production build: python tools/test_meteor_adapter.py
Requires the project JDK/cache, prepared Meteor JAR and remapped bridge JAR.
Uses the existing offline Mixin service, the real MixinExtras and intermediary MC.
This is stronger than a descriptor scan but does not replace module/runtime tests.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
from prepare_meteor import OFFICIAL_SHA256, inspect_jar
from test_lb_capability_adapter import single, invoke

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / "bridge-base/elden-ring/mc-bridge"
OUTPUT = ROOT / "runtime/experiments/meteor-adapter-check"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--meteor-jar", type=Path, default=ROOT / "runtime/downloads/meteor-client-0.5.8.jar")
    parser.add_argument("--bridge-jar", type=Path, default=PROJECT / "build/libs/er-bridge-0.3.6.jar")
    parser.add_argument("--java-home", type=Path, default=ROOT / ".tools/java/jdk-25.0.4.1+1")
    args = parser.parse_args()
    data = args.meteor_jar.read_bytes()
    if hashlib.sha256(data).hexdigest() != OFFICIAL_SHA256:
        raise SystemExit("Expected the official pinned Meteor 0.5.8 artifact")
    inspect_jar(data)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    modules = ROOT / ".tools/gradle-home/caches/modules-2/files-2.1"
    cache = ROOT / ".tools/gradle-home/caches/fabric-loom/minecraftMaven/net/minecraft"
    dependencies = [single((modules / group / name / version).glob(f"*/{name}-{version}.jar"), name)
                    for group, name, version in [("net.fabricmc", "sponge-mixin", "0.17.4+mixin.0.8.7"),
                        ("io.github.llamalad7", "mixinextras-fabric", "0.5.5"), ("net.fabricmc", "fabric-loader", "0.19.5")]]
    for part in ("clientonly", "common"):
        dependencies.append(single(cache.glob(f"minecraft-{part}-intermediary/1.21.1-loom.*/minecraft-{part}-intermediary-*.jar"), part))
    dependencies.extend(p for p in modules.rglob("*.jar") if not any(s in p.name for s in ("-sources", "-javadoc", "-natives-")))
    config = {"required": True, "package": "dev.ermc.bridge.client.integrations.meteor", "compatibilityLevel": "JAVA_21",
              "client": ["KillAuraMixin", "NametagsMixin", "HealthSortMixin"], "injectors": {"defaultRequire": 1}}
    (OUTPUT / "meteor-offline-mixins.json").write_text(json.dumps(config), encoding="utf-8")
    services = OUTPUT / "META-INF/services"
    services.mkdir(parents=True, exist_ok=True)
    for service, implementation in [("IMixinService", "Service"), ("IGlobalPropertyService", "PropertiesService")]:
        (services / ("org.spongepowered.asm.service." + service)).write_text("OfflineMixinProbe$" + implementation + "\n", encoding="utf-8")
    classpath = os.pathsep.join(str(p) for p in dict.fromkeys([OUTPUT, args.bridge_jar.resolve(), args.meteor_jar.resolve(), *dependencies]))
    suffix = ".exe" if os.name == "nt" else ""
    invoke(args.java_home / f"bin/javac{suffix}", ["-encoding", "UTF-8", "-classpath", classpath, "-d", OUTPUT,
           PROJECT / "src/testFixtures/lb-capability/OfflineMixinProbe.java", PROJECT / "src/testFixtures/meteor/MeteorMixinProbe.java"], OUTPUT / "compile.log")
    invoke(args.java_home / f"bin/java{suffix}", ["-classpath", classpath, "MeteorMixinProbe"], OUTPUT / "transform.log")
    print((OUTPUT / "transform.log").read_text(encoding="utf-8", errors="replace"))
    print("Bridge SHA-256: " + hashlib.sha256(args.bridge_jar.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
