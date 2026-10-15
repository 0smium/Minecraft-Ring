// Adapted from SocketByte/EldenCraft native_colliders.rs at ba7591cdde9fb16e17552cfe53546f44d0fd856e.
// Copyright (c) 2026 Adam Grzegorzewski. MIT license: ../third_party/eldencraft/LICENSE.
// No native calls in destructors. Caller must be the joined post-physics game task.
#pragma once
#include "common.h"
#include "native_colliders_signatures.h"
#include <array>
#include <cmath>
#include <cstring>
#include <limits>

namespace mb::native_colliders {
constexpr uint32_t INVALID_BODY=0x00FFFFFFu;
constexpr size_t MAX_BODIES=4096, BODY_STRIDE=0xB0;
struct Box { double min[3]={}, max[3]={}; };
struct World {
    uintptr_t cs=0,hk=0;
    bool operator==(const World& b)const{return cs==b.cs&&hk==b.hk;}
    bool operator!=(const World& b)const{return !(*this==b);}
};
struct Context {
    bool enabled=false,offline=false,hostReady=false;
    uint32_t taskThreadId=0,hostLife=0,zone=0;
    uint64_t epoch=0;
    uintptr_t player=0;
    double havokOffset[3]={},feetHavok[3]={}; // Havok = this project's stable + offset.
};
struct alignas(16) Vector { float v[4]; };
struct Vertices { const Vector* data; int32_t count,stride; };
template<size_t N>struct alignas(16) Native {
    uint8_t bytes[N]={};
    template<class T>void put(size_t at,const T& value){memcpy(bytes+at,&value,sizeof(value));}
};
static_assert(sizeof(Vector)==16&&alignof(Vector)==16&&sizeof(Vertices)==16&&sizeof(Native<0xB0>)==0xB0,"native Havok ABI");
struct Owned { uint32_t id=INVALID_BODY;uintptr_t shape=0;Box bounds;bool destroySubmitted=false; };
struct Template { uint32_t body=INVALID_BODY,filter=0,characterFilter=0;uint16_t material=0;uintptr_t shape=0; };
struct Hit { bool hit=false;uint32_t body=INVALID_BODY;float point[3]={},normal[3]={}; };
enum class SlotState { Invalid,Missing,Present };
enum class Removal { Error,Pending,Removed };

inline bool finite3(const double* p){for(int i=0;i<3;++i)if(!std::isfinite(p[i])||std::abs(p[i])>1000000.)return false;return true;}
inline bool validBox(const Box& b){
    if(!finite3(b.min)||!finite3(b.max))return false;
    for(int i=0;i<3;++i)if(b.max[i]-b.min[i]<.002||b.max[i]-b.min[i]>128.)return false;
    return true;
}
inline bool geometry(const Box& b,const double* offset,Vector& center,std::array<Vector,8>& vertices){
    if(!validBox(b)||!finite3(offset))return false;
    for(int i=0;i<3;++i){
        double c=(b.min[i]+b.max[i])*.5+offset[i],h=(b.max[i]-b.min[i])*.5;
        if(!std::isfinite(c)||std::abs(c)>1000000.)return false;
        center.v[i]=float(c);
        for(unsigned j=0;j<8;++j)vertices[j].v[i]=float((j&(1u<<i))?h:-h);
    }
    center.v[3]=0;for(auto& v:vertices)v.v[3]=0;return true;
}
inline bool layerPairAllowed(uint32_t a,uint32_t b,uint64_t word){
    auto ag=(a>>7)&3,bg=(b>>7)&3;
    return (word&(uint64_t(1)<<(b&63)))&&(ag==0||bg==0||ag!=bg);
}

class Api {
public:
    using Init=void*(__fastcall*)(void*);
    using Ref=void(__fastcall*)(void*);
    using Factory=void*(__fastcall*)(const Vertices*,float,void*);
    using Drop=void*(__fastcall*)(void*,uint32_t);
    using Create=uint32_t*(__fastcall*)(void*,uint32_t*,void*);
    using Add=void(__fastcall*)(void*,const uint32_t*,int32_t,int32_t,int32_t);
    using Destroy=void(__fastcall*)(void*,const uint32_t*,int32_t,int32_t);
    using Ray=uint32_t*(__fastcall*)(void*,uint32_t*,uint32_t,const Vector*,const Vector*,Vector*,Vector*,uint32_t*);
    struct Calls { Init configInit=nullptr,cinfoInit=nullptr;Ref configDrop=nullptr,addRef=nullptr,release=nullptr;
        Factory factory=nullptr;Drop cinfoDrop=nullptr;Create create=nullptr;Add add=nullptr;Destroy destroy=nullptr;Ray ray=nullptr; };
private:
    uintptr_t base_=0;size_t imageSize_=0;bool enabled_=false;Calls calls_;
    template<class T>static bool read(uintptr_t p,size_t offset,T& out){
        return p>=0x10000&&p<=UINTPTR_MAX-offset-sizeof(T)&&mem_read((void*)(p+offset),&out,sizeof(out));
    }
    static bool pointer(uintptr_t p){return p>=0x10000&&!(p&7);}
    bool typed(uintptr_t p,uintptr_t vt)const{uintptr_t actual=0;return pointer(p)&&read(p,0,actual)&&actual==base_+vt;}
    bool shapeValid(uintptr_t shape)const{
        uintptr_t vt=0;return pointer(shape)&&!(shape&15)&&read(shape,0,vt)&&vt>=base_&&vt-base_<imageSize_;
    }
public:
    bool init(uintptr_t base,size_t imageSize,bool exactBuildVerified){
        base_=base;imageSize_=imageSize;enabled_=false;
        if(!exactBuildVerified||!base||imageSize<0x3D7E548)return false;
        for(const auto& c:kChecks){uint8_t actual[32];if(c.rva+c.size>imageSize||!mem_read((void*)(base+c.rva),actual,c.size)||memcmp(actual,c.bytes,c.size))return false;}
        calls_={(Init)(base+0x1882BB0),(Init)(base+0x1913080),(Ref)(base+0x1682CA0),(Ref)(base+0x1682CD0),(Ref)(base+0x1682E10),
            (Factory)(base+0x18836F0),(Drop)(base+0x18AAC30),(Create)(base+0x18ACA60),(Add)(base+0x18ABE60),
            (Destroy)(base+0x18AE410),(Ray)(base+0xC72060)};
        enabled_=true;return true;
    }
#ifdef ERMC_COLLIDER_FIXTURE
    void fixtureCalls(const Calls& calls){calls_=calls;}
#endif
    bool enabled()const{return enabled_;}
    bool offline(bool& warping)const{
        uintptr_t game=0,session=0,again=0;uint8_t online=1,warp=1,onlineAgain=1,warpAgain=1;uint32_t lobby=1,protocol=1,repeat[2]={};
        if(!enabled_||!read(base_,0x3D6D988,game)||!typed(game,0x2A82110)||
            !read(base_,0x3D7E540,session)||!typed(session,0x2B9D1B8)||
            !read(game,0xBC8,online)||online!=0||!read(game,0x10,warp)||warp>1||
            !read(session,0xC,lobby)||lobby!=0||!read(session,0x10,protocol)||protocol!=0||
            !read(base_,0x3D6D988,again)||again!=game||!read(base_,0x3D7E540,again)||again!=session||
            !read(game,0xBC8,onlineAgain)||onlineAgain!=online||!read(game,0x10,warpAgain)||warpAgain!=warp||
            !read(session,0xC,repeat)||repeat[0]!=lobby||repeat[1]!=protocol)return false;
        warping=warp!=0;return true;
    }
    bool world(World& out)const{
        uintptr_t man=0,again=0;World w;
        if(!enabled_||!read(base_,0x3D7A0D0,man)||!typed(man,0x2B934E8)||
            !read(man,0x98,w.cs)||!typed(w.cs,0x2B96978)||!read(w.cs,8,w.hk)||!typed(w.hk,0x2EF0DD8)||
            !read(base_,0x3D7A0D0,again)||again!=man||!read(man,0x98,again)||again!=w.cs)return false;
        out=w;return true;
    }
    bool current(World expected)const{World w;return world(w)&&w==expected;}
    // The exact-build task table schedules 99 then 113 then 117. All collider
    // mutations run in lane 0's exclusive group 117, after 99's Havok post task.
    // C4FB15 installs C51040 for HavokMan+138; C51247 registers it as group 99.
    // Its C51F50 path waits/finalizes the graph and clears +A8 at C52023; the
    // pre task stores that outstanding graph at C524BC. Refuse writes while it
    // remains non-null even if the caller is accidentally scheduled too early.
    bool postPhysicsReady(World expected)const{
        uintptr_t man=0,cs=0,pending=1,again=1;
        return current(expected)&&read(base_,0x3D7A0D0,man)&&typed(man,0x2B934E8)&&
            read(man,0x98,cs)&&cs==expected.cs&&read(man,0xA8,pending)&&pending==0&&
            read(man,0xA8,again)&&again==0;
    }
    SlotState body(World w,uint32_t id,uintptr_t& ptr)const{
        uint32_t count=0,actual=0,flags=0;uintptr_t table=0;
        uint32_t index=id&INVALID_BODY;
        if(index==INVALID_BODY||!current(w)||!read(w.hk,0x30,count)||count==0||count>1000000||
            !read(w.hk,0x28,table)||!pointer(table)||(table&15))return SlotState::Invalid;
        if(index>=count)return SlotState::Missing;
        if(table>UINTPTR_MAX-size_t(index)*BODY_STRIDE-0xB0)return SlotState::Invalid;
        ptr=table+size_t(index)*BODY_STRIDE;
        if(!read(ptr,0x70,actual)||!read(ptr,0x44,flags))return SlotState::Invalid;
        return actual==id&&(flags&3)?SlotState::Present:SlotState::Missing;
    }
    SlotState owned(World w,const Owned& o,uintptr_t& ptr)const{
        auto state=body(w,o.id,ptr);if(state!=SlotState::Present)return state;
        uintptr_t shape=0,user=0;if(!read(ptr,0x60,shape)||!read(ptr,0xA0,user))return SlotState::Invalid;
        if(shape!=o.shape)return SlotState::Missing;
        return user==0?SlotState::Present:SlotState::Invalid;
    }
    bool broadphase(World w,const Owned& o,bool& present)const{
        uintptr_t p=0;uint32_t handle=UINT32_MAX;if(owned(w,o,p)!=SlotState::Present||!read(p,0x78,handle))return false;
        present=handle!=UINT32_MAX;return true;
    }
    bool characterFilter(World w,uintptr_t player,uint32_t& filter)const{
        uintptr_t modules=0,physics=0,owner=0;
        if(!typed(player,0x2A7FBB0)||!read(player,0x190,modules)||!read(modules,0x68,physics)||
            !typed(physics,0x2A3C890)||!read(physics,8,owner)||owner!=player)return false;
        for(size_t at:{size_t(0x98),size_t(0xA0)}){
            uintptr_t wrapper=0,proxy=0,shape=0,wrapperShape=0,hk=0,bodyPtr=0,bodyShape=0;
            uint32_t id=INVALID_BODY,value=0,bodyFilter=0;
            if(!read(physics,at,wrapper)||!pointer(wrapper)||!read(wrapper,0x88,proxy)||!typed(proxy,0x2B95260)||
                !read(proxy,0x40,hk)||hk!=w.hk||!read(proxy,0x28,shape)||!pointer(shape)||(shape&15)||
                !read(wrapper,0x80,wrapperShape)||shape!=wrapperShape||!read(proxy,0x3C,value)||!read(proxy,0x38,id))continue;
            if((id&INVALID_BODY)!=INVALID_BODY&&(body(w,id,bodyPtr)!=SlotState::Present||
                !read(bodyPtr,0x60,bodyShape)||bodyShape!=shape||!read(bodyPtr,0x6C,bodyFilter)||bodyFilter!=value))continue;
            filter=value;return true;
        }
        return false;
    }
    bool pairAllowed(World w,uint32_t a,uint32_t b)const{
        uintptr_t wrapper=0,owner=0,child=0;uint64_t word=0;
        return read(w.hk,0x4D0,wrapper)&&typed(wrapper,0x2EF22F8)&&read(wrapper,0x38,owner)&&owner==w.hk&&
            read(wrapper,0x30,child)&&typed(child,0x2B94DF0)&&
            read(child,0x20+(((a&127)*2)+((b&127)>>6))*8,word)&&layerPairAllowed(a,b,word);
    }
    bool ray(World w,const double* origin,const double* delta,uint32_t filter,Hit& out)const{
        out={};if(!finite3(origin)||!finite3(delta)||!current(w))return false;
        Vector o={},d={},point={{NAN,NAN,NAN,NAN}},normal=point;double length2=0;
        for(int i=0;i<3;++i){o.v[i]=float(origin[i]);d.v[i]=float(delta[i]);length2+=double(d.v[i])*d.v[i];}
        if(length2<1e-12)return false;
        uint32_t id=INVALID_BODY,key=UINT32_MAX;
        calls_.ray((void*)w.cs,&id,filter,&o,&d,&point,&normal,&key);
        if((id&INVALID_BODY)==INVALID_BODY)return true;
        uintptr_t bodyPtr=0;if(body(w,id,bodyPtr)!=SlotState::Present)return false;
        double dot=0,off=0,norm=0,len=std::sqrt(length2);
        for(int i=0;i<3;++i){if(!std::isfinite(point.v[i])||!std::isfinite(normal.v[i]))return false;dot+=(point.v[i]-o.v[i])*d.v[i]/len;norm+=double(normal.v[i])*normal.v[i];}
        for(int i=0;i<3;++i){double v=point.v[i]-o.v[i]-dot*d.v[i]/len;off+=v*v;}
        if(dot<-.02||dot>len+.02||off>.0004||norm<.25||norm>2.25)return false;
        out.hit=true;out.body=id;memcpy(out.point,point.v,12);memcpy(out.normal,normal.v,12);return true;
    }
    bool groundTemplate(World w,const Hit& hit,uint32_t character,Template& out)const{
        uintptr_t p=0,shape=0;uint32_t motion=1,flags=0,filter=0;uint16_t material=UINT16_MAX;
        if(!hit.hit||hit.normal[1]<.5f||body(w,hit.body,p)!=SlotState::Present||!read(p,0x40,motion)||motion!=0||
            !read(p,0x44,flags)||(flags&3)!=1||!read(p,0x6A,material)||material==UINT16_MAX||
            !read(p,0x6C,filter)||!pairAllowed(w,character,filter)||!read(p,0x60,shape)||!pointer(shape))return false;
        out={hit.body,filter,character,material,shape};return true;
    }
    bool templateCurrent(World w,const Template& t,uint32_t character)const{
        uintptr_t p=0,shape=0;uint32_t filter=0,motion=1,flags=0;uint16_t material=UINT16_MAX;
        return body(w,t.body,p)==SlotState::Present&&read(p,0x60,shape)&&shape==t.shape&&read(p,0x6C,filter)&&filter==t.filter&&
            read(p,0x6A,material)&&material==t.material&&read(p,0x40,motion)&&motion==0&&read(p,0x44,flags)&&(flags&3)==1&&pairAllowed(w,character,filter);
    }
    bool createBox(World w,const Template& t,const Box& bounds,const double* offset,Owned& out)const{
        uint8_t shapeRefs=0;Vector center;std::array<Vector,8> vectors;
        if(!read(base_,0x3C19CC0,shapeRefs)||shapeRefs!=1||!geometry(bounds,offset,center,vectors)||!current(w))return false;
        Vertices points{vectors.data(),8,16};Native<0x50> config;
        calls_.configInit(config.bytes);uintptr_t shape=(uintptr_t)calls_.factory(&points,0.f,config.bytes);
        calls_.configDrop(config.bytes+0x18);
        if(!shape)return false;
        // An unexpected allocator result is isolated, never passed to an unproven destructor.
        if(!shapeValid(shape))return false;
        Native<0xB0> cinfo;calls_.cinfoInit(cinfo.bytes);calls_.addRef((void*)shape);
        cinfo.put(0,shape);cinfo.put(0x10,t.filter);cinfo.put(0x14,t.material);cinfo.put(0x30,center);
        uint32_t id=INVALID_BODY;calls_.create((void*)w.hk,&id,cinfo.bytes);calls_.cinfoDrop(cinfo.bytes,0);
        if((id&INVALID_BODY)==INVALID_BODY){calls_.release((void*)shape);return false;}
        out={id,shape,bounds,false};return true;
    }
    bool add(World w,const Owned& o)const{
        uintptr_t p=0;if(owned(w,o,p)!=SlotState::Present)return false;
        calls_.add((void*)w.hk,&o.id,1,1,0);return true;
    }
    Removal remove(World w,Owned& o)const{
        if(!o.shape)return Removal::Removed; // Independent shape reference was already retired.
        uintptr_t p=0;auto state=owned(w,o,p);
        if(state==SlotState::Invalid||!shapeValid(o.shape))return Removal::Error;
        if(state==SlotState::Present&&!o.destroySubmitted){calls_.destroy((void*)w.hk,&o.id,1,0);o.destroySubmitted=true;state=owned(w,o,p);}
        if(state==SlotState::Invalid)return Removal::Error;
        if(state==SlotState::Present)return Removal::Pending;
        calls_.release((void*)o.shape);o.shape=0;return Removal::Removed;
    }
};
} // namespace mb::native_colliders
