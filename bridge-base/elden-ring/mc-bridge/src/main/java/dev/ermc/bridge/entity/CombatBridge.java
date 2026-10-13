package dev.ermc.bridge.entity;

import dev.ermc.bridge.CoordMap;
import dev.ermc.bridge.LocalHostOwner;
import dev.ermc.bridge.TerrainManager;
import dev.ermc.bridge.link.ErLink;
import dev.ermc.bridge.link.GameState;
import dev.ermc.bridge.link.Protocol;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Host game -> Minecraft damage. While the Tarnished stands in for the Minecraft player, the
 * DLL reports confirmed incoming hits and their actual host HP loss. A nearby hostile may
 * supply an approximate direction when the attacker hint is unavailable. Actual host effects
 * are translated separately by HostEffectsBridge.
 * <ul>
 *   <li>Each hit is a Minecraft attack by that enemy's proxy (the live enemy proxy nearest the
 *   reported attacker, never an NPC's): a shield raised towards it blocks it, armor reduces
 *   it, it knocks back, and tamed wolves go for the attacker. Its size is a base by attacker
 *   size plus the share of the Tarnished's max HP lost, so it doesn't depend on the host game's
 *   HP scale.</li>
 *   <li>HP loss outside the confirmed hit path is observation data, not an identified effect or an
 *   additional Minecraft damage event.</li>
 * </ul>
 * Creative mode is invulnerable as usual.
 */
public final class CombatBridge {
	private CombatBridge() {
	}

	private static final Logger LOG = LoggerFactory.getLogger("erbridge");
	/** Attack by an enemy with a Minecraft proxy ("slain by c4300"). */
	public static final ResourceKey<DamageType> MONSTER = key("monster");
	/** Attack by an enemy without a proxy nearby ("slain by a monster"). */
	public static final ResourceKey<DamageType> MONSTER_HIT = key("monster_hit");
	/** Confirmed physical hit with no trustworthy direction; preserve armor without random knockback. */
	public static final ResourceKey<DamageType> MONSTER_UNDIRECTED = key("monster_undirected");
	private static final float[] EV = new float[9];
	private static final GameState TRACE_STATE = new GameState();
	/** Minecraft damage per host-game hit, before Minecraft armor: a base by attacker size plus a share of the hit. */
	private static final float SMALL_BASE = 3.0F;
	private static final float LARGE_BASE = 6.0F;
	private static final float PROPORTIONAL = 30.0F; // x (host-game damage / the Tarnished's max HP)
	/** The DLL's attacker position is the enemy's feet; its proxy's box is searched this far around it. */
	private static final double BLAME_RADIUS = 32.0;
	private static int lastHits = -1;
	private static float lastTotal;
	private static float lastUnattributedTotal;
	private static MinecraftServer activeServer;
	private static ServerPlayer activePlayer;
	private static long activeHostStart;

	private static ResourceKey<DamageType> key(String name) {
		return ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath("erbridge", name));
	}

	private static Holder<DamageType> type(ServerLevel level, ResourceKey<DamageType> key) {
		return level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(key);
	}

	public static void reset() {
		lastHits = -1;
		lastTotal = lastUnattributedTotal = 0;
		activeServer = null;
		activePlayer = null;
		activeHostStart = 0;
	}

	public static void onServerTick(MinecraftServer server) {
		ErLink link = ErLink.get();
		ServerPlayer player = LocalHostOwner.find(server);
		long hostStart = link.hostStartMs();
		if (activeServer != server || activePlayer != player || activeHostStart != hostStart) {
			reset();
			activeServer = server;
			activePlayer = player;
			activeHostStart = hostStart;
		}
		if (!TerrainManager.isBridgeWorld() || player == null || !player.isAlive()
			|| player.serverLevel() != server.overworld() || !link.alive() || hostStart <= 0
			|| !link.readHunterEvents(EV)) {
			lastHits = -1; // Returning owners/hosts start at a fresh baseline; never replay absence.
			return;
		}
		int hits = (int) EV[0];
		float total = EV[1];
		float unattributedTotal = EV[8];
		if (hits < 0 || !Float.isFinite(total) || !Float.isFinite(unattributedTotal)
			|| total < 0 || unattributedTotal < 0) {
			lastHits = -1;
			return;
		}
		if (lastHits < 0 || hits < lastHits || total < lastTotal || unattributedTotal < lastUnattributedTotal) {
			// First read or a reset counter: only count damage from now on.
			lastHits = hits;
			lastTotal = total;
			lastUnattributedTotal = unattributedTotal;
			return;
		}
		float maxHp = EV[6];  // the Tarnished's max HP; 0 until the DLL reported a hit
		ServerLevel level = server.overworld();
		if (hits != lastHits) {
			float hostDamage = total - lastTotal;
			lastHits = hits;
			lastTotal = total;
			CoordMap.Mapping map = CoordMap.get();
			int reportedKind = (int) EV[7];
			boolean hasDirection = reportedKind == Protocol.ENT_SMALL_MONSTER || reportedKind == Protocol.ENT_LARGE_MONSTER;
			Vec3 from = map != null && hasDirection ? map.toMc(EV[3], EV[4], EV[5]) : null;
			ErEntity attacker = from != null ? EntityBridge.nearestEnemy(from, BLAME_RADIUS) : null;
			// The host game's damage after the Tarnished's armor says little in Minecraft terms, so
			// scale: small enemies hit like a zombie, bosses much harder.
			int kind = attacker != null ? attacker.kind() : reportedKind;
			float share = maxHp > 0 ? hostDamage / maxHp : 0.0F;
			float amount = Math.min(20.0F, (kind == Protocol.ENT_SMALL_MONSTER ? SMALL_BASE : LARGE_BASE) + share * PROPORTIONAL);
			hit(level, player, from, attacker, amount, hostDamage, share);
		}

		float loss = Math.max(0.0F, unattributedTotal - lastUnattributedTotal);
		lastUnattributedTotal = unattributedTotal;
		if (loss > 0) {
			LOG.info("[host-effects] observed {} ER HP loss outside confirmed incoming hits; not applied as Minecraft damage", loss);
		}
	}

	private static void hit(ServerLevel level, ServerPlayer player, Vec3 from, ErEntity attacker, float amount, float hostDamage,
							float share) {
		DamageSource source;
		if (attacker != null) {
			source = new DamageSource(type(level, MONSTER), attacker);
		} else if (from != null) {
			source = new DamageSource(type(level, MONSTER_HIT), from);
		} else {
			source = new DamageSource(type(level, MONSTER_UNDIRECTED));
		}
		// Blocking needs to know where the blow came from: the attacker's position.
		float beforeHealth = player.getHealth(), beforeAbsorption = player.getAbsorptionAmount();
		Vec3 beforePosition = player.position(), beforeVelocity = player.getDeltaMovement();
		boolean blocked = player.isDamageSourceBlocked(source);
		boolean hurt = player.hurt(source, amount);
		// Minecraft already knocks back by 0.4 away from the attacker; big host-game blows push
		// harder, unless the shield took them.
		if (hurt && !blocked && from != null) {
			double dx = from.x - player.getX();
			double dz = from.z - player.getZ();
			double extra = Math.min(1.1, share * 3.0);
			if (extra > 0.05 && dx * dx + dz * dz > 1e-4) {
				player.knockback(extra, dx, dz);
				player.hurtMarked = true;
			}
		}
		LOG.info("Elden Ring hit {} for {} HP ({}% of max) -> {} Minecraft damage from {}{}", player.getName().getString(), hostDamage,
			Math.round(share * 100.0F), amount, attacker != null ? attacker.getName().getString() : "?",
			blocked ? " (blocked by shield)" : hurt ? "" : " (ignored: creative/invulnerable)");
		LOG.info("[combat-baseline] server hurt tick {} hostHitCount {}: actualHP {} -> {} loss {}; absorption {} -> {}; hurt {} blocked {}; pos {} -> {} velocity {} -> {}; {}",
			player.tickCount, lastHits, beforeHealth, player.getHealth(), beforeHealth - player.getHealth(),
			beforeAbsorption, player.getAbsorptionAmount(), hurt, blocked, beforePosition, player.position(),
			beforeVelocity, player.getDeltaMovement(), publishedHostPosition());
	}

	/** Client observation requests enqueue this read on the integrated server thread. */
	public static void tracePositionWindow(MinecraftServer server, java.util.UUID playerId, int receiptTick, int offset) {
		ServerPlayer player = server.getPlayerList().getPlayer(playerId);
		if (player == null || !TerrainManager.isBridgeWorld()) return;
		LOG.info("[combat-baseline] server window receiptTick {} offset {} actualTick {}: actualHP {} absorption {} dimension {} pos {} velocity {}; {}",
			receiptTick, offset, player.tickCount, player.getHealth(), player.getAbsorptionAmount(),
			player.level().dimension().location(), player.position(), player.getDeltaMovement(), publishedHostPosition());
	}

	private static String publishedHostPosition() {
		if (!ErLink.get().snapshot(TRACE_STATE) || !TRACE_STATE.has(Protocol.STATE_PLAYER_VALID)) return "ER published position unavailable";
		return "ER published frame " + TRACE_STATE.frame + " stage " + TRACE_STATE.stageId + " localXYZ "
			+ new Vec3(TRACE_STATE.playerPos[0], TRACE_STATE.playerPos[1], TRACE_STATE.playerPos[2]);
	}
}
