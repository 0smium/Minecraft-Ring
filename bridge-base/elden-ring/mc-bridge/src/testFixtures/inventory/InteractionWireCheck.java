import dev.ermc.bridge.link.HostInteractionSample;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public final class InteractionWireCheck {
    private static byte[] wire;
    private static int checks;
    private static ByteBuffer bytes(){return ByteBuffer.allocateDirect(wire.length).order(ByteOrder.LITTLE_ENDIAN).put(wire).flip();}
    private static void check(boolean condition){checks++;if(!condition)throw new AssertionError("check "+checks);}
    private static void reject(Consumer<ByteBuffer> edit){var b=bytes();edit.accept(b);check(HostInteractionSample.read(b,0).isEmpty());}
    public static void main(String[] args)throws Exception{
        wire=Files.readAllBytes(Path.of(args[0]));check(wire.length==64);
        var s=HostInteractionSample.read(bytes(),0).orElseThrow();
        check(s.ready()&&s.canResume()&&!s.menuBlocking()&&!s.resting());
        check(s.hostPid()==42&&s.hostStartMs()==1000&&s.hostGeneration()==2&&s.hostLife()==3&&s.frame()==100&&s.zone()==10);
        check(s.currentFor(42,1000,2,3,100,1500));
        check(!s.currentFor(43,1000,2,3,100,1500));
        check(!s.currentFor(42,999,2,3,100,1500));
        check(!s.currentFor(42,1000,3,3,100,1500));
        check(!s.currentFor(42,1000,2,4,100,1500));
        check(!s.currentFor(42,1000,2,3,221,1500));
        check(!s.currentFor(42,1000,2,3,100,2001));
        check(!s.currentFor(42,1000,2,3,100,1399));
        reject(b->b.putInt(0,0));reject(b->b.putInt(0,3));reject(b->b.putInt(4,0));reject(b->b.putInt(8,2));
        reject(b->b.putInt(12,64));reject(b->b.putInt(12,4));reject(b->b.putInt(12,55));
        reject(b->b.putInt(56,0x20));reject(b->b.putInt(56,0x800));reject(b->b.putInt(60,1));
        reject(b->b.putLong(16,0));reject(b->b.putLong(24,0));reject(b->b.putLong(32,0));
        reject(b->b.putInt(40,0));reject(b->b.putInt(44,0));reject(b->b.limit(63));
        var rest=bytes().putInt(12,57).putInt(56,0xA0);
        var resting=HostInteractionSample.read(rest,0).orElseThrow();check(resting.resting()&&!resting.canResume());
        var menu=HostInteractionSample.read(bytes().putInt(12,51),0).orElseThrow();check(menu.menuBlocking()&&!menu.canResume());
        var unknown=HostInteractionSample.read(bytes().putInt(12,0),0).orElseThrow();check(!unknown.ready()&&!unknown.currentFor(42,1000,2,3,100,1500));
        check(HostInteractionSample.read(ByteBuffer.wrap(wire),0).isEmpty());
        check(HostInteractionSample.read(bytes(),-1).isEmpty());
        check(HostInteractionSample.read(bytes(),1).isEmpty());
        System.out.println("PASS: "+checks+" HINT Java checks: native layout, flags, conservative resume, unknown data, malformed frames and session/generation/life/freshness gates");
    }
}
