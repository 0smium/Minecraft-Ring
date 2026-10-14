#pragma once
#include <stdint.h>
#include <stddef.h>

namespace mb::debug_test_enemy {

struct Identity {
    uintptr_t pointer = 0;
    uint64_t handle = 0;
    bool operator==(const Identity& other) const { return pointer == other.pointer && handle == other.handle; }
};

struct Context {
    uintptr_t world = 0, creator = 0;
    Identity player;
    uint32_t life = 0, zone = 0;
    bool operator==(const Context& other) const {
        return world == other.world && creator == other.creator && player == other.player &&
               life == other.life && zone == other.zone;
    }
};

struct Entry {
    Identity identity;
    bool matchesTemplate = false;
};

struct Snapshot {
    Context context;
    Entry entries[256]{};
    size_t count = 0;
    uintptr_t output = 0;
    bool complete = false, busy = false, parametersMatch = false;
};

// The native creator exposes a shared last-result pointer, not a request token.
// These checks reject observable interference; another writer submitting the exact
// same template between observations cannot be distinguished without further hooks.
class Tracker {
public:
    enum class Phase { Idle, Pending, Owned, Quarantined };
    enum class Result { Waiting, Confirmed, Abandoned, TimedOut };
    static constexpr uint64_t kTimeoutMs = 15000;

    bool context(const Context* current) {
        if (!current) {
            const bool changed = hasContext_;
            hasContext_ = false;
            phase_ = Phase::Idle;
            own_ = {};
            return changed;
        }
        if (hasContext_ && context_ == *current) return false;
        context_ = *current;
        hasContext_ = true;
        phase_ = Phase::Idle;
        own_ = {};
        return true;
    }

    Phase phase() const { return phase_; }
    bool begin(const Snapshot& before, uint64_t now) {
        if (phase_ != Phase::Idle || !sameContext(before) || !valid(before) || before.busy) return false;
        baselineCount_ = before.count;
        for (size_t i = 0; i < before.count; ++i) baseline_[i] = before.entries[i].identity;
        started_ = now;
        phase_ = Phase::Pending;
        return true;
    }

    // Do not reset the game's shared creator or adopt its eventual result after
    // cancellation. A new lifecycle is needed before submitting another request.
    void cancel() { if (phase_ == Phase::Pending) phase_ = Phase::Quarantined; }

    Result poll(const Snapshot& observed, uint64_t now) {
        if (phase_ != Phase::Pending) return Result::Waiting;
        if (!sameContext(observed) || !valid(observed) || !observed.parametersMatch) return abandon(Result::Abandoned);
        if (now < started_ || now - started_ >= kTimeoutMs) return abandon(Result::TimedOut);
        Identity candidate{};
        size_t newCount = 0;
        bool matches = false;
        for (size_t i = 0; i < observed.count; ++i) {
            const Entry& entry = observed.entries[i];
            bool previous = false;
            for (size_t j = 0; j < baselineCount_; ++j) previous |= entry.identity == baseline_[j];
            if (previous) continue;
            candidate = entry.identity;
            matches = entry.matchesTemplate;
            ++newCount;
        }
        if (newCount > 1 || (newCount == 1 && !matches)) return abandon(Result::Abandoned);
        if (observed.busy || newCount == 0 || candidate.pointer != observed.output) return Result::Waiting;
        own_ = candidate;
        phase_ = Phase::Owned;
        return Result::Confirmed;
    }

    // Consume the record before a destructive call, making repeated cleanup inert.
    // A missing/recycled object or unreadable set is forgotten, never rediscovered.
    Identity takeOwned(const Snapshot& observed) {
        if (phase_ != Phase::Owned) return {};
        const Identity wanted = own_;
        own_ = {};
        phase_ = Phase::Idle;
        if (!sameContext(observed) || !valid(observed)) return {};
        for (size_t i = 0; i < observed.count; ++i)
            if (observed.entries[i].identity == wanted) return wanted;
        return {};
    }

private:
    static bool valid(const Snapshot& snapshot) { return snapshot.complete && snapshot.count <= 256; }
    bool sameContext(const Snapshot& snapshot) const { return hasContext_ && context_ == snapshot.context; }
    Result abandon(Result result) { phase_ = Phase::Quarantined; return result; }
    Context context_{};
    bool hasContext_ = false;
    Phase phase_ = Phase::Idle;
    Identity baseline_[256]{}, own_{};
    size_t baselineCount_ = 0;
    uint64_t started_ = 0;
};

}  // namespace mb::debug_test_enemy
