#pragma once
#include "bridge_protocol.h"
#include <cmath>
#include <cstring>

namespace mb {
// Pure validation shared with the disk/fixture tool. No game calls or allocator.
struct BlockColliderWire {
    ErmcBlockColliders snapshot{};
    enum Error : uint32_t { OK=0, DISABLED=1, INCOMPLETE=2, STALE=3, SESSION=4, MALFORMED=5, BUSY=6 };
    Error error=DISABLED;
    static bool identity(const ErmcBlockColliders& s,const ErmcHeader& h,uint32_t zone) {
        return s.hostPid && s.hostPid==h.hostPid && s.hostStartMs==h.hostStartMs &&
            s.coreGeneration==h.coreGeneration && s.hostLife==h.hostLife && s.hostLife && s.zone==zone && zone &&
            s.mcPid && s.mcPid==h.mcPid && s.mcStartMs && s.mcStartMs==h.mcStartMs;
    }
    bool validate(const ErmcHeader& h,uint32_t zone,uint64_t now) {
        const auto& s=snapshot;
        if(s.magic!=ERMC_BLOCK_COLLIDER_MAGIC || s.version!=ERMC_BLOCK_COLLIDER_VERSION ||
            s.flags&~7u || s.count>ERMC_MAX_BLOCK_COLLIDERS || s.reserved) {error=MALFORMED;return false;}
        if(!(s.flags&ERMC_BLOCK_COLLIDER_ENABLED)) {error=DISABLED;return false;}
        if(!(s.flags&ERMC_BLOCK_COLLIDER_COMPLETE)) {error=INCOMPLETE;return false;}
        if(!identity(s,h,zone)) {error=SESSION;return false;}
        if(!s.sampledAt || s.sampledAt>now || now-s.sampledAt>750) {error=STALE;return false;}
        if(!s.worldEpoch || !s.revision) {error=MALFORMED;return false;}
        for(float c:s.center) if(!std::isfinite(c)||std::fabs(c)>1000000) {error=MALFORMED;return false;}
        for(uint32_t i=0;i<s.count;i++) for(int a=0;a<3;a++) {
            const auto& b=s.boxes[i];
            if(!std::isfinite(b.min[a])||!std::isfinite(b.max[a])||b.max[a]-b.min[a]<.002f||b.max[a]-b.min[a]>64||
                std::fabs(b.min[a]-s.center[a])>64||std::fabs(b.max[a]-s.center[a])>64) {error=MALFORMED;return false;}
        }
        error=OK;return true;
    }
    template<class Fence> bool read(const ErmcBlockColliders* source,const ErmcHeader& h,uint32_t zone,uint64_t now,Fence fence) {
        for(int attempt=0;attempt<3;attempt++) {
            uint32_t before=source->seq;if(before&1u)continue;
            fence();std::memcpy(&snapshot,source,sizeof(snapshot));fence();
            if(before!=source->seq)continue;
            return validate(h,zone,now);
        }
        error=BUSY;return false;
    }
};
}
