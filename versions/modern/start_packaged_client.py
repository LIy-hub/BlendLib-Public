"""Launch an exact release JAR through Fabric's production KnotClient in a fresh profile.

Uses official Mojang/Fabric launch metadata and verified cached libraries. It never
joins a world or server. The caller observes the log/process and closes only this PID.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--minecraft", required=True)
parser.add_argument("--runtime", required=True, type=Path)
parser.add_argument("--fabric-api", required=True, type=Path)
parser.add_argument("--java", required=True, type=Path)
parser.add_argument("--directory", required=True, type=Path)
parser.add_argument("--gradle-home", required=True, type=Path)
parser.add_argument("--loader", default="0.19.3")
args = parser.parse_args()
directory = args.directory.resolve()
if directory.exists():
    raise SystemExit(f"Refusing to overwrite an existing client profile: {directory}")
runtime = args.runtime.resolve(strict=True)
with zipfile.ZipFile(runtime) as archive:
    metadata = json.loads(archive.read("fabric.mod.json"))
assert metadata["id"] == "blendlib" and metadata["depends"]["minecraft"] == args.minecraft
directory.mkdir(parents=True)
(directory / "mods").mkdir()
(directory / "natives").mkdir()
shutil.copy2(runtime, directory / "mods" / runtime.name)
shutil.copy2(args.fabric_api, directory / "mods" / args.fabric_api.name)
cache = args.gradle_home.resolve() / "caches"
loom = cache / "fabric-loom"
game = json.loads((loom / args.minecraft / "mojang_minecraft_info.json").read_text(encoding="utf-8"))
downloads = directory.parent / "client-dependencies"
downloads.mkdir(exist_ok=True)

def download(url, path, sha1=None):
    if path.exists() and (sha1 is None or hashlib.sha1(path.read_bytes()).hexdigest() == sha1):
        return path
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".part")
    subprocess.run(["curl.exe", "--fail", "--silent", "--show-error", "--retry", "3",
        "--retry-all-errors", "--retry-delay", "1", "--connect-timeout", "15", "--max-time", "120",
        url, "-o", str(temporary)], check=True)
    if sha1 and hashlib.sha1(temporary.read_bytes()).hexdigest() != sha1:
        raise ValueError(f"SHA1 mismatch: {url}")
    temporary.replace(path)
    return path

profile_path = download(
    f"https://meta.fabricmc.net/v2/versions/loader/{args.minecraft}/{args.loader}/profile/json",
    downloads / f"fabric-{args.minecraft}-{args.loader}.json")
profile = json.loads(profile_path.read_text(encoding="utf-8"))

def allowed(library):
    rules = library.get("rules", [])
    result = not rules
    for rule in rules:
        os_rule = rule.get("os", {})
        matches = os_rule.get("name", "windows") == "windows"
        matches &= os_rule.get("arch", "x86_64") in ("x86_64", "amd64")
        if matches:
            result = rule["action"] == "allow"
    return result

def library_path(library):
    group, artifact, version, *classifier = library["name"].split(":")
    filename = f"{artifact}-{version}" + (f"-{classifier[0]}" if classifier else "") + ".jar"
    relative = Path(group.replace(".", "/")) / artifact / version / filename
    details = library.get("downloads", {}).get("artifact", {})
    sha1 = details.get("sha1", library.get("sha1"))
    for candidate in (cache / "modules-2/files-2.1" / group / artifact / version).glob(f"*/{filename}"):
        actual = hashlib.sha1(candidate.read_bytes()).hexdigest()
        if actual == (sha1 or candidate.parent.name):
            return candidate
    url = details.get("url", library.get("url", "https://libraries.minecraft.net/") + relative.as_posix())
    return download(url, downloads / "libraries" / relative, sha1)

# Loader-provided ASM takes priority over a game's older transitive ASM.
libraries = {}
for library in game["libraries"] + profile["libraries"]:
    if allowed(library):
        pieces = library["name"].split(":")
        key = (pieces[0], pieces[1], tuple(pieces[3:]))
        libraries[key] = library
with ThreadPoolExecutor(max_workers=4) as pool:
    classpath = list(pool.map(library_path, libraries.values()))
for library in classpath:
    if "natives-windows" in library.name:
        with zipfile.ZipFile(library) as archive:
            for entry in archive.namelist():
                if entry.lower().endswith(".dll"):
                    (directory / "natives" / Path(entry).name).write_bytes(archive.read(entry))

client_jar = loom / args.minecraft / "minecraft-client.jar"
assert hashlib.sha1(client_jar.read_bytes()).hexdigest() == game["downloads"]["client"]["sha1"]
classpath.append(client_jar)
assets = loom / "assets"
asset_key = f"{args.minecraft}-{game['assetIndex']['id']}"
index = download(game["assetIndex"]["url"], assets / "indexes" / f"{asset_key}.json", game["assetIndex"]["sha1"])
objects = json.loads(index.read_text(encoding="utf-8"))["objects"]
missing = {item["hash"]: item for item in objects.values() if not (
    assets / "objects" / item["hash"][:2] / item["hash"]).exists()}

def fetch_asset(item):
    digest = item["hash"]
    return download(f"https://resources.download.minecraft.net/{digest[:2]}/{digest}",
        assets / "objects" / digest[:2] / digest, digest)

with ThreadPoolExecutor(max_workers=4) as pool:
    list(pool.map(fetch_asset, missing.values()))
arguments = ["-Xms256m", "-Xmx1G", "-Dfile.encoding=UTF-8", "--enable-native-access=ALL-UNNAMED",
    f"-Djava.library.path={directory / 'natives'}", "-Dminecraft.launcher.brand=BlendLibSmoke",
    "-Dminecraft.launcher.version=1"]
arguments += profile.get("arguments", {}).get("jvm", [])
arguments += ["-cp", os.pathsep.join(map(str, classpath)), profile["mainClass"],
    "--username", "BlendLibSmoke", "--version", args.minecraft, "--gameDir", str(directory),
    "--assetsDir", str(assets), "--assetIndex", asset_key,
    "--uuid", "00000000000000000000000000000001", "--accessToken", "0",
    "--userType", "legacy", "--versionType", "release", "--width", "960", "--height", "540"]
argument_file = directory / "launch-arguments.txt"
argument_file.write_text("\n".join('"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"' for value in arguments), encoding="utf-8")
console = (directory / "console.log").open("w", encoding="utf-8")
process = subprocess.Popen([str(args.java), "@" + str(argument_file)], cwd=directory,
    stdout=console, stderr=subprocess.STDOUT,
    creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
console.close()
state = {"pid": process.pid, "minecraft": args.minecraft, "blendlib": metadata["version"],
    "runtime": str(runtime), "runtime_sha256": hashlib.sha256(runtime.read_bytes()).hexdigest(),
    "directory": str(directory), "launcher": "Fabric production KnotClient", "visual_acceptance": "not performed"}
(directory / "process.json").write_text(json.dumps(state, indent=2), encoding="utf-8")
print(json.dumps(state))
