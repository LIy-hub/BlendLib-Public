# Legacy version evidence

Baseline: `347591d` (Minecraft 26.1.2 Beta.1). Release target: `1.0.0-beta.2+<minecraft>`.
Verification performed on 2026-09-07/08. Gradle 9.6 runs with Java 25; compiler and packaged runtimes use Java 21.

## Final artifact matrix

Every row compiled the complete generated main/client source sets, remapped the release JAR, and checked
exact Minecraft/Fabric metadata, all generated production source classes, Java class major 65, no nested
Java 25 artifact and no excluded X7 shader resource. Tests use actual target Minecraft interfaces.

| Minecraft | Fabric API | Sources / classes | Tests | Packaged server | Packaged client |
| --- | --- | --- | --- | --- | --- |
| 1.21.1 | 0.116.17+1.21.1 | 493 / 872 | PASS, 2 | PASS, exit 0 | PASS, resource reload and close |
| 1.21.2 | 0.106.1+1.21.2 | 493 / 872 | PASS, 2 | PASS, exit 0 | PASS, resource reload and close |
| 1.21.3 | 0.114.1+1.21.3 | 493 / 872 | PASS, 2 | PASS, exit 0 | PASS, resource reload and close |
| 1.21.4 | 0.119.4+1.21.4 | 493 / 873 | PASS, 4 | PASS, exit 0 | PASS, resource reload and close |
| 1.21.5 | 0.128.2+1.21.5 | 492 / 872 | PASS, 2 | PASS, exit 0 | PASS, resource reload and close |
| 1.21.6 | 0.128.2+1.21.6 | 492 / 872 | PASS, 2 | PASS, exit 0 | PASS, resource reload and close |
| 1.21.7 | 0.129.0+1.21.7 | 492 / 872 | PASS, 2 | PASS, exit 0 | PASS, resource reload and close |
| 1.21.8 | 0.136.1+1.21.8 | 492 / 872 | PASS, 2 | PASS, exit 0 | PASS, resource reload and close |

## SHA-256 and evidence locations

Release artifacts are `build/<minecraft>/libs/blendlib-fabric-1.0.0-beta.2+<minecraft>.jar`.
Build logs are local `build-<minecraft>-<run>.log`; tests are `build/<minecraft>/test-results/test/`.

| Minecraft | SHA-256 | Build log run | Server result / seconds | Client result |
| --- | --- | --- | --- | --- |
| 1.21.1 | `8c45f73ece19a33ee5d7d322544d2701c51e44a689a448acd9d294a8b3d867c6` | r7 | `1.21.1-server-r1/result.json`, 27.81 s | `1.21.1-client-1/result.json` |
| 1.21.2 | `b4f759b2f6690461c697c53e6356e458fb8add823fd843d432ee4b7e55b8057c` | r6 | `1.21.2-server-r1/result.json`, 41.71 s | `1.21.2-client-1/result.json` |
| 1.21.3 | `9cc5611b8526974f3cf3c67d61ef753d9c86102a6c22a54cdd017049b645c6be` | r5 | `1.21.3-server-r1/result.json`, 75.88 s | `1.21.3-client-1/result.json` |
| 1.21.4 | `8dd6f4d6113e0fb8425c0f63f1a201adf87562e73592314ec0fbd33399cae6bb` | r7 | `1.21.4-server-r2/result.json`, 43.39 s | `1.21.4-client-2/result.json` |
| 1.21.5 | `92cd6c64a6e90b8c44aef655ece58b05afc4172ff53715a760d74f3c81d857f9` | r3 | `1.21.5-server-r2/result.json`, 29.66 s | `1.21.5-client-1/result.json` |
| 1.21.6 | `99e7cdb12f057b844de4252a313398201076db1ea583da8214285be72fa32c8c` | r1 + r3 resource recheck | `1.21.6-server-r2/result.json`, 37.49 s | `1.21.6-client-1/result.json` |
| 1.21.7 | `7816988cbcc66a64774f8c0c509c58f2724f386e9cfbb27dd532eaa078635f34` | r1 + r3 resource recheck | `1.21.7-server-r2/result.json`, 56.37 s | `1.21.7-client-1/result.json` |
| 1.21.8 | `ccb4c5bf4a132c5a33f6d5a5ee39586cf4ad7d4f5f2b69c8a6b5c3233575319b` | r10 | `1.21.8-server-r2/result.json`, 40.47 s | `1.21.8-client-2/result.json` |

Server result paths are relative to `versions/legacy/build/smoke/`. Client results are owned by the main
agent under `D:/BlendLib/versions/modern/build/smoke/`; each records the exact copied runtime hash,
`published=true, diagnostics=0`, startup result, requested window close and observed process exit.
No client entered a world and no visual acceptance was performed. Expected offline-authentication fetch
errors are recorded separately from startup/shutdown failures in those receipts.

## Commands and runtime method checks

```powershell
./gradlew.bat -p versions/legacy -Pminecraft_version=<minecraft> --no-daemon --max-workers=1 build verifyRuntimeJar
```

`build_matrix.ps1` runs that command sequentially for all eight supported targets, with unique logs.
The .6/.7 resource-only rechecks used `verifyRuntimeJar`; their previously passing production code and
two submission tests were unchanged. The .1 test bootstraps native registries before initializing
RenderType, because that version initializes Items transitively. The .4 run includes two additional
native item model/transform tests.

Dedicated servers used the shared `versions/modern/smoke_server.py`, an exact Fabric 0.19.3 launcher,
the matching API JAR and SHA-verified official server bundle. Each run used a fresh isolated directory,
loopback-only ports 25631–25638, `nogui`, 1 GiB maximum heap, then sent `stop` after `Done` and observed exit 0.

The .1/.2/.3 actual ItemRenderer bytecode calls the pinned eight-parameter `render` method from
`renderStatic`; remapped JAR annotations target intermediary `class_918.method_23179`, `require=1`.
The .4 item lookup remaps to `class_1092.method_65746`, `require=1`. Actual packaged client startup is
the separate class-loading/injection check. See `API_ADAPTATION.md` for lifecycle and public API review.

## Preserved failed-attempt evidence

The first 1.21.8 artifact, SHA-256
`42cd40361c170ba70fb1cddb8d0e83ce36cbb2717700f6517a7c52c04232ba21`, built and started a server
but FAILED packaged client resource reload (`1.21.8-client-1`). Vanilla eagerly preprocessed an unused
X7 shader importing the absent `minecraft:sample_lightmap.glsl`. The final legacy builds exclude all six
26.1.2-only X7 shader resources; CPU geometry uses the game's native shaders. A verifier rejects leaks.
Because ProcessResources is a Copy task, its verified per-version output directory is cleared before
copying so previously packed excluded files cannot survive. The new .8 hash passed `1.21.8-client-2`.

The first .4 validated hash
`75e26a21b47a4ff8caf5a64ebefaf4503432a8353f2ff44e9fdaf594bf21be8b` passed initial client/server
startup. The final .4 hash adds a small model-lookup test seam and two precise tests; its new server
evidence is `1.21.4-server-r2`. Initial .6/.7 server-r1 receipts are retained but do not bind the final
shader-free hashes. TLS transport failures and the .1 test bootstrap failure are preserved in earlier
build logs; they are not counted as successful runs.

X7 native GPU execution, GLB gameplay/visual acceptance and publication approval are outside these results.
