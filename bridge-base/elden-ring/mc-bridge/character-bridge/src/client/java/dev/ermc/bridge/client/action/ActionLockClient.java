package dev.ermc.bridge.client.action;

import dev.ermc.bridge.api.BridgeLifecycle;
import dev.ermc.bridge.api.PlayerActions;
import dev.ermc.bridge.character.action.ActionLockPayload;
import dev.ermc.bridge.client.PolicyHeartbeatLease;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;

/** Server-owned sleep state. Never infers a restriction from IPC, a potion icon or B01 mode. */
public final class ActionLockClient {
    private static final PolicyHeartbeatLease LEASE = new PolicyHeartbeatLease();
    private static volatile boolean sleeping;
    private static long epoch, sequence;

    private ActionLockClient() {}

    public static void initialize() {
        ClientPlayConnectionEvents.INIT.register((handler, client) -> clear());
        BridgeLifecycle.CLIENT_DISCONNECTED.register(ActionLockClient::clear);
        BridgeLifecycle.CLIENT_CONTROL_RELEASED.register(ActionLockClient::release);
        PlayerActions.ALLOW.register(player -> player != Minecraft.getInstance().player || !blocksActions());
        ClientPlayNetworking.registerGlobalReceiver(ActionLockPayload.TYPE, (payload, context) -> {
            if (context.player() == null || context.player() != context.client().player
                    || context.client().getConnection() != context.player().connection
                    || !context.player().getUUID().equals(payload.owner())
                    || payload.epoch() <= 0 || payload.sequence() <= 0
                    || payload.epoch() < epoch || payload.epoch() == epoch && payload.sequence() <= sequence) return;
            epoch = payload.epoch(); sequence = payload.sequence();
            LEASE.receive(System.nanoTime());
            sleeping = payload.sleeping();
            if (sleeping) stopActiveInput(context.client());
        });
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.player == null || client.level == null) { release(); return; }
            if (LEASE.expired(System.nanoTime(), client.getSingleplayerServer() != null && client.isPaused()))
                sleeping = false;
            if (sleeping) stopActiveInput(client);
        });
    }

    public static boolean blocksActions() { return sleeping; }

    public static void clear() {
        sleeping = false; epoch = sequence = 0; LEASE.reset();
    }

    /** Same connection: keep the sequence watermark so old queued sleep packets cannot revive it. */
    private static void release() { sleeping = false; LEASE.reset(); }

    public static void clearInput(Input input) {
        input.forwardImpulse = input.leftImpulse = 0;
        input.up = input.down = input.left = input.right = false;
        input.jumping = input.shiftKeyDown = false;
    }

    /** Only voluntary controls; external velocity, gravity, collisions and camera remain intact. */
    public static void stopActiveInput(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) return;
        clearInput(player.input);
        player.xxa = player.zza = 0;
        player.setJumping(false);
        player.setSprinting(false);
        player.getAbilities().flying = false;
        // Do not use releaseUsingItem: releasing an already drawn bow could fire it.
        player.stopUsingItem();
        if (client.gameMode != null && client.gameMode.isDestroying()) client.gameMode.stopDestroyBlock();
    }
}
