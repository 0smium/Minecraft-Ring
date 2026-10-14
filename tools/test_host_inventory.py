"""Check the real HINV reader/publisher against bounded memory fixtures and target disk.

Never starts the game, accesses a running process, or writes native memory. Binary
fixtures under runtime/experiments are produced by the production C++ publisher.
"""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
ER = ROOT / 'bridge-base/elden-ring/er-bridge'
CHECKS = [('equipment', 0x260461), ('inventory', 0x2488C0), ('entries', 0x24E787),
          ('counts', 0x24C734), ('quantity', 0x7135F0), ('equipmentHandle', 0x3BE700),
          ('owner', 0x245515), ('equipmentSlot', 0x249183), ('entryFields', 0x7135B9)]

NATIVE = r'''
#include <cassert>
#include <cstdio>
#include <cstring>
#include <map>
#include <vector>
#include "host_inventory.h"
constexpr uintptr_t BASE=0x10000000, PLAYER=0x20000000, DATA=0x30000000;
constexpr uintptr_t EQUIP=DATA+0x2B0, INVENTORY=EQUIP+0x158;
constexpr uintptr_t NORMAL=0x40000000, KEY=0x50000000, MULTI=0x60000000;
static std::map<uintptr_t,std::vector<uint8_t>> memory;
static uint8_t mapping[ERMC_OFF_HOST_INVENTORY+sizeof(ErmcHostInventory)]={};
static uintptr_t mutateAddress=0; static unsigned mutationReads=0;
static unsigned inventoryHeadReads=0;
namespace mb {
bool mem_read(const void* p,void* out,size_t size){
    auto address=(uintptr_t)p;
    if(address==INVENTORY && size==0x60) ++inventoryHeadReads;
    auto it=memory.upper_bound(address);
    if(it==memory.begin())return false;
    --it;
    if(address-it->first>it->second.size() || size>it->second.size()-(address-it->first))return false;
    memcpy(out,it->second.data()+address-it->first,size);
    if(address==mutateAddress && ++mutationReads==2 && size)((uint8_t*)out)[0]^=1;
    return true;
}
ErmcHeader* shm_header(){return (ErmcHeader*)mapping;}
}
template<class T>void put(uintptr_t address,const T& value){
    auto it=memory.upper_bound(address);assert(it!=memory.begin());--it;
    assert(address-it->first+sizeof(value)<=it->second.size());
    memcpy(it->second.data()+address-it->first,&value,sizeof(value));
}
static void code(uintptr_t rva,std::initializer_list<uint8_t> bytes){memory[BASE+rva]=bytes;}
static void seed(uint32_t normalCount=3,uint32_t keyCount=2){
    memory.clear();mutateAddress=0;mutationReads=0;
    /* SIGNATURE_SEED */
    memory[PLAYER].resize(0x600);memory[DATA].resize(0x700);
    memory[NORMAL].resize(normalCount*24);memory[KEY].resize(keyCount*24);
    put(PLAYER,BASE+0x2A7FBB0);put(PLAYER+0x580,DATA);put(DATA,BASE+0x29E8FA8);
    put(DATA+0x68,uint32_t(164));uint16_t name[17]={0x6D4B,0x8BD5};put(DATA+0x9C,name);
    put(EQUIP,BASE+0x29E5880);put(EQUIP+0x3F0,DATA);put(EQUIP+0x3F8,uint8_t(1));
    uint32_t equipped[22];for(auto& x:equipped)x=UINT32_MAX;put(EQUIP+8,equipped);
    put(INVENTORY,BASE+0x29E5F28);
    put(INVENTORY+8,normalCount+keyCount);put(INVENTORY+0x0C,normalCount);
    put(INVENTORY+0x10,NORMAL);put(INVENTORY+0x18,normalCount);
    put(INVENTORY+0x1C,keyCount);put(INVENTORY+0x20,KEY);put(INVENTORY+0x28,keyCount);
    put(INVENTORY+0x40,NORMAL);put(INVENTORY+0x48,INVENTORY+0x18);
    put(INVENTORY+0x50,KEY);put(INVENTORY+0x58,INVENTORY+0x28);
    for(uint32_t i=0;i<normalCount;++i){
        put(NORMAL+i*24,uint32_t(0x80800001+i));put(NORMAL+i*24+4,uint32_t(100000+i));
        put(NORMAL+i*24+8,uint32_t(1));put(NORMAL+i*24+12,i);
    }
    for(uint32_t i=0;i<keyCount;++i){
        put(KEY+i*24,uint32_t(0xB0000010+i));put(KEY+i*24+4,uint32_t(0x40002760+i));
        put(KEY+i*24+8,uint32_t(2+i));put(KEY+i*24+12,uint32_t(50+i));
    }
}
static mb::HostInventoryReader reader;
static mb::HostInventorySnapshot observe(){mutationReads=0;return reader.read((void*)PLAYER);}
static void reject(){auto out=observe();assert(!out.valid&&out.count==0&&out.totalEntries==0&&out.fingerprint==0);}
static void wire(const char* prefix,const char* suffix){
    char path[1024];snprintf(path,sizeof(path),"%s%s",prefix,suffix);FILE* f=fopen(path,"wb");assert(f);
    auto* packet=mapping+ERMC_OFF_HOST_INVENTORY;
    assert(fwrite(packet,1,sizeof(ErmcHostInventory),f)==sizeof(ErmcHostInventory));fclose(f);
}
int main(int argc,char** argv){
    using namespace mb;assert(argc==2);assert(!reader.init(BASE));reject();
    seed();assert(reader.init(BASE));auto out=observe();
    assert(out.valid&&out.count==5&&out.totalEntries==5&&out.normalCapacity==3&&out.keyCapacity==2);
    assert(out.entries[0].slot==0&&out.entries[0].rawItemId==0x40002760&&out.entries[0].quantity==2);
    assert(out.entries[0].flags==1&&out.entries[2].slot==2&&out.entries[2].flags==0);
    // Slot gaps are not compacted into native indices, quantities may legitimately be zero.
    put(NORMAL+24+4,UINT32_MAX);put(NORMAL+8,uint32_t(0));
    put(EQUIP+8+4,uint32_t(2));put(EQUIP+0x90+4,uint32_t(0x80800001));
    out=observe();assert(out.valid&&out.count==4&&out.entries[2].quantity==0&&out.entries[3].slot==4);
    assert(out.entries[2].equipmentMask==(1u<<1));
    put(EQUIP+0x90+4,uint32_t(0x80800002));out=observe();assert(out.entries[2].equipmentMask==0);
    put(EQUIP+0x90+4,uint32_t(0x80800001));
    memset(mapping+ERMC_OFF_HOST_PROGRESSION,0xA5,sizeof(ErmcHostProgression));
    publish_host_inventory(observe(),false,true,100,7,1791964800000ull);
    auto* packet=(ErmcHostInventory*)(mapping+ERMC_OFF_HOST_INVENTORY);
    assert(packet->seq==2&&packet->state==2&&packet->flags==1&&packet->count==4&&packet->totalEntries==4);
    assert(packet->ownerFingerprint&&packet->sampleTimeMs==1791964800000ull&&packet->hostLife==7);
    wire(argv[1],"");
    // Loading always invalidates payload, even if the caller still has a valid old sample.
    publish_host_inventory(observe(),true,true,101,8,1791964800500ull);
    assert(packet->state==1&&packet->count==0&&packet->ownerFingerprint==0&&packet->flags==0);
    for(auto e:packet->entries)assert(!e.rawItemId&&!e.gaitemHandle&&!e.quantity&&!e.equipmentMask);
    wire(argv[1],".loading");
    publish_host_inventory({},false,false,102,8,1791964801000ull);assert(packet->state==0&&packet->count==0);wire(argv[1],".unavailable");
    for(size_t i=0;i<sizeof(ErmcHostProgression);++i)assert(mapping[ERMC_OFF_HOST_PROGRESSION+i]==0xA5);
    // Hard bounds and full owner chain fail closed.
    for(uintptr_t address:{PLAYER,DATA,EQUIP,INVENTORY}){seed();put(address,uintptr_t(0));reject();}
    seed();put(EQUIP+0x3F0,DATA+8);reject();
    seed();put(EQUIP+0x3F8,uint8_t(0));reject();
    seed();put(INVENTORY+0x0C,uint32_t(4097));reject();
    seed();put(INVENTORY+0x1C,uint32_t(4097));reject();
    seed();put(INVENTORY+0x18,uint32_t(4));reject();
    seed();put(INVENTORY+0x28,uint32_t(3));reject();
    seed();put(INVENTORY+0x40,NORMAL+24);reject();
    seed();put(INVENTORY+0x48,INVENTORY+0x28);reject();
    seed();put(INVENTORY+0x50,KEY+24);reject();
    seed();put(INVENTORY+0x58,INVENTORY+0x18);reject();
    seed();memory.erase(NORMAL);reject();
    seed();memory[KEY].pop_back();reject();
    // Capacity tail bytes may contain old entries; only the in-use span is observed.
    seed();put(INVENTORY+0x18,uint32_t(1));put(INVENTORY+0x28,uint32_t(1));
    out=observe();assert(out.valid&&out.count==2&&out.entries[1].slot==2);
    seed();put(NORMAL+8,uint32_t(1000000000));reject();
    seed();put(NORMAL+16,uint8_t(2));reject();
    // Every independently changing pointer/header/entry/equipment observation is rejected.
    for(uintptr_t address:{PLAYER+0x580,INVENTORY,NORMAL,KEY,EQUIP+8,EQUIP+0x90,EQUIP+0x3F0,EQUIP+0x3F8}){
        seed();mutateAddress=address;reject();
    }
    seed();memory[BASE+0x2488C0][0]^=1;assert(!reader.init(BASE));reject();
    seed();assert(reader.init(BASE));put(NORMAL,uint32_t(0));put(KEY,UINT32_MAX);
    out=observe();assert(out.valid&&out.count==3&&out.entries[0].slot==1&&out.entries[1].slot==3);
    // Active multiplayer key accessor is read rather than accidentally exposing storage keys.
    seed();memory[MULTI]=memory[KEY];put(MULTI+8,uint32_t(9));
    put(INVENTORY+0x2C,uint32_t(2));put(INVENTORY+0x30,MULTI);put(INVENTORY+0x38,uint32_t(2));
    put(INVENTORY+0x50,MULTI);put(INVENTORY+0x58,INVENTORY+0x38);
    out=observe();assert(out.valid&&out.entries[0].quantity==9);put(INVENTORY+0x2C,uint32_t(3));reject();
    seed(1030,2);out=observe();assert(out.valid&&out.count==1024&&out.totalEntries==1032);
    publish_host_inventory(out,false,false,103,8,1791964801500ull);assert(packet->flags==2&&packet->entries[1023].slot==1023);wire(argv[1],".truncated");
    seed(0,0);out=observe();assert(out.valid&&out.count==0);
    publish_host_inventory(out,false,true,104,8,1791964802000ull);assert(packet->state==2&&packet->flags==1&&packet->count==0);wire(argv[1],".empty");
    // Actual game-thread feed: no more than 2 Hz and immediate invalidation between polls.
    seed();static HostInventoryFeed feed;assert(feed.init(BASE));HostProgressionReader identity;assert(identity.init(BASE));
    auto owner=identity.read((void*)PLAYER);inventoryHeadReads=0;
    feed.reset(200,7,1000);feed.tick((void*)PLAYER,owner,false,true,200,7,1000);
    assert(packet->state==2&&inventoryHeadReads==2);
    feed.tick((void*)PLAYER,owner,false,true,201,7,1250);assert(inventoryHeadReads==2&&packet->sampleTimeMs==1000);
    feed.tick(nullptr,{},true,false,202,7,1300);assert(packet->state==1&&packet->count==0&&inventoryHeadReads==2);
    feed.tick((void*)PLAYER,owner,false,true,203,7,1400);assert(packet->state==0&&packet->count==0&&inventoryHeadReads==2);
    feed.tick((void*)PLAYER,owner,false,true,204,7,1500);assert(packet->state==2&&inventoryHeadReads==4);
    feed.tick((void*)PLAYER,owner,false,true,205,8,1510);assert(packet->state==0&&packet->hostLife==8&&inventoryHeadReads==4);
    feed.tick((void*)PLAYER,owner,false,true,206,8,2000);assert(packet->state==2&&inventoryHeadReads==6);
    owner.fingerprint++;feed.tick((void*)PLAYER,owner,false,true,207,8,2010);assert(packet->state==0&&packet->count==0);
    feed.tick((void*)PLAYER,owner,false,true,208,8,2500);assert(packet->state==0&&inventoryHeadReads==8);
    feed.reset(209,8,2600);assert(packet->state==0&&packet->ownerFingerprint==0);
    puts("PASS: production native inventory reader: owner/vtables, bounded lists, sparse slots, real equipment handle matching, zero quantity, double-read rejection, truncation, invalidation and HINV ABI");
    puts("PASS: production inventory feed caps full scans at 2 Hz, invalidates loading/life/owner immediately and rejects mismatched rereads");
}
'''


def arrays(path):
    source = path.read_text(encoding='utf-8')
    return {name: bytes(int(v.strip(), 0) for v in content.split(',') if v.strip())
            for name, content in re.findall(r'static const uint8_t (\w+)\[\] = \{(.*?)\};', source, re.S)}


def check_disk(codes):
    installation = ROOT / 'installation.json'
    if not installation.exists():
        print('SKIP: installed exe unavailable; native fixtures still run')
        return
    import pefile
    exe = Path(json.loads(installation.read_text(encoding='utf-8-sig'))['game_dir']) / 'eldenring.exe'
    with exe.open('rb') as stream:
        assert hashlib.file_digest(stream, 'sha256').hexdigest() == '1a3547101327f65d0c76da2f9190ac0aa66871ea42bae2aecc61e11a8b597891'
    pe = pefile.PE(str(exe), fast_load=True)
    for name, rva in CHECKS:
        assert pe.get_data(rva, len(codes[name])) == codes[name], name + ' differs from target exe'
    image = pe.get_memory_mapped_image()
    import struct
    for vtable, label in [(0x29E5880, b'.?AVEquipGameData@CS@@'), (0x29E5F28, b'.?AVEquipInventoryData@CS@@')]:
        locator = struct.unpack_from('<Q', image, vtable - 8)[0] - pe.OPTIONAL_HEADER.ImageBase
        type_desc = struct.unpack_from('<I', image, locator + 12)[0]
        assert image[type_desc + 16:type_desc + 16 + len(label)] == label
    print('PASS: installed exe SHA-256, nine actual access signatures and both inventory RTTI class identities (disk only)')


def main():
    codes = arrays(ER / 'src/host_inventory.h')
    check_disk(codes)
    progression = arrays(ER / 'src/host_progression.h')
    seed = [(rva, codes[name]) for name, rva in CHECKS]
    seed += [(rva, progression[name]) for name, rva in [('gameData', 0x657220), ('level', 0x688CD0),
             ('levelCaller', 0x3C2B74), ('name', 0x260087)]]
    setup = '\n'.join(f'code(0x{rva:X},{{{",".join(hex(v) for v in values)}}});' for rva, values in seed)
    compiler = os.environ.get('CXX') or shutil.which('g++') or shutil.which('clang++')
    if not compiler:
        compiler = str(next((ROOT / '.tools/compiler').glob('*/bin/clang++.exe')))
    output = ROOT / 'runtime/experiments/native-inventory-wire-v1.bin'
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='ermc-inventory-') as directory:
        work = Path(directory)
        (work / 'windows.h').write_text('#pragma once\nusing HWND=void*;using LONG=long;inline LONG InterlockedIncrement(volatile LONG* p){return ++*p;}inline LONG InterlockedDecrement(volatile LONG* p){return --*p;}\n')
        source = work / 'check.cpp'
        source.write_text(NATIVE.replace('/* SIGNATURE_SEED */', setup), encoding='utf-8')
        binary = work / ('check.exe' if os.name == 'nt' else 'check')
        command = [compiler, '-std=c++17', '-Wall', '-Wextra', '-Werror', f'-I{work}', f'-I{ER/"include"}', f'-I{ER/"src"}', str(source), '-o', str(binary)]
        if os.name == 'nt':
            command.insert(1, '-static')
        subprocess.run(command, check=True)
        subprocess.run([str(binary), str(output)], check=True)
    print('C++ publisher fixtures:', output)


if __name__ == '__main__':
    main()
