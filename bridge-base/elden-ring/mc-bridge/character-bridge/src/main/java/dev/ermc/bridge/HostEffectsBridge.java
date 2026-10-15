package dev.ermc.bridge;

import dev.ermc.bridge.link.ErLink;
import dev.ermc.bridge.link.HostEffectCursor;
import dev.ermc.bridge.link.HostEffectRules;
import dev.ermc.bridge.link.HostEffectRules.Effect;
import dev.ermc.bridge.link.HostEffectRules.LeaseSpec;
import dev.ermc.bridge.link.HostEffectState;
import dev.ermc.bridge.link.Protocol;
import dev.ermc.bridge.mixin.MobEffectInstanceAccessor;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;

/** Identified host states become owned vanilla effects on the integrated host's player only. */
public final class HostEffectsBridge {
    private HostEffectsBridge() {}
    private static final Logger LOG = LoggerFactory.getLogger("erbridge");
    private static final HostEffectState STATE = new HostEffectState();
    private static final HostEffectCursor CURSOR = new HostEffectCursor();
    private static final Map<Effect, Lease> LEASES = new EnumMap<>(Effect.class);
    // Burst HP attribution still needs an attack+frost runtime check before enabling by default.
    private static final boolean FROST_BURST = Boolean.getBoolean("erbridge.experimentalFrostBurst");
    private static MinecraftServer activeServer;
    private static ServerPlayer activePlayer;
    private static int hostLife = Integer.MIN_VALUE;
    private static long effectFrame = Long.MIN_VALUE, renderFrame = Long.MIN_VALUE;
    private static long effectAdvancedAt, renderAdvancedAt;

    private static final class Lease {
        final ServerPlayer player;
        final LeaseSpec spec;
        final MobEffectInstance effect;
        int lastTick, lastDuration;
        Lease(ServerPlayer player, LeaseSpec spec, MobEffectInstance effect) {
            this.player = player;
            this.spec = spec;
            this.effect = effect;
            remember();
        }
        void remember() { lastTick = player.tickCount; lastDuration = effect.getDuration(); }
        boolean ownsEffect() {
            int expected = lastDuration - Math.max(0, player.tickCount - lastTick);
            // Vanilla merges equal effects; shorter outside additions cannot be attributed.
            return player.getEffect(type(spec.effect())) == effect && effect.getAmplifier() == spec.amplifier()
                    && !effect.isInfiniteDuration() && effect.getDuration() > 0 && effect.getDuration() <= expected;
        }
        void releaseOwned() {
            // A weaker, longer external effect is stored beneath our top instance by vanilla.
            // The hidden chain ticks alongside the top, so restore its remaining duration as-is.
            MobEffectInstance hidden = hiddenEffect(effect);
            while (hidden != null && !hidden.isInfiniteDuration() && hidden.getDuration() <= 0) {
                hidden = hiddenEffect(hidden);
            }
            if (player.removeEffect(type(spec.effect())) && hidden != null) player.addEffect(hidden);
        }
    }

    private static MobEffectInstance hiddenEffect(MobEffectInstance effect) {
        return ((MobEffectInstanceAccessor) effect).erbridge$getHiddenEffect();
    }

    public static void onServerTick(MinecraftServer server) {
        if (activeServer != server) { reset(); activeServer = server; }
        ErLink link = ErLink.get();
        // One local IPC host has one owner. LAN guests must not inherit its ailments.
        // Dedicated/multiple-host sessions require the explicit binding transport from B01.
        var profile = server.getSingleplayerProfile();
        ServerPlayer player = profile == null ? null : server.getPlayerList().getPlayer(profile.getId());
        if (player != activePlayer) { clearOwn("host player changed"); activePlayer = player; }
        if (player == null || !player.isAlive() || player.level() != server.overworld()
                || !TerrainManager.isBridgeWorld() || !link.alive() || !link.readHostEffects(STATE)) {
            clearOwn("player, world, connection or snapshot unavailable");
            return;
        }
        long now = System.nanoTime() / 1_000_000L, currentRenderFrame = link.hostFrame();
        if (STATE.frame < effectFrame || currentRenderFrame < renderFrame) clearOwn("host frame restarted");
        if (STATE.frame != effectFrame) { effectFrame = STATE.frame; effectAdvancedAt = now; }
        if (currentRenderFrame != renderFrame) { renderFrame = currentRenderFrame; renderAdvancedAt = now; }
        if (hostLife != STATE.hostLife) { clearOwn("host life changed"); hostLife = STATE.hostLife; }
        if (!STATE.has(Protocol.HOST_EFFECT_VALID) || !STATE.has(Protocol.HOST_EFFECT_MC_CONTROL)
                || STATE.hostLife != link.hostLife() || STATE.frame <= 0
                || now - effectAdvancedAt > 1000 || now - renderAdvancedAt > 1000) {
            clearOwn("host effect invalid, stale or control released");
            return;
        }
        boolean frostBurst = CURSOR.observe(STATE);
        dev.ermc.bridge.character.action.ActionLockServer.observe(player, STATE.has(Protocol.HOST_EFFECT_SLEEP));
        Map<Effect, LeaseSpec> desired = HostEffectRules.desired(STATE);
        var leases = LEASES.entrySet().iterator();
        while (leases.hasNext()) {
            var entry = leases.next();
            Lease lease = entry.getValue();
            boolean owned = lease.ownsEffect();
            if (!lease.spec.equals(desired.get(entry.getKey())) || !owned) {
                if (owned) lease.releaseOwned();
                leases.remove();
            }
        }
        for (LeaseSpec spec : desired.values()) {
            Holder<MobEffect> type = type(spec.effect());
            Lease lease = LEASES.get(spec.effect());
            if (lease == null) {
                if (player.hasEffect(type)) continue; // Never take over another mod/potion's effect.
                MobEffectInstance effect = new MobEffectInstance(type, spec.duration(), spec.amplifier());
                if (player.addEffect(effect) && player.getEffect(type) == effect
                        && effect.getAmplifier() == spec.amplifier() && !effect.isInfiniteDuration()
                        && effect.getDuration() > 0 && effect.getDuration() <= spec.duration()) {
                    LEASES.put(spec.effect(), new Lease(player, spec, effect));
                    LOG.info("Host state -> vanilla {} tier {} for {} (life {}, frame {})", spec.effect(),
                            spec.amplifier() + 1, player.getName().getString(), STATE.hostLife, STATE.frame);
                }
            } else {
                int remaining = lease.effect.getDuration();
                if (remaining <= spec.renewBelow()) {
                    player.addEffect(new MobEffectInstance(type, remaining + spec.extension(), spec.amplifier()));
                    if (player.getEffect(type) != lease.effect || lease.effect.getAmplifier() != spec.amplifier()
                            || lease.effect.isInfiniteDuration() || lease.effect.getDuration() <= 0
                            || lease.effect.getDuration() > remaining + spec.extension()) {
                        LEASES.remove(spec.effect());
                        continue;
                    }
                }
                lease.remember();
            }
        }
        if (frostBurst && FROST_BURST) {
            // One vanilla Instant Damage I application; no host HP-delta replay and no extra knockback.
            MobEffects.HARM.value().applyInstantenousEffect(null, null, player, 0, 1.0);
            LOG.info("Experimental frost proc {} -> Instant Damage I for {}", STATE.frostProcs, player.getName().getString());
            if (!player.isAlive()) clearOwn("frost burst death");
        }
    }

    private static Holder<MobEffect> type(Effect effect) {
        return switch (effect) {
            case POISON -> MobEffects.POISON;
            case WITHER -> MobEffects.WITHER;
            case SLOWNESS -> MobEffects.MOVEMENT_SLOWDOWN;
            case BLINDNESS -> MobEffects.BLINDNESS;
            case NAUSEA -> MobEffects.CONFUSION;
            case WEAKNESS -> MobEffects.WEAKNESS;
        };
    }

    private static void clearOwn(String reason) {
        dev.ermc.bridge.character.action.ActionLockServer.clear();
        for (Lease lease : LEASES.values()) {
            if (lease.ownsEffect()) lease.releaseOwned();
            LOG.info("Host {} lease released for {} ({})", lease.spec.effect(), lease.player.getName().getString(), reason);
        }
        LEASES.clear();
        CURSOR.reset();
    }

    /** Run before player saves; bridge leases must not survive a stopped host. */
    public static void onServerStopping(MinecraftServer server) {
        if (activeServer == server) clearOwn("server stopping");
    }

    public static void reset() {
        clearOwn("reset");
        activeServer = null;
        activePlayer = null;
        hostLife = Integer.MIN_VALUE;
        effectFrame = renderFrame = Long.MIN_VALUE;
        effectAdvancedAt = renderAdvancedAt = 0;
    }
}
