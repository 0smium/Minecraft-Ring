"""Prepare an offline save backup, query runes, or explicitly fund a B01 test.

No process-memory APIs, raw WRITE fallback, level changes, or automatic retries.
Default invocation queries the project's live native mailbox without awarding anything.
"""
from __future__ import annotations

import argparse
import contextlib
import ctypes
import hashlib
import json
import mmap
import os
from pathlib import Path
import secrets
import shutil
import struct
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
EXE_SHA256 = '1a3547101327f65d0c76da2f9190ac0aa66871ea42bae2aecc61e11a8b597891'
MAX_AMOUNT = 5_000_000
CMD, RESPONSE, COMMAND = 0x1000, 0x2000, 11
REQUEST = struct.Struct('<8I4Q')
RESULT = struct.Struct('<Iii7I3Q')
STATUS = {0: 'ok', -20: 'bad_request', -21: 'unsupported_signature', -22: 'not_alive',
          -23: 'session_changed', -24: 'owner_changed', -25: 'expired', -26: 'balance_changed_or_limit',
          -27: 'superseded', -28: 'readback_unknown', -29: 'award_cache_full'}


def sha256(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def running_hosts() -> list[dict]:
    import psutil
    hosts = []
    for proc in psutil.process_iter(['pid', 'name', 'exe', 'create_time']):
        if (proc.info['name'] or '').casefold() == 'eldenring.exe':
            if proc.info['create_time'] is None:
                raise ValueError('Cannot verify the Elden Ring process creation time')
            hosts.append({'pid': proc.pid, 'path': proc.info['exe'],
                          'started_ms': int(proc.info['create_time'] * 1000)})
    return hosts


def save_file(explicit: str | None) -> Path:
    if explicit:
        result = Path(explicit).resolve(strict=True)
        if result.name.casefold() != 'er0000.sl2':
            raise ValueError('--save-file must identify the actual ER0000.sl2 save')
        return result
    saves = sorted((Path(os.environ['APPDATA']) / 'EldenRing').glob('*/ER0000.sl2'))
    if len(saves) != 1:
        raise ValueError(f'Found {len(saves)} save accounts; specify the active account with --save-file')
    return saves[0].resolve(strict=True)


@contextlib.contextmanager
def save_read_lock(path: Path):
    """Deny concurrent writes/deletes while copying; refuse an existing writer."""
    if os.name != 'nt':
        raise RuntimeError('The save-backup mode requires Windows sharing semantics')
    import msvcrt
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.CreateFileW.argtypes = [ctypes.c_wchar_p, ctypes.c_uint32, ctypes.c_uint32,
                                  ctypes.c_void_p, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_void_p]
    kernel.CreateFileW.restype = ctypes.c_void_p
    handle = kernel.CreateFileW(str(path), 0x80000000, 1, None, 3, 0x80, None)
    if handle == ctypes.c_void_p(-1).value:
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        fd = msvcrt.open_osfhandle(handle, os.O_RDONLY | os.O_BINARY)
    except BaseException:
        kernel.CloseHandle.argtypes = [ctypes.c_void_p]
        kernel.CloseHandle(handle)
        raise
    with os.fdopen(fd, 'rb') as stream:
        yield stream


def make_backup(source: Path, output: Path) -> Path:
    if running_hosts():
        raise ValueError('Exit Elden Ring completely before --backup-only; no live save is copied')
    output.mkdir(parents=True, exist_ok=False)
    items = []
    sources = [source]
    companion = source.with_suffix(source.suffix + '.bak')
    if companion.exists():
        sources.append(companion)
    # Keeping the primary lock open until all checks complete prevents game save writes
    # during the copy. No manifest is issued if the game starts while copying.
    with save_read_lock(source) as primary:
        for current in sources:
            target = output / current.name
            if current == source:
                with target.open('xb') as dest:
                    shutil.copyfileobj(primary, dest)
            else:
                with save_read_lock(current) as reader, target.open('xb') as dest:
                    shutil.copyfileobj(reader, dest)
            items.append({'source': str(current), 'file': target.name,
                          'size': target.stat().st_size, 'sha256': sha256(target)})
        if running_hosts():
            raise ValueError('Elden Ring started during backup; backup is incomplete and cannot authorize funding')
    manifest = output / 'manifest.json'
    manifest.write_text(json.dumps({'schema': 1, 'purpose': 'erbridge-test-runes',
        'created_ms': int(time.time() * 1000), 'host_closed': True, 'files': items}, indent=2), encoding='utf-8')
    return manifest


def verify_backup(manifest: Path, source: Path, host: dict, now_ms: int) -> dict:
    manifest = manifest.resolve(strict=True)
    data = json.loads(manifest.read_text(encoding='utf-8'))
    if data.get('schema') != 1 or data.get('purpose') != 'erbridge-test-runes' or data.get('host_closed') is not True:
        raise ValueError('Expected the manifest produced by --backup-only while Elden Ring was closed')
    created = data.get('created_ms', 0)
    if not 0 < created < host['started_ms'] or not 0 <= now_ms - created <= 24 * 60 * 60 * 1000:
        raise ValueError('Backup must be from the last 24 hours and precede this Elden Ring process')
    found = False
    for item in data.get('files', []):
        name = item['file']
        if Path(name).name != name:
            raise ValueError('Backup manifest filename leaves the backup directory')
        path = (manifest.parent / name).resolve(strict=True)
        if path.parent != manifest.parent or path.stat().st_size != item['size'] or sha256(path) != item['sha256']:
            raise ValueError('Backup data differs from its manifest')
        if Path(item['source']).resolve() == source.resolve() and name.casefold() == 'er0000.sl2':
            found = path.stat().st_size > 0
    if not found:
        raise ValueError('Backup does not contain the selected active ER0000.sl2 save')
    return {'manifest': str(manifest), 'manifest_sha256': sha256(manifest), 'created_ms': created}


def session(shared) -> dict:
    magic, version, size = struct.unpack_from('<3I', shared)
    if (magic, version, size) != (0x434D484D, 1, 8 * 1024 * 1024):
        raise ValueError('Unsupported bridge mapping header')
    pid = struct.unpack_from('<I', shared, 0x20)[0]
    start = struct.unpack_from('<Q', shared, 0x28)[0]
    generation, status = struct.unpack_from('<Ii', shared, 0x40)
    life = struct.unpack_from('<I', shared, 0x58)[0]
    if not pid or not start or status != 1:
        raise ValueError('Native core is not running')
    return dict(pid=pid, host_start_ms=start, generation=generation, life=life)


def pack_request(host: dict, amount: int = 0, observed: dict | None = None,
                 *, now_ms: int | None = None, nonce: int | None = None) -> tuple[bytes, int]:
    if not isinstance(amount, int) or not 0 <= amount <= MAX_AMOUNT:
        raise ValueError('Test award must be between 1 and 5,000,000 runes (zero is query only)')
    observed = observed or {}
    if amount and not observed.get('fingerprint'):
        raise ValueError('Award requires a successful fresh owner query')
    now_ms = int(time.time() * 1000) if now_ms is None else now_ms
    nonce = nonce or (secrets.randbits(64) or 1)
    return REQUEST.pack(1, amount, host['pid'], host['generation'], host['life'],
        observed.get('level', 0), observed.get('after', 0), 0, host['host_start_ms'],
        observed.get('fingerprint', 0), now_ms + 3000, nonce), nonce


def decode_result(blob: bytes, nonce: int) -> dict:
    fields = RESULT.unpack(blob)
    names = ['version', 'status', 'actual_delta', 'before', 'after', 'level', 'life',
             'pid', 'generation', 'reserved', 'host_start_ms', 'fingerprint', 'nonce']
    out = dict(zip(names, fields))
    if out['version'] != 1 or out['reserved'] or out['nonce'] != nonce:
        raise ValueError('Mismatched response; outcome unknown, do not retry an award')
    out['result'] = STATUS.get(out['status'], f'unknown_status_{out["status"]}')
    return out


def transact(shared, host: dict, amount: int, observed: dict | None, audit) -> dict:
    if session(shared) != host:
        raise ValueError('Host session/life changed before submission')
    req, resp = struct.unpack_from('<2I', shared, CMD)
    if req != resp:
        raise ValueError('Developer mailbox is busy; do not overwrite or resubmit an unknown award')
    payload, nonce = pack_request(host, amount, observed)
    seq = (req + 1) & 0xFFFFFFFF
    audit('prepared', {'seq': seq, 'nonce': nonce, 'amount': amount, 'host': host,
                       'expected': observed, 'deadline_ms': struct.unpack_from('<Q', payload, 48)[0]})
    # Payload first, publication sequence last, matching the existing x86 mailbox protocol.
    shared[CMD + 24:CMD + 24 + len(payload)] = payload
    struct.pack_into('<II', shared, CMD + 8, COMMAND, len(payload))
    struct.pack_into('<I', shared, CMD, seq)
    deadline = time.monotonic() + 5.0
    while time.monotonic() < deadline:
        if session(shared) != host:
            raise RuntimeError('Host changed after submission: outcome unknown, do not automatically retry')
        if struct.unpack_from('<I', shared, CMD)[0] != seq:
            raise RuntimeError('Another writer replaced this request: outcome unknown, do not retry')
        if struct.unpack_from('<I', shared, CMD + 4)[0] == seq:
            status, size = struct.unpack_from('<iI', shared, CMD + 16)
            if size != RESULT.size:
                raise RuntimeError(f'Native command unsupported/invalid response ({status}, {size}); no fallback write')
            out = decode_result(bytes(shared[RESPONSE:RESPONSE + size]), nonce)
            if status != out['status']:
                raise RuntimeError('Inconsistent response: outcome unknown, do not retry')
            audit('result', out)
            if status != 0:
                raise RuntimeError(f'Native command refused or outcome unknown: {out["result"]}; no retry')
            if amount and (out['actual_delta'] != amount or out['after'] != out['before'] + amount):
                raise RuntimeError('Award readback differs: outcome unknown, do not retry')
            return out
        time.sleep(0.01)
    # Never reset reqSeq or resend. The native deadline rejects a pending request when
    # ticks resume; an already executing request must be treated as an unknown outcome.
    raise TimeoutError('No response: outcome unknown. Native pending requests expire; do not retry the award')


@contextlib.contextmanager
def tool_lock():
    import msvcrt
    path = ROOT / 'runtime/test-runes.lock'
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('a+b') as stream:
        if stream.tell() == 0:
            stream.write(b'\0'); stream.flush()
        stream.seek(0)
        msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
        try:
            yield
        finally:
            stream.seek(0)
            msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--amount', type=int, help='Explicit positive test award; at most 5,000,000')
    parser.add_argument('--backup-only', action='store_true', help='Back up the active save only while ER is fully closed')
    parser.add_argument('--backup', type=Path, help='Manifest from this round\'s --backup-only (required for an award)')
    parser.add_argument('--save-file', help='Active account ER0000.sl2; required if multiple accounts are present')
    args = parser.parse_args(argv)
    if os.name != 'nt':
        parser.error('This tool requires the local Windows project environment')
    if args.amount is not None and not 1 <= args.amount <= MAX_AMOUNT:
        parser.error('--amount must be in 1..5,000,000')
    if args.backup_only and (args.amount is not None or args.backup is not None):
        parser.error('--backup-only cannot submit an award')
    if args.amount is not None and args.backup is None:
        parser.error('--amount requires --backup pointing to the closed-game backup manifest')
    with tool_lock():
        if args.backup_only:
            source = save_file(args.save_file)
            output = ROOT / 'runtime/backups' / ('test-runes-' + time.strftime('%Y%m%d-%H%M%S') + '-' + secrets.token_hex(3))
            print(json.dumps({'backup_manifest': str(make_backup(source, output))}, indent=2))
            return
        hosts = running_hosts()
        if len(hosts) != 1 or not hosts[0]['path']:
            raise ValueError('Exactly one readable eldenring.exe process is required')
        process = hosts[0]
        installation = json.loads((ROOT / 'installation.json').read_text(encoding='utf-8-sig'))
        exe = (Path(installation['game_dir']) / 'eldenring.exe').resolve(strict=True)
        if Path(process['path']).resolve() != exe or sha256(exe) != EXE_SHA256:
            raise ValueError('Only the verified App 1.17.1 / exe 2.7.1.0 installation is supported')
        backup = verify_backup(args.backup, save_file(args.save_file), process, int(time.time()*1000)) if args.amount else None
        log_dir = ROOT / 'runtime/experiments/test-runes'
        log_dir.mkdir(parents=True, exist_ok=True)
        log_path = log_dir / (time.strftime('%Y%m%d-%H%M%S') + '-' + secrets.token_hex(3) + '.jsonl')
        with log_path.open('x', encoding='utf-8') as log:
            def audit(event, values):
                log.write(json.dumps({'at_ms': int(time.time()*1000), 'event': event, **values}) + '\n')
                log.flush(); os.fsync(log.fileno())
            audit('start', {'amount': args.amount or 0, 'backup': backup, 'exe_sha256': EXE_SHA256})
            try:
                with (ROOT / 'runtime/bridge.shm').open('r+b') as stream, mmap.mmap(stream.fileno(), 0) as shared:
                    host = session(shared)
                    if host['pid'] != process['pid'] or host['host_start_ms'] < process['started_ms'] - 1000:
                        raise ValueError('Stale bridge mapping or mismatched host process')
                    heartbeat = struct.unpack_from('<Q', shared, 0x10)[0]
                    time.sleep(.15)
                    if struct.unpack_from('<Q', shared, 0x10)[0] == heartbeat:
                        raise ValueError('Host heartbeat is not advancing')
                    observed = transact(shared, host, 0, None, audit)
                    outcome = transact(shared, host, args.amount, observed, audit) if args.amount else observed
                    print(json.dumps({'observation': outcome, 'log': str(log_path)}, indent=2))
            except BaseException as error:
                audit('error', {'message': str(error), 'automatic_retry': False})
                raise


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError) as error:
        print(f'test-runes: {error}', file=sys.stderr)
        sys.exit(1)
