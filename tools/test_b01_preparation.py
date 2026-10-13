"""Compile the actual B01 common sources and run simulated host checks in two JVMs.

Requires a full JDK 17+ (jdk.compiler); no Gradle, downloads or running games.
The Fabric production build retains its own Java toolchain and release target.
"""

import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "bridge-base/elden-ring/mc-bridge/src"


def java_path(java_home):
    executable = "java.exe" if os.name == "nt" else "java"
    if java_home:
        path = Path(java_home) / "bin" / executable
        if not path.is_file():
            raise SystemExit(f"Java executable not found: {path}")
        return str(path.resolve())
    bundled = sorted((ROOT / ".tools/java").glob(f"*/bin/{executable}"))
    if bundled:
        return str(bundled[-1])
    found = shutil.which(executable)
    if found:
        return found
    raise SystemExit("A full JDK 17+ is required. Set JAVA_HOME or pass --java-home.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    args = parser.parse_args()
    java = java_path(args.java_home)
    common = SOURCE / "main/java/dev/ermc/bridge"
    sources = sorted((common / "api").glob("*.java"))
    sources += sorted((common / "gameplay/progression").glob("*.java"))
    sources += sorted((common / "gameplay/capability").glob("*.java"))
    sources += sorted((SOURCE / "test/java/dev/ermc/bridge/preparation").glob("*.java"))
    with tempfile.TemporaryDirectory(prefix="ermc-b01-") as temporary:
        output = Path(temporary)
        classes = output / "classes"
        classes.mkdir()
        print(f"Compiling {len(sources)} real source/fixture files with {java}", flush=True)
        subprocess.run([java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "--release", "17",
                        "-encoding", "UTF-8", "-Xlint:all", "-Werror", "-d", str(classes),
                        *map(str, sources)], cwd=ROOT, check=True)
        command = [java, "-cp", str(classes), "dev.ermc.bridge.preparation.B01PreparationCheck",
                   str(output / "data")]
        subprocess.run(command, cwd=ROOT, check=True)
        subprocess.run([*command, "--restore"], cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
