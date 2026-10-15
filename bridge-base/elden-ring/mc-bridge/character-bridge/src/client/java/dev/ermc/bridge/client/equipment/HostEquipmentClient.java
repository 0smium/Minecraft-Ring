package dev.ermc.bridge.client.equipment;

import dev.ermc.bridge.TerrainManager;
import dev.ermc.bridge.character.equipment.HostEquipmentMenus;
import dev.ermc.bridge.client.Overlay;
import dev.ermc.bridge.client.equipment.mixin.ContainerScreenAccessor;
import dev.ermc.bridge.client.equipment.mixin.InventoryScreenAccessor;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

public final class HostEquipmentClient {
    private HostEquipmentClient(){}
    public static void initialize(){
        MenuScreens.register(HostEquipmentMenus.TYPE,HostEquipmentScreen::new);
        ScreenEvents.AFTER_INIT.register((mc,screen,width,height)->{
            if((screen instanceof InventoryScreen||screen instanceof CreativeModeInventoryScreen)
                    &&mc.getSingleplayerServer()!=null&&TerrainManager.isBridgeWorld()){
                var origin=(ContainerScreenAccessor)screen;
                var entry=Button.builder(Component.literal("ER"),button->open())
                    .tooltip(Tooltip.create(Component.literal("法环装备与物品 · 请先放回鼠标拿着的物品")))
                    .bounds(0,0,20,16).build();
                Screens.getButtons(screen).add(entry);
                updateEntry(screen,origin,entry);
                // The recipe button changes leftPos directly, without reinitializing this screen.
                ScreenEvents.beforeRender(screen).register((current,graphics,x,y,delta)->updateEntry(current,origin,entry));
                ScreenEvents.afterTick(screen).register(current->updateEntry(current,origin,entry));
            }
        });
    }
    private static void updateEntry(Screen screen,ContainerScreenAccessor origin,Button entry){
        entry.setPosition(origin.erbridge$leftPos()+(screen instanceof InventoryScreen?77:199),origin.erbridge$topPos()+8);
        // Vanilla suppresses the inventory itself while the recipe book covers it in a narrow viewport.
        entry.visible=!(screen instanceof InventoryScreen inventory
            &&((InventoryScreenAccessor)inventory).erbridge$widthTooNarrow()&&inventory.getRecipeBookComponent().isVisible());
    }
    public static void open(){
        Minecraft mc=Minecraft.getInstance();
        if(!(mc.screen instanceof AbstractContainerScreen<?> screen)||!screen.getMenu().getCarried().isEmpty())return;
        if(mc.player!=null&&mc.player.containerMenu.getCarried().isEmpty()&&mc.getSingleplayerServer()!=null&&TerrainManager.isBridgeWorld()&&Overlay.active()&&!Overlay.hostBusy()
                &&ClientPlayNetworking.canSend(HostEquipmentMenus.Open.TYPE))ClientPlayNetworking.send(HostEquipmentMenus.Open.INSTANCE);
    }
}
