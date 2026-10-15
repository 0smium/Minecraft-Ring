import java.util.*;
import dev.ermc.bridge.api.*;
import dev.ermc.bridge.character.action.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.*;
import net.fabricmc.fabric.api.networking.v1.*;

/** Real server restriction/owner/API with explicit Fabric, player and networking doubles. */
public final class SleepActionServerCheck {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static int count(){return ServerPlayNetworking.packets.size();}
    private static ActionLockPayload last(){return ServerPlayNetworking.packets.get(count()-1).payload();}
    private static MinecraftServer server(UUID id){var s=new MinecraftServer();s.profile=new MinecraftServer.Profile(id);return s;}
    private static ServerPlayer owner(MinecraftServer s,UUID id){var p=new ServerPlayer(s,id);s.players.list.put(id,p);return p;}
    private static void callbacks(Player p,boolean allowed){
        var result=allowed?InteractionResult.PASS:InteractionResult.FAIL;
        check(AttackBlockCallback.EVENT.callback.interact(p,null,null,null,null)==result,"block attack gate");
        check(AttackEntityCallback.EVENT.callback.interact(p,null,null,null,null)==result,"entity attack gate");
        check(UseBlockCallback.EVENT.callback.interact(p,null,null,null)==result,"block use gate");
        check(UseEntityCallback.EVENT.callback.interact(p,null,null,null,null)==result,"entity use gate");
        var item=UseItemCallback.EVENT.callback.interact(p,null,null);
        check(item.result()==result&&item.object()==p.held,"item use gate preserves original held item");
        check(PlayerBlockBreakEvents.BEFORE.callback.before(null,p,null,null,null)==allowed,"block break gate");
    }
    public static void main(String[] args){
        ActionLockServer.initialize();
        check(PayloadTypeRegistry.registrations==1,"one S2C restriction channel");
        check(!PlayerActions.allowed(null)&&PlayerActions.allowed(new Player()),"optional rule leaves unrelated player types allowed");
        UUID id=UUID.randomUUID();var s=server(id);var p=owner(s,id);var guest=owner(s,UUID.randomUUID());
        ActionLockServer.observe(guest,true);check(!ActionLockServer.blocks(guest)&&count()==0,"LAN guest cannot acquire host sleep");
        s.dedicated=true;ActionLockServer.observe(p,true);check(!ActionLockServer.blocks(p)&&count()==0,"dedicated server is not local ER owner");s.dedicated=false;
        ActionLockServer.observe(p,false);check(!ActionLockServer.blocks(p)&&!last().sleeping(),"explicit awake observation is published");
        long epoch=last().epoch(),sequence=last().sequence();
        p.using=p.sprinting=p.abilities.flying=p.abilities.mayfly=true;
        ActionLockServer.observe(p,true);
        check(ActionLockServer.blocks(p)&&!PlayerActions.allowed(p)&&PlayerActions.allowed(guest),"only actual local owner is restricted");
        check(last().sleeping()&&last().epoch()==epoch&&last().sequence()>sequence,"sleep change increments sequence in same server epoch");
        check(p.stops==1&&p.releases==0&&!p.using,"cancel use without releasing a charged bow");
        check(!p.sprinting&&!p.abilities.flying&&p.abilities.mayfly&&p.updates==1,"stop sprint/flight while preserving mayfly permission");
        check(Arrays.equals(p.velocity,new double[]{.7,-.2,.4}),"sleep preserves velocity, knockback and gravity");
        callbacks(p,false);callbacks(guest,true);callbacks(new Player(),true);
        int sent=count();for(int tick=1;tick<10;tick++){p.tickCount=tick;ActionLockServer.observe(p,true);}
        check(count()==sent&&p.stops==1&&p.updates==1,"no per-tick packets or repeated cancellation");
        p.tickCount=10;ActionLockServer.observe(p,true);check(count()==sent+1&&last().sleeping(),"ten-tick heartbeat renews active restriction");
        p.tickCount=2;ActionLockServer.observe(p,true);check(count()==sent+2,"tick-count rewind publishes a fresh sequence");
        BridgeLifecycle.PLAYER_DISCONNECTED.fire(guest);check(ActionLockServer.blocks(p),"guest disconnect does not clear owner");
        ServerLifecycleEvents.SERVER_STOPPING.fire(server(UUID.randomUUID()));check(ActionLockServer.blocks(p),"unrelated server stop ignored");
        ActionLockServer.observe(p,false);check(!ActionLockServer.blocks(p)&&PlayerActions.allowed(p)&&!last().sleeping(),"awake releases gate immediately");
        check(!p.abilities.flying&&p.abilities.mayfly,"wake does not resume flying automatically");callbacks(p,true);
        ActionLockServer.observe(p,true);ActionLockServer.clear();check(!ActionLockServer.blocks(p)&&!last().sleeping(),"clear sends explicit release");
        sent=count();ActionLockServer.clear();check(count()==sent,"repeated clear is silent");
        ActionLockServer.observe(p,true);p.alive=false;check(!ActionLockServer.blocks(p),"dead holder never blocks before next observation");
        ActionLockServer.observe(p,true);check(!last().sleeping(),"death clears published lock");p.alive=true;
        ActionLockServer.observe(p,true);p.removed=true;check(!ActionLockServer.blocks(p),"removed instance never blocks");
        ActionLockServer.observe(p,true);check(!last().sleeping(),"removed holder is released");p.removed=false;
        ActionLockServer.observe(p,true);var next=owner(s,id);
        check(!ActionLockServer.blocks(p)&&!ActionLockServer.blocks(next),"respawn replacement inherits no old lock");
        ActionLockServer.observe(next,true);check(ActionLockServer.blocks(next)&&!ActionLockServer.blocks(p),"fresh instance receives its own observation");
        check(ServerPlayNetworking.packets.get(count()-2).player()==p&&!ServerPlayNetworking.packets.get(count()-2).payload().sleeping(),"replacement releases old client before publishing new instance");
        BridgeLifecycle.BEFORE_RECALL.fire(next);check(!ActionLockServer.blocks(next)&&!last().sleeping(),"recall clears holder");
        ActionLockServer.observe(next,true);BridgeLifecycle.PLAYER_DISCONNECTED.fire(next);check(!ActionLockServer.blocks(next)&&!last().sleeping(),"owner disconnect clears holder");
        ActionLockServer.observe(next,true);s.profile=new MinecraftServer.Profile(guest.id);
        check(!ActionLockServer.blocks(next),"changed local profile immediately invalidates old owner");
        ActionLockServer.observe(guest,true);check(ActionLockServer.blocks(guest)&&!ActionLockServer.blocks(next),"new local profile has separate owner observation");
        var second=server(UUID.randomUUID());var secondOwner=owner(second,second.profile.getId());
        ActionLockServer.observe(secondOwner,true);check(last().epoch()>epoch&&last().sequence()==1,"new server uses newer epoch with fresh sequence");
        check(!ActionLockServer.blocks(guest)&&ActionLockServer.blocks(secondOwner),"server switch releases earlier host");
        ServerLifecycleEvents.SERVER_STOPPING.fire(second);check(!ActionLockServer.blocks(secondOwner)&&!last().sleeping(),"server stop releases before save");
        ServerLifecycleEvents.SERVER_STOPPED.fire(second);epoch=last().epoch();
        ActionLockServer.observe(secondOwner,true);check(last().epoch()>epoch,"restart obtains a newer epoch");
        ActionLockServer.clear();ServerPlayNetworking.unsupported.add(secondOwner);sent=count();
        ActionLockServer.observe(secondOwner,true);check(ActionLockServer.blocks(secondOwner)&&count()==sent,"unsupported channel sends no payload while server gate remains authoritative");
        secondOwner.tickCount+=10;ActionLockServer.observe(secondOwner,true);check(count()==sent,"unsupported heartbeat also stays silent");
        ServerPlayNetworking.unsupported.remove(secondOwner);secondOwner.tickCount+=10;ActionLockServer.observe(secondOwner,true);
        check(count()==sent+1&&last().sleeping(),"newly supported channel receives next heartbeat");
        ActionLockServer.reset();check(!ActionLockServer.blocks(secondOwner)&&!last().sleeping(),"reset emits release then forgets state");
        long previousEpoch=0,previousSequence=0;
        for(var packet:ServerPlayNetworking.packets){
            var value=packet.payload();
            check(value.owner().equals(packet.player().id),"payload owner matches target connection");
            check(value.epoch()>=previousEpoch,"epochs never rewind");
            check(value.epoch()>previousEpoch||value.sequence()>previousSequence,"sequence strictly grows within epoch");
            previousEpoch=value.epoch();previousSequence=value.sequence();
        }
        System.out.println("PASS: "+checks+" production ActionLockServer/LocalHostOwner/PlayerActions assertions with explicit MC/Fabric/network doubles");
        System.out.println("LIMIT: no game, native state, B01 configuration, live movement or real networking was exercised");
    }
}
