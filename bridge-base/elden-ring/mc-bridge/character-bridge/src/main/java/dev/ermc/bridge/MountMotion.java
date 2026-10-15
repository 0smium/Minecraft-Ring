package dev.ermc.bridge;

import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;

/** Each side selects its own entity; the client receives identity through an S2C payload. */
public final class MountMotion {
    private MountMotion() {}
    private record Identity(UUID owner, UUID horse) {}
    private static Identity clientIdentity;

    public static void acceptClient(UUID owner, UUID horse) { clientIdentity = horse == null ? null : new Identity(owner, horse); }
    public static void clearClient() { clientIdentity = null; }

    public static Entity body(Player player) {
        if (player instanceof ServerPlayer serverPlayer) return MountRuntime.body(serverPlayer);
        Identity identity = clientIdentity;
        if (identity != null && identity.owner().equals(player.getUUID())
                && player.getVehicle() instanceof Horse horse && identity.horse().equals(horse.getUUID())
                && horse.isAlive() && horse.getFirstPassenger() == player) return horse;
        return player;
    }

    public static boolean riding(Player player) { return body(player) != player; }
}
