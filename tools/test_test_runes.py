"""Offline test-rune boundary checks. Never opens the live mailbox or game process.

Compiles the actual native helper, executes a relocated copy of the verified leaf
function on private fixture memory, and checks backup/CLI logic using temporary files.
"""
from pathlib import Path
import hashlib
import json
import os
import re
import struct
import subprocess
import tempfile
import time
from unittest.mock import patch

import grant_test_runes as cli
from test_host_progression import NATIVE as PROGRESSION_FIXTURE

ROOT = Path(__file__).resolve().parents[1]
ER = ROOT / 'bridge-base/elden-ring/er-bridge'
PREFIX = PROGRESSION_FIXTURE[:PROGRESSION_FIXTURE.index('static mb::HostProgressionSnapshot read(')]
PREFIX = PREFIX.replace('#include "host_progression.h"', '#include "host_test_runes.h"')
NATIVE = PREFIX + r'''
static unsigned calls = 0;
static bool failAfter = false;
static int32_t award(uintptr_t fn, void* data, int32_t amount) {
    assert(fn == BASE + mb::test_runes::kAdd && (uintptr_t)data == DATA);
    calls++;
    uint32_t balance = 0;
    assert(mb::mem_read((void*)(DATA+0x6C), &balance, 4));
    put(DATA+0x6C, balance + (uint32_t)amount);
    if (failAfter) memory.erase(DATA+0x6C);
    return amount;
}
static void runeSeed() {
    seed();
    using namespace mb::test_runes;
    memory[BASE+kAdd] = {kCode, kCode+sizeof(kCode)};
    memory[BASE+0x6526DB] = {kOwnerCall, kOwnerCall+sizeof(kOwnerCall)};
    put(BASE+0x29E8D38,kBalanceMax); put(DATA+0x6C,uint32_t(100));
    calls=0;failAfter=false;
}
static ErmcHeader host() {
    ErmcHeader h = {}; h.hostPid=42;h.hostStartMs=500;h.coreGeneration=3;h.hostLife=7;h.coreStatus=1;return h;
}
static ErmcTestRunesRequest request() {
    ErmcTestRunesRequest r = {};r.version=1;r.hostPid=42;r.coreGeneration=3;r.hostLife=7;
    r.hostStartMs=500;r.deadlineMs=4000;r.nonce=99;return r;
}
int main() {
    using namespace mb;using namespace mb::test_runes;
    runeSeed();Access access;assert(access.init(BASE));auto h=host();auto r=request();
    auto run=[&](ErmcTestRunesRequest q,bool alive=true){ownerReads=0;return access.execute(q,h,(void*)PLAYER,alive,award,[]{return uint64_t(1000);},[]{return true;});};
    auto query=run(r);assert(query.status==OK && query.before==100 && query.after==100 && query.level==10 && calls==0);
    r.amount=1000;r.level=query.level;r.fingerprint=query.fingerprint;r.currentRunes=query.after;
    auto bad=r;bad.amount=ERMC_TEST_RUNES_MAX+1;assert(run(bad).status==BAD_REQUEST);
    bad=r;bad.amount=~0u;assert(run(bad).status==BAD_REQUEST);
    bad=r;bad.version=2;assert(run(bad).status==BAD_REQUEST);
    bad=r;bad.nonce=0;assert(run(bad).status==BAD_REQUEST);
    bad=r;bad.reserved=1;assert(run(bad).status==BAD_REQUEST);
    assert(run(r,false).status==NOT_ALIVE);
    for(unsigned field=0;field<4;field++) {
        bad=r;if(field==0)bad.hostPid++;if(field==1)bad.hostStartMs++;if(field==2)bad.coreGeneration++;if(field==3)bad.hostLife++;
        assert(run(bad).status==SESSION);
    }
    bad=r;bad.level++;assert(run(bad).status==OWNER);
    bad=r;bad.fingerprint++;assert(run(bad).status==OWNER);
    bad=r;bad.currentRunes++;assert(run(bad).status==BALANCE);
    bad=r;bad.deadlineMs=999;assert(run(bad).status==EXPIRED);
    bad=r;bad.deadlineMs=999999;assert(run(bad).status==BAD_REQUEST);
    memory[BASE+kAdd][20]^=1;assert(run(r).status==UNSUPPORTED);memory[BASE+kAdd][20]^=1;
    memory[BASE+0x6526DB][0]^=1;assert(run(r).status==UNSUPPORTED);memory[BASE+0x6526DB][0]^=1;
    put(BASE+0x29E8D38,uint32_t(123));assert(run(r).status==UNSUPPORTED);put(BASE+0x29E8D38,kBalanceMax);
    put(DATA,uintptr_t(0));assert(run(r).status==OWNER);put(DATA,BASE+0x29E8FA8);
    switchData=true;assert(run(r).status==OWNER);switchData=false;
    put(DATA+0x6C,kBalanceMax-1);bad=r;bad.currentRunes=kBalanceMax-1;assert(run(bad).status==BALANCE);put(DATA+0x6C,uint32_t(100));
    unsigned clockCalls=0;ownerReads=0;
    assert(access.execute(r,h,(void*)PLAYER,true,award,[&]{return uint64_t(++clockCalls==1?1000:5000);},[]{return true;}).status==EXPIRED);
    unsigned currentCalls=0;ownerReads=0;
    assert(access.execute(r,h,(void*)PLAYER,true,award,[]{return uint64_t(1000);},[&]{return ++currentCalls==1;}).status==SUPERSEDED);
    assert(calls==0);
    auto ok=run(r);assert(ok.status==OK && ok.before==100 && ok.after==1100 && ok.actualDelta==1000 && calls==1);
    assert(run(r).status==BALANCE && calls==1); // stale observation cannot fund twice
    put(DATA+0x6C,uint32_t(100));failAfter=true;assert(run(r).status==READBACK_UNKNOWN && calls==2);failAfter=false;
    put(DATA+0x6C,uint32_t(100));calls=0;
    Queue queue;ErmcTestRunesResult output;uint64_t queueNow=1000;
    auto poll=[&](uint32_t seq,auto req){return queue.poll(seq,req,output,queueNow);};
    assert(!poll(10,r));assert(!poll(10,r));
    queue.service(10,true,[&](auto req){return run(req);});
    queue.service(10,true,[&](auto req){return run(req);});
    assert(poll(10,r) && output.status==OK && calls==1);
    assert(poll(11,r) && output.status==OK && calls==1); // exact nonce replay returns cached result
    bad=r;bad.amount++;assert(poll(12,bad)&&output.status==BAD_REQUEST&&calls==1);
    auto queryB=request();queryB.nonce=1234;
    assert(!poll(13,queryB));queue.service(13,true,[&](auto req){return run(req);});
    assert(poll(13,queryB)&&output.status==OK&&calls==1);
    put(DATA+0x6C,uint32_t(100)); // spending runes can restore A's expected balance
    assert(poll(14,r)&&output.status==OK&&output.after==1100&&calls==1); // A survives intervening query B
    bad=r;bad.amount++;assert(poll(15,bad)&&output.status==BAD_REQUEST&&calls==1);
    bad=r;bad.nonce++;bad.deadlineMs=1500;
    assert(!poll(16,bad));queueNow=2000;
    queue.service(16,true,[&](auto req){ownerReads=0;return access.execute(req,h,(void*)PLAYER,true,award,[&]{return queueNow;},[]{return true;});});
    assert(poll(16,bad)&&output.status==EXPIRED&&calls==1); // frozen tick resumes after deadline
    bad.nonce++;bad.deadlineMs=4000;assert(!poll(17,bad));queue.service(18,false,[&](auto req){return run(req);});
    assert(poll(17,bad)&&output.status==SUPERSEDED&&calls==1);
    Queue full;unsigned accepted=0;uint32_t nextSeq=100;
    for(unsigned i=0;i<64;i++) {
        auto fresh=r;fresh.nonce=10000+i;put(DATA+0x6C,uint32_t(100));
        if(full.poll(nextSeq,fresh,output,1000)) {assert(output.status==DEDUP_FULL);break;}
        full.service(nextSeq,true,[&](auto req){return run(req);});
        assert(full.poll(nextSeq,fresh,output,1000)&&output.status==OK);accepted++;nextSeq++;
    }
    assert(accepted>0&&accepted<64&&calls==1+accepted);
    auto first=r;first.nonce=10000;assert(full.poll(++nextSeq,first,output,1000)&&output.status==OK&&calls==1+accepted);
    assert(!full.poll(++nextSeq,queryB,output,1000));full.service(nextSeq,true,[&](auto req){return run(req);});
    assert(full.poll(nextSeq,queryB,output,1000)&&output.status==OK); // queries work even at capacity
    auto renewed=r;renewed.nonce=20000;renewed.deadlineMs=8000;
    assert(!full.poll(++nextSeq,renewed,output,4000)); // expired awards are reclaimed
    full.service(nextSeq,true,[&](auto req){ownerReads=0;return access.execute(req,h,(void*)PLAYER,true,award,[]{return uint64_t(4000);},[]{return true;});});
    assert(full.poll(nextSeq,renewed,output,4000)&&output.status==OK&&calls==2+accepted);
    assert(full.poll(++nextSeq,first,output,4000)&&output.status==EXPIRED&&calls==2+accepted);
    // Execute the exact verified arithmetic code in THIS test process on fixture data.
    // Relocate only its RIP-relative cap constant; never attach to eldenring.exe.
    auto* page=(uint8_t*)VirtualAlloc(nullptr,4096,MEM_COMMIT|MEM_RESERVE,PAGE_EXECUTE_READWRITE);assert(page);
    memcpy(page,kCode,sizeof(kCode));int32_t relative=256-47;memcpy(page+43,&relative,4);memcpy(page+256,&kBalanceMax,4);
    auto real=(int32_t(__fastcall*)(void*,int32_t))page;
    uint8_t fixture[0x200]={};auto set=[&](unsigned at,uint64_t value){memcpy(fixture+at,&value,8);};
    auto get=[&](unsigned at){uint64_t value;memcpy(&value,fixture+at,8);return value;};
    set(0x6C,100);set(0x70,200);assert(real(fixture,1000)==1000 && (uint32_t)get(0x6C)==1100 && get(0x70)==1200);
    assert(real(fixture,-50)==-50 && (uint32_t)get(0x6C)==1050 && get(0x70)==1200);
    set(0x6C,kBalanceMax-1);set(0x70,0xfffffffeu);
    assert(real(fixture,5)==1 && (uint32_t)get(0x6C)==kBalanceMax && get(0x70)==0xffffffffu && fixture[0x109]==1);
    assert(VirtualFree(page,0,MEM_RELEASE));
    puts("PASS: native signatures/owner/session/amount/deadline/readback; award replay across queries/balance rollback, changed payload, cache capacity/expiry; real leaf arithmetic and rune-memory bookkeeping");
}
'''


def rejects(fn):
    try:
        fn()
    except (ValueError, RuntimeError, FileNotFoundError):
        return
    raise AssertionError('expected rejection')


def check_cli(work):
    host = dict(pid=42, host_start_ms=500, generation=3, life=7)
    observed = dict(level=164, fingerprint=123, after=10)
    packed, nonce = cli.pack_request(host, 100, observed, now_ms=1000, nonce=99)
    assert len(packed) == 64 and nonce == 99
    assert cli.REQUEST.unpack(packed) == (1,100,42,3,7,164,10,0,500,123,4000,99)
    for value in [-1,5_000_001]:
        rejects(lambda: cli.pack_request(host,value,observed))
    rejects(lambda: cli.pack_request(host,100,None))
    blob = cli.RESULT.pack(1,0,100,10,110,164,7,42,3,0,500,123,99)
    assert cli.decode_result(blob,99)['after'] == 110
    rejects(lambda: cli.decode_result(blob,100))
    source=work/'source'/'ER0000.sl2';source.parent.mkdir();source.write_bytes(b'fixture save only')
    source.with_suffix('.sl2.bak').write_bytes(b'fixture companion save only')
    output=work/'backup';now=int(time.time()*1000)
    with patch.object(cli,'running_hosts',return_value=[]):
        manifest=cli.make_backup(source,output)
    process={'started_ms':int(time.time()*1000)+1000}
    assert cli.verify_backup(manifest,source,process,now+2000)['manifest']==str(manifest)
    assert (output/'ER0000.sl2.bak').read_bytes()==b'fixture companion save only'
    with patch.object(cli,'running_hosts',return_value=[{'pid':42}]):
        rejects(lambda: cli.make_backup(source,work/'blocked'))
    with patch.object(cli,'running_hosts',side_effect=[[],[{'pid':42}]]):
        rejects(lambda: cli.make_backup(source,work/'started-during-copy'))
    assert not (work/'started-during-copy'/'manifest.json').exists()
    rejects(lambda: cli.verify_backup(manifest,source,{'started_ms':1},now+2000))
    rejects(lambda: cli.verify_backup(manifest,source,process,now+2*24*60*60*1000))
    (output/'ER0000.sl2').write_bytes(b'changed');rejects(lambda: cli.verify_backup(manifest,source,process,now+2000))
    # Exercise the real mailbox client with a bytearray and a simulated responder.
    shared=bytearray(0x3000)
    struct.pack_into('<3I',shared,0,0x434D484D,1,8*1024*1024)
    struct.pack_into('<I',shared,0x20,42);struct.pack_into('<Q',shared,0x28,500)
    struct.pack_into('<Ii',shared,0x40,3,1);struct.pack_into('<I',shared,0x58,7)
    events=[]
    def respond(_):
        payload=cli.REQUEST.unpack_from(shared,cli.CMD+24)
        seq=struct.unpack_from('<I',shared,cli.CMD)[0]
        out=cli.RESULT.pack(1,0,0,10,10,164,7,42,3,0,500,123,payload[-1])
        shared[cli.RESPONSE:cli.RESPONSE+64]=out
        struct.pack_into('<iI',shared,cli.CMD+16,0,64)
        struct.pack_into('<I',shared,cli.CMD+4,seq)
    with patch.object(cli.time,'sleep',side_effect=respond):
        result=cli.transact(shared,host,0,None,lambda event,values:events.append((event,values)))
    assert result['actual_delta']==0 and cli.REQUEST.unpack_from(shared,cli.CMD+24)[1]==0
    assert [event for event,_ in events]==['prepared','result']
    with patch.object(cli.time,'monotonic',side_effect=[0,0,6]), patch.object(cli.time,'sleep',return_value=None):
        try:
            cli.transact(shared,host,100,observed,lambda *_:None)
        except TimeoutError:
            pass
        else:
            raise AssertionError('missing timeout')
    assert struct.unpack_from('<2I',shared,cli.CMD)==(2,1) # one submission, no cancel overwrite or retry
    rejects(lambda: cli.transact(shared,host,100,observed,lambda *_:None))
    print('PASS: CLI wire layout, positive award bounds, fresh closed-game backup, session age and backup corruption rejection')


def check_disk():
    installation=ROOT/'installation.json'
    if not installation.exists():
        print('SKIP: supported installed exe not present; native fixtures still run')
        return
    import pefile
    exe=Path(json.loads(installation.read_text(encoding='utf-8-sig'))['game_dir'])/'eldenring.exe'
    assert cli.sha256(exe)==cli.EXE_SHA256, 'unsupported installed executable'
    p=pefile.PE(str(exe),fast_load=True)
    assert hashlib.sha256(p.get_data(0x25e0e0,149)).hexdigest()=='c04206377d14de26edf27bc6b27d32a90c1c2f311336bc2b046f71768566aa94'
    header=(ER/'src/host_test_runes.h').read_text()
    for name,rva in [('kCode',0x25e0e0),('kOwnerCall',0x6526db)]:
        region=re.search(rf'{name}\[\] = \{{(.*?)\}};',header,re.S).group(1)
        code=bytes(int(part.strip(),0) for part in region.split(',') if part.strip())
        assert p.get_data(rva,len(code))==code, name+' differs from installed exe'
    assert p.get_data(0x29e8d38,4)==bytes.fromhex('ffc99a3b')
    print('PASS: real exe SHA256, full 149-byte leaf function, PlayerIns owner caller and cap constant (disk only)')


def main():
    check_disk()
    compiler=os.environ.get('CXX') or str(next((ROOT/'.tools/compiler').glob('*/bin/clang++.exe')))
    with tempfile.TemporaryDirectory(prefix='ermc-test-runes-') as directory:
        work=Path(directory)
        source=work/'check.cpp';source.write_text(NATIVE,encoding='utf-8')
        binary=work/'check.exe'
        subprocess.run([compiler,'-std=c++17','-static','-Wall','-Wextra','-Werror',
                        f'-I{ER/"include"}',f'-I{ER/"src"}',str(source),'-o',str(binary)],check=True)
        subprocess.run([str(binary)],check=True)
        check_cli(work)


if __name__=='__main__':
    main()
