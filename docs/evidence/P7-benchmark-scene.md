# P7 isolated benchmark-scene wiring

Status: implementation evidence only. This document does **not** claim a P7 performance,
Iris/Sodium, reload-leak, or visual Gate result.

## Opt-in server hosts

The server-safe Showcase registration exposes exactly two P7-only host types:

| Public registration | Entity class | Client binding |
|---|---|---|
| `ShowcaseEntities.P7_BENCHMARK_RIGID` | `com.liy.blendlib.showcase.entity.P7BenchmarkRigidEntity` | `P7BenchmarkRigidEntity(EntityType<? extends P7BenchmarkRigidEntity>, Level)` |
| `ShowcaseEntities.P7_BENCHMARK_SKINNED` | `com.liy.blendlib.showcase.entity.P7BenchmarkSkinnedEntity` | `P7BenchmarkSkinnedEntity(EntityType<? extends P7BenchmarkSkinnedEntity>, Level)` |

Both classes inherit the server-safe `P7BenchmarkHostEntity`: no client class, `BlendModelKey`,
resource loader, mesh, visual collision, or synchronized visual payload is stored in an entity.
`P7BenchmarkScenePlan` retains the canonical `P7ReferenceScenario` model key next to each exact
placement so that a separate client-only binding can render the planned host without sending a
model key over the network.

The common initializer only registers this administrator command tree; it never creates a scene:

```text
/blendlib_showcase p7 spawn
/blendlib_showcase p7 status
/blendlib_showcase p7 clear
```

`spawn` refuses a second scene in the same level and verifies exactly 100 rigid plus 25 skinned
hosts before reporting success. It does not move a player, set a camera, enable a resource pack,
start profiling, or make a Gate assertion. `clear` removes only the two P7 host types from the
current level.

## Isolated client preparation

The normal Showcase client/server directories remain untouched. Prepare only the deterministic
resource pack below the dedicated benchmark directory:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat :blendlib-showcase:generateP7ReferenceAssets :blendlib-showcase:verifyP7BenchmarkClientRun --console=plain
```

The verified pack root is:

`D:\BlendLib\blendlib-showcase\run\p7-benchmark\resourcepacks\blendlib-p7-reference`

The associated isolated launch configuration is:

```powershell
.\gradlew.bat :blendlib-showcase:runP7BenchmarkClient --console=plain
```

That launch configuration alone sets `blendlib.showcase.p7.enabled=true` and the deterministic
output root `D:\BlendLib\blendlib-showcase\run\p7-benchmark\benchmark-results`; normal
`runClient` and `runServer` receive neither property.

Before a real measurement, the operator must enable that resource pack in the isolated client,
use a disposable world, configure the fixed `1920x1080`/FOV-90/dynamic-FOV-off/effective-
render-distance-at-least-8 conditions, and execute `/tp @s 0 67 24 180 0`.
The controller must observe the resulting player centre `(0.5, 67.0, 24.5,
yaw=180, pitch=0)` before warm-up. Then use the separate client binding that maps
`P7_BENCHMARK_RIGID` to `blendlib_showcase:p7/rigid_10k` and
`P7_BENCHMARK_SKINNED` to `blendlib_showcase:p7/skinned_20k_64j`.

## Automated implementation verification

Completed locally on 2026-07-29:

```text
./gradlew.bat :blendlib-showcase:test --tests 'com.liy.blendlib.showcase.perf.scene.*' --rerun-tasks --console=plain
BUILD SUCCESSFUL (17 actionable tasks: 17 executed)

./gradlew.bat :blendlib-showcase:generateP7ReferenceAssets :blendlib-showcase:verifyP7BenchmarkClientRun --console=plain
BUILD SUCCESSFUL (17 actionable tasks: 3 executed, 14 up-to-date)

git diff --check
PASS
```

The unit tests pin frozen counts, all 125 placements, both canonical model keys, no automatic
spawn from the initializer, administrator-only command registration, server/client boundary
tokens, and the `run/p7-benchmark` Gradle run configuration. The Gradle verifier proves that the
generated manifest and both generated GLBs are present only under the isolated pack root.
The follow-up focused tests also pin that a partial submitted-host count invalidates a frame before
warm-up accounting, and that the repaired generated mesh/joints declare strict explicit TRS.
They additionally pin that the controller receives the public client-tick parameter, waits for the
teleport-centred frozen camera before `beginCapture()`, and never uses `Minecraft.getInstance()`.

An isolated `:blendlib-showcase:runServer` startup on 2026-07-29 reached Minecraft's
`Done (0.470s)` line after the Showcase initializer registered. The non-interactive harness closed
server stdin, so it could not dispatch the P7 command; the exact isolated Java PID was then stopped
and Gradle consequently reported `NTSTATUS 0xFFFFFFFF`. This is startup evidence only, not a
successful command-dispatch test or a P7 Gate result; no official server or world was started.

## Still required for the P7 Gate

This wiring is not visual evidence. The client-only renderer binding exists, but the first real
125-instance isolated client attempt was invalid because it submitted only 0 rigid and 10 skinned
hosts at the current frozen camera, and the same process later exposed the generated-skin crash.
ADR-017 and ADR-020 now fix the visibility and command-syntax contract, but no
post-acceptance capture has yet claimed a valid reference result. A limited
post-repair isolated smoke created all 125 hosts and reached the requested camera
without a new P7 crash report before controlled shutdown, but its warm-up guard
correctly wrote an invalid `0/0` submission report and it did not prove every
skinned host submitted. That report predates the controller's camera-preflight
correction; post-correction spawn/teleport ordering remains `WAITING` until the
camera is in position, while partial frames after capture begins remain invalid.
Remaining work requires a fresh real 125-instance run under the accepted
`/tp @s 0 67 24 180 0` contract, 600 warm-up frames plus 1,800 captured frames,
JFR/profiler timing and allocation data, 20 reload leak evidence, no-shader/
Iris/Sodium smoke, and manual visibility confirmation. None of those requirements
is relaxed by this document.

## 2026-07-30 dedicated-server evidence correction

Any earlier generic `:blendlib-showcase:runServer` / `run/server` startup is a
retained historical default-run development observation, not P7-specific
isolated or Gate evidence. The local-only
`:blendlib-showcase:runP7SmokeServer` has now separately reached
`Done (3.235s)!` on `127.0.0.1:25574`, accepted console `stop`, and saved all
dimensions in `blendlib-p7-smoke-world`; see
`docs/evidence/P7-isolated-server-smoke-2026-07-30.md`. That smoke is
independent from the benchmark client and satisfies none of its visual or
performance requirements.
