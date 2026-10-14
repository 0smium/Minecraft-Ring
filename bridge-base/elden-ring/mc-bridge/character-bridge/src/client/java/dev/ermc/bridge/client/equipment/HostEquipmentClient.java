package dev.ermc.bridge.client.equipment;

import dev.ermc.bridge.TerrainManager;
import dev.ermc.bridge.character.equipment.HostEquipmentMenus;
import dev.ermc.bridge.client.Overlay;
import dev.ermc.bridge.client.equipment.mixin.ContainerScreenAccessor;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

public final class HostEquipmentClient {
    private HostEquipmentClient(){}
    public static void initialize(){
        MenuScreens.register(HostEquipmentMenus.TYPE,HostEquipmentScreen::new);
        ScreenEvents.AFTER_INIT.register((mc,screen,width,height)->{
            if(screen instanceof InventoryScreen&&mc.getSingleplayerServer()!=null&&TerrainManager.isBridgeWorld()){
                var origin=(ContainerScreenAccessor)screen;
                Screens.getButtons(screen).add(Button.builder(Component.literal("ER"),button->open())
                    .bounds(origin.erbridge$leftPos()+77,origin.erbridge$topPos()+8,20,16).build());
            }
        });
    }
    public static void open(){
        Minecraft mc=Minecraft.getInstance();
        if(mc.player!=null&&mc.player.containerMenu.getCarried().isEmpty()&&mc.getSingleplayerServer()!=null&&TerrainManager.isBridgeWorld()&&Overlay.active()&&!Overlay.hostBusy()
                &&ClientPlayNetworking.canSend(HostEquipmentMenus.Open.TYPE))ClientPlayNetworking.send(HostEquipmentMenus.Open.INSTANCE);
    }
}
