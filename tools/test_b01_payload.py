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
CHARACTER = ROOT / 'bridge-base/elden-ring/mc-bridge/character-bridge/src/main/java/dev/ermc/bridge'

JAVA = r'''
import dev.ermc.bridge.api.*;
import dev.ermc.bridge.api.CapabilitySnapshot.*;
import dev.ermc.bridge.gameplay.runtime.B01Payload;
import dev.ermc.bridge.gameplay.capability.AbilityCatalog;
import io.netty.buffer.Unpooled;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
public final class B01PayloadCheck {
    static final UUID OWNER=new UUID(0,1), CAMPAIGN=new UUID(0,2), CHARACTER=new UUID(0,3);
    static int rejected;
    interface Action {void run();}
    static void check(boolean c,String label){if(!c)throw new AssertionError(label);}
    static void reject(Action a,String label){try{a.run();}catch(RuntimeException expected){rejected++;return;}throw new AssertionError("Accepted "+label);}
    static RegistryFriendlyByteBuf buffer(){return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);}
    static CapabilitySnapshot snapshot(Map<Ability,Integer> ranks){return AbilityCatalog.instance().policy(7,true).snapshot(new CharacterBinding(OWNER,CAMPAIGN,CHARACTER),ranks);}
    static B01Payload payload(CapabilitySnapshot s){return new B01Payload(OWNER,true,s,164,116,80,36,"成长 · 真实 0 + 测试 116");}
    static void roundtrip(B01Payload p){var b=buffer();try{B01Payload.CODEC.encode(b,p);check(B01Payload.CODEC.decode(b).equals(p),"changed roundtrip");check(!b.isReadable(),"incomplete decode");}finally{b.release();}}
    static void corrupt(B01Payload p,Consumer<RegistryFriendlyByteBuf> action,String label){var b=buffer();try{B01Payload.CODEC.encode(b,p);action.accept(b);b.readerIndex(0);reject(()->B01Payload.CODEC.decode(b),label);}finally{b.release();}}
    static Map<String,Integer> positions(RegistryFriendlyByteBuf b) {
        b.readerIndex(0);b.readVarInt();b.readUUID();b.readBoolean();check(b.readBoolean(),"fixture snapshot");
        b.readUUID();b.readUUID();Map<String,Integer> p=new HashMap<>();p.put("revision",b.readerIndex());b.readLong();
        p.put("count",b.readerIndex());int count=b.readVarInt();
        for(int i=0;i<count;i++) {
            String id=b.readUtf(48);p.put(id+":rank",b.readerIndex());b.readVarInt();b.readBoolean();int modes=b.readVarInt();
            for(int j=0;j<modes;j++)b.readUtf(64);
            int limits=b.readVarInt();for(int j=0;j<limits;j++){String key=b.readUtf(128);p.put(id+":"+key,b.readerIndex());b.readDouble();b.readDouble();}
        }
        return p;
    }
    static void malformedCounters(int level,int earned,int spent,int remaining,String status) {
        var b=buffer();try{b.writeVarInt(2);b.writeUUID(OWNER);b.writeBoolean(true);b.writeBoolean(false);b.writeVarInt(level);b.writeVarInt(earned);b.writeVarInt(spent);b.writeVarInt(remaining);b.writeUtf(status,32767);reject(()->B01Payload.CODEC.decode(b),"counter/status");}finally{b.release();}
    }
    public static void main(String[] args){
        roundtrip(payload(null));roundtrip(new B01Payload(OWNER,false,null,0,0,0,0,"自由"));
        roundtrip(payload(snapshot(Map.of())));
        for(var preset:AbilityCatalog.instance().presets().values())roundtrip(payload(snapshot(preset.ranks())));
        B01Payload valid=payload(snapshot(AbilityCatalog.instance().presets().get("journey").ranks()));
        reject(()->new B01Payload(UUID.randomUUID(),true,valid.snapshot(),1,1,0,1,"x"),"mismatched owner");
        reject(()->payload(new CapabilitySnapshot(valid.snapshot().binding(),7,Map.of(Ability.SPEED,valid.snapshot().abilities().get(Ability.SPEED)))),"missing abilities");
        Map<Ability,Grant> unknown=new HashMap<>(valid.snapshot().abilities());unknown.put(new Ability("timer"),new Grant(0,true,Set.of(),Map.of()));
        reject(()->payload(new CapabilitySnapshot(valid.snapshot().binding(),7,unknown)),"unknown abilities");
        corrupt(valid,b->b.setByte(0,1),"old protocol");
        corrupt(valid,b->b.writerIndex(b.writerIndex()-1),"truncated");
        corrupt(valid,b->b.setByte(positions(b).get("speed:rank"),8),"unsupported rank");
        corrupt(valid,b->b.setLong(positions(b).get("revision"),0),"revision zero");
        corrupt(valid,b->b.setByte(positions(b).get("count"),65),"oversized count");
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            corrupt(valid,b->b.setDouble(positions(b).get("speed:speed"),bad),"bad minimum");
            corrupt(valid,b->b.setDouble(positions(b).get("aura:range")+8,bad),"bad maximum");
        }
        corrupt(valid,b->b.setDouble(positions(b).get("speed:speed"),.049),"below catalog lower bound");
        corrupt(valid,b->b.setDouble(positions(b).get("speed:speed")+8,.37),"above rank bound");
        corrupt(valid,b->b.setDouble(positions(b).get("aura:WallRange")+8,1),"relaxed fixed guard");
        corrupt(valid,b->b.setDouble(positions(b).get("speed:speed"),.4),"inverted range");
        malformedCounters(-1,0,0,0,"ok");malformedCounters(714,0,0,0,"ok");
        for(int bad:new int[]{-1,100001}){malformedCounters(1,bad,0,0,"ok");malformedCounters(1,0,bad,0,"ok");malformedCounters(1,0,0,bad,"ok");}
        malformedCounters(1,0,0,0,"x".repeat(257));
        System.out.println("PASS: production policy_v2 CODEC, all five 14-ability presets, locked/free/loading, UTF-8, "+rejected+" corrupt packet cases; real Minecraft buffer, no games");
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
    sources = [COMMON / 'api/CharacterBinding.java', COMMON / 'api/CapabilitySnapshot.java', CHARACTER / 'gameplay/runtime/B01Payload.java', CHARACTER / 'gameplay/capability/AbilityCatalog.java', CHARACTER / 'gameplay/capability/AbilityPolicy.java']
    with tempfile.TemporaryDirectory(prefix='ermc-b01-wire-') as directory:
        output = Path(directory)
        fixture = output / 'B01PayloadCheck.java'
        fixture.write_text(JAVA, encoding='utf-8')
        compile_args = output / 'compile.args'
        arguments_file(compile_args, ['-encoding', 'UTF-8', '-cp', classpath, '-d', output, *sources, fixture])
        subprocess.run([str(java_home / 'bin' / f'javac{executable}'), '@' + str(compile_args)], cwd=ROOT, check=True)
        run_args = output / 'run.args'
        arguments_file(run_args, ['-cp', str(output) + os.pathsep + str(ROOT / 'bridge-base/elden-ring/mc-bridge/character-bridge/src/main/resources') + os.pathsep + classpath, 'B01PayloadCheck'])
        subprocess.run([str(java_home / 'bin' / f'java{executable}'), '@' + str(run_args)], cwd=ROOT, check=True)


if __name__ == '__main__':
    main()
