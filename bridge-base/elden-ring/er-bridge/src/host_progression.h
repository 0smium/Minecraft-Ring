#pragma once
#include "common.h"
#include <string.h>

namespace mb {

// Optional read-only reader for eldenring.exe 2.7.1.0 / App 1.17.1.
// Reference: vswarte/fromsoftware-rs@59fbd3b3b7daaf14aca47c9f73530493dba6bc79,
// cs/chr_ins.rs PlayerIns and cs/player_game_data.rs PlayerGameData.
// Target disk evidence: runtime/experiments/progression-research/target-progression-fields.json.
// Runtime validation remains required; no new process access or writes to ER.
struct HostProgressionSnapshot {
    bool valid = false;
    uint32_t level = 0;
    uint64_t fingerprint = 0;
    uint16_t name[17] = {};
    uint16_t nameUnits = 0;
};

class HostProgressionReader {
    uintptr_t base_ = 0;
    bool enabled_ = false;
    static bool signature(uintptr_t address, const uint8_t* expected, size_t size) {
        uint8_t bytes[32];
        return size <= sizeof(bytes) && mem_read((void*)address, bytes, size) &&
            memcmp(bytes, expected, size) == 0;
    }

public:
    bool init(uintptr_t base) {
        base_ = base;
        static const uint8_t gameData[] = {0x48,0x8B,0x81,0x80,0x05,0,0,0xC3};
        static const uint8_t level[] = {0x48,0x85,0xC9,0x74,0x04,0x8B,0x41,0x68,0xC3,0x33,0xC0,0xC3};
        static const uint8_t levelCaller[] = {0x48,0x8B,0x0F,0x48,0x8B,0x01,0xFF,0x90,0x68,0x01,
            0,0,0x48,0x8B,0xC8,0xE8,0x48,0x61,0x2C,0};
        static const uint8_t name[] = {0x48,0x8D,0xAE,0x9C,0,0,0,0x48,0x83,0xCB,0xFF,
            0x48,0xFF,0xC3,0x66,0x44,0x39,0x6C,0x5D,0,0x75,0xF5};
        enabled_ = signature(base + 0x657220, gameData, sizeof(gameData)) &&
            signature(base + 0x688CD0, level, sizeof(level)) &&
            signature(base + 0x3C2B74, levelCaller, sizeof(levelCaller)) &&
            signature(base + 0x260087, name, sizeof(name));
        return enabled_;
    }

    HostProgressionSnapshot read(const void* player) const {
        HostProgressionSnapshot result;
        uintptr_t address = (uintptr_t)player, vtable = 0, data = 0;
        if (!enabled_ || !address || address > UINTPTR_MAX - 0x588 ||
            !mem_read(player, &vtable, sizeof(vtable)) || vtable != base_ + 0x2A7FBB0 ||
            !mem_read((void*)(address + 0x580), &data, sizeof(data)) || !data ||
            data > UINTPTR_MAX - 0xBE || !mem_read((void*)data, &vtable, sizeof(vtable)) ||
            vtable != base_ + 0x29E8FA8) return result;
        HostProgressionSnapshot observed;
        if (!mem_read((void*)(data + 0x68), &observed.level, sizeof(observed.level)) ||
            // Stock ER's supported level range; unknown/modded/corrupt values cannot grant points.
            observed.level < 1 || observed.level > 713 ||
            !mem_read((void*)(data + 0x9C), observed.name, sizeof(observed.name))) return result;
        uint16_t length = 0;
        while (length < 17 && observed.name[length]) ++length;
        if (!length || length == 17) return result;
        for (uint16_t i = 0; i < length; i++) {
            uint16_t c = observed.name[i];
            if (c >= 0xD800 && c <= 0xDBFF) {
                if (++i == length || observed.name[i] < 0xDC00 || observed.name[i] > 0xDFFF) return result;
            } else if (c >= 0xDC00 && c <= 0xDFFF) return result;
        }
        // Recheck fields and owner chain: a switched/rebuilt object is not a complete sample.
        uint32_t level = 0;
        uint16_t name[17];
        uintptr_t currentData = 0, playerVtable = 0, dataVtable = 0;
        if (!mem_read((void*)(data + 0x68), &level, sizeof(level)) || level != observed.level ||
            !mem_read((void*)(data + 0x9C), name, sizeof(name)) || memcmp(name, observed.name, sizeof(name)) ||
            !mem_read((void*)(address + 0x580), &currentData, sizeof(currentData)) || currentData != data ||
            !mem_read(player, &playerVtable, sizeof(playerVtable)) || playerVtable != base_ + 0x2A7FBB0 ||
            !mem_read((void*)data, &dataVtable, sizeof(dataVtable)) || dataVtable != base_ + 0x29E8FA8)
            return result;
        // Only a weak observable fingerprint. Name edits/reused names need explicit server
        // binding; native pointers, process IDs, level and hostLife never enter this hash.
        uint64_t hash = 14695981039346656037ull;
        for (uint16_t i = 0; i < length; i++) {
            hash = (hash ^ (observed.name[i] & 0xFFu)) * 1099511628211ull;
            hash = (hash ^ (observed.name[i] >> 8)) * 1099511628211ull;
        }
        for (uint16_t i = length; i < 17; i++) observed.name[i] = 0;
        observed.nameUnits = length;
        observed.fingerprint = hash ? hash : 1;
        observed.valid = true;
        return observed;
    }
};

inline void publish_host_progression(const HostProgressionSnapshot& value, bool loading, bool mcControl,
                                     uint64_t sampleFrame, uint32_t hostLife) {
    // Header starts at mapping offset zero. This independent block needs no GameState ABI change.
    auto* out = (ErmcHostProgression*)((uint8_t*)shm_header() + ERMC_OFF_HOST_PROGRESSION);
    const uint32_t writing = out->seq | 1u;
    out->seq = writing;
    __asm__ __volatile__("" ::: "memory");
    out->magic = ERMC_HOST_PROGRESSION_MAGIC;
    out->version = ERMC_HOST_PROGRESSION_VERSION;
    out->state = value.valid ? ERMC_PROGRESSION_READY : loading ? ERMC_PROGRESSION_LOADING : ERMC_PROGRESSION_UNAVAILABLE;
    out->sampleFrame = sampleFrame;
    out->hostLife = hostLife;
    out->level = value.valid ? value.level : 0;
    out->flags = ERMC_PROGRESSION_EXPLICIT_BINDING | (value.valid ?
        ERMC_PROGRESSION_OBSERVED_NAME | ERMC_PROGRESSION_WEAK_FINGERPRINT : 0) |
        (value.valid && mcControl ? ERMC_PROGRESSION_MC_CONTROL : 0);
    out->saveSlot = -1;
    out->fingerprint = value.valid ? value.fingerprint : 0;
    if (value.valid) memcpy(out->name, value.name, sizeof(out->name));
    else memset(out->name, 0, sizeof(out->name));
    out->nameUnits = value.valid ? value.nameUnits : 0;
    out->reserved = 0;
    __asm__ __volatile__("" ::: "memory");
    out->seq = writing + 1u;
}

} // namespace mb
