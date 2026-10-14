package dev.ermc.bridge.character.equipment;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import dev.ermc.bridge.link.HostInventorySample;

/** Real MC item/component/slot checks, without a game process or a native write. */
public final class EquipmentProjectionCheck {
    private static int checks;
    public static void main(String[] args){
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        var entry=new HostInventorySample.Entry(7,91,0x10001234,25,1,1<<13,false);
        ItemStack projected=HostEquipmentMenu.display(entry);
        check(projected.getCount()==1,"host quantity cannot turn into stack count");
        check(projected.is(Items.PAPER)&&projected.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
            net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY).modifiers().isEmpty(),"neutral tooltip carrier never claims vanilla leather defense");
        check(projected.get(DataComponents.CUSTOM_DATA).copyTag().getInt("erbridge_item_id")==entry.itemId(),"icon raw ID component");
        check(projected.get(DataComponents.LORE).lines().stream().anyMatch(s->s.getString().contains("25")),"original quantity shown in tooltip");
        var container=new SimpleContainer(1);container.setItem(0,projected);
        var slot=new HostEquipmentMenu.ProjectionSlot(container,0,0,0);
        check(!slot.mayPickup(null)&&!slot.mayPlace(new ItemStack(Items.STONE)),"cannot take or place");
        check(!slot.allowModification(null)&&slot.isFake(),"MC screen treats projection as fake/unmodifiable");
        check(slot.remove(64).isEmpty()&&container.getItem(0)==projected,"direct slot removal cannot mint copies");
        ItemStack offered=new ItemStack(Items.STONE,10);
        check(slot.safeInsert(offered).getCount()==10&&container.getItem(0)==projected,"drag/safe insert preserves both stacks");
        check(slot.tryRemove(1,64,null).isEmpty()&&container.getItem(0)==projected,"safe take blocked");
        check(projected.has(DataComponents.CUSTOM_NAME),"standard item tooltip uses display name");
        check(!slot.isHighlightable(),"large native icon does not receive a 16px vanilla highlight");
        check(HostEquipmentMenu.PAGE_SIZE==50&&HostEquipmentMenu.GHOST_COUNT==58,"50 carried entries plus eight ER slots, no MC inventory duplicates");
        check(projected.get(DataComponents.LORE).lines().stream().anyMatch(s->s.getString().contains("丢弃尚未接入")),"unsupported native drop is explicit, no silent deletion");
        var old=new HostInventorySample(100,1000,4,2,2,1,1,1,0,java.util.List.of(entry));
        check(!old.canWriteSlot(13),"legacy observation cannot create a write request");
        var truncated=new HostInventorySample(100,1000,4,2,2,3,1,1,0,java.util.List.of(entry),45,1,0xF000,4);
        check(!truncated.canWriteSlot(13),"incomplete snapshots cannot write");
        var ready=new HostInventorySample(100,1000,4,2,2,1,1,1,0,java.util.List.of(entry),45,1,0xF000,2);
        check(ready.canWriteSlot(12)&&ready.canWriteSlot(15)&&!ready.canWriteSlot(17),"only native armor capability slots writable");
        var equip=new HostEquipmentMenus.Intent(12,72,1,false,entry.slot(),entry.handle(),entry.itemId());
        var removeObserved=new HostEquipmentMenus.Intent(12,72,1,true,entry.slot(),entry.handle(),entry.itemId());
        check(HostEquipmentMenu.resolveIntentEntry(ready,java.util.List.of(entry),0,72,equip)==entry,"drag equip resolves only the observed displayed item");
        check(HostEquipmentMenu.resolveIntentEntry(ready,java.util.List.of(entry),0,72,removeObserved)==entry,"drag unequip binds to the observed equipped item");
        check(HostEquipmentMenu.resolveIntentEntry(ready,java.util.List.of(entry),0,73,equip)==null,"snapshot/page epoch changed during drag rejects it");
        check(HostEquipmentMenu.resolveIntentEntry(ready,java.util.List.of(entry),0,73,removeObserved)==null,"stale unequip drag cannot remove replacement gear");
        check(HostEquipmentMenu.resolveIntentEntry(ready,java.util.List.of(entry),1,72,equip)==null,"another visible page cannot authorize old drag");
        check(HostEquipmentMenu.resolveIntentEntry(ready,java.util.List.of(entry),0,72,
            new HostEquipmentMenus.Intent(12,72,0,true,entry.slot(),entry.handle(),entry.itemId()))==null,"wrong equipment slot cannot authorize remove");
        check(HostEquipmentMenu.resolveIntentEntry(ready,java.util.List.of(entry),0,72,
            new HostEquipmentMenus.Intent(12,72,1,true,0,0,0))==null,"legacy blind unequip identity is rejected");
        check(HostEquipmentMenu.resolveIntentEntry(ready,java.util.List.of(entry),0,72,
            new HostEquipmentMenus.Intent(12,72,1,false,entry.slot(),entry.handle()+1,entry.itemId()))==null,"reused inventory index with a new handle is rejected");
        check(HostEquipmentMenu.resolveIntentEntry(ready,java.util.List.of(entry),0,72,
            new HostEquipmentMenus.Intent(12,72,1,false,entry.slot(),entry.handle(),entry.itemId()+1))==null,"raw item identity must still match");
        check(HostEquipmentMenu.equippedEntry(ready,1)==entry&&HostEquipmentMenu.equippedEntry(ready,0)==null,"equipment mask, not page order, selects the equipped piece");
        var talisman=new HostInventorySample.Entry(9,93,0x20001234,1,2,1<<20,false);
        check(HostEquipmentMenu.matchesFilter(entry,0)&&HostEquipmentMenu.matchesFilter(talisman,0),"all-items filter includes armor and talismans");
        check(HostEquipmentMenu.matchesFilter(entry,1)&&!HostEquipmentMenu.matchesFilter(talisman,1),"armor tab excludes talismans");
        check(HostEquipmentMenu.matchesFilter(talisman,2)&&!HostEquipmentMenu.matchesFilter(entry,2),"talisman tab excludes armor");
        check(HostEquipmentMenu.resolveIntentEntry(ready,java.util.List.of(talisman),0,72,
            new HostEquipmentMenus.Intent(12,72,1,false,talisman.slot(),talisman.handle(),talisman.itemId()))==null,"read-only talisman drag cannot become armor equip");
        var keyed=new HostInventorySample.Entry(10,94,entry.itemId(),1,2,0,true);
        check(HostEquipmentMenu.resolveIntentEntry(ready,java.util.List.of(keyed),0,72,
            new HostEquipmentMenus.Intent(12,72,1,false,keyed.slot(),keyed.handle(),keyed.itemId()))==null,"key item cannot authorize equipment operation");
        var bytes=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY);
        try {
            var intent=new HostEquipmentMenus.Intent(12,0x81234567,2,false,998,0xF1234567,0x10001234);
            HostEquipmentMenus.Intent.CODEC.encode(bytes,intent);
            check(HostEquipmentMenus.Intent.CODEC.decode(bytes).equals(intent),"observed identity and full 32-bit epoch survive C2S roundtrip");
            var remove=new HostEquipmentMenus.Intent(12,4,3,true,entry.slot(),entry.handle(),entry.itemId());
            HostEquipmentMenus.Intent.CODEC.encode(bytes,remove);
            check(HostEquipmentMenus.Intent.CODEC.decode(bytes).equals(remove),"explicit unequip remains distinct from an empty-item equip");
        } finally { bytes.release(); }
        System.out.println("PASS "+checks+": actual MC projection ItemStack, enlarged ghost Slot, native intent identity/page/epoch, filters and write boundary");
        System.out.println("LIMIT: no screen, integrated server, gameplay, native equipment function or live inventory was exercised");
    }
    private static void check(boolean test,String description){checks++;if(!test)throw new AssertionError(description);}
}
