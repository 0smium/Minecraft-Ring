"""Prepare the official Meteor 0.5.8 production JAR and record its local SHA-256 pin.

Use --jar for an already downloaded release. No game is started. A first download
records its digest; it is not a pre-published, independently verified digest.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import struct
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
URL = "https://maven.meteordev.org/releases/meteordevelopment/meteor-client/0.5.8/meteor-client-0.5.8.jar"
CLASSES = {
    "meteordevelopment/meteorclient/systems/modules/combat/KillAura": {
        "methods": {("entityCheck", "(Lnet/minecraft/class_1297;)Z")},
        "references": set(),
    },
    "meteordevelopment/meteorclient/systems/modules/render/Nametags": {
        "methods": {("renderGenericLivingNametag", "(Lnet/minecraft/class_1309;Z)V")},
        "references": {
            ("net/minecraft/class_1299", "method_5897", "()Lnet/minecraft/class_2561;"),
            ("net/minecraft/class_1309", "method_6032", "()F"),
            ("net/minecraft/class_1309", "method_6063", "()F"),
            ("net/minecraft/class_1309", "method_6067", "()F"),
            ("java/lang/String", "valueOf", "(I)Ljava/lang/String;"),
        },
    },
    "meteordevelopment/meteorclient/utils/entity/SortPriority": {
        "methods": {("sortHealth", "(Lnet/minecraft/class_1297;Lnet/minecraft/class_1297;)I")},
        "references": {("net/minecraft/class_1309", "method_6032", "()F")},
    },
}


def class_symbols(data):
    """Read method signatures and constant-pool member refs; not a Mixin runtime verifier."""
    stream = io.BytesIO(data)

    def read(n):
        value = stream.read(n)
        if len(value) != n:
            raise ValueError("Truncated class file")
        return value

    def u2():
        return struct.unpack(">H", read(2))[0]

    def u4():
        return struct.unpack(">I", read(4))[0]

    if u4() != 0xCAFEBABE:
        raise ValueError("Invalid class file")
    read(4)
    pool = [None] * u2()
    i = 1
    while i < len(pool):
        tag = read(1)[0]
        if tag == 1:
            pool[i] = read(u2()).decode("utf-8", errors="replace")
        elif tag in (7, 8, 16, 19, 20):
            pool[i] = (tag, u2())
        elif tag in (9, 10, 11, 12, 17, 18):
            pool[i] = (tag, u2(), u2())
        elif tag in (3, 4):
            read(4)
        elif tag in (5, 6):
            read(8)
            i += 1
        elif tag == 15:
            read(3)
        else:
            raise ValueError(f"Unknown constant-pool tag {tag}")
        i += 1
    read(6)
    read(2 * u2())

    def members():
        result = set()
        for _ in range(u2()):
            read(2)
            name, descriptor = u2(), u2()
            result.add((pool[name], pool[descriptor]))
            for _ in range(u2()):
                read(2)
                read(u4())
        return result

    members()
    methods = members()
    references = set()
    for entry in pool:
        if isinstance(entry, tuple) and entry[0] in (9, 10, 11):
            owner, member = pool[entry[1]], pool[entry[2]]
            references.add((pool[owner[1]], pool[member[1]], pool[member[2]]))
    return methods, references


def inspect_jar(data):
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        metadata = json.loads(jar.read("fabric.mod.json"))
        if metadata.get("id") != "meteor-client" or metadata.get("version") != "0.5.8":
            raise ValueError("Expected the Meteor Client 0.5.8 release")
        versions = metadata.get("depends", {}).get("minecraft", [])
        if isinstance(versions, str):
            versions = [versions]
        if "1.21.1" not in versions:
            raise ValueError("Release metadata does not explicitly include Minecraft 1.21.1")
        for name, expected in CLASSES.items():
            methods, references = class_symbols(jar.read(name + ".class"))
            if not expected["methods"] <= methods or not expected["references"] <= references:
                raise ValueError(f"Unsupported production API/mapping in {name}; named/dev JARs are not accepted")
        return {
            "schema": 1, "id": "meteor-client", "version": "0.5.8", "minecraft": "1.21.1",
            "sha256": hashlib.sha256(data).hexdigest(), "size": len(data),
            "custom_commit": metadata.get("custom", {}).get("meteor-client:commit", ""),
            "api_preflight": "method signatures and constant-pool references; runtime Mixin audit still required",
        }


def atomic_write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary = tempfile.mkstemp(prefix=path.name, suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(handle, "wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, help="Existing official release JAR instead of downloading")
    parser.add_argument("--expected-sha256", help="Previously recorded SHA-256, if available")
    parser.add_argument("--replace", action="store_true", help="Explicitly replace a different local artifact pin")
    args = parser.parse_args()
    target = ROOT / "runtime/downloads/meteor-client-0.5.8.jar"
    lock = target.with_suffix(".lock.json")
    if args.jar:
        data = args.jar.read_bytes()
        source = "local release JAR"
    else:
        with urllib.request.urlopen(URL, timeout=30) as response:
            data = response.read(128 * 1024 * 1024 + 1)
        if len(data) > 128 * 1024 * 1024:
            raise ValueError("Unexpectedly large Meteor artifact")
        source = URL
    metadata = inspect_jar(data)
    if args.expected_sha256 and metadata["sha256"] != args.expected_sha256.lower():
        raise ValueError("Artifact differs from --expected-sha256")
    if lock.exists() and not args.replace:
        previous = json.loads(lock.read_text(encoding="utf-8"))
        if previous["sha256"] != metadata["sha256"]:
            raise ValueError("Existing Meteor pin differs; inspect the new artifact before using --replace")
    metadata["source"] = source
    atomic_write(target, data)
    atomic_write(lock, (json.dumps(metadata, indent=2) + "\n").encode())
    print(f"Prepared Meteor 0.5.8: {target}\nSHA-256: {metadata['sha256']}")
    print("API preflight passed. Production launch, Mixin application and gameplay remain unverified.")


if __name__ == "__main__":
    main()
