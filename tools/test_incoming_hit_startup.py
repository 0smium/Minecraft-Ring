"""Exercise real incoming-hit/game/core startup code with offline Win32/MinHook stubs.

No game process, injection, deployment or executable-memory patching. Function bodies
are extracted unchanged from production; only external OS/game boundaries are faked.
"""
from pathlib import Path
import argparse
import os
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
ER = ROOT / 'bridge-base/elden-ring/er-bridge'
SRC = ER / 'src'


def function(source, marker):
    start = source.index(marker)
    opening = source.index('{', start)
    depth = 0
    for end in range(opening, len(source)):
        if source[end] == '{':
            depth += 1
        elif source[end] == '}':
            depth -= 1
            if depth == 0:
                return source[start:end + 1]
    raise AssertionError(f'Incomplete production function: {marker}')


WINDOWS = r'''
#pragma once
#include <cstddef>
#include <shared_mutex>
using HWND=void*;using HANDLE=void*;using LONG=long;using DWORD=unsigned long;
using BOOL=int;using LPVOID=void*;using VOID=void;using LPCWSTR=const wchar_t*;using LPCSTR=const char*;
#define WINAPI
#ifndef __fastcall
#define __fastcall
#endif
#ifdef __declspec
#undef __declspec
#endif
#define __declspec(x)
struct SRWLOCK {std::shared_mutex mutex;};
#define SRWLOCK_INIT {}
inline void AcquireSRWLockExclusive(SRWLOCK* p){p->mutex.lock();}
inline void ReleaseSRWLockExclusive(SRWLOCK* p){p->mutex.unlock();}
inline void AcquireSRWLockShared(SRWLOCK* p){p->mutex.lock_shared();}
inline void ReleaseSRWLockShared(SRWLOCK* p){p->mutex.unlock_shared();}
inline LONG InterlockedIncrement(volatile LONG* p){return ++*p;}
inline LONG InterlockedDecrement(volatile LONG* p){return --*p;}
HANDLE CreateThread(void*,size_t,DWORD(*)(LPVOID),LPVOID,DWORD,DWORD*);
DWORD WaitForSingleObject(HANDLE,DWORD);
BOOL CloseHandle(HANDLE);
void Sleep(DWORD);
'''

PREFIX = r'''
#include <cassert>
#include <cstdio>
#include <cstring>
#include <map>
#include <vector>
#include "incoming_hits.h"

constexpr uintptr_t BASE=0x10000000;
static std::map<uintptr_t,std::vector<uint8_t>> memory;
struct Boundary {
    bool sharedMemory=true, mainSignature=true;
    MH_STATUS initialize=MH_OK, create=MH_OK, enable=MH_OK;
    unsigned sharedOpen=0, crashInit=0, initializeCalls=0, createCalls=0, enableCalls=0, removeCalls=0;
    unsigned optionalInit=0, testRunesInit=0, inventoryInit=0, namesClear=0, fpsPatch=0, combatPatch=0, taskPage=0, compositor=0, threads=0;
    unsigned sharedClose=0, crashClose=0, uninitialize=0, disable=0, gameStop=0, compositorStop=0, handlesClosed=0;
    bool hookCreated=false, hookEnabled=false;
} boundary;
static void __fastcall hpOriginal(void*,int,uint8_t,uint8_t,uint8_t,float,float,uint8_t){}
extern "C" MH_STATUS WINAPI MH_Initialize(){++boundary.initializeCalls;return boundary.initialize;}
extern "C" MH_STATUS WINAPI MH_CreateHook(LPVOID at,LPVOID detour,LPVOID* original){
    ++boundary.createCalls;assert((uintptr_t)at==BASE+0x436AE0 && detour);
    if(boundary.create==MH_OK){boundary.hookCreated=true;*original=(void*)&hpOriginal;}return boundary.create;
}
extern "C" MH_STATUS WINAPI MH_EnableHook(LPVOID at){
    ++boundary.enableCalls;assert((uintptr_t)at==BASE+0x436AE0 && boundary.hookCreated);
    if(boundary.enable==MH_OK)boundary.hookEnabled=true;
    return boundary.enable;
}
extern "C" MH_STATUS WINAPI MH_RemoveHook(LPVOID at){
    ++boundary.removeCalls;assert((uintptr_t)at==BASE+0x436AE0 && boundary.hookCreated);
    boundary.hookCreated=false;boundary.hookEnabled=false;return MH_OK;
}
extern "C" MH_STATUS WINAPI MH_DisableHook(LPVOID at){assert(at==MH_ALL_HOOKS);++boundary.disable;boundary.hookEnabled=false;return MH_OK;}
extern "C" MH_STATUS WINAPI MH_Uninitialize(){++boundary.uninitialize;boundary.hookCreated=false;boundary.hookEnabled=false;return MH_OK;}
HANDLE CreateThread(void*,size_t,DWORD(*entry)(LPVOID),LPVOID,DWORD,DWORD*){
    assert(entry && boundary.hookCreated && boundary.hookEnabled);++boundary.threads;
    return (HANDLE)(uintptr_t)(0xF000+boundary.threads); // Count only; never executes a worker.
}
DWORD WaitForSingleObject(HANDLE handle,DWORD){assert(handle);return 0;}
BOOL CloseHandle(HANDLE handle){assert(handle);++boundary.handlesClosed;return 1;}
void Sleep(DWORD){}

namespace mb {
volatile LONG g_inflight=0;
static HANDLE g_worker=nullptr,g_pacer=nullptr;
static volatile LONG g_stop=0;
static DWORD WINAPI worker(LPVOID){return 0;}
static DWORD WINAPI pacer(LPVOID){return 0;}
static ErmcHeader header{};static ErmcGameState state{};
bool mem_read(const void* at,void* out,size_t size){
    auto it=memory.find((uintptr_t)at);if(it==memory.end()||it->second.size()<size)return false;
    memcpy(out,it->second.data(),size);return true;
}
void log(const char*,...){}
void log_close(){}
uint64_t now_ms(){return 1000;}
ErmcHeader* shm_header(){return &header;}
ErmcGameState* shm_state(){return &state;}
bool shm_open(){++boundary.sharedOpen;return boundary.sharedMemory;}
void shm_close(){++boundary.sharedClose;}
void crash_init(){++boundary.crashInit;}
void crash_shutdown(){++boundary.crashClose;}
uintptr_t main_module_base(){return BASE;}
size_t main_module_size(){return 0x800000;}
struct CodeSig {uintptr_t rva;const char* sig;const char* name;};
static const CodeSig kSigs[]={{0x123,"90","main"}};
static const CodeSig kEntityNameSigs[]={{0x456,"90","names"}};
static const CodeSig kCombatSigs[]={{0x789,"90","combat"}};
static const CodeSig kActionSigs[]={{0xABC,"90","action"}};
static bool bytes_match(uintptr_t address,const char*){return address!=BASE+0x123 || boundary.mainSignature;}
static uintptr_t g_base=0;
static bool g_ok=false,g_hostProgressionOk=false,g_entityNamesOk=false,g_fallTraceOk=false,g_environmentOk=false,g_combatOk=false,g_actionOk=false;
struct OptionalReader {bool init(uintptr_t){++boundary.optionalInit;return true;}};
static OptionalReader g_hostEffects,g_hostProgression;
struct TestRuneReader {bool init(uintptr_t){++boundary.testRunesInit;return true;}};
[[maybe_unused]] static TestRuneReader g_testRunes; // absent in the pre-fix comparison revision
struct InventoryFeed {bool init(uintptr_t){++boundary.inventoryInit;return true;} void reset(uint64_t,uint32_t,uint64_t){}};
[[maybe_unused]] static InventoryFeed g_hostInventory;
struct TaskPage {void* target[2]={};};
static TaskPage page{};static TaskPage* g_page=nullptr;
static TaskPage* find_or_make_page(){++boundary.taskPage;return &page;}
static void task_tick(){}static void task_camera(){}
static void clear_entity_names(){++boundary.namesClear;}
static void unlock_fps(){++boundary.fpsPatch;}
static void patch_poke_row(){++boundary.combatPatch;}
static void publish_host_effects(int,bool,uint64_t,uint32_t){}
static void publish_host_progression(int,bool,bool,uint64_t,uint32_t){}
bool compositor_init(){++boundary.compositor;return true;}
void compositor_detach(){}
void compositor_shutdown(){++boundary.compositorStop;}
void game_shutdown(){++boundary.gameStop;}
'''

TESTS = r'''
static void reset(){
    using namespace mb;
    boundary={};memory.clear();header={};state={};g_worker=g_pacer=nullptr;g_stop=0;g_inflight=0;
    g_base=0;g_ok=false;g_page=nullptr;page={};
    g_hostProgressionOk=g_entityNamesOk=g_fallTraceOk=g_environmentOk=g_combatOk=g_actionOk=false;
    incoming_hits::original=nullptr;incoming_hits::returnAddress=0;incoming_hits::installed=false;
    incoming_hits::target={};incoming_hits::generation=0;incoming_hits::pending={};
    seed();
}
static void noTakeover(){
    using namespace mb;
    assert(!g_ok && g_page==nullptr && page.target[0]==nullptr && page.target[1]==nullptr);
    assert(boundary.optionalInit==0 && boundary.testRunesInit==0 && boundary.inventoryInit==0 && boundary.namesClear==0 && boundary.fpsPatch==0 && boundary.combatPatch==0 && boundary.taskPage==0);
    assert(boundary.compositor==0 && boundary.threads==0 && g_worker==nullptr && g_pacer==nullptr);
    assert(!incoming_hits::installed && !boundary.hookEnabled);
}
static void cleanFailure(){
    erb_core_shutdown();
    assert(!boundary.hookCreated && !boundary.hookEnabled && boundary.handlesClosed==0);
    assert(boundary.disable==1 && boundary.uninitialize==1 && boundary.sharedClose==1 && boundary.crashClose==1);
}
int main(){
    using namespace mb;
    // Every signature used by the real header must reject mismatch and unreadable memory.
    for(uintptr_t address:SIGNATURE_ADDRESSES){
        reset();memory[address][0]^=1;assert(!incoming_hits::init(BASE));
        assert(boundary.createCalls==0 && boundary.enableCalls==0 && !incoming_hits::installed);
        reset();memory.erase(address);assert(!incoming_hits::init(BASE));assert(boundary.createCalls==0);
        reset();memory[address][0]^=1;assert(!erb_core_init());noTakeover();assert(boundary.createCalls==0);cleanFailure();
        reset();memory.erase(address);assert(!erb_core_init());noTakeover();assert(boundary.createCalls==0);cleanFailure();
    }
    for(MH_STATUS status:{MH_ERROR_MEMORY_ALLOC,MH_ERROR_UNSUPPORTED_FUNCTION,MH_ERROR_ALREADY_CREATED}){
        reset();boundary.create=status;assert(!incoming_hits::init(BASE));
        assert(boundary.createCalls==1 && boundary.enableCalls==0 && boundary.removeCalls==0 && !incoming_hits::installed);
        reset();boundary.create=status;assert(!erb_core_init());noTakeover();
        assert(boundary.createCalls==1 && boundary.enableCalls==0 && !boundary.hookCreated);cleanFailure();
    }
    for(MH_STATUS status:{MH_ERROR_MEMORY_PROTECT,MH_ERROR_NOT_CREATED,MH_ERROR_ENABLED}){
        reset();boundary.enable=status;assert(!incoming_hits::init(BASE));
        assert(boundary.createCalls==1 && boundary.enableCalls==1 && boundary.removeCalls==1);
        assert(incoming_hits::original==nullptr && !incoming_hits::installed && !boundary.hookCreated);
        reset();boundary.enable=status;assert(!erb_core_init());noTakeover();
        assert(boundary.removeCalls==1 && incoming_hits::original==nullptr);cleanFailure();
    }
    reset();boundary.mainSignature=false;assert(!erb_core_init());noTakeover();assert(boundary.createCalls==0);cleanFailure();
    reset();boundary.initialize=MH_ERROR_MEMORY_ALLOC;assert(!erb_core_init());noTakeover();assert(boundary.createCalls==0);cleanFailure();
    reset();boundary.sharedMemory=false;assert(!erb_core_init());noTakeover();assert(boundary.initializeCalls==0 && boundary.crashInit==0);
    // Healthy startup actually reaches the existing later optional work, task attachment and two thread boundaries.
    for(MH_STATUS status:{MH_OK,MH_ERROR_ALREADY_INITIALIZED}){
        reset();boundary.initialize=status;assert(erb_core_init());
        assert(g_ok && incoming_hits::installed && boundary.hookCreated && boundary.hookEnabled);
        assert(incoming_hits::original==&hpOriginal && incoming_hits::returnAddress==BASE+0x448AA0);
        assert(boundary.optionalInit==2 && boundary.fpsPatch==1 && boundary.combatPatch==1 && boundary.taskPage==1);
        assert(page.target[0]==(void*)&task_tick && page.target[1]==(void*)&task_camera);
        assert(boundary.compositor==1 && boundary.threads==2 && g_worker && g_pacer);
        incoming_hits::set_target((void*)0x2000,(void*)0x3000,1);incoming_hits::pending.count=1;
        erb_core_shutdown();assert(!boundary.hookCreated && !boundary.hookEnabled && boundary.handlesClosed==2);
        assert(page.target[0]==nullptr && page.target[1]==nullptr && incoming_hits::target.data==nullptr && incoming_hits::pending.count==0);
    }
    puts("PASS: real incoming_hits.h rejects all five unreadable/mismatched signatures and CreateHook/EnableHook failures; failed enable removes its hook");
    puts("PASS: actual game_init/core init fails before optional patches, takeover tasks, compositor and worker/pacer; healthy startup proceeds, real shutdown clears task/hook ownership");
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--regression-ref', help='Also prove the same assertions reject game/core functions from this Git revision')
    args = parser.parse_args()
    incoming = (SRC / 'incoming_hits.h').read_text(encoding='utf8')
    game = (SRC / 'game.cpp').read_text(encoding='utf8')
    core = (SRC / 'core.cpp').read_text(encoding='utf8')
    # Seed exactly the production byte arrays. This is test input, not a second implementation of init.
    arrays = dict(re.findall(r'static const uint8_t (\w+)\[\] = \{(.*?)\};', incoming, re.S))
    checks = re.findall(r'match\(base \+ (0x[0-9A-Fa-f]+), (\w+), sizeof\(\2\)\)', incoming)
    if len(checks) != 5:
        raise AssertionError('Incoming signature schema changed; review this fixture before accepting it')
    seed = 'static void seed(){\n' + '\n'.join(
        f'memory[BASE+{address}]={{{arrays[name]}}};' for address, name in checks) + '\n}\n'
    addresses = 'static const uintptr_t SIGNATURE_ADDRESSES[]={' + ','.join('BASE+' + address for address, _ in checks) + '};\n'
    def fixture(game_source, core_source):
        actual = function(game_source, 'bool game_init()') + '\n' + function(game_source, 'void game_detach()') + '\n}\n'
        actual += function(core_source, 'extern "C" __declspec(dllexport) bool erb_core_init()') + '\n'
        actual += function(core_source, 'extern "C" __declspec(dllexport) void erb_core_shutdown()') + '\n'
        return PREFIX + actual + seed + addresses + TESTS
    compiler = os.environ.get('CXX') or shutil.which('clang++') or shutil.which('g++')
    if not compiler:
        compiler = str(next((ROOT / '.tools/compiler').glob('*/bin/clang++.exe')))
    with tempfile.TemporaryDirectory(prefix='ermc-incoming-startup-') as directory:
        work = Path(directory)
        (work / 'windows.h').write_text(WINDOWS, encoding='utf8')
        check = work / 'check.cpp'; check.write_text(fixture(game, core), encoding='utf8')
        binary = work / ('check.exe' if os.name == 'nt' else 'check')
        command = [compiler, '-std=c++17', '-Wall', '-Wextra', '-Werror', f'-I{work}',
                   f'-I{ER / "include"}', f'-I{SRC}', f'-I{ER / "third_party/minhook/include"}', str(check), '-o', str(binary)]
        if os.name == 'nt':
            command.insert(1, '-static')
            if 'clang' in Path(compiler).name:
                command.insert(1, '--target=x86_64-w64-mingw32')
        subprocess.run(command, check=True)
        subprocess.run([str(binary)], check=True)
        if args.regression_ref:
            for name, original in [('game.cpp', game), ('core.cpp', core)]:
                relative = (SRC / name).relative_to(ROOT).as_posix()
                previous = subprocess.check_output(['git', 'show', f'{args.regression_ref}:{relative}'], cwd=ROOT, text=True, encoding='utf8')
                if previous == original:
                    raise AssertionError(f'{name} has no regression delta at {args.regression_ref}')
                check.write_text(fixture(previous, core) if name == 'game.cpp' else fixture(game, previous), encoding='utf8')
                subprocess.run(command, check=True)
                rejected = subprocess.run([str(binary)], capture_output=True, text=True)
                if rejected.returncode == 0:
                    raise AssertionError(f'Assertions did not detect pre-fix {name}')
                print(f'PASS: same assertions reject pre-fix production {name} from {args.regression_ref}', flush=True)


if __name__ == '__main__':
    main()
