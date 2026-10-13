"""Compile optional adapter handlers with platform stubs and test artifact preflight.

The synthetic Meteor classes test the class-file inspector, not real Mixin application.
Pass --meteor-jar to additionally inspect an actual official release artifact.
"""
import argparse
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile
from prepare_meteor import inspect_jar, class_symbols
from test_b01_preparation import java_path
from test_host_effects import STUBS as EFFECT_STUBS

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "bridge-base/elden-ring/mc-bridge/src"
STUBS = {name: EFFECT_STUBS[name] for name in (
    "org/slf4j/Logger.java", "org/slf4j/LoggerFactory.java", "net/minecraft/network/chat/Component.java",
    "dev/ermc/bridge/TerrainManager.java")}
STUBS.update({
    "dev/ermc/bridge/link/ErLink.java": 'package dev.ermc.bridge.link; public final class ErLink {private static final ErLink I=new ErLink();public boolean connected=true;public static ErLink get(){return I;} public boolean alive(){return connected;}}',
    "net/minecraft/world/entity/Entity.java": 'package net.minecraft.world.entity; public class Entity {public net.minecraft.network.chat.Component getName(){return new net.minecraft.network.chat.Component("host name");}}',
    "net/minecraft/world/entity/LivingEntity.java": 'package net.minecraft.world.entity; public class LivingEntity extends Entity {public float getHealth(){return 20;}}',
    "dev/ermc/bridge/entity/ErEntity.java": '''package dev.ermc.bridge.entity;
public class ErEntity extends net.minecraft.world.entity.LivingEntity {
public long id=1; public float health=1000,max=2000; public boolean enemy=true,alive=true,invisible=false;
public long hostId(){return id;} public float hp(){return health;} public float maxHp(){return max;}
public boolean isAlive(){return alive;} public boolean isTargetableEnemy(){return enemy;}
public boolean isInvisible(){return invisible;}
}''',
    "net/fabricmc/loader/api/FabricLoader.java": '''package net.fabricmc.loader.api;
import java.util.Optional;
public final class FabricLoader {
public static String version; private static final FabricLoader I=new FabricLoader();
public static FabricLoader getInstance(){return I;}
public record Version(String getFriendlyString) {} public record Metadata(Version getVersion) {} public record Mod(Metadata getMetadata) {}
public Optional<Mod> getModContainer(String id){return version==null ? Optional.empty() : Optional.of(new Mod(new Metadata(new Version(version))));}
}''',
    "org/objectweb/asm/tree/ClassNode.java": 'package org.objectweb.asm.tree; public class ClassNode {}',
    "org/spongepowered/asm/mixin/extensibility/IMixinInfo.java": 'package org.spongepowered.asm.mixin.extensibility; public interface IMixinInfo {}',
    "org/spongepowered/asm/mixin/extensibility/IMixinConfigPlugin.java": '''package org.spongepowered.asm.mixin.extensibility;
import java.util.*; import org.objectweb.asm.tree.ClassNode;
public interface IMixinConfigPlugin {
void onLoad(String s); String getRefMapperConfig(); boolean shouldApplyMixin(String a,String b);
void acceptTargets(Set<String> a,Set<String> b); List<String> getMixins();
void preApply(String a,ClassNode b,String c,IMixinInfo d); void postApply(String a,ClassNode b,String c,IMixinInfo d);
}''',
    "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable.java": '''package org.spongepowered.asm.mixin.injection.callback;
public final class CallbackInfoReturnable<T> {public T value;public boolean cancelled;public void setReturnValue(T v){value=v;cancelled=true;}}''',
})
ANNOTATIONS = {
    "org/spongepowered/asm/mixin/Mixin": 'String[] targets(); boolean remap() default true;',
    "org/spongepowered/asm/mixin/Pseudo": '',
    "org/spongepowered/asm/mixin/Shadow": 'boolean remap() default true;',
    "org/spongepowered/asm/mixin/injection/At": 'String value(); String target() default ""; boolean remap() default true;',
    "org/spongepowered/asm/mixin/injection/Inject": 'String[] method(); At at(); boolean cancellable() default false; boolean remap() default true;',
    "org/spongepowered/asm/mixin/injection/Redirect": 'String[] method(); At at(); boolean remap() default true;',
    "com/llamalad7/mixinextras/injector/ModifyExpressionValue": 'String[] method(); org.spongepowered.asm.mixin.injection.At at(); boolean remap() default true;',
    "com/llamalad7/mixinextras/sugar/Local": 'boolean argsOnly() default false;',
}
for name, body in ANNOTATIONS.items():
    package, simple = name.rsplit("/", 1)
    STUBS[name + ".java"] = f"package {package.replace('/', '.')}; public @interface {simple} {{ {body} }}"

SYNTHETIC = {
    "net/minecraft/class_1297.java": 'package net.minecraft; public class class_1297 {}',
    "net/minecraft/class_2561.java": 'package net.minecraft; public class class_2561 {}',
    "net/minecraft/class_1299.java": 'package net.minecraft; public class class_1299 {public class_2561 method_5897(){return null;}}',
    "net/minecraft/class_1309.java": '''package net.minecraft; public class class_1309 extends class_1297 {
public float method_6032(){return 1;} public float method_6063(){return 1;} public float method_6067(){return 0;}
public class_1299 type(){return new class_1299();}}''',
    "meteordevelopment/meteorclient/systems/modules/combat/KillAura.java": '''package meteordevelopment.meteorclient.systems.modules.combat;
public class KillAura {private boolean entityCheck(net.minecraft.class_1297 entity){return entity!=null;}}''',
    "meteordevelopment/meteorclient/systems/modules/render/Nametags.java": '''package meteordevelopment.meteorclient.systems.modules.render;
public class Nametags {
private void renderGenericLivingNametag(net.minecraft.class_1309 entity,boolean shadow) {
System.out.println(entity.type().method_5897());
System.out.println(String.valueOf((int)(entity.method_6032()+entity.method_6063()+entity.method_6067())));
}}''',
    "meteordevelopment/meteorclient/utils/entity/SortPriority.java": '''package meteordevelopment.meteorclient.utils.entity;
public class SortPriority {private static int sortHealth(net.minecraft.class_1297 a,net.minecraft.class_1297 b) {
return Float.compare(((net.minecraft.class_1309)a).method_6032(),((net.minecraft.class_1309)b).method_6032());
}}''',
}


def reject(call):
    try:
        call()
    except (ValueError, KeyError, zipfile.BadZipFile):
        return
    raise AssertionError("Expected artifact rejection")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--meteor-jar", type=Path)
    args = parser.parse_args()
    java = java_path(args.java_home)
    with tempfile.TemporaryDirectory(prefix="ermc-meteor-") as directory:
        out = Path(directory)
        files = []
        for name, content in {**STUBS, **SYNTHETIC}.items():
            p = out / "sources" / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")
            files.append(p)
        integration = SRC / "client/java/dev/ermc/bridge/client/integrations"
        files += list((integration / "meteor").glob("*.java"))
        files += [integration / "HostEntityView.java", integration / "liquidbounce/TargetConfigurableMixin.java",
                  SRC / "testFixtures/meteor/MeteorPreparationCheck.java"]
        classes = out / "classes"
        classes.mkdir()
        subprocess.run([java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "--release", "17",
                        "-encoding", "UTF-8", "-Xlint:all", "-Werror", "-d", str(classes), *map(str, files)], check=True)
        subprocess.run([java, "-cp", str(classes), "dev.ermc.bridge.meteor.MeteorPreparationCheck"], check=True)

        def fixture(version="0.5.8", minecraft="1.21.1", missing=None):
            archive = io.BytesIO()
            with zipfile.ZipFile(archive, "w") as jar:
                jar.writestr("fabric.mod.json", json.dumps({"id": "meteor-client", "version": version,
                                                          "depends": {"minecraft": ["1.21", minecraft]}}))
                for name in SYNTHETIC:
                    entry = str(Path(name).with_suffix(".class")).replace("\\", "/")
                    if entry != missing:
                        jar.writestr(entry, (classes / entry).read_bytes())
            return archive.getvalue()

        good = inspect_jar(fixture())
        assert good["version"] == "0.5.8" and len(good["sha256"]) == 64
        reject(lambda: inspect_jar(fixture(version="0.5.9")))
        reject(lambda: inspect_jar(fixture(minecraft="1.21.4")))
        reject(lambda: inspect_jar(fixture(missing="meteordevelopment/meteorclient/systems/modules/combat/KillAura.class")))
        reject(lambda: inspect_jar(b"not a jar"))
        reject(lambda: class_symbols(b"\xca\xfe\xba\xbe"))
        print("PASS: synthetic JAR metadata, production descriptors/refs, wrong version/MC/missing class rejection")
    if args.meteor_jar:
        metadata = inspect_jar(args.meteor_jar.read_bytes())
        print("Actual artifact preflight:", json.dumps(metadata))
    else:
        print("NOT RUN: actual Meteor JAR, Mixin injection, Gradle/PowerShell launch and game rendering.")


if __name__ == "__main__":
    main()
