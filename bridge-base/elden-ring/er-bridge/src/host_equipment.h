#pragma once
#include "host_inventory.h"

namespace mb::host_equipment {
// App 1.17.1 / executable 2.7.1.0 only. See tools/test_host_equipment.py for
// complete disk-function hashes and native UI caller evidence. Calls run only in
// task_update; no worker-side invocation or direct equipment-memory write exists.
constexpr uintptr_t kEquip = 0x249160, kUnequip = 0x247160;
constexpr uint32_t kArmorSlots = (1u << 12) | (1u << 13) | (1u << 14) | (1u << 15);
constexpr uint32_t kTalismanSlots = (1u << 17) | (1u << 18) | (1u << 19) | (1u << 20);
enum Status { OK=0, BAD_REQUEST=-40, UNSUPPORTED=-41, NOT_READY=-42, SESSION=-43,
    OWNER=-44, EXPIRED=-45, STALE=-46, ITEM=-47, SLOT=-48, SUPERSEDED=-49,
    READBACK_UNKNOWN=-50, DEDUP_FULL=-51, CONFLICT=-52 };

inline ErmcEquipmentResult result(const ErmcEquipmentRequest& req, int status) {
    ErmcEquipmentResult out = {};
    out.version = ERMC_HOST_EQUIPMENT_VERSION; out.status = status;
    out.equipmentSlot = req.equipmentSlot; out.nonce = req.nonce;
    out.hostStartMs = req.hostStartMs; out.hostLife = req.hostLife;
    out.inventorySlot = UINT32_MAX;
    return out;
}

class Access {
    uintptr_t base_ = 0;
    bool enabled_ = false;
    HostInventoryReader reader_;
    bool code(uintptr_t rva, size_t size, uint64_t expected) const {
        uint64_t hash = 14695981039346656037ull;
        uint8_t chunk[128];
        for (size_t at = 0; at < size;) {
            size_t take = size-at < sizeof(chunk) ? size-at : sizeof(chunk);
            if (!mem_read((void*)(base_+rva+at), chunk, take)) return false;
            for (size_t i=0;i<take;++i) { hash ^= chunk[i]; hash *= 1099511628211ull; }
            at += take;
        }
        return hash == expected;
    }
    bool signatures() const {
        return code(kEquip, 0x1AC, 0x5A6372977F7AD099ull) &&
            code(kUnequip, 0x61B, 0x5B44F153BA58F3FFull) &&
            code(0x788E84, 0x1F, 0x656BFC673790468Cull) &&
            code(0x247890, 0x49, 0x4671F979229599F2ull) &&
            code(0xD3005C, 0x23, 0x5A1D9C888691BE68ull) &&
            code(0xD238E0, 0x57, 0x0F6C4C72CDCB869Bull) && // actual same-group predicate
            code(0xD23657, 0x23, 0x4E3549431A522F3Cull) && // accessory param index 2
            code(0x788630, 0x3A, 0x3F3F5C3CDD3DED2Full) && // native UI conflict loop
            code(0x788CE9, 0x13, 0x036335D52C8CF836ull);   // native accessory slot category
    }
    static bool session(const ErmcEquipmentRequest& req, const ErmcHeader& host) {
        return req.hostPid && req.hostPid == host.hostPid && req.hostStartMs == host.hostStartMs &&
            req.coreGeneration == host.coreGeneration && req.hostLife == host.hostLife && host.coreStatus == 1;
    }
    template<class Accessory>
    static int talismanRules(const ErmcEquipmentRequest& req, const HostInventorySnapshot& inventory,
                             Accessory accessory) {
        int16_t requestedGroup=-1;
        if (req.operation==1 && ((req.itemId>>28)!=2 || !accessory(req.itemId,requestedGroup))) return ITEM;
        for (uint32_t slot=17;slot<=20;++slot) {
            const uint32_t index=inventory.equipmentIndices[slot], handle=inventory.equipmentHandles[slot];
            if (index==UINT32_MAX && handle==0) continue;
            const ErmcInventoryEntry* equipped=nullptr;
            for (uint32_t i=0;i<inventory.count;++i) {
                const auto& entry=inventory.entries[i];
                if (entry.slot==index && entry.gaitemHandle==handle && (entry.equipmentMask&(1u<<slot))) {
                    if (equipped) return ITEM;
                    equipped=&entry;
                }
            }
            // A broken/foreign occupied slot is unknown, never silently skipped.
            int16_t group=-1;
            if (!equipped || equipped->flags || (equipped->rawItemId>>28)!=2 ||
                !accessory(equipped->rawItemId,group)) return ITEM;
            if (req.operation==1 && slot!=req.equipmentSlot &&
                (equipped->gaitemHandle==req.handle || equipped->rawItemId==req.itemId ||
                 (requestedGroup!=-1 && group!=-1 && requestedGroup==group))) return CONFLICT;
        }
        return OK;
    }
public:
    bool init(uintptr_t base) {
        base_ = base; enabled_ = reader_.init(base) && signatures(); return enabled_;
    }
    uint32_t writableSlots(uint32_t talismans=0) const {
        if (!enabled_) return 0;
        return kArmorSlots | (talismans>=1 && talismans<=4 ? ((1u<<talismans)-1u)<<17 : 0);
    }

    // Metadata uses the same game-verified formula as EquipGameData::GetAccessorySize:
    // PlayerCommonParam[0].baseAccSlotSize + PlayerGameData.unlocked_talisman_slots,
    // capped at four. The caller supplies the freshly read regulation base (0 unknown).
    uint32_t talismanSlots(const void* player, uint32_t regulationBase) const {
        uintptr_t data = 0;
        uint8_t extra = 0;
        if (!enabled_ || !player || regulationBase < 1 || regulationBase > 4 ||
            !mem_read((const uint8_t*)player+0x580, &data, 8) || !data ||
            !mem_read((void*)(data+0xC6), &extra, 1) || extra > 3) return 0;
        const uint32_t count = regulationBase+extra;
        return count > 4 ? 4 : count;
    }

    template<class Armor, class Accessory, class Capacity, class Invoke, class Clock, class Current>
    ErmcEquipmentResult execute(const ErmcEquipmentRequest& req, const ErmcHeader& host,
        const void* player, bool ready, uint32_t zone, Armor armor, Accessory accessory, Capacity capacity,
        Invoke invoke, Clock clock, Current current) {
        auto out = result(req, BAD_REQUEST);
        uint64_t now = clock();
        if (req.version != ERMC_HOST_EQUIPMENT_VERSION || !req.nonce || !req.snapshotToken ||
            (req.operation != 1 && req.operation != 2) || req.deadlineMs > now+10000) return out;
        if (now >= req.deadlineMs) { out.status=EXPIRED; return out; }
        if (!current()) { out.status=SUPERSEDED; return out; }
        if (!enabled_ || !signatures()) { out.status=UNSUPPORTED; return out; }
        const bool talisman=req.equipmentSlot>=17 && req.equipmentSlot<=20;
        if (!talisman && (req.equipmentSlot < 12 || req.equipmentSlot > 15)) { out.status=SLOT; return out; }
        if (!ready || !player) { out.status=NOT_READY; return out; }
        if (!session(req,host) || req.zone != zone) { out.status=SESSION; return out; }
        const uint32_t slots=talisman ? capacity() : 0;
        if (talisman && (!slots || slots>4 || req.equipmentSlot-17>=slots)) { out.status=SLOT; return out; }
        const auto& before = reader_.read(player);
        if (!before.valid || before.fingerprint != req.fingerprint) { out.status=OWNER; return out; }
        const uint64_t fingerprint = before.fingerprint, token = before.token();
        if (before.totalEntries != before.count || token != req.snapshotToken) { out.status=STALE; return out; }
        if (req.operation == 1) {
            bool found = false;
            for (uint32_t i=0;i<before.count;++i) {
                const auto& entry=before.entries[i];
                if (entry.slot == req.inventorySlot && entry.gaitemHandle == req.handle &&
                    entry.rawItemId == req.itemId && !entry.flags) found=true;
            }
            if (!found || (!talisman && ((req.itemId >> 28) != 1 || !armor(req.itemId,req.equipmentSlot)))) {
                out.status=ITEM; return out;
            }
        } else if (req.handle || req.itemId || req.inventorySlot != UINT32_MAX) return out;
        if (talisman) {
            out.status=talismanRules(req,before,accessory);
            if (out.status!=OK) return out;
        }

        uintptr_t data = 0, repeatedData = 0;
        if (!mem_read((const uint8_t*)player+0x580,&data,8) || !data) { out.status=OWNER; return out; }
        // read() reuses one owned buffer. Carry only the validated scalar identity
        // and full-content token across reads, never a stale snapshot reference.
        const auto& again=reader_.read(player);
        if (!again.valid || again.totalEntries != again.count || again.fingerprint != fingerprint || again.token() != token ||
            !mem_read((const uint8_t*)player+0x580,&repeatedData,8) || data != repeatedData) {
            out.status=STALE; return out;
        }
        if (talisman && (capacity()!=slots || talismanRules(req,again,accessory)!=OK)) { out.status=STALE; return out; }
        if (!session(req,host) || clock() >= req.deadlineMs || !current()) { out.status=SUPERSEDED; return out; }
        invoke(req.operation == 1 ? base_+kEquip : base_+kUnequip, (void*)(data+0x2B0), req);
        // Invocation happened exactly once. Any missing/different observation means
        // UNKNOWN, never a retry suggestion; UI waits for a fresh native snapshot.
        out.status=READBACK_UNKNOWN; out.sampledAt=clock();
        const auto& after=reader_.read(player);
        if (!after.valid || after.totalEntries != after.count || after.fingerprint != fingerprint || !session(req,host) ||
            !mem_read((const uint8_t*)player+0x580,&repeatedData,8) || data != repeatedData) return out;
        out.snapshotToken=after.token();
        if (talisman && req.operation==2) {
            // 0x247614..0x247643 passes native handle=0 and index=-1 to equip.
            // Missing item projection is insufficient: verify both raw native fields.
            if (after.equipmentIndices[req.equipmentSlot]==UINT32_MAX && !after.equipmentHandles[req.equipmentSlot])
                out.status=OK;
            return out;
        }
        for (uint32_t i=0;i<after.count;++i) {
            const auto& entry=after.entries[i];
            if (!(entry.equipmentMask & (1u << req.equipmentSlot))) continue;
            out.handle=entry.gaitemHandle;out.itemId=entry.rawItemId;out.inventorySlot=entry.slot;
            const uint32_t naked=0x10000000u+10000+(req.equipmentSlot-12)*100;
            if ((req.operation == 1 && entry.gaitemHandle == req.handle && entry.rawItemId == req.itemId &&
                    entry.slot == req.inventorySlot) || (req.operation == 2 && entry.rawItemId == naked)) out.status=OK;
            break;
        }
        return out;
    }
};

// All state is game-thread-owned. Retain every accepted nonce until its deadline;
// later requests cannot evict an outcome that could otherwise replay a mutation.
class Mailbox {
    struct Done { bool occupied=false; ErmcEquipmentRequest request={}; ErmcEquipmentResult result={}; } done_[16];
public:
    void reset(ErmcEquipmentMailbox* box) {
        for (auto& done:done_) done={};
        memset(box,0,sizeof(*box));
    }
    template<class Execute>
    bool service(ErmcEquipmentMailbox* box, uint64_t now, Execute execute) {
        const uint32_t seq=box->reqSeq;
        if (!seq || seq == box->respSeq) return false;
        __asm__ __volatile__("" ::: "memory");
        ErmcEquipmentRequest req;memcpy(&req,&box->request,sizeof(req));
        __asm__ __volatile__("" ::: "memory");
        if (seq != box->reqSeq) return false;
        ErmcEquipmentResult out=result(req,BAD_REQUEST);
        int empty=-1;bool duplicate=false;
        for (int i=0;i<16;++i) {
            auto& old=done_[i];
            if (old.occupied && now >= old.request.deadlineMs) old.occupied=false;
            if (!old.occupied) { if(empty<0) empty=i; continue; }
            if (req.nonce && req.nonce == old.request.nonce) {
                duplicate=true;
                out=!memcmp(&req,&old.request,sizeof(req)) ? old.result : result(req,BAD_REQUEST);
                break;
            }
        }
        if (!duplicate) {
            if (!req.nonce || req.version != ERMC_HOST_EQUIPMENT_VERSION || req.deadlineMs > now+10000) {}
            else if (req.deadlineMs <= now) out=result(req,EXPIRED);
            else if (empty<0) out=result(req,DEDUP_FULL);
            else {
                done_[empty]={true,req,{}};
                out=execute(req,seq);
                done_[empty].result=out;
            }
        }
        if (seq != box->reqSeq) return true;
        memcpy(&box->result,&out,sizeof(out));
        __asm__ __volatile__("" ::: "memory");
        box->respSeq=seq;
        return true;
    }
};
} // namespace mb::host_equipment
