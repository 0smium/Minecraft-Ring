package dev.ermc.bridge.character;

import dev.ermc.bridge.client.MountClient;
import dev.ermc.bridge.client.ProgressionClient;
import net.fabricmc.api.ClientModInitializer;

/** Character UI uses E -> ER; no additional inventory key competes with LB module bindings. */
public final class CharacterBridgeClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ProgressionClient.initialize();
        MountClient.initialize();
        dev.ermc.bridge.client.equipment.HostEquipmentClient.initialize();
    }
}
