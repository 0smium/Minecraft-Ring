package dev.ermc.bridge.api;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Ordered extension points owned by Core. Character rules subscribe without Core loading them.
 * Server callbacks run synchronously on the server thread; client callbacks on the client thread.
 * In particular, recall/control-release listeners finish before Core writes a new host pose.
 */
public final class BridgeLifecycle {
    private BridgeLifecycle() {}

    @FunctionalInterface public interface ServerTick { void tick(MinecraftServer server); }
    @FunctionalInterface public interface BeforeRecall { void beforeRecall(ServerPlayer player); }
    @FunctionalInterface public interface PlayerDisconnected { void disconnected(ServerPlayer player); }
    @FunctionalInterface public interface ControlReleased { void released(); }
    @FunctionalInterface public interface ClientDisconnected { void disconnected(); }

    /** Core has initialized its bridge world; independent mod entrypoint order is irrelevant. */
    public static final Event<ServerTick> HOST_WORLD_READY = EventFactory.createArrayBacked(
        ServerTick.class, callbacks -> server -> { for (ServerTick callback : callbacks) callback.tick(server); });

    /** Before terrain sampling/recall; allows a character provider to release an expired subject. */
    public static final Event<ServerTick> BEFORE_TERRAIN_TICK = EventFactory.createArrayBacked(
        ServerTick.class, callbacks -> server -> { for (ServerTick callback : callbacks) callback.tick(server); });

    /** After Core combat and before Core life handling, preserving existing effect/death ordering. */
    public static final Event<ServerTick> AFTER_COMBAT_TICK = EventFactory.createArrayBacked(
        ServerTick.class, callbacks -> server -> { for (ServerTick callback : callbacks) callback.tick(server); });

    /** Immediately before the local owner's host recall moves the player. */
    public static final Event<BeforeRecall> BEFORE_RECALL = EventFactory.createArrayBacked(
        BeforeRecall.class, callbacks -> player -> { for (BeforeRecall callback : callbacks) callback.beforeRecall(player); });

    public static final Event<PlayerDisconnected> PLAYER_DISCONNECTED = EventFactory.createArrayBacked(
        PlayerDisconnected.class, callbacks -> player -> { for (PlayerDisconnected callback : callbacks) callback.disconnected(player); });

    /** F8, host busy or disabled overlay: relinquish character control before releasing the host camera. */
    public static final Event<ControlReleased> CLIENT_CONTROL_RELEASED = EventFactory.createArrayBacked(
        ControlReleased.class, callbacks -> () -> { for (ControlReleased callback : callbacks) callback.released(); });

    public static final Event<ClientDisconnected> CLIENT_DISCONNECTED = EventFactory.createArrayBacked(
        ClientDisconnected.class, callbacks -> () -> { for (ClientDisconnected callback : callbacks) callback.disconnected(); });
}
