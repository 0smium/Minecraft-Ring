#!/usr/bin/env python3
"""Inspect vanilla 1.21.1 feature data without launching Minecraft or writing a world."""

import argparse
import hashlib
import json
from pathlib import Path
import zipfile


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_JAR = ROOT / (
    ".tools/gradle-home/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-common/"
    "1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2/"
    "minecraft-common-1.21.1-loom.mappings.1_21_1.layered+hash.2198-v2.jar"
)


def inspect(jar: Path) -> dict:
    with jar.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    with zipfile.ZipFile(jar) as archive:
        def read(name: str) -> dict:
            return json.loads(archive.read(name))

        version = read("version.json")
        if version.get("id") != "1.21.1":
            raise ValueError("This investigation is pinned to Minecraft 1.21.1")
        result = {
            "minecraft": version["id"],
            "jar_sha256": digest,
            "configured_trees": {},
            "placed_features": {},
            "biome_vegetation": {},
        }
        for tree in ("oak", "birch"):
            feature = read(f"data/minecraft/worldgen/configured_feature/{tree}.json")
            config = feature["config"]
            trunk = config["trunk_placer"]
            result["configured_trees"][tree] = {
                "type": feature["type"],
                "trunk": trunk,
                "trunk_height_range": [trunk["base_height"], trunk["base_height"]
                    + trunk["height_rand_a"] + trunk["height_rand_b"]],
                "foliage": config["foliage_placer"],
                "leaf_state": config["foliage_provider"]["state"],
                "soil": config["dirt_provider"],
                "decorators": config["decorators"],
            }
        for name in ("oak_checked", "birch_checked", "trees_plains", "trees_birch"):
            result["placed_features"][name] = read(f"data/minecraft/worldgen/placed_feature/{name}.json")
        # GenerationStep.Decoration.VEGETAL_DECORATION is index 9 in the pinned version.
        for biome in ("plains", "forest", "birch_forest"):
            result["biome_vegetation"][biome] = read(
                f"data/minecraft/worldgen/biome/{biome}.json")["features"][9]
        result["plains_tree_selector"] = read(
            "data/minecraft/worldgen/configured_feature/trees_plains.json")
        return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=DEFAULT_JAR)
    args = parser.parse_args()
    print(json.dumps(inspect(args.jar), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
