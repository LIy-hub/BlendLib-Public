# P5 implementation evidence

Status: implementation evidence only. P5 is **not** a Gate PASS. P4 still has
open user-managed visual/audit conditions. ADR-015 is accepted; its canonical
asset-bound animation integration remains implementation work, not a Gate
waiver.

## Implemented non-conflicting P5 runtime

- `BlendAnimationKey` and sealed `BlendInstanceKey` keep animation and instance
  identities pure, typed, and session-scoped. Entity IDs cannot collide across
  different connections.
- The pure-core controller supplies initial state, triggers, loop/non-loop
  progression, configured next-state transitions, speed, smoothstep cross-fade,
  monotonic sequence correction, and presentation-only visual events.
- Pure pose/palette code samples supported channels, composes rigid node
  transforms, resolves sockets, derives skin palettes, and CPU-skins prepared
  immutable geometry. `PreparedSkinnedGeometry.prepare(...)` is the explicit
  asset/backend boundary and retains immutable material-slot/UV0/index topology;
  each `CpuSkinnedMesh` retains that exact prepared topology after checking the
  output vertex count. `CpuSkinner.skin(...)` no longer reads defensive
  `MeshPrimitive` copies in its repeated skin loop. Its hot loop writes into a
  six-float scratch buffer through `SkinPalette` and has a numerical regression
  test that forbids per-vertex/per-influence `Vec3` construction.
- Client extraction state uses one `ClientAnimationInstanceRegistry` with
  generation replacement/retirement, a capacity-bounded access-order LRU pose
  cache with metrics, and deterministic visible-distance update buckets. A
  binding is now identified by the required `(instanceKey, modelKey, generation)`
  triple; either a model or generation rebind replaces the controller and removes
  every cached pose for that typed instance, while `PoseCacheKey` also contains
  the model key. Its extraction-only `sampleAndCache(...)` operation now takes
  an explicit caller-prepared `PoseSampler` and current `PoseCacheKey`, rejects
  stale model/generation/state keys before lookup, samples exactly once only on
  an LRU miss, and refreshes the instance's latest immutable pose on either a
  miss or hit. It deliberately does not advance a controller or select time,
  cadence, visibility, resource, scene, or render state.
- The 26.1.2 render adapter now has a package-private immutable
  `RigidNodePaletteSnapshot` carrier bound to one handle model key and generation.
  `ModelRenderSnapshot` rejects mismatches before submit; the rigid backend uses
  the prepared palette only when present and otherwise preserves its P4 rest-pose
  behavior. This is synthetic handoff coverage only: it performs no controller
  sampling, asset lookup, scene selection, or Showcase wiring.
- The actual 26.1.2 client entrypoint registers `ClientPlayConnectionEvents.INIT`
  and `DISCONNECT`, plus public `ClientEntityEvents.ENTITY_UNLOAD` and
  `ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD`. The bridge creates an
  adapter-private UUID connection token at INIT; future entity bindings obtain
  the same full `Entity(session,id)` key from it. An entity unload therefore
  exact-matches only that session/id, while a block unload exact-matches its
  dimension/packed-position key. Both paths remove every corresponding pose;
  disconnect clears controller, instance, pose-cache, and cache-observation
  state and discards the token.
- P3 now rejects the two glTF-invalid animation cases with existing
  `ANIM-007`: a duplicate `(node,path)` target in one animation, and any target
  whose original glTF node declared `matrix`.
- The immutable asset now retains the exact ordered root list from the default
  scene already validated by the strict loader, and combined load rejects an
  undeclared descriptor `next` with existing `DESC-002` before controller
  construction. These are data/integrity corrections only: they do not select
  an active-hierarchy policy, reject scene-external skin/joint nodes, attach a
  palette to an asset, or decide event-time behavior.
- `CanonicalP2RuntimeGoldenTest` loads the original P2 rigid and skinned
  Showcase resources through `ModelAssetLoader`, checks their independent P2
  hashes, and exercises P5 sampling, palettes, prepared geometry, and CPU skin
  output at fixed times. See `P5-canonical-golden-tests.md`.
- A reproducible `skinned_v1` Showcase source and runtime asset now supplies
  distinct `idle`, `walk`, and `attack` clips, an external PNG, and committed
  double-export structure/SHA-256 goldens. Its binding now uses the accepted
  ADR-015 runtime semantics rather than inferring Blender action names. See
  `P5-showcase-animation-asset.md`.

## Canonical asset-bound integration

- `BlendLibClientEntrypoint` owns one `SkinnedAnimationRuntime` over the same
  generation registry and lifecycle bridge used by reload, INIT, disconnect,
  entity unload, and block-entity unload. The reload callback receives only
  the registry's actual published generation.
- `BlendLibClientServices` exposes that entrypoint-owned runtime only to the
  extraction-side adapter. `BlendEntityRendererBuilder.skinnedAnimation(...)`
  selects a declared state while entity state is extracted; it creates a
  generation-bound CPU-skinned immutable frame before submit. Submit still
  consumes only `ModelRenderSnapshot` and does not query a controller, registry,
  parser, resource manager, or world.
- The optional `onSkinnedVisualEvent(...)` surface forwards only
  presentation-only event keys. Showcase registers one client-only consumer:
  it first rejects a non-render thread, a removed or invisible entity, and all
  keys except `blendlib_showcase:attack_whoosh`; only then it calls ordinary
  `Level.addParticle(ParticleTypes.SWEEP_ATTACK, ...)`. It performs no
  gameplay, server, network, async queue, always-visible-particle, or
  hit-detection action. This is implementation evidence, not visual proof.
- `ShowcaseEntities.ANIMATED_ACTOR` is now registered only from the Showcase
  client source set with `ShowcaseSkinnedAnimationBinding.MODEL_KEY` and the
  deterministic `ShowcaseAnimatedActorStateSchedule` (`idle [0,80)`,
  `walk [80,120)`, `attack [120,132)`). Its common entity host remains
  stateless and server-safe.
- `ShowcaseSkinnedResourcePreparationTest` reads the committed Showcase
  descriptor, GLB, and external PNG directly, runs the strict loader, checks
  the canonical default-scene root, animation/event contract and material, then
  prepares a real `SkinnedRenderHandle`. It is not a synthetic fixture and does
  not claim reload-thread or visual coverage.
- The deterministic Showcase contract now declares exactly
  `"blendlib_showcase:tip": {"node":
  "ShowcaseAnimationRoot/ShowcaseAnimationArmature/ShowcaseRootBone/ShowcaseTipBone"}`.
  Blender 5.1.2 completed an isolated record-golden export and a second strict
  golden-read export; a direct Draft 2020-12 validation of the committed
  descriptor passed; the retained source `.blend` SHA-256 is
  `3ce143f190a2d8eac25df4392fdd0ce3dc61a4c397fc62ba3f1bf7920c857511`.
  The extended real-resource regression samples the canonical walk state at
  `7/24s` and `19/24s`, queries the socket from `NodePalette`, and asserts
  approximately `(+0.07,+0.60,0)` and `(-0.07,+0.60,0)` respectively. Those
  are model-hierarchy coordinates, not Minecraft world coordinates. A Java 25
  JShell probe over the compiled pure API/core classes also strict-loaded the
  current descriptor, resolved the socket to node `0`, and emitted those two
  sampled positions. Both newest JUnit classes were then Java-25 compiled
  against the current local 26.1.2 classpath and executed with the locally
  cached JUnit Platform 1.11.4/Jupiter Engine 5.11.4: the socket resource
  class reported `total=2 succeeded=2 failed=0`, and the guarded visual-event
  contract class reported `total=2 succeeded=2 failed=0`. These are direct
  JUnit Platform results. The current focused Gradle refresh then passed both
  classes: the Showcase animation-contract/source-boundary selection recorded
  5 tests with zero failures/errors (16 executed tasks), and the client
  real-resource/socket selection recorded 2 tests with zero failures/errors
  (11 executed tasks).

## Post-integration verification

On 2026-07-29 with `JAVA_HOME=C:\Program Files\Java\latest\jdk-25`:

```powershell
.\gradlew.bat :blendlib-fabric-client:test --tests '*EntityAdapterContractsTest' --tests '*BlendLibClientEntrypointLifecycleTest' --tests '*ClientAdapterContractsTest' --tests '*SkinnedAnimationRuntime*' --tests '*ClientSkinnedExtractionBridgeTest' --tests '*VisualEvent*' --rerun-tasks --console=plain
.\gradlew.bat :blendlib-showcase:test --tests '*ShowcaseSkinnedAnimationContractsTest' --tests '*ShowcaseAnimatedActorStateScheduleTest' --tests '*ShowcaseAnimatedActorEntityContractsTest' --tests '*ShowcaseSourceBoundaryTest' --rerun-tasks --console=plain
.\gradlew.bat :blendlib-fabric-client:test :blendlib-showcase:test --rerun-tasks --console=plain
.\gradlew.bat :blendlib-fabric-client:test --tests '*ShowcaseSkinnedResourcePreparationTest' --rerun-tasks --console=plain
.\gradlew.bat clean check --console=plain
.\gradlew.bat :blendlib-core:test --console=plain
.\gradlew.bat buildRelease --console=plain
```

All commands succeeded. The focused client run executed 11 tasks, the focused
Showcase run completed 12 tests with zero failures/errors (16 tasks), the
combined client/Showcase run completed 18 tasks, the earlier real-resource
regression completed one test without failure (11 tasks), `clean check`
completed 37 tasks (21 executed, 16 cached), core test completed successfully,
and `buildRelease` completed 32 tasks (4 executed, 28 up-to-date). Those
results predate the latest guarded `attack_whoosh` consumer and real-Showcase
two-time socket supplement. That supplement was subsequently re-run with its
two focused Gradle selections: `ShowcaseSkinnedAnimationContractsTest` plus
`ShowcaseSourceBoundaryTest` recorded 5 tests with zero failures/errors (16
executed tasks), and `ShowcaseSkinnedResourcePreparationTest` recorded 2 tests
with zero failures/errors (11 executed tasks).

## Automated verification

On 2026-07-29 with `JAVA_HOME=C:\Program Files\Java\latest\jdk-25`, the
root coordinator ran:

```powershell
.\gradlew.bat :blendlib-core:test --rerun-tasks --console=plain
.\gradlew.bat :blendlib-fabric-client:test --tests '*ClientAnimationInstanceRegistryTest' --tests '*ClientAnimationLifecycleBridgeTest' --tests '*BoundedPoseCacheTest' --tests '*ClientAnimationSourceBoundaryTest' --tests '*ClientModelReloadListenerTest' --tests '*RenderContractsTest' --rerun-tasks --console=plain
.\gradlew.bat clean check --console=plain
.\gradlew.bat buildRelease --console=plain
python -B test-assets\p5\verify_p5_fixtures.py --project-root D:\BlendLib
git diff --check
git status --short --branch
```

Results:

- Earlier forced core rerun: `BUILD SUCCESSFUL`; 15 suites / 45 tests, zero
  failures. After the canonical-golden/CPU hot-path additions, root forced a
  new `:blendlib-core:test --rerun-tasks` result: `BUILD SUCCESSFUL`; six core
  tasks executed, zero failures.
- Fresh full clean check (independent P5 test runner): `BUILD SUCCESSFUL`; 37
  actionable tasks, 27 executed and 10 from cache. The Fabric client test
  result set contains 13 suites / 36 tests, zero failures.
- Post-check `buildRelease`: `BUILD SUCCESSFUL`; 32 actionable tasks, 4
  executed and 28 up-to-date.
- After the lifecycle addition, root forced
  `:blendlib-fabric-client:test --rerun-tasks`: `BUILD SUCCESSFUL`; 11 tasks
  executed. Its regression suite proves that an unload of one
  `Entity(session,id)` leaves a same-id different-session entry intact, block
  unload keeps same-position other-dimension and same-dimension other-position
  entries, and neither touches Item/Ephemeral entries. It also covers INIT
  rotation, disconnect token invalidation, exact pose-cache removal, and source
  registration of the four public Fabric lifecycle events.
- The independent post-lifecycle run repeated `clean check` successfully (37
  actionable tasks; 20 executed and 17 from cache) and `buildRelease`
  successfully (32 actionable tasks; 4 executed and 28 up-to-date).
- P5 fixture verifier printed
  `BLENDLIB_P5_FIXTURE_VALIDATION_OK rigid-two-node-palette skinned-single-joint-source`.
- `git diff --check` produced no output. The worktree is deliberately unstaged
  and includes P3--P5 implementation files because their phase Gates are not
  yet passed.
- After the model-identity, prepared-topology, and rigid-palette additions, a
  fresh serial `:blendlib-core:test --rerun-tasks` succeeded (six executed
  tasks), then the focused client animation/reload/render contract selection
  succeeded (11 executed tasks). The coordinator then ran a single-process
  `clean check`: `BUILD SUCCESSFUL`, 37 actionable tasks (24 executed, 13 from
  cache), followed by `buildRelease`: `BUILD SUCCESSFUL`, 32 actionable tasks
  (4 executed, 28 up-to-date). This serial rerun supersedes a prior concurrent
  core-test output-directory race; it found no source failure.
- After the explicit `sampleAndCache(...)` extraction path was added, its
  implementer ran the focused registry/LRU/source-boundary selection with
  `BUILD SUCCESSFUL` (11 executed tasks): registry 7 tests, bounded cache 3
  tests, and source boundary 1 test all had zero failures. An independent
  Java-25 verification runner then ran one serial current-worktree
  `clean check` with `BUILD SUCCESSFUL` (37 tasks; 21 executed, 16 from cache)
  followed by `buildRelease` with `BUILD SUCCESSFUL` (32 tasks; 4 executed,
  28 up-to-date). Both commands completed before any further Gradle command;
  `git diff --check` remained empty.
- After default-scene-root retention and the combined-load undeclared-`next`
  integrity repair, the coordinator independently forced
  `:blendlib-core:test --tests '*ModelAssetLoaderTest' --rerun-tasks` and
  `:blendlib-fabric-client:test --tests '*RenderContractsTest' --rerun-tasks`.
  Both were `BUILD SUCCESSFUL` (6 and 11 executed tasks). The loader test
  asserts exact immutable root ordering and `DESC-002` after a real clip has
  decoded; the render-contract fixture is only the mechanical constructor
  compatibility check.
- The subsequent independent serial Java-25 current-worktree verification ran
  `clean check` successfully (37 tasks; 24 executed, 13 from cache), followed
  by `buildRelease` successfully (32 tasks; 4 executed, 28 up-to-date), with
  empty `git diff --check`. It did not claim visual, performance, or Gate
  acceptance.
- After the latest presentation/socket supplement and its focused Gradle
  confirmations, the coordinator re-ran the standard verification on the
  current worktree: `clean check` was `BUILD SUCCESSFUL` (37 actionable tasks;
  19 executed, 18 from cache), `:blendlib-core:test` was `BUILD SUCCESSFUL`
  (6 tasks up-to-date), and `buildRelease` was `BUILD SUCCESSFUL` (32
  actionable tasks; 4 executed, 28 up-to-date). The separate isolated server
  and client startup-smoke sections below cover the same current code; neither
  is treated as visual acceptance.

Compilation continues to emit pre-existing Java serial warnings from
`BlendAssetLoadException` and a Fabric deprecation warning for
`EntityRendererRegistry`; neither is treated as a completed performance or
compatibility acceptance.

## P5 presentation socket marker completion

The current client adapter adds exactly one optional, immutable presentation
socket transform to `ModelRenderSnapshot`. The public skinned-entity builder
can select one descriptor-declared socket; extraction resolves it from the same
`ClientSkinnedExtractionFrame` that captured the CPU-skinned mesh, while submit
only consumes the frozen snapshot. `SkinnedSocketMarkerSubmitter` emits a small
RGB axis with `RenderTypes.lines()` through `SubmitNodeCollector` after the
entity dispatcher placement and in the exact order `root → unitsToBlocksScale
→ socket`. It contains no entity/world, controller, registry, loader, parser,
resource-I/O, asynchronous-render, or raw-OpenGL dependency.

The current Showcase client configures only
`blendlib_showcase:tip`. `SkinnedRenderBackendContractsTest` asserts immutable
handle/generation/skinned-frame retention, missing-socket suppression, CULLED
suppression, and the numerical transform result `root(10,20,30) +
socket(4,6,8) / 2 units-per-block = (12,23,34)`. The focused Java-25 Gradle
selection `:blendlib-fabric-client:test --tests
'*SkinnedRenderBackendContractsTest' --rerun-tasks --console=plain` passed
after that CULLED regression (11 executed tasks). The prior current-worktree
focused client extraction selection and Showcase contract/source-boundary
selection also passed (11 and 16 executed tasks respectively).

The final post-marker serial Java-25 `clean check` was `BUILD SUCCESSFUL` (37
actionable tasks; 18 executed, 19 from cache); `:blendlib-core:test` was
`BUILD SUCCESSFUL` (6 up-to-date tasks); and `buildRelease` was `BUILD
SUCCESSFUL` (32 actionable tasks; 4 executed, 28 up-to-date). This documents
implementation/build evidence only, not visual acceptance.

## Showcase asset path-safety correction

The P5 asset scripts are now pinned to the checked-in `D:\BlendLib` project.
Their exporter permits writes only to `blendlib-showcase` or the exact
`build/p5-showcase-animation-determinism/{first,second}` verification outputs;
the verifier's recursive cleanup is similarly confined. A negative Blender
test confirmed an attempted output root of `D:\BlendLib` stops at
`BLENDLIB-CLI-002` before glTF export, and an attempted source-writer root of
`D:\BlendLib\build` stops at `BLENDLIB-P5-FIXTURE-001` before Blender scene
mutation. The real two-export verifier then passed again.

Before that restriction, integration detected exactly three generated asset
files below an unintended untracked `D:\BlendLib\src` tree. The coordinator
verified the three paths and removed only that generated tree. A separate
completed-agent mistake had already removed its two accidental server-repo test
files; the coordinator verified the remaining
`D:\MinecraftFabricServer-26.1.2\blendlib-core` tree was empty and removed
only that empty directory. No official server, world, or user-authored file was
started, stopped, modified, or deleted.

## Isolated dedicated-server smoke

Command:

```powershell
.\gradlew.bat :blendlib-showcase:runServer --console=plain
```

Run directory: `D:\BlendLib\blendlib-showcase\run\server`.

The latest post-marker server loaded Minecraft 26.1.2, Fabric Loader 0.19.3, Fabric API
`0.154.2+26.1.2`, BlendLib with bundled API/core/common, Showcase, and its
public static-rigid model key in the SERVER environment. It reached
`Done (0.563s)!` and logged saving every dimension. The host's noninteractive
stdin was already closed, so no normal `stop` command could be delivered. After
confirming the exact Java command line contained both the isolated
`D:\BlendLib\blendlib-showcase` path and `-Dfabric.dli.env=server`, the
coordinator terminated only that child. Consequently Loom reported exit `-1`;
this is cleanup evidence, not a false clean Gradle success. No official server
or world was started, stopped, or modified.

## Real-client startup smoke

Command:

```powershell
.\gradlew.bat :blendlib-showcase:runClient --console=plain
```

Run directory/log: `D:\BlendLib\blendlib-showcase\run\client` and
`D:\BlendLib\blendlib-showcase\run\client\logs\latest.log`.

The latest post-marker real client loaded both BlendLib and the Showcase in
CLIENT context, including `fabric-lifecycle-events-v1 4.1.1+df84eb3d4c`,
initialized the Render thread, Indigo, LWJGL, OpenAL and resource reload. Its
BlendLib reload summary was `candidate_generation=1 active_generation=1
published=true stale=false models=4 missing=0 diagnostics=0`. No BlendLib or
Showcase crash report or JVM fatal-error file was created. The offline test
credential received Mojang/Realms 401 responses, and Loom warned that the empty
optional `build/resources/client` classpath directory was absent; neither is a
BlendLib visual result. The client remained in the noninteractive UI, so the
coordinator first verified the exact isolated Java command line contained both
`D:\BlendLib\blendlib-showcase` and `-Dfabric.dli.env=client`, then stopped only
that child. Loom consequently returned exit `-1`; this is controlled startup
cleanup evidence, not a clean Gradle exit or visual acceptance. It is not
evidence that a model, texture, axis, socket, skin, state transition, or F3+T
reload looks correct.

## Explicit non-passes

- ADR-015 is accepted and enforced: the selected default-scene reachable
  hierarchy is canonical, required scene-external joints/skins are rejected,
  and out-of-range events are load errors. The runtime and Showcase binding
  tests above do not replace the user's separate audit.
- The deterministic Showcase idle/walk/attack asset is wired into the Java
  runtime. A real-resource model-space socket regression, a client-only RGB
  socket marker, and a guarded presentation-only `attack_whoosh` particle
  consumer now exist and passed their current focused Gradle selections.
  Actual visual/F3+T evidence of the marker and animation remains outstanding.
- No human visual verification, F3+T reload verification, or P7 performance
  benchmark has been performed.
- P4 ADR-013/ADR-014 and user-managed visual conditions remain open.
- No phase commit, remote push, public release, production deployment, or
  license decision beyond the approved local metadata/GPL-scoped Blender add-on
  has been made.

## ADR-018 P5-only fallback fixture (2026-07-30)

The accepted fallback-schedule provenance decision is now implemented without
changing the normal P6-synchronized actor or its wire protocol:

- `ShowcaseEntities.P5_FALLBACK_ACTOR` registers the separately summonable
  `blendlib_showcase:p5_fallback_actor` with fixed server gameplay dimensions
  `0.60F × 1.80F`, tracking range `8`, and update interval `3`.
- `ShowcaseP5FallbackActorEntity` is a server-safe, stateless display host. It
  contains no `BlendAnimations`, P6 payload/store, animation trigger, synced
  animation data, or client dependency.
- Its client-only renderer uses exactly
  `skinnedAnimation((entity, request) ->
  ShowcaseAnimatedActorStateSchedule.stateAt(request.ageInTicks()))`; it has
  no synchronized-state selector. The normal `animated_actor` remains on
  `.synchronizedSkinnedAnimation(...)` unchanged.

Java 25/no-daemon verification completed with zero failures/errors:

```powershell
.\gradlew.bat --no-daemon :blendlib-showcase:test --rerun-tasks --console=plain `
  --tests com.liy.blendlib.showcase.ShowcaseP5FallbackActorFixtureContractsTest `
  --tests com.liy.blendlib.showcase.ShowcaseAnimatedActorStateScheduleTest `
  --tests com.liy.blendlib.showcase.ShowcaseAnimatedActorEntityContractsTest `
  --tests com.liy.blendlib.showcase.entity.ShowcaseP6EntityTriggerContractsTest
```

The result was `BUILD SUCCESSFUL in 22s`, 13 selected tests, zero failures and
zero errors; `git diff --check` had no output. The required isolated 132-tick
real-client observation remains WAITING, so this implementation evidence does
not make P5 or P6 PASS.

## 2026-07-30 dedicated-server evidence

The current P5 candidate now has a real phase-specific isolated server smoke.
`:blendlib-showcase:runP5SmokeServer` used only `run/p5-smoke-server`,
`127.0.0.1:25573`, and `blendlib-p5-smoke-world`; it reached
`Done (2.426s)!`, accepted console `stop`, saved all dimensions, and returned
Gradle exit `0`. Exact hashes and safety checks are retained in
`docs/evidence/P5-isolated-server-smoke-2026-07-30.md`.

Any generic `:blendlib-showcase:runServer` / `run/server` startup remains a
historical default-run observation. This closes only the P5 dedicated-server
item; P5 remains `IN_PROGRESS`, and no phase PASS, staging, or commit is
authorized by this smoke.
