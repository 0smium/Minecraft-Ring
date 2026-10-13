"""Prepare the verified optional B01 skill-tree dependency. Does not launch either game."""
from hashlib import sha256
from pathlib import Path
from urllib.request import Request, urlopen
import os
import tempfile

ROOT = Path(__file__).resolve().parents[1]
NAME = "puffish_skills-0.19.2-1.21-fabric.jar"
URL = "https://cdn.modrinth.com/data/hqQqvaa4/versions/ppOVyisF/" + NAME
SHA256 = "276ce0b5f15bcfeafe632c8eabe0d2d3104b9b0362897e66fd049bcd217ba915"


def main():
    target = ROOT / "runtime/downloads" / NAME
    if target.is_file():
        if sha256(target.read_bytes()).hexdigest() != SHA256:
            raise SystemExit(f"Unexpected existing file; preserve/inspect it before retrying: {target}")
        print(f"Verified {target} SHA-256 {SHA256}")
        return
    request = Request(URL, headers={"User-Agent": "Minecraft-Ring/B01-dependency-preparation"})
    with urlopen(request, timeout=60) as response:
        data = response.read(2 * 1024 * 1024)
    if sha256(data).hexdigest() != SHA256:
        raise SystemExit("Downloaded Skills artifact does not match the pinned SHA-256")
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=target.parent, suffix=".tmp", delete=False) as output:
            temporary = Path(output.name)
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, target)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    print(f"Prepared {target} SHA-256 {SHA256}")


if __name__ == "__main__":
    main()
