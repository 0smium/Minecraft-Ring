"""Transform sleep guards against actual MC 1.21.1 and pinned LB #3530, without launching games."""
import argparse
import hashlib
import json
import os
from pathlib import Path
from test_lb_capability_adapter import dependencies, invoke, single, LB, LB_SHA256

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / "bridge-base/elden-ring/mc-bridge"
OUTPUT = ROOT / "runtime/experiments/sleep-action-mixin-check"


def main():
    version = next(line.split("=", 1)[1].strip() for line in (PROJECT / "gradle.properties").read_text().splitlines()
                   if line.strip().startswith("version="))
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bridge-jar", type=Path, default=PROJECT / f"build/libs/er-bridge-{version}.jar")
    parser.add_argument("--character-jar", type=Path, default=PROJECT / f"character-bridge/build/libs/er-character-bridge-{version}.jar")
    args = parser.parse_args()
    if hashlib.sha256(LB.read_bytes()).hexdigest() != LB_SHA256:
        parser.error("Expected the pinned official LB #3530 JAR")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for name, package, mixins in [
        ("sleep-common", "dev.ermc.bridge.mixin", ["ActionLockLivingMixin", "ActionLockPlayerMixin", "PlayerMixin", "LivingEntityMixin"]),
        ("sleep-mc", "dev.ermc.bridge.client.action.mixin", ["SleepInputMixin", "SleepMinecraftMixin", "SleepGameModeMixin"]),
        ("sleep-lb", "dev.ermc.bridge.client.integrations.liquidbounce", ["CapabilityModuleMixin", "CapabilityAuraMixin",
          "SleepEventMixin", "SleepScheduledActionMixin", "SleepSequenceMixin"]),
    ]:
        (OUTPUT / f"{name}.json").write_text(json.dumps({"required": True, "package": package,
            "compatibilityLevel": "JAVA_21", "client": mixins, "injectors": {"defaultRequire": 1}}), encoding="utf-8")
    services = OUTPUT / "META-INF/services"
    services.mkdir(parents=True, exist_ok=True)
    for service, implementation in [("IMixinService", "Service"), ("IGlobalPropertyService", "PropertiesService")]:
        (services / ("org.spongepowered.asm.service." + service)).write_text("OfflineMixinProbe$" + implementation + "\n")
    classpath = os.pathsep.join(str(path) for path in [OUTPUT, args.bridge_jar, args.character_jar, LB, *dependencies()])
    jdk = single((ROOT / ".tools/java").glob("*/bin/javac.exe"), "JDK").parent
    invoke(jdk / "javac.exe", ["-encoding", "UTF-8", "-classpath", classpath, "-d", OUTPUT,
        PROJECT / "src/testFixtures/lb-capability/OfflineMixinProbe.java",
        PROJECT / "src/testFixtures/sleep-action/SleepActionMixinProbe.java",
        PROJECT / "src/testFixtures/sleep-action/SleepActionPolicyProbe.java"], OUTPUT / "compile.log")
    for probe in ("SleepActionMixinProbe", "SleepActionPolicyProbe"):
        log = OUTPUT / f"{probe}.log"
        invoke(jdk / "java.exe", ["-classpath", classpath, probe], log)
        print(log.read_text(encoding="utf-8", errors="replace"))
    print("LIMIT: no live packet transport, physics, LB module lifecycle or game startup.")


if __name__ == "__main__":
    main()
