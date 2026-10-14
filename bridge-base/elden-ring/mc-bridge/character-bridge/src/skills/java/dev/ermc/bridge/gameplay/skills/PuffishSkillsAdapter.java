package dev.ermc.bridge.gameplay.skills;

import dev.ermc.bridge.api.CapabilitySnapshot.Ability;
import dev.ermc.bridge.api.BridgeLifecycle;
import dev.ermc.bridge.gameplay.progression.LevelProgress;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.puffish.skillsmod.api.Category;
import net.puffish.skillsmod.api.Skill;
import net.puffish.skillsmod.api.SkillsAPI;

/** Loaded only after the optional dependency/version check in SkillsBridge. */
public final class PuffishSkillsAdapter implements SkillsBridge.Backend {
    private static final ResourceLocation CATEGORY = ResourceLocation.parse(SkillsBridge.CATEGORY_ID);
    private static final ResourceLocation POINT_SOURCE = ResourceLocation.parse(LevelProgress.POINT_SOURCE);
    private static final String[] SPEED_NODES = {"speed_1", "speed_2", "speed_3"};
    private static final String[] AURA_NODES = {"aura_1", "aura_2", "aura_3"};
    private final Set<UUID> pendingPlayers = new HashSet<>();

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
        BridgeLifecycle.PLAYER_DISCONNECTED.register(player -> pendingPlayers.remove(player.getUUID()));
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            if (success) server.getPlayerList().getPlayers().forEach(player -> pendingPlayers.add(player.getUUID()));
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> pendingPlayers.clear());
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
        int entitled = progress.earnedPoints();
        if (current.getPoints(player, POINT_SOURCE) != entitled) {
            current.setPoints(player, POINT_SOURCE, entitled);
        }
        // Only players with a confirmed B01 character binding receive this category.
        if (!current.isUnlocked(player)) current.unlock(player);
        return Optional.of(snapshot(current, player));
    }

    @Override
    public Optional<SkillsBridge.Status> snapshot(ServerPlayer player) {
        return SkillsAPI.getCategory(CATEGORY).map(category -> snapshot(category, player));
    }

    private static SkillsBridge.Status snapshot(Category category, ServerPlayer player) {
        var ranks = new EnumMap<Ability, Integer>(Ability.class);
        ranks.put(Ability.SPEED, category.isUnlocked(player) ? rank(category, player, SPEED_NODES) : 0);
        ranks.put(Ability.AURA, category.isUnlocked(player) ? rank(category, player, AURA_NODES) : 0);
        return new SkillsBridge.Status(category.getPoints(player, POINT_SOURCE), category.getPointsTotal(player),
                category.getSpentPoints(player), category.getPointsLeft(player), ranks);
    }

    private static int rank(Category category, ServerPlayer player, String[] nodes) {
        int rank = 0;
        for (String id : nodes) {
            var skill = category.getSkill(id);
            if (skill.isEmpty() || skill.get().getState(player) != Skill.State.UNLOCKED) break;
            ++rank;
        }
        return rank;
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
        category.get().resetSkills(player);
        return true;
    }
}
