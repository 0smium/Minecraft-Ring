// Adapted from SocketByte/EldenCraft native_colliders.rs at ba7591cdde9fb16e17552cfe53546f44d0fd856e.
// Copyright (c) 2026 Adam Grzegorzewski. MIT license: ../third_party/eldencraft/LICENSE.
#pragma once
#include "native_colliders_api.h"
#include <algorithm>
#include <chrono>
#include <map>
#include <vector>

namespace mb::native_colliders {
using Key=std::array<uint64_t,6>;
inline Key key(const Box& b){
    Key k;for(int i=0;i<6;++i){double value=i<3?b.min[i]:b.max[i-3];if(value==0)value=0.;memcpy(&k[i],&value,8);}return k;
}
inline double distance(const double* point,const Box& b){
    double d=0;for(int i=0;i<3;++i){double a=std::max({b.min[i]-point[i],point[i]-b.max[i],0.});d+=a*a;}return d;
}
inline bool intersects(const Box& b,const double* a,const double* z){
    double low=0,high=1;
    for(int i=0;i<3;++i){
        double d=z[i]-a[i];if(std::abs(d)<1e-12){if(a[i]<b.min[i]-.001||a[i]>b.max[i]+.001)return false;continue;}
        double first=(b.min[i]-.001-a[i])/d,last=(b.max[i]+.001-a[i])/d;
        if(first>last)std::swap(first,last);low=std::max(low,first);high=std::min(high,last);if(low>high)return false;
    }
    return true;
}
struct Status {
    size_t desired=0,owned=0,broadphase=0,pending=0,quarantined=0,quarantinedBodies=0;
    size_t created=0,removed=0,abandoned=0;
    bool complete=false,offline=false;
    const char* error="disabled";
};
class Driver {
    using Bodies=std::map<Key,Owned>;
    struct Retired { World world;Bodies bodies;double offset[3]; };
    static constexpr size_t CREATE_BUDGET=96,REMOVE_BUDGET=128,MAX_RETIRED=8;
    Api api_;World world_;bool hasWorld_=false,hasTemplate_=false,identityChanging_=false,shutdown_=false;Template template_;
    Bodies bodies_;std::vector<Retired> retired_;
    uint64_t epoch_=0;uint32_t life_=0,zone_=0,thread_=0;
    double offset_[3]={};const char* failure_=nullptr;
    Status status_;
    void counts(){
        status_.owned=bodies_.size();status_.quarantined=retired_.size();status_.quarantinedBodies=0;
        for(const auto& r:retired_)status_.quarantinedBodies+=r.bodies.size();
    }
    bool cleanup(World w,Bodies& bodies,size_t& budget){
        for(auto it=bodies.begin();it!=bodies.end()&&budget;){
            --budget;auto outcome=api_.remove(w,it->second);
            if(outcome==Removal::Error){status_.error="owned body cleanup identity unavailable";return false;}
            if(outcome==Removal::Pending){status_.error="native destruction pending";++it;continue;}
            it=bodies.erase(it);++status_.removed;
        }
        if(!bodies.empty()){status_.error="cleanup budget pending";return false;}return true;
    }
    bool bind(World w,size_t& budget){
        if(hasWorld_&&world_!=w){
            if(!bodies_.empty()){
                if(retired_.size()>=MAX_RETIRED){status_.error="retired world quarantine full";return false;}
                Retired r{world_,std::move(bodies_),{offset_[0],offset_[1],offset_[2]}};
                retired_.push_back(std::move(r));bodies_.clear();
            }
            hasTemplate_=false;epoch_=0;failure_=nullptr;
        }
        world_=w;hasWorld_=true;
        for(auto it=retired_.begin();it!=retired_.end();){
            if(it->world!=w){++it;continue;}
            if(!cleanup(w,it->bodies,budget))return false;
            it=retired_.erase(it);
        }
        return true;
    }
    bool findTemplate(World w,const Context& c){
        uint32_t character=0;if(!api_.characterFilter(w,c.player,character)){status_.error="local character filter unavailable";return false;}
        if(hasTemplate_&&api_.templateCurrent(w,template_,character))return true;
        hasTemplate_=false;
        static constexpr double offsets[5][2]={{0,0},{.75,0},{-.75,0},{0,.75},{0,-.75}};
        for(const auto& p:offsets){
            double origin[3]={c.feetHavok[0]+p[0],c.feetHavok[1]+.5,c.feetHavok[2]+p[1]},delta[3]={0,-5,0};Hit hit;
            if(!api_.ray(w,origin,delta,character,hit)){status_.error="ground template ray invalid";return false;}
            if(hit.hit&&!ownsBody(w,hit.body)&&api_.groundTemplate(w,hit,character,template_)){hasTemplate_=true;return true;}
        }
        status_.error="need static ground accepted by character filter";return false;
    }
    bool measure(World w){
        status_.broadphase=0;
        for(const auto& pair:bodies_){bool present=false;
            if(!api_.broadphase(w,pair.second,present)){status_.error="owned body identity changed";failure_=status_.error;return false;}
            if(present)++status_.broadphase;
        }
        return true;
    }
public:
    bool init(uintptr_t base,size_t imageSize,bool exactBuildVerified){
        // Never lose ownership by reinitializing a live driver.
        if(shutdown_||hasWorld_||!bodies_.empty()||!retired_.empty())return false;
        bool ok=api_.init(base,imageSize,exactBuildVerified);status_.error=ok?"disabled":"unsupported executable";return ok;
    }
#ifdef ERMC_COLLIDER_FIXTURE
    void fixtureCalls(const Api::Calls& calls){api_.fixtureCalls(calls);}
#endif
    const Status& status()const{return status_;}
    const Api& api()const{return api_;}
    bool ownsBody(World w,uint32_t id)const{
        if(hasWorld_&&world_==w)for(const auto& pair:bodies_)if(pair.second.id==id)return true;
        for(const auto& r:retired_)if(r.world==w)for(const auto& pair:r.bodies)if(pair.second.id==id)return true;
        return false;
    }
    // Only geometric reads; an occluded native ray is UNKNOWN, never proof of
    // air. The caller must keep prior terrain and never publish a clear cell.
    bool occludesTerrainRay(const double* start,const double* end)const{
        if(!finite3(start)||!finite3(end))return true;
        World current;if(!api_.world(current))return !bodies_.empty()||!retired_.empty();
        if(hasWorld_&&world_==current){
            if(identityChanging_&&!bodies_.empty())return true;
            for(const auto& pair:bodies_)if(intersects(pair.second.bounds,start,end))return true;
        }
        // A returning retired world may have a different stable coordinate frame.
        // Until cleanup confirms retirement, no native ray can prove empty air.
        for(const auto& r:retired_)if(r.world==current&&!r.bodies.empty())return true;
        return false;
    }
    bool occludesTerrainRay(const float* start,const float* end)const{
        double a[3]={start[0],start[1],start[2]},b[3]={end[0],end[1],end[2]};return occludesTerrainRay(a,b);
    }
    // One caller in the game's post-physics task, after Havok jobs have joined. Fresh
    // complete box snapshots only; a closed/paused guest sets hostReady=false.
    const Status& tick(const Context& c,const Box* boxes,size_t count){
        status_.created=status_.removed=status_.broadphase=0;status_.complete=false;status_.desired=count;status_.pending=0;status_.offline=false;
        if(shutdown_){status_.error="driver abandoned until process exit";return status_;}
        if(!api_.enabled()){status_.error="unsupported executable";counts();return status_;}
        uint32_t currentThread=GetCurrentThreadId();
        if(!c.taskThreadId||currentThread!=c.taskThreadId||(thread_&&thread_!=currentThread)){
            status_.error="not the post-physics task thread";counts();return status_;}
        thread_=currentThread;
        // Even a suspended/lost-offline frame may change the coordinate identity.
        // Keep rays UNKNOWN while any old bodies remain and cannot be reconciled.
        if(epoch_!=c.epoch||life_!=c.hostLife||zone_!=c.zone||!std::equal(offset_,offset_+3,c.havokOffset))identityChanging_=true;
        bool warp=false;
        if(!c.offline||!api_.offline(warp)){status_.error="offline session unavailable; cleanup deferred";counts();return status_;}
        status_.offline=true;
        World w;if(!api_.world(w)){status_.error="Havok world unavailable; ownership retained";counts();return status_;}
        if(!api_.postPhysicsReady(w)){status_.error="Havok task graph outstanding; cleanup deferred";counts();return status_;}
        size_t removeBudget=REMOVE_BUDGET;
        if(!bind(w,removeBudget)){counts();return status_;}
        bool active=c.enabled&&c.hostReady&&!warp&&c.epoch&&finite3(c.havokOffset)&&finite3(c.feetHavok);
        if(!active){
            if(cleanup(w,bodies_,removeBudget)){epoch_=0;hasTemplate_=false;failure_=nullptr;status_.error="suspended";}
            status_.desired=0;status_.pending=bodies_.size();counts();return status_;
        }
        if(count>MAX_BODIES||(count&&!boxes)){status_.error="invalid complete box snapshot";counts();return status_;}
        for(size_t i=0;i<count;++i)if(!validBox(boxes[i])){status_.error="invalid box geometry";counts();return status_;}
        if(epoch_!=c.epoch||life_!=c.hostLife||zone_!=c.zone||!std::equal(offset_,offset_+3,c.havokOffset)){
            identityChanging_=true;
            if(!cleanup(w,bodies_,removeBudget)){counts();return status_;}
            epoch_=c.epoch;life_=c.hostLife;zone_=c.zone;std::copy(c.havokOffset,c.havokOffset+3,offset_);hasTemplate_=false;failure_=nullptr;
        }
        identityChanging_=false;
        // An earlier remove may be queued by Havok. Do not reuse its geometry
        // key, count it as complete, or send destruction twice while awaiting it.
        bool retiring=false;
        for(auto it=bodies_.begin();it!=bodies_.end();){
            if(!it->second.destroySubmitted){++it;continue;}
            if(!removeBudget){retiring=true;break;}--removeBudget;
            auto outcome=api_.remove(w,it->second);
            if(outcome==Removal::Removed){it=bodies_.erase(it);++status_.removed;}
            else{retiring=true;++it;}
        }
        if(retiring){status_.error="prior destruction pending";status_.pending=bodies_.size();counts();return status_;}
        if(failure_){status_.error=failure_;counts();return status_;}
        std::map<Key,Box> desired;for(size_t i=0;i<count;++i)desired.emplace(key(boxes[i]),boxes[i]);
        status_.desired=desired.size();
        double feet[3];for(int i=0;i<3;++i)feet[i]=c.feetHavok[i]-c.havokOffset[i];
        std::vector<Box> missing;
        for(const auto& pair:desired)if(!bodies_.count(pair.first))missing.push_back(pair.second);
        std::sort(missing.begin(),missing.end(),[&](const Box& a,const Box& b){return distance(feet,a)<distance(feet,b);});
        auto started=std::chrono::steady_clock::now();
        if(!missing.empty()){
            if(!findTemplate(w,c)){counts();return status_;}
            for(const auto& box:missing){
                if(status_.created>=CREATE_BUDGET||bodies_.size()>=MAX_BODIES||std::chrono::steady_clock::now()-started>=std::chrono::microseconds(2000))break;
                Owned own;
                if(!api_.createBox(w,template_,box,offset_,own)){status_.error=failure_="native box construction failed; no automatic retry";counts();return status_;}
                // Track before any readback or insertion check, so an uncertain
                // native result cannot trigger repeated orphan allocations.
                bodies_.emplace(key(box),own);++status_.created;
                if(!api_.add(w,own)){status_.error=failure_="created body ownership failed; retained";counts();return status_;}
            }
        }
        size_t stillMissing=0;for(const auto& pair:desired)if(!bodies_.count(pair.first))++stillMissing;
        // Replacements must actually enter the broadphase before removing an
        // old floor, except when space must be made at the hard body limit.
        bool replacementsReady=true;
        for(const auto& pair:desired){auto found=bodies_.find(pair.first);bool broad=false;
            if(found==bodies_.end()||!api_.broadphase(w,found->second,broad)||!broad){replacementsReady=false;break;}}
        if(replacementsReady||bodies_.size()+stillMissing>MAX_BODIES){
            std::vector<Key> stale;for(const auto& pair:bodies_)if(!desired.count(pair.first))stale.push_back(pair.first);
            std::sort(stale.begin(),stale.end(),[&](const Key& a,const Key& b){return distance(feet,bodies_.at(a).bounds)>distance(feet,bodies_.at(b).bounds);});
            for(const auto& k:stale){if(!removeBudget)break;--removeBudget;auto found=bodies_.find(k);auto result=api_.remove(w,found->second);
                if(result==Removal::Error){status_.error="stale body cleanup unavailable";counts();return status_;}
                if(result==Removal::Removed){bodies_.erase(found);++status_.removed;}}
        }
        counts();if(!measure(w))return status_;
        status_.pending=stillMissing+(bodies_.size()-status_.broadphase);
        for(const auto& pair:bodies_)if(!desired.count(pair.first))++status_.pending;
        status_.complete=status_.pending==0&&status_.broadphase==desired.size();
        status_.error=status_.complete?nullptr:"native broadphase/update pending";return status_;
    }
    // For process shutdown/disabled reload only. Does not invoke Havok, touch an
    // old world, or release native references on a worker thread. Quarantined
    // references deliberately remain bounded until the process exits.
    Status abandon(){
        size_t n=bodies_.size();for(const auto& r:retired_)n+=r.bodies.size();
        bodies_.clear();retired_.clear();hasWorld_=false;hasTemplate_=false;epoch_=0;shutdown_=true;
        status_={};status_.abandoned=n;status_.error=n?"native ownership abandoned until process exit":"shutdown";return status_;
    }
};
} // namespace mb::native_colliders
