#pragma once
#include "host_progression.h"
namespace mb {

// Read-only App 1.17.1 / exe 2.7.1.0 adapter. Layout reference is fixed at
// vswarte/fromsoftware-rs@59fbd3b3b7daaf14aca47c9f73530493dba6bc79,
// crates/eldenring/src/cs/{player_game_data,item_id}.rs. Every traversed class
// has target-executable RTTI and access-instruction checks; no operation API is guessed.
// Call read on the game thread at most twice per second. The reader owns bounded
// scratch buffers so the game's stack is not used for two whole native inventories.
struct HostInventorySnapshot {
    bool valid = false;
    uint64_t fingerprint = 0;
    uint32_t count = 0, totalEntries = 0, normalCapacity = 0, keyCapacity = 0;
    ErmcInventoryEntry entries[ERMC_MAX_INVENTORY_ENTRIES] = {};
};

class HostInventoryReader {
    static constexpr uint32_t kMaxSlots = 4096;
    struct NativeEntry {
        uint32_t handle, itemId, quantity, sortId;
        uint8_t isNew, padding[3];
        int32_t potGroup;
    };
    static_assert(sizeof(NativeEntry) == 24, "target native inventory stride");
    uintptr_t base_ = 0;
    bool enabled_ = false;
    HostProgressionReader identity_;
    NativeEntry key_[kMaxSlots], normal_[kMaxSlots], repeated_[kMaxSlots];

    static bool bytes(uintptr_t address, const void* expected, size_t size) {
        uint8_t value[64];
        return size <= sizeof(value) && mem_read((void*)address, value, size) && !memcmp(value, expected, size);
    }
    template<class T> static T at(const uint8_t* data, size_t offset) {
        T value; memcpy(&value, data + offset, sizeof(value)); return value;
    }
    static bool span(uintptr_t address, size_t size) {
        return address && address <= UINTPTR_MAX - size;
    }
    bool copy_entries(uintptr_t source, uint32_t capacity, NativeEntry* target) {
        if (!capacity) return true;
        const size_t size = size_t(capacity) * sizeof(NativeEntry);
        return span(source, size) && mem_read((void*)source, target, size);
    }
    bool unchanged(uintptr_t source, uint32_t capacity, const NativeEntry* previous) {
        return !capacity || (copy_entries(source, capacity, repeated_) &&
            !memcmp(repeated_, previous, size_t(capacity) * sizeof(NativeEntry)));
    }
    static bool category(uint32_t id) {
        const uint32_t kind = id >> 28;
        return kind == 0 || kind == 1 || kind == 2 || kind == 4 || kind == 8;
    }
    static bool append(HostInventorySnapshot& result, const NativeEntry* entries,
                       uint32_t capacity, uint32_t firstSlot, bool key,
                       const uint32_t* equipment, const uint32_t* handles) {
        for (uint32_t i = 0; i < capacity; ++i) {
            const auto& item = entries[i];
            // Empty slots are deliberately retained in native indexing. A depleted
            // flask may still be an owned item with zero quantity.
            if (!category(item.itemId) || !item.handle || item.handle == UINT32_MAX) continue;
            if (item.quantity > 999999999u || item.isNew > 1) return false;
            ++result.totalEntries;
            if (result.count == ERMC_MAX_INVENTORY_ENTRIES) continue;
            auto& out = result.entries[result.count++];
            out.slot = firstSlot + i;
            out.gaitemHandle = item.handle;
            out.rawItemId = item.itemId;
            out.quantity = item.quantity;
            out.sortId = item.sortId;
            out.flags = key ? ERMC_INVENTORY_KEY_ITEM : 0;
            for (uint32_t slot = 0; slot < 22; ++slot)
                if (equipment[slot] == out.slot && handles[slot] == item.handle)
                    out.equipmentMask |= 1u << slot;
        }
        return true;
    }

public:
    bool init(uintptr_t base) {
        base_ = base;
        static const uint8_t equipment[] = {0x4C,0x8D,0xB1,0xB0,0x02,0,0,0x4C,0x8B,0xFA,0x49,0x8B,0xCE,0xE8,0x4D,0x84,0xFE,0xFF};
        static const uint8_t inventory[] = {0x48,0x8D,0x81,0x58,0x01,0,0,0xC3};
        static const uint8_t entries[] = {0x8B,0x41,0x1C,0x48,0x89,0x7C,0x24,0x30,0x3B,0xD0,0x73,0x10,
            0x8B,0xC2,0x48,0x8D,0x14,0x40,0x48,0x8B,0x41,0x50,0x48,0x8D,0x3C,0xD0,
            0xEB,0x0E,0x2B,0xD0,0x49,0x8B,0x40,0x40,0x48,0x8D,0x0C,0x52,0x48,0x8D,0x3C,0xC8};
        static const uint8_t counts[] = {0x48,0x8B,0x4F,0x58,0x8B,0x47,0x1C,0x39,0x01,0x0F,0x92,0xC3,0xEB,0x15,
            0x48,0x8B,0x4F,0x48,0x8B,0x47,0x0C,0x39,0x01};
        static const uint8_t quantity[] = {0x8B,0x41,0x08,0xC3};
        static const uint8_t equipmentHandle[] = {0x49,0x63,0xC0,0x8B,0x4C,0x81,0x24,0x48,0x8B,0xC2,0x89,0x0A,0xC3};
        static const uint8_t equipmentSlot[] = {0x48,0x83,0xC1,0x6C,0x44,0x8B,0xC5,0x48,0x8D,0x94,0x24,0xC0,0,0,0,
            0xE8,0x69,0x55,0x17,0,0x8B,0x03,0x39,0x84,0x24,0xC0,0,0,0,0x75,0x6A,0x39,0x74,0xAF,0x08};
        static const uint8_t entryFields[] = {0xC7,0x43,0x04,0xFF,0xFF,0xFF,0xFF,0x48,0xC7,0x43,0x08,0,0,0,0};
        static const uint8_t owner[] = {0x49,0x89,0xB6,0xF0,0x03,0,0,0x41,0x88,0xAE,0xF8,0x03,0,0};
        enabled_ = identity_.init(base) && bytes(base+0x260461,equipment,sizeof(equipment)) &&
            bytes(base+0x2488C0,inventory,sizeof(inventory)) && bytes(base+0x24E787,entries,sizeof(entries)) &&
            bytes(base+0x24C734,counts,sizeof(counts)) && bytes(base+0x7135F0,quantity,sizeof(quantity)) &&
            bytes(base+0x3BE700,equipmentHandle,sizeof(equipmentHandle)) &&
            bytes(base+0x249183,equipmentSlot,sizeof(equipmentSlot)) && bytes(base+0x7135B9,entryFields,sizeof(entryFields)) &&
            bytes(base+0x245515,owner,sizeof(owner));
        return enabled_;
    }

    HostInventorySnapshot read(const void* player) {
        HostInventorySnapshot result;
        if (!enabled_) return result;
        const auto identity = identity_.read(player);
        if (!identity.valid) return result;
        uintptr_t data = 0;
        if (!mem_read((const uint8_t*)player+0x580,&data,sizeof(data)) || !span(data,0x6B0)) return result;
        const uintptr_t equipment = data+0x2B0, inventory = equipment+0x158;
        uintptr_t equipmentVtable = 0, owner = 0;
        uint8_t local = 0, head[0x60], repeatedHead[0x60];
        uint32_t equipped[22], handles[22], repeatedEquipped[22], repeatedHandles[22];
        if (!mem_read((void*)equipment,&equipmentVtable,8) || equipmentVtable != base_+0x29E5880 ||
            !mem_read((void*)(equipment+0x3F0),&owner,8) || owner != data ||
            !mem_read((void*)(equipment+0x3F8),&local,1) || local != 1 ||
            !mem_read((void*)inventory,head,sizeof(head)) || at<uintptr_t>(head,0) != base_+0x29E5F28 ||
            !mem_read((void*)(equipment+8),equipped,sizeof(equipped)) ||
            !mem_read((void*)(equipment+0x90),handles,sizeof(handles))) return result;
        const uint32_t normalCapacity = at<uint32_t>(head,0x0C), keyCapacity = at<uint32_t>(head,0x1C);
        const uint32_t normalLength = at<uint32_t>(head,0x18);
        const uintptr_t normalHead = at<uintptr_t>(head,0x40), keyHead = at<uintptr_t>(head,0x50);
        uint32_t keyActiveCapacity = keyCapacity, keyLength = at<uint32_t>(head,0x28);
        if (normalCapacity > kMaxSlots || keyCapacity > kMaxSlots ||
            normalLength > normalCapacity ||
            normalHead != at<uintptr_t>(head,0x10) || at<uintptr_t>(head,0x48) != inventory+0x18) return result;
        if (at<uintptr_t>(head,0x58) == inventory+0x28) {
            if (keyHead != at<uintptr_t>(head,0x20)) return result;
        } else if (at<uintptr_t>(head,0x58) == inventory+0x38) {
            keyActiveCapacity = at<uint32_t>(head,0x2C);
            keyLength = at<uint32_t>(head,0x38);
            if (keyHead != at<uintptr_t>(head,0x30) || keyActiveCapacity > keyCapacity) return result;
        } else return result;
        if (keyLength > keyActiveCapacity || !copy_entries(normalHead,normalLength,normal_) ||
            !copy_entries(keyHead,keyLength,key_) ||
            !unchanged(normalHead,normalLength,normal_) || !unchanged(keyHead,keyLength,key_) ||
            !mem_read((void*)inventory,repeatedHead,sizeof(repeatedHead)) || memcmp(head,repeatedHead,sizeof(head)) ||
            !mem_read((void*)(equipment+8),repeatedEquipped,sizeof(equipped)) || memcmp(equipped,repeatedEquipped,sizeof(equipped)) ||
            !mem_read((void*)(equipment+0x90),repeatedHandles,sizeof(handles)) || memcmp(handles,repeatedHandles,sizeof(handles))) return result;
        uintptr_t again = 0;
        uint8_t localAgain = 0;
        const auto after = identity_.read(player);
        if (!after.valid || after.fingerprint != identity.fingerprint || after.level != identity.level ||
            !mem_read((const uint8_t*)player+0x580,&again,8) || again != data ||
            !mem_read((void*)equipment,&again,8) || again != equipmentVtable ||
            !mem_read((void*)(equipment+0x3F0),&again,8) || again != data ||
            !mem_read((void*)(equipment+0x3F8),&localAgain,1) || localAgain != 1) return result;
        result.normalCapacity = normalCapacity;
        result.keyCapacity = keyCapacity;
        if (!append(result,key_,keyLength,0,true,equipped,handles) ||
            !append(result,normal_,normalLength,keyCapacity,false,equipped,handles)) return {};
        result.fingerprint = identity.fingerprint;
        result.valid = true;
        return result;
    }
};

inline void publish_host_inventory(const HostInventorySnapshot& value, bool loading, bool mcControl,
                                   uint64_t frame, uint32_t life, uint64_t sampleTimeMs) {
    auto* out = (ErmcHostInventory*)((uint8_t*)shm_header()+ERMC_OFF_HOST_INVENTORY);
    const uint32_t writing = out->seq | 1u;
    out->seq = writing;
    __asm__ __volatile__("" ::: "memory");
    const bool ready = value.valid && !loading;
    out->magic = ERMC_HOST_INVENTORY_MAGIC;
    out->version = ERMC_HOST_INVENTORY_VERSION;
    out->state = loading ? ERMC_INVENTORY_LOADING : ready ? ERMC_INVENTORY_READY : ERMC_INVENTORY_UNAVAILABLE;
    out->sampleFrame = frame;
    out->sampleTimeMs = sampleTimeMs;
    out->ownerFingerprint = ready ? value.fingerprint : 0;
    out->hostLife = life;
    out->flags = ready ? ((mcControl ? ERMC_INVENTORY_MC_CONTROL : 0) |
        (value.totalEntries > value.count ? ERMC_INVENTORY_TRUNCATED : 0)) : 0;
    out->count = ready ? value.count : 0;
    out->totalEntries = ready ? value.totalEntries : 0;
    out->normalCapacity = ready ? value.normalCapacity : 0;
    out->keyCapacity = ready ? value.keyCapacity : 0;
    if (ready) memcpy(out->entries,value.entries,sizeof(out->entries));
    else memset(out->entries,0,sizeof(out->entries));
    __asm__ __volatile__("" ::: "memory");
    out->seq = writing+1u;
}

// The game thread invalidates a changed/unavailable owner immediately, while full
// inventory reads remain bounded to one attempt per 500 ms, including failed reads.
class HostInventoryFeed {
    HostInventoryReader reader_;
    bool enabled_ = false;
    uintptr_t player_ = 0;
    uint64_t fingerprint_ = 0, lastAttempt_ = 0;
    uint32_t life_ = UINT32_MAX, state_ = UINT32_MAX;
public:
    bool init(uintptr_t base) { enabled_ = reader_.init(base); return enabled_; }
    void reset(uint64_t frame, uint32_t life, uint64_t now) {
        player_ = 0; fingerprint_ = 0; lastAttempt_ = 0; life_ = UINT32_MAX; state_ = UINT32_MAX;
        publish_host_inventory({}, false, false, frame, life, now);
    }
    void tick(const void* player, const HostProgressionSnapshot& identity, bool loading, bool mcControl,
              uint64_t frame, uint32_t life, uint64_t now) {
        const bool ready = enabled_ && player && identity.valid && !loading;
        const uint32_t unavailable = enabled_ && loading ? ERMC_INVENTORY_LOADING : ERMC_INVENTORY_UNAVAILABLE;
        const uint64_t fingerprint = ready ? identity.fingerprint : 0;
        const uintptr_t address = ready ? (uintptr_t)player : 0;
        if (address != player_ || fingerprint != fingerprint_ || life != life_ || (!ready && state_ != unavailable)) {
            player_ = address; fingerprint_ = fingerprint; life_ = life; state_ = unavailable;
            publish_host_inventory({}, unavailable == ERMC_INVENTORY_LOADING, false, frame, life, now);
        }
        if (!ready || (lastAttempt_ && now >= lastAttempt_ && now - lastAttempt_ < 500)) return;
        lastAttempt_ = now;
        auto value = reader_.read(player);
        if (value.valid && value.fingerprint != fingerprint) value = {};
        state_ = value.valid ? ERMC_INVENTORY_READY : ERMC_INVENTORY_UNAVAILABLE;
        publish_host_inventory(value, false, mcControl, frame, life, now);
    }
};

} // namespace mb
