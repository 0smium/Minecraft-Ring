package dev.ermc.bridge.character;

import dev.ermc.bridge.HostEffectsBridge;
import dev.ermc.bridge.MountRuntime;
import dev.ermc.bridge.api.BridgeLifecycle;
import dev.ermc.bridge.gameplay.runtime.B01Runtime;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/** Character use cases and gameplay rules, loaded independently of the transport/rendering core. */
public final class CharacterBridgeMod implements ModInitializer {
    public static final String MOD_ID = "er_character_bridge";

    @Override
    public void onInitialize() {
        MountRuntime.initialize();
        ServerLifecycleEvents.SERVER_STOPPING.register(HostEffectsBridge::onServerStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> HostEffectsBridge.reset());
        // Preserve Combat -> effects -> Life ordering without a Core dependency on this mod.
        BridgeLifecycle.AFTER_COMBAT_TICK.register(HostEffectsBridge::onServerTick);
        B01Runtime.initialize();
    }
}
