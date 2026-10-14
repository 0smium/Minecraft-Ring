"""Read supported ER EXE RTTI from disk; never attach, launch, or modify a game.

Type presence is a research lead, not a verified field layout, active map shape,
mutable shape-key mask API, or proof of destructible terrain.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = '1a3547101327f65d0c76da2f9190ac0aa66871ea42bae2aecc61e11a8b597891'
TYPES = [
    ('V', 'hknpCompressedMeshShape'), ('V', 'hknpMaskedShape'),
    ('V', 'hknpMaskedCompoundShape'), ('V', 'hknpDestructionShapeProperties'),
    ('V', 'hknpHeightFieldShape'), ('V', 'hknpCompoundShape'),
    ('V', 'hknpMaterialPaletteShapeTagCodec'), ('V', 'fsnpCustomParamCompressedMeshShape'),
    ('U', 'hknpShapeKeyMask'), ('U', 'hknpCompoundShapeKeyMask'),
    ('U', 'KeyMask@hknpCompressedMeshShapeInternals'),
    ('V', 'CSWorldGeomHitIns@CS'), ('V', 'CSWorldGeomStaticIns@CS'),
    ('V', 'CSWorldGeomDynamicIns@CS'),
]


def inspect(exe):
    import pefile
    with exe.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if digest != EXPECTED:
        raise SystemExit('Unsupported executable: no class or offset compatibility claim made')
    pe = pefile.PE(str(exe), fast_load=True)
    try:
        image = pe.get_memory_mapped_image()
        base = pe.OPTIONAL_HEADER.ImageBase
        rows = []
        for kind, name in TYPES:
            name_rva = image.find(('.?A' + kind + name + '@@\0').encode())
            if name_rva < 16:
                rows.append({'name': name, 'present': False})
                continue
            descriptor = name_rva - 16
            locators, vtables, position = [], [], 0
            while True:
                hit = image.find(struct.pack('<I', descriptor), position)
                if hit < 0:
                    break
                locator, position = hit - 12, hit + 4
                if locator < 0 or locator + 24 > len(image):
                    continue
                if struct.unpack_from('<I', image, locator)[0] != 1 or struct.unpack_from('<I', image, locator + 20)[0] != locator:
                    continue
                locators.append(hex(locator))
                pointer, search = struct.pack('<Q', base + locator), 0
                while True:
                    address = image.find(pointer, search)
                    if address < 0:
                        break
                    if address % 8 == 0:
                        vtables.append(hex(address + 8))
                    search = address + 8
            rows.append({'name': name, 'present': True, 'type_descriptor_rva': hex(descriptor),
                         'locators': locators, 'candidate_vtable_rvas': vtables})
        return {'exe_sha256': digest, 'read_only': True, 'types': rows,
                'scope': 'Disk RTTI only. Candidate vtables and class names do not verify active map usage or a mutable-mask API.'}
    finally:
        pe.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--exe', type=Path)
    parser.add_argument('--output', type=Path, default=ROOT / 'runtime/experiments/mining-research/shape-types.json')
    args = parser.parse_args()
    exe = args.exe
    if exe is None:
        install = json.loads((ROOT / 'installation.json').read_text(encoding='utf-8-sig'))
        exe = Path(install['game_dir']) / 'eldenring.exe'
    result = inspect(exe)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(f'Observed {sum(row["present"] for row in result["types"])}/{len(TYPES)} RTTI type names in the supported EXE file')
    print(f'Evidence: {args.output}')
    print('LIMIT: no game, active map inspection, physics writes or digging behavior was exercised')


if __name__ == '__main__':
    main()
