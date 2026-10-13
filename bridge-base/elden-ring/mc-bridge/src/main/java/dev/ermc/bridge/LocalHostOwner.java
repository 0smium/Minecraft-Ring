package dev.ermc.bridge;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** The one integrated-server player represented by this process's local ER connection. */
public final class LocalHostOwner {
    private LocalHostOwner() {}

    /** No fallback to the first LAN player, a display name, or a dedicated server's player list. */
    public static ServerPlayer find(MinecraftServer server) {
        if (server == null || server.isDedicatedServer()) return null;
        var profile = server.getSingleplayerProfile();
        return profile == null ? null : server.getPlayerList().getPlayer(profile.getId());
    }

    /** Object identity also rejects a replaced player instance after respawn or reconnect. */
    public static boolean isOwner(ServerPlayer player) {
        return player != null && find(player.getServer()) == player;
    }
}
