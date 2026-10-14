#pragma once
#include "common.h"
#include <string.h>

namespace mb {
// App 1.17.1 / executable 2.7.1.0 only. Read-only; no hooks, menu calls or input writes.
// Pinned fromsoftware-rs 59fbd3b: cs/menu_man.rs and cs/lua_event_man.rs;
// EldenCraft ba7591c first_person.rs uses the same blocking-menu observation.
// The disk-verified 8C5960 update sets view+B08 false iff CSMenuMan+798 has a
// blocking job; it is not the banner's visible bit (+B09). See test_host_interaction.py.
struct HostInteractionSnapshot { uint32_t flags=0, restFlags=0; };
class HostInteractionReader {
    uintptr_t base_=0;
    bool enabled_=false;
    struct Raw {
        uintptr_t menu=0, model=0, view=0, lua=0, proxy=0, action=0, job=0;
        uint32_t luaFlags=0;
        uint8_t active=0, actionReady=0;
        bool same(const Raw& other)const{
            return menu==other.menu&&model==other.model&&view==other.view&&lua==other.lua&&
                proxy==other.proxy&&action==other.action&&job==other.job&&luaFlags==other.luaFlags&&
                active==other.active&&actionReady==other.actionReady;
        }
    };
    static bool signature(uintptr_t address,const uint8_t* expected,size_t size){
        uint8_t bytes[64];
        return size<=sizeof(bytes)&&mem_read((void*)address,bytes,size)&&!memcmp(bytes,expected,size);
    }
    bool pointer(uintptr_t address,uintptr_t& out)const{
        return mem_read((void*)address,&out,8)&&out>=0x10000&&!(out&7);
    }
    bool typed(uintptr_t address,uintptr_t table)const{
        uintptr_t actual=0;return mem_read((void*)address,&actual,8)&&actual==base_+table;
    }
    bool raw(Raw& out)const{
        uintptr_t owner=0;
        return pointer(base_+0x3D6F820,out.menu)&&typed(out.menu,0x2A9EED8)&&
            pointer(out.menu+0x860,out.model)&&typed(out.model,0x2AD0078)&&
            pointer(out.model+8,out.view)&&typed(out.view,0x2AEAB38)&&
            pointer(out.view+0xB00,owner)&&owner==out.model&&
            mem_read((void*)(out.menu+0x798),&out.job,8)&&
            mem_read((void*)(out.view+0xB08),&out.active,1)&&out.active<=1&&
            pointer(base_+0x3D6BEB8,out.lua)&&pointer(out.lua+8,out.proxy)&&typed(out.proxy,0x2A63608)&&
            mem_read((void*)(out.proxy+0x88),&out.luaFlags,4)&&
            pointer(base_+0x3D72478,out.action)&&typed(out.action,0x2B48140)&&
            mem_read((void*)(out.action+0x29),&out.actionReady,1)&&out.actionReady<=1;
    }
public:
    bool init(uintptr_t base){
        base_=base;
        static const uint8_t menuGlobal[] = {0x48, 0x8B, 0x05, 0xA3, 0x9E, 0x4A, 0x03};
        static const uint8_t menuBlock[] = {0x48, 0x83, 0xB8, 0x98, 0x07, 0x00, 0x00, 0x00, 0x75, 0x59};
        static const uint8_t menuActive[] = {0xC6, 0x83, 0x08, 0x0B, 0x00, 0x00, 0x01, 0xEB, 0x07, 0xC6, 0x83, 0x08, 0x0B, 0x00, 0x00, 0x00};
        static const uint8_t menuModel[] = {0x48, 0x8B, 0x8B, 0x60, 0x08, 0x00, 0x00, 0x48, 0x8B, 0x01, 0xF3, 0x0F, 0x10, 0x4F, 0x08, 0xFF, 0x50, 0x08};
        static const uint8_t modelView[] = {0x48, 0x83, 0x79, 0x08, 0x00, 0x74, 0x03, 0x32, 0xC0, 0xC3, 0x48, 0x89, 0x51, 0x08, 0xB0, 0x01, 0xC3};
        static const uint8_t viewOwner[] = {0x49, 0x89, 0xB6, 0x00, 0x0B, 0x00, 0x00, 0x66, 0x41, 0xC7, 0x86, 0x08, 0x0B, 0x00, 0x00, 0x00, 0x00};
        static const uint8_t luaProxy[] = {0x48, 0x8B, 0x0D, 0x4A, 0x61, 0x88, 0x03, 0x48, 0x8B, 0x49, 0x08, 0xB8, 0x01, 0x00, 0x00, 0x00, 0x48, 0x85, 0xC9, 0x74, 0x2A, 0x8B, 0xF8, 0x89, 0x45, 0xA3, 0x8B, 0x81, 0x88, 0x00, 0x00, 0x00, 0xC1, 0xE8, 0x05, 0x40, 0x84, 0xC7};
        static const uint8_t restBegin[] = {0x83, 0x8F, 0x88, 0x00, 0x00, 0x00, 0x20};
        static const uint8_t restLoop[] = {0x8B, 0x92, 0x88, 0x00, 0x00, 0x00, 0x8B, 0xC2, 0xC1, 0xE8, 0x08, 0xA8, 0x01, 0x75, 0x44, 0x41, 0x81, 0x89, 0x88, 0x00, 0x00, 0x00, 0x80, 0x00, 0x00, 0x00};
        static const uint8_t restEnd[] = {0x8B, 0x82, 0x88, 0x00, 0x00, 0x00, 0x48, 0x8B, 0xFA, 0xC1, 0xE8, 0x07, 0xA8, 0x01, 0x74, 0x7D, 0x81, 0x8A, 0x88, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00};
        static const uint8_t returnTitle[] = {0x81,0x8A,0x88,0,0,0,0,0x08,0,0};
        static const uint8_t actionEnable[] = {0xC7, 0x43, 0x28, 0x01, 0x01, 0x01, 0x00};
        static const uint8_t actionBlock[] = {0x48, 0x8B, 0x06, 0x48, 0x8B, 0xCE, 0xFF, 0x90, 0x00, 0x06, 0x00, 0x00, 0x48, 0x8B, 0x10, 0x48, 0x8B, 0xC8, 0xFF, 0x52, 0x78, 0x84, 0xC0, 0x74, 0x0A, 0xC6, 0x43, 0x29, 0x00};
        enabled_=signature(base+0x8c5976,menuGlobal,sizeof(menuGlobal)) &&
            signature(base+0x8c59b0,menuBlock,sizeof(menuBlock)) &&
            signature(base+0x8c5a0a,menuActive,sizeof(menuActive)) &&
            signature(base+0x767890,menuModel,sizeof(menuModel)) &&
            signature(base+0x862a60,modelView,sizeof(modelView)) &&
            signature(base+0x8c56d5,viewOwner,sizeof(viewOwner)) &&
            signature(base+0x4e5d67,luaProxy,sizeof(luaProxy)) &&
            signature(base+0x59a84a,restBegin,sizeof(restBegin)) &&
            signature(base+0x59a8a7,restLoop,sizeof(restLoop)) &&
            signature(base+0x59a906,restEnd,sizeof(restEnd)) &&
            signature(base+0x59e706,returnTitle,sizeof(returnTitle)) &&
            signature(base+0xa6498f,actionEnable,sizeof(actionEnable)) &&
            signature(base+0xa64c82,actionBlock,sizeof(actionBlock));
        return enabled_;
    }
    HostInteractionSnapshot read(bool hostReady)const{
        Raw first={},again={};HostInteractionSnapshot out;
        if(!enabled_||!raw(first)||!raw(again)||!first.same(again))return out;
        out.flags=ERMC_INTERACTION_VALID;
        // The job pointer catches its opening before the UI's next update; the
        // active bit keeps the closing transition blocked for that update too.
        if(!first.active||first.job)out.flags|=ERMC_INTERACTION_MENU_BLOCKING;
        out.restFlags=first.luaFlags&ERMC_INTERACTION_REST_MASK;
        if(out.restFlags)out.flags|=ERMC_INTERACTION_RESTING;
        if(first.actionReady)out.flags|=ERMC_INTERACTION_ACTION_READY;
        if(hostReady)out.flags|=ERMC_INTERACTION_HOST_READY;
        // Conservative admission, not a claim that an animation end was seen.
        // All native bonfire phase flags must clear. Their live exit timing is
        // still to be tested; retained flags require manual F8 rather than guessing.
        if(hostReady&&first.actionReady&&!(out.flags&(ERMC_INTERACTION_MENU_BLOCKING|ERMC_INTERACTION_RESTING))&&
            // SDK LuaEventControlFlags.return_title_requested; actual 59E706
            // sets proxy+88 bit11 during RegistReturnTitle. Never return there.
            !(first.luaFlags&(1u<<11)))out.flags|=ERMC_INTERACTION_CAN_RESUME;
        return out;
    }
};
inline void publish_host_interaction(const HostInteractionSnapshot& value,uint64_t frame,uint32_t zone,uint64_t now){
    const auto* host=shm_header();
    auto* out=(ErmcHostInteraction*)((uint8_t*)shm_header()+ERMC_OFF_HOST_INTERACTION);
    const uint32_t writing=out->seq|1u;out->seq=writing;
    __asm__ __volatile__("" ::: "memory");
    out->magic=ERMC_HOST_INTERACTION_MAGIC;out->version=ERMC_HOST_INTERACTION_VERSION;
    out->flags=value.flags;out->restFlags=value.restFlags;
    out->sampleFrame=frame;out->sampleTimeMs=now;out->hostStartMs=host->hostStartMs;
    out->hostPid=host->hostPid;out->coreGeneration=host->coreGeneration;out->hostLife=host->hostLife;
    out->zone=zone;out->reserved=0;
    __asm__ __volatile__("" ::: "memory");
    out->seq=writing+1u;
}
} // namespace mb
