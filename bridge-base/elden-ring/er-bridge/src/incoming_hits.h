#pragma once
#include "common.h"
#include "MinHook.h"
#include <string.h>

namespace mb { namespace incoming_hits {

// Target eldenring.exe 2.7.1.0 / App 1.17.1 only. The ordinary hit path
// 448910 -> 448A9B calls this eight-argument HP application; periodic SpEffect
// loss and fall damage call the different 436A40 function. ChrIns+180 is a
// conditional source/kill-credit hint, not evidence that an attack occurred.
using ApplyHp = void(__fastcall*)(void*, int, uint8_t, uint8_t, uint8_t, float, float, uint8_t);
static ApplyHp original = nullptr;
static uintptr_t returnAddress = 0;
static bool installed = false;
static SRWLOCK lock = SRWLOCK_INIT;
struct Target { void* data; void* player; uint32_t life; };
struct Batch {
    uint32_t count = 0;
    uint64_t actualLoss = 0;
    uint64_t sourceHint = ~0ull;
};
static Target target = {};
static uint64_t generation = 0;
static Batch pending;

inline void set_target(void* data, void* player, uint32_t life) {
    AcquireSRWLockExclusive(&lock);
    if (target.data != data || target.player != player || target.life != life) {
        target = {data, player, life};
        generation++;
        pending = {};
    }
    ReleaseSRWLockExclusive(&lock);
}

inline Batch take(void* data, void* player, uint32_t life) {
    AcquireSRWLockExclusive(&lock);
    Batch result;
    if (target.data == data && target.player == player && target.life == life) result = pending;
    pending = {};
    ReleaseSRWLockExclusive(&lock);
    return result;
}

static void __fastcall observe(void* data, int delta, uint8_t a3, uint8_t a4, uint8_t a5,
                               float a6, float a7, uint8_t a8) {
    InflightGuard guard;
    const uintptr_t caller = (uintptr_t)__builtin_return_address(0);
    AcquireSRWLockShared(&lock);
    const Target wanted = target;
    const uint64_t epoch = generation;
    ReleaseSRWLockShared(&lock);
    int before = 0;
    void* owner = nullptr;
    const bool capture = caller == returnAddress && delta < 0 && wanted.data == data && wanted.player &&
        mem_read((uint8_t*)data + 8, &owner, sizeof(owner)) && owner == wanted.player &&
        mem_read((uint8_t*)data + 0x138, &before, sizeof(before)) && before > 0;

    // Preserve the native ABI, checks, NoDead clamping and effects; exactly one call.
    original(data, delta, a3, a4, a5, a6, a7, a8);

    int after = 0;
    if (!capture || !mem_read((uint8_t*)data + 0x138, &after, sizeof(after)) || after >= before) return;
    const uint64_t loss = (uint64_t)((int64_t)before - after);
    uint64_t hint = ~0ull;
    mem_read((uint8_t*)owner + 0x180, &hint, sizeof(hint));
    AcquireSRWLockExclusive(&lock);
    if (generation == epoch && target.data == wanted.data && target.player == wanted.player &&
        target.life == wanted.life) {
        pending.count++;
        pending.actualLoss += loss;
        pending.sourceHint = hint;
    }
    ReleaseSRWLockExclusive(&lock);
}

static bool match(uintptr_t address, const uint8_t* expected, size_t size) {
    uint8_t bytes[64];
    return size <= sizeof(bytes) && mem_read((const void*)address, bytes, size) &&
        memcmp(bytes, expected, size) == 0;
}

inline bool init(uintptr_t base) {
    static const uint8_t prologue[] = {
        0x48,0x89,0x5C,0x24,0x08,0x48,0x89,0x6C,0x24,0x10,0x48,0x89,0x74,0x24,0x18,
        0x57,0x48,0x83,0xEC,0x30,0x41,0x0F,0xB6,0xF1,0x41,0x0F,0xB6,0xE8,0x8B,0xDA,
        0x48,0x8B,0xF9,0x85,0xD2,0x79,0x1E};
    // Registers and stack arguments from the final, calculated hit damage info.
    static const uint8_t caller[] = {
        0x41,0x8B,0x96,0x28,0x02,0x00,0x00,0x45,0x0F,0xB6,0xCC,
        0xF3,0x41,0x0F,0x10,0x86,0x4C,0x01,0x00,0x00,0x85,0xD2,
        0xF3,0x41,0x0F,0x10,0x8E,0x48,0x01,0x00,0x00,0x45,0x0F,0xB6,0x86,0x15,0x01,0x00,0x00};
    static const uint8_t call[] = {0xE8,0x40,0xE0,0xFE,0xFF};
    static const uint8_t callerArguments[] = {
        0x0F,0x9F,0xC0,0x88,0x44,0x24,0x38,0x41,0x80,0xE0,0x01,0xF3,0x0F,0x11,
        0x44,0x24,0x30,0xF7,0xDA,0xF3,0x0F,0x11,0x4C,0x24,0x28,0x88,0x4C,0x24,
        0x20,0x49,0x8B,0xCD,0xE8,0x40,0xE0,0xFE,0xFF};
    static const uint8_t calleeArguments[] = {
        0x0F,0xB6,0x44,0x24,0x78,0x8B,0xD3,0xF3,0x0F,0x10,0x44,0x24,0x70,
        0x48,0x8B,0xCF,0xF3,0x0F,0x10,0x5C,0x24,0x68,0x44,0x0F,0xB6,0x44,
        0x24,0x60,0x88,0x44,0x24,0x28,0xF3,0x0F,0x11,0x44,0x24,0x20,
        0xE8,0xE7,0x08,0x00,0x00};
    if (!match(base + 0x436AE0, prologue, sizeof(prologue)) ||
        !match(base + 0x448A54, caller, sizeof(caller)) || !match(base + 0x448A9B, call, sizeof(call)) ||
        !match(base + 0x448A7B, callerArguments, sizeof(callerArguments)) ||
        !match(base + 0x436B3E, calleeArguments, sizeof(calleeArguments))) {
        log("combat: incoming-hit observation disabled (2.7.1.0 signature mismatch)");
        return false;
    }
    returnAddress = base + 0x448AA0;
    void* at = (void*)(base + 0x436AE0);
    MH_STATUS made = MH_CreateHook(at, (void*)&observe, (void**)&original);
    if (made != MH_OK) {
        log("combat: incoming-hit observation disabled (create hook %d)", made);
        return false;
    }
    MH_STATUS enabled = MH_EnableHook(at);
    if (enabled != MH_OK) {
        MH_RemoveHook(at);
        original = nullptr;
        log("combat: incoming-hit observation disabled (enable hook %d)", enabled);
        return false;
    }
    installed = true;
    log("combat: confirmed incoming-hit HP observation enabled (436AE0, caller 448AA0)");
    return true;
}

inline void detach() {
    set_target(nullptr, nullptr, 0);
    // core.cpp disables all MinHook entries, waits for InflightGuard, then removes
    // trampolines. Do not remove this trampoline while a native call is in flight.
    if (installed) log("combat: incoming-hit capture target released for shutdown");
}

} } // namespace mb::incoming_hits
