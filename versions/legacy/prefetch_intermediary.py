"""Optional exact Fabric intermediary transport cache; every file uses the official SHA-1."""
import hashlib
from pathlib import Path
import subprocess
import sys

target = Path(__file__).parent / "build/fabric-maven/net/fabricmc/intermediary"
for version in sys.argv[1:]:
    if version not in {f"1.21.{i}" for i in range(1, 9)}:
        raise ValueError(f"Unsupported Minecraft target: {version}")
    folder = target / version
    folder.mkdir(parents=True, exist_ok=True)
    for suffix in (".pom", "-v2.jar"):
        name = f"intermediary-{version}{suffix}"
        url = f"https://maven.fabricmc.net/net/fabricmc/intermediary/{version}/{name}"
        path = folder / name
        checksum = folder / (name + ".sha1")
        for remote, output in ((url + ".sha1", checksum), (url, path)):
            candidate = output.with_name(output.name + ".download")
            subprocess.run(["curl.exe", "--http1.1", "-fsSL", "--retry", "4", "--retry-all-errors",
                            "--retry-delay", "2", "--max-time", "90", remote, "-o", str(candidate)], check=True)
            if output == path:
                expected = checksum.read_text().split()[0].lower()
                if hashlib.sha1(candidate.read_bytes()).hexdigest() != expected:
                    raise ValueError(f"SHA-1 mismatch: {url}")
            candidate.replace(output)
        print(version, name, "official SHA-1 verified", flush=True)
