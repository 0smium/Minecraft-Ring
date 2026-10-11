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
	private MovingPlatformClient() {}

	private static void clearCarry() { carriedPlayer = null; carriedY = 0; renderCarry = false; }
	public static void onTeleport() { MOTION.reset(); BOARDING.reset(); clearCarry(); }

	public static void tick(Minecraft mc) {
		clearCarry();
		var map = CoordMap.get();
		if (mc.player == null || mc.level == null || !TerrainManager.isBridgeWorld() || !Overlay.active() || Overlay.hostMode()
			|| !TerrainManager.recallSettled(400) || map == null || !ErLink.get().snapshot(STATE) || map.zone() != STATE.stageId) {
			MOTION.reset(); return;
		}
		BOARDING.refresh(mc.level, map, null);
		var support = map.toMc(STATE.supportPos[0], STATE.supportPos[1], STATE.supportPos[2]);
		boolean valid = STATE.has(Protocol.STATE_SUPPORT_VALID)
			&& Math.abs(mc.player.getX() - support.x) < .6 && Math.abs(mc.player.getZ() - support.z) < .6;
		int previousEpoch = MOTION.epoch();
		var step = MOTION.update(valid, STATE.supportEpoch, STATE.supportTravelY / map.unitsPerMeter(), support.y,
			mc.player.getY(), mc.player.onGround(), mc.player.getAbilities().flying, mc.player.getDeltaMovement().y, System.currentTimeMillis());
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
		if (step.moving() && System.currentTimeMillis() - loggedAt >= 1000) {
			loggedAt = System.currentTimeMillis();
			org.slf4j.LoggerFactory.getLogger("erbridge").info("Moving platform: epoch {} travel {} m, floor {} feet {} grounded {}",
				MOTION.epoch(), MOTION.travel(), step.floor(), mc.player.getY(), mc.player.onGround());
		}
	}

	/** Keep START's physical carry, but restore its old render endpoint after the vanilla tick. */
	public static void afterTick(Minecraft mc) {
		if (carriedPlayer == null) return;
		if (mc.player != carriedPlayer || mc.player.tickCount != carriedTick + 1
			|| MOTION.epoch() != carriedEpoch || mc.player.getAbilities().flying
			|| !Overlay.active() || Overlay.hostMode()) {
			clearCarry(); return;
		}
		// ClientLevel.setOldPosAndRot copied the already-carried feet to both fields.
		// Undo only that copy; the current position, collision and movement packet stay intact.
		mc.player.yo -= carriedY;
		mc.player.yOld -= carriedY;
		renderCarry = true;
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
