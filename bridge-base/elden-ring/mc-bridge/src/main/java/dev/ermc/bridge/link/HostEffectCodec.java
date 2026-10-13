package dev.ermc.bridge.link;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Optional HEFX reader. Accepts the poison-only v1 or extended v2; never guesses unknown layouts. */
public final class HostEffectCodec {
    private HostEffectCodec() {}
    private static final VarHandle INT = MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    public static boolean read(ByteBuffer buffer, HostEffectState out) {
        int base = Protocol.OFF_HOST_EFFECTS;
        if (buffer.limit() < base + 0x20) return false;
        ByteBuffer buf = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        for (int tries = 0; tries < 2000; tries++) {
            int seq = (int) INT.getAcquire(buf, base + Protocol.HFX_SEQ);
            if (seq == 0) return false;
            if ((seq & 1) != 0) { Thread.onSpinWait(); continue; }
            int magic = buf.getInt(base + Protocol.HFX_MAGIC);
            int version = buf.getInt(base + Protocol.HFX_VERSION);
            if (version != 1 && version != Protocol.HOST_EFFECTS_VERSION) return false;
            if (version == 2 && buf.limit() < base + Protocol.HOST_EFFECTS_SIZE) return false;
            int flags = buf.getInt(base + Protocol.HFX_FLAGS);
            long frame = buf.getLong(base + Protocol.HFX_FRAME);
            int life = buf.getInt(base + Protocol.HFX_HOST_LIFE);
            int poison = buf.getInt(base + Protocol.HFX_POISON_ID);
            int rot = version == 2 ? buf.getInt(base + Protocol.HFX_ROT_ID) : -1;
            int frost = version == 2 ? buf.getInt(base + Protocol.HFX_FROST_ID) : -1;
            int sleep = version == 2 ? buf.getInt(base + Protocol.HFX_SLEEP_ID) : -1;
            int madness = version == 2 ? buf.getInt(base + Protocol.HFX_MADNESS_ID) : -1;
            int frostProcs = version == 2 ? buf.getInt(base + Protocol.HFX_FROST_PROCS) : 0;
            VarHandle.acquireFence();
            if ((int) INT.getAcquire(buf, base + Protocol.HFX_SEQ) != seq) continue;
            if (version == 1) flags &= Protocol.HOST_EFFECT_VALID | Protocol.HOST_EFFECT_MC_CONTROL | Protocol.HOST_EFFECT_POISON;
            if (magic != Protocol.HOST_EFFECTS_MAGIC
                    || inconsistent(flags, Protocol.HOST_EFFECT_POISON, poison)
                    || inconsistent(flags, Protocol.HOST_EFFECT_ROT, rot)
                    || inconsistent(flags, Protocol.HOST_EFFECT_FROST, frost)
                    || inconsistent(flags, Protocol.HOST_EFFECT_SLEEP, sleep)
                    || inconsistent(flags, Protocol.HOST_EFFECT_MADNESS, madness)) return false;
            out.version = version;
            out.flags = flags;
            out.frame = frame;
            out.hostLife = life;
            out.poisonEffectId = poison;
            out.rotEffectId = rot;
            out.frostEffectId = frost;
            out.sleepEffectId = sleep;
            out.madnessEffectId = madness;
            out.frostProcs = frostProcs;
            return true;
        }
        return false;
    }

    private static boolean inconsistent(int flags, int bit, int id) {
        return ((flags & bit) != 0) != (id >= 0);
    }
}
