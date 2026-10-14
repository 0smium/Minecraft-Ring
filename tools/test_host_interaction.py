"""Verify menu/grace fields against the pinned EXE, then read private fixtures.

No game launch, process access, menu calls or native input writes occur.
"""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess
import tempfile
import test_host_inventory as inventory
from test_b01_preparation import java_path

ROOT = inventory.ROOT
ER = inventory.ER
NATIVE = r'''
#include <cassert>
#include <cstdio>
#include <cstring>
#include <map>
#include <vector>
#include "host_interaction.h"
constexpr uintptr_t BASE=0x10000000,MENU=0x20000000,MODEL=0x21000000,VIEW=0x22000000;
constexpr uintptr_t LUA=0x23000000,PROXY=0x24000000,ACTION=0x25000000;
static std::map<uintptr_t,std::vector<uint8_t>> memory;
static uint8_t shared[ERMC_SHM_SIZE];
static uintptr_t mutate=0;static unsigned reads=0;
namespace mb {
bool mem_read(const void* p,void* out,size_t size){
    auto a=(uintptr_t)p;auto i=memory.upper_bound(a);if(i==memory.begin())return false;--i;
    if(a-i->first>i->second.size()||size>i->second.size()-(a-i->first))return false;
    memcpy(out,i->second.data()+a-i->first,size);
    if(a==mutate&&++reads==2)((uint8_t*)out)[0]^=1;
    return true;
}
ErmcHeader* shm_header(){return (ErmcHeader*)shared;}
}
template<class T>void put(uintptr_t a,const T& value){
    auto i=memory.upper_bound(a);assert(i!=memory.begin());--i;
    assert(a-i->first+sizeof(value)<=i->second.size());memcpy(i->second.data()+a-i->first,&value,sizeof(value));
}
static void code(uintptr_t rva,std::initializer_list<uint8_t> bytes){memory[BASE+rva]=bytes;}
static void seed(){
    memory.clear();mutate=0;reads=0;
    /* SIGNATURES */
    memory[BASE+0x3D6F820].resize(8);put(BASE+0x3D6F820,MENU);
    memory[BASE+0x3D6BEB8].resize(8);put(BASE+0x3D6BEB8,LUA);
    memory[BASE+0x3D72478].resize(8);put(BASE+0x3D72478,ACTION);
    memory[MENU].resize(0x868);put(MENU,BASE+0x2A9EED8);put(MENU+0x860,MODEL);
    memory[MODEL].resize(0x40);put(MODEL,BASE+0x2AD0078);put(MODEL+8,VIEW);
    memory[VIEW].resize(0xB10);put(VIEW,BASE+0x2AEAB38);put(VIEW+0xB00,MODEL);put(VIEW+0xB08,uint8_t(1));
    memory[LUA].resize(0x20);put(LUA+8,PROXY);
    memory[PROXY].resize(0x90);put(PROXY,BASE+0x2A63608);
    memory[ACTION].resize(0x30);put(ACTION,BASE+0x2B48140);put(ACTION+0x29,uint8_t(1));
}
int main(int argc,char** argv){
    using namespace mb;assert(argc==2);HostInteractionReader reader;
    assert(!reader.init(BASE));assert(reader.read(true).flags==0);
    seed();assert(reader.init(BASE));auto sample=reader.read(true);
    assert(sample.flags==(ERMC_INTERACTION_VALID|ERMC_INTERACTION_CAN_RESUME|ERMC_INTERACTION_ACTION_READY|ERMC_INTERACTION_HOST_READY));
    assert(!(reader.read(false).flags&ERMC_INTERACTION_CAN_RESUME));
    put(VIEW+0xB08,uint8_t(0));assert(reader.read(true).flags&ERMC_INTERACTION_MENU_BLOCKING);
    put(VIEW+0xB08,uint8_t(1));put(MENU+0x798,uintptr_t(0x30000000));
    assert(reader.read(true).flags&ERMC_INTERACTION_MENU_BLOCKING);put(MENU+0x798,uintptr_t(0));
    for(uint32_t flag:{0x20u,0x40u,0x80u,0x100u,0xA0u}){
        put(PROXY+0x88,flag);sample=reader.read(true);
        assert(sample.restFlags==flag&&(sample.flags&ERMC_INTERACTION_RESTING)&&!(sample.flags&ERMC_INTERACTION_CAN_RESUME));
    }
    put(PROXY+0x88,uint32_t(0x800));assert(!(reader.read(true).flags&ERMC_INTERACTION_CAN_RESUME));
    put(PROXY+0x88,uint32_t(0));put(ACTION+0x29,uint8_t(0));assert(!(reader.read(true).flags&ERMC_INTERACTION_CAN_RESUME));
    for(uintptr_t address:{MENU,MODEL,VIEW,PROXY,ACTION}){seed();put(address,uintptr_t(0));assert(!reader.read(true).flags);}
    seed();put(VIEW+0xB00,MODEL+8);assert(!reader.read(true).flags);
    seed();put(VIEW+0xB08,uint8_t(2));assert(!reader.read(true).flags);
    seed();put(ACTION+0x29,uint8_t(2));assert(!reader.read(true).flags);
    for(uintptr_t address:{BASE+0x3D6F820,MENU+0x860,MODEL+8,VIEW+0xB00,VIEW+0xB08,
        MENU+0x798,BASE+0x3D6BEB8,LUA+8,PROXY+0x88,BASE+0x3D72478,ACTION+0x29}){
        seed();mutate=address;assert(!reader.read(true).flags);
    }
    seed();memory[BASE+0x8c59b0][0]^=1;assert(!reader.init(BASE));assert(!reader.read(true).flags);
    seed();assert(reader.init(BASE));sample=reader.read(true);
    auto* host=shm_header();host->hostPid=42;host->hostStartMs=1000;host->coreGeneration=2;host->hostLife=3;
    shared[ERMC_OFF_HOST_INTERACTION-1]=0xA5;shared[ERMC_OFF_HOST_INTERACTION+64]=0x5A;
    publish_host_interaction(sample,100,10,1500);
    auto* packet=(ErmcHostInteraction*)(shared+ERMC_OFF_HOST_INTERACTION);
    assert(packet->seq==2&&packet->magic==ERMC_HOST_INTERACTION_MAGIC&&packet->version==1&&packet->flags==sample.flags);
    assert(packet->sampleFrame==100&&packet->sampleTimeMs==1500&&packet->hostStartMs==1000&&packet->hostPid==42);
    assert(packet->coreGeneration==2&&packet->hostLife==3&&packet->zone==10&&!packet->restFlags);
    FILE* f=fopen(argv[1],"wb");assert(f);assert(fwrite(packet,1,sizeof(*packet),f)==sizeof(*packet));fclose(f);
    publish_host_interaction({},101,0,1501);assert(!packet->flags&&!packet->restFlags&&packet->seq==4);
    assert(shared[ERMC_OFF_HOST_INTERACTION-1]==0xA5&&shared[ERMC_OFF_HOST_INTERACTION+64]==0x5A);
    puts("PASS: actual native menu reader, pointer/vtable/owner checks, changing-chain rejection, menu opening/closing, each rest phase, conservative resume and HINT publication");
    puts("LIMIT: private observations; live grace exit flag timing and automatic handoff remain untested");
}
'''


def main():
    import pefile
    source = (ER / 'src/host_interaction.h').read_text(encoding='utf-8')
    codes = inventory.arrays(ER / 'src/host_interaction.h')
    checks = [(int(rva, 16), name) for rva, name in re.findall(r'signature\(base\+(0x[0-9a-fA-F]+),(\w+),sizeof', source)]
    install = json.loads((ROOT / 'installation.json').read_text(encoding='utf-8-sig'))
    exe = Path(install['game_dir']) / 'eldenring.exe'
    with exe.open('rb') as f:
        assert hashlib.file_digest(f, 'sha256').hexdigest() == '1a3547101327f65d0c76da2f9190ac0aa66871ea42bae2aecc61e11a8b597891'
    pe = pefile.PE(str(exe), fast_load=True)
    for rva, name in checks:
        assert pe.get_data(rva, len(codes[name])) == codes[name], name
    image = pe.get_memory_mapped_image()
    import struct
    for vtable, name in [(0x2A9EED8,'CSMenuManImp'),(0x2AD0078,'FeSystemAnnounceViewModel'),
                         (0x2AEAB38,'FeSystemAnnounceView'),(0x2A63608,'CSLuaEventProxy'),(0x2B48140,'CSActionButtonManImp')]:
        locator = struct.unpack_from('<Q', image, vtable-8)[0] - pe.OPTIONAL_HEADER.ImageBase
        descriptor = struct.unpack_from('<I', image, locator+12)[0]
        expected = ('.?AV'+name+'@CS@@').encode()
        assert image[descriptor+16:descriptor+16+len(expected)] == expected, name
    print(f'PASS: exact supported EXE hash, {len(checks)} menu/Lua/action instruction spans and five RTTI identities',flush=True)
    setup = '\n'.join(f'code(0x{rva:X},{{{",".join(hex(b) for b in codes[name])}}});' for rva,name in checks)
    compiler = str(next((ROOT / '.tools/compiler').glob('*/bin/clang++.exe')))
    output = ROOT / 'runtime/experiments/interaction-research/interaction-wire.bin'
    output.parent.mkdir(parents=True,exist_ok=True)
    java = java_path(None)
    with tempfile.TemporaryDirectory(prefix='ermc-interaction-') as directory:
        work=Path(directory)
        (work/'windows.h').write_text('#pragma once\nusing HWND=void*;using LONG=long;inline LONG InterlockedIncrement(volatile LONG* p){return ++*p;}inline LONG InterlockedDecrement(volatile LONG* p){return --*p;}\n')
        (work/'check.cpp').write_text(NATIVE.replace('/* SIGNATURES */',setup),encoding='utf-8')
        binary=work/('check.exe' if os.name=='nt' else 'check')
        subprocess.run([compiler,'-std=c++17','-O2','-static','-Wall','-Wextra','-Werror',f'-I{work}',f'-I{ER/"include"}',f'-I{ER/"src"}',str(work/'check.cpp'),'-o',str(binary)],check=True)
        subprocess.run([str(binary),str(output)],check=True)
        src=ROOT/'bridge-base/elden-ring/mc-bridge/src'
        subprocess.run([java,'-m','jdk.compiler/com.sun.tools.javac.Main','--release','17','-Xlint:all','-Werror','-d',str(work),
                        str(src/'main/java/dev/ermc/bridge/link/HostInteractionSample.java'),str(src/'testFixtures/inventory/InteractionWireCheck.java')],check=True)
        subprocess.run([java,'-cp',str(work),'InteractionWireCheck',str(output)],check=True)


if __name__ == '__main__':
    main()
