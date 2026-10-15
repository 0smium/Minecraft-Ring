package dev.ermc.bridge.client;

import dev.ermc.bridge.TerrainManager;
import dev.ermc.bridge.api.BridgeLifecycle;
import dev.ermc.bridge.api.PlayerActions;
import dev.ermc.bridge.api.PlayerFlight;
import dev.ermc.bridge.client.integrations.LiquidBounceCapabilityAccess;
import dev.ermc.bridge.gameplay.runtime.FlightAcknowledgementLease;
import dev.ermc.bridge.gameplay.runtime.PrecisionFlightPayload;
import dev.ermc.bridge.link.ErLink;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** Fixed LB Vanilla movement runs only after the integrated server acknowledges the declaration. */
public final class PrecisionFlightClient {
    private static final FlightAcknowledgementLease LEASE = new FlightAcknowledgementLease();
    private static int lastSentTick;
    private static LocalPlayer subject;
    private PrecisionFlightClient() {}

    public static void initialize() {
        ClientPlayConnectionEvents.INIT.register((handler, mc) -> reset());
        BridgeLifecycle.CLIENT_DISCONNECTED.register(PrecisionFlightClient::reset);
        BridgeLifecycle.CLIENT_CONTROL_RELEASED.register(PrecisionFlightClient::release);
        PlayerFlight.CUSTOM_ACTIVE.register(player -> player == Minecraft.getInstance().player && active());
        ClientPlayNetworking.registerGlobalReceiver(PrecisionFlightPayload.TYPE, (payload, context) -> {
            if (subject == null || context.player() != subject || context.client().player != subject
                    || !payload.owner().equals(subject.getUUID())
                    || payload.hostStart() != ErLink.get().hostStartMs() || payload.hostLife() != ErLink.get().hostLife()) return;
            LEASE.receive(payload.sequence(), payload.hostStart(), payload.hostLife(), payload.active(), System.nanoTime());
        });
        ClientTickEvents.START_CLIENT_TICK.register(PrecisionFlightClient::tick);
    }

    private static boolean wantsFlight(Minecraft mc) {
        return mc.player != null && mc.getSingleplayerServer() != null && TerrainManager.isBridgeWorld()
            && mc.player.isAlive() && !mc.player.isRemoved() && !mc.player.isPassenger() && !mc.player.isSpectator()
            && PlayerActions.allowed(mc.player) && Overlay.active() && !Overlay.hostMode() && !Overlay.hostBusy()
            && CameraSync.mode() == CameraSync.Mode.DRIVE_HOST && !mc.isPaused()
            && LiquidBounceCapabilityAccess.activeFlyMode().equals("Vanilla");
    }

    public static boolean active() {
        Minecraft mc = Minecraft.getInstance();
        return subject == mc.player && LEASE.active(System.nanoTime(), ErLink.get().hostStartMs(), ErLink.get().hostLife())
            && wantsFlight(mc);
    }

    private static void tick(Minecraft mc) {
        if (subject != mc.player) { release(); subject = mc.player; }
        if (!wantsFlight(mc) || !ClientPlayNetworking.canSend(PrecisionFlightPayload.TYPE)) { release(); return; }
        if (!LEASE.requested() || mc.player.tickCount - lastSentTick >= 5 || mc.player.tickCount < lastSentTick) send(true);
        if (active()) mc.player.fallDistance = 0;
    }

    private static void send(boolean active) {
        if (subject == null || Minecraft.getInstance().getConnection() == null
                || !ClientPlayNetworking.canSend(PrecisionFlightPayload.TYPE)) return;
        lastSentTick = subject.tickCount;
        ClientPlayNetworking.send(new PrecisionFlightPayload(subject.getUUID(), LEASE.request(active, ErLink.get().hostStartMs(), ErLink.get().hostLife()),
            ErLink.get().hostStartMs(), ErLink.get().hostLife(), active));
    }
    private static void release() {
        if (LEASE.requested()) send(false);
        LEASE.revoke();
    }
    private static void reset() {
        LEASE.reset(); subject = null; lastSentTick = 0;
    }
}
