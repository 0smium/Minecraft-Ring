package dev.ermc.bridge.link;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/** Nonblocking single-producer gameplay mailbox, independent of developer commands. */
public final class HostEquipmentChannel {
    private static final VarHandle INT=MethodHandles.byteBufferViewVarHandle(int[].class,ByteOrder.LITTLE_ENDIAN);
    public static final int OFFSET=0x370000, SIZE=160;
    private final ByteBuffer buffer;
    private int pendingSequence;
    private long pendingNonce;
    public HostEquipmentChannel(ByteBuffer buffer) {
        if (!buffer.isDirect() || buffer.limit()<OFFSET+SIZE) throw new IllegalArgumentException("shared buffer");
        this.buffer=buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    }
    public long submit(HostInventorySample sample,int slot,HostInventorySample.Entry item,
                       int hostPid,int generation,int life,long hostStart,long now) {
        if (sample==null || !sample.canWriteSlot(slot) || sample.hostLife()!=life || hostPid<=0 ||
            hostStart<=0 || sample.sampledAt()<hostStart || sample.sampledAt()<now-2000 || sample.sampledAt()>now+1000 ||
            (item!=null && (!sample.entries().contains(item) || item.keyItem() || item.itemId()>>>28!=1))) return 0;
        int req=(int)INT.getAcquire(buffer,OFFSET),resp=(int)INT.getAcquire(buffer,OFFSET+4);
        if (req!=resp) return 0;
        int seq=req+1;if(seq==0)seq=1;
        long nonce;do{nonce=ThreadLocalRandom.current().nextLong();}while(nonce==0);
        int b=OFFSET+16;
        buffer.putInt(b,1);buffer.putInt(b+4,item==null?2:1);buffer.putInt(b+8,hostPid);buffer.putInt(b+12,generation);
        buffer.putInt(b+16,life);buffer.putInt(b+20,sample.zone());buffer.putInt(b+24,slot);buffer.putInt(b+28,item==null?-1:item.slot());
        buffer.putInt(b+32,item==null?0:item.handle());buffer.putInt(b+36,item==null?0:item.itemId());
        buffer.putLong(b+40,hostStart);buffer.putLong(b+48,sample.fingerprint());buffer.putLong(b+56,sample.snapshotToken());
        buffer.putLong(b+64,now+5000);buffer.putLong(b+72,nonce);
        pendingSequence=seq;pendingNonce=nonce;
        INT.setRelease(buffer,OFFSET,seq);
        return nonce;
    }
    public Optional<HostEquipmentResult> result(long nonce) {
        if(nonce==0 || nonce!=pendingNonce || (int)INT.getAcquire(buffer,OFFSET+4)!=pendingSequence) return Optional.empty();
        int b=OFFSET+96;
        if(buffer.getInt(b)!=1 || buffer.getLong(b+32)!=nonce || buffer.getInt(b+28)!=0) return Optional.empty();
        var result=new HostEquipmentResult(buffer.getInt(b+4),buffer.getInt(b+8),buffer.getInt(b+12),buffer.getInt(b+16),
            buffer.getInt(b+20),buffer.getInt(b+24),buffer.getLong(b+32),buffer.getLong(b+40),buffer.getLong(b+48),buffer.getLong(b+56));
        VarHandle.acquireFence();
        return (int)INT.getAcquire(buffer,OFFSET+4)==pendingSequence && (int)INT.getAcquire(buffer,OFFSET)==pendingSequence
            ? Optional.of(result) : Optional.empty();
    }
}
