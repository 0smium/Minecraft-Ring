package dev.ermc.bridge.gameplay.runtime;

import dev.ermc.bridge.LocalHostOwner;
import dev.ermc.bridge.api.BridgeLifecycle;
import dev.ermc.bridge.api.CapabilitySnapshot.Ability;
import dev.ermc.bridge.api.CapabilitySnapshot.Grant;
import dev.ermc.bridge.api.PlayerFlight;
import dev.ermc.bridge.TerrainManager;
import dev.ermc.bridge.link.ErLink;
import dev.ermc.bridge.link.GameState;
import dev.ermc.bridge.link.Protocol;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Keeps Creative entitlement intact; custom velocity flight additionally needs an activity lease. */
public final class FlightAbilityBridge {
    private static final Ability FLY = new Ability("fly");
    private static final Ability PRECISION = new Ability("fly_precision");
    private static final FlightActivityLease activity = new FlightActivityLease();
    private static final GameState state = new GameState();
    private static ServerPlayer declarer;
    private static boolean customGranted;
    private static long hostStart, hostLife, observedStart, observedLife, lastFrame = -1, frameChangedAt;
    private static int stage;
    private static ServerPlayer holder;
    private static boolean originalMayfly;
    private static float originalSpeed;
    private static Grant applied;
    private FlightAbilityBridge() {}

    public static void initialize() {
        PayloadTypeRegistry.playC2S().register(PrecisionFlightPayload.TYPE, PrecisionFlightPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PrecisionFlightPayload.TYPE, PrecisionFlightPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(PrecisionFlightPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            if (!LocalHostOwner.isOwner(player) || !player.getUUID().equals(payload.owner())) return;
            if (declarer != player) { revokeCustom(); activity.reset(); declarer = player; }
            ErLink link = ErLink.get();
            if (payload.active() && (payload.hostStart() != link.hostStartMs() || payload.hostLife() != link.hostLife())) return;
            if (!activity.receive(payload.sequence(), payload.active(), System.nanoTime())) return;
            tick(player.getServer()); // Install mayfly before the client is allowed to execute velocity flight.
            acknowledge();
        });
        PlayerFlight.CUSTOM_ACTIVE.register(player -> player instanceof ServerPlayer owner && customActive(owner));
        BridgeLifecycle.BEFORE_TERRAIN_TICK.register(server -> updateCustom(LocalHostOwner.find(server)));
        BridgeLifecycle.BEFORE_RECALL.register(player -> { if (player == declarer) revokeCustom(); });
        ServerTickEvents.END_SERVER_TICK.register(FlightAbilityBridge::tick);
        BridgeLifecycle.PLAYER_DISCONNECTED.register(player -> {
            if (player == holder) release();
            if (player == declarer) resetCustom();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { revokeCustom(); release(); });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { clear(); resetCustom(); });
    }

    private static boolean customActive(ServerPlayer player) {
        return customGranted && declarer == player && activity.active(System.nanoTime()) && eligible(player);
    }

    private static boolean eligible(ServerPlayer player) {
        if (player == null || !LocalHostOwner.isOwner(player) || !TerrainManager.isBridgeWorld()
                || !player.isAlive() || player.isRemoved() || player.isPassenger() || player.isSpectator()
                || dev.ermc.bridge.character.action.ActionLockServer.blocks(player)) return false;
        // Free mode is an explicit server setting; absent runtime/settings still means challenge.
        if (!B01Runtime.challengeFor(player)) return true;
        return B01Runtime.snapshotFor(player).map(snapshot -> {
            Grant base = snapshot.abilities().get(FLY), precision = snapshot.abilities().get(PRECISION);
            return base != null && base.unlocked() && precision != null && precision.unlocked()
                && precision.allowedModes().contains("vanilla");
        }).orElse(false);
    }

    private static boolean hostReady() {
        ErLink link = ErLink.get();
        if (observedStart != link.hostStartMs() || observedLife != link.hostLife()) {
            observedStart = link.hostStartMs(); observedLife = link.hostLife(); lastFrame = -1; frameChangedAt = 0;
            if (customGranted) { revokeCustom(); return false; }
        }
        if (!link.poll() || !link.snapshot(state) || !state.has(Protocol.STATE_PLAYER_VALID)
                || !state.has(Protocol.STATE_CAM_OVERRIDDEN) || state.has(Protocol.STATE_HOST_BUSY | Protocol.STATE_PLAYER_DEAD)) return false;
        long now = System.nanoTime();
        if (state.frame > lastFrame) { lastFrame = state.frame; frameChangedAt = now; }
        return state.frame == lastFrame && frameChangedAt != 0 && now - frameChangedAt < FlightActivityLease.DURATION_NANOS;
    }

    private static void updateCustom(ServerPlayer player) {
        boolean allowed = declarer == player && activity.active(System.nanoTime()) && eligible(player) && hostReady();
        ErLink link = ErLink.get();
        if (allowed && customGranted && (hostStart != link.hostStartMs() || hostLife != link.hostLife() || stage != state.stageId)) allowed = false;
        if (allowed && !customGranted) { hostStart = link.hostStartMs(); hostLife = link.hostLife(); stage = state.stageId; }
        if (customGranted && !allowed) { revokeCustom(); return; }
        customGranted = allowed;
        if (allowed) player.fallDistance = 0;
    }

    private static void acknowledge() {
        if (declarer != null && !declarer.isRemoved() && activity.sequence() > 0
                && ServerPlayNetworking.canSend(declarer, PrecisionFlightPayload.TYPE))
            ServerPlayNetworking.send(declarer, new PrecisionFlightPayload(declarer.getUUID(), activity.sequence(),
                ErLink.get().hostStartMs(), ErLink.get().hostLife(), customGranted));
    }
    private static void revokeCustom() { customGranted = false; activity.revoke(); acknowledge(); }
    private static void resetCustom() {
        customGranted = false; activity.reset(); declarer = null;
        hostStart = hostLife = observedStart = observedLife = frameChangedAt = 0; lastFrame = -1; stage = 0;
    }

    private static void tick(MinecraftServer server) {
        ServerPlayer player = LocalHostOwner.find(server);
        updateCustom(player);
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
                || !grant.numericLimits().containsKey("speed")) grant = null;
        if (grant == null && !customActive(player)) { release(); return; }
        var abilities = player.getAbilities();
        if (holder == null) {
            holder = player;
            originalMayfly = abilities.mayfly;
            originalSpeed = abilities.getFlyingSpeed();
        }
        // Do not resend every tick: that would overwrite the user's legal LB speed preference.
        if (!java.util.Objects.equals(grant, applied) || !abilities.mayfly) {
            abilities.mayfly = true;
            if (grant != null) abilities.setFlyingSpeed((float) grant.numericLimits().get("speed").clamp(abilities.getFlyingSpeed()));
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
