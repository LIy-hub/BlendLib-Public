# P4 implementation evidence

Status: implementation evidence plus scoped real-client evidence. P4 is **not**
a Gate PASS: ADR-013, ADR-014, and ADR-019 are accepted and their runtime paths are
implemented; the static/rigid, item, resource-pack reload, and missing-model
paths now have isolated real-client evidence, while the remaining material
coverage and the separate P3/P4 audit are still open.

## Implemented non-conflicting vertical slice

- `BlendModelKey` maps a semantic ID to `blend_models/<path>.json` without I/O.
- Client reload uses final `ResourceManager` selection with prepare/apply generations,
  bounded descriptor/GLB reads, missing handles, and primary diagnostics.
- The 26.1.2 static/rigid backend uses public Blaze3D/Minecraft submission APIs,
  prebuilt immutable handles, units conversion, entity culling bounds, and a
  missing-model fallback. Submit paths do not perform resource I/O or JSON/GLB parsing.
- The Showcase contributes a server-safe, summonable `blendlib_showcase:static_rigid`
  entity and a client-only `staticRestPose()` registration through the public adapter.
- Showcase client compilation selects explicit JAR variants for the adapter while
  Showcase main compilation depends only on `blendlib-api`. Its isolated server
  runtime explicitly receives the normal BlendLib Fabric mod artifact.

## Root-coordinator automated build evidence

On 2026-07-29 with
`JAVA_HOME=C:\Program Files\Java\latest\jdk-25`, the root coordinator ran:

```powershell
.\gradlew.bat clean check :blendlib-core:test buildRelease --console=plain
git diff --check
git status --short --branch
```

Result: `BUILD SUCCESSFUL` (39 actionable tasks; 19 executed, 20 from cache);
`git diff --check` produced no output. This includes the API/core boundary tests,
client/Showcase tests, and `verifyShowcaseDependencyBoundary`.

## Reload generation-retirement integration

The real P4 reload `apply` path now obtains the result of
`ClientModelRegistry.publish(...)` and passes that final active generation ID to
a client-only callback. The client entrypoint injects
`ANIMATION_LIFECYCLE.registry()::retireOtherGenerations`; the reload package does
not import the animation package. This removes controllers and bounded pose-cache
entries from older model generations after a successful replacement, while a
late stale prepared generation receives the already-active generation ID and
therefore cannot retire current state.

The listener-level regression binds a generation-0 ephemeral instance and pose,
applies generation 1, then makes generation 2 active and applies a late
generation-1 candidate. It proves the older controller/pose are removed and
the generation-2 controller/pose remain present. The entrypoint lifecycle
regression protects the production callback injection.

On 2026-07-29 the root coordinator independently forced:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat :blendlib-fabric-client:test --rerun-tasks --console=plain
```

Result: `BUILD SUCCESSFUL`, 11 actionable tasks executed. The only compiler
warnings were the pre-existing core serial warning and the 26.1.2 Fabric
`EntityRendererRegistry` deprecation. An independent post-integration
`clean check --console=plain` also completed successfully (37 tasks; 21
executed, 16 from cache), and `buildRelease --console=plain` completed
successfully (32 tasks; 4 executed, 28 up-to-date). `git diff --check`
produced no output.

## Reload production summary and development diagnostics

The real reload `apply` path now constructs the candidate backend generation,
publishes it atomically, reports against the `ClientModelRegistry.publish(...)`
return value, and only then invokes the animation-generation callback. The
production sink writes exactly one INFO summary per apply in this stable form:

```text
blendlib_reload candidate_generation=… active_generation=… published=… stale=… models=… missing=… diagnostics=…
```

When the Java logger has DEBUG enabled, it additionally writes bounded,
escaped per-diagnostic fields (`severity`, `code`, `model_key`, `resource_id`,
`location`, `message`, and `cause_summary`). Primary missing diagnostics are
deduplicated by final active generation and model key, so delayed/repeated
stale applies add their required summary but cannot repeat a current
generation's primary detail. The deduplicator is reload-local; reload does not
depend on the render package or execute any logging from render submit.

The root coordinator independently forced
`:blendlib-fabric-client:test --tests '*ClientModelReloadListenerTest'
--rerun-tasks --console=plain` after this integration. The JUnit XML reports
12 tests, 0 failures, 0 errors, and 0 skipped. Regressions cover publication
ordering, summary fields, DEBUG-on/off behavior, stable escaped detail fields,
global diagnostics, stale-candidate deduplication, and re-emission in a newer
generation. This is automated diagnostic evidence only; it is not a visual
or P4 Gate PASS.

## Isolated resource-pack fixtures and final-selection regression

`test-assets/p4-resource-packs/` supplies two source-controlled, never-default
resource packs for the isolated visual/reload procedure. The local 26.1.2
runtime requires the post-64 range schema, so both `pack.mcmeta` files use
`min_format: 84` and `max_format: 84`. This was verified from the local runtime
error and `PackFormat$IntermediaryFormat` bytecode after the obsolete
single-field `pack_format` form was rejected.

- `valid-override` replaces only the descriptor at
  `assets/blendlib_showcase/blend_models/fixtures/static_model.json`. It
  references the committed P2 rigid GLB and external PNG from the baseline
  Showcase resources; this exercises a high-priority descriptor combined with
  lower-priority mesh/texture assets.
- `malformed-missing-mesh` replaces the same descriptor but names only the
  nonexistent `models3d/fixtures/does_not_exist.glb`, deliberately yielding a
  single missing-model primary diagnostic without a second texture fault.

`verify-p4-resource-packs.ps1` checks both metadata files, resource IDs,
paths, the baseline GLB/PNG SHA-256 values, and that neither fixture carries
the missing file. The root coordinator ran it successfully. It also forced:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat :blendlib-fabric-client:test --tests '*ClientModelReloadListenerTest' --rerun-tasks --console=plain
```

Result: `BUILD SUCCESSFUL`; the JUnit XML reports 8 tests, 0 failures, 0
errors, 0 skipped. The two added listener tests prove the final-resource
selection does not open the lower-priority descriptor, valid descriptor/GLB/
PNG composition becomes a loaded handle, and the malformed descriptor becomes
one `DESC_002` missing handle. See `test-assets/p4-resource-packs/README.md`
for the exact isolated copy/priority/F3+T procedure. These are preparation and
automated evidence only, never a visual or production acceptance claim.

After those fixture and listener-test additions, an independent final
`clean check --console=plain` with Java 25.0.2 completed successfully: 37
tasks, 18 executed and 19 from cache. It did not run an additional client or
server process and does not alter the outstanding manual/ADR Gate conditions.

## Isolated dedicated-server evidence

Command:

```powershell
.\gradlew.bat :blendlib-showcase:runServer --console=plain
```

Run directory: `D:\BlendLib\blendlib-showcase\run\server`.

After the dependency-boundary repair, the server log showed Fabric Loader loading
`blendlib 0.1.0-alpha.1+26.1.2` with bundled API/core/common artifacts in the
SERVER environment and `Done (0.433s)!`. `stop` was sent through the same
isolated session; it logged normal saving for all dimensions and exited with
code 0. No official server directory or world was touched.

## Real-client startup smoke evidence

Command:

```powershell
.\gradlew.bat :blendlib-showcase:runClient --console=plain
```

Run directory/log: `D:\BlendLib\blendlib-showcase\run\client` and
`D:\BlendLib\blendlib-showcase\run\client\logs\latest.log`.

The real client loaded both `blendlib` and `blendlib_showcase`; the Render thread
initialized Indigo/LWJGL/OpenAL and resource reload listed `blendlib_showcase`
twice. No BlendLib/Showcase exception or crash report was observed. Mojang/Realms
authentication 401 messages and an empty client-resource-directory warning are
external/development noise, not accepted visual evidence.

After the reload-generation-retirement integration, a second isolated
`runClient` smoke loaded Minecraft 26.1.2/Fabric Loader 0.19.3, BlendLib,
Showcase, Indigo, LWJGL, OpenAL, and the resource reload list without a
BlendLib/Showcase crash report. The noninteractive host did not provide a
visual acceptance surface. Before cleanup, the coordinator checked that the
only terminated Java child command line contained both
`D:\BlendLib\blendlib-showcase` and `-Dfabric.dli.env=client`; no matching
process remained afterward. Its Gradle `-1` cleanup result is not a startup
failure or a visual PASS.

## Accepted ADR-013 item marker/wrapper and ADR-014/ADR-019 material paths

The local project owner accepted ADR-013 and ADR-014 on 2026-07-29, then
accepted ADR-019's narrow single-sided exact-0.10 cutout matrix correction on
2026-07-30. The P4 adapter already implements their approved, public-API-only
paths:

- `BlendLibItemModelBindings` installs exactly one public
  `ModelLoadingPlugin.Context.modifyItemModelBeforeBake()` hook. It replaces
  only explicitly registered marker item IDs with
  `SpecialModelWrapper.Unbaked`; it does not register a literal
  `blendlib:model` codec, use reflection, or import a Fabric implementation
  package.
- `BlendLibItemSpecialRenderer` resolves a current immutable model handle in
  `extractArgument`. Its submit method consumes only that prepared argument
  plus vanilla pose/collector/light/overlay input; it does not look up a
  model, read a resource, parse JSON/GLB, sample animation, use raw OpenGL, or
  access a Minecraft global. Foil and outline inputs are deliberately not
  reinterpreted as culling or material intent in P4.
- Showcase registers the server-safe item
  `blendlib_showcase:static_rigid_item`; its ordinary marker JSON remains
  `minecraft:model` / `minecraft:item/stick`, and the explicit BlendLib
  binding lives only in the Showcase client source set.
- The strict material mapper accepts only verified 26.1.2 public paths:
  opaque/single-sided -> `entitySolid`, cutout/single-sided ->
  `entityCutoutCull`, cutout/double-sided -> `entityCutout(..., false)`, and
  translucent/double-sided -> `entityTranslucent(..., false)`. Additive,
  opaque/double-sided, translucent/single-sided, and non-equivalent cutout
  thresholds produce a missing handle with `BLENDLIB-MAT-004` and a
  field-specific JSON Pointer. Emissive remains independent fullbright vertex
  lighting.

ADR-019 does not alter production mapper or backend logic: it aligns the
fixture/test/document classification with the already-checked
`entityCutoutCull` route for `cutout`, `double_sided=false`, and exact `0.10`.
Its two lit/emissive fixture rows are supported inputs, but still require fresh
isolated real-client evidence; this source/test alignment is not a material
visual result or P4 Gate PASS.

On 2026-07-29 the root coordinator ran:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat :blendlib-fabric-client:test :blendlib-showcase:test --rerun-tasks --console=plain
```

Result: `BUILD SUCCESSFUL` (18 executed tasks). This includes the marker
binding/snapshot/no-submit-I/O contracts, material mapper and reload diagnostic
tests, the updated integration expectations, and Showcase's client/main source
boundary check. It is automated evidence only, not item or material visual
acceptance.

The coordinator then added and ran an exact before-bake callback regression:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat :blendlib-fabric-client:test --tests '*BlendLibItemAdapterContractsTest' --rerun-tasks --console=plain
```

Result: `BUILD SUCCESSFUL` (11 executed tasks). It invokes the public
`ModelModifier.BeforeBakeItem.Context` contract directly: only an explicitly
registered item ID becomes `SpecialModelWrapper.Unbaked`, with the expected
vanilla base model and empty extra transform; an unregistered item returns the
original unbaked model unchanged.

The independent runner then completed the required Java-25 sequence on this
same worktree: `clean check` (37 tasks, 21 executed/16 cached),
`:blendlib-core:test` (success), `buildRelease` (32 tasks, 4 executed/28
up-to-date), and `git diff --check` (no output).

The current isolated server smoke reached `Done (0.514s)!` after loading
BlendLib and Showcase, then saved all three dimensions. The noninteractive
host closed stdin, so the coordinator verified the exact
`D:\BlendLib\blendlib-showcase` server child command line and stopped only
that isolated child after the save. Gradle consequently reported exit `-1` for
cleanup; this is not a failure to reach `Done` and no formal server/world path
was touched.

The earlier pre-P5-wiring isolated real-client smoke completed normally
(`BUILD SUCCESSFUL` in 44 seconds). It loaded the client environment,
BlendLib, Showcase, Indigo, LWJGL, OpenAL, and the Showcase resource pack; no
Showcase/BlendLib crash report or matching client process remained afterward.
Its historical reload summary reported four discovered handles with two missing
P5 skinned assets in the shared Showcase resource tree. That retained log is
not a P4 static-item visual result and does not describe the current resource
state: the current isolated client reload reports `models=4 missing=0`.
Neither startup summary is visual/reload acceptance evidence; the user-managed
P4 visual/F3+T observations and the accepted ADR-013/ADR-014 item/material
observations remain WAITING. The empty client-resource-directory warning and
offline Realms authentication messages are development noise.

## Real isolated client evidence

The root coordinator completed the fixed baseline -> valid override -> baseline
restore -> malformed override -> baseline restore sequence in the isolated
Showcase client. It includes real resource-pack priority UI, real F3+T reloads,
static/rigid entity visuals, first-person item visual evidence, missing-model
fallback, and the exact namespaced diagnostics command. The durable evidence,
command output excerpts, screenshot hashes, and remaining limits are recorded
in `docs/evidence/P4-manual-client-2026-07-29.md`.

## Explicit non-passes

- The accepted exact material subset has no complete real-client
  culling/texture/emissive matrix observation yet; unsupported combinations
  must remain explicit `BLENDLIB-MAT-004` missing-model results.
- The ADR-019 exact-0.10 single-sided cutout lit/emissive rows are newly
  classified as supported but have no fresh real-client observation yet; they
  remain P4 material-matrix WAITING rather than a visual/Gate PASS.
- The user-managed separate P3/P4 audit remains outstanding. This evidence
  does not make P3 or P4 a Gate PASS, authorize staging/commit as a passed
  phase, or replace later P5--P8 visual/synchronization/performance gates.

## 2026-07-30 dedicated-server evidence

The current P4 candidate now has a real phase-specific isolated server smoke.
`:blendlib-showcase:runP4SmokeServer` used only `run/p4-smoke-server`,
`127.0.0.1:25572`, and `blendlib-p4-smoke-world`; it reached
`Done (4.945s)!`, accepted console `stop`, saved all dimensions, and returned
Gradle exit `0`. Exact hashes and safety checks are retained in
`docs/evidence/P4-isolated-server-smoke-2026-07-30.md`.

Any generic `:blendlib-showcase:runServer` / `run/server` startup remains a
historical default-run observation. This new smoke closes only the P4
dedicated-server item; it does not change P4's `WAITING` audit or real-client
material requirements and does not authorize a Gate PASS, staging, or commit.
