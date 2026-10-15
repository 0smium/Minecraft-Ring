"""Check the built Core/Character boundary and load entrypoint types without games.

Run after the matching build: no options for Core + Character without Skills,
--b01 for the optional Skills artifact, or --core-only for just the base JAR.
This verifies actual JARs and JVM linkage, not Fabric entrypoint execution or play.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

from test_b01_preparation import java_path
from test_lb_capability_adapter import single, argument_file

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / "bridge-base/elden-ring/mc-bridge"
PREFIX = "dev/ermc/bridge/"


def read_artifact(path):
    with zipfile.ZipFile(path) as jar:
        metadata = json.loads(jar.read("fabric.mod.json"))
        contents = {name: jar.read(name) for name in jar.namelist() if not name.endswith("/")}
    return metadata, contents


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def entrypoints(metadata):
    return [item if isinstance(item, str) else item["value"]
            for group in metadata["entrypoints"].values() for item in group]


def check_metadata(metadata, contents, available):
    for name in entrypoints(metadata):
        require(name.replace(".", "/") + ".class" in contents, f"Entrypoint not in own JAR: {name}")
    for mixin in metadata.get("mixins", []):
        config_name = mixin if isinstance(mixin, str) else mixin["config"]
        require(config_name in contents, f"Missing Mixin config: {config_name}")
        config = json.loads(contents[config_name])
        names = [config["package"] + "." + name for group in ("mixins", "client", "server")
                 for name in config.get(group, [])]
        if config.get("plugin"):
            names.append(config["plugin"])
        for name in names:
            require(name.replace(".", "/") + ".class" in available, f"Missing Mixin/plugin: {name}")


PROBE = r'''
import java.net.*;
import java.nio.file.*;
import java.util.*;
public final class SplitEntrypointProbe {
    public static void main(String[] args) throws Exception {
        String[] paths=Files.readString(Path.of(args[0])).split(java.util.regex.Pattern.quote(java.io.File.pathSeparator));
        URL[] urls=Arrays.stream(paths).map(p->{try{return Path.of(p).toUri().toURL();}catch(Exception e){throw new RuntimeException(e);}}).toArray(URL[]::new);
        try(URLClassLoader loader=new URLClassLoader(urls,ClassLoader.getPlatformClassLoader()){
            @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
                if(name.startsWith("net.puffish.") || name.startsWith("net.ccbluex.") || name.startsWith("meteordevelopment."))
                    throw new ClassNotFoundException("Optional client/framework excluded: "+name);
                return super.loadClass(name,resolve);
            }
        }) {
            for(int i=1;i<args.length;i++) {
                Class<?> type=Class.forName(args[i],false,loader);
                type.getDeclaredMethods(); type.getDeclaredConstructors();
                System.out.println("PASS: entrypoint/API type links without optional mods: "+args[i]);
            }
        }
    }
}
'''


def link_entrypoints(jars, names):
    cache = ROOT / ".tools/gradle-home/caches"
    modules = cache / "modules-2/files-2.1"
    paths = list(jars)
    properties = dict(line.split("=", 1) for line in (PROJECT / "gradle.properties").read_text().splitlines()
                      if "=" in line and not line.lstrip().startswith("#"))
    for part in ("common", "clientonly"):
        paths.append(single((cache / "fabric-loom/minecraftMaven/net/minecraft").glob(
            f"minecraft-{part}-intermediary/1.21.1-loom.*/minecraft-{part}-intermediary-*.jar"), f"MC {part}"))
    loader = properties["loader_version"]
    paths.append(single((modules / "net.fabricmc/fabric-loader" / loader).glob(f"*/fabric-loader-{loader}.jar"), "Fabric Loader"))
    metadata = cache / f"fabric-loom/{properties['minecraft_version']}/mojang_minecraft_info.json"
    for library in json.loads(metadata.read_text())["libraries"]:
        parts = library["name"].split(":")
        if len(parts) != 3 or parts[0] == "ca.weblite":
            continue  # Native classifiers and macOS-only Objective-C bridge.
        group, name, version = parts
        paths.append(single((modules / group / name / version).glob(f"*/{name}-{version}.jar"), library["name"]))
    java = java_path(None)
    with tempfile.TemporaryDirectory(prefix="ermc-split-") as temporary:
        work = Path(temporary)
        source = work / "SplitEntrypointProbe.java"
        source.write_text(PROBE, encoding="utf-8")
        subprocess.run([java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "-d", str(work), str(source)], check=True)
        cp_file = work / "dependencies.txt"
        cp_file.write_text(os.pathsep.join(map(str, paths)), encoding="utf-8")
        args = work / "probe.args"
        argument_file(args, ["-cp", work, "SplitEntrypointProbe", cp_file, *names])
        subprocess.run([java, "@" + str(args)], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core-only", action="store_true")
    parser.add_argument("--b01", action="store_true")
    parser.add_argument("--core-jar", type=Path, default=PROJECT / "build/libs/er-bridge-0.3.7.jar")
    parser.add_argument("--character-jar", type=Path, default=PROJECT / "character-bridge/build/libs/er-character-bridge-0.3.7.jar")
    args = parser.parse_args()
    if args.core_only and args.b01:
        parser.error("--core-only and --b01 are mutually exclusive")
    core_meta, core = read_artifact(args.core_jar)
    require(core_meta["id"] == "erbridge", "Core mod identity changed")
    require(not set(core_meta["depends"]) & {"er_character_bridge", "puffish_skills", "liquidbounce", "meteor-client"},
            "Core declares optional character/client dependencies")
    character_types = ("MountRuntime", "MountMotion", "MountPayload", "HostEffectsBridge", "link/HostEffectRules",
                       "client/HostInventoryScreen", "client/MountClient", "client/ProgressionClient", "client/PrecisionFlightClient",
                       "client/integrations/CapabilityPolicyClient", "client/integrations/LiquidBounceCapabilityAccess")
    for name in character_types:
        require(PREFIX + name + ".class" not in core, f"Character implementation remains in Core: {name}")
    for name, data in core.items():
        if name.endswith(".class"):
            require(not name.startswith(PREFIX + "gameplay/"), f"Gameplay remains in Core: {name}")
            require(b"net/puffish/" not in data, f"Core links Skills: {name}")
            require(b"dev/ermc/bridge/character/" not in data, f"Core links Character implementation: {name}")
            for character in character_types:
                require((PREFIX + character).encode() not in data, f"Core links character type {character}: {name}")
    jars = [args.core_jar.resolve()]
    names = entrypoints(core_meta) + ["dev.ermc.bridge.api.PlayerFlight"]
    combined = core
    if not args.core_only:
        meta, character = read_artifact(args.character_jar)
        require(meta["id"] == "er_character_bridge", "Wrong Character mod identity")
        require(meta["version"] == core_meta["version"], "JAR versions differ")
        require(meta["depends"]["erbridge"] == "=" + core_meta["version"], "Character must require the paired Core version")
        duplicates = {n for n in core.keys() & character.keys() if n.endswith(".class")}
        require(not duplicates, f"Duplicate classes in split JARs: {sorted(duplicates)}")
        for name in character_types:
            require(PREFIX + name + ".class" in character, f"Missing migrated Character implementation: {name}")
        adapter = PREFIX + "gameplay/skills/PuffishSkillsAdapter.class"
        require((adapter in character) == args.b01, "Skills adapter presence does not match requested profile")
        require(any(n.startswith("data/erbridge/puffish_skills/") for n in character) == args.b01,
                "Skills data presence does not match requested profile")
        if not args.b01:
            for name, data in character.items():
                if name.endswith(".class"):
                    require(b"net/puffish/" not in data, f"Character-only JAR links Skills: {name}")
        combined = core | character
        check_metadata(meta, character, combined)
        jars.append(args.character_jar.resolve())
        names += entrypoints(meta)
        names += ["dev.ermc.bridge.gameplay.skills.SkillsBridge"]
    check_metadata(core_meta, core, combined)
    link_entrypoints(jars, names)
    for path in jars:
        print(f"SHA-256 {path.name}: {hashlib.sha256(path.read_bytes()).hexdigest()}")
    print("PASS: actual JAR ownership, metadata, Mixins and optional-dependency type linkage")
    print("LIMIT: no Fabric initialization, live world, GUI, Skills purchase or game startup was executed.")


if __name__ == "__main__":
    main()
