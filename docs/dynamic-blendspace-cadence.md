# Positive dynamic blendspace cadence

This additive API varies the shared playback rate of one configured
[1D](synchronized-blendspace-1d.md) or [directional 2D](directional-blendspace-2d.md)
blendspace. The existing definitions, constructors, direct extraction signatures and renderer
configuration retain fixed cadence one unless the new API is explicitly used. Parameter changes
still choose mixture weights independently of cadence.

## Entity renderer

Configure the layers and a 1D or 2D blendspace first, then register an extraction-only cadence
callback. The callback returns a dimensionless multiplier, not a duration or an absolute speed:

```java
builder.skinnedAnimation((entity, request) -> IDLE)
    .animationLayerCues(layers, upperAttackCues)
    .animationLayerWeights(upperFadeWeights)
    .animationBlendSpace1D(space, (entity, request) -> entity.measuredHorizontalSpeed())
    .animationBlendSpaceCadence((entity, request) -> {
        double measuredSpeed = entity.measuredHorizontalSpeed(); // actual blocks per server tick
        double authoredReferenceSpeed = 0.06;                    // this fixture's authored choice
        return Math.max(0.5, Math.min(2.0, measuredSpeed / authoredReferenceSpeed));
    });
```

The same `animationBlendSpaceCadence` call follows `animationBlendSpace2D(...)` for a directional
consumer. Calling it before configuring either blendspace is an error. Keep the definition and
callbacks stable; do not construct a new definition every frame. This is consumer policy, not
stride-length inference. Capture actual collision-resolved displacement; a requested movement
velocity can disagree with movement when an actor hits a wall. For 2D, transform that displacement
into the chosen local axes for the mixture, and use its Euclidean horizontal magnitude for cadence.

Parameter and cadence are each captured once per extraction attempt. Both are validated, including
all member-derived rates, before external weight and cue callbacks/transactions. Callbacks must
not perform resource reads, mutate animation ownership or issue member commands. Standard
lifecycle guards abandon stale captures if a callback unloads, retires, disconnects or reloads.
An invalid captured input or failed prospective core frame preserves the previously committed
blendspace/core state. This does not promise rollback of all adapter clocks or callback side effects;
legacy base-controller limits are unchanged. The guarantee does not extend to a pose modifier or
event listener throwing after core publication.

## Bounds and rate compensation

A multiplier must be positive, finite and in `[1/64, 64]`. It must also satisfy the potentially
tighter bounds of every member. For raw clip duration `D`, positive descriptor state speed `S`,
authored common cycle `C`, and multiplier `M`:

- Effective raw-clip speed is `(D / C) * M`
- Command-speed multiplier is `((D / C) * M) / S`
- Both rates, including the actual rounded descriptor-speed product, must be in `[1/64, 64]`

Cadence one must already bind successfully; another multiplier cannot rescue an invalid baseline.
For the runnable fixtures, `C = 0.8` seconds. The 1D clips have durations `2 / 1 / 0.5` seconds
and descriptor speeds `0.5 / 1.5 / 2`; the 2D clips have durations
`2 / 1 / 0.5 / 1.5 / 2.5` and speeds `0.5 / 1.5 / 2 / 0.75 / 1.25`. Their complete authored
multiplier range `[0.5, 2]` satisfies the derived bounds. Because each derived positive rate is
linear in `M`, valid range endpoints bound all intermediate rates; packaged verification also
checks representative interior values.

The consumer maps measured speed divided by `0.06` blocks/tick into that `[0.5, 2]` range. Idle
therefore advances at `0.5x`; the lower clamp is intentional. At `1x`, the common cycle is `0.8`
seconds; when held at `0.5x` or `2x`, it is `1.6` or `0.4` seconds respectively. Changing a
multiplier partway through a cycle does not restart it. There is no zero/pause or reverse mode.

## Exact boundary semantics

At a successful extraction boundary, the elapsed interval advances with the **previously
committed** cadence. The newly captured value applies from this boundary onward. For example:

| Extraction time | Newly captured cadence | Published normalized phase |
| --- | --- | --- |
| 0.0 s | 0.5x | 0.000 |
| 0.2 s | 1.5x | 0.125 |
| 0.4 s | 2.0x | 0.500 |
| 0.5 s | 0.5x | 0.750 |
| 0.7 s | 1.0x | 0.875 |

These values use `C = 0.8`. Applying the newest rate retroactively to an elapsed interval, or to
all time since activation, would give different and incorrect phases.

All members, including zero-weight members, advance together. Ordinary cadence changes update a
complete immutable member-rate vector inside the existing core frame publication. They do not
issue commands, seek playheads, change accepted command sequences/watermarks, reset observer
occurrences or marker cursors, or restart transitions. An already active transition retains its
previous-source rate. Upper attacks and other nonmembers keep their own rates, weights and cues.

The activation retains normalized integrated phase, a monotonic time high-water mark, and held
cadence. Duplicate and backward timestamps do not advance or rewind the group; the last valid
capture at that boundary supplies the next held cadence. Generation reload rebinds current loaded
clip durations and descriptor speeds at the integrated phase. The clock retains bounded state,
not an unbounded history of cycles. A gap over the existing 600-second advance bound uses a silent
initialization-style discontinuity at the current phase; no skipped marker history is replayed.
If an entity is culled, the last committed cadence is held until extraction resumes. Unobserved
intermediate speed changes are not reconstructed.

Activation ownership is unchanged: source, owner, model and definition identify the activation.
Unload, explicit retirement, disconnect/play-init, or an intervening non-blendspace extraction
ends it. A changed identity starts a fresh phase-zero activation. Existing member-cue,
member-weight, rule-ownership and command-capacity checks still apply before live changes.

## Direct extraction and core binding

`SkinnedAnimationRuntime.extractBlendSpace(...)` and `extractBlendSpace2D(...)` have additive
overloads with `double cadenceMultiplier` immediately after the scalar/vector parameter and
before source/owner arguments. The existing pose modifier and optional event listener remain
in their original positions. Old signatures retain fixed multiplier one.

```java
runtime.extractBlendSpace(input, layers, commands, upperWeights, space, measuredSpeed,
    cadenceMultiplier, source, owner, proceduralPose, visualEventHandler);

runtime.extractBlendSpace2D(input, layers, commands, upperWeights, directionalSpace, localVelocity,
    cadenceMultiplier, source, owner, proceduralPose, visualEventHandler);
```

Low-level integrations can use `definition.syncGroup().bind(plan).rateUpdate(multiplier)`.
The resulting immutable update includes every member and belongs to that exact plan identity;
an equivalent or reloaded plan requires a fresh binding. Use `advanceBlendSpaceAtFrame(deltaSeconds, commands, weights, rateUpdate)`
rather than inventing per-member commands or time sources. The core frame advances old
rates, applies ordinary commands, validates/installs the whole update for future intervals, and
publishes once. A failure cannot leave only some members with the new cadence.

## Runnable opt-in and evidence

See the [runnable cadence instructions](../versions/modern/showcase/README.md#opt-in-positive-dynamic-blendspace-cadence).
The single client JVM flag `-Dblendlib.examples.blendspaceCadence=true` augments either existing
1D or 2D selection. It has no effect on default/discrete/IK modes. Without it, the original
fixed-cycle consumers and their checks remain unchanged.

The packaged verification loads the committed unequal-duration real GLBs from the built example
JAR through production reload. It exercises both extraction overloads through the same consumer
speed policy, with multiple cadence changes, idle/zero-weight progression, duplicate/backward
boundaries and independent actor clocks. At hand-calculated integrated phases it directly samples
raw GLB tracks and builds an independent ordinary layered frame from explicit clip-local
playheads and analytically calculated weights. It compares every layered transform, final
procedural socket and CPU-skinned vertex, not just state labels or phase arithmetic. It also
checks the authored upper marker, upper rate/fade independence, real nested attachments, no
extraction resource I/O, retained geometry, changed-descriptor-speed reload, silent long-gap
recovery and retirement/reconnect. Core/client tests separately exercise member marker boundaries,
atomic failure, transition/observer invariants and reloads whose raw clip lengths change.

There is no automatic footstep deduplication or marker leader: nonzero member layers retain their
existing independent marker semantics. Initialization, reload/recovery and zero-weight consumption
remain silent. This is headless evidence, not a native display/GPU or visual gait-quality claim.
Native graphics acceptance remains deferred. No root motion, stride warping, foot locking, arbitrary
clock, pause/reverse, exporter/private Blender project, schema/editor or network change is included.

## Preview build

```sh
bash gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true -Pblendlib_preview=cadence-preview build verifyRuntimeJar verifyRunnableExamples
```

Matching runtime, sources and example artifacts use `1.0.0-beta.4+26.3-cadence-preview`. This is a
cumulative development preview, not a replacement published release. Install only one matching
runtime/example pair alongside the required Fabric dependencies. The published beta.4 shader
alignment, both-depth-mode ShaderC/SPIR-V reflection coverage and incremental shader-resource
input guard are retained. No release, tag, merge or CurseForge publication is implied.
