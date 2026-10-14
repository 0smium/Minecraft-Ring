package dev.ermc.bridge.character.equipment;

import dev.ermc.bridge.LocalHostOwner;
import dev.ermc.bridge.TerrainManager;
import dev.ermc.bridge.link.ErLink;
import dev.ermc.bridge.link.HostInventorySample;
import dev.ermc.bridge.link.HostInventorySample.Entry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import java.util.*;

/** Native inventory projection in standard slots. Ghost entries never become Minecraft inventory items. */
public final class HostEquipmentMenu extends AbstractContainerMenu {
    public static final int PAGE_SIZE = 27, GHOST_COUNT = 8 + PAGE_SIZE;
    private final SimpleContainer projection = new SimpleContainer(GHOST_COUNT);
    private final ContainerData data = new SimpleContainerData(9);
    private final ServerPlayer owner;
    private HostInventorySample shown;
    private List<Entry> carried = List.of();
    private long lastRead, pendingNonce, submittedAt;
    private int viewEpoch, projectedPage = -1;
    private static final int[] HOST_SLOTS = {12,13,14,15,17,18,19,20};

    public HostEquipmentMenu(int id, Inventory inventory) { this(id, inventory, null); }
    public HostEquipmentMenu(int id, Inventory inventory, ServerPlayer owner) {
        super(HostEquipmentMenus.TYPE, id); this.owner = owner;
        for (int i=0;i<8;i++) addSlot(new ProjectionSlot(projection, i, 12+i*22, 24));
        for (int row=0;row<3;row++) for(int col=0;col<9;col++)
            addSlot(new ProjectionSlot(projection, 8+row*9+col, 12+col*18, 66+row*18));
        for (int row=0;row<3;row++) for(int col=0;col<9;col++) addSlot(new Slot(inventory, 9+row*9+col, 12+col*18, 141+row*18));
        for (int col=0;col<9;col++) addSlot(new Slot(inventory,col,12+col*18,199));
        // Delegate placement, binding curse, equipment callbacks and empty sprites to MC's own armor slots.
        for (int armor=0;armor<4;armor++) {
            final Slot vanilla = inventory.player.inventoryMenu.getSlot(5+armor);
            addSlot(new Slot(inventory,39-armor,205,141+armor*18) {
                @Override public boolean mayPlace(ItemStack stack) { return vanilla.mayPlace(stack); }
                @Override public boolean mayPickup(Player player) { return vanilla.mayPickup(player); }
                @Override public int getMaxStackSize() { return vanilla.getMaxStackSize(); }
                @Override public void setByPlayer(ItemStack stack, ItemStack previous) { vanilla.setByPlayer(stack,previous); }
                @Override public com.mojang.datafixers.util.Pair<net.minecraft.resources.ResourceLocation,net.minecraft.resources.ResourceLocation> getNoItemIcon() { return vanilla.getNoItemIcon(); }
            });
        }
        addDataSlots(data); data.set(2,0);
        if(owner!=null) refresh();
    }
    public int epoch(){return (data.get(7)&0xFFFF)|((data.get(8)&0xFFFF)<<16);}
    public int page(){return data.get(0);} public int pages(){return Math.max(1,data.get(1));}
    public int selected(){return data.get(2);} public int writable(){return data.get(3);}
    public int talismans(){return data.get(4);} public int status(){return data.get(5);}
    public boolean pending(){return data.get(6)!=0;}
    public String statusText(){return switch(status()){
        case 1->"等待法环确认…";case 2->"已由法环确认";case 3->"操作未确认，先观察实际装备";
        case 4->"等待当前角色背包";case 5->"当前状态不可装配";case 6->"此页暂只支持四件防具装卸";
        case 7->"请选择对应的防具槽";case 9->"背包已刷新，请按新画面重新选择";case 8->"待确认请求处理中";default->"点防具槽选择 · 右键槽卸下 · 点物品装配";};}
    @Override public boolean stillValid(Player player) {
        return owner==null || player==owner && LocalHostOwner.isOwner(owner) && owner.isAlive()
            && TerrainManager.isBridgeWorld() && ErLink.get().alive();
    }
    private HostInventorySample current() {
        ErLink link=ErLink.get();var sample=link.readHostInventory();var host=link.readHostProgression();
        if(!link.alive()||sample.isEmpty()||host.isEmpty()||!sample.get().currentFor(host.get(),link.hostFrame(),link.hostLife(),link.hostStartMs(),System.currentTimeMillis()))return null;
        return sample.get();
    }
    private void refresh() {
        lastRead=System.currentTimeMillis();HostInventorySample current=current();
        if(current==null){shown=null;carried=List.of();projection.clearContent();data.set(3,0);data.set(4,0);data.set(5,4);return;}
        if (shown == null || shown.snapshotToken()!=current.snapshotToken() || projectedPage!=page()) {
            viewEpoch++; data.set(7,viewEpoch&0xFFFF); data.set(8,viewEpoch>>>16); projectedPage=page();
        }
        shown=current;
        carried=shown.entries().stream().filter(e->!e.keyItem()&&(e.itemId()>>>28==1||e.itemId()>>>28==2)).toList();
        data.set(1,Math.max(1,(carried.size()+PAGE_SIZE-1)/PAGE_SIZE));data.set(0,Math.min(page(),pages()-1));
        data.set(3,shown.writableSlots());data.set(4,shown.talismanSlots());
        if(status()==4)data.set(5,0);
        for(int i=0;i<8;i++){
            final int slot=HOST_SLOTS[i];Entry e=shown.entries().stream().filter(item->(item.equipmentMask()&(1<<slot))!=0).findFirst().orElse(null);
            projection.setItem(i,e==null||isNaked(e,slot)?ItemStack.EMPTY:project(e));
        }
        for(int i=0;i<PAGE_SIZE;i++){
            int index=page()*PAGE_SIZE+i;projection.setItem(8+i,index<carried.size()?project(carried.get(index)):ItemStack.EMPTY);
        }
    }
    private static boolean isNaked(Entry item,int slot){return slot>=12&&slot<=15&&item.itemId()==0x10000000+10000+(slot-12)*100;}
    private ItemStack project(Entry entry) {
        ItemStack stack=display(entry);CompoundTag tag=stack.get(DataComponents.CUSTOM_DATA).copyTag();
        tag.putInt("erbridge_view_epoch",viewEpoch);stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));return stack;
    }
    public static ItemStack display(Entry entry) {
        ItemStack stack=new ItemStack(entry.itemId()>>>28==2?Items.GOLD_NUGGET:Items.LEATHER_CHESTPLATE);
        CompoundTag tag=new CompoundTag();tag.putInt("erbridge_item_id",entry.itemId());tag.putInt("erbridge_inventory_slot",entry.slot());tag.putInt("erbridge_item_handle",entry.handle());stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));
        String name=HostItemAssets.item(entry.itemId()).map(HostItemAssets.ItemInfo::name).filter(s->!s.isBlank()).orElse(entry.category()+" "+entry.itemCode());
        stack.set(DataComponents.CUSTOM_NAME,Component.literal(name));
        stack.set(DataComponents.LORE,new ItemLore(List.of(Component.literal("法环物品 · 数量 "+entry.quantity()),
            Component.literal("ID "+entry.itemCode()+" · 库存槽 "+entry.slot()),
            Component.literal(entry.equipmentText().isEmpty()?"尚未装备":entry.equipmentText()),
            Component.literal("镜像格：点击发装配意图，不能取出或消耗"))));
        return stack;
    }
    @Override public void broadcastChanges(){
        if(owner!=null){
            if(pendingNonce!=0){
                var result=ErLink.get().readEquipmentResult(pendingNonce);
                if(result.isPresent()){
                    data.set(5,result.get().successful()?2:3);owner.displayClientMessage(Component.literal(result.get().message()),true);
                    pendingNonce=0;data.set(6,0);refresh();
                }else if(System.currentTimeMillis()-submittedAt>8000){pendingNonce=0;data.set(6,0);data.set(5,3);}
            }
            if(System.currentTimeMillis()-lastRead>=250)refresh();
        }
        super.broadcastChanges();
    }
    @Override public boolean clickMenuButton(Player player,int button){
        if(owner==null||player!=owner||owner.containerMenu!=this||!stillValid(player)||pending())return false;
        if(button==0&&page()>0)data.set(0,page()-1);else if(button==1&&page()+1<pages())data.set(0,page()+1);else return false;
        refresh();broadcastChanges();return true;
    }
    @Override public void clicked(int slotId,int button,ClickType type,Player player){
        // Vanilla click packets identify only a slot index. Never infer an equipment intent from that.
        if(slotId>=0&&slotId<GHOST_COUNT)return;
        super.clicked(slotId,button,type,player);
    }
    public void acceptIntent(ServerPlayer player,HostEquipmentMenus.Intent intent){
        if(owner==null||player!=owner||owner.containerMenu!=this||!stillValid(player)||intent.containerId()!=containerId)return;
        if(pending()){data.set(5,8);return;}
        if(intent.armorIndex()<0||intent.armorIndex()>3)return;
        if(shown==null||intent.epoch()!=viewEpoch){data.set(5,9);return;}
        Entry entry=null;
        if(!intent.remove()){
            entry=carried.stream().skip((long)page()*PAGE_SIZE).limit(PAGE_SIZE)
                .filter(e->e.slot()==intent.inventorySlot()&&e.handle()==intent.handle()&&e.itemId()==intent.itemId()).findFirst().orElse(null);
            if(entry==null){data.set(5,9);return;}
        }
        data.set(2,intent.armorIndex());submit(entry);
    }
    private void submit(Entry entry){
        HostInventorySample now=current();int slot=HOST_SLOTS[selected()];
        if(now==null||shown==null||now.snapshotToken()!=shown.snapshotToken()||!shown.canWriteSlot(slot)){data.set(5,5);refresh();return;}
        if(entry!=null){
            if(entry.itemId()>>>28!=1){data.set(5,7);return;}
            var info=HostItemAssets.item(entry.itemId());
            if(info.isPresent()&&info.get().armorSlot()>=0&&info.get().armorSlot()!=slot){data.set(5,7);return;}
        }
        pendingNonce=ErLink.get().submitEquipment(shown,slot,entry);
        if(pendingNonce==0){data.set(5,5);return;}
        submittedAt=System.currentTimeMillis();data.set(5,1);data.set(6,1);
    }
    @Override public ItemStack quickMoveStack(Player player,int slot){return ItemStack.EMPTY;}
    @Override public boolean canDragTo(Slot slot){return !(slot instanceof ProjectionSlot)&&super.canDragTo(slot);}
    @Override public boolean canTakeItemForPickAll(ItemStack stack,Slot slot){return !(slot instanceof ProjectionSlot)&&super.canTakeItemForPickAll(stack,slot);}
    public static final class ProjectionSlot extends Slot {
        public ProjectionSlot(SimpleContainer container,int index,int x,int y){super(container,index,x,y);}
        @Override public boolean mayPlace(ItemStack stack){return false;}
        @Override public boolean mayPickup(Player player){return false;}
        @Override public boolean allowModification(Player player){return false;}
        @Override public boolean isFake(){return true;}
        @Override public ItemStack remove(int amount){return ItemStack.EMPTY;}
    }
}
