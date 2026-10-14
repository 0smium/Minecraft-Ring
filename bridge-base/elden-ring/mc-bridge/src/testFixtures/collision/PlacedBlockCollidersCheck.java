package dev.ermc.bridge.collision;

import dev.ermc.bridge.TerrainBlock;
import dev.ermc.bridge.TerrainShapeBlock;
import dev.ermc.bridge.CoordMap;
import dev.ermc.bridge.link.BlockColliderChannel;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.*;
import java.util.*;

/** Actual vanilla states/shapes; a loaded-only in-memory world drives the production cache. */
public final class PlacedBlockCollidersCheck {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static ColliderMerge.Box box(double x,double y,double z,double X,double Y,double Z){return new ColliderMerge.Box(x,y,z,X,Y,Z);}
    private static double volume(List<ColliderMerge.Box> boxes){return boxes.stream().mapToDouble(b->(b.maxX()-b.minX())*(b.maxY()-b.minY())*(b.maxZ()-b.minZ())).sum();}
    private static boolean covers(List<ColliderMerge.Box> boxes,double x,double y,double z){return boxes.stream().anyMatch(b->x>b.minX()&&x<b.maxX()&&y>b.minY()&&y<b.maxY()&&z>b.minZ()&&z<b.maxZ());}
    private record ChunkKey(int x,int z){}
    private record SectionKey(int x,int y,int z){}
    private static final class MemoryWorld implements PlacedBlockCollisionSnapshots.WorldView {
        final Map<BlockPos,BlockState> states=new HashMap<>();
        final Map<ChunkKey,Object> chunks=new HashMap<>();final Map<SectionKey,Object> identities=new HashMap<>();
        final Set<ChunkKey> unloaded=new HashSet<>();int stateReads,shapeReads;
        void put(int x,int y,int z,BlockState state){states.put(new BlockPos(x,y,z),state);}
        @Override public Object identity(){return this;}
        @Override public PlacedBlockCollisionSnapshots.SectionView section(int x,int y,int z){
            ChunkKey ck=new ChunkKey(x,z);SectionKey sk=new SectionKey(x,y,z);
            Object chunk=chunks.computeIfAbsent(ck,k->new Object()),section=identities.computeIfAbsent(sk,k->new Object());
            return new PlacedBlockCollisionSnapshots.SectionView(){
                public Object chunkIdentity(){return chunk;}public Object sectionIdentity(){return section;}
                public boolean loaded(){return !unloaded.contains(ck);}
                public boolean mayHaveRealBlocks(){return states.entrySet().stream().anyMatch(e->
                    e.getKey().getX()>>4==x&&e.getKey().getY()>>4==y&&e.getKey().getZ()>>4==z&&PlacedBlockCollisionSnapshots.isReal(e.getValue()));}
                public BlockState state(int a,int b,int c){stateReads++;return states.getOrDefault(new BlockPos((x<<4)+a,(y<<4)+b,(z<<4)+c),Blocks.AIR.defaultBlockState());}
            };
        }
        @Override public BlockState getBlockState(BlockPos pos){shapeReads++;return states.getOrDefault(pos,Blocks.AIR.defaultBlockState());}
        @Override public FluidState getFluidState(BlockPos pos){return getBlockState(pos).getFluidState();}
        @Override public BlockEntity getBlockEntity(BlockPos pos){return null;}
        @Override public int getHeight(){return 384;}
        @Override public int getMinBuildHeight(){return -64;}
    }
    private static final class ChangingBlock extends Block {
        double height=1;
        ChangingBlock(){super(BlockBehaviour.Properties.of().noLootTable().dynamicShape());}
        @Override protected VoxelShape getCollisionShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return Shapes.box(0,0,0,1,height,1);}
    }
    private static final class NeighborBlock extends Block {
        NeighborBlock(){super(BlockBehaviour.Properties.of().noLootTable().dynamicShape());}
        @Override protected VoxelShape getCollisionShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return level.getBlockState(pos.east()).is(Blocks.STONE)?Shapes.block():Shapes.empty();}
    }
    private static final class ComplexBlock extends Block {
        final VoxelShape shape;
        ComplexBlock(){
            super(BlockBehaviour.Properties.of().noLootTable().dynamicShape());
            var pieces=new ArrayList<VoxelShape>();
            for(int i=0;i<65;i++)pieces.add(Shapes.box(i/128.0,0,0,(i+.5)/128,1,1));
            shape=Shapes.or(Shapes.empty(),pieces.toArray(VoxelShape[]::new));
        }
        @Override protected VoxelShape getCollisionShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return shape;}
    }
    public static void main(String[] args)throws Exception{
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        // Standalone bootstrap has already frozen registries. Reopen only this fixture JVM's block
        // registry to register explicit test blocks, as Fabric normally allows before its freeze.
        var frozen=net.minecraft.core.MappedRegistry.class.getDeclaredField("frozen");frozen.setAccessible(true);frozen.set(BuiltInRegistries.BLOCK,false);
        var holders=net.minecraft.core.MappedRegistry.class.getDeclaredField("unregisteredIntrusiveHolders");holders.setAccessible(true);holders.set(BuiltInRegistries.BLOCK,new IdentityHashMap<>());
        var proxy=register("terrain",new TerrainBlock(BlockBehaviour.Properties.of().noLootTable()));
        var detail=register("terrain_detail",new TerrainShapeBlock(BlockBehaviour.Properties.of().noLootTable().dynamicShape()));
        var dynamic=register("dynamic",new ChangingBlock());
        var dependent=register("neighbor",new NeighborBlock());
        var complex=register("complex",new ComplexBlock());
        BuiltInRegistries.BLOCK.freeze();
        MemoryWorld world=new MemoryWorld();BlockPos origin=new BlockPos(2,64,3);
        BlockState slab=Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE,SlabType.BOTTOM);
        var half=PlacedBlockCollisionSnapshots.extractBoxes(world,origin,slab);
        check(half.equals(List.of(box(2,64,3,3,64.5,4))),"actual bottom slab remains half-height in world coordinates");
        var top=PlacedBlockCollisionSnapshots.extractBoxes(world,origin,slab.setValue(BlockStateProperties.SLAB_TYPE,SlabType.TOP));
        check(top.equals(List.of(box(2,64.5,3,3,65,4))),"top slab leaves lower half open");
        check(PlacedBlockCollisionSnapshots.extractBoxes(world,origin,slab.setValue(BlockStateProperties.WATERLOGGED,true)).equals(half),"waterlogged solid retains its collision");
        BlockState stairs=Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HALF,Half.BOTTOM)
            .setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.EAST).setValue(BlockStateProperties.STAIRS_SHAPE,StairsShape.STRAIGHT);
        var steps=PlacedBlockCollisionSnapshots.extractBoxes(world,origin,stairs);
        check(Math.abs(volume(steps)-.75)<1e-9&&steps.size()>1,"actual stairs stay multiple boxes, not a full cube");
        VoxelShape rejoined=Shapes.empty();for(var b:steps)rejoined=Shapes.or(rejoined,Shapes.create(new AABB(b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ())));
        check(!Shapes.joinIsNotEmpty(rejoined,stairs.getCollisionShape(world,origin).move(2,64,3),BooleanOp.NOT_SAME),"reconstructed stairs preserve vanilla occupied and empty space");
        check(PlacedBlockCollisionSnapshots.extractBoxes(world,origin,Blocks.WATER.defaultBlockState()).isEmpty()
            &&PlacedBlockCollisionSnapshots.extractBoxes(world,origin,Blocks.LAVA.defaultBlockState()).isEmpty(),"fluids have no exported rigid body");
        check(PlacedBlockCollisionSnapshots.extractBoxes(world,origin,Blocks.AIR.defaultBlockState()).isEmpty()
            &&PlacedBlockCollisionSnapshots.extractBoxes(world,origin,Blocks.TORCH.defaultBlockState()).isEmpty(),"air and noncolliding decoration stay empty");
        check(!PlacedBlockCollisionSnapshots.isReal(proxy.defaultBlockState())&&!PlacedBlockCollisionSnapshots.isReal(detail.defaultBlockState()),"both terrain proxy kinds are excluded without registry identity guesses");
        var floor=new ArrayList<ColliderMerge.Box>();for(int x=0;x<10;x++)for(int z=0;z<10;z++)floor.add(box(x,0,z,x+1,.5,z+1));
        check(ColliderMerge.merge(floor).equals(List.of(box(0,0,0,10,.5,10))),"coplanar slab floor merges to one seam-free body");
        check(ColliderMerge.merge(List.of(box(0,0,0,1,.5,1),box(1,0,0,2,1,1))).size()==2,"different cross sections do not fill the stair notch");
        check(ColliderMerge.merge(List.of(box(0,0,0,1,1,1),box(1+1e-8,0,0,2,1,1))).size()==2,"even tiny real MC gaps are not bridged by epsilon");
        var permuted=new ArrayList<>(floor);Collections.shuffle(permuted,new Random(7));
        check(ColliderMerge.merge(permuted).equals(ColliderMerge.merge(floor)),"merge order is deterministic");
        ColliderWindow window=ColliderWindow.around(-.5,64,-.5,999,-999,999);
        check(window.maxX()-window.minX()+1<=48&&window.maxY()-window.minY()+1<=32&&window.maxZ()-window.minZ()+1<=48,"motion lookahead remains in bounded scan dimensions");
        check(Math.floorMod(window.minX(),4)==0&&Math.floorMod(window.maxX()+1,4)==0,"negative window coordinates snap correctly");
        check(window.contains(-1,64,-1),"bounded motion lead still contains current feet");
        var map=new CoordMap.Mapping(1000,2000,-3000,1,true,false,60,7);
        var mapped=NativeBlockCollisionBridge.toHost(map,List.of(box(map.originX()+1,102,4,map.originX()+3,103.5,6)));
        check(Arrays.equals(mapped,new float[]{1001,2002,-3005.5f,1003,2003.5f,-3003.5f}),"production toHost removes MC region offset and reorders mirrored Z min/max");
        var hostFeet=map.toHost(map.originX()+2,102,5);
        check(BlockColliderChannel.validBoxes(new float[]{(float)hostFeet[0],(float)hostFeet[1],(float)hostFeet[2]},mapped),"mirrored production boxes satisfy the actual native wire bounds");
        var mcLow=map.toMc(mapped[0],mapped[1],mapped[5]);var mcHigh=map.toMc(mapped[3],mapped[4],mapped[2]);
        check(mcLow.equals(new Vec3(map.originX()+1,102,4))&&mcHigh.equals(new Vec3(map.originX()+3,103.5,6)),"host roundtrip preserves original real-block corners");
        var scaleMap=new CoordMap.Mapping(10,20,30,2,false,false,10,3);
        check(Arrays.equals(NativeBlockCollisionBridge.toHost(scaleMap,List.of(box(scaleMap.originX(),100,.5,scaleMap.originX()+1,100.5,1.5))),
            new float[]{10,20,30,12,21,32}),"nonmirrored nonunit mapping scales all shape dimensions once");

        PlacedBlockCollisionSnapshots snapshots=new PlacedBlockCollisionSnapshots();
        world.put(0,64,0,slab);Vec3 feet=new Vec3(.5,65,.5);
        var first=snapshots.sample(world,"session-a",feet,1);
        check(first.complete()&&first.boxes().equals(List.of(box(0,64,0,1,64.5,1))),"production snapshot exports a real placed slab");
        int reads=world.stateReads;var same=snapshots.sample(world,new String("session-a"),feet,2);
        check(same.revision()==first.revision()&&same.scannedBlocks()==0&&world.stateReads==reads,"equal session/window with no changes reuses section and exact merge");
        world.put(1,64,0,slab);snapshots.changed(world,new BlockPos(1,64,0),Blocks.AIR.defaultBlockState(),slab);
        var placed=snapshots.sample(world,"session-a",feet,3);
        check(placed.complete()&&placed.revision()>same.revision()&&placed.boxes().equals(List.of(box(0,64,0,2,64.5,1))),"place invalidation rebuilds and merges the added slab");
        world.put(0,64,0,Blocks.AIR.defaultBlockState());snapshots.changed(world,new BlockPos(0,64,0),slab,Blocks.AIR.defaultBlockState());
        var removed=snapshots.sample(world,"session-a",feet,4);
        check(!covers(removed.boxes(),.5,64.25,.5)&&covers(removed.boxes(),1.5,64.25,.5),"break removes stale collision immediately");
        reads=world.stateReads;snapshots.changed(world,new BlockPos(1,64,0),proxy.defaultBlockState(),detail.defaultBlockState());
        snapshots.sample(world,"session-a",feet,5);check(world.stateReads==reads,"proxy regeneration does not churn real-block section cache");
        var session=snapshots.sample(world,"session-b",feet,6);
        check(session.complete()&&session.revision()>removed.revision()&&session.scannedBlocks()>0,"new life/scene session rebuilds even with identical local blocks");
        world.unloaded.add(new ChunkKey(0,0));var unloaded=snapshots.sample(world,"session-b",feet,7);
        check(!unloaded.complete()&&unloaded.boxes().isEmpty(),"unloaded chunk clears export instead of retaining stale boxes");
        world.unloaded.clear();check(snapshots.sample(world,"session-b",feet,8).complete(),"loaded chunk can rebuild without an explicit block-change callback");
        snapshots.invalidate();check(snapshots.sample(world,"session-b",feet,9).scannedBlocks()>0,"disconnect/disable invalidation discards section cache");

        MemoryWorld dynamicWorld=new MemoryWorld();dynamicWorld.put(0,64,0,dynamic.defaultBlockState());
        snapshots=new PlacedBlockCollisionSnapshots();var high=snapshots.sample(dynamicWorld,"dynamic",feet,1);dynamic.height=.25;
        var low=snapshots.sample(dynamicWorld,"dynamic",feet,2);
        check(high.complete()&&low.complete()&&low.revision()>high.revision()&&Math.abs(volume(low.boxes())-.25)<1e-9,"dynamic block-entity-dependent shape changes without replacing its BlockState");
        MemoryWorld neighborWorld=new MemoryWorld();neighborWorld.put(15,64,0,dependent.defaultBlockState());
        snapshots=new PlacedBlockCollisionSnapshots();Vec3 edge=new Vec3(15.5,65,.5);var empty=snapshots.sample(neighborWorld,"neighbor",edge,1);
        neighborWorld.put(16,64,0,Blocks.STONE.defaultBlockState());snapshots.changed(neighborWorld,new BlockPos(16,64,0),Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState());
        var linked=snapshots.sample(neighborWorld,"neighbor",edge,2);
        check(empty.complete()&&!covers(empty.boxes(),15.5,64.5,.5)&&covers(linked.boxes(),15.5,64.5,.5)
            &&linked.scannedBlocks()==8192,"neighbor edit across section boundary invalidates both dependent sections");

        MemoryWorld busy=new MemoryWorld();for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)for(int y=3;y<=4;y++)busy.put(x*16+1,y*16+1,z*16+1,Blocks.STONE.defaultBlockState());
        snapshots=new PlacedBlockCollisionSnapshots();snapshots.sample(busy,"budget",new Vec3(0,64,0),1);
        var budget=snapshots.sample(busy,"budget",new Vec3(.4,64,.4),2);
        check(!budget.complete()&&budget.boxes().isEmpty()&&budget.scannedBlocks()<=PlacedBlockCollisionSnapshots.MAX_SCANNED_BLOCKS,"scan budget never publishes a partial or previous snapshot");
        var resumed=snapshots.sample(busy,"budget",new Vec3(.8,64,.8),3);
        check(resumed.complete()&&resumed.scannedBlocks()<=PlacedBlockCollisionSnapshots.MAX_SCANNED_BLOCKS,"bounded section work resumes on the next server tick");
        MemoryWorld many=new MemoryWorld();for(int x=-10;x<=12;x+=2)for(int z=-10;z<=12;z+=2)for(int y=64;y<=70;y+=2)many.put(x,y,z,Blocks.STONE.defaultBlockState());
        snapshots=new PlacedBlockCollisionSnapshots();var capped=snapshots.sample(many,"cap",new Vec3(.5,65,.5),1);
        check(capped.complete()&&capped.capped()&&capped.boxes().size()==512&&capped.mergedBoxCount()==576,"full scan can select the nearest 512 without calling it complete geometry coverage");
        check(covers(capped.boxes(),.5,64.5,.5),"nearest budget retains collision under the player");
        MemoryWorld unsafe=new MemoryWorld();unsafe.put(0,64,0,complex.defaultBlockState());
        var rejected=new PlacedBlockCollisionSnapshots().sample(unsafe,"complex",feet,1);
        check(!rejected.complete()&&rejected.boxes().isEmpty(),"over-complex single shape fails closed instead of overflowing native budget");
        System.out.println("PASS "+checks+": actual Minecraft slab/stair/fluid/proxy shapes; exact merge; live-change/neighbor/dynamic/session cache; loaded-only and capped budgets");
        System.out.println("LIMIT: memory-backed BlockGetter/sections, not a live ServerLevel, game, native Havok body or block-placement UI");
    }
    private static <T extends Block> T register(String name,T block){return Registry.register(BuiltInRegistries.BLOCK,ResourceLocation.fromNamespaceAndPath("collider_fixture",name),block);}
}
