package dev.ermc.bridge.client;

import dev.ermc.bridge.CoordMap;
import dev.ermc.bridge.MovingPlatformMotion;
import dev.ermc.bridge.MovingPlatformTerrain;
import dev.ermc.bridge.MovingPlatformBoarding;
import dev.ermc.bridge.TerrainManager;
import dev.ermc.bridge.link.ControlState;
import dev.ermc.bridge.link.ErLink;
import dev.ermc.bridge.link.GameState;
import dev.ermc.bridge.link.Protocol;
import net.minecraft.client.Minecraft;

/** Predict lift motion locally; normal movement packets carry the resulting feet to the server. */
public final class MovingPlatformClient {
	private static final MovingPlatformMotion MOTION = new MovingPlatformMotion();
	private static final MovingPlatformBoarding BOARDING = new MovingPlatformBoarding();
	private static final GameState STATE = new GameState();
	private static long loggedAt;
	private static net.minecraft.client.player.LocalPlayer carriedPlayer;
	private static int carriedEpoch, carriedTick;
	private static double carriedY;
	private static boolean renderCarry;
	private static final org.slf4j.Logger TRACE = org.slf4j.LoggerFactory.getLogger("erbridge");
	private static long traceUntil;
	private static boolean traceTick;
	private static int traceTickId;
	private MovingPlatformClient() {}

	private static void clearCarry() { carriedPlayer = null; carriedY = 0; renderCarry = false; }
	public static void onTeleport() {
		long now = System.currentTimeMillis();
		var player = Minecraft.getInstance().player;
		if (player != null && (MOTION.moving(now) || now < traceUntil)) {
			TRACE.info("[platform-trace] position packet reset tick {}: x {} y {} z {} ground {} vy {}; epoch {} travel {} renderCarry {} carriedY {}",
				player.tickCount, player.getX(), player.getY(), player.getZ(), player.onGround(), player.getDeltaMovement().y,
				MOTION.epoch(), MOTION.travel(), renderCarry, carriedY);
		}
		MOTION.reset(); BOARDING.reset(); clearCarry();
	}

	public static void tick(Minecraft mc) {
		clearCarry();
		traceTick = false;
		long now = System.currentTimeMillis();
		int startEpoch = MOTION.epoch();
		boolean wasMoving = MOTION.moving(now);
		var map = CoordMap.get();
		if (mc.player == null || mc.level == null || mc.player.isPassenger() || !TerrainManager.isBridgeWorld() || !Overlay.active() || Overlay.hostMode()
			|| !TerrainManager.recallSettled(400) || map == null || !ErLink.get().snapshot(STATE) || map.zone() != STATE.stageId) {
			MOTION.reset(); BOARDING.reset();
			if (startEpoch != 0 && (wasMoving || now < traceUntil) && mc.player != null && mc.level != null) {
				traceTick = true; traceTickId = mc.player.tickCount;
				TRACE.info("[platform-trace] client START tick {} gate unavailable: y {} yo {} ground {} vy {} shapeTop {} epoch {} -> {} reset {}",
					traceTickId, mc.player.getY(), mc.player.yo, mc.player.onGround(), mc.player.getDeltaMovement().y,
					TerrainManager.platformTraceShapeTop(mc.level, mc.player), startEpoch, MOTION.epoch(), MOTION.resetReason());
			}
			return;
		}
		BOARDING.refresh(mc.level, map, null);
		var support = map.toMc(STATE.supportPos[0], STATE.supportPos[1], STATE.supportPos[2]);
		boolean valid = STATE.has(Protocol.STATE_SUPPORT_VALID)
			&& Math.abs(mc.player.getX() - support.x) < .6 && Math.abs(mc.player.getZ() - support.z) < .6;
		int previousEpoch = MOTION.epoch();
		double startY = mc.player.getY(), startYo = mc.player.yo, startVy = mc.player.getDeltaMovement().y;
		boolean startGround = mc.player.onGround();
		var step = MOTION.update(valid, STATE.supportEpoch, STATE.supportTravelY / map.unitsPerMeter(), support.y,
			mc.player.getY(), mc.player.onGround(), mc.player.getAbilities().flying, mc.player.getDeltaMovement().y, System.currentTimeMillis());
		if (wasMoving || step.moving()) traceUntil = now + 750;
		boolean resetContact = previousEpoch != 0 && previousEpoch != MOTION.epoch();
		traceTick = now < traceUntil && (mc.player.tickCount % 5 == 0 || resetContact);
		if (traceTick) {
			traceTickId = mc.player.tickCount;
			TRACE.info("[platform-trace] client START tick {}: y {} yo {} ground {} vy {} shapeTop {}; valid {} nativeEpoch {} floor {} travel {}; epoch {} -> {} reset {}; carry {} landed {} moving {}",
				traceTickId, startY, startYo, startGround, startVy, TerrainManager.platformTraceShapeTop(mc.level, mc.player),
				valid, STATE.supportEpoch, support.y, STATE.supportTravelY / map.unitsPerMeter(), previousEpoch, MOTION.epoch(),
				MOTION.resetReason(), step.dy(), step.landed(), step.moving());
		}
		if (step.moving()) MovingPlatformTerrain.move(mc.level, mc.player.position(), step.previousFloor(), step.floor());
		if (step.dy() != 0 || step.landed()) {
			double beforeY = mc.player.getY();
			mc.player.setPos(mc.player.getX(), MovingPlatformMotion.collisionHeight(step.floor()), mc.player.getZ());
			mc.player.setOnGround(true);
			mc.player.fallDistance = 0;
			if (step.landed()) mc.player.setDeltaMovement(mc.player.getDeltaMovement().multiply(1, 0, 1));
			if (previousEpoch != 0 && previousEpoch == MOTION.epoch()
				&& (!step.landed() || Math.abs(beforeY - MovingPlatformMotion.collisionHeight(step.previousFloor())) <= .125)) {
				carriedPlayer = mc.player;
				carriedEpoch = previousEpoch;
				carriedTick = mc.player.tickCount;
				carriedY = mc.player.getY() - beforeY;
			}
		}
		if (traceTick) TRACE.info("[platform-trace] client carried tick {}: xyz {} {} {} targetTop {} shapeTop {} actualCarry {}",
			traceTickId, mc.player.getX(), mc.player.getY(), mc.player.getZ(), MovingPlatformMotion.collisionHeight(step.floor()),
			TerrainManager.platformTraceShapeTop(mc.level, mc.player), carriedY);
		if (step.moving() && System.currentTimeMillis() - loggedAt >= 1000) {
			loggedAt = System.currentTimeMillis();
			org.slf4j.LoggerFactory.getLogger("erbridge").info("Moving platform: epoch {} travel {} m, floor {} feet {} grounded {}",
				MOTION.epoch(), MOTION.travel(), step.floor(), mc.player.getY(), mc.player.onGround());
		}
	}

	public static boolean tracingPhysics(net.minecraft.client.player.LocalPlayer player) {
		return traceTick && player == Minecraft.getInstance().player && MOTION.epoch() != 0
			&& Overlay.active() && !Overlay.hostMode();
	}

	/** Observe vanilla movement without changing its vector, position or collision. */
	public static void tracePhysics(net.minecraft.client.player.LocalPlayer player,
		net.minecraft.world.entity.MoverType type, net.minecraft.world.phys.Vec3 movement, String phase) {
		TRACE.info("[platform-trace] client physics {} startTick {} tick {}: type {} request {}; xyz {} {} {} ground {} vy {} shapeTop {} floor {} epoch {}",
			phase, traceTickId, player.tickCount, type, movement, player.getX(), player.getY(), player.getZ(),
			player.onGround(), player.getDeltaMovement().y, TerrainManager.platformTraceShapeTop(player.level(), player), MOTION.floor(), MOTION.epoch());
	}

	/** Keep START's physical carry, but restore its old render endpoint after the vanilla tick. */
	public static void afterTick(Minecraft mc) {
		boolean applied = false;
		if (carriedPlayer != null) {
			if (mc.player != carriedPlayer || mc.player.isPassenger() || mc.player.tickCount != carriedTick + 1
				|| MOTION.epoch() != carriedEpoch || mc.player.getAbilities().flying
				|| !Overlay.active() || Overlay.hostMode()) {
				clearCarry();
			} else {
				// ClientLevel.setOldPosAndRot copied the already-carried feet to both fields.
				// Undo only that copy; the current position, collision and movement packet stay intact.
				mc.player.yo -= carriedY;
				mc.player.yOld -= carriedY;
				renderCarry = true;
				applied = true;
			}
		}
		if (traceTick && mc.player != null && mc.level != null) {
			TRACE.info("[platform-trace] client END startTick {} tick {}: y {} yo {} ground {} vy {} shapeTop {} epoch {} renderApplied {} renderCarry {} carriedY {}",
				traceTickId, mc.player.tickCount, mc.player.getY(), mc.player.yo, mc.player.onGround(), mc.player.getDeltaMovement().y,
				TerrainManager.platformTraceShapeTop(mc.level, mc.player), MOTION.epoch(), applied, renderCarry, carriedY);
		}
	}

	public static void copyControl(ControlState control, float partialTick) {
		control.supportEpoch = MOTION.epoch();
		var map = CoordMap.get();
		// CameraSync interpolates yo -> Y using this same partial tick. Acknowledge
		// exactly the rendered part of the actual carry, including collision quantization.
		double remaining = renderCarry && control.supportEpoch == carriedEpoch
			? (1 - Math.max(0, Math.min(1, partialTick))) * carriedY : 0;
		control.supportTravelY = (float)((MOTION.travel() - remaining) * (map == null ? 1 : map.unitsPerMeter()));
	}
}
