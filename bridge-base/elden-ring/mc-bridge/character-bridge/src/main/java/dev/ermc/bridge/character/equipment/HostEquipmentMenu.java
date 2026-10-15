package dev.ermc.bridge.character.equipment;

import dev.ermc.bridge.LocalHostOwner;
import dev.ermc.bridge.TerrainManager;
import dev.ermc.bridge.api.PlayerActions;
import dev.ermc.bridge.link.ErLink;
import dev.ermc.bridge.link.HostEquipmentResult;
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
    public static final int COLUMNS = 10, ROWS = 5, CELL_SIZE = 28, ICON_SIZE = 26;
    public static final int GRID_X = 14, GRID_Y = 94, PAGE_SIZE = COLUMNS * ROWS, GHOST_COUNT = 8 + PAGE_SIZE;
    private final SimpleContainer projection = new SimpleContainer(GHOST_COUNT);
    private final ContainerData data = new SimpleContainerData(12);
    private final ServerPlayer owner;
    private HostInventorySample shown;
    private List<Entry> carried = List.of();
    private long lastRead, pendingNonce, submittedAt;
    private int viewEpoch, projectedPage = -1;
    private static final int[] HOST_SLOTS = {12,13,14,15,17,18,19,20};

    public HostEquipmentMenu(int id, Inventory inventory) { this(id, inventory, null); }
    public HostEquipmentMenu(int id, Inventory inventory, ServerPlayer owner) {
        super(HostEquipmentMenus.TYPE, id); this.owner = owner;
        for (int i=0;i<8;i++) addSlot(new ProjectionSlot(projection, i, GRID_X+i*34+(i>=4?4:0), 27));
        for (int row=0;row<ROWS;row++) for(int col=0;col<COLUMNS;col++)
            addSlot(new ProjectionSlot(projection, 8+row*COLUMNS+col, GRID_X+col*CELL_SIZE, GRID_Y+row*CELL_SIZE));
        addDataSlots(data); data.set(2,0);
        if(owner!=null) refresh();
    }
    public int epoch(){return (data.get(7)&0xFFFF)|((data.get(8)&0xFFFF)<<16);}
    public int page(){return data.get(0);} public int pages(){return Math.max(1,data.get(1));}
    public int selected(){return data.get(2);} public int writable(){return unpackWritableSlots(data.get(3),data.get(11));}
    // ContainerData travels as signed 16-bit values; talisman capabilities occupy host bits 17..20.
    public static int unpackWritableSlots(int low,int high){return (low&0xFFFF)|((high&0xFFFF)<<16);}
    private void setWritable(int mask){data.set(3,mask&0xFFFF);data.set(11,mask>>>16);}
    public static int hostSlot(int equipmentIndex){return equipmentIndex>=0&&equipmentIndex<HOST_SLOTS.length?HOST_SLOTS[equipmentIndex]:-1;}
    public boolean canWriteEquipment(int index){
        int slot=hostSlot(index);
        return slot>=0&&(index<4||talismans()>index-4)&&(writable()&(1<<slot))!=0;
    }
    public int talismans(){return data.get(4);} public int status(){return data.get(5);}
    public int filter(){return data.get(9);} public int itemCount(){return data.get(10);}
    public boolean pending(){return data.get(6)!=0;}
    public String statusText(){if(status()<0)return HostEquipmentResult.messageFor(status());return switch(status()){
        case 1->"等待法环确认…";case 2->"已由法环确认";case 3->"操作未确认，先观察实际装备";
        case 4->"等待当前角色背包";case 5->"当前状态或槽位不可装配";case 6->"此页支持防具与已解锁护符槽";
        case 7->"请选择对应的防具或护符槽";case 9->"背包已刷新，请按新画面重新选择";case 8->"待确认请求处理中";default->"防具/护符拖入槽装配 · 拖回列表或右键卸下";};}
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
        if(current==null){shown=null;carried=List.of();projection.clearContent();setWritable(0);data.set(4,0);data.set(5,4);return;}
        if (shown == null || shown.snapshotToken()!=current.snapshotToken() || projectedPage!=page()) {
            viewEpoch++; data.set(7,viewEpoch&0xFFFF); data.set(8,viewEpoch>>>16); projectedPage=page();
        }
        shown=current;
        carried=shown.entries().stream().filter(e->matchesFilter(e,filter())).toList();
        data.set(10,carried.size());
        data.set(1,Math.max(1,(carried.size()+PAGE_SIZE-1)/PAGE_SIZE));data.set(0,Math.min(page(),pages()-1));
        setWritable(shown.writableSlots());data.set(4,shown.talismanSlots());
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
    public static boolean matchesFilter(Entry entry,int filter){return switch(filter){
        case 0->true;case 1->entry.itemId()>>>28==1;case 2->entry.itemId()>>>28==2;
        case 3->entry.itemId()>>>28==0;case 4->entry.itemId()>>>28==4||entry.itemId()>>>28==8;default->false;};}
    public static boolean matchesIdentity(Entry entry,HostEquipmentMenus.Intent intent){
        return entry!=null&&entry.slot()==intent.inventorySlot()&&entry.handle()==intent.handle()&&entry.itemId()==intent.itemId();
    }
    public static Entry equippedEntry(HostInventorySample sample,int equipmentIndex){
        int slot=hostSlot(equipmentIndex);
        if(sample==null||slot<0)return null;
        return sample.entries().stream().filter(e->(e.equipmentMask()&(1<<slot))!=0)
            .filter(e->HostInventorySample.acceptsEquipmentItem(slot,e)&&!isNaked(e,slot)).findFirst().orElse(null);
    }
    static Entry resolveIntentEntry(HostInventorySample sample,List<Entry> visibleItems,int page,int epoch,HostEquipmentMenus.Intent intent){
        int slot=hostSlot(intent.equipmentIndex());
        if(sample==null||intent.epoch()!=epoch||!sample.canWriteSlot(slot)||page<0)return null;
        if(intent.remove()){
            Entry equipped=equippedEntry(sample,intent.equipmentIndex());
            return matchesIdentity(equipped,intent)?equipped:null;
        }
        return visibleItems.stream().skip((long)page*PAGE_SIZE).limit(PAGE_SIZE)
            .filter(e->sample.entries().contains(e)&&HostInventorySample.acceptsEquipmentItem(slot,e)&&matchesIdentity(e,intent)).findFirst().orElse(null);
    }
    private ItemStack project(Entry entry) {
        ItemStack stack=display(entry);CompoundTag tag=stack.get(DataComponents.CUSTOM_DATA).copyTag();
        tag.putInt("erbridge_view_epoch",viewEpoch);stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));return stack;
    }
    public static ItemStack display(Entry entry) {
        // A neutral carrier avoids vanilla leather armor attributes in the otherwise standard tooltip.
        ItemStack stack=new ItemStack(Items.PAPER);
        CompoundTag tag=new CompoundTag();tag.putInt("erbridge_item_id",entry.itemId());tag.putInt("erbridge_inventory_slot",entry.slot());tag.putInt("erbridge_item_handle",entry.handle());stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));
        String name=HostItemAssets.item(entry.itemId()).map(HostItemAssets.ItemInfo::name).filter(s->!s.isBlank()).orElse(entry.category()+" "+entry.itemCode());
        stack.set(DataComponents.CUSTOM_NAME,Component.literal(name));
        stack.set(DataComponents.LORE,new ItemLore(List.of(Component.literal("法环物品 · 数量 "+entry.quantity()),
            Component.literal("ID "+entry.itemCode()+" · 库存槽 "+entry.slot()),
            Component.literal(entry.equipmentText().isEmpty()?"尚未装备":entry.equipmentText()),
            Component.literal(entry.itemId()>>>28==1?"防具可拖至对应装备槽；右键装备槽卸下":
                entry.itemId()>>>28==2?"护符可拖至已解锁护符槽；右键装备槽卸下":"本类物品暂只读"),
            Component.literal("丢弃尚未接入法环原生流程；不可取出为 MC 物品"))));
        return stack;
    }
    @Override public void broadcastChanges(){
        if(owner!=null){
            if(pendingNonce!=0){
                var result=ErLink.get().readEquipmentResult(pendingNonce);
                if(result.isPresent()){
                    data.set(5,result.get().successful()?2:result.get().unknown()?3:result.get().status());
                    owner.displayClientMessage(Component.literal(result.get().message()),true);
                    pendingNonce=0;data.set(6,0);refresh();
                }else if(System.currentTimeMillis()-submittedAt>8000){pendingNonce=0;data.set(6,0);data.set(5,3);}
            }
            if(System.currentTimeMillis()-lastRead>=250)refresh();
        }
        super.broadcastChanges();
    }
    @Override public boolean clickMenuButton(Player player,int button){
        if(owner==null||player!=owner||owner.containerMenu!=this||!stillValid(player)||pending())return false;
        if(button>=10&&button<=14){data.set(9,button-10);data.set(0,0);projectedPage=-1;}
        else if(button==0&&page()>0)data.set(0,page()-1);else if(button==1&&page()+1<pages())data.set(0,page()+1);else return false;
        refresh();broadcastChanges();return true;
    }
    @Override public void clicked(int slotId,int button,ClickType type,Player player){
        // Vanilla click packets identify only a slot index. Never infer an equipment intent from that.
        // This menu contains only ER projections. Outside/drop/number-key packets cannot move MC items either.
    }
    public void acceptIntent(ServerPlayer player,HostEquipmentMenus.Intent intent){
        if(owner==null||player!=owner||owner.containerMenu!=this||!stillValid(player)||intent.containerId()!=containerId)return;
        if(!PlayerActions.allowed(player)){data.set(5,5);return;}
        if(pending()){data.set(5,8);return;}
        if(hostSlot(intent.equipmentIndex())<0)return;
        if(shown==null||intent.epoch()!=viewEpoch){data.set(5,9);return;}
        if(!shown.canWriteSlot(hostSlot(intent.equipmentIndex()))){data.set(5,5);return;}
        Entry entry=resolveIntentEntry(shown,carried,page(),viewEpoch,intent);
        if(entry==null){data.set(5,9);return;}
        data.set(2,intent.equipmentIndex());submit(intent.remove()?null:entry);
    }
    private void submit(Entry entry){
        if(!PlayerActions.allowed(owner)){data.set(5,5);return;}
        HostInventorySample now=current();int slot=HOST_SLOTS[selected()];
        if(now==null||shown==null||now.snapshotToken()!=shown.snapshotToken()||!shown.canWriteSlot(slot)||!now.canWriteSlot(slot)){data.set(5,5);refresh();return;}
        if(entry!=null){
            if(!HostInventorySample.acceptsEquipmentItem(slot,entry)){data.set(5,7);return;}
            var info=HostItemAssets.item(entry.itemId());
            if(slot<=15&&info.isPresent()&&info.get().armorSlot()>=0&&info.get().armorSlot()!=slot){data.set(5,7);return;}
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
        @Override public boolean isHighlightable(){return false;}
        @Override public ItemStack remove(int amount){return ItemStack.EMPTY;}
    }
}
