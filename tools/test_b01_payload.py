"""Exercise the actual B01Payload.CODEC with Minecraft 1.21.1's real byte buffer.

After a normal compileJava, obtain the named dependency classpath from that cache:
  python tools/test_b01_skills.py --write-classpath runtime/experiments/b01-classpath.txt
  python tools/test_b01_payload.py --classpath-file runtime/experiments/b01-classpath.txt

No Gradle invocation, downloads, game/server/client startup, or production stubs.
CharacterBinding, CapabilitySnapshot and B01Payload are compiled from current
sources into a temporary directory, ahead of any cached project classes.
The CODEC validates its own wire values; authenticated recipient/world identity
is separately checked by ProgressionClient, which this offline test does not run.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
COMMON = ROOT / 'bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge'

JAVA = r'''
import dev.ermc.bridge.api.*;
import dev.ermc.bridge.api.CapabilitySnapshot.*;
import dev.ermc.bridge.gameplay.runtime.B01Payload;
import io.netty.buffer.Unpooled;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

public final class B01PayloadCheck {
    static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID CAMPAIGN = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID CHARACTER = UUID.fromString("00000000-0000-0000-0000-000000000003");
    static int rejected;
    interface Action {void run();}
    static void check(boolean condition,String label) {if(!condition)throw new AssertionError(label);}
    static void reject(Action action,String label) {
        try {action.run();} catch(RuntimeException expected) {rejected++;return;}
        throw new AssertionError("Accepted " + label);
    }
    static RegistryFriendlyByteBuf buffer() {return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);}
    static Grant grant(Ability ability,int rank,double cap,boolean visible) {
        return rank==0 ? new Grant(0,visible,Set.of(),Map.of())
            : new Grant(rank,visible,Set.of(ability==Ability.SPEED?"ground_custom":"single_target"),
                Map.of(ability==Ability.SPEED?"speed":"range",new NumericRange(ability==Ability.SPEED?.05:1,cap)));
    }
    static CapabilitySnapshot snapshot(UUID player,int rank,double speed,double aura) {
        return new CapabilitySnapshot(new CharacterBinding(player,CAMPAIGN,CHARACTER),7,
            Map.of(Ability.SPEED,grant(Ability.SPEED,rank,speed,true),Ability.AURA,grant(Ability.AURA,rank,aura,rank!=0)));
    }
    static B01Payload payload(CapabilitySnapshot snapshot,boolean challenge) {
        return new B01Payload(OWNER,challenge,snapshot,100,6,3,3,"角色 · 技能点 3/6");
    }
    static void roundtrip(B01Payload source) {
        var b=buffer();try {
            B01Payload.CODEC.encode(b,source);
            check(B01Payload.CODEC.decode(b).equals(source),"Roundtrip changed payload");
            check(!b.isReadable(),"CODEC did not consume its complete encoded payload");
        } finally {b.release();}
    }
    static void corrupt(B01Payload source,Consumer<RegistryFriendlyByteBuf> mutation,String label) {
        var b=buffer();try {
            B01Payload.CODEC.encode(b,source);mutation.accept(b);b.readerIndex(0);
            reject(()->B01Payload.CODEC.decode(b),label);
        } finally {b.release();}
    }
    // Locate numeric fields in actual CODEC output through the actual MC buffer API.
    static int speedRank(RegistryFriendlyByteBuf b) {
        b.readerIndex(0);b.readUUID();b.readBoolean();check(b.readBoolean(),"Expected snapshot fixture");
        b.readUUID();b.readUUID();b.readLong();return b.readerIndex();
    }
    static int speedMinimum(RegistryFriendlyByteBuf b) {speedRank(b);b.readVarInt();b.readBoolean();return b.readerIndex();}
    static int auraMinimum(RegistryFriendlyByteBuf b) {
        speedMinimum(b);b.readDouble();b.readDouble();b.readVarInt();b.readBoolean();return b.readerIndex();
    }
    static void malformedCounters(int level,int earned,int spent,int remaining,String status,String label) {
        var b=buffer();try {
            // Deliberately malformed sender fixture, decoded by the production CODEC.
            b.writeUUID(OWNER);b.writeBoolean(true);b.writeBoolean(false);
            b.writeVarInt(level);b.writeVarInt(earned);b.writeVarInt(spent);b.writeVarInt(remaining);b.writeUtf(status,32767);
            reject(()->B01Payload.CODEC.decode(b),label);
        } finally {b.release();}
    }
    public static void main(String[] args) {
        roundtrip(payload(null,true)); // unbound/loading challenge state, no authorization
        roundtrip(payload(null,false)); // free mode without a snapshot
        roundtrip(payload(snapshot(OWNER,0,.3,2.5),true)); // explicit locked grants and visibility
        double[] speed={.30,.33,.36},aura={2.5,3,3.5};
        for(int i=0;i<3;i++)roundtrip(payload(snapshot(OWNER,i+1,speed[i],aura[i]),true));
        roundtrip(new B01Payload(OWNER,false,snapshot(OWNER,3,.6,6),713,100000,100000,0,"x".repeat(256)));
        System.out.println("PASS: production CODEC roundtrips unbound, free, locked, all three ranks, boundary counts and Unicode status");

        reject(()->payload(snapshot(UUID.randomUUID(),1,.3,2.5),true),"snapshot UUID != payload owner");
        reject(()->new B01Payload(OWNER,true,null,0,0,0,0,"x".repeat(257)),"constructor status >256");
        B01Payload valid=payload(snapshot(OWNER,1,.3,2.5),true);
        corrupt(valid,b->b.writerIndex(b.writerIndex()-1),"truncated packet");
        corrupt(valid,b->b.setByte(speedRank(b),4),"rank 4");
        corrupt(valid,b->b.setLong(speedRank(b)-8,0),"revision 0");
        System.out.println("PASS: mismatched snapshot owner, long status, truncated wire, rank 4 and revision 0 are rejected");

        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            corrupt(valid,b->b.setDouble(speedMinimum(b),bad),"non-finite Speed minimum");
            corrupt(valid,b->b.setDouble(speedMinimum(b)+8,bad),"non-finite Speed maximum");
            corrupt(valid,b->b.setDouble(auraMinimum(b),bad),"non-finite Aura minimum");
            corrupt(valid,b->b.setDouble(auraMinimum(b)+8,bad),"non-finite Aura maximum");
        }
        corrupt(valid,b->b.setDouble(speedMinimum(b),.049),"Speed below lower bound");
        corrupt(valid,b->b.setDouble(speedMinimum(b)+8,.601),"Speed above upper bound");
        corrupt(valid,b->b.setDouble(auraMinimum(b),.999),"Aura below lower bound");
        corrupt(valid,b->b.setDouble(auraMinimum(b)+8,6.001),"Aura above upper bound");
        corrupt(valid,b->b.setDouble(speedMinimum(b),.4),"inverted Speed range");
        System.out.println("PASS: real decoder rejects NaN, both infinities, out-of-bounds and inverted numeric limits");

        malformedCounters(-1,0,0,0,"ok","negative level");
        malformedCounters(714,0,0,0,"ok","level above 713");
        for(int bad:new int[]{-1,100001}) {
            malformedCounters(1,bad,0,0,"ok","earned out of bounds");
            malformedCounters(1,0,bad,0,"ok","spent out of bounds");
            malformedCounters(1,0,0,bad,"ok","remaining out of bounds");
        }
        malformedCounters(0,0,0,0,"x".repeat(257),"wire status above 256");
        System.out.println("PASS: production decoder rejects invalid level/point counters and overlong wire status ("+rejected+" rejected cases)");
        System.out.println("LIMIT: recipient UUID authentication, world/session ownership and network delivery require the separate client/runtime checks");
    }
}
'''


def arguments_file(path, args):
    # JDK @argfiles avoid Windows's command-line length limit for Loom classpaths.
    path.write_text('\n'.join('"' + str(arg).replace('\\', '/').replace('"', '\\"') + '"' for arg in args), encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath-file', type=Path, required=True)
    parser.add_argument('--java-home', type=Path)
    args = parser.parse_args()
    classpath = args.classpath_file.read_text(encoding='utf-8-sig').strip()
    java_home = args.java_home or next((ROOT / '.tools/java').glob('*/bin/java.exe')).parent.parent
    executable = '.exe' if os.name == 'nt' else ''
    sources = [COMMON / 'api/CharacterBinding.java', COMMON / 'api/CapabilitySnapshot.java', COMMON / 'gameplay/runtime/B01Payload.java']
    with tempfile.TemporaryDirectory(prefix='ermc-b01-wire-') as directory:
        output = Path(directory)
        fixture = output / 'B01PayloadCheck.java'
        fixture.write_text(JAVA, encoding='utf-8')
        compile_args = output / 'compile.args'
        arguments_file(compile_args, ['-encoding', 'UTF-8', '-cp', classpath, '-d', output, *sources, fixture])
        subprocess.run([str(java_home / 'bin' / f'javac{executable}'), '@' + str(compile_args)], cwd=ROOT, check=True)
        run_args = output / 'run.args'
        arguments_file(run_args, ['-cp', str(output) + os.pathsep + classpath, 'B01PayloadCheck'])
        subprocess.run([str(java_home / 'bin' / f'java{executable}'), '@' + str(run_args)], cwd=ROOT, check=True)


if __name__ == '__main__':
    main()
