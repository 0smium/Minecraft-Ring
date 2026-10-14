package dev.ermc.bridge.world;

import dev.ermc.bridge.TerrainBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/** Loaded-only planning. Applying changes and saving generation ownership belongs to the server. */
public final class SurfaceResourceBlocks {
    public record Change(BlockPos pos, BlockState state) {}

    private SurfaceResourceBlocks() {}

    /**
     * Returns null when the complete resource cannot safely fit. No blocks are written and no
     * chunk is requested. The extra loaded halo covers neighbor updates when the plan is applied.
     * Only proven native terrain at root cells may be replaced, never existing Minecraft soil.
     */
    public static List<Change> prepare(SurfaceResourcePlan.Candidate candidate, int baseY,
                                       BlockGetter level, Predicate<BlockPos> loaded) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(loaded, "loaded");
        int radius = SurfaceResourcePlan.radius(candidate.kind());
        int height = SurfaceResourcePlan.maxHeight(candidate.kind());
        int halo = radius + 1;
        if ((long) baseY - 1 < level.getMinBuildHeight()
                || (long) baseY + height + 2 >= (long) level.getMinBuildHeight() + level.getHeight()
                || (long) candidate.x() - halo < Integer.MIN_VALUE
                || (long) candidate.x() + halo > Integer.MAX_VALUE
                || (long) candidate.z() - halo < Integer.MIN_VALUE
                || (long) candidate.z() + halo > Integer.MAX_VALUE) return null;

        // Complete this pass before querying even one block or collision shape.
        for (int dx = -halo; dx <= halo; dx++) {
            for (int dz = -halo; dz <= halo; dz++) {
                for (int dy = -1; dy <= height + 2; dy++) {
                    if (!loaded.test(new BlockPos(candidate.x() + dx, baseY + dy, candidate.z() + dz))) return null;
                }
            }
        }
        // Check the whole box, including gaps between leaves and the space above the resource.
        // Existing vegetation and fluids are also owned blocks, not replaceable empty space.
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = 1; dy <= height + 1; dy++) {
                    BlockPos pos = new BlockPos(candidate.x() + dx, baseY + dy, candidate.z() + dz);
                    if (!level.getBlockState(pos).isAir()) return null;
                }
            }
        }

        Map<BlockPos, BlockState> planned = new LinkedHashMap<>();
        for (var placement : SurfaceResourcePlan.layout(candidate, baseY)) {
            if (Math.abs(placement.dx()) > radius || Math.abs(placement.dz()) > radius
                    || placement.dy() < 0 || placement.dy() > height) return null;
            BlockPos pos = new BlockPos(candidate.x() + placement.dx(), baseY + placement.dy(),
                    candidate.z() + placement.dz());
            if (placement.dy() == 0) {
                BlockState current = level.getBlockState(pos);
                if (!(current.getBlock() instanceof TerrainBlock)) return null;
                var shape = current.getCollisionShape(level, pos, CollisionContext.empty());
                if (shape.isEmpty()) return null;
                double top = shape.max(Direction.Axis.Y);
                if (top < .65 || top > 1.000001) return null;
            }
            if (planned.put(pos, state(placement.material())) != null) return null;
        }

        // Vanilla leaves must remain harvestable and decay after all supporting logs are gone.
        // Seed the distance field from actual generated logs, rather than pinning leaves forever.
        Map<BlockPos, Integer> distances = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        for (var entry : planned.entrySet()) {
            if (entry.getValue().is(Blocks.OAK_LOG) || entry.getValue().is(Blocks.BIRCH_LOG)) {
                distances.put(entry.getKey(), 0);
                queue.add(entry.getKey());
            }
        }
        while (!queue.isEmpty()) {
            BlockPos pos = queue.remove();
            int distance = distances.get(pos) + 1;
            if (distance > 6) continue;
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = pos.relative(direction);
                BlockState next = planned.get(neighbor);
                if (next == null || !(next.getBlock() instanceof LeavesBlock)
                        || distance >= distances.getOrDefault(neighbor, 7)) continue;
                distances.put(neighbor, distance);
                queue.add(neighbor);
            }
        }
        List<Change> result = new ArrayList<>(planned.size());
        for (var entry : planned.entrySet()) {
            BlockState block = entry.getValue();
            if (block.getBlock() instanceof LeavesBlock) {
                int distance = distances.getOrDefault(entry.getKey(), 7);
                if (distance > 6) return null;
                block = block.setValue(LeavesBlock.DISTANCE, distance).setValue(LeavesBlock.PERSISTENT, false);
            }
            result.add(new Change(entry.getKey(), block));
        }
        return List.copyOf(result);
    }

    public static BlockState state(SurfaceResourcePlan.Material material) {
        return switch (material) {
            case GRASS_BLOCK -> Blocks.GRASS_BLOCK.defaultBlockState();
            case DIRT -> Blocks.DIRT.defaultBlockState();
            case OAK_LOG -> Blocks.OAK_LOG.defaultBlockState();
            case OAK_LEAVES -> Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, false);
            case BIRCH_LOG -> Blocks.BIRCH_LOG.defaultBlockState();
            case BIRCH_LEAVES -> Blocks.BIRCH_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, false);
            case DANDELION -> Blocks.DANDELION.defaultBlockState();
            case POPPY -> Blocks.POPPY.defaultBlockState();
            case SHORT_GRASS -> Blocks.SHORT_GRASS.defaultBlockState();
            case SWEET_BERRY_BUSH -> Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3);
            case STONE -> Blocks.STONE.defaultBlockState();
            case COAL_ORE -> Blocks.COAL_ORE.defaultBlockState();
        };
    }
}
