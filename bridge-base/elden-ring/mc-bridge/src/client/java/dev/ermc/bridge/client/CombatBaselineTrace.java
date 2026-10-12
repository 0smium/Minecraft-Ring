package dev.ermc.bridge.client;

import dev.ermc.bridge.TerrainManager;
import dev.ermc.bridge.entity.CombatBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Local END-tick receipts, not a count or attribution of individual hurt events. */
public final class CombatBaselineTrace {
    private static final Logger LOG = LoggerFactory.getLogger("erbridge");
    private static LocalPlayer observed;
    private static ClientLevel world;
    private static float previousHealth;
    private static Vec3 previousPosition;
    private static int previousTick, receiptTick = -1;
    private static boolean sampledFive;
    private CombatBaselineTrace() {}

    public static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null || !TerrainManager.isBridgeWorld()
                || !Overlay.active() || Overlay.hostMode()) {
            observed = null; world = null; previousPosition = null; receiptTick = -1;
            return;
        }
        LocalPlayer player = mc.player;
        float health = player.getHealth();
        if (observed != player || world != mc.level || player.tickCount < previousTick) {
            observed = player; world = mc.level; receiptTick = -1;
            previousHealth = health; previousPosition = player.position(); previousTick = player.tickCount;
            return;
        }
        if (health < previousHealth) {
            String window = receiptTick < 0 ? "started" : "restarted/overwrites receipt tick " + receiptTick;
            LOG.info("[combat-baseline] health-drop receipt END tick {}: health {} -> {} absorption {}; previous END tick {} pos {}; current pos {} raw deltaMovement {}; window {} (not an individual hurt count)",
                player.tickCount, previousHealth, health, player.getAbsorptionAmount(), previousTick,
                previousPosition, player.position(), player.getDeltaMovement(), window);
            receiptTick = player.tickCount; sampledFive = false;
            serverSample(mc, player, 0);
        } else if (health > previousHealth) {
            receiptTick = -1; // Healing invalidates the pending observation window.
        }
        if (receiptTick >= 0) {
            int elapsed = player.tickCount - receiptTick;
            if (elapsed >= 5 && !sampledFive) {
                sample(mc, player, 5, elapsed); sampledFive = true;
            }
            if (elapsed >= 20) {
                sample(mc, player, 20, elapsed); receiptTick = -1;
            }
        }
        previousHealth = health; previousPosition = player.position(); previousTick = player.tickCount;
    }

    private static void sample(Minecraft mc, LocalPlayer player, int target, int elapsed) {
        LOG.info("[combat-baseline] health-drop window +{} client ticks (observed +{}) END tick {} receipt tick {}: health {} absorption {} pos {} raw deltaMovement {}",
            target, elapsed, player.tickCount, receiptTick, player.getHealth(), player.getAbsorptionAmount(),
            player.position(), player.getDeltaMovement());
        serverSample(mc, player, target);
    }

    private static void serverSample(Minecraft mc, LocalPlayer player, int offset) {
        var server = mc.getSingleplayerServer();
        if (server == null) return;
        var playerId = player.getUUID();
        int capturedReceiptTick = receiptTick;
        server.execute(() -> CombatBridge.tracePositionWindow(server, playerId, capturedReceiptTick, offset));
    }
}
