# P3 Implementation Evidence — Strict Pure-Java Core Loader

Status: IMPLEMENTED — all P3 implementation evidence is present; the remaining
review/audit handoff is user-managed and separate. This document is
implementation evidence, not a P3 Gate PASS decision.

## Implemented scope

- Pure-Java `AssetBytes` and `AssetResolver`; the resolver accepts only a
  validated `BlendResourceId` and has no `Path`, URI, class-loader, Minecraft,
  or Fabric parameter.
- Strict GLB 2.0 header/chunk reader, bounded internal JSON AST, embedded-BIN
  accessor/buffer-view reader, overflow-safe range arithmetic, U16/U32-only
  triangle indices, component alignment validation, and no sparse/external
  buffer support.
- Strict v1 descriptor decoder: canonical `BlendResourceId` references,
  `models3d/*.glb` meshes, external `textures/*.png` base colors, material
  defaults, cutout threshold rules, additive mode, sockets, animation states,
  extension rejection, and all frozen hard limits.
- Immutable `ModelAsset`, node/mesh/skin/skeleton/socket/animation data,
  descriptor ID, units-per-block, material intent, descriptor animation state
  metadata, and the exact ordered roots from the loader-validated default GLB
  scene. This preserves validated resource data without choosing any P5
  active-hierarchy runtime policy, and lets P4 consume reload results without
  reparsing the descriptor.
- Rigid/static/skinned decoding, inverse-bind validation, node-cycle/depth
  checks, one-active-node-per-mesh restriction, bounds, linear/step sampling,
  quaternion slerp, camera/light `SCENE-006` warnings, and non-fatal
  `PERF-001` warnings above 100,000 total vertices or above 128 joints in an
  individual or combined relevant skin set. These advisory thresholds never
  relax the frozen hard limits.
- Safety repairs from the P3 early audit: pre-allocation limits for primitive,
  skin, and animation accessors; bounded JSON values/strings/collections;
  all node skin references validated; non-uniform/negative scale rejected;
  transform/bounds arithmetic overflow is translated to a controlled
  `SCENE-005` diagnostic.
- The combined descriptor/GLB load now rejects a state whose declared `next`
  resource id is absent from that descriptor's state map using existing
  `DESC-002`, rather than allowing the error to reach controller construction
  as a generic exception. This is an integrity repair for the approved
  non-loop-plus-`next` state-machine contract; it does not add an event-time
  policy.

## Stable diagnostics

ADR-010 assigns and documents the additive P3 codes while preserving every P0
meaning:

- `DESC-002`, `GLB-002`, `GLB-015`, `SCENE-005`, `SCENE-006`, `SKIN-001`,
  `ANIM-007`, and the non-fatal warning `PERF-001`.

## Test coverage

- The self-authored P3 fixture catalog covers valid GLB, header/declared
  length/chunk/accessor/index failures, required extensions, cycles,
  non-finite animation output, non-monotonic times, and all named hard-limit
  fixtures.
- Direct production-loader tests cover descriptor defaults/cutout/additive,
  unsafe mesh references, U8 rejection/U32 acceptance, component alignment,
  non-finite and overflowed scene transforms, baked-scale enforcement, mesh
  multi-binding rejection, all-node skin references, inverse-bind mismatch,
  CUBICSPLINE rejection, metadata preservation, configured low limits, and
  exact `PERF-001` `WARN` diagnostics for a 100,001-vertex rigid asset and a
  129-joint skinned asset, plus no warning at the exact 100,000/128
  thresholds.
- Canonical P2 static/rigid/skinned assets load with their documented node,
  primitive, vertex/index, clip, skin, and bounds golden values.
- Loader regressions prove that the exact validated default-scene root order is
  retained immutably and that a valid decoded clip combined with an undeclared
  descriptor `next` fails deterministically as `DESC-002` before any controller
  is constructed.
- Fixed-seed fuzz uses 512 mutations of a valid archive plus 128 varied-size
  valid-container/random-JSON archives. It accepts only successful loads or
  `BlendAssetLoadException`, and explicitly fails on array/buffer/negative
  allocation, stack-overflow, or out-of-memory escapes.

## Commands and observed results

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat :blendlib-core:test --tests com.liy.blendlib.core.loader.PerformanceWarningTest
```

Result: `BUILD SUCCESSFUL`; the three direct threshold tests pass: warning
above each threshold and no warning at either exact threshold.

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat :blendlib-core:test
```

Result: `BUILD SUCCESSFUL`; 10 core test suites, 28 tests, 0 failures, 0 errors,
0 skipped.

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat :blendlib-core:test --tests '*ModelAssetLoaderTest' --rerun-tasks --console=plain
.\gradlew.bat :blendlib-fabric-client:test --tests '*RenderContractsTest' --rerun-tasks --console=plain
```

Result: both focused Java-25 runs were `BUILD SUCCESSFUL` (6 and 11 executed
tasks respectively). The latter mechanically proves client render-contract
fixtures pass the new immutable `ModelAsset` constructor argument; neither run
claims a render or P5 Gate result.

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat clean check --console=plain
.\gradlew.bat buildRelease --console=plain
```

Result: an independent serial current-worktree verification completed both
commands successfully: `clean check` ran 37 tasks (24 executed, 13 from cache)
and `buildRelease` ran 32 tasks (4 executed, 28 up-to-date). This is build
evidence only, not a P3 Gate decision.

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat :blendlib-core:check
```

Result: `BUILD SUCCESSFUL`; includes `verifyCoreJarBoundary`, which scans the
assembled core JAR paths and class constant pools for Minecraft/Fabric type
references.

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat clean check
git diff --check
git status --short --branch
```

Result: `BUILD SUCCESSFUL` (36 actionable tasks). `git diff --check` completed
without output. The branch remains `Liy/blendlib-v1`; P3 files are intentionally
unstaged pending independent review and coordinator-owned phase commit.

## Open gate items

- ADR-011 is Accepted and its fixed-revision provenance/derivation subtask is
  complete. The repository now contains only CC0 upstream payload required for
  derivation, a SHA-256 manifest, deterministic strict-GLB derivatives, and
  loader tests. Raw upstream `.gltf` remains strict-rejection-only.
- The remaining P3 audit/review handoff is user-managed and separate. No PASS
  decision, staging, or phase commit is implied by this implementation evidence.
- No P4 renderer/reload registry, P5 controller, P6 networking, client visual
  verification, production-server action, release, push, or publication was
  performed.

## 2026-07-30 dedicated-server evidence

The current P3 candidate now has a real phase-specific isolated server smoke.
`:blendlib-showcase:runP3SmokeServer` used only
`run/p3-smoke-server`, `127.0.0.1:25571`, and
`blendlib-p3-smoke-world`; it loaded the fixed runtime baseline, reached
`Done (5.723s)!`, accepted a standard-input `stop`, saved all dimensions, and
returned Gradle exit `0`. Port `25571` was free afterward and no crash report
was created. Exact hashes and safety checks are retained in
`docs/evidence/P3-isolated-server-smoke-2026-07-30.md`.

Any generic `:blendlib-showcase:runServer` / `run/server` observation remains
historical non-Gate evidence. The new isolated smoke closes only P3's
dedicated-server item; P3 stays `REVIEW` for the user-managed audit, and no
phase PASS, staging, or commit is implied.
