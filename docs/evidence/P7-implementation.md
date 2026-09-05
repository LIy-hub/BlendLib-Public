# P7 implementation evidence

Date: 2026-07-29  
Status: implementation evidence in progress; P7 Gate remains WAITING.

## Material boundary retained

The default material resolver and layer-provider seam is internal to
MaterialRenderMapper. It runs while static or skinned handles are prepared;
render submit receives immutable prepared material data and does not invoke a
resolver. Existing P4 behavior is preserved exactly:

- opaque/cutout/translucent/emissive mappings remain unchanged;
- additive, double-sided opaque, single-sided translucent, and a non-0.1
  cutout threshold remain explicit rejections;
- no descriptor/schema/core/public API/RenderType exposure was added.

ADR-016 is a Proposed record only. It pauses real descriptor extension decoding
and additive/custom pipeline work until the owner resolves the extension
contract and revalidates ADR-014 against the local 26.1.2 bytecode.

## Reload and cache evidence

ClientModelRegistry now exposes package-private test-only retention
observations. It retains exactly the active generation, marks stale/replaced
generations retired without invalidating already-captured immutable snapshots,
and never keeps a registry-side retired-handle collection.

The deterministic regression executes 20 listener prepare/apply cycles and
proves:

- active handle count remains bounded;
- stale publication cannot replace the active generation or grow retained
  registry ownership;
- pose-cache LRU, generation retirement, and disconnect cleanup stay within
  capacity or return to zero.

## Performance harness

See P7-performance.md for the frozen 100 rigid / 25 skinned reference scene.
The explicit generator creates its bundle only under
D:\BlendLib\blendlib-showcase\build\generated\p7-reference-assets.
It is dormant by default and not normal Showcase content.

Post-repair generation SHA-256:

- rigid_10k.glb:
  4656f2c4b058dabff49f84ed8b7ae000dd8d0fde5b11627c3336138ff1c796c9
- skinned_20k_64j.glb:
  799ea4a009a79f3fdf7665fe29702d6760345f95092d70fbf38e85bb9daffc26

## Strict generated-skin repair

The P7 generator now writes explicit identity translation, rotation, and scale for its rigid
mesh, skinned mesh, and all 64 joint nodes. This removes an implicit-default dependency from the
strict GLB-to-pose path without changing the frozen geometry, joint count, inverse-bind data, or
animation contract. The focused generator test validates all generated joint/weight slots,
inverse-bind 3x3 determinants, and animated joint rotation determinants. A focused strict
loader-to-pose-to-CPU-skinning probe at animation times 0, 0.5, and 1.0 kept all required normal
matrices invertible and all 60,000 skinned normals finite. A follow-up isolated real-client smoke
created all 125 P7 hosts and reached the requested camera without a new P7 crash report before
controlled shutdown, but it did not prove all hosts submitted and is not a visual/runtime Gate.

## Camera preflight correction

The real smoke showed that spawning at a different player position could start measurement before
the operator completed the prescribed teleport. The active ADR-017/ADR-020 command is
`/tp @s 0 67 24 180 0`. The controller receives the public Fabric client-tick `Minecraft`
parameter, never `Minecraft.getInstance()`, and remains `WAITING` until the player is at the
scenario's teleport-centred camera pose `(0.5, 67.0, 24.5, yaw=180, pitch=0)` within a
0.05-block / 1-degree tolerance. It invalidates a capture if that camera moves after warm-up
begins. This is a preflight ordering correction only: it does not alter the frozen camera/layout,
culling semantics, target work, or the exact per-frame 100/25 submission guard.

## Isolated Iris/Sodium startup smoke

The optional P7 Iris/Sodium harness is deliberately separate from both the normal Showcase runs
and the dormant benchmark run. `runP7IrisSodiumSmokeClient` uses only
`D:\\BlendLib\\blendlib-showcase\\run\\p7-iris-sodium`; it does not enable the P7 scene or change
host counts, geometry, camera, culling, warm-up, sampling, renderer behavior, or publication
inputs. Its explicit preparation task downloads only the following fixed Modrinth files over
HTTPS, validates SHA-1 before naming them, and refuses unexpected content in that run's `mods`
directory:

| Mod | Modrinth version id | Isolated filename | SHA-1 |
|---|---|---|---|
| Iris 1.11.2 for Fabric 26.1.2 | `e4ioH5mG` | `iris-fabric-1.11.2+mc26.1.2.jar` | `5f23dc2bae9fa28a18ef1ec6a60c0d6f8fcc5b13` |
| Sodium 0.9.1 for Fabric 26.1.2 | `vf7UgZpC` | `sodium-fabric-0.9.1+mc26.1.2.jar` | `cdb5ab59dc05840c5fc762c3821570c6fa02a8dc` |

The opt-in verifier resolves the normal `compileClasspath`, `runtimeClasspath`,
`clientCompileClasspath`, and `clientRuntimeClasspath`, verifies that none resolve the optional
Modrinth Iris/Sodium modules, confirms the normal `run/client`, `run/server`, and
`run/p7-benchmark` mod folders have no pinned smoke JAR, and confirms the Showcase JAR does not
embed either file. The direct-download task is not a dependency of `check`, normal client/server
runs, local RC assembly, publication, or release verification.

With `JAVA_HOME=C:\\Program Files\\Java\\latest\\jdk-25`, the coordinator ran:

```powershell
.\\gradlew.bat :blendlib-showcase:verifyP7IrisSodiumSmokeClientRun --console=plain
```

It passed with 19 actionable tasks (2 executed, 17 up-to-date), and independently recomputed the
two output SHA-1 values above. The first attempt exposed a Kotlin-DSL script compile ambiguity
before any download or client launch; it was corrected to an explicit file-name lambda before the
passing invocation.

One and only one isolated startup attempt then ran:

```powershell
.\\gradlew.bat :blendlib-showcase:runP7IrisSodiumSmokeClient --console=plain
```

`run/p7-iris-sodium/logs/latest.log` records Fabric Loader loading Minecraft 26.1.2/Loader
0.19.3, both Iris and Sodium in the Fabric mod list, Sodium's configuration/graphics-adapter
initialization, Iris DSA initialization, the Render thread, and a resource reload listing both
`iris` and `sodium`. The run used no shaderpack; Iris explicitly reported that shaders were
disabled because none was selected. A scan of that isolated run found no crash-report or
`hs_err_pid` file. The only `ERROR` records were offline credential/Realms 401 messages, which
are external authentication observations rather than a BlendLib, Iris, Sodium, or render crash.

After these startup observations, the coordinator validated the Java command line included
`-Dblendlib.showcase.p7.iris_sodium_smoke=true`, the isolated Loom argument file, and
`-Dfabric.dli.env=client`, then stopped only that child PID 63284. Loom consequently reported
exit value `-1` / `NTSTATUS 0xFFFFFFFF`; this is the expected controlled-shutdown outcome, not a
claim that the Gradle task completed normally.

This is startup/no-fatal-render-error evidence only. It is not shaderpack validation, a visual
inspection, static/rigid/skinned proof, a P7 benchmark capture, an all-host submission proof,
JFR/profiler/performance evidence, or a P7 Gate decision.

## Verification completed

With JAVA_HOME=C:\Program Files\Java\latest\jdk-25:

```powershell
.\gradlew.bat :blendlib-fabric-client:test --tests '*Material*' --rerun-tasks --console=plain
.\gradlew.bat :blendlib-fabric-client:test --tests '*RenderContractsTest' --tests '*SkinnedRenderBackendContractsTest' --rerun-tasks --console=plain
.\gradlew.bat :blendlib-fabric-client:test --tests '*ClientModelRegistryTest' --tests '*ClientModelReloadListenerTest' --tests '*BoundedPoseCacheTest' --tests '*ClientAnimationInstanceRegistryTest' --rerun-tasks --console=plain
.\gradlew.bat :blendlib-showcase:test --tests '*Perf*' --rerun-tasks --console=plain
.\gradlew.bat clean check --console=plain
.\gradlew.bat :blendlib-core:test --console=plain
.\gradlew.bat buildRelease --console=plain
.\gradlew.bat :blendlib-showcase:generateP7ReferenceAssets --console=plain
git diff --check
```

The listed clean check and buildRelease succeeded before the generated-skin repair (43 actionable
tasks, 22 executed/21 cached; buildRelease 37 tasks, 5 executed/32 up-to-date). Post-repair,
`P7ReferenceAssetGeneratorPerfTest`, `P7ReferenceScenarioPerfTest`, and
`P7PerfSourceBoundaryTest` passed, as did
`:blendlib-showcase:generateP7ReferenceAssets :blendlib-showcase:verifyP7BenchmarkClientRun`.
The full clean check is therefore historical implementation evidence, not a post-repair P7 Gate
assertion.

The coordinator also reran the whole post-repair Showcase `*Perf*` suite (17 actionable tasks)
and `ClientRenderMeasurementCollectorTest` (12 actionable tasks), both successfully. This only
refreshes implementation evidence; it cannot replace the unresolved all-host runtime evidence.
After the camera-preflight correction, the coordinator reran `*Perf*` again successfully (17
actionable tasks). The source-boundary test locks the public callback, absence of
`Minecraft.getInstance()`, camera guard before `beginCapture()`, and partial-frame guard before
warm-up advancement.

On 2026-07-29, a later state-machine refactor made that source-boundary test search for a
retired controller-local `State.WARMUP` branch. The test now checks the live split contract:
the controller calculates exact `100/25` submissions before dispatching
`INVALID_SUBMISSIONS`, and the state machine returns that transition before it increments
`warmupFrames`. No production performance code, reference counts, thresholds, or ADR state
changed. The coordinator reran
`:blendlib-fabric-client:test :blendlib-showcase:test --rerun-tasks --console=plain` with
19 executed tasks and zero failures, followed by a clean `git diff --check`. This is a
regression-test refresh only, not a P7 runtime, visual, or performance Gate result.

The isolated Showcase server reached Done (0.361s) and saved every dimension.
The isolated real client reached Render thread, Indigo, LWJGL, OpenAL and
resource reload with four loaded models, zero missing models and zero
diagnostics. Exact verified PIDs 19752 (server) and 59956 (client) were
stopped after evidence capture.

## Still required

This evidence does not prove that every skinned host is stable in a real submitted render frame,
nor visual correctness, 60 FPS, real allocation, Iris/Sodium compatibility, or 20 user-observed
resource reloads.
ADR-017 and ADR-020 are accepted, but a fresh isolated run is still required before a valid
reference result can be claimed. The manual run must use `/tp @s 0 67 24 180 0`, load the repaired
generated bundle, display all 125 instances under the accepted reproducible visibility contract,
warm up 600 frames, capture 1,800 frames in JFR or a profiler, and retain screenshots, logs,
p50/p95, CPU, allocation, cache/handle, and environment evidence.

## 2026-07-30 dedicated-server evidence

The current P7 candidate now has a real phase-specific isolated server smoke.
`:blendlib-showcase:runP7SmokeServer` used only `run/p7-smoke-server`,
`127.0.0.1:25574`, and `blendlib-p7-smoke-world`; it reached
`Done (3.235s)!`, accepted console `stop`, saved all dimensions, and returned
Gradle exit `0`. Exact hashes and safety checks are retained in
`docs/evidence/P7-isolated-server-smoke-2026-07-30.md`.

Any generic `:blendlib-showcase:runServer` / `run/server` startup remains a
historical default-run observation. The server smoke is also independent from
`run/p7-benchmark`; it closes no visual, all-host, JFR, FPS, allocation,
Iris/Sodium, reload-leak, or performance requirement. P7 remains `WAITING`.
