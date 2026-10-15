import dev.ermc.bridge.link.HostInventorySample;
import dev.ermc.bridge.link.HostProgressionSample;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public final class InventoryWireCheck {
    private static int checks;
    private static byte[] original;
    private static ByteBuffer direct(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(bytes); buffer.flip(); return buffer;
    }
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static HostInventorySample read(Path path) throws Exception {
        return HostInventorySample.read(direct(Files.readAllBytes(path)), 0).orElseThrow();
    }
    private static void reject(Consumer<ByteBuffer> edit) {
        ByteBuffer bytes = direct(original);
        edit.accept(bytes);
        check(HostInventorySample.read(bytes, 0).isEmpty());
    }
    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]);
        original = Files.readAllBytes(path);
        HostInventorySample sample = read(path);
        check(original.length == HostInventorySample.SIZE && HostInventorySample.SIZE == 0x40060);
        check(HostInventorySample.readCurrent(direct(original),0).orElseThrow().equals(sample));
        check(sample.state() == 2 && sample.frame() == 100 && sample.hostLife() == 7);
        check(sample.entries().size() == 4 && sample.totalEntries() == 4 && sample.normalCapacity() == 3 && sample.keyCapacity() == 2);
        check(sample.entries().get(0).keyItem() && sample.entries().get(0).quantity() == 2);
        check(sample.entries().get(2).slot() == 2 && sample.entries().get(2).quantity() == 0 && !sample.entries().get(2).keyItem());
        check(sample.entries().get(2).equipmentMask() == 2 && sample.entries().get(2).equipmentText().equals("右手1"));
        check(sample.entries().get(3).slot() == 4);
        check(sample.snapshotToken() != 0 && sample.writableSlots() == 0 && sample.talismanSlots() == 0);
        ByteBuffer legacy=direct(original);legacy.putInt(8,1);legacy.limit(HostInventorySample.V1_SIZE);
        var legacySample=HostInventorySample.read(legacy,0).orElseThrow();
        check(legacySample.snapshotToken()==0 && !legacySample.canWriteSlot(12));
        check(HostInventorySample.readCurrent(legacy,0).isEmpty());
        check(sample.entries().get(0).matches("关键") && sample.entries().get(2).matches("右手1"));
        check(sample.entries().get(0).matches("0X40002760") && !sample.entries().get(0).matches("不存在"));
        try { sample.entries().clear(); throw new AssertionError("mutable snapshot"); }
        catch (UnsupportedOperationException expected) { checks++; }
        HostProgressionSample owner = new HostProgressionSample(100, 7, 2, 164, 7, sample.fingerprint(), "fixture");
        long now = sample.sampledAt(), start = now - 1000;
        check(sample.currentFor(owner, 100, 7, start, now));
        check(!sample.currentFor(owner, 100, 7, start, now + 2001));
        check(!sample.currentFor(owner, 100, 7, start, now - 1001));
        check(!sample.currentFor(owner, 100, 8, start, now));
        check(!sample.currentFor(owner, 581, 7, start, now));
        check(!sample.currentFor(owner, 100, 7, now + 1, now + 1));
        check(!sample.currentFor(owner, 100, 7, 0, now));
        check(!sample.currentFor(new HostProgressionSample(100, 7, 2, 164, 7, sample.fingerprint() + 1, "other"), 100, 7, start, now));
        check(!sample.currentFor(new HostProgressionSample(100, 7, 1, 0, 4, 0, ""), 100, 7, start, now));
        for (String suffix : new String[]{".loading", ".unavailable"}) {
            var unavailable = read(Path.of(path + suffix));
            check(unavailable.state() != 2 && unavailable.entries().isEmpty() && unavailable.fingerprint() == 0);
            check(!unavailable.currentFor(owner, unavailable.frame(), unavailable.hostLife(), start, now));
        }
        var empty = read(Path.of(path + ".empty"));
        check(empty.state() == 2 && empty.entries().isEmpty());
        var truncated = read(Path.of(path + ".truncated"));
        check(truncated.entries().size() == 1024 && truncated.totalEntries() == 1106 && truncated.flags() == 3);
        check(truncated.entries().get(1023).slot() == 1023);
        check(!truncated.canWriteSlot(12));
        check(HostInventorySample.readCurrent(direct(Files.readAllBytes(Path.of(path+".truncated"))),0).isEmpty());
        var large = read(Path.of(path + ".large"));
        check(large.entries().size()==1106 && large.totalEntries()==1106 && large.canWriteSlot(12) && large.talismanSlots()==4);
        for(int i=0;i<4;i++) check(large.entries().get(1102+i).equipmentMask()==(1<<(17+i)));
        var maximum = read(Path.of(path + ".maximum"));
        check(maximum.entries().size()==8192 && maximum.totalEntries()==8192 && maximum.entries().get(8191).slot()==8191);
        check(maximum.canWriteSlot(15));
        var nativeTalismans=read(Path.of(path+".talismans"));
        check(nativeTalismans.talismanSlots()==4&&nativeTalismans.writableSlots()==0x1EF000);
        for(int slot=17;slot<=20;slot++)check(nativeTalismans.canWriteSlot(slot));
        var unknownTalismans=read(Path.of(path+".talisman-unknown"));
        check(unknownTalismans.talismanSlots()==0&&unknownTalismans.writableSlots()==0xF000);
        check(unknownTalismans.canWriteSlot(12)&&!unknownTalismans.canWriteSlot(17));
        for(int capacity=0;capacity<=4;capacity++) {
            ByteBuffer talismans=direct(original);
            talismans.putInt(HostInventorySample.FOOTER_OFFSET+12,HostInventorySample.availableEquipmentSlots(capacity));
            talismans.putInt(HostInventorySample.FOOTER_OFFSET+16,capacity);
            var capable=HostInventorySample.readCurrent(talismans,0).orElseThrow();
            check(capable.canWriteSlot(12)&&capable.canWriteSlot(15));
            for(int slot=17;slot<=20;slot++) check(capable.canWriteSlot(slot)==(slot-17<capacity));
            check(!capable.canWriteSlot(16)&&!capable.canWriteSlot(21));
        }
        reject(b -> b.putInt(0, 0)); reject(b -> b.putInt(0, 3)); reject(b -> b.putInt(4, 0));
        reject(b -> b.putInt(8, 4)); reject(b -> b.putInt(12, 3)); reject(b -> b.putLong(16, -1));
        reject(b -> b.putLong(24, 0)); reject(b -> b.putLong(32, 0)); reject(b -> b.putInt(44, 5));
        reject(b -> b.putInt(48, 8193)); reject(b -> b.putInt(48, -1)); reject(b -> b.putInt(52, 3));
        reject(b -> b.putInt(56, 4097)); reject(b -> b.putInt(60, -1)); reject(b -> b.putInt(44, 3));
        reject(b -> b.putInt(64, 5)); reject(b -> b.putInt(96, 0)); reject(b -> b.putInt(68, 0));
        reject(b -> b.putInt(68, -1)); reject(b -> b.putInt(72, -1)); reject(b -> b.putInt(76, 1000000000));
        reject(b -> b.putInt(84, 1 << 22)); reject(b -> b.putInt(84, 2)); reject(b -> b.putInt(88, 0));
        reject(b -> b.putInt(92, 1)); reject(b -> b.putInt(12, 1));
        reject(b -> b.putLong(HostInventorySample.FOOTER_OFFSET,0));
        reject(b -> b.putInt(HostInventorySample.FOOTER_OFFSET+12,1<<17));
        reject(b -> {b.putInt(HostInventorySample.FOOTER_OFFSET+16,1);b.putInt(HostInventorySample.FOOTER_OFFSET+12,1<<18);});
        reject(b -> {b.putInt(HostInventorySample.FOOTER_OFFSET+16,4);b.putInt(HostInventorySample.FOOTER_OFFSET+12,1<<16);});
        reject(b -> {b.putInt(HostInventorySample.FOOTER_OFFSET+16,4);b.putInt(HostInventorySample.FOOTER_OFFSET+12,1<<21);});
        reject(b -> b.putInt(HostInventorySample.FOOTER_OFFSET+16,5));
        reject(b -> b.putInt(HostInventorySample.FOOTER_OFFSET+20,1));
        reject(b -> b.limit(HostInventorySample.SIZE-1));
        check(HostInventorySample.read(ByteBuffer.allocate(8), 0).isEmpty());
        check(HostInventorySample.read(direct(original), 1).isEmpty());
        check(HostInventorySample.read(direct(original), -8).isEmpty());
        check(HostInventorySample.read(ByteBuffer.wrap(original), 0).isEmpty());
        ByteBuffer offset = ByteBuffer.allocateDirect(original.length + 8).order(ByteOrder.BIG_ENDIAN);
        offset.position(8); offset.put(original);
        check(HostInventorySample.read(offset, 8).orElseThrow().entries().equals(sample.entries()));
        System.out.println("PASS: " + checks + " production HINV Java checks using C++ publisher bytes: layout, slots/equipment, sparse/zero/truncated/unknown data, malformed rejection, owner/session/freshness and filters");
        System.out.println("LIMIT: no Minecraft screen rendering, live inventory or game startup");
    }
}
