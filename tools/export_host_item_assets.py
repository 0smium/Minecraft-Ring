# SPDX-License-Identifier: GPL-3.0-or-later
"""Export local ER item names and 64px original icons; never write the game installation.

Requires Python 3.13+, soulstruct==2.6.0, pycryptodome and Pillow. The optional
Soulstruct install may live in .tools/python-host-assets. All output stays under
runtime/host-assets, outside Git. Data0 is indexed and sought, not fully unpacked.
Format references: Ekey/ER.BDT.Tool and Grimrukh/soulstruct (see documentation).
"""
from pathlib import Path
import argparse
import hashlib
import io
import json
import re
import struct
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / ".tools/python-host-assets"))

# Public archive verification key embedded in ER, not a user credential.
DATA0_KEY = b"""-----BEGIN RSA PUBLIC KEY-----
MIIBCwKCAQEA9Rju2whruXDVQZpfylVEPeNxm7XgMHcDyaaRUIpXQE0qEo+6Y36L
P0xpFvL0H0kKxHwpuISsdgrnMHJ/yj4S61MWzhO8y4BQbw/zJehhDSRCecFJmFBz
3I2JC5FCjoK+82xd9xM5XXdfsdBzRiSghuIHL4qk2WZ/0f/nK5VygeWXn/oLeYBL
jX1S8wSSASza64JXjt0bP/i6mpV2SLZqKRxo7x2bIQrR1yHNekSF2jBhZIgcbtMB
xjCywn+7p954wjcfjxB5VWaZ4hGbKhi1bhYPccht4XnGhcUTWO3NmJWslwccjQ4k
sutLq3uRjLMM0IeTkQO6Pv8/R7UNFtdCWwIERzH8IQ==
-----END RSA PUBLIC KEY-----"""


def path_hash(name):
    result = 0
    for char in name.lower().replace("\\", "/"):
        result = (result * 133 + ord(char)) & 0xffffffffffffffff
    return result


class Archive:
    def __init__(self, game):
        from Crypto.PublicKey import RSA
        raw = (game / "Data0.bhd").read_bytes()
        key = RSA.import_key(DATA0_KEY)
        block = key.size_in_bytes()
        if len(raw) % block:
            raise ValueError("Unsupported Data0 archive header length")
        self.header = b"".join(pow(int.from_bytes(raw[i:i+block], "big"), key.e, key.n)
                               .to_bytes(block-1, "big") for i in range(0, len(raw), block))
        if self.header[:8] != b"BHD5\xff\x01\0\0":
            raise ValueError("Unsupported Data0 archive format")
        self.game = game
        self.entries = {}
        count, offset = struct.unpack_from("<ii", self.header, 16)
        if not 0 < count < 100000 or offset < 24 or offset + 8*count > len(self.header):
            raise ValueError("Invalid archive bucket table")
        for i in range(count):
            size, at = struct.unpack_from("<ii", self.header, offset + i*8)
            if size < 0 or at < 0 or at + 40*size > len(self.header):
                raise ValueError("Invalid archive entry table")
            for j in range(size):
                h, padded, length, start, sha, aes = struct.unpack_from("<Qiiqqq", self.header, at+j*40)
                if h in self.entries:
                    raise ValueError("Ambiguous archive file hash")
                self.entries[h] = (padded, length, start, aes)

    def entry(self, path):
        return self.entries.get(path_hash(path))

    def read(self, path):
        from Crypto.Cipher import AES
        value = self.entry(path)
        if value is None:
            return None
        padded, length, start, aes = value
        if not 0 <= length <= padded <= 128*1024*1024 or start < 0:
            raise ValueError("Asset exceeds the bounded export reader")
        with (self.game / "Data0.bdt").open("rb") as stream:
            stream.seek(start)
            data = bytearray(stream.read(padded))
        if len(data) != padded:
            raise ValueError("Truncated game archive")
        if aes:
            key = self.header[aes:aes+16]
            ranges = struct.unpack_from("<i", self.header, aes+16)[0]
            if not 0 <= ranges <= 10000 or aes+20+ranges*16 > len(self.header):
                raise ValueError("Invalid asset encryption ranges")
            cipher = AES.new(key, AES.MODE_ECB)
            for i in range(ranges):
                begin, end = struct.unpack_from("<qq", self.header, aes+20+i*16)
                if begin == end or begin == -1 or end == -1:
                    continue
                if begin < 0 or end > padded or (end-begin) % 16:
                    raise ValueError("Invalid encrypted range")
                data[begin:end] = cipher.decrypt(data[begin:end])
        return bytes(data[:length] if length else data)


def read_names(archive, language):
    from soulstruct.containers import Binder
    from soulstruct.base.text.fmg import FMG
    result = {kind: {} for kind in ("weapon", "armor", "talisman", "goods", "gem")}
    # Original binder IDs are stable across languages; DLC has separate ID groups.
    ids = {10: "goods", 11: "weapon", 12: "armor", 13: "talisman", 35: "gem"}
    for suffix in ("", "_dlc01", "_dlc02"):
        data = archive.read(f"/msg/{language}/item{suffix}.msgbnd.dcx")
        if data is None:
            continue
        binder = Binder.from_bytes(data)
        for entry in binder.entries:
            kind = ids.get(entry.entry_id)
            # Use known name-file stems as well; never ingest captions/descriptions as names.
            stem = entry.path.rsplit("\\", 1)[-1].lower()
            for pattern, candidate in (("weaponname", "weapon"), ("protectorname", "armor"),
                                       ("accessoryname", "talisman"), ("goodsname", "goods"), ("gemname", "gem")):
                if pattern in stem:
                    kind = candidate
            if kind:
                result[kind].update({k: v for k, v in FMG.from_bytes(entry).entries.items() if v})
    return result


def read_items(game, names):
    from Crypto.Cipher import AES
    from soulstruct.containers import Binder
    from soulstruct.base.params.param import TypedParam
    from soulstruct.eldenring.params import paramdef
    raw = (game / "regulation.bin").read_bytes()
    key = bytes.fromhex("99bffc366a6bc8c6f5827d093602d676c42892a01c207fb024d3af4e493fef99")
    binder = Binder.from_bytes(AES.new(key, AES.MODE_CBC, raw[:16]).decrypt(raw[16:]))
    types = [
        ("EquipParamWeapon", "EQUIP_PARAM_WEAPON_ST", "weapon", 0, "WeaponIcon"),
        ("EquipParamProtector", "EQUIP_PARAM_PROTECTOR_ST", "armor", 1, "MaleIcon"),
        ("EquipParamAccessory", "EQUIP_PARAM_ACCESSORY_ST", "talisman", 2, "MenuIcon"),
        ("EquipParamGoods", "EQUIP_PARAM_GOODS_ST", "goods", 4, "GoodIcon"),
        ("EquipParamGem", "EQUIP_PARAM_GEM_ST", "gem", 8, "IconId"),
    ]
    items = []
    for stem, row_type, kind, category, icon_field in types:
        entry = next(e for e in binder.entries if e.path.replace("\\", "/").endswith("/"+stem+".param"))
        param = TypedParam(getattr(paramdef, row_type)).from_bytes(entry)
        for item_id, row in param.rows.items():
            if item_id < 0 or item_id >= 0x10000000:
                continue
            # Reinforced weapons often share the base text and icon; preserve the actual raw ID.
            base_id = item_id - item_id % 100 if category == 0 else item_id
            name = names[kind].get(item_id) or names[kind].get(base_id)
            if not name or name.strip() == "[ERROR]":
                continue  # Unknown/unavailable text remains an explicit ID fallback in the menu.
            icon = int(getattr(row, icon_field))
            armor_slot = 12 + int(row.ArmorType) if category == 1 else -1
            if armor_slot != -1 and not 12 <= armor_slot <= 15:
                armor_slot = -1
            items.append({"id": category << 28 | item_id, "name": name[:256], "icon": icon,
                          "armorSlot": armor_slot})
        print(f"Read {stem}: {len(param.rows)} rows", flush=True)
    return items


def export_icons(archive, output, desired):
    from soulstruct.containers import Binder, TPF
    from soulstruct.containers.entry import BinderEntry
    from soulstruct.utilities.binary import BinaryReader
    from PIL import Image
    header = archive.read("/menu/hi/00_solo.tpfbhd")
    data_entry = archive.entry("/menu/hi/00_solo.tpfbdt")
    if header is None or data_entry is None or data_entry[3] != 0:
        raise ValueError("Unsupported icon container; no whole-game unpack fallback")
    _, entries = Binder._read_header_v4(BinaryReader(header))
    icons = output / "icons"
    icons.mkdir(parents=True, exist_ok=True)
    exported = set()
    with (archive.game / "Data0.bdt").open("rb") as stream:
        for entry in entries:
            match = re.search(r"MENU_Knowledge_(\d+)", entry.path, re.I)
            if not match or int(match[1]) not in desired:
                continue
            icon = int(match[1])
            if not 0 <= entry.data_offset < data_entry[0] or not 0 < entry.compressed_size <= 16*1024*1024:
                raise ValueError("Invalid individual icon entry")
            stream.seek(data_entry[2] + entry.data_offset)
            data = stream.read(entry.compressed_size)
            if len(data) != entry.compressed_size:
                raise ValueError("Truncated icon")
            tpf = TPF.from_bytes(BinderEntry(data=data, entry_id=entry.entry_id, path=entry.path, flags=entry.flags))
            if len(tpf.textures) != 1:
                raise ValueError("Unexpected multi-texture item icon")
            image = Image.open(io.BytesIO(tpf.textures[0].data)).convert("RGBA")
            image.thumbnail((64, 64), Image.Resampling.LANCZOS)
            canvas = Image.new("RGBA", (64, 64))
            canvas.alpha_composite(image, ((64-image.width)//2, (64-image.height)//2))
            canvas.save(icons / f"{icon}.png")
            exported.add(icon)
    return exported


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--game-dir", type=Path)
    parser.add_argument("--language", choices=("zhocn", "engus"), default="zhocn")
    args = parser.parse_args()
    game = args.game_dir or Path(json.loads((ROOT / "installation.json").read_text(encoding="utf-8-sig"))["game_dir"])
    output = ROOT / "runtime/host-assets"
    output.mkdir(parents=True, exist_ok=True)
    from soulstruct.dcx import oodle
    oodle.LOAD_DLL(str(game / "oo2core_6_win64.dll"))
    archive = Archive(game)
    names = read_names(archive, args.language)
    print("Names:", {key: len(value) for key, value in names.items()}, flush=True)
    items = read_items(game, names)
    if not items:
        raise ValueError("No item metadata; existing export was not replaced")
    exported = export_icons(archive, output, {i["icon"] for i in items if i["icon"] > 0})
    manifest = {"schema": 1, "language": args.language,
                "sourceRegulationSha256": hashlib.sha256((game / "regulation.bin").read_bytes()).hexdigest(),
                "sourceData0HeaderSha256": hashlib.sha256((game / "Data0.bhd").read_bytes()).hexdigest(),
                "items": items, "exportedIcons": len(exported)}
    temporary = output / "items.json.tmp"
    temporary.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(output / "items.json")
    print(f"Exported {len(items)} item records / {len(exported)} original icons to {output}")
    print("Game files unchanged; images and text stay local and are not included in Git or the JAR.")


if __name__ == "__main__":
    main()
