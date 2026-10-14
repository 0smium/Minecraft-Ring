"""Consume production C++ HINV fixtures with the actual Java inventory codec; no games.

Run python tools/test_host_inventory.py first to produce the bounded native fixtures.
"""
from pathlib import Path
import subprocess
import tempfile
from test_b01_preparation import java_path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "bridge-base/elden-ring/mc-bridge/src"


def main():
    wire = ROOT / "runtime/experiments/native-inventory-wire-v1.bin"
    if not wire.is_file():
        raise SystemExit("Run python tools/test_host_inventory.py first")
    java = java_path(None)
    with tempfile.TemporaryDirectory(prefix="ermc-inventory-client-") as directory:
        classes = Path(directory)
        sources = [SRC / "main/java/dev/ermc/bridge/link" / (name + ".java")
                   for name in ("HostInventorySample", "HostProgressionSample", "HostEquipmentChannel", "HostEquipmentResult")]
        sources.append(SRC / "testFixtures/inventory/InventoryWireCheck.java")
        sources.append(SRC / "testFixtures/inventory/EquipmentWireCheck.java")
        subprocess.run([java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "--release", "17", "-encoding", "UTF-8",
                        "-Xlint:all", "-Werror", "-d", str(classes), *map(str, sources)], check=True)
        subprocess.run([java, "-cp", str(classes), "InventoryWireCheck", str(wire)], check=True)
        subprocess.run([java, "-cp", str(classes), "EquipmentWireCheck"], check=True)


if __name__ == "__main__":
    main()
