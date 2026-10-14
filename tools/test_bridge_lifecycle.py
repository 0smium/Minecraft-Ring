"""Exercise production movement/lifecycle APIs and extracted registration code without games.

Uses the pinned, named Fabric Event/EventFactory implementation, with small entity, executor,
network and game-system doubles. Runs Core-first and Character-first in separate JVMs;
it does not launch Fabric/Minecraft or claim live riding/disconnection was tested.
"""
from pathlib import Path
import os
import subprocess
import tempfile
import zipfile
from test_b01_preparation import java_path

ROOT = Path(__file__).resolve().parents[1]
PROJECT = ROOT / "bridge-base/elden-ring/mc-bridge"
CORE = PROJECT / "src/main/java/dev/ermc/bridge"
CLIENT = PROJECT / "src/client/java/dev/ermc/bridge/client"
CHARACTER = PROJECT / "character-bridge/src/main/java/dev/ermc/bridge"
OUT = ROOT / "runtime/experiments/bridge-lifecycle-check"


def statements(text, prefix):
    """Copy complete production registration statements, including nested lambdas."""
    result = []
    offset = 0
    while (start := text.find(prefix, offset)) >= 0:
        end = text.index("(", start)
        depth = 1
        end += 1
        while depth:
            depth += (text[end] == "(") - (text[end] == ")")
            end += 1
        assert text[end] == ";", text[start:end + 10]
        result.append(text[start:end + 1])
        offset = end + 1
    return result


def method(text, signature):
    start = text.index(signature)
    end = text.index("{", start) + 1
    depth = 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


STUBS = {
"net/minecraft/resources/ResourceLocation.java": """package net.minecraft.resources;
public record ResourceLocation(String namespace,String path) implements Comparable<ResourceLocation> {
public static ResourceLocation fromNamespaceAndPath(String n,String p){return new ResourceLocation(n,p);}
public int compareTo(ResourceLocation r){return toString().compareTo(r.toString());}}""",
"net/minecraft/world/entity/Entity.java": """package net.minecraft.world.entity;
public class Entity {public Object world; public boolean alive=true,removed;
public Entity(Object world){this.world=world;}public Object level(){return world;}
public boolean isAlive(){return alive;}public boolean isRemoved(){return removed;}}""",
"net/minecraft/world/entity/player/Player.java": """package net.minecraft.world.entity.player;
public class Player extends net.minecraft.world.entity.Entity {public Player(Object world){super(world);}}""",
"net/minecraft/server/level/ServerPlayer.java": """package net.minecraft.server.level;
public class ServerPlayer extends net.minecraft.world.entity.player.Player {
public final net.minecraft.server.MinecraftServer server;
public ServerPlayer(net.minecraft.server.MinecraftServer s){super(s);server=s;}}""",
"net/minecraft/server/MinecraftServer.java": """package net.minecraft.server;
public class MinecraftServer extends check.Executor {}""",
"net/minecraft/client/Minecraft.java": """package net.minecraft.client;
public class Minecraft extends check.Executor {public Object connection;public Object getConnection(){return connection;}}""",
"check/Executor.java": """package check;
public class Executor {public final Thread owner=Thread.currentThread();private final java.util.Queue<Runnable> tasks=new java.util.concurrent.ConcurrentLinkedQueue<>();
public void execute(Runnable task){if(Thread.currentThread()==owner)task.run();else tasks.add(task);}
public void drain(){if(Thread.currentThread()!=owner)throw new AssertionError("wrong executor thread");Runnable r;while((r=tasks.poll())!=null)r.run();}}""",
"check/Trace.java": """package check;
public class Trace {public static final java.util.List<String> events=new java.util.ArrayList<>();public static boolean ready;
public static final Thread owner=Thread.currentThread();
public static void add(String event){if(Thread.currentThread()!=owner)throw new AssertionError("off-thread callback: "+event);events.add(event);}}""",
"net/fabricmc/fabric/api/event/lifecycle/v1/ServerTickEvents.java": """package net.fabricmc.fabric.api.event.lifecycle.v1;
import net.fabricmc.fabric.api.event.*;import net.minecraft.server.MinecraftServer;
public class ServerTickEvents {public interface End {void onEndTick(MinecraftServer s);}
public static final Event<End> END_SERVER_TICK=EventFactory.createArrayBacked(End.class,a->s->{for(var f:a)f.onEndTick(s);});}""",
"net/fabricmc/fabric/api/event/lifecycle/v1/ServerLifecycleEvents.java": """package net.fabricmc.fabric.api.event.lifecycle.v1;
import net.fabricmc.fabric.api.event.*;import net.minecraft.server.MinecraftServer;
public class ServerLifecycleEvents {public interface Started {void onServerStarted(MinecraftServer s);}
public static final Event<Started> SERVER_STARTED=EventFactory.createArrayBacked(Started.class,a->s->{for(var f:a)f.onServerStarted(s);});}""",
"net/fabricmc/fabric/api/networking/v1/ServerPlayConnectionEvents.java": """package net.fabricmc.fabric.api.networking.v1;
import net.fabricmc.fabric.api.event.*;import net.minecraft.server.MinecraftServer;import net.minecraft.server.level.ServerPlayer;
public class ServerPlayConnectionEvents {public static class Handler {public ServerPlayer player;public Handler(ServerPlayer p){player=p;}}
public interface Disconnected {void onPlayDisconnect(Handler h,MinecraftServer s);}
public static final Event<Disconnected> DISCONNECT=EventFactory.createArrayBacked(Disconnected.class,a->(h,s)->{for(var f:a)f.onPlayDisconnect(h,s);});}""",
"net/fabricmc/fabric/api/client/networking/v1/ClientPlayConnectionEvents.java": """package net.fabricmc.fabric.api.client.networking.v1;
import net.fabricmc.fabric.api.event.*;import net.minecraft.client.Minecraft;
public class ClientPlayConnectionEvents {public interface Disconnected {void onPlayDisconnect(Object h,Minecraft mc);}
public static final Event<Disconnected> DISCONNECT=EventFactory.createArrayBacked(Disconnected.class,a->(h,mc)->{for(var f:a)f.onPlayDisconnect(h,mc);});}""",
"check/TerrainManager.java": """package check;import net.minecraft.server.MinecraftServer;
public class TerrainManager {public static boolean isBridgeWorld(){return Trace.ready;}
public static void onServerStarted(MinecraftServer s){Trace.ready=true;Trace.add("terrain-ready");}
public static void onServerTick(MinecraftServer s){Trace.add("terrain");}}""",
"check/B01Runtime.java": """package check;public class B01Runtime {
public B01Runtime(net.minecraft.server.MinecraftServer s){Trace.add("progression-ready");}}""",
"check/MountRuntime.java": """package check;public class MountRuntime {
public static void beforeTerrainTick(net.minecraft.server.MinecraftServer s){Trace.add("mount");}
public static void beforeRecall(net.minecraft.server.level.ServerPlayer p){Trace.add("dismount");}}""",
"check/HostEffectsBridge.java": """package check;public class HostEffectsBridge {
public static void onServerTick(net.minecraft.server.MinecraftServer s){Trace.add("effects");}}""",
"check/LifeBridge.java": """package check;public class LifeBridge {
public static void onServerStarted(net.minecraft.server.MinecraftServer s){Trace.add("life-ready");}
public static void onServerTick(net.minecraft.server.MinecraftServer s){Trace.add("life");}}""",
"check/WorldEnvironmentBridge.java": """package check;public class WorldEnvironmentBridge {
public static void onServerStarted(net.minecraft.server.MinecraftServer s){Trace.add("world-ready");}
public static void onServerTick(net.minecraft.server.MinecraftServer s){Trace.add("world");}}""",
}
for name, event in (("EntityBridge", "entities"), ("CombatBridge", "combat")):
    STUBS[f"check/{name}.java"] = (f"package check;public class {name} {{public static void "
        f'onServerTick(net.minecraft.server.MinecraftServer s){{Trace.add("{event}");}}}}')


CHECK = r"""package check;
import dev.ermc.bridge.api.*;import net.minecraft.world.entity.*;import net.minecraft.world.entity.player.*;
import net.minecraft.server.*;import net.minecraft.server.level.*;import net.minecraft.client.Minecraft;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;import net.fabricmc.fabric.api.networking.v1.*;
import net.fabricmc.fabric.api.client.networking.v1.*;
public class LifecycleCheck {
private static int checks;
static void ok(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
static void events(String... expected){ok(Trace.events.equals(java.util.List.of(expected)),"events "+Trace.events+" expected "+java.util.List.of(expected));Trace.events.clear();}
static void network(Runnable action)throws Exception{Thread t=new Thread(action,"simulated-netty");t.setUncaughtExceptionHandler((x,e)->failure=e);t.start();t.join();if(failure!=null)throw new AssertionError(failure);}
static volatile Throwable failure;
public static void main(String[] args)throws Exception{
Object world=new Object();Player player=new Player(world);
ok(MovementSubjects.body(player)==player,"Core-only falls back to player");
var selected=new java.util.concurrent.atomic.AtomicReference<Entity>();
MovementSubjects.register(p->{ok(p==player,"provider sees this exact player");return selected.get();});
ok(MovementSubjects.body(player)==player,"null provider falls back");
selected.set(player);ok(MovementSubjects.body(player)==player,"non-owning provider falls back");
Entity body=new Entity(world);selected.set(body);ok(MovementSubjects.body(player)==body,"live matching-world body used");
body.alive=false;ok(MovementSubjects.body(player)==player,"dead body rejected");body.alive=true;
body.removed=true;ok(MovementSubjects.body(player)==player,"removed body rejected");body.removed=false;
body.world=new Object();ok(MovementSubjects.body(player)==player,"other-world body rejected");body.world=world;
Entity second=new Entity(world);MovementSubjects.register(p->second);
ok(MovementSubjects.body(player)==body,"first valid provider wins");body.alive=false;
ok(MovementSubjects.body(player)==second,"invalid first provider does not hide next valid body");
MinecraftServer server=new MinecraftServer();ServerPlayer owner=new ServerPlayer(server);
BridgeLifecycle.BEFORE_TERRAIN_TICK.invoker().tick(server);BridgeLifecycle.BEFORE_RECALL.invoker().beforeRecall(owner);
BridgeLifecycle.CLIENT_CONTROL_RELEASED.invoker().released();events();
if(args[0].equals("character-first")){Wiring.character();Wiring.core();}else{Wiring.core();Wiring.character();}
ServerLifecycleEvents.SERVER_STARTED.invoker().onServerStarted(server);
events("terrain-ready","progression-ready","life-ready","world-ready");
ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server);
events("mount","terrain","entities","combat","effects","life","world");
Wiring.recall(owner);events("dismount","teleport");
Wiring.suspend();events("control-release","camera-release");
var handler=new ServerPlayConnectionEvents.Handler(owner);
network(()->ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(handler,server));events();
handler.player=new ServerPlayer(server);server.drain();events("server-disconnect");
ok(Wiring.disconnected==owner,"queued disconnect captures original player instance");
Minecraft mc=new Minecraft();Object connection=new Object();mc.connection=connection;
network(()->ClientPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(connection,mc));events();
mc.drain();events("client-disconnect");
network(()->ClientPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(connection,mc));
mc.connection=new Object();mc.drain();events();
ok(mc.connection!=connection,"old disconnect does not reset replacement connection");
Object current=mc.connection;network(()->ClientPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(current,mc));
mc.connection=null;mc.drain();events("client-disconnect");
System.out.println("PASS: "+checks+" movement/lifecycle assertions, "+args[0]+", real Fabric event implementation");
}}
"""


def main():
    core = (CORE / "ErBridgeMod.java").read_text(encoding="utf-8")
    client = (CLIENT / "ErBridgeClient.java").read_text(encoding="utf-8")
    mount = (CHARACTER / "MountRuntime.java").read_text(encoding="utf-8")
    b01 = (CHARACTER / "gameplay/runtime/B01Runtime.java").read_text(encoding="utf-8")
    character_entry = (CHARACTER / "character/CharacterBridgeMod.java").read_text(encoding="utf-8")
    server_started = statements(core, "ServerLifecycleEvents.SERVER_STARTED.register(")
    tick = statements(core, "ServerTickEvents.END_SERVER_TICK.register(")
    server_disconnect = statements(core, "ServerPlayConnectionEvents.DISCONNECT.register(")
    client_disconnect = statements(client, "ClientPlayConnectionEvents.DISCONNECT.register(")
    registrations = [*statements(mount, "BridgeLifecycle.BEFORE_TERRAIN_TICK.register("),
        *statements(mount, "BridgeLifecycle.BEFORE_RECALL.register("),
        *statements(b01, "BridgeLifecycle.HOST_WORLD_READY.register("),
        *statements(character_entry, "BridgeLifecycle.AFTER_COMBAT_TICK.register(")]
    assert len(server_started) == 3 and len(tick) == 7 and len(registrations) == 4
    assert len(server_disconnect) == len(client_disconnect) == 1
    recall = method((CORE / "TerrainManager.java").read_text(encoding="utf-8"), "private static void teleportToHunter(")
    release = "BridgeLifecycle.BEFORE_RECALL.invoker().beforeRecall(player);"
    assert recall.index(release) < recall.index("player.teleportTo("), "release must precede teleport"
    suspend = method((CLIENT / "CameraSync.java").read_text(encoding="utf-8"), "public static void suspend()")
    stubs = dict(STUBS)
    stubs["check/Wiring.java"] = """package check;
import dev.ermc.bridge.api.*;import net.minecraft.server.level.ServerPlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;import net.fabricmc.fabric.api.networking.v1.*;
import net.fabricmc.fabric.api.client.networking.v1.*;
public class Wiring {
static B01Runtime active;static ServerPlayer disconnected;static boolean driving;
static class Overlay {static boolean hostMode(){return true;}static boolean hostBusy(){return false;}static boolean active(){return true;}}
static void releaseHostCamera(){Trace.add("camera-release");}
static void core(){""" + "\n".join([*server_started, *tick, *server_disconnect, *client_disconnect]) + """}
static void character(){""" + "\n".join(registrations) + """
BridgeLifecycle.PLAYER_DISCONNECTED.register(p->{disconnected=p;Trace.add("server-disconnect");});
BridgeLifecycle.CLIENT_DISCONNECTED.register(()->Trace.add("client-disconnect"));
BridgeLifecycle.CLIENT_CONTROL_RELEASED.register(()->Trace.add("control-release"));}
static void recall(ServerPlayer player){""" + release + 'Trace.add("teleport");}\n' + suspend + "\n}"
    stubs["check/LifecycleCheck.java"] = CHECK
    cache = ROOT / ".tools/gradle-home/caches/modules-2/files-2.1"
    fabric = [p for p in (PROJECT / ".gradle/loom-cache/remapped_mods").rglob("fabric-api-base-*.jar")
              if not p.name.endswith("-sources.jar")]
    assert len(fabric) == 1, fabric
    dependencies = [*fabric]
    for group, artifact in (("com.google.guava", "guava"), ("org.slf4j", "slf4j-api"), ("it.unimi.dsi", "fastutil")):
        jars = [p for p in (cache / group / artifact).rglob("*.jar") if not p.name.endswith("-sources.jar")]
        assert jars, f"Missing cached {group}:{artifact}"
        dependencies.append(sorted(jars)[-1])
    # Record the actual off-thread origin that makes executor handoff necessary.
    network_sources = list((PROJECT / ".gradle/loom-cache/remapped_mods").rglob("fabric-networking-api-v1-*-sources.jar"))
    evidence = []
    for jar in network_sources:
        with zipfile.ZipFile(jar) as archive:
            name = "net/fabricmc/fabric/mixin/networking/ClientConnectionMixin.java"
            if name not in archive.namelist():
                continue
            source = archive.read(name).decode("utf-8")
            assert '@Inject(method = "channelInactive", at = @At("HEAD"))' in source
            assert "extension.getAddon().handleDisconnect();" in source
            evidence.append(str(jar.relative_to(ROOT)))
    assert evidence, "Pinned Fabric channelInactive evidence missing"
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "fabric-thread-evidence.txt").write_text("\n".join(evidence) + "\n", encoding="utf-8")
    java = java_path(None)
    with tempfile.TemporaryDirectory(prefix="erbridge-lifecycle-") as temporary:
        work = Path(temporary)
        classes = work / "classes"
        classes.mkdir()
        sources = [CORE / "api/MovementSubjects.java", CORE / "api/BridgeLifecycle.java"]
        for relative, source in stubs.items():
            path = work / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source, encoding="utf-8")
            sources.append(path)
        cp = os.pathsep.join(map(str, [classes, *dependencies]))
        commands = [("compile", [java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "-proc:none", "-encoding", "UTF-8", "-cp", cp, "-d", str(classes), *map(str, sources)])]
        commands += [(order, [java, "-cp", cp, "check.LifecycleCheck", order]) for order in ("core-first", "character-first")]
        for name, command in commands:
            result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
            (OUT / f"{name}.log").write_text(result.stdout + result.stderr, encoding="utf-8")
            print(result.stdout + result.stderr, end="")
            result.check_returncode()
    print("PASS: production registrations retain world-ready/tick/recall/F8 ordering and marshal Netty disconnects")


if __name__ == "__main__":
    main()
