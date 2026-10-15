package dev.ermc.bridge.world;

import dev.ermc.bridge.TerrainBlock;
import dev.ermc.bridge.TerrainShapeBlock;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Uses real vanilla block survival/leaf rules with memory-backed blocks, no game/server. */
public final class SurfaceResourceBlocksCheck {
    private static int checks;
    private static TerrainBlock terrain;
    private static TerrainShapeBlock detail;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static final class World implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        int reads;
        @Override public BlockState getBlockState(BlockPos pos) {
            reads++;
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
        LevelReader reader() {
            return (LevelReader) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {LevelReader.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getBlockState" -> getBlockState((BlockPos) args[0]);
                        case "getFluidState" -> getFluidState((BlockPos) args[0]);
                        case "getBlockEntity" -> null;
                        case "getMinBuildHeight" -> -64;
                        case "getHeight" -> 384;
                        case "getMaxLocalRawBrightness", "getBrightness" -> 15;
                        case "isClientSide" -> false;
                        case "hasChunk", "hasChunkAt", "hasChunksAt" -> true;
                        case "getChunk" -> throw new AssertionError("test requested a chunk");
                        default -> throw new AssertionError("unexpected world call: " + method);
                    });
        }
    }

    private static SurfaceResourcePlan.Candidate candidate(SurfaceResourcePlan.Kind kind, long seed) {
        return new SurfaceResourcePlan.Candidate(123L, 15, -17, kind, seed);
    }

    private static World ground(SurfaceResourcePlan.Candidate candidate) {
        World world = new World();
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++)
            world.blocks.put(new BlockPos(candidate.x() + x, 64, candidate.z() + z), terrain.defaultBlockState());
        return world;
    }

    private static void apply(World world, List<SurfaceResourceBlocks.Change> changes) {
        for (var change : changes) world.blocks.put(change.pos(), change.state());
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var frozen = net.minecraft.core.MappedRegistry.class.getDeclaredField("frozen");
        frozen.setAccessible(true);
        frozen.set(BuiltInRegistries.BLOCK, false);
        var holders = net.minecraft.core.MappedRegistry.class.getDeclaredField("unregisteredIntrusiveHolders");
        holders.setAccessible(true);
        holders.set(BuiltInRegistries.BLOCK, new IdentityHashMap<>());
        terrain = Registry.register(BuiltInRegistries.BLOCK, ResourceLocation.fromNamespaceAndPath("surface_fixture", "terrain"),
                new TerrainBlock(BlockBehaviour.Properties.of().noLootTable()));
        detail = Registry.register(BuiltInRegistries.BLOCK, ResourceLocation.fromNamespaceAndPath("surface_fixture", "detail"),
                new TerrainShapeBlock(BlockBehaviour.Properties.of().noLootTable().dynamicShape()));
        BuiltInRegistries.BLOCK.freeze();
        // Bootstrap initializes real blocks but not a server's data pack loader. Bind the exact
        // vanilla soil/log entries exercised below; do not add bridge terrain to these tags.
        Map<TagKey<Block>, List<Holder<Block>>> tags = new HashMap<>();
        tags.put(BlockTags.DIRT, List.of(Blocks.DIRT.builtInRegistryHolder(), Blocks.GRASS_BLOCK.builtInRegistryHolder()));
        tags.put(BlockTags.LOGS, List.of(Blocks.OAK_LOG.builtInRegistryHolder(), Blocks.BIRCH_LOG.builtInRegistryHolder()));
        BuiltInRegistries.BLOCK.bindTags(tags);

        var oak = candidate(SurfaceResourcePlan.Kind.OAK, 0);
        World world = ground(oak);
        Map<BlockPos, BlockState> before = Map.copyOf(world.blocks);
        var tree = SurfaceResourceBlocks.prepare(oak, 64, world, pos -> true);
        check(tree != null && tree.size() > 20, "actual oak states planned on supported native terrain");
        check(before.equals(world.blocks), "prepare never writes to its input world");
        check(SurfaceResourceBlocks.prepare(oak, 64, world, pos -> true).equals(tree), "same actual block plan is reproducible");
        try { tree.clear(); throw new AssertionError("mutable result"); }
        catch (UnsupportedOperationException expected) { checks++; }

        World missing = ground(oak);
        check(SurfaceResourceBlocks.prepare(oak, 64, missing, pos -> pos.getX() != oak.x() + 3) == null,
                "unloaded neighbor halo rejects a whole tree");
        check(missing.reads == 0, "loaded-only pass rejects before reading any block or requesting a chunk");
        check(SurfaceResourceBlocks.prepare(oak, 314, ground(oak), pos -> true) == null,
                "build limit leaves no room for resource plus neighbor halo");
        check(SurfaceResourceBlocks.prepare(new SurfaceResourcePlan.Candidate(1, Integer.MAX_VALUE, 0,
                SurfaceResourcePlan.Kind.OAK, 0), 64, world, pos -> true) == null, "coordinate overflow rejected");

        for (BlockState obstruction : List.of(Blocks.OAK_PLANKS.defaultBlockState(), Blocks.WATER.defaultBlockState(),
                Blocks.DANDELION.defaultBlockState(), terrain.defaultBlockState(), detail.defaultBlockState())) {
            World blocked = ground(oak);
            // A canopy corner is empty in the template but must still be kept clear.
            blocked.blocks.put(new BlockPos(oak.x() + 2, 67, oak.z() + 2), obstruction);
            check(SurfaceResourceBlocks.prepare(oak, 64, blocked, pos -> true) == null,
                    "whole resource box protects existing " + obstruction);
        }
        World roof = ground(oak);
        roof.blocks.put(new BlockPos(oak.x(), 64 + SurfaceResourcePlan.maxHeight(oak.kind()) + 1, oak.z()),
                Blocks.STONE.defaultBlockState());
        check(SurfaceResourceBlocks.prepare(oak, 64, roof, pos -> true) == null, "extra top clearance rejects a ceiling");
        BlockPos root = new BlockPos(oak.x(), 64, oak.z());
        for (BlockState protectedRoot : List.of(Blocks.DIRT.defaultBlockState(), Blocks.GRASS_BLOCK.defaultBlockState(),
                Blocks.OAK_PLANKS.defaultBlockState(), Blocks.AIR.defaultBlockState())) {
            World occupied = ground(oak);
            occupied.blocks.put(root, protectedRoot);
            check(SurfaceResourceBlocks.prepare(oak, 64, occupied, pos -> true) == null,
                    "resource never replaces player/root block " + protectedRoot);
        }
        World low = ground(oak);
        low.blocks.put(root, terrain.defaultBlockState().setValue(TerrainBlock.HEIGHT, 10));
        check(SurfaceResourceBlocks.prepare(oak, 64, low, pos -> true) == null, "0.625-high native floor cannot support a raised soil foundation");
        low.blocks.put(root, terrain.defaultBlockState().setValue(TerrainBlock.HEIGHT, 11));
        check(SurfaceResourceBlocks.prepare(oak, 64, low, pos -> true) != null, "0.6875-high floor supports a small embedded foundation");
        low.blocks.put(root, detail.defaultBlockState().setValue(TerrainBlock.HEIGHT, 6));
        check(SurfaceResourceBlocks.prepare(oak, 64, low, pos -> true) == null, "actual detail collision fallback is checked, not block identity alone");

        var decaying = LeavesBlock.class.getDeclaredMethod("decaying", BlockState.class);
        decaying.setAccessible(true);
        for (var kind : List.of(SurfaceResourcePlan.Kind.OAK, SurfaceResourcePlan.Kind.BIRCH)) {
            for (long seed = 0; seed <= 1; seed++) {
                var c = candidate(kind, seed);
                World forest = ground(c);
                var changes = SurfaceResourceBlocks.prepare(c, 64, forest, pos -> true);
                check(changes != null, "both trunk heights and tree species fit");
                apply(forest, changes);
                for (var change : changes) {
                    BlockState state = change.state();
                    if (!(state.getBlock() instanceof LeavesBlock)) continue;
                    int nearest = 7;
                    for (Direction direction : Direction.values()) {
                        BlockState neighbor = forest.getBlockState(change.pos().relative(direction));
                        nearest = Math.min(nearest, LeavesBlock.getOptionalDistanceAt(neighbor).orElse(7) + 1);
                    }
                    check(state.getValue(LeavesBlock.DISTANCE) == nearest && nearest <= 6,
                            "leaf has exact vanilla neighboring-log/leaf distance");
                    check(!state.getValue(LeavesBlock.PERSISTENT) && !((boolean) decaying.invoke(state.getBlock(), state)),
                            "generated supported leaf is stable but not permanent");
                    BlockState detached = state.setValue(LeavesBlock.DISTANCE, 7);
                    check((boolean) decaying.invoke(detached.getBlock(), detached), "detached generated leaf remains eligible for vanilla decay");
                }
                forest.blocks.put(root.above(), Blocks.AIR.defaultBlockState());
                BlockState sapling = (kind == SurfaceResourcePlan.Kind.OAK ? Blocks.OAK_SAPLING : Blocks.BIRCH_SAPLING).defaultBlockState();
                check(sapling.canSurvive(forest.reader(), root.above()), "harvested tree leaves normal soil suitable for a replanted sapling");
                check(((SaplingBlock) sapling.getBlock()).isValidBonemealTarget(forest.reader(), root.above(), sapling),
                        "replanted vanilla sapling accepts bonemeal targeting");
            }
        }
        for (var kind : List.of(SurfaceResourcePlan.Kind.FLOWERS, SurfaceResourcePlan.Kind.GRASS, SurfaceResourcePlan.Kind.BERRIES)) {
            var c = candidate(kind, 3);
            World plants = ground(c);
            var changes = SurfaceResourceBlocks.prepare(c, 64, plants, pos -> true);
            check(changes != null, "plant patch fits on terrain");
            apply(plants, changes);
            for (var change : changes) if (change.pos().getY() == 65) {
                check(change.state().canSurvive(plants.reader(), change.pos()), "actual vanilla plant survives generated ordinary soil");
                plants.blocks.remove(change.pos());
                check(change.state().canSurvive(plants.reader(), change.pos()), "harvested plant can be replanted on retained soil");
                plants.blocks.put(change.pos(), change.state());
            }
        }
        check(SurfaceResourceBlocks.state(SurfaceResourcePlan.Material.SWEET_BERRY_BUSH).getValue(SweetBerryBushBlock.AGE) == 3,
                "berry bush starts at vanilla harvestable age");
        check(!Blocks.DANDELION.defaultBlockState().canSurvive(ground(oak).reader(), root.above()),
                "bridge terrain has not been globally declared dirt");
        var rock = candidate(SurfaceResourcePlan.Kind.ROCK, 0);
        check(SurfaceResourceBlocks.prepare(rock, 64, ground(rock), pos -> true).stream()
                .anyMatch(change -> change.state().is(Blocks.COAL_ORE)), "rock cluster contains a real mineable vanilla coal ore state");
        apply(world, tree);
        check(SurfaceResourceBlocks.prepare(oak, 64, world, pos -> true) == null, "existing generated resource is never overwritten by a repeat plan");
        System.out.println("PASS " + checks + ": real Minecraft resource states, loaded-only/full-box guards, native support height, exact leaf distances, vanilla plant survival and sapling replant/bonemeal targeting");
        System.out.println("LIMIT: memory-backed blocks with explicit vanilla soil/log tags; no ServerLevel mutation, actual growth, loot roll, native scene or gameplay was executed");
    }
}
