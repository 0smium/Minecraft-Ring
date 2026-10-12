"""Compile the real depth tracker and compositor fence helpers with CPU-only D3D stubs."""
from pathlib import Path
import os, shutil, subprocess

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'bridge-base/elden-ring/er-bridge/src'
OUT = ROOT / 'build/native-depth-resize-test'
OUT.mkdir(parents=True, exist_ok=True)
compositor = (SRC / 'compositor.cpp').read_text(encoding='utf8')

def block(marker):
    start = compositor.index(marker)
    opening = compositor.index('{', start)
    depth = 0
    for end in range(opening, len(compositor)):
        if compositor[end] == '{': depth += 1
        elif compositor[end] == '}':
            depth -= 1
            if depth == 0: return compositor[start:end + 1]
    raise AssertionError(marker)

prefix = r'''
#include <atomic>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <shared_mutex>
#include <unordered_map>
#include <vector>
using UINT=unsigned; using DWORD=unsigned; using UINT64=uint64_t; using HRESULT=int;
#define STDMETHODCALLTYPE
#define SUCCEEDED(x) ((x)>=0)
#define FAILED(x) ((x)<0)
struct SRWLOCK {std::shared_mutex mutex;};
#define SRWLOCK_INIT {}
static void AcquireSRWLockExclusive(SRWLOCK* p){p->mutex.lock();}
static void ReleaseSRWLockExclusive(SRWLOCK* p){p->mutex.unlock();}
static void AcquireSRWLockShared(SRWLOCK* p){p->mutex.lock_shared();}
static void ReleaseSRWLockShared(SRWLOCK* p){p->mutex.unlock_shared();}
struct InflightGuard {};
static void log(const char*,...) {}
static uint64_t clockMs=100;
static uint64_t now_ms(){return clockMs;}
constexpr unsigned D3D12_RESOURCE_DIMENSION_TEXTURE2D=2, D3D12_RESOURCE_FLAG_ALLOW_DEPTH_STENCIL=2;
constexpr unsigned DXGI_FORMAT_R24G8_TYPELESS=44, DXGI_FORMAT_D24_UNORM_S8_UINT=45,
    DXGI_FORMAT_R32G8X24_TYPELESS=19, DXGI_FORMAT_D32_FLOAT_S8X24_UINT=20;
using D3D12_RESOURCE_STATES=unsigned; using D3D12_RESOURCE_BARRIER_FLAGS=unsigned;
constexpr unsigned D3D12_RESOURCE_STATE_DEPTH_WRITE=16, D3D12_RESOURCE_STATE_DEPTH_READ=32,
    D3D12_RESOURCE_STATE_COMMON=0, D3D12_RESOURCE_BARRIER_TYPE_TRANSITION=0,
    D3D12_RESOURCE_BARRIER_FLAG_BEGIN_ONLY=1, D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES=~0u;
struct D3D12_RESOURCE_DESC {
    unsigned Dimension=2; uint64_t Width=1600; unsigned Height=900;
    struct {unsigned Count=1;} SampleDesc;
    unsigned DepthOrArraySize=1,MipLevels=1,Flags=2,Format=19;
};
struct ID3D12Resource {
    int refs=1; D3D12_RESOURCE_DESC desc;
    void AddRef(){assert(refs>0);++refs;}
    void Release(){assert(refs>0);--refs;}
    D3D12_RESOURCE_DESC GetDesc(){assert(refs>0);return desc;}
};
struct D3D12_RESOURCE_BARRIER {
    unsigned Type=0,Flags=0;
    struct {ID3D12Resource* pResource;unsigned StateBefore,StateAfter,Subresource;} Transition;
};
struct ID3D12CommandList {};
struct ID3D12GraphicsCommandList : ID3D12CommandList {};
struct ID3D12CommandAllocator {};
struct ID3D12PipelineState {};
static void barrierOriginal(ID3D12GraphicsCommandList*,UINT,const D3D12_RESOURCE_BARRIER*){}
static HRESULT resetOriginal(ID3D12GraphicsCommandList*,ID3D12CommandAllocator*,ID3D12PipelineState*){return 0;}
template<class T> static void safe_release(T*& p){if(p)p->Release();p=nullptr;}
struct FakeFence {
    uint64_t complete=0;
    uint64_t GetCompletedValue(){return complete;}
    HRESULT SetEventOnCompletion(uint64_t,void*){return 0;}
};
struct FakeQueue {
    bool finish=false,fail=false;
    HRESULT Signal(FakeFence* fence,uint64_t value){if(fail)return -1;if(finish)fence->complete=value;return 0;}
};
static DWORD WaitForSingleObject(void*,DWORD){return 258;}
static FakeFence fence;static FakeQueue queue;
static FakeFence* g_fence=&fence;static FakeQueue* g_queue=&queue;
static void* g_fenceEvent=nullptr;static uint64_t g_fenceNext=0;
struct IDXGISwapChain3 {};
static IDXGISwapChain3 chain;static IDXGISwapChain3* g_sc=&chain;
static unsigned buffersReleased=0;
static void release_backbuffers(){++buffersReleased;}
'''

real_helpers = '\n'.join([
    block('struct RingSlot {')+';',
    block('struct DepthSourcePin {')+';',
    'static SRWLOCK g_graphicsLock = SRWLOCK_INIT;\nstatic thread_local unsigned g_graphicsLockDepth = 0, g_resizeDepth = 0;',
    block('struct GraphicsLock {')+';',
    block('struct ResizeScope {')+';',
    block('static bool wait_fence('),
    block('static bool wait_idle('),
    block('static bool retire_depth_source('),
    'static RingSlot g_ring[3] = {};',
    block('static bool before_resize('),
])

tests = r'''
static void record(ID3D12GraphicsCommandList& list,ID3D12Resource& source) {
    D3D12_RESOURCE_BARRIER b{0,0,{&source,0,D3D12_RESOURCE_STATE_DEPTH_WRITE,D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES}};
    hkResourceBarrier(&list,1,&b);
    ID3D12CommandList* lists[]={&list};depth_submit(1,lists);
}
int main(){
    g_barrierOriginal=(void*)&barrierOriginal;g_resetOriginal=(void*)&resetOriginal;
    ID3D12GraphicsCommandList list;
    // The old implementation stopped discovering after its 32 retained resources.
    // Execute 48 rebuilds through the actual resize helper and actual tracker.
    ID3D12Resource sources[48]; // Distinct addresses, as actual resize generations allocate new buffers.
    for(int cycle=0;cycle<48;++cycle){
        ID3D12Resource& source=sources[cycle];source.desc.Width=cycle%2?2560:1600;source.desc.Height=cycle%2?1440:900;
        record(list,source);
        TrackedDepth snapshot{};assert(depth_snapshot((UINT)source.desc.Width,source.desc.Height,&snapshot));
        assert(snapshot.resource==&source && source.refs==3);
        {DepthSourcePin early{snapshot.resource};} // Early return discards its owned snapshot.
        assert(source.refs==2);
        assert(depth_snapshot((UINT)source.desc.Width,source.desc.Height,&snapshot));
        {DepthSourcePin pending{snapshot.resource};g_ring[0].depthSource=pending.detach();}
        g_ring[0].fence=++g_fenceNext;
        // A pending GPU copy pins the source. Neither slot reuse nor resize timeout retires it.
        assert(!retire_depth_source(g_ring[0]));
        assert(source.refs==3 && g_ring[0].depthSource==&source);
        assert(!before_resize(&chain));
        assert(source.refs==3 && g_trackedDepth.size()==1 && !g_depthChanges.empty());
        // The real resize helper waits for queue completion, clears closed-list records,
        // releases its fence pin and tracker ownership, even with no backbuffer-count guard.
        queue.finish=true;assert(before_resize(&chain));queue.finish=false;
        assert(source.refs==1 && g_ring[0].depthSource==nullptr);
        assert(g_trackedDepth.empty() && g_depthChanges.empty());
        source.Release();assert(source.refs==0);clockMs+=16;
        fence.complete=0;
    }
    assert(buffersReleased==48 && g_depthResetCount==48);
    // A stale closed command list cannot impose pre-resize state on the next generation.
    ID3D12Resource reused;record(list,reused);depth_tracking_reset();assert(reused.refs==1);
    ID3D12CommandList* oldLists[]={&list};depth_submit(1,oldLists);
    TrackedDepth snapshot{};assert(!depth_snapshot(1600,900,&snapshot));
    record(list,reused);assert(depth_snapshot(1600,900,&snapshot));
    DepthSourcePin leased{snapshot.resource};depth_tracking_reset();reused.Release();
    assert(reused.refs==1); // Snapshot survives tracker reset and engine-owner release.
    RingSlot slot{};slot.depthSource=leased.detach();slot.fence=++g_fenceNext;
    assert(!retire_depth_source(slot) && reused.refs==1);
    fence.complete=slot.fence;assert(retire_depth_source(slot) && reused.refs==0);
    // Exercise the real TLS guard for ResizeBuffers1 -> ResizeBuffers same-thread recursion.
    {GraphicsLock outer;ResizeScope resize;assert(resize.outer);
      {GraphicsLock inner;ResizeScope nested;assert(!nested.outer && g_resizeDepth==2);}
      assert(g_resizeDepth==1 && g_graphicsLockDepth==1);}
    assert(g_resizeDepth==0 && g_graphicsLockDepth==0);
    std::puts("PASS: 48 rebuilds, timeout retention, old-list invalidation, snapshot/pin/fence ownership and recursive resize");
}
'''

source=OUT/'test.cpp'
source.write_text(prefix+'\n#include "'+(SRC/'depth_tracking.h').as_posix()+'"\n'+real_helpers+'\n'+tests,encoding='utf8')
cxx=os.environ.get('CXX') or shutil.which('clang++') or shutil.which('g++')
if not cxx:
    cxx=str(next((ROOT/'.tools/compiler').glob('*/bin/clang++.exe')))
binary=OUT/('test.exe' if os.name=='nt' else 'test')
command=[cxx,'-std=c++17','-O1',str(source),'-o',str(binary)]
if os.name=='nt':
    command.insert(1,'-static')
    if 'clang' in Path(cxx).name:command.insert(1,'--target=x86_64-w64-mingw32')
subprocess.run(command,check=True)
subprocess.run([str(binary)],check=True)
