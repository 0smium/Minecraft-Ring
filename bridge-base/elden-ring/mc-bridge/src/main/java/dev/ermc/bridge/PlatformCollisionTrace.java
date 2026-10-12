package dev.ermc.bridge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Read-only explanation of vanilla's first newly encountered collision. */
public final class PlatformCollisionTrace {
    private static final Logger LOG = LoggerFactory.getLogger("erbridge");
    // Match the float-derived tolerance in the actual 1.21.1 listener bytecode.
    private static final double SHRINK = 9.999999747378752E-6;
    private static final int MAX_BLOCKS = 6, MAX_HITS = 2;

    private PlatformCollisionTrace() {}

    public static void rejected(ServerPlayer player, LevelReader level, AABB oldBox, Vec3 start, Vec3 lastGood,
            Vec3 request, boolean packetGround) {
        Vec3 current = player.position();
        AABB destination = player.getBoundingBox().move(request.subtract(current)).deflate(SHRINK);
        VoxelShape oldShape = Shapes.create(oldBox.deflate(SHRINK));
        VoxelShape rejected = null;
        // Copy vanilla's criterion, including candidates which may have no hit box.
        for (VoxelShape collider : level.getCollisions(player, destination)) {
            if (!Shapes.joinIsNotEmpty(collider, oldShape, BooleanOp.AND)) {
                rejected = collider;
                break;
            }
        }
        String source = "unknown/not-found-on-reread";
        List<AABB> hits = new ArrayList<>(MAX_HITS);
        if (rejected != null) {
            for (AABB box : rejected.toAabbs()) {
                if (box.intersects(destination)) {
                    hits.add(box);
                    if (hits.size() == MAX_HITS) break;
                }
            }
            source = describeSource(player, level, rejected, destination, hits);
        }
        LOG.info("[platform-trace] server new collision rejected tick {} epoch {}: old {} current {} lastGood {} request {} packetGround {}; oldBB {} requestBB {}; collider {} hits {} source {}",
            player.tickCount, TerrainManager.platformTraceEpoch(player), start, current, lastGood, request, packetGround, oldBox, destination,
            rejected == null || rejected.isEmpty() ? "unknown/empty" : rejected.bounds(), hits, source);
    }

    private static String describeSource(ServerPlayer player, LevelReader level, VoxelShape rejected,
            AABB destination, List<AABB> hits) {
        if (hits.isEmpty()) return "unknown/no-destination-overlap";
        // getCollisions concatenates entity collisions before block collisions.
        for (VoxelShape entity : level.getEntityCollisions(player, destination))
            if (sameShape(entity, rejected)) return "entity (terrain attribution unavailable)";

        LinkedHashSet<BlockPos> candidates = new LinkedHashSet<>();
        for (AABB hit : hits) candidates.add(BlockPos.containing(hit.intersect(destination).getCenter()));
        int feetY = Mth.floor(destination.minY);
        for (int y = feetY - 1; y <= feetY + 1 && candidates.size() < MAX_BLOCKS; y++)
            for (int x = Mth.floor(destination.minX); x <= Mth.floor(destination.maxX) && candidates.size() < MAX_BLOCKS; x++)
                for (int z = Mth.floor(destination.minZ); z <= Mth.floor(destination.maxZ) && candidates.size() < MAX_BLOCKS; z++)
                    candidates.add(new BlockPos(x, y, z));

        int checked = 0;
        for (BlockPos pos : candidates) {
            if (++checked > MAX_BLOCKS) break;
            if (!level.hasChunkAt(pos)) continue;
            var state = level.getBlockState(pos);
            VoxelShape block = state.getCollisionShape(level, pos, CollisionContext.of(player)).move(pos.getX(), pos.getY(), pos.getZ());
            // Bounding-box coincidence alone cannot identify a terrain source.
            if (!sameShape(block, rejected)) continue;
            List<String> cells = new ArrayList<>(MAX_HITS);
            for (AABB hit : hits) {
                AABB overlap = hit.intersect(destination);
                cells.add("x" + cellRange(overlap.minX - pos.getX(), overlap.maxX - pos.getX())
                    + "/y" + cellRange(overlap.minY - pos.getY(), overlap.maxY - pos.getY())
                    + "/z" + cellRange(overlap.minZ - pos.getZ(), overlap.maxZ - pos.getZ()));
            }
            return "block " + pos + " state " + state + " terrain " + ErBridgeMod.isTerrain(state)
                + " overlapCells(1/16) " + cells + " checkedBlocks " + checked;
        }
        return "unknown/block not matched within " + Math.min(checked, MAX_BLOCKS) + " nearby candidates";
    }

    private static boolean sameShape(VoxelShape first, VoxelShape second) {
        return !first.isEmpty() && !second.isEmpty() && !Shapes.joinIsNotEmpty(first, second, BooleanOp.NOT_SAME);
    }

    private static String cellRange(double min, double max) {
        if (min < 0 || max > 1) return "outside-block";
        return Mth.clamp(Mth.floor(min * 16), 0, 15) + ".."
            + Mth.clamp((int)Math.ceil(max * 16) - 1, 0, 15);
    }
}
