package dev.ermc.bridge.api;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Public movement-body service shared by terrain sampling and host pose output.
 * The returned vanilla entity supplies its feet, AABB, step height and grounded state;
 * camera position and player input remain the rider's responsibility.
 * Without a character provider the subject is always the player.
 */
public final class MovementSubjects {
    private MovementSubjects() {}

    @FunctionalInterface
    public interface Provider {
        /** Return the controlled body, or the player/null when this provider does not own a ride. */
        Entity body(Player player);
    }

    private static final CopyOnWriteArrayList<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    /** Register once during mod initialization. Providers resolve each side's own entity instance. */
    public static void register(Provider provider) {
        PROVIDERS.addIfAbsent(Objects.requireNonNull(provider));
    }

    public static Entity body(Player player) {
        Objects.requireNonNull(player);
        for (Provider provider : PROVIDERS) {
            Entity body = provider.body(player);
            if (body != null && body != player && body.level() == player.level()
                    && body.isAlive() && !body.isRemoved()) return body;
        }
        return player;
    }
}
