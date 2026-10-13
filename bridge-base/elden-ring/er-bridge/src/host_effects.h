#pragma once
#include "common.h"
#include "host_effect_state.h"
#include <string.h>

namespace mb {

// Read-only optional capability for eldenring.exe 2.7.1.0 / App 1.17.1.
// Layout reference: vswarte/fromsoftware-rs@59fbd3b3b7daaf14aca47c9f73530493dba6bc79,
// cs/chr_ins.rs and cs/sp_effect.rs. Target code and live RTTI/owner were also checked;
// evidence: runtime/experiments/poison-research/effect-field-code-verification.json.
// ividyon/EldenRingHKS@8710f97408dd57bf1d6ef919f619cb2937919c39/c9997.hks
// defines GetSpEffectType=362 and SP_EFFECT_TYPE_POISON=2. The target's same query
// reads linked entries' param stateInfo, independently of the HP-loss channel.
class HostEffectsReader {
    uintptr_t base_ = 0;
    bool enabled_ = false;

    static bool signature(uintptr_t address, const uint8_t* expected, size_t size) {
        uint8_t bytes[16];
        return size <= sizeof(bytes) && mem_read((const void*)address, bytes, size) &&
            memcmp(bytes, expected, size) == 0;
    }

public:
    bool init(uintptr_t base) {
        base_ = base;
        // HKS GetSpEffectType dispatcher, then SpecialEffect::has_state_info.
        static const uint8_t playerEffect[] = {0x48, 0x8B, 0x89, 0x78, 0x01, 0x00, 0x00};
        static const uint8_t head[] = {0x49, 0x8B, 0x43, 0x08};
        static const uint8_t param[] = {0x48, 0x8B, 0x08};
        static const uint8_t stateInfo[] = {0x0F, 0xB7, 0x91, 0x56, 0x01, 0x00, 0x00};
        static const uint8_t next[] = {0x48, 0x8B, 0x40, 0x30};
        static const uint8_t effectId[] = {0x39, 0x48, 0x08, 0x74, 0x11, 0x48, 0x8B,
            0x40, 0x30, 0x48, 0x85, 0xC0, 0x75, 0xF2};
        enabled_ = signature(base + 0x41176C, playerEffect, sizeof(playerEffect)) &&
            signature(base + 0x4FA432, head, sizeof(head)) &&
            signature(base + 0x4FA440, param, sizeof(param)) &&
            signature(base + 0x4FA448, stateInfo, sizeof(stateInfo)) &&
            signature(base + 0x4FA459, next, sizeof(next)) &&
            signature(base + 0x4FAD06, effectId, sizeof(effectId));
        return enabled_;
    }

    HostEffectsSnapshot read(const void* player) const {
        HostEffectsSnapshot result;
        if (!enabled_ || !player) return result;
        uintptr_t playerVtable = 0, effect = 0;
        if (!mem_read(player, &playerVtable, sizeof(playerVtable)) ||
            playerVtable != base_ + 0x2A7FBB0 ||
            !mem_read((const uint8_t*)player + 0x178, &effect, sizeof(effect)) || !effect)
            return result;
        struct List { uintptr_t vtable, head, owner; } list;
        if (!mem_read((const void*)effect, &list, sizeof(list)) ||
            list.vtable != base_ + 0x2A4D490 || list.owner != (uintptr_t)player)
            return result;

        uintptr_t seen[256];
        size_t count = 0;
        HostEffectsSnapshot observed;
        for (uintptr_t current = list.head; current;) {
            if (count == 256) return result; // Incomplete traversal is unknown, never clean.
            for (size_t i = 0; i < count; i++) if (seen[i] == current) return result;
            seen[count++] = current;
            struct Entry {
                uintptr_t param;
                int32_t id;
                uint8_t unused[36];
                uintptr_t next;
            } entry;
            static_assert(sizeof(Entry) == 0x38, "SpecialEffect entry prefix");
            static_assert(offsetof(Entry, id) == 8 && offsetof(Entry, next) == 0x30,
                          "SpecialEffect entry offsets");
            if (!mem_read((const void*)current, &entry, sizeof(entry)) ||
                !entry.param || entry.param > UINTPTR_MAX - 0x158 || entry.id < 0)
                return result;
            uint16_t stateInfo = 0;
            if (!mem_read((const void*)(entry.param + 0x156), &stateInfo, sizeof(stateInfo)))
                return result;
            observed.observe(stateInfo, entry.id);
            current = entry.next;
        }
        observed.valid = true;
        return observed;
    }
};

inline void publish_host_effects(const HostEffectsSnapshot& value, bool mcControl,
                                 uint64_t sampleFrame, uint32_t hostLife) {
    ErmcHostEffects* out = shm_host_effects();
    static HostEffectEpisodes episodes;
    const uint32_t frostProcs = episodes.observe(value, hostLife);
    const uint32_t writing = out->seq | 1u;
    out->seq = writing;
    __asm__ __volatile__("" ::: "memory");
    out->magic = ERMC_HOST_EFFECTS_MAGIC;
    out->version = ERMC_HOST_EFFECTS_VERSION;
    out->flags = (value.valid ? ERMC_HOST_EFFECTS_VALID : 0) |
        (mcControl ? ERMC_HOST_EFFECTS_MC_CONTROL : 0) |
        (value.valid && value.poisonEffectId >= 0 ? ERMC_HOST_EFFECTS_POISON : 0) |
        (value.valid && value.rotEffectId >= 0 ? ERMC_HOST_EFFECTS_ROT : 0) |
        (value.valid && value.frostEffectId >= 0 ? ERMC_HOST_EFFECTS_FROST : 0) |
        (value.valid && value.sleepEffectId >= 0 ? ERMC_HOST_EFFECTS_SLEEP : 0) |
        (value.valid && value.madnessEffectId >= 0 ? ERMC_HOST_EFFECTS_MADNESS : 0);
    out->sampleFrame = sampleFrame;
    out->hostLife = hostLife;
    out->poisonEffectId = value.valid ? value.poisonEffectId : -1;
    out->rotEffectId = value.valid ? value.rotEffectId : -1;
    out->frostEffectId = value.valid ? value.frostEffectId : -1;
    out->sleepEffectId = value.valid ? value.sleepEffectId : -1;
    out->madnessEffectId = value.valid ? value.madnessEffectId : -1;
    out->frostProcs = frostProcs;
    out->reserved = 0;
    __asm__ __volatile__("" ::: "memory");
    out->seq = writing + 1u;
}

} // namespace mb
