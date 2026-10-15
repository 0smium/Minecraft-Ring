package dev.ermc.bridge.api;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.world.entity.player.Player;

/** Optional character rules may suspend deliberate actions without owning physics or damage. */
public final class PlayerActions {
    private PlayerActions() {}
    @FunctionalInterface public interface Gate { boolean allow(Player player); }
    public static final Event<Gate> ALLOW = EventFactory.createArrayBacked(Gate.class, gates -> player -> {
        for (Gate gate : gates) if (!gate.allow(player)) return false;
        return true;
    });
    public static boolean allowed(Player player) { return player != null && ALLOW.invoker().allow(player); }
}
