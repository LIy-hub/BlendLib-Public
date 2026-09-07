"""Start an exact packaged Fabric JAR in a fresh, loopback-only server and stop after readiness."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import threading
import time
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--minecraft", required=True)
parser.add_argument("--runtime", required=True, type=Path)
parser.add_argument("--fabric-api", required=True, type=Path)
parser.add_argument("--java", required=True, type=Path)
parser.add_argument("--directory", required=True, type=Path)
parser.add_argument("--launcher", required=True, type=Path)
parser.add_argument("--libraries-cache", type=Path)
parser.add_argument("--versions-cache", type=Path)
parser.add_argument("--fabric-server-cache", type=Path)
parser.add_argument("--server-bundle", type=Path)
parser.add_argument("--port", required=True, type=int)
args = parser.parse_args()

runtime = args.runtime.resolve(strict=True)
with zipfile.ZipFile(runtime) as archive:
    metadata = json.loads(archive.read("fabric.mod.json"))
assert metadata["id"] == "blendlib"
assert metadata["depends"]["minecraft"] == args.minecraft
directory = args.directory.resolve()
directory.mkdir(parents=True, exist_ok=False)
(directory / "mods").mkdir()
shutil.copy2(runtime, directory / "mods" / runtime.name)
shutil.copy2(args.fabric_api, directory / "mods" / args.fabric_api.name)
shutil.copy2(args.launcher, directory / "fabric-server.jar")
for source, name in ((args.libraries_cache, "libraries"), (args.versions_cache, "versions")):
    if source:
        shutil.copytree(source, directory / name)
if args.fabric_server_cache:
    shutil.copytree(args.fabric_server_cache, directory / ".fabric" / "server")
if args.server_bundle:
    server_directory = directory / ".fabric" / "server"
    server_directory.mkdir(parents=True, exist_ok=True)
    shutil.copy2(args.server_bundle, server_directory / f"{args.minecraft}-server.jar")
(directory / "eula.txt").write_text("eula=true\n", encoding="utf-8")
(directory / "server.properties").write_text(
    f"server-ip=127.0.0.1\nserver-port={args.port}\nonline-mode=false\n"
    "enable-rcon=false\nenable-query=false\nwhite-list=true\nspawn-protection=0\n"
    "level-name=blendlib-isolated-smoke\nview-distance=2\nsimulation-distance=2\n"
    "max-players=1\nmotd=BlendLib isolated packaged verification\n",
    encoding="utf-8",
)
command = [str(args.java), "-Xms128m", "-Xmx1G", "-jar", "fabric-server.jar", "nogui"]
started = time.time()
process = subprocess.Popen(
    command, cwd=directory, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
    creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
)
state = {
    "minecraft": args.minecraft, "blendlib": metadata["version"],
    "runtime": str(runtime), "runtime_sha256": hashlib.sha256(runtime.read_bytes()).hexdigest(),
    "pid": process.pid, "command": command, "directory": str(directory),
    "ready": False, "stop_sent": False, "timed_out": False,
}
(directory / "process.json").write_text(json.dumps(state, indent=2), encoding="utf-8")

def read_output():
    with (directory / "console.log").open("w", encoding="utf-8") as output:
        for line in process.stdout:
            output.write(line)
            output.flush()
            if "Done (" in line and not state["stop_sent"]:
                state["ready"] = True
                process.stdin.write("stop\n")
                process.stdin.flush()
                state["stop_sent"] = True

reader = threading.Thread(target=read_output, daemon=True)
reader.start()
try:
    state["exit_code"] = process.wait(timeout=240)
except subprocess.TimeoutExpired:
    state["timed_out"] = True
    process.stdin.write("stop\n")
    process.stdin.flush()
    try:
        state["exit_code"] = process.wait(timeout=20)
    except subprocess.TimeoutExpired:
        process.terminate()
        state["exit_code"] = process.wait(timeout=10)
reader.join(timeout=5)
state["elapsed_seconds"] = round(time.time() - started, 2)
state["result"] = "PASS" if (
    state["ready"] and state["stop_sent"] and state["exit_code"] == 0 and not state["timed_out"]
) else "FAIL"
(directory / "result.json").write_text(json.dumps(state, indent=2), encoding="utf-8")
print(json.dumps(state, ensure_ascii=False))
raise SystemExit(0 if state["result"] == "PASS" else 1)
