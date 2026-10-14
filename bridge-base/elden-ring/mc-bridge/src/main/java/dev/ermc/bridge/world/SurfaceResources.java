package dev.ermc.bridge.world;

import dev.ermc.bridge.CoordMap;
import dev.ermc.bridge.LocalHostOwner;
import dev.ermc.bridge.TerrainManager;
import dev.ermc.bridge.link.ErLink;
import dev.ermc.bridge.link.GameState;
import dev.ermc.bridge.link.Protocol;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.slf4j.LoggerFactory;

/** Small, loaded-only surface decoration pass. It neither generates chunks nor edits native assets. */
public final class SurfaceResources {
    private SurfaceResources() {}
    private static final SurfaceResourceSites SITES = new SurfaceResourceSites();
    private static final GameState STATE = new GameState();
    private static CoordMap.Mapping observedMap;
    private static long hostStart;
    private static int hostLife;
    private static long lastTick = Long.MIN_VALUE;
    private static int cursor;
    private static int refreshCursor;
    private static long lastRefresh;
    private static String status = "waiting for native surface samples";

    public static void initialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
            Commands.literal("erbridge").then(Commands.literal("resources").requires(s -> s.hasPermission(2))
                .executes(c -> command(c.getSource(), "status"))
                .then(Commands.literal("status").executes(c -> command(c.getSource(), "status")))
                .then(Commands.literal("on").executes(c -> command(c.getSource(), "on")))
                .then(Commands.literal("off").executes(c -> command(c.getSource(), "off"))))));
    }

    public static void reset() {
        TerrainManager.clearResourceSurfaceRequests();
        SITES.clear(); observedMap = null; hostStart = 0; hostLife = 0;
        lastTick = Long.MIN_VALUE; cursor = 0; refreshCursor = 0; lastRefresh = 0;
        status = "waiting for native surface samples";
    }

    public static void observe(CoordMap.Mapping map, int x, int z, double y, boolean eligible, long now) {
        if (!map.equals(observedMap)) {
            SITES.clear(); TerrainManager.clearResourceSurfaceRequests(); observedMap = map;
        }
        SITES.observe(x, z, y, eligible, now);
    }

    public static void forget(int x, int z) { SITES.forget(x, z); }

    public static void tick(MinecraftServer server) {
        if (!TerrainManager.isBridgeWorld()) return;
        var owner = LocalHostOwner.find(server);
        var map = CoordMap.get();
        ErLink link = ErLink.get();
        if (owner == null || owner.level() != server.overworld() || !owner.isAlive()
                || map == null || map.provisional() || !link.poll() || !link.snapshot(STATE)
                || !STATE.has(Protocol.STATE_PLAYER_VALID) || !STATE.has(Protocol.STATE_CAM_OVERRIDDEN)
                || STATE.has(Protocol.STATE_HOST_BUSY | Protocol.STATE_PLAYER_DEAD)
                || STATE.stageId != map.zone() || !TerrainManager.recallSettled(1500)
                || !TerrainManager.defaultRaySettings()) {
            // Returning from loading or F8 must obtain fresh evidence before placing anything.
            SITES.clear(); TerrainManager.clearResourceSurfaceRequests();
            status = "waiting for active local host"; return;
        }
        if (hostStart != link.hostStartMs() || hostLife != link.hostLife() || !map.equals(observedMap)) {
            SITES.clear(); TerrainManager.clearResourceSurfaceRequests();
            observedMap = map; hostStart = link.hostStartMs(); hostLife = link.hostLife();
        }
        ServerLevel level = server.overworld();
        long time = level.getGameTime();
        if (lastTick != Long.MIN_VALUE && time - lastTick < 10) return;
        lastTick = time;
        SurfaceResourceData data = SurfaceResourceData.get(level);
        if (!data.enabled()) { status = data.compatible() ? "disabled" : "unsupported save version"; return; }

        long seed = level.getSeed() ^ Double.doubleToLongBits(map.ax())
            ^ Long.rotateLeft(Double.doubleToLongBits(map.ay()), 17)
            ^ Long.rotateLeft(Double.doubleToLongBits(map.az()), 37)
            ^ Double.doubleToLongBits(map.unitsPerMeter()) ^ (map.flipZ() ? 0x51f0a28cL : 0);
        var candidates = new ArrayList<>(SurfaceResourcePlan.candidates(seed, map.zone(), map.region(),
            owner.blockPosition().getX(), owner.blockPosition().getZ(), 21));
        candidates.sort(Comparator.comparingDouble(c -> distanceSq(c, owner.getX(), owner.getZ())));
        long now = System.currentTimeMillis();
        if (now - lastRefresh >= 1000) {
            // Independent cursor: a 16-candidate tick budget and a 1-second refresh cadence
            // must not repeatedly select the same subset of an otherwise stationary scene.
            for (int scanned = 0; scanned < candidates.size(); scanned++) {
                var candidate = candidates.get(Math.floorMod(refreshCursor++, candidates.size()));
                if (data.contains(candidate.id()) || distanceSq(candidate, owner.getX(), owner.getZ()) < 36
                        || SITES.baseY(candidate, now) != null) continue;
                TerrainManager.refreshResourceSurface(candidate.x(), candidate.z(), SurfaceResourcePlan.radius(candidate.kind()));
                lastRefresh = now;
                break;
            }
        }
        long deadline = System.nanoTime() + 2_000_000L;
        int checked = 0;
        status = "waiting for stable open ground / clearance";
        for (int scanned = 0; scanned < candidates.size(); scanned++) {
            if (checked >= 16 || System.nanoTime() > deadline) break;
            var candidate = candidates.get(Math.floorMod(cursor++, candidates.size()));
            if (data.contains(candidate.id()) || distanceSq(candidate, owner.getX(), owner.getZ()) < 36) continue;
            checked++;
            Integer base = SITES.baseY(candidate, now);
            if (base == null) continue;
            if (base < level.getMinBuildHeight() + 1
                    || base + SurfaceResourcePlan.maxHeight(candidate.kind()) + 2 >= level.getMaxBuildHeight()) continue;
            int radius = SurfaceResourcePlan.radius(candidate.kind());
            boolean moving = false;
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (TerrainManager.movingColumn(candidate.x() + dx, candidate.z() + dz)) moving = true;
            }
            if (moving) continue;
            AABB area = new AABB(candidate.x() - radius, base, candidate.z() - radius,
                candidate.x() + radius + 1, base + SurfaceResourcePlan.maxHeight(candidate.kind()) + 2,
                candidate.z() + radius + 1).inflate(1);
            if (!level.getEntities((net.minecraft.world.entity.Entity) null, area, entity -> !entity.isSpectator()).isEmpty()) continue;
            var changes = SurfaceResourceBlocks.prepare(candidate, base, level,
                pos -> level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null);
            if (changes == null) continue;
            if (!place(level, changes)) {
                data.enabled(false); status = "placement failed; generation disabled; see log"; return;
            }
            data.generated(candidate.id());
            status = "generated " + candidate.kind() + " at " + candidate.x() + "," + base + "," + candidate.z();
            LoggerFactory.getLogger("erbridge").info("[surface-resources] {} ({} blocks, {} sites saved)",
                status, changes.size(), data.count());
            return; // One complete small feature at most per half-second; never split a tree across ticks.
        }
    }

    private static double distanceSq(SurfaceResourcePlan.Candidate c, double x, double z) {
        double dx = c.x() + .5 - x, dz = c.z() + .5 - z;
        return dx * dx + dz * dz;
    }

    private record Before(BlockPos pos, BlockState state, CompoundTag blockEntity) {}

    private static boolean place(ServerLevel level, List<SurfaceResourceBlocks.Change> changes) {
        List<Before> before = new ArrayList<>();
        try {
            for (var change : changes) {
                var entity = level.getBlockEntity(change.pos());
                before.add(new Before(change.pos(), level.getBlockState(change.pos()),
                    entity == null ? null : entity.saveWithFullMetadata(level.registryAccess())));
                if (!level.setBlock(change.pos(), change.state(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) {
                    throw new IllegalStateException("setBlock rejected " + change.pos());
                }
            }
        } catch (RuntimeException failure) {
            // All writes are on this server thread. Restore detailed collision NBT as well as block states.
            for (int i = before.size() - 1; i >= 0; i--) {
                Before old = before.get(i);
                level.setBlock(old.pos(), old.state(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                var entity = level.getBlockEntity(old.pos());
                if (entity != null && old.blockEntity() != null) {
                    entity.loadWithComponents(old.blockEntity(), level.registryAccess()); entity.setChanged();
                    level.sendBlockUpdated(old.pos(), old.state(), old.state(), Block.UPDATE_CLIENTS);
                }
            }
            LoggerFactory.getLogger("erbridge").error("[surface-resources] Placement rolled back", failure);
            return false;
        }
        // Dirt/log support is already in place before normal plant and leaf neighbour handling.
        for (var change : changes) level.updateNeighborsAt(change.pos(), change.state().getBlock());
        return true;
    }

    private static int command(CommandSourceStack source, String action) {
        if (!TerrainManager.isBridgeWorld() || source.getLevel() != source.getServer().overworld()
                || !LocalHostOwner.isOwner(source.getPlayer())) {
            source.sendFailure(Component.literal("地表资源命令只用于 ER Bridge 本机宿主。")); return 0;
        }
        var data = SurfaceResourceData.get(source.getLevel());
        if (!data.compatible()) { source.sendFailure(Component.literal("资源记录版本不兼容，已停止生成。")); return 0; }
        if (action.equals("on")) data.enabled(true);
        if (action.equals("off")) { data.enabled(false); TerrainManager.clearResourceSurfaceRequests(); }
        source.sendSuccess(() -> Component.literal("地表资源 " + (data.enabled() ? "开启" : "关闭")
            + "；已生成 " + data.count() + " 处；地表采样 " + SITES.size() + "；" + status
            + "。关闭仅停止新增，已采集位置不会补回。"), false);
        return 1;
    }
}
