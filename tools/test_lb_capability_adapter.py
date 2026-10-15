"""Check fixed LB3530 B01 injections and real Value execution, without starting games.

After the normal production build has populated the local cache:
  python tools/test_lb_capability_adapter.py
Optional: --bridge-jar PATH, --java-home PATH, --classpath-file PATH.
The default discovers existing production/intermediary dependencies and extracts
LB's bundled JARs only into ignored runtime/experiments. No Gradle or downloads.
This checks Kotlin's public float-range factory/access handles, 15 real Mixin injections, normal helper class loading, real Value
set/get/inner/restore/Gson import, locking, downgrade and free-mode preservation,
and real ChoiceConfigurable switches with explicit lifecycle-counter/module-enabled fixtures.
It does NOT initialize actual LB module singletons or execute live module cleanup,
GUI, networking, movement/attacks or game startup. Those still need human testing.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / "bridge-base/elden-ring/mc-bridge"
LB = ROOT / "runtime/downloads/liquidbounce.jar"
LB_SHA256 = "1824011d91046f8f8b388cfa63782f38cdf754f12cb997317eab8e69e67ef20d"
FIXTURES = PROJECT / "src/testFixtures/lb-capability"
OUTPUT = ROOT / "runtime/experiments/lb-capability-check"
MIXINS = ["CapabilityValueMixin", "CapabilityChoiceMixin", "CapabilityModuleMixin",
          "CapabilityManagerMixin", "CapabilityAuraMixin", "CapabilityToggleableMixin", "CapabilityBHopMixin",
          "CapabilityModuleListMixin", "CapabilitySettingsViewMixin"]


def single(paths, label):
    paths = list(paths)
    if len(paths) != 1:
        raise SystemExit(f"Expected one cached {label}, found {len(paths)}; build normally first or supply --classpath-file")
    return paths[0].resolve()


def dependencies():
    cache = ROOT / ".tools/gradle-home/caches"
    modules = cache / "modules-2/files-2.1"
    # Put the actual launcher versions first, rather than an older cached loader.
    paths = [single((modules / group / artifact / version).glob(f"*/{artifact}-{version}.jar"), artifact)
             for group, artifact, version in [("net.fabricmc", "sponge-mixin", "0.17.4+mixin.0.8.7"),
                 ("io.github.llamalad7", "mixinextras-fabric", "0.5.5"), ("net.fabricmc", "fabric-loader", "0.19.5")]]
    for part in ("clientonly", "common"):
        paths.append(single((cache / "fabric-loom/minecraftMaven/net/minecraft").glob(
            f"minecraft-{part}-intermediary/1.21.1-loom.*/minecraft-{part}-intermediary-*.jar"), f"intermediary MC {part}"))
    with zipfile.ZipFile(LB) as jar:
        bundled = OUTPUT / "bundled"
        bundled.mkdir(exist_ok=True)
        for name in sorted(jar.namelist()):
            if name.startswith("META-INF/jars/") and name.endswith(".jar"):
                path = bundled / Path(name).name
                path.write_bytes(jar.read(name))
                paths.append(path)
    paths.extend(sorted(path for path in modules.rglob("*.jar")
                        if not any(marker in path.name for marker in ("-sources", "-javadoc", "-natives-"))))
    return list(dict.fromkeys(paths))


def argument_file(path, arguments):
    # Java argfiles interpret backslash escapes; use forward slashes on Windows.
    path.write_text("\n".join('"' + str(value).replace("\\", "/").replace('"', '\\"') + '"'
                              for value in arguments) + "\n", encoding="utf-8")


def invoke(binary, arguments, log):
    args_file = log.with_suffix(".args")
    argument_file(args_file, arguments)
    with log.open("w", encoding="utf-8") as stream:
        result = subprocess.run([str(binary), "@" + str(args_file)], stdout=stream, stderr=subprocess.STDOUT)
    if result.returncode:
        print(log.read_text(encoding="utf-8", errors="replace"))
        raise SystemExit(result.returncode)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bridge-jar", type=Path, default=PROJECT / "build/libs/er-bridge-0.3.6.jar")
    parser.add_argument("--character-jar", type=Path, default=PROJECT / "character-bridge/build/libs/er-character-bridge-0.3.6.jar")
    parser.add_argument("--java-home", type=Path, default=ROOT / ".tools/java/jdk-25.0.4.1+1")
    parser.add_argument("--classpath-file", type=Path)
    args = parser.parse_args()
    if not args.bridge_jar.is_file() or not args.character_jar.is_file() or not LB.is_file():
        parser.error("The built Core + Character production JARs and prepared fixed LB JAR are required")
    if hashlib.sha256(LB.read_bytes()).hexdigest() != LB_SHA256:
        parser.error("LB JAR differs from fixed official build #3530")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    config = {"required": True, "package": "dev.ermc.bridge.client.integrations.liquidbounce",
              "compatibilityLevel": "JAVA_21", "client": MIXINS, "injectors": {"defaultRequire": 1}}
    (OUTPUT / "offline-mixins.json").write_text(json.dumps(config), encoding="utf-8")
    config["client"] = ["CapabilityValueMixin", "CapabilityChoiceMixin"]
    (OUTPUT / "value-runtime-mixins.json").write_text(json.dumps(config), encoding="utf-8")
    services = OUTPUT / "META-INF/services"
    services.mkdir(parents=True, exist_ok=True)
    for name, implementation in [("IMixinService", "Service"), ("IGlobalPropertyService", "PropertiesService")]:
        (services / ("org.spongepowered.asm.service." + name)).write_text("OfflineMixinProbe$" + implementation + "\n")
    extra = (args.classpath_file.read_text(encoding="utf-8-sig").strip().split(os.pathsep)
             if args.classpath_file else dependencies())
    classpath = os.pathsep.join(str(path) for path in [OUTPUT, args.bridge_jar.resolve(), args.character_jar.resolve(), LB, *extra])
    suffix = ".exe" if os.name == "nt" else ""
    invoke(args.java_home / f"bin/javac{suffix}", ["-encoding", "UTF-8", "-classpath", classpath, "-d", OUTPUT,
           FIXTURES / "OfflineMixinProbe.java", FIXTURES / "RuntimeValueProbe.java", FIXTURES / "KotlinRangeProbe.java",
           FIXTURES / "GuiPolicyProbe.java", FIXTURES / "RoutePolicyProbe.java", FIXTURES / "FixtureChoice.java"], OUTPUT / "compile.log")
    for probe in ("KotlinRangeProbe", "OfflineMixinProbe", "RuntimeValueProbe"):
        log = OUTPUT / (probe + ".log")
        invoke(args.java_home / f"bin/java{suffix}", ["-classpath", classpath, probe], log)
        print(f"PASS {probe}: {log}")
    print("Bridge SHA-256: " + hashlib.sha256(args.bridge_jar.read_bytes()).hexdigest())
    print("Character SHA-256: " + hashlib.sha256(args.character_jar.read_bytes()).hexdigest())
    print("LIMIT: no module singleton initialization, live module cleanup, GUI, networking, movement/attack or game startup.")


if __name__ == "__main__":
    main()
