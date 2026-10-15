"""Exercise production MBCL Java writer/native reader plus unknown-ray evidence; no games."""
from pathlib import Path
import subprocess
import tempfile
from test_b01_preparation import java_path

ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'bridge-base/elden-ring/mc-bridge/src/main/java'
ER=ROOT/'bridge-base/elden-ring/er-bridge'
JAVA=r'''
import dev.ermc.bridge.link.BlockColliderChannel;
import dev.ermc.bridge.TerrainRayEvidence;
import java.nio.*;
import java.nio.file.*;
public class Check {
 static int checks;
 static void require(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}
 public static void main(String[] args)throws Exception{
  ByteBuffer b=ByteBuffer.allocateDirect(0x420000).order(ByteOrder.LITTLE_ENDIAN);
  b.putInt(0x20,42);b.putInt(0x24,84);b.putLong(0x28,100);b.putLong(0x30,200);b.putInt(0x40,1);b.putInt(0x58,2);
  var s=BlockColliderChannel.session(b,60);int p=BlockColliderChannel.OFFSET;
  require(BlockColliderChannel.registeredTo(b,84,200));
  require(!BlockColliderChannel.registeredTo(b,85,200));
  b.putLong(0x30,201);require(!BlockColliderChannel.registeredTo(b,84,200));b.putLong(0x30,200);
  b.put(p-1,(byte)55);b.put(p+0x3060,(byte)66);
  float[] c={10,20,30},box={10,20,30,11,21,31};
  require(BlockColliderChannel.write(b,s,3,4,1000,c,box,true,false));
  require(b.getInt(p)==2&&b.getInt(p+68)==1&&b.getFloat(p+96)==10&&b.getLong(p+88)==3);
  require(b.getInt(p+12)==3&&b.get(p-1)==55&&b.get(p+0x3060)==66);
  byte[] fixture=new byte[0x3060];b.get(p,fixture);Files.write(Path.of(args[0]),fixture);
  require(BlockColliderChannel.write(b,s,3,5,1001,c,box,false,false)&&b.getInt(p+68)==0&&b.getInt(p+12)==1);
  require(BlockColliderChannel.write(b,s,3,6,1002,c,new float[0],true,false)&&b.getInt(p+12)==3);
  require(!BlockColliderChannel.write(b,s,3,7,1003,c,new float[]{0,0,0,Float.NaN,1,1},true,false)&&b.getInt(p+12)==0);
  require(!BlockColliderChannel.validBoxes(c,new float[]{10,20,30,10,21,31}));
  require(!BlockColliderChannel.validBoxes(c,new float[513*6]));
  require(!BlockColliderChannel.validBoxes(c,new float[]{1000,20,30,1001,21,31}));
  b.putInt(0x58,3);require(!BlockColliderChannel.write(b,s,3,8,1004,c,box,true,false));b.putInt(0x58,2);
  require(!BlockColliderChannel.write(b,s,0,8,1004,c,box,true,false));
  p=BlockColliderChannel.STATUS_OFFSET;
  b.putInt(p,2);b.putInt(p+4,BlockColliderChannel.MAGIC);b.putInt(p+8,1);b.putInt(p+12,3);
  b.putLong(p+16,100);b.putInt(p+32,42);b.putInt(p+36,1);b.putInt(p+40,2);b.putInt(p+44,60);
  b.putLong(p+48,1000);b.putLong(p+56,4);b.putInt(p+64,1);b.putInt(p+96,84);b.putLong(p+104,200);b.putLong(p+112,3);
  require(BlockColliderChannel.status(b,s,1001).orElseThrow().owned()==1);
  require(BlockColliderChannel.status(b,s,3001).isEmpty());
  require(BlockColliderChannel.status(b,s,999).isEmpty());
  b.putInt(p,3);require(BlockColliderChannel.status(b,s,1001).isEmpty());b.putInt(p,4);
  b.putInt(p+40,3);require(BlockColliderChannel.status(b,s,1001).isEmpty());
  require(TerrainRayEvidence.known(new int[]{0,1,0},0,3));
  require(!TerrainRayEvidence.known(new int[]{0,2,1},0,3));
  require(!TerrainRayEvidence.known(new int[]{-1},0,1));
  require(!TerrainRayEvidence.known(new int[]{0},1,1));
  System.out.println("PASS: "+checks+" production Java MBCL/unknown-ray checks");
 }
}
'''
CPP=r'''
#include "block_collider_wire.h"
#include <cassert>
#include <cstdio>
#include <limits>
int main(int argc,char** argv){
 assert(argc==2);mb::BlockColliderWire reader;FILE* f=fopen(argv[1],"rb");assert(f);
 ErmcBlockColliders original{};assert(fread(&original,1,sizeof(original),f)==sizeof(original));fclose(f);
 ErmcHeader h{};h.hostPid=42;h.mcPid=84;h.hostStartMs=100;h.mcStartMs=200;h.coreGeneration=1;h.hostLife=2;
 auto check=[&](bool expected){reader.snapshot=original;assert(reader.validate(h,60,1001)==expected);};check(true);
 assert(reader.snapshot.count==1&&reader.snapshot.boxes[0].min[0]==10&&reader.snapshot.worldEpoch==3);
 reader.snapshot=original;assert(!reader.validate(h,60,1751)&&reader.error==mb::BlockColliderWire::STALE);
 reader.snapshot=original;assert(!reader.validate(h,60,999));
 for(int field=0;field<7;field++){
  auto changed=h;
  switch(field){case 0:changed.hostPid++;break;case 1:changed.mcPid++;break;case 2:changed.hostStartMs++;break;
   case 3:changed.mcStartMs++;break;case 4:changed.coreGeneration++;break;case 5:changed.hostLife++;break;default:break;}
  reader.snapshot=original;assert(!reader.validate(changed,field==6?61:60,1001));
 }
 for(int fault=0;fault<10;fault++){
  reader.snapshot=original;auto& s=reader.snapshot;
  switch(fault){case 0:s.magic=0;break;case 1:s.version=2;break;case 2:s.flags=8;break;case 3:s.count=513;break;
   case 4:s.boxes[0].max[0]=s.boxes[0].min[0];break;case 5:s.boxes[0].max[0]=1000;break;
   case 6:s.center[0]=std::numeric_limits<float>::quiet_NaN();break;case 7:s.worldEpoch=0;break;
   case 8:s.revision=0;break;case 9:s.reserved=1;break;}
  assert(!reader.validate(h,60,1001));
 }
 auto active=original;assert(reader.read(&active,h,60,1001,[]{}));
 active.seq=3;assert(!reader.read(&active,h,60,1001,[]{}));
 active=original;assert(!reader.read(&active,h,60,1001,[&]{active.seq+=2;}));
 reader.snapshot=original;reader.snapshot.count=0;assert(reader.validate(h,60,1001));
 reader.snapshot.flags=1;assert(!reader.validate(h,60,1001));
 puts("PASS: Java-to-native ABI, full/empty/incomplete snapshots, session/age/geometry and seqlock rejection");
}
'''

def main():
    java=java_path(None)
    compiler=next((ROOT/'.tools/compiler').glob('*/bin/clang++.exe'))
    with tempfile.TemporaryDirectory(prefix='ermc-block-wire-') as directory:
        work=Path(directory);(work/'Check.java').write_text(JAVA,encoding='utf8');(work/'check.cpp').write_text(CPP,encoding='utf8')
        subprocess.run([java,'-m','jdk.compiler/com.sun.tools.javac.Main','--release','17','-Xlint:all','-Werror','-d',str(work),
            str(SRC/'dev/ermc/bridge/link/BlockColliderChannel.java'),str(SRC/'dev/ermc/bridge/TerrainRayEvidence.java'),str(work/'Check.java')],check=True)
        subprocess.run([java,'-cp',str(work),'Check',str(work/'wire.bin')],check=True)
        binary=work/'check.exe'
        subprocess.run([str(compiler),'-std=c++17','-O2','-static','-Wall','-Wextra','-Werror',f'-I{ER/"include"}',f'-I{ER/"src"}',str(work/'check.cpp'),'-o',str(binary)],check=True)
        subprocess.run([str(binary),str(work/'wire.bin')],check=True)
    print('LIMIT: no live mapping, game tasks, GUI or native body creation was exercised')

if __name__=='__main__':main()
