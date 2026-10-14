"""Exercise the actual native debug-spawn functions on private fixture memory.

No game launch or process access. The creator/world/characters below stand in for
the native memory readers; request, recognition, cancellation and cleanup execute
the production C++ source and its real Tracker helper.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
ER = ROOT / 'bridge-base/elden-ring/er-bridge'

PREFIX = r'''
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <math.h>
#include <assert.h>
#include <stdio.h>
#include <vector>
#include <utility>
#include <type_traits>
#include "debug_test_enemy.h"
namespace mb {
namespace rva { constexpr uintptr_t kWorldChrMan=1,kVtWorldChrMan=2; }
namespace combat { constexpr uintptr_t kVtDebugChrCreator=3,kForceDeath=4; }
constexpr size_t kChrHandle=8,kChrNpcParamId=0x60,kChrModelId=0x64;
constexpr size_t kWcmChrSets=0x1DED8,kPhysPos=0x10,kCamMatrix=0;
constexpr int kDebugChrSet=114;
using ForceDeath_t=void(__fastcall*)(void*,bool);
struct Player { uint8_t* ins; uint8_t* data; uint8_t* phys; };
struct Chr { uint8_t* ins; uint8_t* data; uint8_t* phys; };
struct ErmcHeader { uint32_t debugFlags=0,hostLife=1; };
enum Life { LIFE_ALIVE, LIFE_DEAD };
static Life g_life=LIFE_ALIVE;
static struct {bool valid=true;uint32_t zone=7;} g_frame;
static bool g_combatOk=true,g_standing=true;
static uint64_t clockMs=100;
static uint8_t world[0x20000],creator[0x1C0],creator2[0x1C0],set[0x20],members[16*16];
static uint8_t playerIns[0x580],playerData[0x100],playerPhys[0x100];
struct FakeChr { uint8_t ins[0x580],data[0x100],phys[0x100];bool readable=true,dead=false; };
static FakeChr characters[8];
static ErmcHeader header;
static Player player{playerIns,playerData,playerPhys};
static std::vector<std::pair<uintptr_t,size_t>> regions;
static std::vector<uint64_t> killed;
template<class T> static void put(void* address,const T& value) {
    static_assert(!std::is_array<T>::value,"write a pointer or explicit scalar");
    memcpy(address,&value,sizeof(value));
}
static bool mem_readable(const void* pointer,size_t count) {
    const auto address=(uintptr_t)pointer;
    for (const auto& region:regions)
        if (address>=region.first && address-region.first<=region.second && count<=region.second-(address-region.first)) return true;
    return false;
}
static uint8_t* typed(void* pointer,uintptr_t type) {
    return mem_readable(pointer,8) && *(uintptr_t*)pointer==type ? (uint8_t*)pointer : nullptr;
}
static uint8_t* global_ptr(uintptr_t,uintptr_t type) { return typed(world,type); }
static bool get_player(Player* out) { *out=player;return true; }
static bool block_valid(const Player&) { return true; }
static bool get_chr(uint8_t* ins,Chr* out) {
    for (auto& chr:characters) if (chr.ins==ins && chr.readable) {
        *out={chr.ins,chr.data,chr.phys};return true;
    }
    return false;
}
static bool chr_dead(const Chr& chr) {
    for (auto& fake:characters) if (fake.ins==chr.ins) return fake.dead;
    assert(false);return true;
}
static uint8_t* final_camera() { return nullptr; }
static uint64_t now_ms() { return clockMs; }
static ErmcHeader* shm_header() { return &header; }
static void log(const char*,...) {}
static void __fastcall force_death(void* pointer,bool awardRunes) {
    assert(!awardRunes);
    killed.push_back(*(uint64_t*)((uint8_t*)pointer+kChrHandle));
}
static uintptr_t g_base=(uintptr_t)&force_death-combat::kForceDeath;
'''

SUFFIX = r'''
static void member(unsigned slot,unsigned chr) {
    put(members+slot*16,(uint8_t*)characters[chr].ins);members[slot*16+8]=4;
}
static void seed(bool external=true) {
    memset(world,0,sizeof(world));memset(creator,0,sizeof(creator));memset(creator2,0,sizeof(creator2));
    memset(set,0,sizeof(set));memset(members,0,sizeof(members));
    memset(playerIns,0,sizeof(playerIns));memset(playerPhys,0,sizeof(playerPhys));
    regions={{(uintptr_t)world,sizeof(world)},{(uintptr_t)creator,sizeof(creator)},
        {(uintptr_t)creator2,sizeof(creator2)},{(uintptr_t)set,sizeof(set)},
        {(uintptr_t)members,sizeof(members)}};
    put(world,rva::kVtWorldChrMan);put(creator,combat::kVtDebugChrCreator);put(creator2,combat::kVtDebugChrCreator);
    put(world+0x1E648,(uint8_t*)creator);put(world+kWcmChrSets+8*kDebugChrSet,(uint8_t*)set);
    put(set+0x10,uint32_t{16});put(set+0x18,(uint8_t*)members);
    put(playerIns+kChrHandle,uint64_t{1000});
    for(unsigned i=0;i<8;++i) {
        auto& chr=characters[i];memset(chr.ins,0,sizeof(chr.ins));chr.readable=true;chr.dead=false;
        put(chr.ins+kChrHandle,uint64_t{100+i});put(chr.ins+kChrModelId,int32_t{3000});
        put(chr.ins+kChrNpcParamId,int32_t{30000014});
    }
    if(external) member(0,0);
    put(creator+0x1B0,external?characters[0].ins:nullptr);
    header={};g_testEnemy=debug_test_enemy::Tracker{};
    g_life=LIFE_ALIVE;g_frame={true,7};g_combatOk=true;g_standing=true;clockMs=100;killed.clear();
    update_test_enemy_context(true);
}
static void submit() {
    header.debugFlags=4;service_test_enemy(player);
    assert(g_testEnemy.phase()==debug_test_enemy::Tracker::Phase::Pending && creator[0x44]==1);
}
static void finish(unsigned chr=1) {
    member(1,chr);put(creator+0x1B0,(uint8_t*)characters[chr].ins);creator[0x44]=0;
}
static void recognize() {
    finish();service_test_enemy(player);
    assert(g_testEnemy.phase()==debug_test_enemy::Tracker::Phase::Owned);
}
static void none() { assert(killed.empty()); }
static void test() {
    // An external same-template soldier is present before our own request.
    seed();submit();recognize();remove_test_enemy("test");
    assert(killed.size()==1 && killed[0]==101);
    remove_test_enemy("again");assert(killed.size()==1);
    puts("PASS: only confirmed own identity is removed; external same-template member survives; cleanup idempotent");

    seed();remove_test_enemy("F8 without own spawn");none();
    header.debugFlags=8;service_test_enemy(player);none();
    puts("PASS: no own request means zero ForceDeath calls");

    seed();submit();recognize();put(characters[1].ins+kChrHandle,uint64_t{999});
    remove_test_enemy("reused address");none();
    puts("PASS: same pointer with a different handle is never removed");

    seed();submit();remove_test_enemy("F8 while pending");finish();service_test_enemy(player);none();
    assert(g_testEnemy.phase()==debug_test_enemy::Tracker::Phase::Quarantined);
    header.debugFlags=4;service_test_enemy(player);assert(creator[0x44]==0);none();
    puts("PASS: pending cancellation rejects late result and resubmission in the same lifecycle");

    seed();submit();clockMs+=debug_test_enemy::Tracker::kTimeoutMs;finish();service_test_enemy(player);
    remove_test_enemy("late timeout");none();
    assert(g_testEnemy.phase()==debug_test_enemy::Tracker::Phase::Quarantined);
    puts("PASS: timed-out completion cannot acquire ownership");

    seed();submit();put(creator+0xF4,int32_t{123});finish();service_test_enemy(player);
    remove_test_enemy("changed parameters");none();
    assert(g_testEnemy.phase()==debug_test_enemy::Tracker::Phase::Quarantined);
    seed();submit();put(world+0x1E648,(uint8_t*)creator2);finish();service_test_enemy(player);
    remove_test_enemy("changed creator");none();
    puts("PASS: changed request fields or creator pointer abandon ownership");

    seed();submit();finish();member(2,2);service_test_enemy(player);remove_test_enemy("two new results");none();
    assert(g_testEnemy.phase()==debug_test_enemy::Tracker::Phase::Quarantined);
    seed();characters[0].readable=false;header.debugFlags=4;service_test_enemy(player);
    assert(creator[0x44]==0 && g_testEnemy.phase()==debug_test_enemy::Tracker::Phase::Idle);none();
    seed();submit();recognize();characters[0].readable=false;remove_test_enemy("partial cleanup snapshot");none();
    puts("PASS: multiple new candidates and incomplete baseline/cleanup snapshots fail closed");

    seed();submit();recognize();g_life=LIFE_DEAD;header.debugFlags=12;update_test_enemy_context(false);
    assert((header.debugFlags&12)==0);g_life=LIFE_ALIVE;update_test_enemy_context(true);
    put(characters[1].ins+kChrHandle,uint64_t{777});remove_test_enemy("reloaded address");none();
    seed();submit();recognize();++g_frame.zone;update_test_enemy_context(true);remove_test_enemy("changed map");none();
    seed();submit();++header.hostLife;update_test_enemy_context(true);finish();service_test_enemy(player);
    remove_test_enemy("old life completion");none();
    puts("PASS: death/loading, zone change and host-life change forget old instances and late results");

    seed();header.debugFlags=12;service_test_enemy(player);assert(creator[0x44]==0);none();
    seed();submit();header.debugFlags=12;service_test_enemy(player);finish();service_test_enemy(player);none();
    assert(g_testEnemy.phase()==debug_test_enemy::Tracker::Phase::Quarantined);
    puts("PASS: removal wins simultaneous spawn+remove, including an already pending request");

    seed();header.debugFlags=4;update_test_enemy_context(false);assert((header.debugFlags&12)==0);
    update_test_enemy_context(true);service_test_enemy(player);assert(creator[0x44]==0);none();
    seed();creator[0x44]=1;header.debugFlags=4;service_test_enemy(player);
    assert(g_testEnemy.phase()==debug_test_enemy::Tracker::Phase::Idle);none();
    puts("PASS: loading discards queued spawn requests; busy creator is untouched");

    seed();submit();finish();put(characters[1].ins+kChrNpcParamId,int32_t{34510000});service_test_enemy(player);
    remove_test_enemy("different template");none();
    seed();submit();creator[0x44]=0;service_test_enemy(player);
    assert(g_testEnemy.phase()==debug_test_enemy::Tracker::Phase::Pending);remove_test_enemy("old output");none();
    puts("PASS: mismatched template and pre-request output are never claimed");

    // An old allocation may legitimately be reused for a NEW handle: recognition
    // compares full identities instead of rejecting every repeated pointer.
    seed();submit();put(characters[0].ins+kChrHandle,uint64_t{901});creator[0x44]=0;
    service_test_enemy(player);assert(g_testEnemy.phase()==debug_test_enemy::Tracker::Phase::Owned);
    remove_test_enemy("new instance at old allocation");assert(killed.size()==1 && killed[0]==901);
    puts("PASS: a newly created handle may be recognized even if its allocation address was reused");
}
}  // namespace mb
int main() { mb::test(); }
'''


def main():
    source = (ER / 'src/game.cpp').read_text(encoding='utf-8')
    start = source.index('// -- a test enemy (dev):')
    end = source.index('// ---------------------------------------------------------------------------------------', start)
    block = source[start:end]
    # Keep lifecycle cancellation outside the alive-only service branch.
    tick = source[source.index('static void __fastcall task_tick('):]
    assert tick.index('update_test_enemy_context(alive);') < tick.index('if (havePlayer) stand_in(')
    assert tick.index('update_test_enemy_context(alive);') < tick.index('if (alive) {')
    compiler = os.environ.get('CXX') or str(next((ROOT / '.tools/compiler').glob('*/bin/clang++.exe')))
    with tempfile.TemporaryDirectory(prefix='ermc-debug-ownership-') as directory:
        work = Path(directory)
        cpp = work / 'check.cpp'
        cpp.write_text(PREFIX + block + SUFFIX, encoding='utf-8')
        binary = work / 'check.exe'
        subprocess.run([compiler, '-std=c++17', '-static', '-Wall', '-Wextra', '-Werror',
                        f'-I{ER / "src"}', str(cpp), '-o', str(binary)], check=True)
        subprocess.run([str(binary)], check=True)
    print('PASS: actual native spawn/recognition/cleanup source; no game or live memory access')
    print('LIMIT: identical-template concurrent writes between observations remain indistinguishable')


if __name__ == '__main__':
    main()
