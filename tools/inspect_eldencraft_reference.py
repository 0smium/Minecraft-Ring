"""Compare pinned EldenCraft collider entrypoint metadata with the local EXE on disk.

Read-only: does not open a process, load native code, start a game or install files.
Matching prefixes establish a research baseline, not working Havok integration.
Requires the existing pefile dependency and an external read-only reference checkout.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PIN = 'ba7591cdde9fb16e17552cfe53546f44d0fd856e'
EXE_SHA = '1a3547101327f65d0c76da2f9190ac0aa66871ea42bae2aecc61e11a8b597891'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', type=Path, default=ROOT.parent / 'EldenCraft-reference')
    parser.add_argument('--exe', type=Path)
    parser.add_argument('--output', type=Path, default=ROOT / 'runtime/experiments/eldencraft-compatibility.json')
    args = parser.parse_args()
    reference = args.reference.resolve(strict=True)
    revision = subprocess.check_output(['git', '-C', str(reference), 'rev-parse', 'HEAD'], text=True).strip()
    if revision != PIN:
        raise SystemExit('Reference checkout differs from the pinned commit')
    relative = 'native/src/native_colliders.rs'
    source = (reference / relative).read_bytes()
    committed = subprocess.check_output(['git', '-C', str(reference), 'show', f'{PIN}:{relative}'])
    if source.replace(b'\r\n', b'\n') != committed.replace(b'\r\n', b'\n'):
        raise SystemExit('Reference collider source has local changes')
    exe = args.exe
    if exe is None:
        install = json.loads((ROOT / 'installation.json').read_text(encoding='utf-8-sig'))
        exe = Path(install['game_dir']) / 'eldenring.exe'
    with exe.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if digest != EXE_SHA:
        raise SystemExit('Unsupported executable; no compatibility claim made')
    text = re.sub(r'//[^\n]*', '', source.decode('utf8'))
    section = re.search(r'const ENTRYPOINTS:.*?= &\[(.*?)\];', text, re.S)
    if section is None:
        raise SystemExit('Pinned entrypoint metadata missing')
    records = re.findall(r'\(\s*(0x[0-9a-f]+)\s*,\s*&\[(.*?)\],?\s*\)', section[1], re.S)
    if len(records) != 17:
        raise SystemExit(f'Expected 17 pinned entrypoints, found {len(records)}')
    import pefile
    pe = pefile.PE(str(exe), fast_load=True)
    try:
        checks = []
        for address, raw in records:
            expected = bytes(int(x, 16) for x in re.findall(r'0x([0-9a-f]+)', raw))
            checks.append({'rva': address, 'prefix_bytes': len(expected),
                           'matches': pe.get_data(int(address, 16), len(expected)) == expected})
    finally:
        pe.close()
    result = {'reference_commit': PIN, 'exe_sha256': digest, 'read_only': True,
              'checks': checks, 'all_match': all(c['matches'] for c in checks),
              'scope': 'Disk entrypoint metadata only; runtime worlds, filters, ownership and collision behavior untested'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf8')
    print(json.dumps(result, indent=2))
    if not result['all_match']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
