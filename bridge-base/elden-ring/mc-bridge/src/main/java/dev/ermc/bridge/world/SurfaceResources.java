package dev.ermc.bridge.world;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
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
                .then(Commands.literal("off").executes(c -> command(c.getSource(), "off")))
                .then(exclusionCommands()))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> exclusionCommands() {
        return Commands.literal("exclude")
            .executes(c -> listExclusions(c.getSource(), 1))
            .then(Commands.literal("here")
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, SurfaceResourceExclusion.MAX_RADIUS))
                    .executes(c -> addExclusion(c.getSource(), false, IntegerArgumentType.getInteger(c, "radius"), "局部禁区"))
                    .then(Commands.argument("name", StringArgumentType.greedyString())
                        .executes(c -> addExclusion(c.getSource(), false, IntegerArgumentType.getInteger(c, "radius"),
                            StringArgumentType.getString(c, "name"))))))
            .then(Commands.literal("zone")
                .executes(c -> addExclusion(c.getSource(), true, 0, "区域禁区"))
                .then(Commands.argument("name", StringArgumentType.greedyString())
                    .executes(c -> addExclusion(c.getSource(), true, 0, StringArgumentType.getString(c, "name")))))
            .then(Commands.literal("list").executes(c -> listExclusions(c.getSource(), 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1, SurfaceResourceExclusion.MAX_RULES / 8))
                    .executes(c -> listExclusions(c.getSource(), IntegerArgumentType.getInteger(c, "page")))))
            .then(Commands.literal("remove").then(Commands.argument("id", IntegerArgumentType.integer(1))
                .executes(c -> removeExclusion(c.getSource(), IntegerArgumentType.getInteger(c, "id")))));
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
        SurfaceResourceExclusion.Scope scope;
        try { scope = SurfaceResourceExclusion.Scope.from(map); }
        catch (IllegalArgumentException invalid) { status = "waiting for stable map identity"; return; }

        long seed = level.getSeed() ^ Double.doubleToLongBits(map.ax())
            ^ Long.rotateLeft(Double.doubleToLongBits(map.ay()), 17)
            ^ Long.rotateLeft(Double.doubleToLongBits(map.az()), 37)
            ^ Double.doubleToLongBits(map.unitsPerMeter()) ^ (map.flipZ() ? 0x51f0a28cL : 0);
        var candidates = new ArrayList<>(SurfaceResourcePlan.candidates(seed, map.zone(), map.region(),
            owner.blockPosition().getX(), owner.blockPosition().getZ(), 21));
        // Reject complete footprints before queueing native surface queries, without changing
        // candidate ids or claiming receipts. Removing a rule cannot respawn harvested sites.
        candidates.removeIf(candidate -> data.excluded(scope, candidate));
        if (candidates.isEmpty()) { status = "no eligible candidates (check region exclusions)"; return; }
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

    private static SurfaceResourceData commandData(CommandSourceStack source) {
        if (!TerrainManager.isBridgeWorld() || source.getLevel() != source.getServer().overworld()
                || !source.hasPermission(2) || !LocalHostOwner.isOwner(source.getPlayer())) {
            source.sendFailure(Component.literal("地表资源命令只用于 ER Bridge 本机宿主管理员。")); return null;
        }
        var data = SurfaceResourceData.get(source.getLevel());
        if (!data.compatible()) {
            source.sendFailure(Component.literal("资源记录损坏或版本不兼容，已停止生成并保留原记录。")); return null;
        }
        return data;
    }

    private static int command(CommandSourceStack source, String action) {
        var data = commandData(source);
        if (data == null) return 0;
        if (action.equals("on")) data.enabled(true);
        if (action.equals("off")) { data.enabled(false); TerrainManager.clearResourceSurfaceRequests(); }
        long here = 0;
        try { here = data.exclusionCount(SurfaceResourceExclusion.Scope.from(CoordMap.get())); }
        catch (IllegalArgumentException ignored) { /* A disconnected status query still reports the saved ledger. */ }
        long current = here;
        source.sendSuccess(() -> Component.literal("地表资源 " + (data.enabled() ? "开启" : "关闭")
            + "；已生成 " + data.count() + " 处；地表采样 " + SITES.size() + "；" + status
            + "；禁区 " + data.exclusions().size() + " 条（当前映射 " + current + "）"
            + "。关闭/禁区仅停止新增，已采集位置不会补回。"), false);
        return 1;
    }

    private static int addExclusion(CommandSourceStack source, boolean wholeZone, int radius, String name) {
        var data = commandData(source);
        if (data == null) return 0;
        var map = CoordMap.get();
        ErLink link = ErLink.get();
        GameState state = new GameState();
        if (map == null || map.provisional() || !link.poll() || !link.snapshot(state)
                || !state.has(Protocol.STATE_PLAYER_VALID) || !state.has(Protocol.STATE_CAM_OVERRIDDEN)
                || !source.getPlayer().isAlive() || state.stageId != map.zone()
                || state.has(Protocol.STATE_HOST_BUSY | Protocol.STATE_PLAYER_DEAD)
                || !TerrainManager.recallSettled(1500) || !map.contains(source.getPlayer().getX())) {
            source.sendFailure(Component.literal("请回到 MC 控制，等待当前法环区域加载/坐标同步完成，再记录禁区。")); return 0;
        }
        try {
            BlockPos pos = source.getPlayer().blockPosition();
            var rule = data.exclude(SurfaceResourceExclusion.Scope.from(map), wholeZone,
                wholeZone ? 0 : pos.getX(), wholeZone ? 0 : pos.getZ(), radius, name.trim());
            // Pending requests may predate the new restriction; recollect only eligible sites.
            TerrainManager.clearResourceSurfaceRequests();
            source.sendSuccess(() -> Component.literal("已添加禁区 #" + rule.id() + " " + rule.name() + "："
                + (wholeZone ? "当前法环区域及坐标映射" : "MC (" + rule.x() + ", " + rule.z() + ") 半径 " + radius + " 格、全部高度")
                + "。仅停止后续新增，不删除现有资源或采集记录。"), false);
            return 1;
        } catch (IllegalArgumentException invalid) {
            source.sendFailure(Component.literal(invalid.getMessage())); return 0;
        }
    }

    private static int listExclusions(CommandSourceStack source, int page) {
        var data = commandData(source);
        if (data == null) return 0;
        var rules = data.exclusions();
        int pages = Math.max(1, (rules.size() + 7) / 8);
        if (page < 1 || page > pages) {
            source.sendFailure(Component.literal("禁区列表共 " + pages + " 页。")); return 0;
        }
        SurfaceResourceExclusion.Scope current = null;
        try { current = SurfaceResourceExclusion.Scope.from(CoordMap.get()); }
        catch (IllegalArgumentException ignored) { /* All saved regions remain removable while disconnected. */ }
        source.sendSuccess(() -> Component.literal("资源禁区 " + rules.size() + "/128；第 " + page + "/" + pages
            + " 页。规则只影响后续新增；remove <编号> 不清除采集记录。"), false);
        for (int i = (page - 1) * 8; i < Math.min(page * 8, rules.size()); i++) {
            var rule = rules.get(i);
            boolean here = rule.scope().equals(current);
            source.sendSuccess(() -> Component.literal("#" + rule.id() + " " + rule.name()
                + (here ? " [当前映射]" : " [其他映射]") + " zone=" + rule.scope().zone() + " region=" + rule.scope().region()
                + (rule.wholeZone() ? " 整区" : " MC(" + rule.x() + "," + rule.z() + ") r=" + rule.radius())), false);
        }
        return 1;
    }

    private static int removeExclusion(CommandSourceStack source, int id) {
        var data = commandData(source);
        if (data == null) return 0;
        if (!data.removeExclusion(id)) {
            source.sendFailure(Component.literal("没有禁区 #" + id + "；可用 exclude list 查看。")); return 0;
        }
        source.sendSuccess(() -> Component.literal("已移除禁区 #" + id
            + "。未生成位置可再次参与筛选；其他重叠禁区仍有效，已采集位置不会补回，现有方块不变。"), false);
        return 1;
    }
}
