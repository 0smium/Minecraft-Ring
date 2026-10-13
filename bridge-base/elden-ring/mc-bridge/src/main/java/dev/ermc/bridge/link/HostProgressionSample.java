package dev.ermc.bridge.link;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/** HPRG v1. The name/fingerprint is an observation, never a persistent character identifier. */
public record HostProgressionSample(long frame, int hostLife, int state, int level,
                                    int flags, long fingerprint, String name) {
    public static final int OFFSET = 0xD00, SIZE = 0x58, MAGIC = 0x47525048;
    public static final int READY = 2;
    private static final VarHandle INT = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    public boolean ready() { return state == READY; }

    /** Small bounded seqlock read; an absent, future, malformed or busy publisher is unknown. */
    public static Optional<HostProgressionSample> read(ByteBuffer source, int base) {
        if (base < 0 || source.limit() - base < SIZE || (base & 3) != 0) return Optional.empty();
        ByteBuffer b = source.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        for (int attempt = 0; attempt < 8; attempt++) {
            int sequence = (int) INT.getAcquire(b, base);
            if (sequence == 0) return Optional.empty();
            if ((sequence & 1) != 0) { Thread.onSpinWait(); continue; }
            int magic = b.getInt(base + 4), version = b.getInt(base + 8), state = b.getInt(base + 12);
            long frame = b.getLong(base + 16);
            int life = b.getInt(base + 24), level = b.getInt(base + 28), flags = b.getInt(base + 32);
            int slot = b.getInt(base + 36);
            long fingerprint = b.getLong(base + 40);
            char[] name = new char[17];
            for (int i = 0; i < name.length; i++) name[i] = b.getChar(base + 48 + i * 2);
            int count = b.getShort(base + 82) & 0xffff, reserved = b.getInt(base + 84);
            VarHandle.acquireFence();
            if ((int) INT.getAcquire(b, base) != sequence) continue;
            if (magic != MAGIC || version != 1 || frame < 0 || state < 0 || state > READY
                    || slot != -1 || reserved != 0 || (flags & ~15) != 0 || (flags & 4) == 0) return Optional.empty();
            if (state != READY) {
                if (level != 0 || fingerprint != 0 || count != 0 || flags != 4) return Optional.empty();
                for (char c : name) if (c != 0) return Optional.empty();
                return Optional.of(new HostProgressionSample(frame, life, state, 0, flags, 0, ""));
            }
            if (level < 1 || level > 713 || count < 1 || count > 16 || (flags & 7) != 7) return Optional.empty();
            long hash = 0xcbf29ce484222325L;
            for (int i = 0; i < count; i++) {
                char c = name[i];
                if (c == 0 || Character.isHighSurrogate(c) && (i + 1 >= count || !Character.isLowSurrogate(name[i + 1]))
                        || Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(name[i - 1]))) return Optional.empty();
                hash = (hash ^ (c & 255)) * 0x100000001b3L;
                hash = (hash ^ (c >>> 8)) * 0x100000001b3L;
            }
            for (int i = count; i < name.length; i++) if (name[i] != 0) return Optional.empty();
            if (fingerprint != (hash == 0 ? 1 : hash)) return Optional.empty();
            return Optional.of(new HostProgressionSample(frame, life, state, level, flags, fingerprint, new String(name, 0, count)));
        }
        return Optional.empty();
    }
}
