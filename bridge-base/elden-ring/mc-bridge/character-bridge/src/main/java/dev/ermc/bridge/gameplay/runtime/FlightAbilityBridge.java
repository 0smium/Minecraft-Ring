package dev.ermc.bridge.gameplay.runtime;

import dev.ermc.bridge.LocalHostOwner;
import dev.ermc.bridge.api.BridgeLifecycle;
import dev.ermc.bridge.api.CapabilitySnapshot.Ability;
import dev.ermc.bridge.api.CapabilitySnapshot.Grant;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Leases vanilla server flight permission to the learned ability; LB still performs Creative flight. */
public final class FlightAbilityBridge {
    private static final Ability FLY = new Ability("fly");
    private static ServerPlayer holder;
    private static boolean originalMayfly;
    private static float originalSpeed;
    private static Grant applied;
    private FlightAbilityBridge() {}

    public static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(FlightAbilityBridge::tick);
        BridgeLifecycle.PLAYER_DISCONNECTED.register(player -> { if (player == holder) release(); });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> release());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
    }

    private static void tick(MinecraftServer server) {
        ServerPlayer player = LocalHostOwner.find(server);
        if (holder != null && (holder != player || !holder.isAlive() || holder.isRemoved())) release();
        // The player-list entry may remain the dead/removed instance until respawn.
        // Releasing the old lease must not grant flight back to that same instance.
        if (player == null || !player.isAlive() || player.isRemoved()) return;
        if (dev.ermc.bridge.character.action.ActionLockServer.blocks(player)) { release(); return; }
        // A gamemode change owns its own ability flags. Never overwrite creative/spectator state.
        if (player.isCreative() || player.isSpectator()) { clear(); return; }
        Grant grant = B01Runtime.challengeFor(player)
            ? B01Runtime.snapshotFor(player).map(snapshot -> snapshot.abilities().get(FLY)).orElse(null) : null;
        if (grant == null || !grant.unlocked() || !grant.allowedModes().contains("creative")
                || !grant.numericLimits().containsKey("speed")) { release(); return; }
        var abilities = player.getAbilities();
        if (holder == null) {
            holder = player;
            originalMayfly = abilities.mayfly;
            originalSpeed = abilities.getFlyingSpeed();
        }
        // Do not resend every tick: that would overwrite the user's legal LB speed preference.
        if (!grant.equals(applied) || !abilities.mayfly) {
            abilities.mayfly = true;
            abilities.setFlyingSpeed((float) grant.numericLimits().get("speed").clamp(abilities.getFlyingSpeed()));
            applied = grant;
            player.onUpdateAbilities();
        }
    }

    private static void release() {
        ServerPlayer player = holder;
        if (player != null && !player.isCreative() && !player.isSpectator()) {
            var abilities = player.getAbilities();
            abilities.mayfly = originalMayfly;
            if (!originalMayfly) abilities.flying = false;
            abilities.setFlyingSpeed(originalSpeed);
            if (!player.isRemoved()) player.onUpdateAbilities();
        }
        clear();
    }

    private static void clear() { holder = null; applied = null; }
}
