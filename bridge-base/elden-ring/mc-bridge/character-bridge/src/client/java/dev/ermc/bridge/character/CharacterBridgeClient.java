package dev.ermc.bridge.character;

import com.mojang.blaze3d.platform.InputConstants;
import dev.ermc.bridge.TerrainManager;
import dev.ermc.bridge.client.HostInventoryScreen;
import dev.ermc.bridge.client.MountClient;
import dev.ermc.bridge.client.Overlay;
import dev.ermc.bridge.client.ProgressionClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** Optional character UI; the registered key identifier remains compatible with existing options. */
public final class CharacterBridgeClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ProgressionClient.initialize();
        MountClient.initialize();
        dev.ermc.bridge.client.equipment.HostEquipmentClient.initialize();
        KeyMapping inventoryKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.erbridge.inventory",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_I, "category.erbridge"));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (inventoryKey.consumeClick()) {
                if (mc.screen == null && mc.player != null && mc.getSingleplayerServer() != null
                        && TerrainManager.isBridgeWorld() && Overlay.active() && !Overlay.hostBusy()) {
                    dev.ermc.bridge.client.equipment.HostEquipmentClient.open();
                }
            }
        });
    }
}
