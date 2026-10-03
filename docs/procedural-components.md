# Procedural rotation components

The client adapter provides reusable `ClientAnimationPoseModifier` implementations in
`com.liy.blendlib.fabric.client.animation.runtime.procedural`. They derive immutable local
poses after animation sampling/blending. They never change node membership, translation,
scale, the cached input pose, or authoritative gameplay state.

## Consumer example

```java
var inertia = new SpringInertiaPoseComponent("head", 3.0, 256);
var components = ProceduralPosePipeline.of(
    new LookAtPoseComponent("head", new Vec3(0, 0, 1), 0.8f,
        context -> targetsInModelSpace.get(context.instanceKey())),
    inertia,
    new ChainFollowPoseComponent(List.of("head", "antenna"), 0.35f),
    new RotationLimitPoseComponent("head", Quaternion.IDENTITY,
        (float) Math.toRadians(65)));

// The entity builder's low-level component entry point:
builder.poseComponents(components);
```

Supply a finite, non-null target for every visible instance. Targets above are **model-space**
positions, not world coordinates: the consumer converts world positions into the model's
coordinate frame before providing them. `Quaternion.IDENTITY` is only an appropriate reference
limit for an identity-oriented rest joint; use the joint's authored local rest rotation otherwise.
The existing low-level runtime `poseModifier` overload also accepts these components directly.
The rotation-only entity `poseModifier` callback has a different signature; use `poseComponents`
for this package.

## Components and ordering

- `LookAtPoseComponent(node, localForward, weight, target)` aims the joint's local forward axis
  at the target, taking the posed ancestor rotations, scales, and translations into account.
  The shortest rotation preserves existing roll as far as possible; there is no independent
  up-vector/roll constraint. A coincident target is a no-op; exactly opposite directions choose
  a deterministic perpendicular axis. Weight is in `[0, 1]`.
- `ChainFollowPoseComponent(nodes, weight)` requires a direct parent-child path with at least
  two distinct names. Each follower blends toward its predecessor's **incoming local rotation**.
  Local axes/rest conventions must match. All predecessors are read from the incoming pose, so
  a newly changed head does not recursively overwrite the whole chain in one call. Combine
  with per-joint springs for temporal lag. This is a rotation-follow effect, not a positional
  solver or IK.
- `SpringInertiaPoseComponent(node, frequencyHz, maximumInstances)` tracks the incoming local
  rotation using a critically damped spring on the principal relative rotation vector. Natural
  frequency is in `(0, 1000]` Hz. Higher values follow more quickly. It analytically integrates
  each interval against a stationary target; the quaternion tangent-space approximation for
  changing, multi-axis targets is intended for visual secondary motion, not rigid-body physics.
- `RotationLimitPoseComponent(node, reference, maximumRadians)` bounds the total shortest-arc
  angular distance from a specified local reference in `[0, pi]`. It is a spherical angular
  limit, not independent Euler-axis or swing/twist limits. Equivalent quaternion signs behave
  identically. Place limits last if the final visible pose must always respect the bound.
- `ProceduralPosePipeline.of(...)` runs in declaration order and forwards resets. Empty pipelines
  return the input pose. Unique node names resolve through the current rig on every invocation;
  missing or ambiguous names and invalid chains fail clearly.

## State and lifecycle

Stateless components can be shared. A spring holds a bounded LRU map keyed by `BlendInstanceKey`.
Every entry also checks model key, asset generation, and resolved node index. Rebinding or reload
therefore starts fresh; no state crosses entities or generations. Do not reuse one spring object
at two different stages in the same pipeline. Use separate objects for independent simulations.
Do not use stateful springs for stateless item identities shared by multiple render instances.

Time comes from `clientGameTimeInTicks / 20`, not looping clip time. First evaluation snaps to its
input. Repeated extraction at the same time returns the same simulated rotation without taking
another integration step. Backward time or a gap greater than 0.5 seconds snaps to the input and
clears velocity. Other input channels still come from the current pose. Clock resets and long
visibility gaps are handled automatically; teleports cannot be inferred from rotation-only input.

```java
components.reset(instanceKey); // Entity removed, teleported, or otherwise discontinuous
components.reset();            // World/session shutdown or explicit full reset
```

The consumer owns lifecycle reset calls. `retainedInstanceCount()` supports diagnostics; eviction
bounds memory even if a removal notification is missed. An evicted instance resumes from its next
input, without a catch-up simulation. Spring methods are synchronized, but mutable target suppliers
still need the application's ordinary client-thread discipline.

## Verification

`ProceduralPoseComponentsTest` covers parent-space look-at, opposite/coincident targets, ordered
composition, chain validation, reference-relative limits, quaternion sign equivalence, preservation
of channels, instance isolation, reload/clock/gap reset, retention bounds, convergence, and
stationary-axis frame-rate independence.

Run the normal Java 25 test suite:

```sh
./gradlew :blendlib-fabric-client:test --tests '*ProceduralPoseComponentsTest'
```

`ProceduralPoseConsumerProbe.main` is an executable, assertion-based consumer example with no
Minecraft runtime or JUnit dependency. It exercises the same public modifier contract and component
composition using a prepared test rig. The JUnit suite also invokes it.
