package dev.ermc.bridge.collision;

import dev.ermc.bridge.TerrainBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import java.util.*;

/**
 * Server-thread projection of real placed block collision, independent of native transport.
 * Nothing is installed or sampled until the opt-in runtime calls sample. Incomplete scans
 * return no boxes, so removal, unload and scene changes cannot retain stale native walls.
 */
public final class PlacedBlockCollisionSnapshots {
    public static final PlacedBlockCollisionSnapshots INSTANCE=new PlacedBlockCollisionSnapshots();
    public static final int MAX_BOXES=512,MAX_SCANNED_BLOCKS=32768,MAX_RAW_BOXES=32768;
    public static final int MAX_SECTION_BOXES=8192,MAX_SHAPE_BOXES=64,MAX_DYNAMIC_CELLS=256;
    public record Snapshot(long revision,boolean complete,int scannedBlocks,int rawBoxCount,int mergedBoxCount,
                           List<ColliderMerge.Box> boxes,boolean capped,String status){
        public Snapshot {boxes=List.copyOf(boxes);}
    }
    private record SectionKey(int x,int y,int z){}
    private record Cell(BlockPos pos,boolean dynamic,List<ColliderMerge.Box> boxes){}
    private static final class CachedSection {
        final Object chunkIdentity,sectionIdentity;
        final List<Cell> cells;
        final String failure;
        final boolean retry;
        CachedSection(SectionView source,List<Cell> cells,String failure,boolean retry){
            chunkIdentity=source.chunkIdentity();sectionIdentity=source.sectionIdentity();this.cells=cells;this.failure=failure;this.retry=retry;
        }
    }
    /** Loaded-only view, also used by the offline test with actual Minecraft block shapes. */
    interface WorldView extends BlockGetter {
        Object identity();
        SectionView section(int x,int y,int z);
        default int missingReads(){return 0;}
    }
    interface SectionView {
        Object chunkIdentity();Object sectionIdentity();boolean loaded();boolean mayHaveRealBlocks();
        BlockState state(int x,int y,int z);
    }
    private Object owner,session;
    private final Map<SectionKey,CachedSection> sections=new HashMap<>();
    private ColliderSnapshotCache cache=new ColliderSnapshotCache();
    private long geometryRevision,revision,previousTick;
    private Vec3 previousFeet;
    private Snapshot latest=new Snapshot(0,false,0,0,0,List.of(),false,"inactive");

    /** Root owns local-player/session/control gates and converts these MC coordinates to host space. */
    public Snapshot sample(ServerLevel level,Object sessionKey,Vec3 feet,long serverTick){
        if(!level.getServer().isSameThread())throw new IllegalStateException("Placed collision sampling must run on the server thread");
        return sample(new LoadedWorld(level),sessionKey,feet,serverTick);
    }
    Snapshot sample(WorldView world,Object sessionKey,Vec3 feet,long serverTick){
        Objects.requireNonNull(world);Objects.requireNonNull(sessionKey);Objects.requireNonNull(feet);
        if(owner!=world.identity()||!Objects.equals(session,sessionKey)){
            clear();owner=world.identity();session=sessionKey;revision++;
        }
        double vx=0,vy=0,vz=0;
        if(previousFeet!=null&&serverTick>previousTick&&serverTick-previousTick<=5){
            double seconds=(serverTick-previousTick)/20.0;
            vx=(feet.x-previousFeet.x)/seconds;vy=(feet.y-previousFeet.y)/seconds;vz=(feet.z-previousFeet.z)/seconds;
        }
        previousFeet=feet;previousTick=serverTick;
        ColliderWindow window=ColliderWindow.around(feet.x,feet.y,feet.z,vx,vy,vz);
        List<CachedSection> visible=new ArrayList<>();Set<SectionKey> retained=new HashSet<>();
        int scanned=0,dynamic=0;String failure=null;
        for(int sx=window.minX()>>4;sx<=window.maxX()>>4;sx++)
            for(int sz=window.minZ()>>4;sz<=window.maxZ()>>4;sz++)
                for(int sy=window.minY()>>4;sy<=window.maxY()>>4;sy++){
                    SectionKey key=new SectionKey(sx,sy,sz);retained.add(key);
                    SectionView source=world.section(sx,sy,sz);CachedSection old=sections.get(key);
                    if(!source.loaded()){
                        if(sections.remove(key)!=null)geometryRevision++;
                        failure="unloaded_chunk";continue;
                    }
                    if(old==null||old.chunkIdentity!=source.chunkIdentity()||old.sectionIdentity!=source.sectionIdentity()||old.retry){
                        if(source.mayHaveRealBlocks()&&scanned+4096>MAX_SCANNED_BLOCKS){failure="scan_budget";continue;}
                        if(source.mayHaveRealBlocks())scanned+=4096;
                        old=scan(world,source,sx,sy,sz);sections.put(key,old);geometryRevision++;
                    }
                    if(old.failure!=null){failure=old.failure;continue;}
                    for(int i=0;i<old.cells.size();i++){
                        Cell cell=old.cells.get(i);if(!cell.dynamic)continue;
                        if(++dynamic>MAX_DYNAMIC_CELLS){failure="dynamic_budget";break;}
                        int missingBefore=world.missingReads();
                        try {
                            List<ColliderMerge.Box> boxes=extractBoxes(world,cell.pos,world.getBlockState(cell.pos));
                            if(!boxes.equals(cell.boxes)){old.cells.set(i,new Cell(cell.pos,true,boxes));geometryRevision++;}
                            if(world.missingReads()!=missingBefore){sections.remove(key);geometryRevision++;failure="unloaded_shape_neighbor";break;}
                        }catch(RuntimeException incompatible){failure="unsupported_dynamic_shape";break;}
                    }
                    visible.add(old);
                }
        sections.keySet().retainAll(retained);
        if(failure!=null)return publish(false,scanned,0,0,List.of(),false,failure);
        int rawCount=0;
        for(CachedSection section:visible)for(Cell cell:section.cells)
            if(window.contains(cell.pos.getX(),cell.pos.getY(),cell.pos.getZ()))rawCount+=cell.boxes.size();
        if(rawCount>MAX_RAW_BOXES)return publish(false,scanned,rawCount,0,List.of(),false,"raw_box_budget");
        List<ColliderMerge.Box> selected=cache.get(owner,window,geometryRevision,feet.x,feet.y,feet.z,MAX_BOXES,()->{
            List<ColliderMerge.Box> raw=new ArrayList<>();
            for(CachedSection section:visible)for(Cell cell:section.cells)
                if(window.contains(cell.pos.getX(),cell.pos.getY(),cell.pos.getZ()))raw.addAll(cell.boxes);
            return raw;
        });
        return publish(true,scanned,rawCount,cache.mergedCount(),selected,cache.mergedCount()>MAX_BOXES,"ready");
    }
    private Snapshot publish(boolean complete,int scanned,int raw,int merged,List<ColliderMerge.Box> boxes,boolean capped,String status){
        if(complete!=latest.complete()||capped!=latest.capped()||!boxes.equals(latest.boxes())||!status.equals(latest.status()))revision++;
        latest=new Snapshot(revision,complete,scanned,raw,merged,boxes,capped,status);return latest;
    }
    public void invalidate(){clear();latest=new Snapshot(++revision,false,0,0,0,List.of(),false,"inactive");}
    private void clear(){owner=null;session=null;sections.clear();cache=new ColliderSnapshotCache();geometryRevision++;previousFeet=null;previousTick=0;}
    public void blockChanged(ServerLevel level,BlockPos pos,BlockState before,BlockState after){
        changed(level,pos,before,after);
    }
    void changed(Object world,BlockPos pos,BlockState before,BlockState after){
        if(owner!=world||before==null||before==after||(!isReal(before)&&!isReal(after)))return;
        // Shape connectivity can depend on a neighbor in another section (fences, walls, stairs).
        boolean removed=false;
        for(int x=(pos.getX()-1)>>4;x<=(pos.getX()+1)>>4;x++)
            for(int y=(pos.getY()-1)>>4;y<=(pos.getY()+1)>>4;y++)
                for(int z=(pos.getZ()-1)>>4;z<=(pos.getZ()+1)>>4;z++)removed|=sections.remove(new SectionKey(x,y,z))!=null;
        if(removed)geometryRevision++;
    }
    private static CachedSection scan(WorldView world,SectionView source,int sx,int sy,int sz){
        List<Cell> cells=new ArrayList<>();int raw=0,missingBefore=world.missingReads();
        if(!source.mayHaveRealBlocks())return new CachedSection(source,cells,null,false);
        try {
            for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                BlockState state=source.state(x,y,z);if(!isReal(state))continue;
                BlockPos pos=new BlockPos((sx<<4)+x,(sy<<4)+y,(sz<<4)+z);
                List<ColliderMerge.Box> boxes=extractBoxes(world,pos,state);raw+=boxes.size();
                if(raw>MAX_SECTION_BOXES)return new CachedSection(source,List.of(),"section_box_budget",false);
                boolean dynamic=state.getBlock().hasDynamicShape();
                if(dynamic||!boxes.isEmpty())cells.add(new Cell(pos,dynamic,boxes));
            }
        }catch(RuntimeException incompatible){return new CachedSection(source,List.of(),"unsupported_block_shape",false);}
        boolean missing=world.missingReads()!=missingBefore;
        return new CachedSection(source,cells,missing?"unloaded_shape_neighbor":null,missing);
    }
    public static boolean isReal(BlockState state){
        // A waterlogged slab still has a solid shape. Exclude fluid blocks, not every fluid-containing block.
        return !state.isAir()&&!(state.getBlock() instanceof TerrainBlock)&&!(state.getBlock() instanceof LiquidBlock);
    }
    public static List<ColliderMerge.Box> extractBoxes(BlockGetter world,BlockPos pos,BlockState state){
        if(!isReal(state))return List.of();
        List<ColliderMerge.Box> result=new ArrayList<>();
        state.getCollisionShape(world,pos,CollisionContext.empty()).forAllBoxes((x0,y0,z0,x1,y1,z1)->{
            if(result.size()>=MAX_SHAPE_BOXES||x0< -16||y0< -16||z0< -16||x1>17||y1>17||z1>17)
                throw new IllegalArgumentException("Unsupported collision shape budget");
            result.add(new ColliderMerge.Box(pos.getX()+x0,pos.getY()+y0,pos.getZ()+z0,pos.getX()+x1,pos.getY()+y1,pos.getZ()+z1));
        });
        return List.copyOf(result);
    }
    private static final class LoadedWorld implements WorldView {
        private static final Object OUTSIDE=new Object();
        private final ServerLevel level;private int missing;
        LoadedWorld(ServerLevel level){this.level=level;}
        @Override public Object identity(){return level;}
        @Override public int missingReads(){return missing;}
        @Override public SectionView section(int x,int y,int z){
            if(y<level.getMinSection()||y>=level.getMaxSection())return new SectionSource(OUTSIDE,OUTSIDE,null,true);
            LevelChunk chunk=level.getChunkSource().getChunkNow(x,z);
            if(chunk==null)return new SectionSource(null,null,null,false);
            LevelChunkSection section=chunk.getSection(chunk.getSectionIndexFromSectionY(y));
            return new SectionSource(chunk,section,section,true);
        }
        private LevelChunk chunk(BlockPos pos){return level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4);}
        @Override public BlockState getBlockState(BlockPos pos){
            if(level.isOutsideBuildHeight(pos))return Blocks.AIR.defaultBlockState();
            LevelChunk chunk=chunk(pos);if(chunk==null){missing++;return Blocks.AIR.defaultBlockState();}return chunk.getBlockState(pos);
        }
        @Override public FluidState getFluidState(BlockPos pos){return getBlockState(pos).getFluidState();}
        @Override public BlockEntity getBlockEntity(BlockPos pos){
            if(level.isOutsideBuildHeight(pos))return null;
            LevelChunk chunk=chunk(pos);if(chunk==null){missing++;return null;}return chunk.getBlockEntity(pos,LevelChunk.EntityCreationType.CHECK);
        }
        @Override public int getHeight(){return level.getHeight();}
        @Override public int getMinBuildHeight(){return level.getMinBuildHeight();}
    }
    private record SectionSource(Object chunkIdentity,Object sectionIdentity,LevelChunkSection content,boolean loaded) implements SectionView {
        @Override public boolean mayHaveRealBlocks(){return content!=null&&!content.hasOnlyAir()&&content.maybeHas(PlacedBlockCollisionSnapshots::isReal);}
        @Override public BlockState state(int x,int y,int z){return content==null?Blocks.AIR.defaultBlockState():content.getBlockState(x,y,z);}
    }
}
