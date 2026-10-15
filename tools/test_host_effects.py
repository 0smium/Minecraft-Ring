"""Exercise actual native HEFX and Java mapping/leases with small platform stubs.

Does not launch Minecraft/Elden Ring or validate the Windows DLL/game at runtime.
Requires C++17 and a full JDK 17+.
Pass --vanilla to also verify the merge model against locally cached Minecraft 1.21.1
classes (requires the project JDK 21+ and an already populated production build cache).
"""
from pathlib import Path
import argparse
import os
import shutil
import subprocess
import tempfile
from test_b01_preparation import java_path

ROOT = Path(__file__).resolve().parents[1]
ER = ROOT / "bridge-base/elden-ring/er-bridge"
MC = ER.parent / "mc-bridge/src"
CHARACTER = ER.parent / "mc-bridge/character-bridge/src"

NATIVE = r"""
#include <cstdio>
#include <cstring>
#include <map>
#include <vector>
#include <cassert>
#include "host_effects.h"
static std::map<uintptr_t, std::vector<uint8_t>> memory;
static ErmcHostEffects shared;
namespace mb {
bool mem_read(const void* p, void* out, size_t n) {
    auto it = memory.find((uintptr_t)p);
    if (it == memory.end() || it->second.size() < n) return false;
    memcpy(out, it->second.data(), n); return true;
}
ErmcHostEffects* shm_host_effects() { return &shared; }
}
template<class T> void put(uintptr_t address, const T& value) {
    const auto* bytes = reinterpret_cast<const uint8_t*>(&value);
    memory[address] = std::vector<uint8_t>(bytes, bytes + sizeof(value));
}
void signature(uintptr_t at, std::initializer_list<uint8_t> bytes) { memory[at] = bytes; }
int main(int argc, char** argv) {
    assert(argc == 2);
    using namespace mb;
    constexpr uintptr_t base=0x10000000, player=0x20000000, list=0x30000000;
    constexpr uintptr_t first=0x40000000, paramBase=0x50000000;
    HostEffectsReader reader;
    assert(!reader.init(base) && !reader.read((void*)player).valid);
    signature(base+0x41176C,{0x48,0x8B,0x89,0x78,0x01,0x00,0x00});
    signature(base+0x4FA432,{0x49,0x8B,0x43,0x08});
    signature(base+0x4FA440,{0x48,0x8B,0x08});
    signature(base+0x4FA448,{0x0F,0xB7,0x91,0x56,0x01,0x00,0x00});
    signature(base+0x4FA459,{0x48,0x8B,0x40,0x30});
    signature(base+0x4FAD06,{0x39,0x48,0x08,0x74,0x11,0x48,0x8B,0x40,0x30,0x48,0x85,0xC0,0x75,0xF2});
    assert(reader.init(base));
    put(player,base+0x2A7FBB0); put(player+0x178,list);
    struct List {uintptr_t vtable,head,owner;} head{base+0x2A4D490,first,player};
    put(list,head);
    struct Entry {uintptr_t param; int32_t id; uint8_t unused[36]; uintptr_t next;};
    const uint16_t types[]={2,5,260,436,437,2,6};
    const int32_t ids[]={4004,4100,5000,6000,7000,4001,9999};
    Entry entries[7]{};
    for(size_t i=0;i<7;i++) {
        entries[i].param=paramBase+i*0x1000; entries[i].id=ids[i];
        entries[i].next=i==6 ? 0 : first+(i+1)*0x1000;
        put(first+i*0x1000,entries[i]); put(entries[i].param+0x156,types[i]);
    }
    auto all=reader.read((void*)player);
    assert(all.valid && all.poisonEffectId==4001 && all.rotEffectId==4100);
    assert(all.frostEffectId==5000 && all.sleepEffectId==6000 && all.madnessEffectId==7000);
    entries[6].next=first; put(first+6*0x1000,entries[6]);
    auto broken=reader.read((void*)player);
    assert(!broken.valid && broken.poisonEffectId==-1 && broken.frostEffectId==-1);
    entries[6].next=0; put(first+6*0x1000,entries[6]);
    memory.erase(entries[4].param+0x156);
    assert(!reader.read((void*)player).valid);
    put(entries[4].param+0x156,types[4]);
    head.owner=player+8; put(list,head); assert(!reader.read((void*)player).valid);
    head.owner=player; put(list,head);
    assert(!reader.read(nullptr).valid);
    puts("PASS: native signature/chain/IDs, cycle/unreadable/owner rejection");
    HostEffectsSnapshot clean; clean.valid=true;
    HostEffectEpisodes episodes;
    assert(episodes.observe(all,7)==0);
    assert(episodes.observe(clean,7)==0);
    assert(episodes.observe(all,7)==1);
    assert(episodes.observe(all,7)==1);
    assert(episodes.observe({},7)==1);
    assert(episodes.observe(all,7)==1);
    assert(episodes.observe(clean,7)==1);
    assert(episodes.observe(all,8)==1);
    assert(episodes.observe(clean,8)==1);
    assert(episodes.observe(all,8)==2);
    publish_host_effects(clean,true,99,7);
    publish_host_effects(all,true,100,7);
    assert(shared.version==2 && shared.seq==4 && shared.frostProcs==1 && shared.flags==127);
    FILE* f=fopen(argv[1],"wb"); assert(f);
    assert(fwrite(&shared,1,sizeof(shared),f)==sizeof(shared)); fclose(f);
    publish_host_effects({},false,101,7);
    assert(shared.flags==0 && shared.rotEffectId==-1 && shared.frostEffectId==-1);
    puts("PASS: native frost episodes, invalid/life baseline and v2 wire fixture");
}
"""

STUBS = {
    "dev/ermc/bridge/character/action/ActionLockServer.java": '''package dev.ermc.bridge.character.action;
import net.minecraft.server.level.ServerPlayer;
public final class ActionLockServer {
public static ServerPlayer holder;public static boolean sleeping;public static int observations,clears;
public static void observe(ServerPlayer player,boolean asleep){holder=player;sleeping=asleep;observations++;}
public static void clear(){holder=null;sleeping=false;clears++;}
public static boolean blocks(ServerPlayer player){return holder==player&&sleeping;}
}''',
    "org/spongepowered/asm/mixin/Mixin.java": 'package org.spongepowered.asm.mixin; public @interface Mixin { Class<?>[] value(); }',
    "org/spongepowered/asm/mixin/gen/Accessor.java": 'package org.spongepowered.asm.mixin.gen; public @interface Accessor { String value(); }',
    "org/slf4j/Logger.java": 'package org.slf4j; public interface Logger { default void info(String s,Object... args) {} }',
    "org/slf4j/LoggerFactory.java": 'package org.slf4j; public final class LoggerFactory { public static Logger getLogger(String s) { return new Logger() {}; } }',
    "net/minecraft/core/Holder.java": 'package net.minecraft.core; public record Holder<T>(T value) {}',
    "net/minecraft/network/chat/Component.java": 'package net.minecraft.network.chat; public record Component(String getString) {}',
    "net/minecraft/world/effect/MobEffect.java": '''package net.minecraft.world.effect;
public class MobEffect { public void applyInstantenousEffect(Object a,Object b,net.minecraft.server.level.ServerPlayer p,int amp,double factor) { p.bursts++; } }''',
    "net/minecraft/world/effect/MobEffects.java": '''package net.minecraft.world.effect;
import net.minecraft.core.Holder;
public final class MobEffects {
public static final Holder<MobEffect> POISON=new Holder<>(new MobEffect()), WITHER=new Holder<>(new MobEffect()),
MOVEMENT_SLOWDOWN=new Holder<>(new MobEffect()), BLINDNESS=new Holder<>(new MobEffect()),
CONFUSION=new Holder<>(new MobEffect()), WEAKNESS=new Holder<>(new MobEffect()), HARM=new Holder<>(new MobEffect());
}''',
    "net/minecraft/world/effect/MobEffectInstance.java": '''package net.minecraft.world.effect;
import net.minecraft.core.Holder;
public final class MobEffectInstance implements dev.ermc.bridge.mixin.MobEffectInstanceAccessor {
public final Holder<MobEffect> type; public int duration, amplifier; private MobEffectInstance hiddenEffect;
public MobEffectInstance(Holder<MobEffect> type,int duration,int amplifier) {this.type=type;this.duration=duration;this.amplifier=amplifier;}
public MobEffectInstance(MobEffectInstance other){this(other.type,other.duration,other.amplifier);}
public int getDuration(){return duration;} public int getAmplifier(){return amplifier;}
public boolean isInfiniteDuration(){return duration==-1;}
public MobEffectInstance erbridge$getHiddenEffect(){return hiddenEffect;}
private boolean shorterThan(MobEffectInstance other){return !isInfiniteDuration() && (duration<other.duration || other.isInfiniteDuration());}
public boolean update(MobEffectInstance next){
if(next.amplifier>amplifier){
if(next.shorterThan(this)){var oldHidden=hiddenEffect;hiddenEffect=new MobEffectInstance(this);hiddenEffect.hiddenEffect=oldHidden;}
amplifier=next.amplifier;duration=next.duration;return true;
}
if(shorterThan(next)){
if(next.amplifier==amplifier){duration=next.duration;return true;}
if(hiddenEffect==null) hiddenEffect=new MobEffectInstance(next); else hiddenEffect.update(next);
}
return false;
}
private void tickDown(){if(hiddenEffect!=null)hiddenEffect.tickDown();if(duration>0)duration--;}
public void tick(){
tickDown();
if(duration==0 && hiddenEffect!=null){duration=hiddenEffect.duration;amplifier=hiddenEffect.amplifier;hiddenEffect=hiddenEffect.hiddenEffect;}
}
}''',
    "net/minecraft/server/level/ServerPlayer.java": '''package net.minecraft.server.level;
import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.*;
import net.minecraft.network.chat.Component;
public final class ServerPlayer {
public final UUID id=UUID.randomUUID(); public final Object world;
public int tickCount,bursts,poisonTicks,witherTicks; public boolean alive=true;
private final Map<Holder<MobEffect>,MobEffectInstance> effects=new HashMap<>();
public ServerPlayer(Object world){this.world=world;}
public boolean isAlive(){return alive;} public Object level(){return world;}
public Component getName(){return new Component("fixture");}
public MobEffectInstance getEffect(Holder<MobEffect> type){return effects.get(type);}
public boolean hasEffect(Holder<MobEffect> type){return effects.containsKey(type);}
public boolean removeEffect(Holder<MobEffect> type){return effects.remove(type)!=null;}
public boolean addEffect(MobEffectInstance next){
var old=effects.get(next.type);
if(old==null){effects.put(next.type,next);return true;}
return old.update(next);
}
public void tick(){
tickCount++;
for(var e:effects.values()) {
if(e.type==MobEffects.POISON && e.duration%25==0) poisonTicks++;
if(e.type==MobEffects.WITHER && e.duration%40==0) witherTicks++;
e.tick();
}
effects.values().removeIf(e->!e.isInfiniteDuration() && e.duration<=0);
}
}''',
    "net/minecraft/server/MinecraftServer.java": '''package net.minecraft.server;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;
public final class MinecraftServer {
public record Profile(UUID getId) {}
public static final class Players {public final Map<UUID,ServerPlayer> all=new HashMap<>(); public ServerPlayer getPlayer(UUID id){return all.get(id);}}
public final Object world=new Object(); public final Players players=new Players(); public Profile profile;
public Profile getSingleplayerProfile(){return profile;} public Players getPlayerList(){return players;} public Object overworld(){return world;}
}''',
    "dev/ermc/bridge/TerrainManager.java": 'package dev.ermc.bridge; public final class TerrainManager {public static boolean bridge=true; public static boolean isBridgeWorld(){return bridge;} }',
    "dev/ermc/bridge/link/ErLink.java": '''package dev.ermc.bridge.link;
public final class ErLink {
private static final ErLink INSTANCE=new ErLink(); public final HostEffectState state=new HostEffectState();
public boolean connected=true,readable=true; public long render=1; public int life=7;
public static ErLink get(){return INSTANCE;} public boolean alive(){return connected;} public long hostFrame(){return render;} public int hostLife(){return life;}
public boolean readHostEffects(HostEffectState out){
if(!readable)return false;
out.version=state.version;out.flags=state.flags;out.frame=state.frame;out.hostLife=state.hostLife;out.frostProcs=state.frostProcs;
return true;
}
}'''
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--vanilla", action="store_true", help="also exercise actual cached Minecraft effect classes")
    args = parser.parse_args()
    java = java_path(args.java_home)
    cxx = os.environ.get("CXX") or shutil.which("g++") or shutil.which("clang++")
    if not cxx:
        bundled = sorted((ROOT / ".tools/compiler").glob("*/bin/clang++.exe"))
        cxx = str(bundled[0]) if bundled else None
    if not cxx:
        raise SystemExit("A C++17 compiler is required (CXX, g++ or clang++).")
    with tempfile.TemporaryDirectory(prefix="ermc-effects-") as directory:
        out = Path(directory)
        (out / "windows.h").write_text("#pragma once\nusing HWND=void*; using LONG=long; inline LONG InterlockedIncrement(volatile LONG* p){return ++*p;} inline LONG InterlockedDecrement(volatile LONG* p){return --*p;}\n")
        (out / "check.cpp").write_text(NATIVE)
        binary = out / ("check.exe" if os.name == "nt" else "check")
        subprocess.run([cxx, "-std=c++17", "-Wall", "-Wextra", "-Werror", f"-I{out}",
                        f"-I{ER/'include'}", f"-I{ER/'src'}", str(out / "check.cpp"), "-o", str(binary)], check=True)
        wire = out / "native-hefx.dat"
        subprocess.run([str(binary), str(wire)], check=True)
        files = []
        for relative, content in STUBS.items():
            p = out / "stubs" / relative
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")
            files.append(p)
        main = MC / "main/java/dev/ermc/bridge"
        files += [main / "link" / f"{name}.java" for name in
                  ("BridgePaths", "Protocol", "HostEffectState", "HostEffectCodec", "HostEffectCursor")]
        character = CHARACTER / "main/java/dev/ermc/bridge"
        files += [character / "link/HostEffectRules.java", character / "HostEffectsBridge.java", character / "mixin/MobEffectInstanceAccessor.java",
                  MC / "testFixtures/host-effects/HostEffectsCheck.java"]
        classes = out / "classes"
        classes.mkdir()
        subprocess.run([java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "--release", "17",
                        "-encoding", "UTF-8", "-Xlint:all", "-Werror", "-d", str(classes), *map(str, files)], check=True)
        run = ["-cp", str(classes), "dev.ermc.bridge.effects.HostEffectsCheck", str(wire)]
        subprocess.run([java, *run], check=True)
        subprocess.run([java, "-Derbridge.experimentalFrostBurst=true", *run], check=True)
        if args.vanilla:
            vanilla_merge_check(java, out)


def vanilla_merge_check(java, out):
    from test_lb_capability_adapter import argument_file, single

    cache = ROOT / ".tools/gradle-home/caches"
    dependencies = [single((cache / "fabric-loom/minecraftMaven/net/minecraft").glob(
        f"minecraft-{part}/1.21.1-loom.*/minecraft-{part}-*.jar"), f"named Minecraft {part}")
        for part in ("common", "clientonly")]
    dependencies += sorted(path for path in (cache / "modules-2/files-2.1").rglob("*.jar")
                           if not any(marker in path.name for marker in ("-sources", "-javadoc", "-natives-")))
    classes = out / "vanilla-classes"
    classes.mkdir()
    classpath = os.pathsep.join(map(str, [classes, *dependencies]))
    arguments = out / "vanilla.args"
    argument_file(arguments, ["-m", "jdk.compiler/com.sun.tools.javac.Main", "-proc:none", "-encoding", "UTF-8",
                             "-classpath", classpath, "-d", classes,
                             MC / "testFixtures/host-effects/VanillaEffectMergeCheck.java"])
    subprocess.run([java, "@" + str(arguments)], check=True)
    argument_file(arguments, ["-classpath", classpath, "dev.ermc.bridge.effects.VanillaEffectMergeCheck"])
    subprocess.run([java, "@" + str(arguments)], cwd=out, check=True)


if __name__ == "__main__":
    main()
