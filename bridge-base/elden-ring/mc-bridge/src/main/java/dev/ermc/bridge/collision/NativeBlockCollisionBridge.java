package dev.ermc.bridge.collision;

import dev.ermc.bridge.CoordMap;
import dev.ermc.bridge.LocalHostOwner;
import dev.ermc.bridge.TerrainManager;
import dev.ermc.bridge.link.BlockColliderChannel;
import dev.ermc.bridge.link.ErLink;
import dev.ermc.bridge.link.GameState;
import dev.ermc.bridge.link.Protocol;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Objects;

/** Default-off server authority. The native DLL independently validates ownership, age and offline state. */
public final class NativeBlockCollisionBridge {
    public static final boolean ENABLED = Boolean.getBoolean("erbridge.nativeBlockCollision");
    private static final Logger LOG=LoggerFactory.getLogger("erbridge");
    private static final GameState STATE=new GameState();
    private static final float[] EMPTY=new float[0],ZERO=new float[3];
    private record Key(BlockColliderChannel.Session host,CoordMap.Mapping map) {}
    private static Key key;
    private static ServerPlayer owner;
    private static long epoch=1,lastLog;
    private static String lastDescription="";
    private NativeBlockCollisionBridge() {}

    public static void reset(MinecraftServer server) {
        if(!ENABLED)return;
        ErLink.get().writeBlockColliders(null,0,0,ZERO,EMPTY,false,false);
        PlacedBlockCollisionSnapshots.INSTANCE.invalidate();
        key=null;owner=null;epoch++;lastDescription="";
    }
    public static void disconnected(ServerPlayer player) {
        if(player==owner)reset(player.getServer());
    }
    public static void tick(MinecraftServer server) {
        if(!ENABLED)return;
        ErLink link=ErLink.get();
        var player=LocalHostOwner.find(server);
        var map=CoordMap.get();
        if(!TerrainManager.isBridgeWorld()||player==null||!player.isAlive()||player.serverLevel()!=server.overworld()
            ||map==null||map.provisional()||!map.contains(player.getX())||!link.alive()||!link.snapshot(STATE)
            ||STATE.stageId!=map.zone()||!STATE.has(Protocol.STATE_PLAYER_VALID)
            ||STATE.has(Protocol.STATE_PLAYER_DEAD|Protocol.STATE_HOST_BUSY)) {
            if(key!=null)reset(server);
            return;
        }
        var session=link.blockColliderSession(map.zone());
        if(session==null||!session.valid()) {if(key!=null)reset(server);return;}
        Key current=new Key(session,map);
        if(!Objects.equals(current,key)||owner!=player) {
            reset(server);key=current;owner=player;
        }
        try {
            var source=PlacedBlockCollisionSnapshots.INSTANCE.sample(player.serverLevel(),key,player.position(),server.getTickCount());
            double[] feet=map.toHost(player.getX(),player.getY(),player.getZ());
            float[] center={(float)feet[0],(float)feet[1],(float)feet[2]};
            float[] boxes=toHost(map,source.boxes());
            boolean published=link.writeBlockColliders(session,epoch,source.revision(),center,boxes,source.complete(),source.capped());
            long now=System.currentTimeMillis();
            String description=source.status()+" boxes="+source.boxes().size()+" capped="+source.capped()+" published="+published;
            var nativeState=link.readBlockColliderStatus(session);
            if(nativeState.isPresent()) {
                var n=nativeState.get();
                description+=" native="+n.state()+" owned="+n.owned()+" pending="+n.pending()+" error="+n.error()+" quarantined="+n.quarantined();
            }
            if(!description.equals(lastDescription)&&now-lastLog>=1000) {
                LOG.info("Native blocks: {}",description);lastDescription=description;lastLog=now;
            }
        } catch(RuntimeException invalid) {
            reset(server);
            if(System.currentTimeMillis()-lastLog>=5000) {
                LOG.warn("Native blocks: snapshot unavailable: {}",invalid.toString());lastLog=System.currentTimeMillis();
            }
        }
    }
    public static float[] toHost(CoordMap.Mapping map,java.util.List<ColliderMerge.Box> boxes) {
        float[] out=new float[boxes.size()*6];int i=0;
        for(var box:boxes) {
            double[] a=map.toHost(box.minX(),box.minY(),box.minZ());
            double[] b=map.toHost(box.maxX(),box.maxY(),box.maxZ());
            for(int axis=0;axis<3;axis++) {
                out[i+axis]=(float)Math.min(a[axis],b[axis]);out[i+axis+3]=(float)Math.max(a[axis],b[axis]);
            }
            i+=6;
        }
        return out;
    }
}
