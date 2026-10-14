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
    public static void main(String[] args){
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        var entry=new HostInventorySample.Entry(7,91,0x10001234,25,1,1<<13,false);
        ItemStack projected=HostEquipmentMenu.display(entry);
        check(projected.getCount()==1,"host quantity cannot turn into stack count");
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
        var old=new HostInventorySample(100,1000,4,2,2,1,1,1,0,java.util.List.of(entry));
        check(!old.canWriteSlot(13),"legacy observation cannot create a write request");
        var truncated=new HostInventorySample(100,1000,4,2,2,3,1,1,0,java.util.List.of(entry),45,1,0xF000,4);
        check(!truncated.canWriteSlot(13),"incomplete snapshots cannot write");
        var ready=new HostInventorySample(100,1000,4,2,2,1,1,1,0,java.util.List.of(entry),45,1,0xF000,2);
        check(ready.canWriteSlot(12)&&ready.canWriteSlot(15)&&!ready.canWriteSlot(17),"only native armor capability slots writable");
        var bytes=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY);
        try {
            var intent=new HostEquipmentMenus.Intent(12,0x81234567,2,false,998,0xF1234567,0x10001234);
            HostEquipmentMenus.Intent.CODEC.encode(bytes,intent);
            check(HostEquipmentMenus.Intent.CODEC.decode(bytes).equals(intent),"observed identity and full 32-bit epoch survive C2S roundtrip");
            var remove=new HostEquipmentMenus.Intent(12,4,3,true,0,0,0);
            HostEquipmentMenus.Intent.CODEC.encode(bytes,remove);
            check(HostEquipmentMenus.Intent.CODEC.decode(bytes).equals(remove),"explicit unequip remains distinct from an empty-item equip");
        } finally { bytes.release(); }
        System.out.println("PASS 14: actual MC projection ItemStack, CustomData, lore, ghost Slot, safe take/insert and native write capability boundary");
        System.out.println("LIMIT: no screen, integrated server, gameplay, native equipment function or live inventory was exercised");
    }
    private static void check(boolean test,String description){if(!test)throw new AssertionError(description);}
}
