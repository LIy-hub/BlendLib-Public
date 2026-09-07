"""Populate Mojang's immutable inputs after validating official SHA-1 and byte size.

Run only for targets that are not currently being configured by Loom.
"""
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.request

cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/fabric-loom'
requested = sys.argv[1:] or [f'1.21.{i}' for i in range(1, 8)]
manifest = json.load(urllib.request.urlopen('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'))
releases = {v['id']: v for v in manifest['versions'] if v['type'] == 'release'}

def fetch(version):
    metadata_bytes = urllib.request.urlopen(releases[version]['url']).read()
    metadata = json.loads(metadata_bytes)
    target = cache / version
    target.mkdir(parents=True, exist_ok=True)
    (target / 'mojang_minecraft_info.json').write_bytes(metadata_bytes)
    for kind in ('client', 'server'):
        spec = metadata['downloads'][kind]
        path = target / f'minecraft-{kind}.jar'
        def valid():
            return path.exists() and path.stat().st_size == spec['size'] and hashlib.sha1(path.read_bytes()).hexdigest() == spec['sha1']
        if valid():
            print(version, kind, 'already verified', flush=True)
            continue
        temporary = path.with_suffix('.prefetch')
        subprocess.run(['curl.exe', '-L', '--fail', '--retry', '2', '--max-time', '600', '-sS', spec['url'], '-o', str(temporary)], check=True)
        if temporary.stat().st_size != spec['size'] or hashlib.sha1(temporary.read_bytes()).hexdigest() != spec['sha1']:
            raise ValueError(f'{version} {kind}: Mojang SHA-1 or length mismatch')
        temporary.replace(path)
        print(version, kind, spec['sha1'], 'verified', flush=True)
    return version

with concurrent.futures.ThreadPoolExecutor(max_workers=3) as executor:
    for result in executor.map(fetch, requested):
        print('READY', result, flush=True)
