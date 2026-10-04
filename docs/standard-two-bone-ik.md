# Standard two-bone IK

`TwoBoneIkPoseComponent` connects analytic two-bone inverse kinematics to the ordinary
entity `poseComponents` pipeline. It works on the sampled/blended pose, before final
node/skin palettes and sockets are extracted. It does not require an experimental X3
plan. The existing X3 solver, snapshot provenance, and public APIs remain unchanged.

## Minimal integration

```java
import com.liy.blendlib.core.model.Vec3;
import com.liy.blendlib.fabric.client.animation.runtime.procedural.TwoBoneIkPoseComponent;
import com.liy.blendlib.fabric.client.animation.runtime.procedural.TwoBoneIkTarget;
import com.liy.blendlib.fabric.client.animation.runtime.procedural.WeightedPoseComponent;

var arm = new TwoBoneIkPoseComponent("shoulder", "elbow", "hand", context ->
    new TwoBoneIkTarget(new Vec3(1.2F, 1.4F, 0.3F), new Vec3(0, 2, 1)));
builder.poseComponents(new WeightedPoseComponent(arm, context -> 1.0));
```

Use three distinct **unique node names**, connected directly as shoulder -> elbow -> hand.
They need not be axis-aligned, and the shoulder can have animated/rotated/scaled ancestors.
The solver resolves indices from the current rig every time, so reordered nodes after reload
cannot reuse stale indices. The end position is the hand node origin: an offset socket or an
attached object's visual tip is a different point and is not automatically targeted.

Both target and pole are **positions in animated model/asset space**, before the entity's
root transform and before world placement. A world-space target must first be converted by
the consumer. The pole chooses the bend side by its projection on the plane perpendicular
to the root-to-target direction. It is not an end orientation or a joint-angle constraint.
The function returns one immutable `TwoBoneIkTarget`, sampled exactly once per valid invocation.
No resource I/O, target polling, world raycast, networking or controller is added.

## Pose and weighting contract

Only the shoulder and elbow local rotations are replaced. The exact node domain, every local
translation/scale, the hand local rotation and all other local transforms remain unchanged.
The input is immutable and never written. Rotating a parent can still move all its descendants
in model space. The component uses the incoming pose from preceding stages, not a cached bind
pose. Current positive uniform local scales, including ancestor and joint scales, contribute
to model-space segment lengths.

Use the existing [weighted wrapper and masks](procedural-components.md#dynamic-weights-and-node-masks)
for fades. An empty mask affects all nodes; a mask containing both joint names is explicit.
At weight zero the solver and observer still run, but the wrapper returns the exact input.
At partial weight, a one-joint mask, or with a rotation limit/other modifier after IK, the
final endpoint generally no longer reaches the solver's effective target. Diagnostics describe
the unweighted solve at that pipeline stage. They do not assert what a later pipeline outputs.

The component is stateless and may be shared between entities/rigs. Both inherited reset methods
are no-ops; enclosing pipelines/wrappers retain their existing reset forwarding. Suppliers and
observers follow the application's client-thread discipline. Identical pose/target input produces
identical output; repeated extraction invokes the supplier again and does not advance simulation.

## Outcomes, singularities and errors

The optional fifth constructor argument receives `(context, result)` once after a solve,
including a degenerate unchanged-pose result:

```java
var arm = new TwoBoneIkPoseComponent("shoulder", "elbow", "hand", targetSource,
    (context, result) -> diagnostics.accept(context.instanceKey(), result));
```

`targetSource` and `diagnostics` above are consumer-owned callbacks. Keep them bounded; do not
retain an unbounded history or log every frame. The result contains:

- `status`: `REACHED`, `CLAMPED_NEAR`, `CLAMPED_FAR`, or `DEGENERATE_CHAIN`
- `effectiveTargetModelSpace`: the clamped solve target, or the unchanged end position for
  a degenerate chain; floating-point endpoint agreement is approximate, not bit-identical
- `usedDirectionFallback`: the target is extremely close to the root, so the current first
  segment supplies a deterministic direction
- `usedPoleFallback`: the pole projection is tiny/collinear, so a deterministic perpendicular
  supplies the bend plane

For model-space segment lengths `a` and `b`, the reach interval is
`[abs(a-b)+g, a+b-g]`, with `g = min(min(a,b)*0.25, max(1e-7, (a+b)*1e-6))`.
The shorter-segment cap keeps the interval valid for unequal lengths. Targets at the exact
inner/outer physical boundary are slightly inset to avoid unstable singular configurations.
If either segment is at most `max(1e-7, max(a,b)*1e-7)`, no rotation is changed and the result is
`DEGENERATE_CHAIN`. At a root-coincident target the chain chooses a deterministic direction,
then clamps near; no history-based foot lock or bend continuity is promised across singularities.

Strict v1 `Transform` rejects zero/negative/non-uniform scale before this component. Bake those
transforms into the asset. No shear, reflected rig, matrix inversion or arbitrary glTF scale
support is introduced. Double geometric intermediates avoid float dot/subtraction overflow;
posed transforms and returned positions/rotations must still fit the finite float model format.
Extreme compositions that overflow/underflow fail before a pose is returned.

Missing/ambiguous names, disconnected chains, wrong pose node sets, null callbacks/results,
and unrepresentable composed transforms fail descriptively. The ordinary runtime intentionally
propagates invalid modifier configuration; it does not automatically swallow these errors.
Resource-pack-aware consumers must validate the current rig or catch the specific configuration
failure and return the incoming pose with a bounded diagnostic. Target/observer callback
exceptions also propagate. Never treat a failed callback as a successful solved frame.

## Runnable 26.3 consumer

The [runnable example](../versions/modern/showcase/README.md) includes an opt-in three-joint
mechanical arm, moving target marker and a marker attached to the actual final end socket.
Enable `-Dblendlib.examples.twoBoneIk=true` at client startup and follow that guide's command.
Existing demo defaults stay unchanged. The headless example check loads the packaged arm asset,
uses the real animation extraction pipeline, and compares the final socket/attachments to the
moving target, including posed ancestors, transformed scene roots, non-unit asset units and resource reload.

Build/check the complete optional consumer with Java 25:

```sh
bash gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true \
  build verifyRuntimeJar verifyRunnableExamples
```

Focused tests live in `animation/runtime/procedural` and run on the official 26.3 source set.
Native world/window rendering remains unverified and deferred; headless socket correctness
is not graphical acceptance. There is no terrain sampling, foot lock, FABRIK, arbitrary-length
chain, end orientation, server gameplay authority or new network protocol.
