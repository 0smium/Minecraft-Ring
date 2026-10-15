// Private-memory/native-call fixture: never attaches to or starts Elden Ring.
#define ERMC_COLLIDER_FIXTURE
#include "native_colliders.h"
#include <cassert>
#include <cstdio>
#include <map>
#include <vector>
using namespace mb::native_colliders;
unsigned fakeThread=7;
constexpr uintptr_t BASE=0x10000000,HK=0x30000000,CS=0x31000000,MAN=0x32000000,PLAYER=0x33000000;
constexpr uintptr_t MODULES=0x34000000,PHYSICS=0x35000000,WRAPPER=0x36000000,PROXY=0x37000000,CHARSHAPE=0x38000000;
constexpr uintptr_t FILTERWRAP=0x39000000,FILTER=0x3A000000,GAME=0x3B000000,SESSION=0x3C000000,TABLE=0x40000000;
constexpr uintptr_t HK2=0x41000000,CS2=0x42000000,TABLE2=0x43000000;
static std::map<uintptr_t,std::vector<uint8_t>> memory;
static std::map<uintptr_t,int> refs;
static unsigned creates=0,adds=0,destroys=0,releases=0,nextShape=0,nextId=1;
static bool delayAdd=false,delayDestroy=false,failFactory=false,failCreate=false;
static std::vector<std::pair<uintptr_t,uint32_t>> pendingAdds,pendingDestroys;
static std::vector<char> actions;
namespace mb {
bool mem_read(const void* p,void* out,size_t size){
    auto a=(uintptr_t)p;auto i=memory.upper_bound(a);if(i==memory.begin())return false;--i;
    if(a-i->first>i->second.size()||size>i->second.size()-(a-i->first))return false;
    memcpy(out,i->second.data()+a-i->first,size);return true;
}
}
template<class T>static void put(uintptr_t a,const T& value){
    auto i=memory.upper_bound(a);assert(i!=memory.begin());--i;
    assert(a-i->first+sizeof(value)<=i->second.size());memcpy(i->second.data()+a-i->first,&value,sizeof(value));
}
template<class T>static T get(uintptr_t a){T out;assert(mb::mem_read((void*)a,&out,sizeof(out)));return out;}
static uintptr_t bodyAddress(uintptr_t hk,uint32_t id){return get<uintptr_t>(hk+0x28)+(id&INVALID_BODY)*BODY_STRIDE;}
static void worldMemory(uintptr_t cs,uintptr_t hk,uintptr_t table){
    memory[cs].resize(0x20);put(cs,BASE+0x2B96978);put(cs+8,hk);
    memory[hk].resize(0x500);put(hk,BASE+0x2EF0DD8);put(hk+0x28,table);put(hk+0x30,uint32_t(9000));
    memory[table].resize(9000*BODY_STRIDE);put(table+0x70,uint32_t(0));put(table+0x44,uint32_t(1));put(table+0x40,uint32_t(0));
    put(table+0x60,CHARSHAPE);put(table+0x6A,uint16_t(4));put(table+0x6C,uint32_t(56));put(table+0x78,uint32_t(0));
    put(hk+0x4D0,FILTERWRAP);
}
static void seed(){
    memory.clear();refs.clear();actions.clear();pendingAdds.clear();pendingDestroys.clear();
    creates=adds=destroys=releases=nextShape=0;nextId=1;delayAdd=delayDestroy=failFactory=failCreate=false;fakeThread=7;
    for(const auto& c:kChecks)memory[BASE+c.rva]={c.bytes,c.bytes+c.size};
    memory[BASE+0x3D7A0D0].resize(8);put(BASE+0x3D7A0D0,MAN);
    memory[BASE+0x3D6D988].resize(8);put(BASE+0x3D6D988,GAME);
    memory[BASE+0x3D7E540].resize(8);put(BASE+0x3D7E540,SESSION);
    memory[BASE+0x3C19CC0].resize(1);put(BASE+0x3C19CC0,uint8_t(1));
    memory[MAN].resize(0xB0);put(MAN,BASE+0x2B934E8);put(MAN+0x98,CS);
    memory[GAME].resize(0xBD0);put(GAME,BASE+0x2A82110);
    memory[SESSION].resize(0x20);put(SESSION,BASE+0x2B9D1B8);
    memory[PLAYER].resize(0x200);put(PLAYER,BASE+0x2A7FBB0);put(PLAYER+0x190,MODULES);
    memory[MODULES].resize(0x80);put(MODULES+0x68,PHYSICS);
    memory[PHYSICS].resize(0xA8);put(PHYSICS,BASE+0x2A3C890);put(PHYSICS+8,PLAYER);put(PHYSICS+0x98,WRAPPER);
    memory[WRAPPER].resize(0x90);put(WRAPPER+0x88,PROXY);put(WRAPPER+0x80,CHARSHAPE);
    memory[PROXY].resize(0x90);put(PROXY,BASE+0x2B95260);put(PROXY+0x28,CHARSHAPE);put(PROXY+0x38,INVALID_BODY);put(PROXY+0x3C,uint32_t(2));put(PROXY+0x40,HK);
    memory[CHARSHAPE].resize(0x40);put(CHARSHAPE,BASE+0x2EE0000);
    memory[FILTERWRAP].resize(0x40);put(FILTERWRAP,BASE+0x2EF22F8);put(FILTERWRAP+0x30,FILTER);put(FILTERWRAP+0x38,HK);
    memory[FILTER].resize(0x820);put(FILTER,BASE+0x2B94DF0);put(FILTER+0x20+(2*2)*8,uint64_t(1)<<56);
    worldMemory(CS,HK,TABLE);worldMemory(CS2,HK2,TABLE2);
}
static void switchWorld(bool second){put(MAN+0x98,second?CS2:CS);put(PROXY+0x40,second?HK2:HK);put(FILTERWRAP+0x38,second?HK2:HK);}
static void* __fastcall configInit(void* p){memset(p,0x11,0x50);return p;}
static void __fastcall configDrop(void*){}
static void* __fastcall factory(const Vertices* v,float radius,void*){
    assert(v->count==8&&v->stride==16&&radius==0&&((uintptr_t)v->data&15)==0);
    for(int i=0;i<8;++i)assert(v->data[i].v[3]==0);
    if(failFactory)return nullptr;
    uintptr_t shape=0x60000000+(++nextShape)*0x1000;memory[shape].resize(0x40);put(shape,BASE+0x2EE0000);refs[shape]=1;return (void*)shape;
}
static void __fastcall addRef(void* p){assert(refs[(uintptr_t)p]>0);++refs[(uintptr_t)p];}
static void __fastcall release(void* p){assert(refs[(uintptr_t)p]>0);--refs[(uintptr_t)p];++releases;}
static void* __fastcall cinfoInit(void* p){memset(p,0x55,0xB0);return p;}
static void* __fastcall cinfoDrop(void* p,uint32_t flags){assert(flags==0);release(*(void**)p);return p;}
static uint32_t* __fastcall createBody(void* world,uint32_t* id,void* raw){
    auto* info=(uint8_t*)raw;uintptr_t shape=0;memcpy(&shape,info,8);assert(refs[shape]==2);
    uint32_t filter;uint16_t material;memcpy(&filter,info+0x10,4);memcpy(&material,info+0x14,2);assert(filter==56&&material==4);
    for(unsigned i:{8u,0x18u,0x20u,0x28u,0x40u,0x80u,0xA8u})assert(info[i]==0x55);
    if(failCreate){*id=INVALID_BODY;return id;}
    *id=nextId++;auto body=bodyAddress((uintptr_t)world,*id);put(body+0x70,*id);put(body+0x44,uint32_t(1));
    put(body+0x40,uint32_t(0));put(body+0x60,shape);put(body+0x6A,material);put(body+0x6C,filter);put(body+0x78,UINT32_MAX);put(body+0xA0,uintptr_t(0));
    for(int i=0;i<3;++i){float value;memcpy(&value,info+0x30+i*4,4);put(body+0x30+i*4,value);}
    ++refs[shape];++creates;actions.push_back('C');return id;
}
static void __fastcall addBodies(void* w,const uint32_t* id,int32_t n,int32_t pending,int32_t mode){
    assert(n==1&&pending==1&&mode==0);++adds;actions.push_back('A');
    if(delayAdd)pendingAdds.emplace_back((uintptr_t)w,*id);else put(bodyAddress((uintptr_t)w,*id)+0x78,*id);
}
static void retire(uintptr_t w,uint32_t id){auto p=bodyAddress(w,id);release((void*)get<uintptr_t>(p+0x60));put(p+0x44,uint32_t(0));put(p+0x78,UINT32_MAX);}
static void __fastcall destroyBodies(void* w,const uint32_t* id,int32_t n,int32_t mode){
    assert(n==1&&mode==0);++destroys;actions.push_back('D');if(delayDestroy)pendingDestroys.emplace_back((uintptr_t)w,*id);else retire((uintptr_t)w,*id);
}
static uint32_t* __fastcall ray(void*,uint32_t* id,uint32_t filter,const Vector* origin,const Vector*,Vector* point,Vector* normal,uint32_t*){
    assert(filter==2);*id=0;*point=*origin;point->v[1]-=.5f;*normal={{0,1,0,0}};return id;
}
static Api::Calls calls(){return {configInit,cinfoInit,configDrop,addRef,release,factory,cinfoDrop,createBody,addBodies,destroyBodies,ray};}
static Context context(){Context c;c.enabled=c.offline=c.hostReady=true;c.taskThreadId=7;c.hostLife=1;c.zone=10;c.epoch=1;c.player=PLAYER;return c;}
static Box box(double x){return {{x,0,0},{x+1,1,1}};}
static void init(Driver& d){assert(d.init(BASE,0x6000000,true));d.fixtureCalls(calls());}
static void allReleased(){for(auto p:refs)assert(p.second==0);}
int main(){
    seed();Driver disabled;assert(!disabled.init(BASE,0x6000000,false));auto c=context();Box b[2]={box(1),box(3)};
    disabled.tick(c,b,1);assert(!creates);
    seed();memory[BASE+kChecks[0].rva][0]^=1;Driver wrong;assert(!wrong.init(BASE,0x6000000,true));
    seed();Driver d;init(d);c.enabled=false;d.tick(c,b,1);assert(!creates);c.enabled=true;
    fakeThread=8;d.tick(c,b,1);assert(!creates);fakeThread=7;
    put(GAME+0xBC8,uint8_t(1));d.tick(c,b,1);assert(!creates);put(GAME+0xBC8,uint8_t(0));
    put(SESSION+0xC,uint32_t(3));d.tick(c,b,1);assert(!creates);put(SESSION+0xC,uint32_t(0));
    put(SESSION+0x10,uint32_t(6));d.tick(c,b,1);assert(!creates);put(SESSION+0x10,uint32_t(0));
    put(GAME+0x10,uint8_t(1));d.tick(c,b,1);assert(!creates);put(GAME+0x10,uint8_t(0));
    put(MAN+0xA8,uintptr_t(0x50000000));d.tick(c,b,1);assert(!creates&&!d.status().complete);put(MAN+0xA8,uintptr_t(0));
    auto s=d.tick(c,b,2);assert(s.complete&&s.owned==2&&s.broadphase==2&&creates==2&&adds==2);
    assert(d.ownsBody({CS,HK},1));assert(refs[0x60001000]==2);
    put(MAN+0xA8,uintptr_t(0x50000000));c.hostReady=false;d.tick(c,nullptr,0);
    assert(!destroys&&d.status().owned==2&&!d.status().complete);put(MAN+0xA8,uintptr_t(0));c.hostReady=true;
    float a[3]={0,.5f,.5f},z[3]={2,.5f,.5f};assert(d.occludesTerrainRay(a,z));
    float miss[3]={0,10,0},missEnd[3]={5,10,0};assert(!d.occludesTerrainRay(miss,missEnd));
    s=d.tick(c,b,1);assert(s.complete&&s.owned==1&&destroys==1);
    // Replacements enter the broadphase before an old floor disappears.
    Box replacement=box(5);delayAdd=true;unsigned oldDestroys=destroys;
    s=d.tick(c,&replacement,1);assert(!s.complete&&s.owned==2&&destroys==oldDestroys);
    for(auto p:pendingAdds)put(bodyAddress(p.first,p.second)+0x78,p.second);pendingAdds.clear();delayAdd=false;
    s=d.tick(c,&replacement,1);assert(s.complete&&s.owned==1&&destroys==oldDestroys+1);
    // Deferred destruction is submitted once; a re-added key waits for retirement.
    delayDestroy=true;s=d.tick(c,nullptr,0);assert(!s.complete&&s.owned==1);oldDestroys=destroys;
    s=d.tick(c,&replacement,1);assert(!s.complete&&destroys==oldDestroys);
    for(auto p:pendingDestroys)retire(p.first,p.second);pendingDestroys.clear();delayDestroy=false;
    s=d.tick(c,&replacement,1);assert(s.complete&&s.owned==1);
    // Native generation replacement must never be destroyed.
    uint32_t last=nextId-1;auto p=bodyAddress(HK,last);auto held=get<uintptr_t>(p+0x60);
    release((void*)held);put(p+0x70,uint32_t(last|0x01000000));put(p+0x60,CHARSHAPE);oldDestroys=destroys;
    c.hostReady=false;s=d.tick(c,nullptr,0);assert(s.owned==0&&destroys==oldDestroys);allReleased();
    unsigned oldReleases=releases;d.tick(c,nullptr,0);assert(releases==oldReleases);
    // A same-generation replacement shape belongs to someone else as well.
    c.hostReady=true;d.tick(c,b,1);last=nextId-1;p=bodyAddress(HK,last);held=get<uintptr_t>(p+0x60);
    release((void*)held);put(p+0x60,CHARSHAPE);oldDestroys=destroys;oldReleases=releases;
    c.hostReady=false;s=d.tick(c,nullptr,0);assert(s.owned==0&&destroys==oldDestroys&&releases==oldReleases+1);
    oldReleases=releases;d.tick(c,nullptr,0);assert(releases==oldReleases);allReleased();
    c.hostReady=true;s=d.tick(c,b,2);assert(s.complete);
    // World replacement does not dereference/destroy the retired world's objects.
    switchWorld(true);oldDestroys=destroys;c.epoch++;c.zone++;
    s=d.tick(c,b,1);assert(s.owned==1&&s.quarantined==1&&s.quarantinedBodies==2&&destroys==oldDestroys);
    switchWorld(false);c.epoch++;c.zone++;s=d.tick(c,b,1);
    assert(s.owned==1&&s.quarantined==1&&s.quarantinedBodies==1&&destroys==oldDestroys+2);
    c.hostReady=false;d.tick(c,nullptr,0);switchWorld(true);d.tick(c,nullptr,0);allReleased();
    // Larger batches obey per-task creation/removal caps and offset/life resets.
    c.hostReady=true;c.epoch++;std::vector<Box> large;for(unsigned i=0;i<220;++i)large.push_back(box(i*2.));
    for(int i=0;i<100;++i){s=d.tick(c,large.data(),large.size());assert(s.created<=96&&s.removed<=128);if(s.complete)break;}
    assert(s.complete&&s.owned==220);
    c.havokOffset[0]=100;c.hostLife++;s=d.tick(c,large.data(),large.size());assert(s.created==0&&s.removed==128&&s.owned==92);
    assert(d.occludesTerrainRay(miss,missEnd)); // unknown while coordinate identity cleanup is pending
    for(int i=0;i<100;++i){s=d.tick(c,large.data(),large.size());if(s.complete)break;}assert(s.complete);
    c.hostReady=false;for(int i=0;i<5;++i)d.tick(c,nullptr,0);assert(d.status().owned==0);allReleased();
    // Failed template/body prerequisites do not allocate.
    c.hostReady=true;c.epoch++;put(FILTER+0x20+(2*2)*8,uint64_t(0));oldDestroys=creates;d.tick(c,b,1);assert(creates==oldDestroys);
    put(FILTER+0x20+(2*2)*8,uint64_t(1)<<56);failFactory=true;d.tick(c,b,1);unsigned shapes=nextShape;d.tick(c,b,1);assert(nextShape==shapes&&creates==oldDestroys);
    failFactory=false;c.hostReady=false;d.tick(c,nullptr,0);c.hostReady=true;d.tick(c,b,1);assert(d.status().owned==1);
    // Lost offline/invalid world table keeps references without risky cleanup.
    oldDestroys=destroys;oldReleases=releases;put(SESSION+0x10,uint32_t(6));c.hostReady=false;c.havokOffset[0]++;
    d.tick(c,nullptr,0);assert(d.occludesTerrainRay(miss,missEnd));
    assert(destroys==oldDestroys&&releases==oldReleases);put(SESSION+0x10,uint32_t(0));put(HK2+0x30,uint32_t(1000001));
    d.tick(c,nullptr,0);assert(destroys==oldDestroys&&releases==oldReleases);put(HK2+0x30,uint32_t(9000));d.tick(c,nullptr,0);allReleased();
    // Factory success + create invalid ID drops cinfo's extra ref and our ref.
    c.hostReady=true;failCreate=true;oldReleases=releases;oldDestroys=creates;
    d.tick(c,b,1);assert(d.status().owned==0&&creates==oldDestroys&&releases==oldReleases+2);allReleased();
    oldReleases=releases;d.tick(c,b,1);assert(releases==oldReleases); // Failure latch prevents repeat allocations.
    failCreate=false;c.hostReady=false;d.tick(c,nullptr,0);
    c.hostReady=true;d.tick(c,b,1);oldDestroys=destroys;oldReleases=releases;auto abandoned=d.abandon();
    assert(abandoned.abandoned==1&&destroys==oldDestroys&&releases==oldReleases);d.tick(c,b,1);assert(destroys==oldDestroys);
    puts("PASS: native collider factory/cinfo/create/add/destroy/ref ownership, complete snapshots, broadphase replacement, delayed retirement, stale generations, world quarantine, body budgets, coordinate/life changes, ray unknown guard, offline/thread/failure gates and no-call abandonment");
    puts("LIMIT: native calls are private fixture callbacks; no live Havok body or collision behavior was tested");
}
