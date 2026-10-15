"""Exercise production sleep ownership/actions with explicit MC, Fabric and network doubles.

No game, Gradle, native process or B01 configuration is used. This is a server
state-machine check; real movement/rendering and client enforcement are separate.
"""
from pathlib import Path
import subprocess
import tempfile
from test_b01_preparation import java_path
from test_flight_ability import STUBS as FLIGHT_STUBS

ROOT = Path(__file__).resolve().parents[1]
MC = ROOT / 'bridge-base/elden-ring/mc-bridge'
STUBS = {
    name: FLIGHT_STUBS[name] for name in (
        'net/minecraft/server/MinecraftServer.java',
        'net/fabricmc/fabric/api/event/lifecycle/v1/ServerTickEvents.java',
        'net/fabricmc/fabric/api/event/lifecycle/v1/ServerLifecycleEvents.java')
}
STUBS.update({
    'net/minecraft/world/entity/player/Player.java': '''package net.minecraft.world.entity.player;
public class Player {public final Object held=new Object();public Object getItemInHand(Object hand){return held;}}''',
    'net/minecraft/server/level/ServerPlayer.java': '''package net.minecraft.server.level;
import java.util.UUID;import net.minecraft.server.MinecraftServer;import net.minecraft.network.chat.Component;
public final class ServerPlayer extends net.minecraft.world.entity.player.Player {
 public final UUID id;public final MinecraftServer server;
 public boolean alive=true,removed,using,sprinting;public int updates,stops,releases,tickCount;
 public final double[] velocity={.7,-.2,.4};public final Abilities abilities=new Abilities();
 public static final class Abilities {public boolean mayfly,flying;}
 public ServerPlayer(MinecraftServer server,UUID id){this.server=server;this.id=id;}
 public boolean isAlive(){return alive;}public boolean isRemoved(){return removed;}
 public MinecraftServer getServer(){return server;}public UUID getUUID(){return id;}
 public Component getName(){return new Component("fixture");}public boolean isUsingItem(){return using;}
 public void stopUsingItem(){using=false;stops++;}public void releaseUsingItem(){using=false;releases++;}
 public void setSprinting(boolean value){sprinting=value;}public Abilities getAbilities(){return abilities;}
 public void onUpdateAbilities(){updates++;}
}''',
    'net/minecraft/network/chat/Component.java': 'package net.minecraft.network.chat;public record Component(String getString) {}',
    'org/slf4j/Logger.java': 'package org.slf4j;public interface Logger {default void info(String text,Object... args){}}',
    'org/slf4j/LoggerFactory.java': 'package org.slf4j;public final class LoggerFactory {public static Logger getLogger(String name){return new Logger(){};}}',
    'net/minecraft/world/InteractionResult.java': 'package net.minecraft.world;public enum InteractionResult {PASS,FAIL}',
    'net/minecraft/world/InteractionResultHolder.java': '''package net.minecraft.world;
public record InteractionResultHolder<T>(InteractionResult result,T object) {
 public static <T> InteractionResultHolder<T> pass(T value){return new InteractionResultHolder<>(InteractionResult.PASS,value);}
 public static <T> InteractionResultHolder<T> fail(T value){return new InteractionResultHolder<>(InteractionResult.FAIL,value);}
}''',
    'net/fabricmc/fabric/api/event/Event.java': '''package net.fabricmc.fabric.api.event;
import java.util.*;import java.util.function.Function;
public final class Event<T> {
 private final Class<T> type;private final Function<T[],T> factory;private final List<T> callbacks=new ArrayList<>();
 public Event(Class<T> type,Function<T[],T> factory){this.type=type;this.factory=factory;}
 public void register(T callback){callbacks.add(callback);}
 @SuppressWarnings("unchecked") public T invoker(){return factory.apply(callbacks.toArray((T[])java.lang.reflect.Array.newInstance(type,0)));}
}''',
    'net/fabricmc/fabric/api/event/EventFactory.java': '''package net.fabricmc.fabric.api.event;
import java.util.function.Function;public final class EventFactory {
 public static <T> Event<T> createArrayBacked(Class<T> type,Function<T[],T> factory){return new Event<>(type,factory);}
}''',
    'dev/ermc/bridge/api/BridgeLifecycle.java': '''package dev.ermc.bridge.api;
import java.util.function.Consumer;import net.minecraft.server.level.ServerPlayer;
public final class BridgeLifecycle {
 public static final Event PLAYER_DISCONNECTED=new Event(),BEFORE_RECALL=new Event();
 public static final class Event {private Consumer<ServerPlayer> callback;public void register(Consumer<ServerPlayer> value){callback=value;}public void fire(ServerPlayer p){callback.accept(p);}}
}''',
    'dev/ermc/bridge/character/action/ActionLockPayload.java': '''package dev.ermc.bridge.character.action;
import java.util.UUID;public record ActionLockPayload(UUID owner,long epoch,long sequence,boolean sleeping) {
 public static final Object TYPE=new Object(),CODEC=new Object();
 public ActionLockPayload {if(owner==null||epoch<=0||sequence<=0)throw new IllegalArgumentException();}
}''',
    'net/fabricmc/fabric/api/networking/v1/PayloadTypeRegistry.java': '''package net.fabricmc.fabric.api.networking.v1;
public final class PayloadTypeRegistry {public static int registrations;private static final PayloadTypeRegistry INSTANCE=new PayloadTypeRegistry();
 public static PayloadTypeRegistry playS2C(){return INSTANCE;}public void register(Object type,Object codec){registrations++;}
}''',
    'net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking.java': '''package net.fabricmc.fabric.api.networking.v1;
import java.util.*;import net.minecraft.server.level.ServerPlayer;import dev.ermc.bridge.character.action.ActionLockPayload;
public final class ServerPlayNetworking {
 public record Sent(ServerPlayer player,ActionLockPayload payload){}
 public static final List<Sent> packets=new ArrayList<>();public static final Set<ServerPlayer> unsupported=Collections.newSetFromMap(new IdentityHashMap<>());
 public static boolean canSend(ServerPlayer p,Object type){return !unsupported.contains(p);}
 public static void send(ServerPlayer p,ActionLockPayload value){if(!canSend(p,ActionLockPayload.TYPE))throw new AssertionError("unsupported send");packets.add(new Sent(p,value));}
}''',
})
for name, parameters, result in (
    ('AttackBlockCallback', 'Player player,Object level,Object hand,Object pos,Object face', 'InteractionResult'),
    ('AttackEntityCallback', 'Player player,Object level,Object hand,Object target,Object hit', 'InteractionResult'),
    ('UseBlockCallback', 'Player player,Object level,Object hand,Object hit', 'InteractionResult'),
    ('UseEntityCallback', 'Player player,Object level,Object hand,Object target,Object hit', 'InteractionResult'),
    ('UseItemCallback', 'Player player,Object level,Object hand', 'InteractionResultHolder<Object>'),
):
    STUBS[f'net/fabricmc/fabric/api/event/player/{name}.java'] = f'''package net.fabricmc.fabric.api.event.player;
import net.minecraft.world.entity.player.Player;import net.minecraft.world.*;
public interface {name} {{
 {result} interact({parameters});Event EVENT=new Event();
 final class Event {{public {name} callback;public void register({name} value){{callback=value;}}}}
}}'''
STUBS['net/fabricmc/fabric/api/event/player/PlayerBlockBreakEvents.java'] = '''package net.fabricmc.fabric.api.event.player;
import net.minecraft.world.entity.player.Player;
public final class PlayerBlockBreakEvents {public interface Before {boolean before(Object world,Player player,Object pos,Object state,Object entity);}
 public static final Event BEFORE=new Event();public static final class Event {public Before callback;public void register(Before value){callback=value;}}
}'''


def main():
    java = java_path(None)
    with tempfile.TemporaryDirectory(prefix='ermc-sleep-server-') as directory:
        out = Path(directory)
        files = []
        for relative, content in STUBS.items():
            path = out / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
            files.append(path)
        files += [
            MC / 'src/main/java/dev/ermc/bridge/LocalHostOwner.java',
            MC / 'src/main/java/dev/ermc/bridge/api/PlayerActions.java',
            MC / 'character-bridge/src/main/java/dev/ermc/bridge/character/action/ActionLockServer.java',
            MC / 'src/testFixtures/sleep-action/SleepActionServerCheck.java',
        ]
        subprocess.run([java, '-m', 'jdk.compiler/com.sun.tools.javac.Main', '--release', '17',
                        '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d', str(out), *map(str, files)], check=True)
        subprocess.run([java, '-cp', str(out), 'SleepActionServerCheck'], check=True)


if __name__ == '__main__':
    main()
