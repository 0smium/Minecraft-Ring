package dev.ermc.bridge.client;

import dev.ermc.bridge.api.BridgeLifecycle;
import dev.ermc.bridge.MountMotion;
import dev.ermc.bridge.MountPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class MountClient {
    private static boolean riding;
    private MountClient() {}
    public static void initialize() {
        ClientPlayConnectionEvents.INIT.register((handler, mc) -> reset());
        BridgeLifecycle.CLIENT_DISCONNECTED.register(MountClient::reset);
        BridgeLifecycle.CLIENT_CONTROL_RELEASED.register(MountClient::releaseControl);
        ClientPlayNetworking.registerGlobalReceiver(MountPayload.TYPE, (payload, context) -> {
            if (!context.player().getUUID().equals(payload.owner())) return;
            MountMotion.acceptClient(payload.owner(), payload.horse());
            riding = payload.horse() != null;
        });
    }
    public static void releaseControl() {
        if (riding && ClientPlayNetworking.canSend(MountPayload.Release.TYPE)) {
            ClientPlayNetworking.send(MountPayload.Release.INSTANCE);
            riding = false;
        }
    }
    private static void reset() { riding = false; MountMotion.clearClient(); }
}
