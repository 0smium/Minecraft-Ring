package dev.ermc.bridge;

import dev.ermc.bridge.link.ErLink;
import dev.ermc.bridge.link.HostEffectState;
import dev.ermc.bridge.link.Protocol;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Translates identified host effects into vanilla Minecraft effects on the server thread. */
public final class HostEffectsBridge {
	private HostEffectsBridge() { }
	private static final Logger LOG = LoggerFactory.getLogger("erbridge");
	private static final HostEffectState STATE = new HostEffectState();
	private static final Map<UUID, Lease> LEASES = new HashMap<>();
	private static MinecraftServer activeServer;
	private static int hostLife = Integer.MIN_VALUE;
	private static long effectFrame = Long.MIN_VALUE, renderFrame = Long.MIN_VALUE;
	private static long effectAdvancedAt, renderAdvancedAt;

	private static final class Lease {
		final ServerPlayer player;
		final MobEffectInstance effect;
		int lastTick, lastDuration;
		Lease(ServerPlayer player, MobEffectInstance effect) {
			this.player = player;
			this.effect = effect;
			remember();
		}
		void remember() {
			lastTick = player.tickCount;
			lastDuration = effect.getDuration();
		}
		boolean ownsEffect() {
			int expected = lastDuration - Math.max(0, player.tickCount - lastTick);
			// A stronger effect or an outside duration extension belongs to its other source.
			// Same-level additions shorter than our lease cannot be distinguished by vanilla.
			return player.getEffect(MobEffects.POISON) == effect && effect.getAmplifier() == 0
				&& !effect.isInfiniteDuration() && effect.getDuration() > 0 && effect.getDuration() <= expected;
		}
	}

	public static void onServerTick(MinecraftServer server) {
		if (activeServer != server) {
			clearOwn("server changed");
			reset();
			activeServer = server;
		}
		ErLink link = ErLink.get();
		if (!TerrainManager.isBridgeWorld() || !link.alive() || !link.readHostEffects(STATE)) {
			clearOwn("world, connection or effect snapshot unavailable");
			return;
		}
		long now = System.currentTimeMillis(), currentRenderFrame = link.hostFrame();
		if (STATE.frame != effectFrame) { effectFrame = STATE.frame; effectAdvancedAt = now; }
		if (currentRenderFrame != renderFrame) { renderFrame = currentRenderFrame; renderAdvancedAt = now; }
		if (hostLife != STATE.hostLife) {
			clearOwn("host life changed");
			hostLife = STATE.hostLife;
		}
		if (!STATE.has(Protocol.HOST_EFFECT_VALID) || !STATE.has(Protocol.HOST_EFFECT_MC_CONTROL)
			|| !STATE.has(Protocol.HOST_EFFECT_POISON) || STATE.hostLife != link.hostLife()
			|| STATE.frame <= 0 || now - effectAdvancedAt > 1000 || now - renderAdvancedAt > 1000) {
			clearOwn("host effect inactive, invalid, stale or control released");
			return;
		}
		var leases = LEASES.entrySet().iterator();
		while (leases.hasNext()) {
			var entry = leases.next();
			Lease lease = entry.getValue();
			boolean owned = lease.ownsEffect();
			if (server.getPlayerList().getPlayer(entry.getKey()) != lease.player || !lease.player.isAlive()
				|| lease.player.level() != server.overworld() || !owned) {
				if (owned) lease.player.removeEffect(MobEffects.POISON);
				LOG.info("Host poison lease released for {} (player changed or outside effect)", lease.player.getName().getString());
				leases.remove();
			}
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!player.isAlive() || player.level() != server.overworld()) continue;
			Lease lease = LEASES.get(player.getUUID());
			if (lease == null) {
				if (player.hasEffect(MobEffects.POISON)) continue; // Never take over an existing Minecraft poison.
				MobEffectInstance poison = new MobEffectInstance(MobEffects.POISON, 100, 0);
				if (player.addEffect(poison) && player.getEffect(MobEffects.POISON) == poison
					&& poison.getAmplifier() == 0 && !poison.isInfiniteDuration()
					&& poison.getDuration() > 0 && poison.getDuration() <= 100) {
					lease = new Lease(player, poison);
					LEASES.put(player.getUUID(), lease);
					LOG.info("Host poison effect {} -> vanilla Poison I for {} (life {}, frame {})",
						STATE.poisonEffectId, player.getName().getString(), STATE.hostLife, STATE.frame);
				}
			} else {
				int remaining = lease.effect.getDuration();
				if (remaining <= 25) {
					// Adding 75 preserves Poison I's duration modulo 25 and its vanilla damage cadence.
					player.addEffect(new MobEffectInstance(MobEffects.POISON, remaining + 75, 0));
					if (player.getEffect(MobEffects.POISON) != lease.effect || lease.effect.getAmplifier() != 0
						|| lease.effect.isInfiniteDuration() || lease.effect.getDuration() <= 0
						|| lease.effect.getDuration() > remaining + 75) {
						LEASES.remove(player.getUUID());
						continue; // A callback installed or extended another source's effect.
					}
				}
				lease.remember();
			}
		}
	}

	private static void clearOwn(String reason) {
		for (Lease lease : LEASES.values()) {
			if (lease.ownsEffect()) lease.player.removeEffect(MobEffects.POISON);
			LOG.info("Host poison lease released for {} ({})", lease.player.getName().getString(), reason);
		}
		LEASES.clear();
	}

	/** SERVER_STOPPING runs before players are saved, so a bridge lease is not persisted. */
	public static void onServerStopping(MinecraftServer server) {
		if (activeServer == server) clearOwn("server stopping");
	}

	public static void reset() {
		LEASES.clear();
		activeServer = null;
		hostLife = Integer.MIN_VALUE;
		effectFrame = renderFrame = Long.MIN_VALUE;
		effectAdvancedAt = renderAdvancedAt = 0;
	}
}
