#pragma once
#include "host_progression.h"
#include <atomic>

namespace mb {

// Disk-verified for App 1.17.1 / exe 2.7.1.0 (SHA256 1a354710...b597891).
// PlayerIns wrapper 0x652690 calls 0x25E0E0 with PlayerIns+0x580 and signed delta.
// Keep the game's own rune-memory/saturation bookkeeping; never substitute a raw write.
namespace test_runes {
constexpr uintptr_t kAdd = 0x25E0E0;
constexpr uint32_t kBalanceMax = 999999999u;
constexpr uint8_t kCode[] = {
    0x44,0x8b,0x49,0x6c,0x45,0x33,0xdb,0x44,0x89,0x5c,0x24,0x10,0x45,0x8d,0x04,0x11,
    0x44,0x89,0x44,0x24,0x08,0x45,0x85,0xc0,0x79,0x07,0x48,0x8d,0x44,0x24,0x10,0xeb,
    0x17,0x41,0x81,0xf8,0xff,0xc9,0x9a,0x3b,0x48,0x8d,0x15,0x29,0xac,0x78,0x02,0x48,
    0x8d,0x44,0x24,0x08,0x48,0x0f,0x4f,0xc2,0x8b,0x00,0x89,0x41,0x6c,0x41,0x2b,0xc1,
    0x85,0xc0,0x7e,0x4a,0x8b,0x51,0x70,0x41,0xba,0xff,0xff,0xff,0xff,0x48,0x98,0x48,
    0x03,0xd0,0x4c,0x89,0x54,0x24,0x18,0x48,0x89,0x54,0x24,0x20,0x4c,0x89,0x5c,0x24,
    0x08,0x79,0x07,0x48,0x8d,0x44,0x24,0x08,0xeb,0x11,0x49,0x3b,0xd2,0x48,0x8d,0x44,
    0x24,0x18,0x4c,0x8d,0x44,0x24,0x20,0x49,0x0f,0x4e,0xc0,0x48,0x8b,0x00,0x49,0x3b,
    0xc2,0x48,0x89,0x41,0x70,0x0f,0x94,0xc0,0x88,0x81,0x09,0x01,0x00,0x00,0x8b,0x41,
    0x6c,0x41,0x2b,0xc1,0xc3
};
constexpr uint8_t kOwnerCall[] = {0x48,0x8b,0x8b,0x80,0x05,0,0,0x8b,0xd7,0xe8,0xf7,0xb9,0xc0,0xff};
enum Status { OK=0, BAD_REQUEST=-20, UNSUPPORTED=-21, NOT_ALIVE=-22, SESSION=-23,
              OWNER=-24, EXPIRED=-25, BALANCE=-26, SUPERSEDED=-27, READBACK_UNKNOWN=-28,
              DEDUP_FULL=-29 };

inline ErmcTestRunesResult result(const ErmcTestRunesRequest& req, int status) {
    ErmcTestRunesResult out = {};
    out.version = ERMC_TEST_RUNES_VERSION; out.status = status; out.nonce = req.nonce;
    return out;
}

class Access {
    uintptr_t base_ = 0;
    HostProgressionReader identity_;
    bool enabled_ = false;
    bool match(uintptr_t rva, const void* expected, size_t length) const {
        uint8_t bytes[sizeof(kCode)];
        return length <= sizeof(bytes) && mem_read((void*)(base_ + rva), bytes, length) &&
            !memcmp(bytes, expected, length);
    }
    bool signatures() const {
        const uint32_t cap = kBalanceMax;
        return match(kAdd, kCode, sizeof(kCode)) && match(0x6526DB, kOwnerCall, sizeof(kOwnerCall)) &&
            match(0x29E8D38, &cap, sizeof(cap));
    }
    bool dataFor(const void* player, uintptr_t& data) const {
        uintptr_t vt = 0, address = (uintptr_t)player;
        return address && address <= UINTPTR_MAX - 0x588 &&
            mem_read(player, &vt, sizeof(vt)) && vt == base_ + 0x2A7FBB0 &&
            mem_read((void*)(address + 0x580), &data, sizeof(data)) && data &&
            data <= UINTPTR_MAX - 0x10A && mem_read((void*)data, &vt, sizeof(vt)) && vt == base_ + 0x29E8FA8;
    }
public:
    bool init(uintptr_t base) {
        base_ = base;
        enabled_ = identity_.init(base) && signatures();
        return enabled_;
    }
    template<class Invoke, class Clock, class Current>
    ErmcTestRunesResult execute(const ErmcTestRunesRequest& req, const ErmcHeader& session,
                               const void* player, bool alive, Invoke invoke, Clock clock, Current current) {
        auto out = result(req, BAD_REQUEST);
        uint64_t now = clock();
        if (req.version != ERMC_TEST_RUNES_VERSION || req.reserved || !req.nonce ||
            req.amount > ERMC_TEST_RUNES_MAX || !req.deadlineMs || req.deadlineMs > now + 10000) return out;
        if (now >= req.deadlineMs) { out.status = EXPIRED; return out; }
        if (!current()) { out.status = SUPERSEDED; return out; }
        if (!enabled_ || !signatures()) { out.status = UNSUPPORTED; return out; }
        if (!alive) { out.status = NOT_ALIVE; return out; }
        if (!req.hostPid || req.hostPid != session.hostPid || req.hostStartMs != session.hostStartMs ||
            req.coreGeneration != session.coreGeneration || req.hostLife != session.hostLife || session.coreStatus != 1) {
            out.status = SESSION; return out;
        }
        auto owner = identity_.read(player);
        uintptr_t data = 0, repeatedData = 0;
        uint32_t balance = 0, repeatedBalance = 0;
        if (!owner.valid || !dataFor(player, data) || !mem_read((void*)(data + 0x6C), &balance, 4) ||
            balance > kBalanceMax) { out.status = OWNER; return out; }
        out.before = out.after = balance; out.level = owner.level; out.fingerprint = owner.fingerprint;
        out.hostPid = req.hostPid; out.hostStartMs = req.hostStartMs;
        out.coreGeneration = req.coreGeneration; out.hostLife = req.hostLife;
        if (!req.amount) { out.status = OK; return out; }
        if (req.fingerprint != owner.fingerprint || req.level != owner.level) { out.status = OWNER; return out; }
        if (req.currentRunes != balance || req.amount > kBalanceMax - balance) { out.status = BALANCE; return out; }
        // Re-read the entire owner chain and balance immediately before the sole call.
        auto again = identity_.read(player);
        if (!again.valid || again.fingerprint != owner.fingerprint || again.level != owner.level ||
            !dataFor(player, repeatedData) || repeatedData != data ||
            !mem_read((void*)(data + 0x6C), &repeatedBalance, 4) || repeatedBalance != balance) {
            out.status = OWNER; return out;
        }
        if (clock() >= req.deadlineMs) { out.status = EXPIRED; return out; }
        if (!current()) { out.status = SUPERSEDED; return out; }
        if (session.hostPid != req.hostPid || session.hostStartMs != req.hostStartMs ||
            session.coreGeneration != req.coreGeneration || session.hostLife != req.hostLife || session.coreStatus != 1) {
            out.status = SESSION; return out;
        }
        out.actualDelta = invoke(base_ + kAdd, (void*)data, (int32_t)req.amount);
        // A call has happened. Failure here is an unknown outcome, never a retry signal.
        auto after = identity_.read(player);
        if (!after.valid || after.fingerprint != owner.fingerprint || after.level != owner.level ||
            !dataFor(player, repeatedData) || repeatedData != data ||
            !mem_read((void*)(data + 0x6C), &out.after, 4) || out.after != balance + req.amount ||
            out.actualDelta != (int32_t)req.amount) { out.status = READBACK_UNKNOWN; return out; }
        out.status = OK;
        return out;
    }
};

// One worker producer, one game-thread consumer. The worker never waits inside its
// compositor loop. An expired request can only produce EXPIRED when ticks resume.
class Queue {
    std::atomic<int> state_{0}; // 0 idle, 1 pending, 2 executing, 3 completed
    uint32_t seq_ = 0;
    ErmcTestRunesRequest request_ = {};
    ErmcTestRunesResult response_ = {};
    struct Award {
        bool occupied = false;
        ErmcTestRunesRequest request = {};
        ErmcTestRunesResult response = {};
    };
    static constexpr size_t kCapacity = 16;
    Award awards_[kCapacity]; // worker-owned; queries cannot evict an unexpired award
    int pendingAward_ = -1;
    void completed() {
        if (pendingAward_ >= 0) awards_[pendingAward_].response = response_;
        pendingAward_ = -1;
        state_.store(0, std::memory_order_release);
    }
public:
    bool poll(uint32_t seq, const ErmcTestRunesRequest& req, ErmcTestRunesResult& out, uint64_t now) {
        int state = state_.load(std::memory_order_acquire);
        if (state == 0) {
            int empty = -1;
            for (size_t i = 0; i < kCapacity; i++) {
                auto& award = awards_[i];
                if (award.occupied && now >= award.request.deadlineMs) award.occupied = false;
                if (!award.occupied) { if (empty < 0) empty = (int)i; continue; }
                if (req.nonce && req.nonce == award.request.nonce) {
                    out = !memcmp(&req, &award.request, sizeof(req)) ? award.response : result(req, BAD_REQUEST);
                    return true;
                }
            }
            if (req.amount) {
                // Reserve space BEFORE accepting a mutation. A full cache must reject
                // new awards instead of evicting an outcome that can still be replayed.
                if (!req.nonce || req.version != ERMC_TEST_RUNES_VERSION || req.reserved ||
                    req.amount > ERMC_TEST_RUNES_MAX || req.deadlineMs > now + 10000) {
                    out = result(req, BAD_REQUEST); return true;
                }
                if (req.deadlineMs <= now) { out = result(req, EXPIRED); return true; }
                if (empty < 0) { out = result(req, DEDUP_FULL); return true; }
                pendingAward_ = empty;
                awards_[empty] = {true, req, {}};
            }
            seq_ = seq; request_ = req;
            state_.store(1, std::memory_order_release);
            return false;
        }
        if (seq != seq_) {
            // A foreign writer superseded the pending command. It cannot leave the
            // queue permanently stuck after the game thread has rejected/completed it.
            if (state == 3) completed();
            return false;
        }
        if (state != 3) return false;
        out = response_; completed(); return true;
    }
    template<class Execute>
    void service(uint32_t currentSeq, bool correctCommand, Execute execute) {
        int pending = 1;
        if (!state_.compare_exchange_strong(pending, 2, std::memory_order_acq_rel)) return;
        response_ = currentSeq == seq_ && correctCommand ? execute(request_) : result(request_, SUPERSEDED);
        state_.store(3, std::memory_order_release);
    }
};
static_assert(sizeof(ErmcTestRunesRequest) == 64 && sizeof(ErmcTestRunesResult) == 64, "test rune command ABI");
} // namespace test_runes
} // namespace mb
