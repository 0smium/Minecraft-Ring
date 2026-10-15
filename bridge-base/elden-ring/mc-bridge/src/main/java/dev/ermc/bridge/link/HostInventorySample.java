package dev.ermc.bridge.link;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** HINV observations; live transport requires v3, legacy v1/v2 codecs remain explicit. */
public record HostInventorySample(long frame, long sampledAt, long fingerprint, int hostLife,
                                  int state, int flags, int totalEntries, int normalCapacity,
                                  int keyCapacity, List<Entry> entries, long snapshotToken,
                                  int zone, int writableSlots, int talismanSlots) {
    public static final int MAGIC = 0x564E4948, MAX_ENTRIES = 8192;
    public static final int LEGACY_MAX_ENTRIES = 1024, V1_SIZE = 64 + LEGACY_MAX_ENTRIES * 32, V2_SIZE = V1_SIZE + 32;
    public static final int FOOTER_OFFSET = 64 + MAX_ENTRIES * 32, SIZE = FOOTER_OFFSET + 32;
    public static final int UNAVAILABLE = 0, LOADING = 1, READY = 2, MC_CONTROL = 1, TRUNCATED = 2;
    private static final VarHandle INT = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final int EQUIPMENT_BITS = (1 << 22) - 1;
    private static final String[] EQUIPMENT = {"左手1", "右手1", "左手2", "右手2", "左手3", "右手3",
        "箭1", "弩箭1", "箭2", "弩箭2", "箭3", "弩箭3", "头部", "上身", "手部", "腿部", "保留槽",
        "护符1", "护符2", "护符3", "护符4", "誓约"};

    public HostInventorySample { entries = List.copyOf(entries); }
    public HostInventorySample(long frame, long sampledAt, long fingerprint, int hostLife,
            int state, int flags, int totalEntries, int normalCapacity, int keyCapacity, List<Entry> entries) {
        this(frame,sampledAt,fingerprint,hostLife,state,flags,totalEntries,normalCapacity,keyCapacity,entries,0,0,0,0);
    }
    public boolean canWriteSlot(int slot) {
        return state == READY && snapshotToken != 0 && (flags & MC_CONTROL) != 0 &&
            (flags & TRUNCATED) == 0 && talismanSlots >= 0 && talismanSlots <= 4 &&
            isEquipmentSlot(slot) && (writableSlots & availableEquipmentSlots(talismanSlots) & (1 << slot)) != 0;
    }
    public static boolean isEquipmentSlot(int slot) { return slot >= 12 && slot <= 15 || slot >= 17 && slot <= 20; }
    public static int availableEquipmentSlots(int talismans) {
        return 0xF000 | (talismans >= 1 && talismans <= 4 ? ((1 << talismans) - 1) << 17 : 0);
    }
    /** Category is only an early boundary; native Param data remains authoritative for fit/conflicts. */
    public static boolean acceptsEquipmentItem(int slot, Entry item) {
        return item != null && !item.keyItem() && isEquipmentSlot(slot)
            && item.itemId() >>> 28 == (slot <= 15 ? 1 : 2);
    }

    public record Entry(int slot, int handle, int itemId, int quantity, int sortId, int equipmentMask, boolean keyItem) {
        public String itemCode() { return String.format(Locale.ROOT, "0x%08X", itemId); }
        public String equipmentText() {
            List<String> slots = new ArrayList<>();
            for (int i = 0; i < EQUIPMENT.length; i++) if ((equipmentMask & (1 << i)) != 0) slots.add(EQUIPMENT[i]);
            return String.join(" / ", slots);
        }
        public String category() {
            return switch (itemId >>> 28) {
                case 0 -> "武器";
                case 1 -> "防具";
                case 2 -> "护符";
                case 4 -> "道具";
                case 8 -> "战灰";
                default -> "未知";
            };
        }
        public boolean matches(String query) {
            String needle = query.strip().toLowerCase(Locale.ROOT);
            return needle.isEmpty() || (category() + " " + itemCode() + " " + Integer.toUnsignedString(itemId)
                + " " + Integer.toUnsignedString(itemId & 0x0fffffff) + " " + equipmentText()
                + (keyItem ? " 关键物品" : " 普通物品")).toLowerCase(Locale.ROOT).contains(needle);
        }
    }

    /** Reject old session/life/character data, even when a previous publisher left a valid block behind. */
    public boolean currentFor(HostProgressionSample owner, long hostFrame, int life, long hostStart, long now) {
        return state == READY && owner != null && owner.ready() && frame > 0 && sampledAt > 0
            && hostLife == life && hostLife == owner.hostLife() && fingerprint == owner.fingerprint()
            && hostStart > 0 && sampledAt >= hostStart && sampledAt >= now - 2000 && sampledAt <= now + 1000
            && Math.abs(frame - hostFrame) <= 480 && Math.abs(frame - owner.frame()) <= 480;
    }

    public static Optional<HostInventorySample> read(ByteBuffer source, int base) {
        return read(source, base, false);
    }

    /** Never interpret a stale legacy block as the current full-inventory capability. */
    public static Optional<HostInventorySample> readCurrent(ByteBuffer source, int base) {
        return read(source, base, true);
    }

    private static Optional<HostInventorySample> read(ByteBuffer source, int base, boolean currentOnly) {
        if (!source.isDirect() || base < 0 || (base & 7) != 0 || source.limit() - base < 64) return Optional.empty();
        ByteBuffer b = source.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        for (int attempt = 0; attempt < 4; attempt++) {
            int sequence = (int) INT.getAcquire(b, base);
            if (sequence == 0) return Optional.empty();
            if ((sequence & 1) != 0) { Thread.onSpinWait(); continue; }
            int magic = b.getInt(base + 4), version = b.getInt(base + 8), state = b.getInt(base + 12);
            if (version < 1 || version > 3 || (currentOnly && version != 3)) return Optional.empty();
            int maxEntries = version == 3 ? MAX_ENTRIES : LEGACY_MAX_ENTRIES;
            int footer = version == 3 ? FOOTER_OFFSET : V1_SIZE;
            int size = footer + (version == 1 ? 0 : 32);
            if (source.limit()-base < size) return Optional.empty();
            long frame = b.getLong(base + 16), time = b.getLong(base + 24), fingerprint = b.getLong(base + 32);
            int life = b.getInt(base + 40), flags = b.getInt(base + 44), count = b.getInt(base + 48);
            int total = b.getInt(base + 52), normal = b.getInt(base + 56), key = b.getInt(base + 60);
            long token = version >= 2 ? b.getLong(base+footer) : 0;
            int zone = version >= 2 ? b.getInt(base+footer+8) : 0;
            int writable = version >= 2 ? b.getInt(base+footer+12) : 0;
            int talismans = version >= 2 ? b.getInt(base+footer+16) : 0;
            boolean valid = count >= 0 && count <= maxEntries;
            List<Entry> entries = new ArrayList<>(valid ? count : 0);
            int previousSlot = -1, equipment = 0;
            if (valid) for (int i = 0; i < count; i++) {
                int offset = base + 64 + i * 32;
                int slot = b.getInt(offset), handle = b.getInt(offset + 4), id = b.getInt(offset + 8);
                int quantity = b.getInt(offset + 12), sortId = b.getInt(offset + 16), mask = b.getInt(offset + 20);
                int entryFlags = b.getInt(offset + 24), reserved = b.getInt(offset + 28), kind = id >>> 28;
                boolean isKey = (entryFlags & 1) != 0;
                valid &= slot > previousSlot && slot >= 0 && slot < (long) key + normal
                    && handle != 0 && handle != -1 && (kind == 0 || kind == 1 || kind == 2 || kind == 4 || kind == 8)
                    && quantity >= 0 && quantity <= 999999999 && (mask & ~EQUIPMENT_BITS) == 0
                    && (equipment & mask) == 0 && (entryFlags & ~1) == 0 && reserved == 0 && isKey == (slot < key);
                previousSlot = slot; equipment |= mask;
                entries.add(new Entry(slot, handle, id, quantity, sortId, mask, isKey));
            }
            VarHandle.acquireFence();
            if ((int) INT.getAcquire(b, base) != sequence) continue;
            if (!valid || magic != MAGIC || state < UNAVAILABLE || state > READY
                    || frame < 0 || time < 0 || (flags & ~3) != 0 || normal < 0 || normal > 4096
                    || key < 0 || key > 4096 || total < 0 || total > normal + key
                    || count != Math.min(total, maxEntries) || ((flags & TRUNCATED) != 0) != (total > count)) return Optional.empty();
            if (state != READY && (fingerprint != 0 || count != 0 || total != 0 || normal != 0 || key != 0 || flags != 0))
                return Optional.empty();
            if (state == READY && (fingerprint == 0 || frame <= 0 || time <= 0)) return Optional.empty();
            if (version >= 2 && (talismans < 0 || talismans > 4 ||
                (writable & ~(version == 3 ? availableEquipmentSlots(talismans) : 0xF000)) != 0 ||
                (state != READY && (token != 0 || writable != 0 || talismans != 0 || zone != 0)) ||
                (state == READY && token == 0) || ((flags & MC_CONTROL) == 0 && writable != 0) ||
                ((flags & TRUNCATED) != 0 && writable != 0) || b.getInt(base+footer+20) != 0 ||
                b.getLong(base+footer+24) != 0)) return Optional.empty();
            return Optional.of(new HostInventorySample(frame, time, fingerprint, life, state, flags, total, normal, key, entries,token,zone,writable,talismans));
        }
        return Optional.empty();
    }
}
