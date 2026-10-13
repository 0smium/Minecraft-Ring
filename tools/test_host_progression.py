"""Exercise the actual read-only HPRG reader, publisher and ABI with memory stubs.

Does not launch/inject/read a running game or establish durable save identity.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
from test_b01_preparation import java_path

ROOT = Path(__file__).resolve().parents[1]
ER = ROOT / 'bridge-base/elden-ring/er-bridge'

NATIVE = r'''
#include <cassert>
#include <cstdio>
#include <cstring>
#include <map>
#include <vector>
#include "host_progression.h"
static std::map<uintptr_t,std::vector<uint8_t>> memory;
static uint8_t mapping[ERMC_OFF_HOST_PROGRESSION+sizeof(ErmcHostProgression)] = {};
static bool switchData=false;static unsigned ownerReads=0;
constexpr uintptr_t BASE=0x10000000, PLAYER=0x20000000, DATA=0x30000000;
namespace mb {
bool mem_read(const void* p,void* out,size_t size){
    uintptr_t address=(uintptr_t)p;
    auto it=memory.find(address);
    if(it==memory.end() || it->second.size()<size)return false;
    memcpy(out,it->second.data(),size);
    if(address==PLAYER+0x580 && ++ownerReads==2 && switchData){uintptr_t other=DATA+0x1000;memcpy(out,&other,size);}
    return true;
}
ErmcHeader* shm_header(){return (ErmcHeader*)mapping;}
}
template<class T>void put(uintptr_t address,const T& value){
    auto* p=(const uint8_t*)&value;memory[address]={p,p+sizeof(value)};
}
static void code(uintptr_t rva,std::initializer_list<uint8_t> bytes){memory[BASE+rva]=bytes;}
static void seed(){
    code(0x657220,{0x48,0x8B,0x81,0x80,0x05,0,0,0xC3});
    code(0x688CD0,{0x48,0x85,0xC9,0x74,4,0x8B,0x41,0x68,0xC3,0x33,0xC0,0xC3});
    code(0x3C2B74,{0x48,0x8B,0x0F,0x48,0x8B,1,0xFF,0x90,0x68,1,0,0,0x48,0x8B,0xC8,0xE8,0x48,0x61,0x2C,0});
    code(0x260087,{0x48,0x8D,0xAE,0x9C,0,0,0,0x48,0x83,0xCB,0xFF,0x48,0xFF,0xC3,0x66,0x44,0x39,0x6C,0x5D,0,0x75,0xF5});
    put(PLAYER,BASE+0x2A7FBB0);put(PLAYER+0x580,DATA);put(DATA,BASE+0x29E8FA8);
    put(DATA+0x68,uint32_t(10));uint16_t name[17]={0x6D4B,0x8BD5};put(DATA+0x9C,name);
    ownerReads=0;switchData=false;
}
static mb::HostProgressionSnapshot read(mb::HostProgressionReader& reader){ownerReads=0;return reader.read((void*)PLAYER);}
static void writeWire(const char* path,const char* suffix,ErmcHostProgression* wire){
    char filename[1024];snprintf(filename,sizeof(filename),"%s%s",path,suffix);
    FILE* f=fopen(filename,"wb");assert(f);assert(fwrite(wire,1,sizeof(*wire),f)==sizeof(*wire));fclose(f);
}
int main(int argc,char** argv){
    using namespace mb;assert(argc==2);
    HostProgressionReader reader;assert(!reader.init(BASE));assert(!read(reader).valid);
    seed();assert(reader.init(BASE));auto ready=read(reader);
    assert(ready.valid && ready.level==10 && ready.nameUnits==2 && ready.fingerprint);
    const auto fingerprint=ready.fingerprint;
    put(DATA+0x68,uint32_t(12));assert(read(reader).fingerprint==fingerprint);
    // Canonical name excludes storage padding, and level never changes identity observation.
    uint16_t name[17]={0x6D4B,0x8BD5};name[16]=0x7FFF;put(DATA+0x9C,name);
    auto padded=read(reader);assert(padded.valid && padded.fingerprint==fingerprint && padded.name[16]==0);
    for(uint32_t level:{0u,714u,~0u}){put(DATA+0x68,level);assert(!read(reader).valid);}
    put(DATA+0x68,uint32_t(713));assert(read(reader).valid);
    name[0]=0xD83D;name[1]=0xDE00;name[2]=0;put(DATA+0x9C,name);
    assert(read(reader).valid && read(reader).nameUnits==2);
    name[1]=0;put(DATA+0x9C,name);assert(!read(reader).valid);
    name[0]=0xDC00;put(DATA+0x9C,name);assert(!read(reader).valid);
    name[0]=0;put(DATA+0x9C,name);assert(!read(reader).valid);
    for(auto& c:name){c='a';}
    put(DATA+0x9C,name);assert(!read(reader).valid);
    seed();switchData=true;assert(!read(reader).valid);switchData=false;
    put(DATA,BASE+0x2A7FBB0);assert(!read(reader).valid);
    seed();memory.erase(DATA+0x68);assert(!read(reader).valid);
    seed();memory.erase(DATA+0x9C);assert(!read(reader).valid);
    seed();memory[BASE+0x657220][0]^=1;assert(!reader.init(BASE));assert(!read(reader).valid);
    seed();assert(reader.init(BASE));ready=read(reader);
    // Publish the real struct, check untouched HEFX block and all unknown/loading fields.
    memset(mapping+ERMC_OFF_HOST_EFFECTS,0xA5,sizeof(ErmcHostEffects));
    publish_host_progression(ready,false,true,100,7);
    auto* wire=(ErmcHostProgression*)(mapping+ERMC_OFF_HOST_PROGRESSION);
    assert(wire->seq==2 && wire->version==1 && wire->magic==0x47525048 && wire->state==2);
    assert(wire->sampleFrame==100 && wire->hostLife==7 && wire->level==10 && wire->flags==15);
    assert(wire->saveSlot==-1 && wire->fingerprint==fingerprint && wire->nameUnits==2);
    writeWire(argv[1],"",wire);
    publish_host_progression({},true,true,101,7);
    assert(wire->seq==4 && wire->state==1 && wire->level==0 && wire->fingerprint==0 && wire->nameUnits==0);
    assert(wire->flags==ERMC_PROGRESSION_EXPLICIT_BINDING && wire->saveSlot==-1);
    for(auto c:wire->name)assert(c==0);
    writeWire(argv[1],".loading",wire);
    publish_host_progression({},false,false,102,8);assert(wire->state==0 && wire->seq==6 && wire->hostLife==8);
    writeWire(argv[1],".unavailable",wire);
    uint16_t emoji[17]={0xD83D,0xDE00};put(DATA+0x9C,emoji);
    auto unicode=read(reader);assert(unicode.valid);
    publish_host_progression(unicode,false,false,103,8);writeWire(argv[1],".emoji",wire);
    for(size_t i=0;i<sizeof(ErmcHostEffects);i++)assert(mapping[ERMC_OFF_HOST_EFFECTS+i]==0xA5);
    puts("PASS: real reader signature/vtables/level/name/switch rejection, canonical weak fingerprint, HPRG ABI/loading/unknown and untouched HEFX block");
}
'''

JAVA = r'''
import dev.ermc.bridge.link.HostProgressionSample;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
public final class HostProgressionInteropCheck {
    static final int BASE=HostProgressionSample.OFFSET;
    static byte[] ready;
    static ByteBuffer buffer(byte[] bytes){
        if(bytes.length!=HostProgressionSample.SIZE)throw new AssertionError("C++ ABI size differs");
        ByteBuffer b=ByteBuffer.allocate(BASE+bytes.length).order(ByteOrder.LITTLE_ENDIAN);
        b.position(BASE);b.put(bytes);return b;
    }
    static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);}
    static void reject(ByteBuffer b,String label){check(HostProgressionSample.read(b,BASE).isEmpty(),label);}
    static void rejectInt(int offset,int value,String label){ByteBuffer b=buffer(ready);b.putInt(BASE+offset,value);reject(b,label);}
    public static void main(String[] args)throws Exception{
        ready=Files.readAllBytes(Path.of(args[0]));
        ByteBuffer b=buffer(ready);
        var sample=HostProgressionSample.read(b.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN),BASE).orElseThrow();
        check(sample.ready()&&sample.frame()==100&&sample.hostLife()==7&&sample.level()==10&&sample.flags()==15,"C++ READY fields");
        check(sample.name().equals("\u6d4b\u8bd5")&&sample.fingerprint()!=0,"C++ canonical UTF16/FNV matches Java validation");
        rejectInt(0,0,"absent sequence");rejectInt(0,3,"odd sequence");
        rejectInt(4,0,"bad magic");rejectInt(8,2,"future version");
        rejectInt(12,3,"unknown state");rejectInt(28,0,"zero level");rejectInt(28,714,"level above stock limit");
        rejectInt(28,-1,"negative/unsigned invalid level");rejectInt(32,11,"missing explicit binding");
        rejectInt(32,4,"READY missing observation quality");rejectInt(32,31,"unknown flags");
        rejectInt(36,0,"unverified save slot");rejectInt(84,1,"nonzero reserved");
        b=buffer(ready);b.putLong(BASE+16,-1);reject(b,"negative frame");
        b=buffer(ready);b.putLong(BASE+40,sample.fingerprint()^1);reject(b,"fingerprint does not describe name");
        b=buffer(ready);b.putShort(BASE+82,(short)0);reject(b,"empty READY name");
        b=buffer(ready);b.putShort(BASE+82,(short)17);reject(b,"unbounded name count");
        b=buffer(ready);b.putChar(BASE+48,'\0');reject(b,"embedded NUL");
        b=buffer(ready);b.putChar(BASE+48,'\ud800');reject(b,"unpaired high surrogate");
        b=buffer(ready);b.putChar(BASE+48,'\udc00');reject(b,"unpaired low surrogate");
        b=buffer(ready);b.putChar(BASE+80,'a');reject(b,"noncanonical trailing name bytes");
        b=buffer(ready);b.limit(b.limit()-1);reject(b,"truncated packet");
        check(HostProgressionSample.read(buffer(ready),-4).isEmpty(),"negative base");
        check(HostProgressionSample.read(buffer(ready),BASE+1).isEmpty(),"unaligned base");
        b=buffer(ready);b.putInt(BASE+28,713);check(HostProgressionSample.read(b,BASE).orElseThrow().level()==713,"upper valid level");
        b.putInt(BASE+28,1);check(HostProgressionSample.read(b,BASE).orElseThrow().level()==1,"lower valid level");
        for(String suffix:new String[]{".loading",".unavailable"}){
            byte[] bytes=Files.readAllBytes(Path.of(args[0]+suffix));
            b=buffer(bytes);var unavailable=HostProgressionSample.read(b,BASE).orElseThrow();
            check(!unavailable.ready()&&unavailable.level()==0&&unavailable.fingerprint()==0&&unavailable.name().isEmpty()&&unavailable.flags()==4,"C++ nonready clears payload");
            b.putInt(BASE+28,1);reject(b,"nonready leaked level");
            b=buffer(bytes);b.putLong(BASE+40,1);reject(b,"nonready leaked fingerprint");
            b=buffer(bytes);b.putChar(BASE+48,'a');reject(b,"nonready leaked name");
            b=buffer(bytes);b.putInt(BASE+32,12);reject(b,"nonready leaked MC-control flag");
        }
        sample=HostProgressionSample.read(buffer(Files.readAllBytes(Path.of(args[0]+".emoji"))),BASE).orElseThrow();
        check(sample.ready()&&sample.name().equals("\ud83d\ude00")&&sample.flags()==7,"C++ surrogate pair/FNV without MC control");
        System.out.println("PASS: production Java HPRG reader consumes C++ READY/LOADING/UNAVAILABLE/emoji wire; rejects version/sequence/layout/UTF16/fingerprint/level bounds");
    }
}
'''

def main():
    compiler = os.environ.get('CXX') or shutil.which('g++') or shutil.which('clang++')
    if not compiler:
        compiler = str(next((ROOT / '.tools/compiler').glob('*/bin/clang++.exe')))
    output = ROOT / 'runtime/experiments'
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='ermc-progression-') as directory:
        work = Path(directory)
        (work/'windows.h').write_text('#pragma once\nusing HWND=void*;using LONG=long;inline LONG InterlockedIncrement(volatile LONG* p){return ++*p;}inline LONG InterlockedDecrement(volatile LONG* p){return --*p;}\n')
        source = work/'check.cpp';source.write_text(NATIVE,encoding='utf8')
        binary = work/('check.exe' if os.name=='nt' else 'check')
        command=[compiler,'-std=c++17','-Wall','-Wextra','-Werror',f'-I{work}',f'-I{ER/"include"}',f'-I{ER/"src"}',str(source),'-o',str(binary)]
        if os.name=='nt':command.insert(1,'-static')
        subprocess.run(command,check=True)
        wire=output/'native-progression-wire-v1.bin'
        subprocess.run([str(binary),str(wire)],check=True)
        java=java_path(os.environ.get('JAVA_HOME'))
        fixture=work/'HostProgressionInteropCheck.java';fixture.write_text(JAVA,encoding='utf8')
        classes=work/'classes';classes.mkdir()
        production=ROOT/'bridge-base/elden-ring/mc-bridge/src/main/java/dev/ermc/bridge/link/HostProgressionSample.java'
        subprocess.run([java,'-m','jdk.compiler/com.sun.tools.javac.Main','--release','17','-Xlint:all','-Werror',
                        '-encoding','UTF-8','-d',str(classes),str(production),str(fixture)],check=True)
        subprocess.run([java,'-cp',str(classes),'HostProgressionInteropCheck',str(wire)],check=True)

if __name__=='__main__':main()
