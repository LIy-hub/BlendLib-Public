# Fixed-cycle directional 2D blendspace

`AnimationBlendSpace2D` adds opt-in continuous center + directional-ring mixing. It shares the
[1D fixed-cycle scheduler and ownership/lifecycle contract](synchronized-blendspace-1d.md),
including explicit inactive zeros, unequal clip lengths, descriptor-speed compensation,
zero-weight clock progression, monotonic time, reload phase retention and silent large-gap
recovery. Existing 1D APIs and default/discrete locomotion remain unchanged.

## Definition and input

```java
var space = new AnimationBlendSpace2D(IDLE_LAYER, List.of(
    new AnimationBlendSpace2D.Direction(0, FORWARD_LAYER),
    new AnimationBlendSpace2D.Direction(Math.PI / 2, LEFT_LAYER),
    new AnimationBlendSpace2D.Direction(Math.PI, BACK_LAYER),
    new AnimationBlendSpace2D.Direction(3 * Math.PI / 2, RIGHT_LAYER)), 0.14, 0.8);

builder.skinnedAnimation((entity, request) -> IDLE)
    .animationLayerCues(layers, upperAttackCues)
    .animationLayerWeights(upperFadeWeights)
    .animationBlendSpace2D(space, (entity, request) -> {
        double yaw = Math.toRadians(entity.getYRot());
        double dx = entity.measuredWorldDx(); // actual collision-resolved blocks per tick
        double dz = entity.measuredWorldDz();
        return new AnimationBlendSpace2D.Input(
            -Math.sin(yaw) * dx + Math.cos(yaw) * dz, // local forward
             Math.cos(yaw) * dx + Math.sin(yaw) * dz); // local left
    });
```

This example chooses X=forward, Y=left. The core API imposes no world/yaw convention. Minecraft
yaw zero faces +Z, and +90 degrees faces -X. Capture both coordinates from the same observation;
transform into local axes before solving. The callback is invoked once per extraction before
external weights and commands. Null, nonfinite, or out-of-bounds inputs fail before live clock
mutation. Do not mix requested target speed with measured velocity.

The immutable definition requires:

- One center layer and 3–15 unique directional layers (the entire plan, including other layers,
  still has at most 16 controllers)
- Finite canonical angles in `[0, 2*pi)`, sorted internally; no implicit wrapping
- Every adjacent angle gap, including the wrap seam, in `[0.000001, pi-0.000001]` radians;
  duplicate/near-duplicate directions and half-plane-only rings are rejected
- A common radius in `[0.000001, 1000000]` and finite input coordinates within `+/-1000000`
- A fixed common cycle in `(0, 600]` seconds, with existing command/effective rate bounds

All members must resolve to positive-duration continuous looping, unit-weight, independent
OVERRIDE layers with equal priority and resolved masks. `syncGroup()` is a stable immutable
ownership/binding object shared with the 1D implementation. The old 1D `bind()` signature remains.
`bind()` rejects unknown layers and incompatible metadata before live mutation.

## Solver semantics

The origin is exactly idle. A nonzero vector selects its containing adjacent angular sector;
triangle barycentric weights blend its two ring members and center. All other members are
explicitly zero. Values outside the polygon are clamped along their radial ray to the polygon
edge. This is a polygon hull, not a unit-circle speed clamp: for the four cardinal samples,
`abs(forward)+abs(left) >= radius` removes idle. Diagonal full-motion thresholds therefore differ
from cardinal thresholds. It does not promise isotropic constant speed or infer stride length.

The wrap seam is handled once, with bounded angle selection; there is no angle-wrapping loop.
Authored angular rays within eight ULPs of `2*pi` (about `7e-15` radians) are handled directly
before division by a near-flat sector determinant. Dimensionless barycentric residue up to `1e-10` snaps to zero, and hull sums within `1e-10`
snap to the boundary, so mathematically inactive members cannot leak marker events through tiny
trigonometric/float residue. Weights remain nonnegative and sum to one within float precision. Axes, center and boundary
choices are deterministic. There is no arbitrary triangulation or extrapolation.

## Playback and events

The solver changes weights only. It never changes cadence, seeks, restarts or increments
member command sequences during ordinary movement, direction changes or idle stop/start.
All clips, including zero-weight directions and idle, advance on the same normalized cycle.
Authored gait/contact phase must align at cycle zero. Changing input speed does not rescale time.

The existing 1D activation identity, generation cache, cue transaction, ownership checks, reload,
retirement, disconnect, duplicate/backward-time handling and exceptional large-gap recovery apply
unchanged. Explicit member commands or dynamic multipliers are rejected, even when zero; upper
attacks and other nonmembers remain independent. Initialization/reload consume markers silently;
ordinary nonzero members can each emit their own events with existing per-frame bounds. There is
no automatic footstep deduplication, contact matching, root motion, foot locking or phase inference.

## Runnable consumer and verification

The [directional example](../versions/modern/showcase/README.md#opt-in-directional-2d-blendspace)
uses a separate real GLB with idle/forward/left/back/right durations `2/1/0.5/1.5/2.5` seconds,
nonunit descriptor rates and a `0.8` second shared cycle. Actual collision-resolved server
horizontal displacement is carried by ordinary consumer entity data and transformed by actor yaw
at capture. No BlendLib synchronization schema or network protocol changes. A smooth circular
trajectory with idle intervals demonstrates seams and stop/start. Upper attack, procedural pose,
attachments, material appearance, named skins and read-only inspection remain available.

Tests cover bounded/invalid inputs, immutable sorting and duplicate policy, random normalization,
seams, polygon clamping, explicit zeros, unchanged 1D behavior, ownership/preflight transactions,
reload with changed rates/durations, lifecycle fences and zero-weight event consumption. Packaged
verification independently samples raw GLB clips and compares manual fixed-pose layered frames to
final poses and CPU-skinned vertices, including upper/procedural/attachment coexistence.

This is headless verification, not native visual or gait-quality acceptance. Native graphics
remains deferred. No exporter/private project changes, schema/editor, dynamic cadence or release.

## Preview build

```sh
bash gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true -Pblendlib_preview=directional-preview build verifyRuntimeJar verifyRunnableExamples
```

Runtime, sources and runnable example use `1.0.0-beta.4+26.3-directional-preview`. The released
beta.4 shader alignment, both-depth-mode ShaderC/SPIR-V reflection tests and incremental shader
resource input guard are retained. Do not install release and preview runtime JARs together.
