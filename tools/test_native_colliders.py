"""Disk-verify native collider entrypoints and run the actual C++ driver with private memory.

Never opens a game process, launches Elden Ring, or executes its native functions.
"""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[1]
ER=ROOT/'bridge-base/elden-ring/er-bridge'


def main():
    import pefile
    install=json.loads((ROOT/'installation.json').read_text(encoding='utf-8-sig'))
    exe=Path(install['game_dir'])/'eldenring.exe'
    with exe.open('rb') as f:
        assert hashlib.file_digest(f,'sha256').hexdigest()=='1a3547101327f65d0c76da2f9190ac0aa66871ea42bae2aecc61e11a8b597891'
    pe=pefile.PE(str(exe),fast_load=True)
    source=(ER/'src/native_colliders_signatures.h').read_text()
    checks=re.findall(r'\{(0x[0-9a-f]+), (\d+), \{([^}]+)\}\}',source)
    assert len(checks)==29
    for address,length,values in checks:
        expected=bytes(int(v.strip(),16) for v in values.split(','))
        assert len(expected)==int(length)
        assert pe.get_data(int(address,16),len(expected))==expected,address
    import struct
    image=pe.get_memory_mapped_image()
    tables=[(0x2B934E8,'CSHavokManImp@CS'),(0x2B96978,'CSPhysWorld@CS'),(0x2EF0DD8,'hknpWorld'),
        (0x2B95260,'CsHkCharacterProxy@CS'),(0x2EF22F8,'hknpConstraintCollisionFilter'),(0x2B94DF0,'CSCollisionFilter@CS'),
        (0x2A82110,'GameMan@CS'),(0x2B9D1B8,'CSSessionManagerImp@CS')]
    for table,name in tables:
        locator=struct.unpack_from('<Q',image,table-8)[0]-pe.OPTIONAL_HEADER.ImageBase
        descriptor=struct.unpack_from('<I',image,locator+12)[0]
        actual=image[descriptor+16:image.find(b'\0',descriptor+16)]
        assert name.encode() in actual,(hex(table),actual,name)
    print(f'PASS: supported EXE SHA, {len(checks)} exact code/data spans including offline/session and post-physics gates, {len(tables)} Havok/game/session RTTI identities',flush=True)
    compiler=next((ROOT/'.tools/compiler').glob('*/bin/clang++.exe'))
    with tempfile.TemporaryDirectory(prefix='ermc-colliders-') as directory:
        work=Path(directory)
        (work/'windows.h').write_text('#pragma once\nusing HWND=void*;using LONG=long;extern unsigned fakeThread;inline unsigned GetCurrentThreadId(){return fakeThread;}inline LONG InterlockedIncrement(volatile LONG* p){return ++*p;}inline LONG InterlockedDecrement(volatile LONG* p){return --*p;}\n')
        binary=work/'check.exe'
        subprocess.run([str(compiler),'-std=c++17','-O2','-static','-Wall','-Wextra','-Werror','-Wframe-larger-than=16384',
            f'-I{work}',f'-I{ER/"include"}',f'-I{ER/"src"}',str(ER/'tests/native_colliders.cpp'),'-o',str(binary)],check=True)
        subprocess.run([str(binary)],check=True)
        # Exercise the actual Windows BCrypt gate against files only. The game
        # executable is never loaded as a module or launched as a process.
        probe=work/'hash.cpp'
        probe.write_text('#include "exact_build.h"\n#include <cassert>\n#include <cstdio>\n'
            'int wmain(int argc,wchar_t** argv){assert(argc==4);'
            'assert(mb::exact_collider_executable(argv[1]));'
            'assert(!mb::exact_collider_executable(argv[2]));'
            'assert(!mb::exact_collider_executable(argv[3]));'
            'puts("PASS: actual BCrypt gate accepts the supported EXE file and rejects wrong/missing files");}\n')
        hash_binary=work/'hash.exe'
        subprocess.run([str(compiler),'-std=c++17','-O2','-static','-municode',f'-I{ER/"src"}',
            str(probe),'-lbcrypt','-o',str(hash_binary)],check=True)
        subprocess.run([str(hash_binary),str(exe),str(probe),str(work/'missing.exe')],check=True)


if __name__=='__main__':main()
