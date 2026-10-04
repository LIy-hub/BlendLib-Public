# Standard two-bone IK verification

Scope: the additive ordinary `TwoBoneIkPoseComponent`, immutable target/pole/diagnostic values,
weighted integration and opt-in 26.3 mechanical-arm consumer. The X3 source/provenance contract
is unchanged. This is a development branch, not a release or native graphics acceptance.

## Behavior covered

- Actual endpoint reach from incoming non-axis-aligned joint rotations, several rotated/scaled
  ancestors, arbitrary node indices and current-generation rig name resolution
- Guarded far/near reach, exact physical singularities, root target, opposite direction,
  both pole sides and deterministic collinear/coincident pole fallback
- Zero, tiny and highly disproportionate segment lengths; safe unchanged-pose degeneration;
  finite huge positions/targets and explicit composed scale/position/numerical failures
- Immutable input, exact node/translation/scale preservation, unchanged end/unrelated local
  transforms, single target/diagnostic callbacks, and callback failure propagation
- Weight 0/0.5/1, named mask composition and ordered pipelines; zero weight still runs the
  solver, while partial weights/masks are not incorrectly advertised as endpoint reach
- Stateless repeated extraction, shared component across entities/rigs/generations and reset
- Exact additive class/constructor/method/record/enum ABI and generic callback signatures;
  executable consumer through the existing public modifier/component interfaces
- Existing X3 deterministic solver, exact-plan provenance and presentation/source boundaries,
  additionally selected into the official 26.3 tests rather than only the root build

## Packaged consumer proof

The arm is an actual three-joint skinned GLB with authored volumetric links/joints/end fork,
a strict descriptor, an opaque color atlas, sockets and a clip animating its ancestor's rotation
and positive uniform scale. A separately enabled ordinary entity renderer consumes the component.
The cyan target cage is a prepared model; the gold endpoint model is placed using the actual
post-IK extracted socket. The two models remain distinct, so the consumer cannot pass simply by
rendering two copies of the requested target.

`verifyRunnableExamples` loads resources from the packaged consumer JAR, validates them through
the strict loader/reload pipeline, invokes `SkinnedAnimationRuntime.extract` and checks final
model/entity/world-space sockets and attachments across motion, three generations, repeated
extraction, separate owners and retirement/disconnect. It checks CPU-skinned geometry moves,
retained snapshots stay immutable and authored culling bounds contain the sampled scene.
The resource-pack guard preserves the incoming pose with a bounded diagnostic for renamed,
ambiguous or disconnected required chains; unrelated target/solver errors are not swallowed.
The optional property defaults off and existing consumer modes remain unchanged unless selected.

## Local results and review

- Official 26.3 full suite: 288 tests passed, plus build/runtime-JAR/runnable-consumer verification
- Focused component/ABI/consumer suite: 32 tests, no failures, errors or skips
- Mechanical-arm scene and transformed resource-pack regression: 147 actual runtime extractions
  and 70,560 CPU-skinned vertices; maximum final socket error `6.769786295990343E-7` authored model units
- Additional deterministic actual-component probe: 10,000 randomized non-axis-aligned chains
  with rotated/scaled ancestors; maximum observed absolute endpoint error `3.2428433769382536E-6`
- Root client suite: 769 tests passed; core suite: 324 passed. The inherited showcase Linux
  symlink-policy assertion is the only root aggregate failure after using the existing local
  test-JVM wrapper to keep launcher text out of exact `javap` comparisons
- The root source-boundary check also exposed a literal identifier substring collision in the
  new callback variable; it was renamed without changing API descriptors or weakening the guard.
  That boundary check now also runs in the official 26.3 selection

The independent focused review found one consumer coordinate-space bug: a valid resource pack
with a transformed `ArmScene` displaced the target cage even though the solver endpoint was
correct. The fixed consumer inverts the captured origin socket's model translation/rotation/scale
before unit conversion. A regression first reproduced the original marker failure, then passed
with translated, non-axis-rotated and uniformly scaled `ArmScene`, `units_per_block = 2.5`, and
nonidentity renderer placement. Both marker centers match the true final socket across the new
49-frame cycle. The review found no other correctness/compatibility defect; its independent
3,780-case numeric boundary probe included 504 exact unchanged-pose degeneracies and observed
maximum endpoint error/chain length `8.6801E-7`.

## Commands and evidence

```sh
bash gradlew -p versions/modern -Pminecraft_version=26.3 \
  test --tests '*TwoBoneIk*'
bash gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true \
  build verifyRuntimeJar verifyRunnableExamples
bash gradlew check --continue
```

Final counts, exact local/remote content-equivalent source tree, JAR hashes, focused/full logs and
exact-head GitHub workflow/job outcomes are recorded in the cumulative delivery's `BUILD_IDENTITY.json`
and `evidence/` files. The normal Build, all 15 compatibility targets and focused 26.3 workflow are
required; a successful initial/source-only plan commit is not substituted for implementation CI.
The existing 26.3 workflow explicitly includes this branch.

Local sandbox startup uses the inherited selector-provider shim. Official GitHub CI uses the
normal JDK/Gradle/Loom tools without that shim. The inherited root Linux symlink-policy assertion
must be distinguished from IK/client/core checks; it is not converted into a feature success.

## Remaining boundaries

No window/world/visual result, dedicated server, broad multiplayer, hardware performance,
Iris/Sodium or experimental GPU acceptance is claimed. Headless endpoint error is numerical
proof only. No terrain sampling, foot locking, arbitrary chain solve, orientation constraint,
new network protocol, server gameplay control, merge, tag, release or CurseForge upload.
