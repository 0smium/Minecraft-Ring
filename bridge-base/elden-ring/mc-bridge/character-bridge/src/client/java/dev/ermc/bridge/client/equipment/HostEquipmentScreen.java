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
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;

/** Vanilla container/slots/tooltips; dragging expresses an intent, never a carried MC item. */
public final class HostEquipmentScreen extends AbstractContainerScreen<HostEquipmentMenu> {
    private Button previous,next;
    private final List<Button> filters=new ArrayList<>();
    private int equipmentIndex;
    private double viewScale=1,pressX,pressY;
    private Gesture gesture;
    private boolean dragged;
    private String hint;
    private static final String[] LABELS={"头部","上身","手部","腿部","护符1","护符2","护符3","护符4"};
    private static final String[] FILTERS={"全部","防具","护符","武器","道具"};
    private record Gesture(int slotIndex, ItemStack observed, int epoch) {}

    public HostEquipmentScreen(HostEquipmentMenu menu,Inventory inventory,Component title){
        super(menu,inventory,title);imageWidth=308;imageHeight=278;titleLabelX=14;titleLabelY=8;
    }
    @Override protected void init(){
        super.init();HostItemIcons.reload();gesture=null;filters.clear();
        // Keep the same server slot coordinates while fitting smaller GUI-scale viewports.
        viewScale=Math.min(1,Math.min((width-12.0)/imageWidth,(height-12.0)/imageHeight));
        leftPos=(int)((width/viewScale-imageWidth)/2);topPos=(int)((height/viewScale-imageHeight)/2);
        for(int i=0;i<FILTERS.length;i++){
            final int filter=i;
            filters.add(addRenderableWidget(Button.builder(Component.literal(FILTERS[i]),b->page(10+filter))
                .bounds(leftPos+14+i*56,topPos+72,52,18).build()));
        }
        previous=addRenderableWidget(Button.builder(Component.literal("<"),b->page(0)).bounds(leftPos+210,topPos+238,22,18).build());
        next=addRenderableWidget(Button.builder(Component.literal(">"),b->page(1)).bounds(leftPos+272,topPos+238,22,18).build());
    }
    private void page(int action){
        gesture=null;hint=null;
        if(minecraft!=null&&minecraft.gameMode!=null)minecraft.gameMode.handleInventoryButtonClick(menu.containerId,action);
    }
    private Slot slotAt(double x,double y){
        for(Slot slot:menu.slots)if(super.isHovering(slot.x,slot.y,HostEquipmentMenu.ICON_SIZE,HostEquipmentMenu.ICON_SIZE,x,y))return slot;
        return null;
    }
    private boolean inList(double x,double y){
        return super.isHovering(HostEquipmentMenu.GRID_X,HostEquipmentMenu.GRID_Y,
            HostEquipmentMenu.COLUMNS*HostEquipmentMenu.CELL_SIZE,HostEquipmentMenu.ROWS*HostEquipmentMenu.CELL_SIZE,x,y);
    }
    @Override protected boolean isHovering(int x,int y,int w,int h,double mouseX,double mouseY){
        if(w==16&&h==16){w=HostEquipmentMenu.ICON_SIZE;h=HostEquipmentMenu.ICON_SIZE;}
        return super.isHovering(x,y,w,h,mouseX,mouseY);
    }
    @Override public boolean mouseClicked(double screenX,double screenY,int button){
        double x=screenX/viewScale,y=screenY/viewScale;Slot slot=slotAt(x,y);
        hint=null;
        if(slot!=null){
            if(menu.pending()||!menu.getCarried().isEmpty())return true;
            if(slot.index<8)equipmentIndex=slot.index;
            if(button==1){
                if(slot.index<8&&slot.hasItem())send(slot.getItem(),menu.epoch(),slot.index,true);
                else if(slot.index<8)hint=slotHint(slot.index);
                else hint="本类物品暂只读；丢弃尚未接入法环原生流程";
            }else if(button==0&&slot.hasItem()){
                gesture=new Gesture(slot.index,slot.getItem().copy(),menu.epoch());pressX=x;pressY=y;dragged=false;
            }
            return true;
        }
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseDragged(double screenX,double screenY,int button,double dx,double dy){
        double x=screenX/viewScale,y=screenY/viewScale;
        if(gesture!=null){dragged|=Math.hypot(x-pressX,y-pressY)>=4;return true;}
        return super.mouseDragged(x,y,button,dx/viewScale,dy/viewScale);
    }
    @Override public boolean mouseReleased(double screenX,double screenY,int button){
        double x=screenX/viewScale,y=screenY/viewScale;
        if(gesture!=null){
            Gesture captured=gesture;gesture=null;
            if(button!=0||menu.pending())return true;
            if(captured.epoch()!=menu.epoch()){hint="背包已刷新，请按新画面重新拖动";return true;}
            Slot target=slotAt(x,y);
            if(dragged){
                if(captured.slotIndex()>=8&&target!=null&&target.index<8){
                    equipmentIndex=target.index;send(captured.observed(),captured.epoch(),equipmentIndex,false);
                }else if(captured.slotIndex()<8&&inList(x,y)){
                    send(captured.observed(),captured.epoch(),captured.slotIndex(),true);
                }else hint="拖至对应防具或护符槽装配；拖回列表卸下。移出页面不会丢弃物品";
            }else if(captured.slotIndex()>=8)send(captured.observed(),captured.epoch(),equipmentIndex,false);
            else hint=slotHint(captured.slotIndex());
            return true;
        }
        return super.mouseReleased(x,y,button);
    }
    @Override public void mouseMoved(double x,double y){super.mouseMoved(x/viewScale,y/viewScale);}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        return super.mouseScrolled(x/viewScale,y/viewScale,horizontal,vertical);
    }
    private String slotHint(int index){
        if(index>=4&&(menu.talismans()==0||index-4>=menu.talismans()))
            return menu.talismans()==0?"尚未读取护符皮袋容量":"需要护符皮袋解锁";
        return menu.canWriteEquipment(index)?"拖入装配，右键或拖回列表卸下":"当前状态不可装配，请等待法环背包刷新";
    }
    private void send(ItemStack observed,int epoch,int equipment,boolean remove){
        var custom=observed.get(DataComponents.CUSTOM_DATA);
        if(custom==null)return;var tag=custom.copyTag();
        if(!tag.contains("erbridge_view_epoch",3)||!tag.contains("erbridge_inventory_slot",3)
                ||!tag.contains("erbridge_item_handle",3)||!tag.contains("erbridge_item_id",3))return;
        if(tag.getInt("erbridge_view_epoch")!=epoch||epoch!=menu.epoch()){hint="背包已刷新，请重新选择";return;}
        int itemId=tag.getInt("erbridge_item_id");
        int slot=HostEquipmentMenu.hostSlot(equipment);
        if(slot<0)return;
        if(!menu.canWriteEquipment(equipment)){hint=slotHint(equipment);return;}
        int category=itemId>>>28;
        if(category!=1&&category!=2){hint="当前只开放防具与护符装卸；其他物品暂只读";return;}
        if(category!=(equipment<4?1:2)){hint="请将防具放入对应防具槽、护符放入已解锁护符槽";return;}
        ClientPlayNetworking.send(new HostEquipmentMenus.Intent(menu.containerId,epoch,equipment,remove,
            tag.getInt("erbridge_inventory_slot"),tag.getInt("erbridge_item_handle"),itemId));
    }
    @Override protected void slotClicked(Slot slot,int slotId,int button,ClickType type){
        // Number keys, Q, double-click collection and vanilla quick-craft never touch projection stacks.
        if(type==ClickType.THROW)hint="丢弃尚未接入法环原生流程，未丢出任何物品";
    }
    @Override protected void containerTick(){
        super.containerTick();
        previous.active=menu.page()>0&&!menu.pending();next.active=menu.page()+1<menu.pages()&&!menu.pending();
        for(int i=0;i<filters.size();i++)filters.get(i).active=i!=menu.filter()&&!menu.pending();
        if(gesture!=null&&gesture.epoch()!=menu.epoch()){gesture=null;hint="背包已刷新，拖动已取消";}
    }
    @Override public void renderBackground(GuiGraphics g,int x,int y,float delta){} // No full-screen blur over ER.
    @Override protected void renderBg(GuiGraphics g,float delta,int mouseX,int mouseY){
        g.fill(leftPos,topPos,leftPos+imageWidth,topPos+imageHeight,0xFF998563);
        g.fill(leftPos+1,topPos+1,leftPos+imageWidth-1,topPos+imageHeight-1,0xF51B242B);
        Slot hovered=slotAt(mouseX,mouseY);
        for(int i=0;i<menu.slots.size();i++){
            Slot slot=menu.slots.get(i);int x=leftPos+slot.x,y=topPos+slot.y;
            int color=i==equipmentIndex?0xFFD4B36B:hovered==slot?0xFF94A7B4:0xFF52606C;
            g.fill(x-1,y-1,x+HostEquipmentMenu.ICON_SIZE+1,y+HostEquipmentMenu.ICON_SIZE+1,color);
            g.fill(x,y,x+HostEquipmentMenu.ICON_SIZE,y+HostEquipmentMenu.ICON_SIZE,0xFF0F171E);
            if(i>=4&&i<8&&(menu.talismans()==0||i-4>=menu.talismans()))
                g.drawCenteredString(font,"×",x+HostEquipmentMenu.ICON_SIZE/2,y+8,0xFFB06767);
        }
    }
    @Override protected void renderSlot(GuiGraphics g,Slot slot){
        // Replace vanilla item rendering entirely. MC leather/gold placeholders never cover native alpha pixels.
        if(!slot.hasItem())return;
        g.pose().pushPose();g.pose().translate(0,0,100);
        renderIcon(g,slot.getItem(),slot.x,slot.y,HostEquipmentMenu.ICON_SIZE);
        g.pose().popPose();
    }
    private void renderIcon(GuiGraphics g,ItemStack stack,int x,int y,int size){
        if(!HostItemIcons.render(g,stack,x,y,size)){
            g.fill(x+2,y+2,x+size-2,y+size-2,0xFF394853);
            g.drawCenteredString(font,"?",x+size/2,y+(size-8)/2,0xFFDAC28C);
        }
    }
    @Override protected void renderLabels(GuiGraphics g,int mouseX,int mouseY){
        g.drawString(font,title,titleLabelX,titleLabelY,0xFFF0D99E,false);
        g.drawString(font,"护符 "+menu.talismans()+" / 4",224,8,0xFFD0D8DE,false);
        for(int i=0;i<LABELS.length;i++){
            Slot slot=menu.slots.get(i);g.drawCenteredString(font,LABELS[i],slot.x+13,56,0xFFE8E4D6);
        }
        g.drawString(font,menu.itemCount()+" 件 · 每页 "+HostEquipmentMenu.PAGE_SIZE+" 格",14,243,0xFFD0D8DE,false);
        g.drawCenteredString(font,(menu.page()+1)+"/"+menu.pages(),252,243,0xFFD0D8DE);
        g.drawString(font,font.plainSubstrByWidth(hint==null?menu.statusText():hint,imageWidth-28),14,263,
            menu.status()==2&&hint==null?0xFF8CDE96:0xFFF0D99E,false);
    }
    @Override public void render(GuiGraphics g,int screenX,int screenY,float delta){
        int x=(int)(screenX/viewScale),y=(int)(screenY/viewScale);
        g.pose().pushPose();g.pose().scale((float)viewScale,(float)viewScale,1);
        super.render(g,x,y,delta);
        hoveredSlot=slotAt(x,y);
        if(gesture!=null&&dragged){
            g.pose().pushPose();g.pose().translate(0,0,300);
            renderIcon(g,gesture.observed(),x-13,y-13,HostEquipmentMenu.ICON_SIZE);g.pose().popPose();
        }
        g.pose().popPose();
        // Tooltip positioners use the actual GUI viewport, unlike the scaled container coordinates.
        if(gesture==null||!dragged)renderTooltip(g,screenX,screenY);
        if(x>=leftPos+14&&x<leftPos+imageWidth-14&&y>=topPos+261&&y<topPos+276)
            g.renderTooltip(font,Component.literal(hint==null?menu.statusText():hint),screenX,screenY);
        if(hoveredSlot!=null&&hoveredSlot.index>=4&&hoveredSlot.index<8&&!hoveredSlot.hasItem())
            g.renderTooltip(font,Component.literal(slotHint(hoveredSlot.index)),screenX,screenY);
    }
}
