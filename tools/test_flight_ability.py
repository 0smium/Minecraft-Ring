"""Exercise the production flight lease with explicit server/player/event doubles.

Uses real LocalHostOwner and CapabilitySnapshot. No game/Gradle/server is launched;
the policy provider and Minecraft ability packet side effect are bounded doubles.
"""
from pathlib import Path
import subprocess
import tempfile
from test_b01_preparation import java_path

ROOT=Path(__file__).resolve().parents[1]
MC=ROOT/'bridge-base/elden-ring/mc-bridge'
FLIGHT=MC/'character-bridge/src/main/java/dev/ermc/bridge/gameplay/runtime/FlightAbilityBridge.java'
STUBS={
'net/minecraft/server/MinecraftServer.java':'''package net.minecraft.server;
import java.util.*;import net.minecraft.server.level.ServerPlayer;
public final class MinecraftServer {
 public boolean dedicated;public Profile profile;public final Players players=new Players();
 public record Profile(UUID getId){}public static final class Players {public final Map<UUID,ServerPlayer> list=new HashMap<>();public ServerPlayer getPlayer(UUID id){return list.get(id);}}
 public boolean isDedicatedServer(){return dedicated;}public Profile getSingleplayerProfile(){return profile;}public Players getPlayerList(){return players;}
}''',
'net/minecraft/server/level/ServerPlayer.java':'''package net.minecraft.server.level;
import java.util.*;import net.minecraft.server.MinecraftServer;
public final class ServerPlayer {
 public final UUID id;public final MinecraftServer server;public boolean alive=true,removed,creative,spectator;public int updates;
 public final Abilities abilities=new Abilities();public static final class Abilities {public boolean mayfly,flying;public float speed=.05f;public float getFlyingSpeed(){return speed;}public void setFlyingSpeed(float s){speed=s;}}
 public ServerPlayer(MinecraftServer server,UUID id){this.server=server;this.id=id;}
 public boolean isAlive(){return alive;}public boolean isRemoved(){return removed;}public boolean isCreative(){return creative;}public boolean isSpectator(){return spectator;}
 public Abilities getAbilities(){return abilities;}public void onUpdateAbilities(){updates++;}public MinecraftServer getServer(){return server;}
}''',
'net/fabricmc/fabric/api/event/lifecycle/v1/ServerTickEvents.java':'''package net.fabricmc.fabric.api.event.lifecycle.v1;
import java.util.function.Consumer;import net.minecraft.server.MinecraftServer;
public final class ServerTickEvents {public static final Event END_SERVER_TICK=new Event();public static final class Event {private Consumer<MinecraftServer> callback;public void register(Consumer<MinecraftServer> c){callback=c;}public void fire(MinecraftServer s){callback.accept(s);}}}''',
'net/fabricmc/fabric/api/event/lifecycle/v1/ServerLifecycleEvents.java':'''package net.fabricmc.fabric.api.event.lifecycle.v1;
public final class ServerLifecycleEvents {public static final ServerTickEvents.Event SERVER_STOPPING=new ServerTickEvents.Event(),SERVER_STOPPED=new ServerTickEvents.Event();}''',
'dev/ermc/bridge/api/BridgeLifecycle.java':'''package dev.ermc.bridge.api;
import java.util.function.Consumer;import net.minecraft.server.level.ServerPlayer;
public final class BridgeLifecycle {public static final Event PLAYER_DISCONNECTED=new Event();public static final class Event {private Consumer<ServerPlayer> callback;public void register(Consumer<ServerPlayer> c){callback=c;}public void fire(ServerPlayer p){callback.accept(p);}}}''',
'dev/ermc/bridge/character/action/ActionLockServer.java':'''package dev.ermc.bridge.character.action;
import net.minecraft.server.level.ServerPlayer;
public final class ActionLockServer {public static ServerPlayer sleeping;public static boolean blocks(ServerPlayer player){return sleeping==player;}}''',
'dev/ermc/bridge/gameplay/runtime/B01Runtime.java':'''package dev.ermc.bridge.gameplay.runtime;
import java.util.*;import dev.ermc.bridge.api.CapabilitySnapshot;import net.minecraft.server.level.ServerPlayer;
public final class B01Runtime {public static final Map<ServerPlayer,CapabilitySnapshot> policies=new IdentityHashMap<>();public static boolean challenge=true;
public static boolean challengeFor(ServerPlayer p){return challenge;}public static Optional<CapabilitySnapshot> snapshotFor(ServerPlayer p){return Optional.ofNullable(policies.get(p));}}'''
}
CHECK=r'''import java.util.*;
import dev.ermc.bridge.api.*;import dev.ermc.bridge.api.CapabilitySnapshot.*;
import dev.ermc.bridge.gameplay.runtime.*;
import dev.ermc.bridge.character.action.ActionLockServer;
import net.minecraft.server.MinecraftServer;import net.minecraft.server.level.ServerPlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
public final class FlightLeaseCheck {
 static int checks;static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static void near(float a,float b,String message){check(Math.abs(a-b)<.000001f,message);}
 static Grant grant(int rank,double maximum){return new Grant(rank,true,Set.of("creative"),Map.of("speed",new NumericRange(.01,maximum)));}
 static Grant locked(){return new Grant(0,true,Set.of(),Map.of());}
 static void policy(ServerPlayer p,Grant g){B01Runtime.policies.put(p,new CapabilitySnapshot(new CharacterBinding(p.id,UUID.randomUUID(),UUID.randomUUID()),1,Map.of(new Ability("fly"),g)));}
 static void tick(MinecraftServer s){ServerTickEvents.END_SERVER_TICK.fire(s);}
 public static void main(String[] args){
  FlightAbilityBridge.initialize();MinecraftServer server=new MinecraftServer();UUID id=UUID.randomUUID();server.profile=new MinecraftServer.Profile(id);
  ServerPlayer p=new ServerPlayer(server,id),guest=new ServerPlayer(server,UUID.randomUUID());server.players.list.put(guest.id,guest);policy(guest,grant(1,.02));
  tick(server);check(!guest.abilities.mayfly,"LAN guest cannot inherit host permission");
  server.players.list.put(id,p);policy(p,grant(1,.02));server.dedicated=true;tick(server);check(!p.abilities.mayfly,"dedicated server is not local owner");server.dedicated=false;
  policy(p,locked());tick(server);check(!p.abilities.mayfly&&p.updates==0,"locked grant leaves vanilla flags alone");
  policy(p,grant(1,.02));tick(server);check(p.abilities.mayfly&&!p.abilities.flying&&p.updates==1,"grant leases permission without auto-flying");near(p.abilities.speed,.02f,"grant caps speed");
  p.abilities.speed=.015f;p.abilities.flying=true;for(int i=0;i<20;i++)tick(server);check(p.updates==1,"unchanged grant sends no per-tick abilities packet");near(p.abilities.speed,.015f,"legal preference remains untouched");
  policy(p,grant(2,.03));tick(server);near(p.abilities.speed,.015f,"upgrade retains legal preference");check(p.updates==2,"upgrade sends once");
  policy(p,grant(1,.012));tick(server);near(p.abilities.speed,.012f,"downgrade applies reduced cap");
  B01Runtime.policies.remove(p);tick(server);check(!p.abilities.mayfly&&!p.abilities.flying,"loading absent snapshot revokes lease");near(p.abilities.speed,.05f,"release restores prior speed");
  policy(p,grant(1,.02));tick(server);p.abilities.flying=true;ActionLockServer.sleeping=p;tick(server);
  check(!p.abilities.mayfly&&!p.abilities.flying,"sleep suspends bridge-owned flight lease");near(p.abilities.speed,.05f,"sleep restores original speed");
  int sleepUpdates=p.updates;for(int i=0;i<15;i++)tick(server);check(p.updates==sleepUpdates,"sleep cannot re-grant flight each tick");
  ActionLockServer.sleeping=null;tick(server);check(p.abilities.mayfly&&!p.abilities.flying,"wake re-grants permission without automatically resuming flight");
  near(p.abilities.speed,.02f,"wake reapplies current learned speed cap");B01Runtime.policies.remove(p);tick(server);
  p.abilities.mayfly=true;p.abilities.flying=true;p.abilities.speed=.06f;policy(p,grant(1,.02));tick(server);policy(p,locked());tick(server);
  check(p.abilities.mayfly&&p.abilities.flying,"pre-existing external flight permission retained");near(p.abilities.speed,.06f,"pre-existing speed restored");
  p.abilities.mayfly=false;p.abilities.flying=false;p.abilities.speed=.05f;policy(p,grant(1,.02));tick(server);p.abilities.flying=true;p.alive=false;tick(server);
  check(!p.abilities.mayfly&&!p.abilities.flying,"dead player must not reacquire released flight lease");int sent=p.updates;tick(server);check(p.updates==sent,"dead instance stays released");
  p.alive=true;tick(server);check(p.abilities.mayfly,"alive owner can reacquire current grant");p.removed=true;tick(server);check(!p.abilities.mayfly,"removed instance cannot reacquire");
  ServerPlayer next=new ServerPlayer(server,id);server.players.list.put(id,next);tick(server);check(!next.abilities.mayfly,"respawn never copies another instance's grant");policy(next,grant(1,.02));tick(server);check(next.abilities.mayfly,"fresh respawn receives its own validated policy");
  next.creative=true;next.abilities.mayfly=true;next.abilities.flying=true;next.abilities.speed=.08f;sent=next.updates;tick(server);B01Runtime.policies.remove(next);tick(server);
  check(next.abilities.mayfly&&next.abilities.flying&&next.updates==sent,"creative gamemode owns abilities");near(next.abilities.speed,.08f,"creative speed not overwritten");
  next.creative=false;next.spectator=true;next.abilities.speed=.09f;tick(server);near(next.abilities.speed,.09f,"spectator speed not overwritten");
  next.spectator=false;next.abilities.mayfly=false;next.abilities.flying=false;next.abilities.speed=.05f;policy(next,grant(1,.02));tick(server);B01Runtime.challenge=false;tick(server);check(!next.abilities.mayfly,"free mode releases challenge lease");B01Runtime.challenge=true;
  policy(next,new Grant(1,true,Set.of("unsupported"),Map.of("speed",new NumericRange(.01,.02))));tick(server);check(!next.abilities.mayfly,"unsupported mode rejected");
  policy(next,new Grant(1,true,Set.of("creative"),Map.of()));tick(server);check(!next.abilities.mayfly,"missing speed policy rejected");
  policy(next,grant(1,.02));tick(server);BridgeLifecycle.PLAYER_DISCONNECTED.fire(next);check(!next.abilities.mayfly,"disconnect releases holder");tick(server);ServerLifecycleEvents.SERVER_STOPPING.fire(server);check(!next.abilities.mayfly,"server stopping releases holder");ServerLifecycleEvents.SERVER_STOPPED.fire(server);
  System.out.println("PASS: "+checks+" production flight lease assertions; real LocalHostOwner and capability records, explicit MC/policy/event doubles");
 }
}
'''

def run(source,expect_failure=False):
    java=java_path(None)
    with tempfile.TemporaryDirectory(prefix='ermc-flight-') as folder:
        work=Path(folder);paths=[]
        for name,text in STUBS.items():
            file=work/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(text);paths.append(file)
        flight=work/'dev/ermc/bridge/gameplay/runtime/FlightAbilityBridge.java';flight.write_text(source);paths.append(flight)
        test=work/'FlightLeaseCheck.java';test.write_text(CHECK);paths.append(test)
        for name in ['LocalHostOwner.java','api/CapabilitySnapshot.java','api/CharacterBinding.java']:
            paths.append(MC/'src/main/java/dev/ermc/bridge'/name)
        subprocess.run([java,'-m','jdk.compiler/com.sun.tools.javac.Main','--release','17','-encoding','UTF-8','-Xlint:all','-Werror','-d',str(work),*map(str,paths)],check=True)
        result=subprocess.run([java,'-cp',str(work),'FlightLeaseCheck'],text=True,capture_output=True)
        if expect_failure:
            assert result.returncode and 'dead player must not reacquire' in result.stderr,result.stdout+result.stderr
            print('PASS: same production test detects the pre-fix dead-player re-grant regression')
        else:
            print(result.stdout,end='');print(result.stderr,end='');result.check_returncode()

def main():
    source=FLIGHT.read_text();run(source)
    old=source.replace('if (player == null || !player.isAlive() || player.isRemoved()) return;','if (player == null) return;')
    assert old!=source
    run(old,True)
    registration=(MC/'character-bridge/src/main/java/dev/ermc/bridge/character/CharacterBridgeMod.java').read_text()
    assert registration.index('B01Runtime.initialize()')<registration.index('FlightAbilityBridge.initialize()')
    print('PASS: flight listener registration remains after B01 policy runtime')

if __name__=='__main__':main()
