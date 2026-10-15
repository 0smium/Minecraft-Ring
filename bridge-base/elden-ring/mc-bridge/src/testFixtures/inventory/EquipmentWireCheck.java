import dev.ermc.bridge.link.HostEquipmentChannel;
import dev.ermc.bridge.link.HostInventorySample;
import dev.ermc.bridge.link.HostEquipmentResult;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

public final class EquipmentWireCheck {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("equipment check "+checks); }
    public static void main(String[] args) {
        int base=HostEquipmentChannel.OFFSET;
        ByteBuffer b=ByteBuffer.allocateDirect(base+HostEquipmentChannel.SIZE).order(ByteOrder.LITTLE_ENDIAN);
        HostEquipmentChannel channel=new HostEquipmentChannel(b);
        var entry=new HostInventorySample.Entry(7,0x80800001,0x10013880,0,4,0,false);
        var sample=new HostInventorySample(100,3000,123,9,2,1,1,10,2,List.of(entry),456,10,0xF000,3);
        check(sample.canWriteSlot(12)&&sample.canWriteSlot(15)&&!sample.canWriteSlot(17));
        check(channel.submit(sample,17,entry,77,4,9,1000,3000)==0);
        check(channel.submit(sample,12,entry,77,4,10,1000,3000)==0);
        check(channel.submit(sample,12,entry,77,4,9,1000,5001)==0);
        check(channel.submit(sample,12,entry,77,4,9,4000,4000)==0);
        var other=new HostInventorySample.Entry(8,0x80800002,entry.itemId(),1,4,0,false);
        check(channel.submit(sample,12,other,77,4,9,1000,3000)==0);
        long nonce=channel.submit(sample,12,entry,77,4,9,1000,3000);
        check(nonce!=0&&b.getInt(base)==1&&b.getInt(base+4)==0);
        int q=base+16;
        check(b.getInt(q)==1&&b.getInt(q+4)==1&&b.getInt(q+8)==77&&b.getInt(q+12)==4);
        check(b.getInt(q+16)==9&&b.getInt(q+20)==10&&b.getInt(q+24)==12&&b.getInt(q+28)==7);
        check(b.getInt(q+32)==entry.handle()&&b.getInt(q+36)==entry.itemId());
        check(b.getLong(q+40)==1000&&b.getLong(q+48)==123&&b.getLong(q+56)==456&&b.getLong(q+64)==8000&&b.getLong(q+72)==nonce);
        check(channel.submit(sample,12,null,77,4,9,1000,3000)==0);
        check(channel.result(nonce).isEmpty());
        int r=base+96;
        b.putInt(r,1);b.putInt(r+4,0);b.putInt(r+8,12);b.putInt(r+12,entry.handle());b.putInt(r+16,entry.itemId());
        b.putInt(r+20,entry.slot());b.putInt(r+24,9);b.putLong(r+32,nonce);b.putLong(r+40,567);b.putLong(r+48,3001);b.putLong(r+56,1000);
        b.putInt(base+4,1);
        var result=channel.result(nonce).orElseThrow();
        check(result.successful()&&!result.unknown()&&result.itemId()==entry.itemId()&&result.snapshotToken()==567);
        check(channel.result(nonce+1).isEmpty());
        b.putInt(r+4,-50);check(channel.result(nonce).orElseThrow().unknown());
        b.putInt(r+28,1);check(channel.result(nonce).isEmpty());b.putInt(r+28,0);
        long remove=channel.submit(sample,12,null,77,4,9,1000,3000);
        check(remove!=0&&remove!=nonce&&b.getInt(base)==2&&b.getInt(q+4)==2);
        check(b.getInt(q+28)==-1&&b.getInt(q+32)==0&&b.getInt(q+36)==0);
        check(channel.result(nonce).isEmpty());
        b.putInt(base+4,b.getInt(base));
        var talisman=new HostInventorySample.Entry(8,0x80800002,0x20001234,0,5,0,false);
        var both=new HostInventorySample(100,3000,123,9,2,1,2,10,2,List.of(entry,talisman),456,10,0x1EF000,4);
        for(int slot=17;slot<=20;slot++) {
            check(both.canWriteSlot(slot));
            check(channel.submit(both,slot,entry,77,4,9,1000,3000)==0);
            check(channel.submit(both,12,talisman,77,4,9,1000,3000)==0);
            long equipTalisman=channel.submit(both,slot,talisman,77,4,9,1000,3000);
            check(equipTalisman!=0&&b.getInt(q+24)==slot&&b.getInt(q+36)==talisman.itemId()&&b.getInt(q+28)==8);
            b.putInt(base+4,b.getInt(base));
            check(channel.submit(both,slot,null,77,4,9,1000,3000)!=0&&b.getInt(q+4)==2&&b.getInt(q+28)==-1);
            b.putInt(base+4,b.getInt(base));
        }
        var locked=new HostInventorySample(100,3000,123,9,2,1,2,10,2,List.of(entry,talisman),456,10,0x1EF000,1);
        check(channel.submit(locked,18,talisman,77,4,9,1000,3000)==0);
        check(channel.submit(locked,20,null,77,4,9,1000,3000)==0);
        var readOnly=new HostInventorySample(100,3000,123,9,2,1,2,10,2,List.of(entry,talisman),456,10,0xF000,4);
        check(channel.submit(readOnly,17,talisman,77,4,9,1000,3000)==0);
        check(new HostEquipmentResult(-52,17,0,0,0,9,1,0,0,1000).message().contains("同系护符"));
        System.out.println("PASS: "+checks+" actual equipment Java codec checks: owner/life/freshness, immutable item membership, request fields, single in-flight request, nonce result matching and unknown outcome");
    }
}
