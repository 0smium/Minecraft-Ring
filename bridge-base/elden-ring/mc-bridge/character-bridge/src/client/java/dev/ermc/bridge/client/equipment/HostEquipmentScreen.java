package dev.ermc.bridge.client.equipment;

import dev.ermc.bridge.character.equipment.HostEquipmentMenu;
import dev.ermc.bridge.character.equipment.HostEquipmentMenus;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/** Standard inventory widgets/tooltips over a server-authoritative ER projection. */
public final class HostEquipmentScreen extends AbstractContainerScreen<HostEquipmentMenu> {
    private Button previous,next;
    private int armorIndex;
    private static final String[] LABELS={"头","身","手","腿","符1","符2","符3","符4"};
    public HostEquipmentScreen(HostEquipmentMenu menu,Inventory inventory,Component title){
        super(menu,inventory,title);imageWidth=234;imageHeight=224;inventoryLabelX=12;inventoryLabelY=130;titleLabelX=12;titleLabelY=6;
    }
    @Override protected void init(){
        super.init();HostItemIcons.reload();
        previous=addRenderableWidget(Button.builder(Component.literal("上一页"),b->page(0)).bounds(leftPos+182,topPos+67,44,18).build());
        next=addRenderableWidget(Button.builder(Component.literal("下一页"),b->page(1)).bounds(leftPos+182,topPos+92,44,18).build());
    }
    private void page(int direction){if(minecraft!=null&&minecraft.gameMode!=null)minecraft.gameMode.handleInventoryButtonClick(menu.containerId,direction);}
    @Override protected void slotClicked(Slot slot,int slotId,int button,ClickType type){
        if(slotId>=0&&slotId<HostEquipmentMenu.GHOST_COUNT){
            if(type!=ClickType.PICKUP||menu.pending()||!menu.getCarried().isEmpty())return;
            if(slotId<4){
                armorIndex=slotId;
                if(button==1)ClientPlayNetworking.send(new HostEquipmentMenus.Intent(menu.containerId,menu.epoch(),armorIndex,true,0,0,0));
            }else if(slotId>=8&&button==0&&slot!=null&&slot.hasItem()){
                var custom=slot.getItem().get(DataComponents.CUSTOM_DATA);
                if(custom==null)return;var tag=custom.copyTag();
                if(!tag.contains("erbridge_view_epoch",3)||!tag.contains("erbridge_inventory_slot",3)||!tag.contains("erbridge_item_handle",3)||!tag.contains("erbridge_item_id",3))return;
                ClientPlayNetworking.send(new HostEquipmentMenus.Intent(menu.containerId,tag.getInt("erbridge_view_epoch"),armorIndex,false,
                    tag.getInt("erbridge_inventory_slot"),tag.getInt("erbridge_item_handle"),tag.getInt("erbridge_item_id")));
            }
            return;
        }
        super.slotClicked(slot,slotId,button,type);
    }
    @Override protected void containerTick(){super.containerTick();previous.active=menu.page()>0&&!menu.pending();next.active=menu.page()+1<menu.pages()&&!menu.pending();}
    @Override public void renderBackground(GuiGraphics g,int x,int y,float delta){} // Keep the composed host scene visible.
    @Override protected void renderBg(GuiGraphics g,float delta,int mouseX,int mouseY){
        g.fill(leftPos,topPos,leftPos+imageWidth,topPos+imageHeight,0xF0C6C6C6);
        g.fill(leftPos+2,topPos+2,leftPos+imageWidth-2,topPos+imageHeight-2,0xE0202930);
        for(int i=0;i<menu.slots.size();i++){
            Slot slot=menu.slots.get(i);int x=leftPos+slot.x,y=topPos+slot.y;
            int color=i==armorIndex?0xFF9B814B:0xFF53606A;
            g.fill(x-1,y-1,x+17,y+17,color);g.fill(x,y,x+16,y+16,0xFF172027);
            if(i>=4&&i<8&&(menu.talismans()==0||i-4>=menu.talismans()))g.drawCenteredString(font,"×",x+8,y+4,0xFFB06767);
        }
    }
    @Override protected void renderLabels(GuiGraphics g,int mouseX,int mouseY){
        g.drawString(font,title,titleLabelX,titleLabelY,0xFFF0D99E,false);
        g.drawString(font,"法环携带装备",12,54,0xFFD0D8DE,false);
        for(int i=0;i<LABELS.length;i++)g.drawString(font,LABELS[i],12+i*22,44,0xFFE8E4D6,false);
        g.drawString(font,"MC 背包",12,130,0xFFD0D8DE,false);g.drawString(font,"MC防具",194,130,0xFFD0D8DE,false);
        g.drawString(font,(menu.page()+1)+"/"+menu.pages(),187,113,0xFFD0D8DE,false);
        g.drawString(font,font.plainSubstrByWidth(menu.statusText(),170),12,118,menu.status()==2?0xFF8CDE96:0xFFF0D99E,false);
    }
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float delta){
        super.render(g,mouseX,mouseY,delta);
        for(int i=0;i<HostEquipmentMenu.GHOST_COUNT;i++){
            Slot slot=menu.slots.get(i);if(slot.hasItem())HostItemIcons.render(g,slot.getItem(),leftPos+slot.x,topPos+slot.y);
        }
        renderTooltip(g,mouseX,mouseY);
        if(mouseX>=leftPos+12&&mouseX<leftPos+180&&mouseY>=topPos+116&&mouseY<topPos+129)
            g.renderTooltip(font,Component.literal(menu.statusText()),mouseX,mouseY);
        for(int i=4;i<8;i++){
            Slot slot=menu.slots.get(i);
            if(!slot.hasItem()&&mouseX>=leftPos+slot.x&&mouseX<leftPos+slot.x+16&&mouseY>=topPos+slot.y&&mouseY<topPos+slot.y+16)
                g.renderTooltip(font,Component.literal(menu.talismans()==0?"尚未读取护符皮袋容量；本版护符只读":i-4>=menu.talismans()?"需要护符皮袋解锁；本版护符只读":"护符槽已解锁；本版护符只读"),mouseX,mouseY);
        }
    }
}
