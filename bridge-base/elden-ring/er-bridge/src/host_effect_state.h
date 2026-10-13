#pragma once
#include "bridge_protocol.h"

namespace mb {

// Semantic classifications from HKS/Grand Archives stateInfo definitions, not guessed effect IDs.
// Runtime chain/layout validation remains in HostEffectsReader. No HP deltas identify an effect.
struct HostEffectsSnapshot {
    bool valid = false;
    int32_t poisonEffectId = -1, rotEffectId = -1, frostEffectId = -1;
    int32_t sleepEffectId = -1, madnessEffectId = -1;

    void observe(uint16_t stateInfo, int32_t effectId) {
        int32_t* slot = nullptr;
        switch (stateInfo) {
            case 2: slot = &poisonEffectId; break;
            case 5: slot = &rotEffectId; break;
            case 260: slot = &frostEffectId; break;
            case 436: slot = &sleepEffectId; break;
            case 437: slot = &madnessEffectId; break;
            default: return;
        }
        if (effectId >= 0 && (*slot < 0 || effectId < *slot)) *slot = effectId;
    }
};

// A snapshot can describe a persistent debuff; it cannot reliably report a one-frame bleed proc.
// Only a continuously observed absent -> present frost edge counts. Loading/unknown/first
// observation establishes a baseline instead of replaying a burst that may have happened earlier.
class HostEffectEpisodes {
    bool known_ = false, frost_ = false;
    uint32_t life_ = 0, frostProcs_ = 0;
public:
    uint32_t observe(const HostEffectsSnapshot& value, uint32_t hostLife) {
        if (!value.valid) { known_ = false; return frostProcs_; }
        const bool frost = value.frostEffectId >= 0;
        if (known_ && life_ == hostLife && !frost_ && frost) ++frostProcs_;
        known_ = true;
        frost_ = frost;
        life_ = hostLife;
        return frostProcs_;
    }
};

} // namespace mb
