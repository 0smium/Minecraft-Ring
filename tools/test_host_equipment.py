"""Disk-verify equipment calls, then exercise production guards with private fake memory.

No game launch/process access/native call occurs. The fixture's Invoke simulates
the game's inventory readback so it proves guards/dedup/ABI, not live equipment.
"""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import test_host_inventory as inventory

ROOT=inventory.ROOT
ER=inventory.ER
HASHES=[(0x249160,0x1ac,0x5a6372977f7ad099),(0x247160,0x61b,0x5b44f153ba58f3ff),
        (0x788e84,0x1f,0x656bfc673790468c),(0x247890,0x49,0x4671f979229599f2),
        (0xd3005c,0x23,0x5a1d9c888691be68),(0xd238e0,0x57,0x0f6c4c72cdcb869b),
        (0xd23657,0x23,0x4e3549431a522f3c),(0x788630,0x3a,0x3f3f5c3cdd3ded2f),
        (0x788ce9,0x13,0x036335d52c8cf836)]

MAIN=r'''
int main() {
    using namespace mb;using namespace mb::host_equipment;
    static Access access;static Mailbox mailbox;
    auto prepare=[](uint32_t normalCount=3){
        seed(normalCount,2);
        /* EQUIPMENT_CODE */
        put(NORMAL+4,uint32_t(0x10013880)); // owned head armor
        put(NORMAL+8,uint32_t(0)); // real owned gear may carry zero quantity
        put(NORMAL+48+4,uint32_t(0x10002710)); // game's naked-head entry
        put(EQUIP+8+12*4,uint32_t(4));put(EQUIP+0x90+12*4,uint32_t(0x80800003));
        put(DATA+0xC6,uint8_t(2));
    };
    prepare();assert(access.init(BASE));assert(access.writableSlots()==0xF000);
    for(uint32_t n=0;n<=4;++n)assert(access.writableSlots(n)==(0xF000u|(((1u<<n)-1)<<17)));
    assert(access.writableSlots(5)==0xF000);
    assert(access.talismanSlots((void*)PLAYER,1)==3);
    assert(access.talismanSlots((void*)PLAYER,0)==0);
    put(DATA+0xC6,uint8_t(7));assert(!access.talismanSlots((void*)PLAYER,1));put(DATA+0xC6,uint8_t(2));
    assert(reader.init(BASE));const auto& inv=observe();
    ErmcHeader header={};header.hostPid=77;header.hostStartMs=1000;header.coreGeneration=4;header.hostLife=9;header.coreStatus=1;
    ErmcEquipmentRequest req={};req.version=1;req.operation=1;req.hostPid=77;req.coreGeneration=4;
    req.hostLife=9;req.zone=10;req.equipmentSlot=12;req.inventorySlot=2;req.handle=0x80800001;req.itemId=0x10013880;
    req.hostStartMs=1000;req.fingerprint=inv.fingerprint;req.snapshotToken=inv.token();req.deadlineMs=8000;req.nonce=42;
    uint64_t now=3000;bool ready=true,current=true,readback=true;unsigned calls=0;uint32_t zone=10;
    auto armor=[](uint32_t item,uint32_t slot){return item==0x10013880 && slot==12;};
    auto accessory=[](uint32_t item,int16_t& group){if(item>>28!=2)return false;group=int16_t((item&0x0FFFFFFF)/10);return true;};
    uint32_t available=3;
    auto capacity=[&]{return available;};
    auto invoke=[&](uintptr_t fn,void* ptr,const ErmcEquipmentRequest& r){
        assert(ptr==(void*)EQUIP);++calls;
        if(!readback)return;
        assert(fn==BASE+(r.operation==1?kEquip:kUnequip));
        put(EQUIP+8+r.equipmentSlot*4,r.operation==1?r.inventorySlot:r.equipmentSlot>=17?UINT32_MAX:uint32_t(4));
        put(EQUIP+0x90+r.equipmentSlot*4,r.operation==1?r.handle:r.equipmentSlot>=17?uint32_t(0):uint32_t(0x80800003));
    };
    auto run=[&](const ErmcEquipmentRequest& r){return access.execute(r,header,(void*)PLAYER,ready,zone,armor,accessory,capacity,invoke,[&]{return now;},[&]{return current;});};
    auto expect=[&](ErmcEquipmentRequest bad,int status){unsigned count=calls;auto actual=run(bad).status;if(actual!=status)fprintf(stderr,"expected %d got %d\n",status,actual);assert(actual==status);assert(calls==count);};
    auto bad=req;bad.version=2;expect(bad,BAD_REQUEST);
    bad=req;bad.nonce=0;expect(bad,BAD_REQUEST);
    bad=req;bad.deadlineMs=now;expect(bad,EXPIRED);
    bad=req;bad.hostPid++;expect(bad,SESSION);
    bad=req;bad.hostStartMs++;expect(bad,SESSION);
    bad=req;bad.coreGeneration++;expect(bad,SESSION);
    bad=req;bad.hostLife++;expect(bad,SESSION);
    bad=req;bad.zone++;expect(bad,SESSION);
    bad=req;bad.fingerprint++;expect(bad,OWNER);
    bad=req;bad.snapshotToken++;expect(bad,STALE);
    bad=req;bad.handle++;expect(bad,ITEM);
    bad=req;bad.inventorySlot++;expect(bad,ITEM);
    bad=req;bad.itemId++;expect(bad,ITEM);
    bad=req;bad.equipmentSlot=13;expect(bad,ITEM);
    bad=req;bad.equipmentSlot=16;expect(bad,SLOT);
    bad=req;bad.equipmentSlot=17;expect(bad,ITEM);
    ready=false;expect(req,NOT_READY);ready=true;current=false;expect(req,SUPERSEDED);current=true;
    auto out=run(req);assert(out.status==OK&&calls==1&&out.itemId==req.itemId&&out.inventorySlot==2&&out.snapshotToken!=req.snapshotToken);
    expect(req,STALE); // old menu snapshot cannot overwrite the equipment change
    req.operation=2;req.handle=0;req.itemId=0;req.inventorySlot=UINT32_MAX;req.snapshotToken=out.snapshotToken;req.nonce++;
    bad=req;bad.itemId=1;expect(bad,BAD_REQUEST);
    out=run(req);assert(out.status==OK&&calls==2&&out.itemId==0x10002710);
    req.operation=1;req.handle=0x80800001;req.itemId=0x10013880;req.inventorySlot=2;req.snapshotToken=out.snapshotToken;req.nonce++;
    readback=false;out=run(req);assert(out.status==READBACK_UNKNOWN&&calls==3);readback=true;
    // Real completed calls are retained across other requests and stale menu replays.
    ErmcEquipmentMailbox box={};mailbox.reset(&box);unsigned executed=0;
    auto execute=[&](const ErmcEquipmentRequest& r,uint32_t seq){++executed;assert(seq==box.reqSeq);return run(r);};
    box.request=req;box.reqSeq=1;assert(mailbox.service(&box,now,execute));assert(box.respSeq==1&&box.result.status==OK&&executed==1);
    auto original=req;box.reqSeq=2;assert(mailbox.service(&box,now,execute));assert(box.result.status==OK&&executed==1);
    box.request.itemId++;box.reqSeq=3;mailbox.service(&box,now,execute);assert(box.result.status==BAD_REQUEST&&executed==1);
    box.request=original;box.request.nonce++;box.reqSeq=4;mailbox.service(&box,now,execute);assert(box.result.status==STALE&&executed==2);
    box.request=original;box.reqSeq=5;mailbox.service(&box,now,execute);assert(box.result.status==OK&&executed==2);
    for(unsigned i=2;i<16;++i){box.request=original;box.request.nonce+=i;box.reqSeq++;mailbox.service(&box,now,execute);}
    box.request.nonce=999;box.reqSeq++;mailbox.service(&box,now,execute);assert(box.result.status==DEDUP_FULL&&executed==16);
    now=9000;box.request=original;box.reqSeq++;mailbox.service(&box,now,execute);assert(box.result.status==EXPIRED&&executed==16);
    // The owned armor can be beyond the former 1024-entry limit, including quantity zero.
    now=3000;prepare(1104);assert(access.init(BASE));assert(reader.init(BASE));
    const uint32_t tail=1103;
    put(NORMAL+tail*24+4,uint32_t(0x10013880));put(NORMAL+tail*24+8,uint32_t(0));
    observe();req.operation=1;req.handle=0x80800001+tail;req.itemId=0x10013880;req.inventorySlot=tail+2;
    req.snapshotToken=inv.token();req.fingerprint=inv.fingerprint;req.deadlineMs=8000;req.nonce=1001;
    unsigned beforeCalls=calls;out=run(req);assert(out.status==OK&&calls==beforeCalls+1&&out.inventorySlot==1105);
    expect(req,STALE);
    req.operation=2;req.handle=0;req.itemId=0;req.inventorySlot=UINT32_MAX;req.snapshotToken=out.snapshotToken;req.nonce++;
    out=run(req);assert(out.status==OK&&out.itemId==0x10002710);
    req.operation=1;req.handle=0x80800001+tail;req.itemId=0x10013880;req.inventorySlot=tail+2;req.snapshotToken=out.snapshotToken;req.nonce++;
    // A tail change between the two pre-call reads must fail; this also detects
    // comparing two aliases of the reader's reused snapshot instead of saved scalars.
    auto changingArmor=[&](uint32_t item,uint32_t slot){put(NORMAL+tail*24+12,uint32_t(777));return armor(item,slot);};
    beforeCalls=calls;
    out=access.execute(req,header,(void*)PLAYER,ready,zone,changingArmor,accessory,capacity,invoke,[&]{return now;},[&]{return current;});
    assert(out.status==STALE&&calls==beforeCalls);

    // Owned talismans use exactly the same native invocation ABI. Fake native
    // writes only simulate the game's confirmed result in this private fixture.
    prepare(6);assert(access.init(BASE));assert(reader.init(BASE));available=4;
    for(unsigned i=0;i<6;++i)put(NORMAL+i*24+4,uint32_t(0x20000000+10*(i+1)));
    put(NORMAL+24+4,uint32_t(0x2000000b)); // alternate tier in group 1
    put(NORMAL+48+4,uint32_t(0x2000000a)); // another owned copy of item 10
    auto refresh=[&]{observe();req.snapshotToken=inv.token();req.fingerprint=inv.fingerprint;req.nonce++;};
    req.operation=1;req.equipmentSlot=17;req.inventorySlot=2;req.handle=0x80800001;req.itemId=0x2000000a;refresh();
    out=run(req);assert(out.status==OK&&out.itemId==req.itemId&&out.inventorySlot==2);
    refresh();req.equipmentSlot=18;expect(req,CONFLICT); // same physical item
    req.inventorySlot=4;req.handle=0x80800003;expect(req,CONFLICT); // identical ID, different copy
    req.inventorySlot=3;req.handle=0x80800002;req.itemId=0x2000000b;expect(req,CONFLICT); // same native group
    req.equipmentSlot=17;out=run(req);assert(out.status==OK&&out.itemId==0x2000000b); // replacement in same slot allowed
    refresh();req.operation=2;req.inventorySlot=UINT32_MAX;req.handle=0;req.itemId=0;
    out=run(req);assert(out.status==OK&&out.handle==0&&out.itemId==0&&out.inventorySlot==UINT32_MAX);
    refresh();out=run(req);assert(out.status==OK); // native already-empty slot is read back as empty
    refresh();req.operation=1;req.equipmentSlot=20;req.inventorySlot=5;req.handle=0x80800004;req.itemId=0x20000028;
    available=3;expect(req,SLOT);available=0;expect(req,SLOT);available=5;expect(req,SLOT);available=4;
    auto droppingCapacity=[&]{return available--;};beforeCalls=calls;
    out=access.execute(req,header,(void*)PLAYER,ready,zone,armor,accessory,droppingCapacity,invoke,[&]{return now;},[&]{return current;});
    assert(out.status==STALE&&calls==beforeCalls);available=4;
    auto missingGroup=[](uint32_t,int16_t&){return false;};
    out=access.execute(req,header,(void*)PLAYER,ready,zone,armor,missingGroup,capacity,invoke,[&]{return now;},[&]{return current;});
    assert(out.status==ITEM&&calls==beforeCalls);
    auto noGroup=[](uint32_t item,int16_t& group){group=-1;return item>>28==2;};
    // Distinct native group=-1 items are compatible; equal raw ID is still not.
    put(EQUIP+8+18*4,uint32_t(3));put(EQUIP+0x90+18*4,uint32_t(0x80800002));refresh();
    out=access.execute(req,header,(void*)PLAYER,ready,zone,armor,noGroup,capacity,invoke,[&]{return now;},[&]{return current;});
    assert(out.status==OK);refresh();req.itemId=0x2000000b;req.inventorySlot=3;req.handle=0x80800002;
    beforeCalls=calls;out=access.execute(req,header,(void*)PLAYER,ready,zone,armor,noGroup,capacity,invoke,[&]{return now;},[&]{return current;});
    assert(out.status==CONFLICT&&calls==beforeCalls);
    req.itemId=0x20000028;req.inventorySlot=5;req.handle=0x80800004;
    unsigned requestedReads=0;
    auto changingGroup=[&](uint32_t item,int16_t& group){
        if(item==req.itemId){group=++requestedReads==1?4:1;return true;}
        return accessory(item,group);
    };
    out=access.execute(req,header,(void*)PLAYER,ready,zone,armor,changingGroup,capacity,invoke,[&]{return now;},[&]{return current;});
    assert(out.status==STALE&&calls==beforeCalls); // param rule changes before actual call
    // Missing/mismatched inventory mapping must not disguise an occupied slot.
    put(EQUIP+8+19*4,uint32_t(999));put(EQUIP+0x90+19*4,uint32_t(0x80809999));refresh();
    req.operation=2;req.inventorySlot=UINT32_MAX;req.itemId=0;req.handle=0;expect(req,ITEM);
    put(EQUIP+8+19*4,UINT32_MAX);put(EQUIP+0x90+19*4,uint32_t(0));refresh();
    auto incompleteEmpty=[&](uintptr_t,void*,const ErmcEquipmentRequest& r){++calls;put(EQUIP+8+r.equipmentSlot*4,UINT32_MAX);};
    out=access.execute(req,header,(void*)PLAYER,ready,zone,armor,accessory,capacity,incompleteEmpty,[&]{return now;},[&]{return current;});
    assert(out.status==READBACK_UNKNOWN); // no entry now projects, but old handle remains!
    puts("PASS: native talisman equip/replace/empty readback, all 4 capacity bits, missing/changed capacity, duplicate/family conflicts, group -1, missing metadata and corrupt occupied slot refusal");
    prepare();memory[BASE+kEquip][0]^=1;assert(!access.init(BASE));expect(req,UNSUPPORTED);
    for(uintptr_t rva:{uintptr_t(0xD238E0),uintptr_t(0xD23657),uintptr_t(0x788630),uintptr_t(0x788CE9)}) {
        prepare();memory[BASE+rva][0]^=1;assert(!access.init(BASE));expect(req,UNSUPPORTED);
    }
    puts("PASS: tail armor equip/unequip beyond 1024, zero quantity ownership, full-content stale token and reused-buffer reread rejection");
    puts("PASS: production equipment guards: owner/session/life/zone/token/item/slot/deadline, equip/unequip readback, unknown outcome, 16-entry nonce dedup/full/expiry, signature refusal and actual pouch slot formula");
    puts("LIMIT: invocation is a private fixture; real armor/talisman stats/persistence remain manual checks");
}
'''

def main():
    import pefile
    installation=json.loads((ROOT/'installation.json').read_text(encoding='utf-8-sig'))
    exe=Path(installation['game_dir'])/'eldenring.exe'
    with exe.open('rb') as f:
        assert hashlib.file_digest(f,'sha256').hexdigest()=='1a3547101327f65d0c76da2f9190ac0aa66871ea42bae2aecc61e11a8b597891'
    pe=pefile.PE(str(exe),fast_load=True)
    disk=[]
    for address,length,expected in HASHES:
        data=pe.get_data(address,length);hash=14695981039346656037
        for byte in data: hash=((hash^byte)*1099511628211)&((1<<64)-1)
        assert hash==expected,hex(address)
        disk.append((address,data))
    # Actual UI call uses handle from the native inventory accessor, index in R9D,
    # slot in EDX and flags true/true/false. Unequip is its separate native path.
    assert pe.get_data(0x788DB3,5)==bytes.fromhex('e8a8e3abff')
    assert pe.get_data(0xD48F6E,6)==bytes.fromhex('c70110270010')
    assert pe.get_data(0x24762a,7)==bytes.fromhex('c7452800000000')  # empty handle
    assert pe.get_data(0x247636,4)==bytes.fromhex('4183c9ff')  # index=-1
    print('PASS: exact installed exe SHA, full equip/unequip, native accessory category/UI group predicate/param index, empty talisman ABI and pouch formula signatures (disk only)')
    codes=inventory.arrays(ER/'src/host_inventory.h');progression=inventory.arrays(ER/'src/host_progression.h')
    seed=[(rva,codes[name]) for name,rva in inventory.CHECKS]
    seed += [(rva,progression[name]) for name,rva in [('gameData',0x657220),('level',0x688CD0),('levelCaller',0x3C2B74),('name',0x260087)]]
    setup='\n'.join(f'code(0x{rva:X},{{{",".join(hex(v) for v in data)}}});' for rva,data in seed)
    extra='memory.erase(BASE+0x249183);\n'+'\n'.join(f'code(0x{rva:X},{{{",".join(hex(v) for v in data)}}});' for rva,data in disk)
    source=inventory.NATIVE.split('static void reject()')[0].replace('/* SIGNATURE_SEED */',setup)
    source=source.replace('#include "host_inventory.h"','#include "host_equipment.h"')+MAIN.replace('/* EQUIPMENT_CODE */',extra)
    compiler=os.environ.get('CXX') or shutil.which('g++') or str(next((ROOT/'.tools/compiler').glob('*/bin/clang++.exe')))
    with tempfile.TemporaryDirectory(prefix='ermc-equipment-') as directory:
        work=Path(directory);(work/'windows.h').write_text('#pragma once\nusing HWND=void*;using LONG=long;inline LONG InterlockedIncrement(volatile LONG* p){return ++*p;}inline LONG InterlockedDecrement(volatile LONG* p){return --*p;}\n')
        (work/'check.cpp').write_text(source,encoding='utf-8');binary=work/'check.exe'
        subprocess.run([compiler,'-std=c++17','-O2','-static','-Wall','-Wextra','-Werror','-Wframe-larger-than=16384',f'-I{work}',f'-I{ER/"include"}',f'-I{ER/"src"}',str(work/'check.cpp'),'-o',str(binary)],check=True)
        subprocess.run([str(binary)],check=True)

if __name__=='__main__':main()
