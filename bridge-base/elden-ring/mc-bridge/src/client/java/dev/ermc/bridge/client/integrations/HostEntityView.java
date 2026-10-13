package dev.ermc.bridge.client.integrations;

import dev.ermc.bridge.TerrainManager;
import dev.ermc.bridge.entity.ErEntity;
import dev.ermc.bridge.link.ErLink;
import java.util.Locale;

/** Shared read-only host semantics for optional clients. Never changes proxy combat health. */
public final class HostEntityView {
    private HostEntityView() {}

    public static boolean available(ErEntity proxy) {
        return TerrainManager.isBridgeWorld() && ErLink.get().alive() && proxy.hostId() != 0L
                && proxy.isAlive() && Float.isFinite(proxy.hp()) && proxy.hp() >= 0
                && Float.isFinite(proxy.maxHp()) && proxy.maxHp() > 0;
    }

    public static boolean targetable(ErEntity proxy) {
        return available(proxy) && proxy.isTargetableEnemy() && proxy.hp() > 0;
    }

    public static float fraction(ErEntity proxy) {
        return available(proxy) ? Math.min(1.0F, proxy.hp() / proxy.maxHp()) : Float.NaN;
    }

    public static String healthText(ErEntity proxy) {
        return available(proxy)
                ? String.format(Locale.ROOT, "%.0f / %.0f ER HP", proxy.hp(), proxy.maxHp()) : "ER HP ?";
    }
}
