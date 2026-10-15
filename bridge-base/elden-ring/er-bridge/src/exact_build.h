#pragma once
#include <windows.h>
#include <bcrypt.h>
#include <array>
#include <cstring>
#include <vector>

namespace mb {
// Called once on initialization of the explicit collider experiment, never on a game tick.
inline bool exact_collider_executable(const wchar_t* path) {
    static const unsigned char expected[32]={0x1a,0x35,0x47,0x10,0x13,0x27,0xf6,0x5d,
        0x0c,0x76,0xda,0x2f,0x91,0x90,0xac,0x0a,0xa6,0x68,0x71,0xea,0x42,0xba,0xe2,0xae,
        0xcc,0x61,0xe1,0x1a,0x8b,0x59,0x78,0x91};
    HANDLE file=CreateFileW(path,GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,FILE_FLAG_SEQUENTIAL_SCAN,nullptr);
    if(file==INVALID_HANDLE_VALUE)return false;
    BCRYPT_ALG_HANDLE algorithm=nullptr;BCRYPT_HASH_HANDLE hash=nullptr;
    DWORD objectBytes=0,written=0,read=0;std::vector<unsigned char> object;
    std::array<unsigned char,65536> bytes{};unsigned char digest[32]{};bool ok=false;
    if(BCryptOpenAlgorithmProvider(&algorithm,BCRYPT_SHA256_ALGORITHM,nullptr,0)>=0 &&
        BCryptGetProperty(algorithm,BCRYPT_OBJECT_LENGTH,reinterpret_cast<PUCHAR>(&objectBytes),sizeof(objectBytes),&written,0)>=0 &&
        objectBytes>0 && objectBytes<1048576) {
        object.resize(objectBytes);
        if(BCryptCreateHash(algorithm,&hash,object.data(),objectBytes,nullptr,0,0)>=0) {
            ok=true;
            for(;;) {
                if(!ReadFile(file,bytes.data(),static_cast<DWORD>(bytes.size()),&read,nullptr)) {ok=false;break;}
                if(!read)break;
                if(BCryptHashData(hash,bytes.data(),read,0)<0) {ok=false;break;}
            }
            if(ok)ok=BCryptFinishHash(hash,digest,sizeof(digest),0)>=0 && !std::memcmp(digest,expected,sizeof(digest));
        }
    }
    if(hash)BCryptDestroyHash(hash);
    if(algorithm)BCryptCloseAlgorithmProvider(algorithm,0);
    CloseHandle(file);return ok;
}
inline bool exact_running_collider_executable() {
    wchar_t path[32768];DWORD length=GetModuleFileNameW(nullptr,path,32768);
    return length && length<32768 && exact_collider_executable(path);
}
}
