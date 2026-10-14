"""Run production Combat/Life ownership and Terrain recall code with small MC/IPC doubles.

No games, DLL or LAN sockets are started. Actual player hurt/teleport operations are recorded
by doubles; damage calculation, routing, session baselines and recall functions are production.
--fabric-events additionally checks the pinned Fabric source artifacts and actual Minecraft
PlayerList bytecode for JOIN order; it neither launches games nor runs a Gradle build.
"""
from pathlib import Path
import argparse
import os
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from test_b01_preparation import java_path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge"


def function(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth, end = 1, opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


STUBS = {
    "net/fabricmc/fabric/api/entity/event/v1/ServerPlayerEvents.java": """package net.fabricmc.fabric.api.entity.event.v1;
import net.minecraft.server.level.ServerPlayer;
public final class ServerPlayerEvents {
public interface Join {void onJoin(ServerPlayer player);}
public static final JoinEvent JOIN=new JoinEvent();
public static final class JoinEvent {private Join callback;public void register(Join value){callback=value;}public void fire(ServerPlayer player){if(callback!=null)callback.onJoin(player);}}
}""",
    "net/fabricmc/fabric/api/networking/v1/ServerPlayConnectionEvents.java": """package net.fabricmc.fabric.api.networking.v1;
import net.minecraft.server.level.ServerPlayer;import net.minecraft.server.MinecraftServer;
public final class ServerPlayConnectionEvents {
public static final class Handler {public final ServerPlayer player;public Handler(ServerPlayer p){player=p;}}
public interface Join {void onPlayReady(Handler handler,Object sender,MinecraftServer server);}
public static final JoinEvent JOIN=new JoinEvent();
public static final class JoinEvent {private Join callback;public void register(Join value){callback=value;}public void fire(ServerPlayer player){if(callback!=null)callback.onPlayReady(new Handler(player),null,player.getServer());}}
}""",
    "org/slf4j/Logger.java": "package org.slf4j; public interface Logger {default void info(String s,Object... a) {}}",
    "org/slf4j/LoggerFactory.java": "package org.slf4j; public final class LoggerFactory {public static Logger getLogger(String s){return new Logger(){};}}",
    "net/minecraft/core/Holder.java": "package net.minecraft.core; public record Holder<T>(T value){}",
    "net/minecraft/core/registries/Registries.java": "package net.minecraft.core.registries; public final class Registries {public static final Object DAMAGE_TYPE=new Object();}",
    "net/minecraft/resources/ResourceLocation.java": "package net.minecraft.resources; public record ResourceLocation(String namespace,String path){public static ResourceLocation fromNamespaceAndPath(String n,String p){return new ResourceLocation(n,p);}}",
    "net/minecraft/resources/ResourceKey.java": "package net.minecraft.resources; public record ResourceKey<T>(ResourceLocation location){public static <T> ResourceKey<T> create(Object r,ResourceLocation l){return new ResourceKey<T>(l);}}",
    "net/minecraft/network/chat/Component.java": "package net.minecraft.network.chat; public record Component(String getString){}",
    "net/minecraft/world/phys/Vec3.java": "package net.minecraft.world.phys; public final class Vec3 {public static final Vec3 ZERO=new Vec3(0,0,0);public final double x,y,z;public Vec3(double x,double y,double z){this.x=x;this.y=y;this.z=z;}}",
    "net/minecraft/world/damagesource/DamageType.java": "package net.minecraft.world.damagesource; public record DamageType(String name){}",
    "net/minecraft/world/damagesource/DamageSource.java": """package net.minecraft.world.damagesource;
import net.minecraft.core.Holder;import net.minecraft.resources.ResourceKey;
public final class DamageSource {
private final Holder<DamageType> type; public DamageSource(Holder<DamageType> t){type=t;}
public DamageSource(Holder<DamageType> t,Object from){type=t;}
public boolean is(ResourceKey<DamageType> key){return type.value().name().equals(key.location().path());}
public String getMsgId(){return type.value().name();}
}""",
    "net/minecraft/world/entity/LivingEntity.java": "package net.minecraft.world.entity; public class LivingEntity{}",
    "net/minecraft/world/level/GameRules.java": """package net.minecraft.world.level;
public final class GameRules {public static final Object RULE_DO_IMMEDIATE_RESPAWN=new Object();public Rule getRule(Object key){return new Rule();}public static final class Rule {public void set(boolean b,Object server){}}}""",
    "net/minecraft/server/level/ServerLevel.java": """package net.minecraft.server.level;
import net.minecraft.core.Holder;import net.minecraft.resources.*;import net.minecraft.world.damagesource.DamageType;
public final class ServerLevel {
public static final class Registry {public Holder<DamageType> getHolderOrThrow(ResourceKey<DamageType> key){return new Holder<>(new DamageType(key.location().path()));}}
public static final class Access {public Registry registryOrThrow(Object key){return new Registry();}}
public Access registryAccess(){return new Access();}public ResourceKey<Object> dimension(){return ResourceKey.create(null,ResourceLocation.fromNamespaceAndPath("test","world"));}
}""",
    "net/minecraft/server/MinecraftServer.java": """package net.minecraft.server;
import java.util.*;import net.minecraft.server.level.*;import net.minecraft.world.level.GameRules;
public final class MinecraftServer {
public record Profile(UUID getId){}public Profile profile;public boolean dedicated;public final Players players=new Players();public final ServerLevel world=new ServerLevel();
public static final class Players {public final Map<UUID,ServerPlayer> all=new LinkedHashMap<>();public ServerPlayer getPlayer(UUID id){return all.get(id);}public java.util.List<ServerPlayer> getPlayers(){return new ArrayList<>(all.values());}}
public Profile getSingleplayerProfile(){return profile;}public boolean isDedicatedServer(){return dedicated;}
public Players getPlayerList(){return players;}public ServerLevel overworld(){return world;}public GameRules getGameRules(){return new GameRules();}
}""",
    "net/minecraft/server/level/ServerPlayer.java": """package net.minecraft.server.level;
import java.util.UUID;import net.minecraft.server.MinecraftServer;import net.minecraft.world.entity.LivingEntity;import net.minecraft.world.damagesource.DamageSource;import net.minecraft.world.phys.Vec3;import net.minecraft.network.chat.Component;
public final class ServerPlayer extends LivingEntity {
public final UUID id;public final MinecraftServer server;public ServerLevel world;public int hurtCalls,teleports,tickCount,knockbacks;public boolean alive=true,hurtMarked;public float health=20;public Vec3 pos=Vec3.ZERO;
public ServerPlayer(MinecraftServer s,UUID id){server=s;world=s.world;this.id=id;}
public MinecraftServer getServer(){return server;}public ServerLevel serverLevel(){return world;}public ServerLevel level(){return world;}public UUID getUUID(){return id;}
public boolean isAlive(){return alive;}public Component getName(){return new Component(id.toString());}public float getHealth(){return health;}public float getAbsorptionAmount(){return 0;}
public Vec3 position(){return pos;}public Vec3 getDeltaMovement(){return Vec3.ZERO;}public double getX(){return pos.x;}public double getY(){return pos.y;}public double getZ(){return pos.z;}public float getYRot(){return 0;}
public boolean isDamageSourceBlocked(DamageSource s){return false;}public boolean hurt(DamageSource s,float n){hurtCalls++;health-=n;if(health<=0)alive=false;return true;}
public void knockback(double a,double x,double z){knockbacks++;}public void teleportTo(ServerLevel l,double x,double y,double z,float yaw,float pitch){world=l;pos=new Vec3(x,y,z);teleports++;}
}""",
    "dev/ermc/bridge/MountRuntime.java": "package dev.ermc.bridge; public final class MountRuntime {public static void beforeRecall(net.minecraft.server.level.ServerPlayer player){}}",
    "dev/ermc/bridge/link/BridgePaths.java": "package dev.ermc.bridge.link; public final class BridgePaths {public static String file(String s){return s;}}",
    "dev/ermc/bridge/link/ErLink.java": """package dev.ermc.bridge.link;
public final class ErLink {private static final ErLink INSTANCE=new ErLink();public static ErLink get(){return INSTANCE;}
public boolean connected=true,readable=true;public long start=100;public int deaths,mcDeaths,life=1;public final float[] events=new float[9];
public boolean alive(){return connected;}public long hostStartMs(){return start;}public int hostDeaths(){return deaths;}public int hostLife(){return life;}public void bumpMcDeaths(){mcDeaths++;}
public boolean readHunterEvents(float[] out){if(!readable)return false;System.arraycopy(events,0,out,0,9);return true;}
public boolean snapshot(GameState out){return false;}
}""",
    "dev/ermc/bridge/entity/ErEntity.java": "package dev.ermc.bridge.entity; import net.minecraft.network.chat.Component; public final class ErEntity {public int kind(){return 1;}public Component getName(){return new Component(\"enemy\");}}",
    "dev/ermc/bridge/entity/EntityBridge.java": "package dev.ermc.bridge.entity; import net.minecraft.world.phys.Vec3; public final class EntityBridge {public static ErEntity nearestEnemy(Vec3 from,double r){return null;}}",
}

TERRAIN_PREAMBLE = """package dev.ermc.bridge;
import java.util.*;import java.util.concurrent.atomic.AtomicInteger;import dev.ermc.bridge.link.*;
import net.minecraft.server.MinecraftServer;import net.minecraft.server.level.*;import net.minecraft.world.phys.Vec3;import org.slf4j.*;
public final class TerrainManager {
private static final Logger LOG=LoggerFactory.getLogger("fixture");public static boolean bridgeWorld=true;
public static final GameState STATE=new GameState();private static final Map<Integer,CoordMap.Mapping> ANCHORS=new HashMap<>();private static CoordMap.Mapping legacyAnchor;
private static final AtomicInteger RECALL_GEN=new AtomicInteger();private static int recallDone;private static long recallDoneAtMs;public static int floors;
public static boolean isBridgeWorld(){return bridgeWorld;}private static void placeFloor(ServerLevel level,Vec3 p){floors++;}private static void saveAnchors(MinecraftServer s){}
public static void recall(MinecraftServer s){recallOwner(s);}public static void anchor(MinecraftServer s){updateAnchor(s);}public static void direct(ServerPlayer p){teleportToHunter(p.serverLevel(),p,CoordMap.get());}
public static void clear(){RECALL_GEN.set(0);recallDone=0;floors=0;ANCHORS.clear();CoordMap.set(null);STATE.flags=Protocol.STATE_PLAYER_VALID;STATE.stageId=1;STATE.unitsPerMeter=100;STATE.playerQuat[3]=1;}
"""

CHECK = """package dev.ermc.bridge;
import java.util.UUID;import dev.ermc.bridge.entity.CombatBridge;import dev.ermc.bridge.link.*;
import net.minecraft.core.Holder;import net.minecraft.world.damagesource.*;import net.minecraft.server.*;import net.minecraft.server.level.*;import net.minecraft.world.phys.Vec3;
public final class LocalHostRoutingCheck {
private static int checks;
private static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
private static DamageSource source(String id){return new DamageSource(new Holder<>(new DamageType(id)));}
private static void hit(){ErLink l=ErLink.get();l.events[0]++;l.events[1]+=10;l.events[6]=100;l.events[7]=Protocol.ENT_SMALL_MONSTER;}
private static void both(MinecraftServer s){CombatBridge.onServerTick(s);LifeBridge.onServerTick(s);}
public static void main(String[] args){
JoinEventWiring.register();
MinecraftServer joining=new MinecraftServer();ServerPlayer newcomer=new ServerPlayer(joining,UUID.randomUUID());
joining.profile=new MinecraftServer.Profile(newcomer.id);newcomer.pos=new Vec3(0,-30,0);TerrainManager.clear();TerrainManager.anchor(joining);
check(LocalHostOwner.find(joining)==null && !LocalHostOwner.isOwner(newcomer),"network join before UUID registration has no owner");
net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.fire(newcomer);
check(newcomer.teleports==0,"network join must not bypass unregistered owner identity");
joining.players.all.put(newcomer.id,newcomer);
net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.JOIN.fire(newcomer);
check(LocalHostOwner.isOwner(newcomer) && newcomer.teleports==1,"production post-registration join callback must recall the owner");
TerrainManager.clear();
System.out.println("PASS: production JOIN wiring waits for UUID registration and then recalls the owner");
MinecraftServer s=new MinecraftServer();UUID id=UUID.randomUUID();ServerPlayer a=new ServerPlayer(s,id),b=new ServerPlayer(s,UUID.randomUUID());
s.profile=new MinecraftServer.Profile(id);s.players.all.put(b.id,b);s.players.all.put(a.id,a);ErLink l=ErLink.get();
check(LocalHostOwner.find(s)==a,"guest listed first must never become owner");check(!LocalHostOwner.isOwner(b),"guest is not owner");
CombatBridge.reset();LifeBridge.reset();both(s);hit();both(s);check(a.hurtCalls==1 && b.hurtCalls==0,"host hit is routed to owner only");
both(s);check(a.hurtCalls==1,"same host hit is not replayed");l.life++;l.deaths++;LifeBridge.onServerTick(s);
check(a.hurtCalls==2 && !a.alive && b.alive,"legitimate hostLife+death kills only owner");
LifeBridge.onDeath(a,source("host_death"));check(l.mcDeaths==0,"host death does not echo to ER");
LifeBridge.onDeath(b,source("fall"));check(l.mcDeaths==0,"guest death does not kill ER");
LifeBridge.onDeath(a,source("fall"));check(l.mcDeaths==1,"owner death still kills ER");a.alive=true;a.health=20;
l.start++;l.deaths=0;l.events[0]=0;l.events[1]=0;both(s);check(a.hurtCalls==2,"ER process counter reset emits no ghost event");
hit();both(s);check(a.hurtCalls==3,"new process accepts fresh hits after baseline");
l.events[0]=0;l.events[1]=0;l.deaths=-1;both(s);check(a.hurtCalls==3,"counter rollback without identity change is baseline only");
l.connected=false;both(s);hit();l.deaths++;both(s);LifeBridge.onDeath(a,source("fall"));check(l.mcDeaths==1,"disconnected death cannot write stale IPC");
l.connected=true;both(s);check(a.hurtCalls==3,"reconnect consumes missed hit/death as baseline");
hit();both(s);check(a.hurtCalls==4,"reconnected owner still receives new events");
s.players.all.remove(id);both(s);hit();l.deaths++;both(s);s.players.all.put(id,a);both(s);check(a.hurtCalls==4 && b.hurtCalls==0,"owner absence never affects guest or replays after join");
ServerPlayer replacement=new ServerPlayer(s,id);s.players.all.put(id,replacement);hit();l.deaths++;both(s);
check(replacement.hurtCalls==0 && !LocalHostOwner.isOwner(a),"replacement player instance receives a new baseline");
LifeBridge.onDeath(a,source("fall"));check(l.mcDeaths==1,"stale owner object cannot kill ER");hit();both(s);check(replacement.hurtCalls==1,"replacement owner gets next event");
s.dedicated=true;both(s);hit();l.deaths++;both(s);LifeBridge.onDeath(replacement,source("fall"));
check(replacement.hurtCalls==1 && b.hurtCalls==0 && l.mcDeaths==1,"dedicated server never consumes local IPC life");
s.dedicated=false;s.profile=null;both(s);check(LocalHostOwner.find(s)==null,"missing integrated profile has no fallback");
s.profile=new MinecraftServer.Profile(id);both(s);l.readable=false;hit();CombatBridge.onServerTick(s);l.readable=true;CombatBridge.onServerTick(s);
check(replacement.hurtCalls==1,"unreadable event interval is not replayed");
replacement.world=new ServerLevel();hit();l.deaths++;both(s);replacement.world=s.world;both(s);
check(replacement.hurtCalls==1,"owner in a different dimension is not damaged and missed event is not replayed");
l.start=0;both(s);hit();l.deaths++;both(s);LifeBridge.onDeath(replacement,source("fall"));
check(replacement.hurtCalls==1 && l.mcDeaths==1,"unidentified host cannot receive or send life events");
l.start=999;both(s);check(replacement.hurtCalls==1,"first identified host snapshot is only a baseline");
TerrainManager.bridgeWorld=false;both(s);hit();l.deaths++;both(s);TerrainManager.bridgeWorld=true;both(s);
check(replacement.hurtCalls==1,"ordinary world event interval is not replayed on bridge entry");
System.out.println("PASS: production CombatBridge/LifeBridge owner routing, restart/absence/reconnect and legitimate host-life death");

TerrainManager.clear();replacement.pos=new Vec3(40000,0,0);TerrainManager.anchor(s);check(replacement.teleports==1 && b.teleports==0,"new host region moves only owner");
TerrainManager.requestRecall();TerrainManager.recall(s);check(replacement.teleports==2 && b.teleports==0,"explicit recall only moves owner");
s.players.all.remove(id);TerrainManager.requestRecall();TerrainManager.recall(s);check(b.teleports==0,"pending recall cannot consume guest as owner");
s.players.all.put(id,replacement);TerrainManager.recall(s);check(replacement.teleports==3,"pending recall survives owner absence");
TerrainManager.STATE.stageId=2;TerrainManager.anchor(s);check(replacement.teleports==4 && b.teleports==0,"ER zone transition does not move guest");
TerrainManager.direct(b);TerrainManager.direct(a);check(b.teleports==0 && a.teleports==0,"final teleport guard rejects guest and old owner");
b.pos=new Vec3(0,-30,0);TerrainManager.onJoin(s,b);check(b.teleports==0,"guest join below zero cannot attach to ER");
replacement.pos=new Vec3(0,-30,0);TerrainManager.onJoin(s,replacement);check(replacement.teleports==5,"owner join retains recovery teleport");
int floors=TerrainManager.floors;LifeBridge.onRespawn(b,b,false);TerrainManager.recall(s);
check(TerrainManager.floors==floors && replacement.teleports==5,"guest respawn does not request host recall");
LifeBridge.onRespawn(a,replacement,false);TerrainManager.recall(s);check(replacement.teleports==6,"owner respawn still requests recall");
LifeBridge.onRespawn(a,replacement,true);TerrainManager.recall(s);check(replacement.teleports==6,"alive dimension return does not add recall");
System.out.println("PASS: production Terrain join/respawn/anchor/recall/teleport functions isolate LAN guests");
System.out.println("PASS: "+checks+" assertions; platform doubles only, no real LAN/game validation");
}
}
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--regression-ref", help="Also prove the same checks reject this Git revision's Combat/Life classes")
    parser.add_argument("--fabric-events", action="store_true", help="verify JOIN order against cached pinned Fabric/MC artifacts")
    args = parser.parse_args()
    java = java_path(args.java_home)
    if args.fabric_events:
        verify_fabric_join_order(java)
    entrypoint = (SOURCE / "ErBridgeMod.java").read_text(encoding="utf-8")
    join_lines = [line.strip() for line in entrypoint.splitlines()
                  if ".JOIN.register(" in line and "TerrainManager.onJoin(" in line]
    if len(join_lines) != 1:
        raise RuntimeError("Expected one production terrain JOIN registration")
    join_wiring = """package dev.ermc.bridge;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
public final class JoinEventWiring {public static void register(){
""" + join_lines[0] + "\n}}\n"
    terrain = (SOURCE / "TerrainManager.java").read_text(encoding="utf-8")
    functions = [function(terrain, name) for name in (
        "public static void requestRecall(", "public static void onRespawn(", "public static void onJoin(",
        "private static void recallOwner(", "private static void updateAnchor(", "private static void teleportToHunter(")]
    if "recallOwner(server);" not in function(terrain, "public static void onServerTick("):
        raise RuntimeError("Terrain production tick no longer invokes the tested recall function")
    with tempfile.TemporaryDirectory(prefix="ermc-owner-") as directory:
        output = Path(directory)
        sources = []
        for name, content in {**STUBS, "dev/ermc/bridge/TerrainManager.java": TERRAIN_PREAMBLE + "\n".join(functions) + "\n}",
                              "dev/ermc/bridge/LocalHostRoutingCheck.java": CHECK,
                              "dev/ermc/bridge/JoinEventWiring.java": join_wiring}.items():
            path = output / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
            sources.append(path)
        sources += [SOURCE / name for name in ("LocalHostOwner.java", "LifeBridge.java", "entity/CombatBridge.java",
                                               "CoordMap.java", "link/Protocol.java", "link/GameState.java")]
        classes = output / "classes"
        subprocess.run([java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "--release", "17", "-encoding", "UTF-8",
                        "-Xlint:all", "-Werror", "-d", str(classes), *map(str, sources)], cwd=ROOT, check=True)
        subprocess.run([java, "-cp", str(classes), "dev.ermc.bridge.LocalHostRoutingCheck"], cwd=ROOT, check=True)
        legacy = output / "legacy-join/JoinEventWiring.java"
        legacy.parent.mkdir()
        legacy.write_text(join_wiring.replace(join_lines[0],
            "ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> TerrainManager.onJoin(server, handler.player));"), encoding="utf-8")
        legacy_classes = output / "legacy-join-classes"
        subprocess.run([java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "--release", "17", "-encoding", "UTF-8",
                        "-classpath", str(classes), "-d", str(legacy_classes), str(legacy)], cwd=ROOT, check=True)
        rejected = subprocess.run([java, "-cp", os.pathsep.join(map(str, [legacy_classes, classes])),
                                   "dev.ermc.bridge.LocalHostRoutingCheck"], cwd=ROOT, capture_output=True, text=True)
        if rejected.returncode == 0 or "production post-registration join callback must recall the owner" not in rejected.stderr:
            raise RuntimeError("The premature networking JOIN wiring did not reproduce the expected missed recall: " + rejected.stdout + rejected.stderr)
        print("PASS: the old networking JOIN registration fails the same post-registration recall assertion", flush=True)
        if args.regression_ref:
            baseline_sources = list(sources)
            for name in ("LifeBridge.java", "entity/CombatBridge.java"):
                path = SOURCE / name
                content = subprocess.check_output(["git", "show", f"{args.regression_ref}:{path.relative_to(ROOT).as_posix()}"],
                                                  cwd=ROOT, text=True, encoding="utf-8")
                # The old Life class only reset on startup; adapt the fixture's test setup name,
                # without altering any old damage/death method under test.
                if name == "LifeBridge.java" and "public static void reset()" not in content:
                    content = content.replace("public final class LifeBridge {", "public final class LifeBridge { public static void reset(){lastHostDeaths=Integer.MIN_VALUE;}")
                copy = output / "baseline" / name
                copy.parent.mkdir(parents=True, exist_ok=True)
                copy.write_text(content, encoding="utf-8")
                baseline_sources[baseline_sources.index(path)] = copy
            old_classes = output / "old-classes"
            subprocess.run([java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "--release", "17", "-encoding", "UTF-8",
                            "-d", str(old_classes), *map(str, baseline_sources)], cwd=ROOT, check=True)
            result = subprocess.run([java, "-cp", str(old_classes), "dev.ermc.bridge.LocalHostRoutingCheck"],
                                    cwd=ROOT, capture_output=True, text=True)
            if result.returncode == 0 or "host hit is routed to owner only" not in result.stderr:
                raise RuntimeError("Old production functions did not reproduce the expected LAN broadcast failure: " + result.stdout + result.stderr)
            print(f"PASS: unchanged {args.regression_ref} Combat/Life methods fail the guest-damage assertion as expected", flush=True)


def verify_fabric_join_order(java):
    from test_lb_capability_adapter import single

    project = ROOT / "bridge-base/elden-ring/mc-bridge"
    properties = (project / "gradle.properties").read_text(encoding="utf-8")
    version = re.search(r"(?m)^fabric_api_version=(.+)$", properties).group(1).strip()
    modules = ROOT / ".tools/gradle-home/caches/modules-2/files-2.1/net.fabricmc.fabric-api"
    pom = ET.parse(single((modules / "fabric-api" / version).glob("*/*.pom"), "fixed Fabric API POM"))
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    dependencies = {entry.findtext("m:artifactId", namespaces=ns): entry.findtext("m:version", namespaces=ns)
                    for entry in pom.findall("m:dependencies/m:dependency", ns)}

    def source(artifact, member):
        jar = single((modules / artifact / dependencies[artifact]).glob("*/*-sources.jar"), artifact + " sources")
        with zipfile.ZipFile(jar) as archive:
            return archive.read(member).decode("utf-8")

    entity = "fabric-entity-events-v1"
    api = source(entity, "net/fabricmc/fabric/api/entity/event/v1/ServerPlayerEvents.java")
    assert "Event<Join> JOIN" in api and "void onJoin(class_3222 player);" in api
    callback = source(entity, "net/fabricmc/fabric/mixin/entity/event/PlayerManagerMixin.java")
    assert '@Inject(method = "onPlayerConnect", at = @At("RETURN"))' in callback
    assert "ServerPlayerEvents.JOIN.invoker().onJoin(player);" in callback
    network = "fabric-networking-api-v1"
    callback = source(network, "net/fabricmc/fabric/mixin/networking/PlayerManagerMixin.java")
    assert 'method = "onPlayerConnect"' in callback and 'value = "INVOKE"' in callback
    assert 'PlayerAbilitiesS2CPacket;<init>' in callback and '.onClientReady();' in callback
    addon = source(network, "net/fabricmc/fabric/impl/networking/server/ServerPlayNetworkAddon.java")
    assert "ServerPlayConnectionEvents.JOIN.invoker().onPlayReady(this.handler, this, this.server);" in function(addon, "public void onClientReady()")

    mc = single((ROOT / ".tools/gradle-home/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-common").glob(
        "1.21.1-loom.*/*.jar"), "named Minecraft 1.21.1")
    javap = Path(java).parent / ("javap.exe" if os.name == "nt" else "javap")
    bytecode = subprocess.check_output([str(javap), "-c", "-p", "-classpath", str(mc), "net.minecraft.server.players.PlayerList"], text=True, encoding="utf-8")
    start = bytecode.index("public void placeNewPlayer(")
    end = bytecode.index("\n  public ", start + 1)
    body = bytecode[start:end]
    abilities = re.search(r'(\d+): invokespecial[^\n]*ClientboundPlayerAbilitiesPacket\."<init>"', body)
    register = re.search(r'getfield[^\n]*playersByUUID:[\s\S]*?(\d+): invokeinterface[^\n]*Map\.put:', body)
    assert abilities and register and int(abilities.group(1)) < int(register.group(1))
    print(f"PASS: pinned Fabric API {version} / entity events {dependencies[entity]} exposes ServerPlayerEvents.JOIN(ServerPlayer) at method RETURN", flush=True)
    print(f"PASS: actual MC 1.21.1 networking JOIN at abilities bytecode {abilities.group(1)} precedes UUID map registration {register.group(1)}", flush=True)


if __name__ == "__main__":
    main()
