package dev.ermc.bridge.link;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/** Read-only HINT. Unknown/rest-transition observations never authorize automatic return. */
public record HostInteractionSample(int hostLife, long hostStartMs, int hostGeneration,
                                    long frame, int zone, int flags, long sampledAt,
                                    int hostPid, int restFlags) {
    public static final int OFFSET=0x3D0000, SIZE=64, MAGIC=0x544E4948;
    public static final int VALID=1, MENU_BLOCKING=2, CAN_RESUME=4, RESTING=8,
        ACTION_READY=16, HOST_READY=32, REST_MASK=0x1E0;
    private static final VarHandle INT=MethodHandles.byteBufferViewVarHandle(int[].class,ByteOrder.LITTLE_ENDIAN);
    public boolean ready(){return (flags&VALID)!=0;}
    public boolean menuBlocking(){return ready()&&(flags&MENU_BLOCKING)!=0;}
    public boolean canResume(){return ready()&&(flags&CAN_RESUME)!=0;}
    public boolean resting(){return ready()&&(flags&RESTING)!=0;}
    public boolean currentFor(int pid,long start,int generation,int life,long hostFrame,long now){
        return ready()&&pid>0&&hostPid==pid&&start>0&&hostStartMs==start&&hostGeneration==generation&&hostLife==life
            &&frame>0&&Math.abs(frame-hostFrame)<=120&&sampledAt>=start&&sampledAt>=now-500&&sampledAt<=now+100;
    }
    public static Optional<HostInteractionSample> read(ByteBuffer source,int base){
        if(!source.isDirect()||base<0||(base&7)!=0||source.limit()-base<SIZE)return Optional.empty();
        var b=source.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        for(int attempt=0;attempt<4;attempt++){
            int seq=(int)INT.getAcquire(b,base);if(seq==0)return Optional.empty();
            if((seq&1)!=0){Thread.onSpinWait();continue;}
            int magic=b.getInt(base+4),version=b.getInt(base+8),flags=b.getInt(base+12);
            long frame=b.getLong(base+16),time=b.getLong(base+24),start=b.getLong(base+32);
            int pid=b.getInt(base+40),generation=b.getInt(base+44),life=b.getInt(base+48),zone=b.getInt(base+52);
            int rest=b.getInt(base+56),reserved=b.getInt(base+60);
            VarHandle.acquireFence();if((int)INT.getAcquire(b,base)!=seq)continue;
            if(magic!=MAGIC||version!=1||(flags&~63)!=0||(rest&~REST_MASK)!=0||reserved!=0
                ||frame<0||time<0||start<0||pid<0||generation<0)return Optional.empty();
            if((flags&VALID)==0&&(flags!=0||rest!=0))return Optional.empty();
            if((flags&VALID)!=0&&(frame==0||time==0||start==0||pid==0||generation==0))return Optional.empty();
            if(((flags&RESTING)!=0)!=(rest!=0))return Optional.empty();
            if((flags&CAN_RESUME)!=0&&((flags&(VALID|ACTION_READY|HOST_READY))!=(VALID|ACTION_READY|HOST_READY)
                ||(flags&(MENU_BLOCKING|RESTING))!=0))return Optional.empty();
            return Optional.of(new HostInteractionSample(life,start,generation,frame,zone,flags,time,pid,rest));
        }
        return Optional.empty();
    }
}
