"""Cache exact Fabric API coordinates from official Maven, with published SHA-1 checks.

This optional transport fallback does not change versions or TLS verification. Gradle
still uses the normal official repositories when this local cache is absent.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("versions", nargs="+")
parser.add_argument("--directory", type=Path, default=Path(__file__).parent / "build/fabric-maven")
parser.add_argument("--gradle-cache", type=Path)
args = parser.parse_args()
destination = args.directory.resolve()
namespace = {"m": "http://maven.apache.org/POM/4.0.0"}

def download(url, path):
    temporary = path.with_name(path.name + ".download")
    subprocess.run([
        "curl.exe", "--fail", "--silent", "--show-error", "--retry", "3", "--retry-all-errors",
        "--retry-delay", "1", "--connect-timeout", "15", "--max-time", "90",
        url, "-o", str(temporary),
    ], check=True)
    temporary.replace(path)

def artifact_file(group, artifact, version, extension):
    filename = f"{artifact}-{version}.{extension}"
    relative = Path(group.replace(".", "/")) / artifact / version / filename
    path = destination / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    checksum = path.with_name(path.name + ".sha1")
    if path.exists() and checksum.exists():
        expected = checksum.read_text().split()[0].lower()
        if hashlib.sha1(path.read_bytes()).hexdigest() == expected:
            return path
    if args.gradle_cache:
        cached = list((args.gradle_cache / group / artifact / version).glob(f"*/{filename}"))
        if cached:
            source = cached[0]
            digest = hashlib.sha1(source.read_bytes()).hexdigest()
            if digest == source.parent.name:
                shutil.copy2(source, path)
                checksum.write_text(digest + "\n", encoding="ascii")
                return path
    url = "https://maven.fabricmc.net/" + relative.as_posix()
    download(url + ".sha1", checksum)
    expected = checksum.read_text().split()[0].lower()
    candidate = path.with_name(path.name + ".candidate")
    download(url, candidate)
    if hashlib.sha1(candidate.read_bytes()).hexdigest() != expected:
        raise ValueError(f"Hash mismatch: {url}")
    candidate.replace(path)
    return path

def fetch(coordinate):
    group, artifact, version = coordinate
    pom = artifact_file(group, artifact, version, "pom")
    document = ET.parse(pom)
    if document.findtext("m:packaging", default="jar", namespaces=namespace) != "pom":
        artifact_file(group, artifact, version, "jar")
    dependencies = []
    for dependency in document.findall("m:dependencies/m:dependency", namespace):
        dependency_group = dependency.findtext("m:groupId", namespaces=namespace)
        if dependency_group == "net.fabricmc.fabric-api":
            dependencies.append((dependency_group,
                dependency.findtext("m:artifactId", namespaces=namespace),
                dependency.findtext("m:version", namespaces=namespace)))
    return dependencies

pending = {("net.fabricmc.fabric-api", "fabric-api", version) for version in args.versions}
visited = set()
with ThreadPoolExecutor(max_workers=4) as pool:
    while pending:
        batch = sorted(pending - visited)
        if not batch:
            break
        visited.update(batch)
        pending = set()
        for coordinate, dependencies in zip(batch, pool.map(fetch, batch)):
            print(":".join(coordinate), flush=True)
            pending.update(dependencies)
receipt = {"source": "https://maven.fabricmc.net/", "coordinates": sorted(":".join(c) for c in visited)}
(destination / "receipt.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
