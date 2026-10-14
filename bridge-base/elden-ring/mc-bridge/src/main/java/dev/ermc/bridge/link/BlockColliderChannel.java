package dev.ermc.bridge.link;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/** Independent MBCL v1. One local server publishes bounded, complete replacement snapshots. */
public final class BlockColliderChannel {
    public static final int OFFSET = 0x400000, STATUS_OFFSET = 0x410000, MAGIC = 0x4C43424D;
    public static final int MAX_BOXES = 512, HEADER_SIZE = 96, BOX_SIZE = 24;
    private static final VarHandle INT = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    public record Session(int hostPid, int generation, int life, int zone, long hostStart,
                          int mcPid, long mcStart) {
        public boolean valid() { return hostPid > 0 && generation > 0 && life > 0 && zone != 0
            && hostStart > 0 && mcPid > 0 && mcStart > 0; }
    }
    public record Status(int state, long sampledAt, long revision, int owned, int desired, int pending,
                         int quarantined, int created, int removed, int error, int flags, long worldEpoch) {}
    private BlockColliderChannel() {}

    public static Session session(ByteBuffer b, int zone) {
        return new Session(b.getInt(0x20), b.getInt(0x40), b.getInt(0x58), zone,
            b.getLong(0x28), b.getInt(0x24), b.getLong(0x30));
    }
    public static boolean registeredTo(ByteBuffer b, int processId, long started) {
        return processId > 0 && started > 0 && b.getInt(0x24) == processId && b.getLong(0x30) == started;
    }

    public static boolean validBoxes(float[] center, float[] boxes) {
        if (center == null || center.length != 3 || boxes == null || boxes.length % 6 != 0
                || boxes.length > MAX_BOXES * 6) return false;
        for (float v : center) if (!Float.isFinite(v) || Math.abs(v) > 1_000_000) return false;
        for (int i = 0; i < boxes.length; i += 6) for (int a = 0; a < 3; a++) {
            float low = boxes[i + a], high = boxes[i + a + 3];
            if (!Float.isFinite(low) || !Float.isFinite(high) || high - low < .002f || high - low > 64
                    || Math.abs(low - center[a]) > 64 || Math.abs(high - center[a]) > 64) return false;
        }
        return true;
    }

    public static boolean write(ByteBuffer b, Session session, long epoch, long revision, long now,
                                float[] center, float[] boxes, boolean complete, boolean capped) {
        boolean valid = session != null && session.valid() && session.equals(session(b, session.zone()))
            && epoch != 0 && revision > 0 && now > 0 && validBoxes(center, boxes);
        int base = OFFSET, seq = b.getInt(base) & ~1;
        INT.setOpaque(b, base, seq + 1); VarHandle.storeStoreFence();
        b.putInt(base + 4, MAGIC); b.putInt(base + 8, 1);
        b.putInt(base + 12, valid ? 1 | (complete ? 2 : 0) | (capped ? 4 : 0) : 0);
        b.putLong(base + 16, valid ? revision : 0); b.putLong(base + 24, now);
        b.putLong(base + 32, valid ? session.hostStart() : 0); b.putLong(base + 40, valid ? session.mcStart() : 0);
        b.putInt(base + 48, valid ? session.hostPid() : 0); b.putInt(base + 52, valid ? session.generation() : 0);
        b.putInt(base + 56, valid ? session.life() : 0); b.putInt(base + 60, valid ? session.zone() : 0);
        b.putInt(base + 64, valid ? session.mcPid() : 0);
        int count = valid && complete ? boxes.length / 6 : 0;
        b.putInt(base + 68, count);
        for (int a = 0; a < 3; a++) b.putFloat(base + 72 + a * 4, valid ? center[a] : 0);
        b.putInt(base + 84, 0); b.putLong(base + 88, valid ? epoch : 0);
        for (int i = 0; i < count * 6; i++) b.putFloat(base + HEADER_SIZE + i * 4, boxes[i]);
        INT.setRelease(b, base, seq + 2);
        return valid;
    }

    public static Optional<Status> status(ByteBuffer b, Session session, long now) {
        if (session == null || !session.valid()) return Optional.empty();
        int p = STATUS_OFFSET;
        for (int attempt = 0; attempt < 3; attempt++) {
            int before = (int) INT.getAcquire(b, p);
            if ((before & 1) != 0) continue;
            if (b.getInt(p + 4) != MAGIC || b.getInt(p + 8) != 1) return Optional.empty();
            var observed = new Session(b.getInt(p + 32), b.getInt(p + 36), b.getInt(p + 40), b.getInt(p + 44),
                b.getLong(p + 16), b.getInt(p + 96), b.getLong(p + 104));
            Status result = new Status(b.getInt(p + 12), b.getLong(p + 48), b.getLong(p + 56),
                b.getInt(p + 64), b.getInt(p + 68), b.getInt(p + 72), b.getInt(p + 76),
                b.getInt(p + 80), b.getInt(p + 84), b.getInt(p + 88), b.getInt(p + 92), b.getLong(p + 112));
            VarHandle.acquireFence();
            if (before != (int) INT.getAcquire(b, p)) continue;
            if (!session.equals(observed) || now < result.sampledAt() || now - result.sampledAt() > 2000
                    || result.state() < 0 || result.state() > 4) return Optional.empty();
            return Optional.of(result);
        }
        return Optional.empty();
    }
}
