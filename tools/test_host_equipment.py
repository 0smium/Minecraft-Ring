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
        (0xd3005c,0x23,0x5a1d9c888691be68)]

MAIN=r'''
int main() {
    using namespace mb;using namespace mb::host_equipment;
    static Access access;static Mailbox mailbox;
    auto prepare=[](){
        seed();
        /* EQUIPMENT_CODE */
        put(NORMAL+4,uint32_t(0x10013880)); // owned head armor
        put(NORMAL+8,uint32_t(0)); // real owned gear may carry zero quantity
        put(NORMAL+48+4,uint32_t(0x10002710)); // game's naked-head entry
        put(EQUIP+8+12*4,uint32_t(4));put(EQUIP+0x90+12*4,uint32_t(0x80800003));
        put(DATA+0xC6,uint8_t(2));
    };
    prepare();assert(access.init(BASE));assert(access.writableSlots()==0xF000);
    assert(access.talismanSlots((void*)PLAYER,1)==3);
    assert(access.talismanSlots((void*)PLAYER,0)==0);
    put(DATA+0xC6,uint8_t(7));assert(!access.talismanSlots((void*)PLAYER,1));put(DATA+0xC6,uint8_t(2));
    assert(reader.init(BASE));auto inv=observe();
    ErmcHeader header={};header.hostPid=77;header.hostStartMs=1000;header.coreGeneration=4;header.hostLife=9;header.coreStatus=1;
    ErmcEquipmentRequest req={};req.version=1;req.operation=1;req.hostPid=77;req.coreGeneration=4;
    req.hostLife=9;req.zone=10;req.equipmentSlot=12;req.inventorySlot=2;req.handle=0x80800001;req.itemId=0x10013880;
    req.hostStartMs=1000;req.fingerprint=inv.fingerprint;req.snapshotToken=inv.token();req.deadlineMs=8000;req.nonce=42;
    uint64_t now=3000;bool ready=true,current=true,readback=true;unsigned calls=0;uint32_t zone=10;
    auto armor=[](uint32_t item,uint32_t slot){return item==0x10013880 && slot==12;};
    auto invoke=[&](uintptr_t fn,void* ptr,const ErmcEquipmentRequest& r){
        assert(ptr==(void*)EQUIP);++calls;
        if(!readback)return;
        assert(fn==BASE+(r.operation==1?kEquip:kUnequip));
        put(EQUIP+8+r.equipmentSlot*4,r.operation==1?r.inventorySlot:uint32_t(4));
        put(EQUIP+0x90+r.equipmentSlot*4,r.operation==1?r.handle:uint32_t(0x80800003));
    };
    auto run=[&](const ErmcEquipmentRequest& r){return access.execute(r,header,(void*)PLAYER,ready,zone,armor,invoke,[&]{return now;},[&]{return current;});};
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
    bad=req;bad.equipmentSlot=17;expect(bad,SLOT);
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
    now=3000;prepare();memory[BASE+kEquip][0]^=1;assert(!access.init(BASE));expect(req,UNSUPPORTED);
    puts("PASS: production equipment guards: owner/session/life/zone/token/item/slot/deadline, equip/unequip readback, unknown outcome, 16-entry nonce dedup/full/expiry, signature refusal and actual pouch slot formula");
    puts("LIMIT: invocation is a private fixture; real armor visuals/stats/persistence remain manual checks");
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
    print('PASS: exact installed exe SHA, full equip/unequip functions, native UI call and talisman formula signatures (disk only)')
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
        subprocess.run([compiler,'-std=c++17','-static','-Wall','-Wextra','-Werror',f'-I{work}',f'-I{ER/"include"}',f'-I{ER/"src"}',str(work/'check.cpp'),'-o',str(binary)],check=True)
        subprocess.run([str(binary)],check=True)

if __name__=='__main__':main()
