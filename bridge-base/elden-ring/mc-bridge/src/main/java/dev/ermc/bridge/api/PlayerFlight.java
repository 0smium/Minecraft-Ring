package dev.ermc.bridge.api;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.world.entity.player.Player;

/** Classifies authorized custom flight without changing vanilla's travel algorithm. */
public final class PlayerFlight {
    private PlayerFlight() {}
    @FunctionalInterface public interface Activity { boolean active(Player player); }
    public static final Event<Activity> CUSTOM_ACTIVE = EventFactory.createArrayBacked(Activity.class, providers -> player -> {
        for (Activity provider : providers) if (provider.active(player)) return true;
        return false;
    });
    public static boolean customActive(Player player) {
        return player != null && player.isAlive() && !player.isRemoved() && CUSTOM_ACTIVE.invoker().active(player);
    }
    public static boolean isFlying(Player player) {
        return player != null && (player.getAbilities().flying || customActive(player));
    }
}
