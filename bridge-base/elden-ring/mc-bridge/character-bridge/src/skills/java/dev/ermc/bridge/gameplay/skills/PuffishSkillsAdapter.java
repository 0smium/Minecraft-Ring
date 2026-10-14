package dev.ermc.bridge.gameplay.skills;

import dev.ermc.bridge.api.CapabilitySnapshot.Ability;
import dev.ermc.bridge.api.BridgeLifecycle;
import dev.ermc.bridge.gameplay.progression.LevelProgress;
import dev.ermc.bridge.gameplay.capability.AbilityCatalog;
import dev.ermc.bridge.gameplay.skills.GrowthStateStore.State;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.puffish.skillsmod.api.Category;
import net.puffish.skillsmod.api.Skill;
import net.puffish.skillsmod.api.SkillsAPI;

/** Loaded only after the optional dependency/version check in SkillsBridge. */
public final class PuffishSkillsAdapter implements SkillsBridge.Backend {
    private static final ResourceLocation CATEGORY = ResourceLocation.parse(SkillsBridge.CATEGORY_ID);
    private static final ResourceLocation POINT_SOURCE = ResourceLocation.parse(LevelProgress.POINT_SOURCE);
    private static final ResourceLocation TEST_SOURCE = ResourceLocation.parse("erbridge:test_preset");
    private static final Set<String> LEGACY = Set.of("speed_1", "speed_2", "speed_3", "aura_1", "aura_2", "aura_3");
    private final Set<UUID> pendingPlayers = new HashSet<>();
    private final Map<UUID, State> journals = new HashMap<>();
    private final Map<UUID, LevelProgress> entitlements = new HashMap<>();

    public PuffishSkillsAdapter() {}

    @Override
    public void register(Consumer<ServerPlayer> changed) {
        SkillsAPI.registerSkillUnlockEvent((player, category, skill) -> {
            if (CATEGORY.equals(category)) changed.accept(player);
        });
        SkillsAPI.registerSkillLockEvent((player, category, skill) -> {
            if (CATEGORY.equals(category)) changed.accept(player);
        });
        // Run login/reload reconciliation after all framework lifecycle listeners,
        // independent of Fabric's optional-mod initialization order.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> pendingPlayers.add(handler.player.getUUID()));
        BridgeLifecycle.PLAYER_DISCONNECTED.register(player -> { pendingPlayers.remove(player.getUUID()); journals.remove(player.getUUID()); entitlements.remove(player.getUUID()); });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            journals.clear();
            if (success) server.getPlayerList().getPlayers().forEach(player -> pendingPlayers.add(player.getUUID()));
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { pendingPlayers.clear(); journals.clear(); entitlements.clear(); });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (pendingPlayers.isEmpty()) return;
            var pending = Set.copyOf(pendingPlayers);
            pendingPlayers.clear();
            for (UUID id : pending) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player != null) changed.accept(player);
            }
        });
    }

    @Override
    public Optional<SkillsBridge.Status> reconcile(ServerPlayer player, LevelProgress progress) {
        var category = SkillsAPI.getCategory(CATEGORY);
        if (category.isEmpty()) return Optional.empty();
        Category current = category.get();
        verifyTree(current);
        entitlements.put(player.getUUID(), progress);
        State state = journal(player, progress, current);
        if (state == null || state.phase().equals("migration")) return Optional.of(pending(current, player));
        if (legacySaveNeedsRetry(state, current, player, progress)) return Optional.of(pending(current, player));
        if (state.phase().equals("preset_applying") || state.phase().equals("preset_clearing"))
            throw new IllegalStateException("测试预设操作中断；执行 /erbridge progression preset clear 恢复原构筑");
        int entitled = progress.earnedPoints();
        if (current.getPoints(player, POINT_SOURCE) != entitled) {
            current.setPoints(player, POINT_SOURCE, entitled);
        }
        // Only players with a confirmed B01 character binding receive this category.
        if (!current.isUnlocked(player)) current.unlock(player);
        if (!state.testActive() && current.getPoints(player, TEST_SOURCE) != 0) {
            // The journal may be newer than Skills' last world save after a crash.
            restore(current, player, progress, state);
        } else if (state.testActive() && current.getPoints(player, TEST_SOURCE) != state.testPoints()) {
            throw new IllegalStateException("测试点源与恢复日志不符；执行 preset clear 恢复");
        }
        return Optional.of(snapshot(current, player, state));
    }

    @Override
    public Optional<SkillsBridge.Status> snapshot(ServerPlayer player) {
        LevelProgress progress = entitlements.get(player.getUUID());
        return progress == null ? Optional.empty() : reconcile(player, progress);
    }

    private static SkillsBridge.Status snapshot(Category category, ServerPlayer player, State state) {
        AbilityCatalog catalog = AbilityCatalog.instance(); Set<String> unlocked = unlocked(category, player);
        var ranks = catalog.ranks(category.isUnlocked(player) ? unlocked : Set.of());
        int expectedCost = unlocked.stream().mapToInt(n -> catalog.nodes().get(n).cost()).sum();
        if (category.getSpentPoints(player) != expectedCost) throw new IllegalStateException("Skills 节点价格与能力目录不符；停止授权");
        for (var source : category.streamPointsSources(player).toList())
            if (!source.equals(POINT_SOURCE) && !source.equals(TEST_SOURCE) && category.getPoints(player, source) != 0)
                throw new IllegalStateException("发现未知 Skills 点源；停止授权");
        return new SkillsBridge.Status(category.getPoints(player, POINT_SOURCE), category.getPointsTotal(player),
                category.getSpentPoints(player), category.getPointsLeft(player), ranks,
                category.getPoints(player, TEST_SOURCE), false, state.testActive() ? state.preset() : "");
    }
    private static SkillsBridge.Status pending(Category category, ServerPlayer player) {
        return new SkillsBridge.Status(category.getPoints(player, POINT_SOURCE), category.getPointsTotal(player),
                category.getSpentPoints(player), Math.max(0, category.getPointsLeft(player)), AbilityCatalog.instance().ranks(Set.of()), 0, true, "");
    }
    private static Set<String> unlocked(Category category, ServerPlayer player) {
        return category.streamUnlockedSkills(player).map(Skill::getId).collect(java.util.stream.Collectors.toSet());
    }
    private static boolean legacySaveNeedsRetry(State state, Category category, ServerPlayer player, LevelProgress progress) {
        if (!state.phase().equals("ready") || state.legacyNodes().isEmpty() || !state.legacyNodes().equals(unlocked(category, player))) return false;
        // The journal can survive a crash before Skills' NBT is flushed. Never authorize an
        // invalid old-price build merely because the migration's recovery file is already v2.
        if (category.getSpentPoints(player) > progress.earnedPoints()) return true;
        try { AbilityCatalog.instance().ranks(state.legacyNodes()); return false; }
        catch (IllegalArgumentException invalid) { return true; }
    }
    private static void verifyTree(Category category) {
        Set<String> actual = category.streamSkills().map(Skill::getId).collect(java.util.stream.Collectors.toSet());
        if (!actual.equals(AbilityCatalog.instance().nodes().keySet())) throw new IllegalStateException("Skills 图谱与能力目录不一致；停止授权");
    }
    private GrowthStateStore store(ServerPlayer player) {
        return new GrowthStateStore(player.getServer().getWorldPath(LevelResource.ROOT).resolve("data/erbridge/growth"));
    }
    private State journal(ServerPlayer player, LevelProgress progress, Category category) {
        State found = journals.get(player.getUUID());
        if (found != null) {
            if (!found.binding().equals(progress.binding())) throw new IllegalStateException("Growth 角色不符");
            return found;
        }
        try {
            var saved = store(player).load(progress.binding());
            if (saved.isPresent()) { journals.put(player.getUUID(), saved.get()); return saved.get(); }
            Set<String> previous = unlocked(category, player);
            if (!LEGACY.containsAll(previous)) throw new IllegalStateException("未知旧技能；需要人工迁移，未清除记录");
            if (!previous.isEmpty()) return null; // Explicit migrate command required; no silent cost increase/respec.
            State initial = new State(progress.binding(), 2, "ready", "", 0, Set.of(), Set.of());
            save(player, initial); return initial;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private void save(ServerPlayer player, State state) {
        try { store(player).save(state); journals.put(player.getUUID(), state); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
    @Override public String migrate(ServerPlayer player, LevelProgress progress) {
        Category category = SkillsAPI.getCategory(CATEGORY).orElseThrow(); verifyTree(category);
        State previous = journal(player, progress, category);
        if (previous != null && !previous.phase().equals("migration") && !legacySaveNeedsRetry(previous, category, player, progress))
            return "当前角色已使用成长目录 v2，无需迁移";
        Set<String> legacy = previous == null ? unlocked(category, player) : previous.legacyNodes();
        if (!LEGACY.containsAll(legacy)) throw new IllegalStateException("未知旧节点，迁移中止且保留原记录");
        State pending = new State(progress.binding(), 2, "migration", "", 0, Set.of(), legacy);
        save(player, pending); // Durable old node list before the real framework refund.
        category.resetSkills(player); category.setPoints(player, POINT_SOURCE, progress.earnedPoints()); category.setPoints(player, TEST_SOURCE, 0);
        save(player, pending.ready());
        return "已明确迁移旧节点 " + legacy + "：旧成本每阶 1 点已通过 Skills 全额退款；真实等级额度不变。旧学习快照保留于 " + store(player).path(progress.binding());
    }
    @Override public String applyPreset(ServerPlayer player, LevelProgress progress, String id) {
        AbilityCatalog catalog = AbilityCatalog.instance(); var preset = catalog.presets().get(id);
        if (preset == null) throw new IllegalArgumentException("未知预设：" + id);
        Category category = SkillsAPI.getCategory(CATEGORY).orElseThrow(); verifyTree(category);
        State old = journal(player, progress, category);
        if (old == null || old.phase().equals("migration")) throw new IllegalStateException("先执行 progression migrate 明确退款旧树");
        if (!old.phase().equals("ready")) throw new IllegalStateException("已有测试预设；先 preset clear 恢复原构筑");
        snapshot(category, player, old); // Reject unexpected sources/costs before capturing legitimate state.
        State pending = new State(progress.binding(), 2, "preset_applying", id, preset.budget(), unlocked(category, player), old.legacyNodes());
        save(player, pending);
        category.resetSkills(player); category.setPoints(player, POINT_SOURCE, progress.earnedPoints()); category.setPoints(player, TEST_SOURCE, preset.budget());
        purchase(category, player, catalog.selectedNodes(preset.ranks()));
        save(player, pending.phase("preset_active"));
        return "已应用测试预设 " + preset.name() + "；独立测试点 +" + preset.budget() + "，实际消费 " + catalog.cost(preset.ranks())
                + "。未修改法环等级、未自动启用模块。preset clear 恢复原学习节点并撤销全部测试点";
    }
    @Override public String clearPreset(ServerPlayer player, LevelProgress progress) {
        Category category = SkillsAPI.getCategory(CATEGORY).orElseThrow(); verifyTree(category);
        State state = journal(player, progress, category);
        if (state == null || state.phase().equals("migration")) throw new IllegalStateException("先完成旧树 migrate");
        if (state.phase().equals("ready")) return "没有活动测试预设";
        State clearing = state.phase("preset_clearing"); save(player, clearing);
        restore(category, player, progress, clearing); save(player, clearing.ready());
        return "已撤销全部测试点并恢复测试前节点；测试期间真实升级获得的点数保留";
    }
    private static void restore(Category category, ServerPlayer player, LevelProgress progress, State state) {
        AbilityCatalog.instance().ranks(state.backupNodes());
        int cost = state.backupNodes().stream().mapToInt(n -> AbilityCatalog.instance().nodes().get(n).cost()).sum();
        if (cost > progress.earnedPoints()) throw new IllegalStateException("原构筑超过真实点额度；保留恢复日志并停止授权");
        category.resetSkills(player); category.setPoints(player, POINT_SOURCE, progress.earnedPoints()); category.setPoints(player, TEST_SOURCE, 0);
        purchase(category, player, state.backupNodes());
    }
    private static void purchase(Category category, ServerPlayer player, Set<String> nodes) {
        for (String id : AbilityCatalog.instance().purchaseOrder(nodes)) {
            Skill skill = category.getSkill(id).orElseThrow();
            if (skill.getState(player) != Skill.State.AFFORDABLE) throw new IllegalStateException("Skills 正式前置/点数检查拒绝 " + id);
            skill.unlock(player);
            if (skill.getState(player) != Skill.State.UNLOCKED) throw new IllegalStateException("Skills 未确认购买 " + id);
        }
    }

    @Override
    public boolean openScreen(ServerPlayer player) {
        var category = SkillsAPI.getCategory(CATEGORY);
        if (category.isEmpty() || !category.get().isUnlocked(player)) return false;
        category.get().openScreen(player);
        return true;
    }

    @Override
    public boolean resetSkills(ServerPlayer player) {
        var category = SkillsAPI.getCategory(CATEGORY);
        if (category.isEmpty() || !category.get().isUnlocked(player)) return false;
        LevelProgress progress = entitlements.get(player.getUUID());
        if (progress == null) return false;
        State state = journal(player, progress, category.get());
        if (state == null || !Set.of("ready", "preset_active").contains(state.phase()))
            throw new IllegalStateException("先完成 migrate / preset clear，保留旧学习快照");
        category.get().resetSkills(player);
        return true;
    }
}
