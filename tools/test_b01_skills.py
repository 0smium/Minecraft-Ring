"""Validate the downloaded Skills artifact and parse Bridge's tree with its real classes.

After a standard compileJava has populated the local Loom cache, reproduce the
classpath without Gradle or downloads with:
  python tools/test_b01_skills.py --write-classpath runtime/experiments/b01-classpath.txt
Then pass --classpath-file runtime/experiments/b01-classpath.txt to this check
or to test_b01_payload.py. Cache ambiguity/missing libraries fail explicitly.
This is an offline parser/data check: it never starts a server, client or game UI.
The parser gets constructor-free registry/platform context fixtures; purchase checks
use the actual framework's normal affordability, point-cost and persistence methods.
It does not replace the later player/network/GUI runtime test.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "runtime/downloads/puffish_skills-0.19.2-1.21-fabric.jar"
SHA256 = "276ce0b5f15bcfeafe632c8eabe0d2d3104b9b0362897e66fd049bcd217ba915"
RESOURCES = ROOT / "bridge-base/elden-ring/mc-bridge/src/main/resources/data/erbridge/puffish_skills"


def cached_classpath():
    """Find the real named dependencies produced by the current local Loom build."""
    project = ROOT / "bridge-base/elden-ring/mc-bridge"
    properties = dict(line.split("=", 1) for line in (project / "gradle.properties").read_text().splitlines()
                      if "=" in line and not line.lstrip().startswith("#"))
    modules = ROOT / ".tools/gradle-home/caches/modules-2/files-2.1"
    loom = project / ".gradle/loom-cache"

    def single(paths, label):
        paths = list(paths)
        if len(paths) != 1:
            raise SystemExit(f"Expected one cached {label}, found {len(paths)}; run the standard build or supply a classpath file")
        return paths[0].resolve()

    skills = single((loom / "remapped_mods/remapped/unspecified").glob(
        "puffish_skills-0.19.2-1.21-fabric-*/*/puffish_skills-*.jar"), "named Skills JAR")
    mapping_suffix = skills.parent.parent.name.removeprefix("puffish_skills-0.19.2-1.21-fabric-")
    paths = [skills]
    metadata = ROOT / f".tools/gradle-home/caches/fabric-loom/{properties['minecraft_version']}/mojang_minecraft_info.json"
    for library in json.loads(metadata.read_text())['libraries']:
        name = library['name'].split(':')
        if len(name) != 3 or name[0] == 'ca.weblite':  # Native classifiers / macOS-only Objective-C bridge.
            continue
        group, artifact, version = name
        paths.append(single((modules / group / artifact / version).glob(f"*/{artifact}-{version}.jar"), library['name']))
    for part in ('common', 'clientOnly'):
        paths.append(single((loom / 'minecraftMaven/net/minecraft').glob(
            f"minecraft-{part}-*/{properties['minecraft_version']}-loom.*/minecraft-{part}-*.jar"), f"named Minecraft {part}"))
    api = loom / 'remapped_mods/remapped/net/fabricmc/fabric-api'
    fabric = sorted(path.resolve() for path in api.rglob('*.jar')
                    if mapping_suffix in path.name and not path.name.endswith('-sources.jar'))
    if not fabric:
        raise SystemExit('Missing remapped Fabric API modules; run the standard build first')
    paths.extend(fabric)
    loader = properties['loader_version']
    paths.append(single((modules / 'net.fabricmc/fabric-loader' / loader).glob(f"*/fabric-loader-{loader}.jar"), 'Fabric Loader'))
    return os.pathsep.join(str(path) for path in dict.fromkeys(paths))

JAVA = r'''
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.puffish.skillsmod.SkillsMod;
import net.puffish.skillsmod.api.Skill;
import net.puffish.skillsmod.api.config.ConfigContext;
import net.puffish.skillsmod.config.CategoryConfig;
import net.puffish.skillsmod.config.PackConfig;
import net.puffish.skillsmod.config.reader.FileConfigReader;
import net.puffish.skillsmod.server.data.CategoryData;
import net.puffish.skillsmod.server.setup.ServerPlatform;
import net.puffish.skillsmod.util.VersionedConfigContext;

public class B01SkillsCheck {
    static void check(boolean value,String message) {
        if(!value)throw new AssertionError(message);
    }
    static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafeClass=Class.forName("sun.misc.Unsafe");
        Field field=unsafeClass.getDeclaredField("theUnsafe");field.setAccessible(true);
        return unsafeClass.getMethod("allocateInstance",Class.class).invoke(field.get(null),type);
    }
    static void set(Class<?> owner,Object object,String fieldName,Object value) throws Exception {
        Field field=owner.getDeclaredField(fieldName);field.setAccessible(true);field.set(object,value);
    }
    static void purchase(CategoryData data,CategoryConfig category,String id) {
        var skill=category.skills().getById(id).orElseThrow();
        check(data.canUnlockSkill(category,skill,false),"Normal purchase denied: "+id);
        data.unlockSkill(id);
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // The parser needs registry access, but no constructor, world or server thread runs.
        MinecraftServer contextServer=(MinecraftServer)allocate(DedicatedServer.class);
        set(MinecraftServer.class,contextServer,"registries",RegistryLayer.createRegistryAccess());
        SkillsMod contextMod=(SkillsMod)allocate(SkillsMod.class);
        ServerPlatform platform=(ServerPlatform)Proxy.newProxyInstance(ServerPlatform.class.getClassLoader(),
            new Class<?>[]{ServerPlatform.class},(proxy,method,arguments) -> false);
        set(SkillsMod.class,contextMod,"platform",platform);
        set(SkillsMod.class,null,"instance",contextMod);
        ConfigContext context=new VersionedConfigContext(new ConfigContext() {
            public MinecraftServer getServer() {return contextServer;}
            public void emitWarning(String message) {throw new AssertionError(message);}
        },3);
        var reader=new FileConfigReader(Path.of(args[0]));
        var pack=PackConfig.parse("erbridge",reader.read(Path.of("config.json")).getSuccess().orElseThrow(),context)
            .getSuccess().orElseThrow();
        check(pack.version()==3 && pack.categories().equals(List.of("abilities")),"Pack category declaration");
        var parsed=reader.readCategory("erbridge","abilities",0,context);
        if(parsed.getSuccess().isEmpty())throw new AssertionError(parsed.getFailure().orElseThrow().toString());
        CategoryConfig category=parsed.getSuccess().orElseThrow();
        check(category.skills().getAll().size()==6,"Exactly three ranks in each of two abilities");
        check(category.experience().isEmpty() && category.exchange().isEmpty(),"No extra XP or exchange point source");
        check(category.definitions().getAll().stream().allMatch(d -> d.rewards().isEmpty()),"No duplicate attribute rewards");
        System.out.println("PASS: real Skills 0.19.2 parses the pack, category, icons, definitions and directed graph");

        var source=ResourceLocation.parse("erbridge:er_level");
        CategoryData a=CategoryData.create(category.general()),b=CategoryData.create(category.general());
        check(!a.isUnlocked() && a.getPointsTotal()==0,"An unbound player receives no category or points");
        a.unlock();a.setPoints(source,2);b.unlock();b.setPoints(source,1);
        check(!a.canUnlockSkill(category,category.skills().getById("speed_2").orElseThrow(),false),"Cannot skip Speed I");
        check(!a.canUnlockSkill(category,category.skills().getById("aura_2").orElseThrow(),false),"Cannot skip Aura I");
        purchase(a,category,"speed_1");purchase(a,category,"speed_2");purchase(b,category,"aura_1");
        check(a.getSpentPoints(category)==2 && a.getPointsLeft(category)==0,"A consumes its two point costs");
        check(b.getSpentPoints(category)==1 && b.getPointsLeft(category)==0,"B consumes only its own point cost");
        check(!a.canUnlockSkill(category,category.skills().getById("aura_1").orElseThrow(),false),"No purchase with zero remaining points");
        check(!a.canUnlockSkill(category,category.skills().getById("speed_1").orElseThrow(),false),"No duplicate purchase");
        check(!a.getUnlockedSkillIds().contains("aura_1") && !b.getUnlockedSkillIds().contains("speed_1"),"Independent framework data");
        System.out.println("PASS: actual normal affordability, prerequisites, costs, duplicate rejection and separate player data");
        a.setPoints(source,2);a.setPoints(source,2);
        check(a.getSpentPoints(category)==2 && a.getPointsLeft(category)==0,"Cumulative reconciliation preserves spending");
        a.setPoints(source,3);
        check(a.getPointsLeft(category)==1 && b.getPointsLeft(category)==0,"One new source point without cross-player changes");
        purchase(a,category,"speed_3");
        check(a.getSpentPoints(category)==3 && a.getPointsLeft(category)==0,"Speed III consumes the third earned point");
        a.resetSkills();
        check(a.getSpentPoints(category)==0 && a.getPointsLeft(category)==3,"Framework respec restores points, not a second balance");
        purchase(a,category,"aura_1");purchase(a,category,"aura_2");purchase(a,category,"aura_3");
        var restored=CategoryData.read(a.writeNbt(new CompoundTag()));
        check(restored.isUnlocked() && restored.getPoints(source)==3 && restored.getSpentPoints(category)==3
            && restored.getPointsLeft(category)==0 && restored.getUnlockedSkillIds().equals(Set.of("aura_1","aura_2","aura_3")),
            "Framework NBT restores source quota and learned skills together");
        System.out.println("PASS: cumulative source reconciliation, real data reset/refund and framework NBT restoration");
        System.out.println("LIMIT: offline parser/data validation; player callbacks, GUI, config reload and ER/LB co-load remain untested");
    }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath-file", type=Path)
    parser.add_argument("--write-classpath", type=Path, help="Write only the already-built named cache classpath; do not run a check")
    parser.add_argument("--java-home", type=Path)
    args = parser.parse_args()
    if args.write_classpath:
        classpath = cached_classpath()
        args.write_classpath.parent.mkdir(parents=True, exist_ok=True)
        args.write_classpath.write_text(classpath + '\n', encoding='utf-8')
        print(f"Wrote {len(classpath.split(os.pathsep))} actual cached dependency entries to {args.write_classpath}")
        return
    if not args.classpath_file:
        parser.error('--classpath-file is required for the check; use --write-classpath after the standard build')
    data = JAR.read_bytes()
    if hashlib.sha256(data).hexdigest() != SHA256:
        raise SystemExit("Skills artifact SHA-256 mismatch")
    with zipfile.ZipFile(JAR) as archive:
        metadata = json.loads(archive.read("fabric.mod.json"))
    assert metadata["id"] == "puffish_skills" and metadata["version"] == "0.19.2"
    assert metadata["depends"]["minecraft"] == "~1.21" and metadata["environment"] == "*"
    assert metadata["license"] == "All Rights Reserved"
    print("PASS: official Skills artifact SHA-256, version, MC range and loader metadata", flush=True)
    classpath = args.classpath_file.read_text(encoding="utf-8-sig").strip()
    java_home = args.java_home or next((ROOT / ".tools/java").glob("*/bin/java.exe")).parent.parent
    executable = ".exe" if os.name == "nt" else ""
    with tempfile.TemporaryDirectory(prefix="ermc-skills-") as directory:
        output = Path(directory)
        source = output / "B01SkillsCheck.java"
        source.write_text(JAVA, encoding="utf-8")
        subprocess.run([str(java_home / "bin" / f"javac{executable}"), "-encoding", "UTF-8", "-cp", classpath,
                        "-d", str(output), str(source)], cwd=ROOT, check=True)
        subprocess.run([str(java_home / "bin" / f"java{executable}"), "-cp", str(output) + os.pathsep + classpath,
                        "B01SkillsCheck", str(RESOURCES)], cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
