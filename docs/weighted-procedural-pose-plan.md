# Weighted procedural pose composition plan

## Grounded scope

Animation v2 already exposes fixed layer weights and named bone masks. Procedural components
already compose in declaration order, but their pipeline cannot blend a whole component or
sub-pipeline dynamically against its incoming pose. Add one `WeightedPoseComponent`, using the
existing extraction context, component lifecycle and quaternion interpolation.

## Contract

- A context weight supplier returns a finite value in [0,1]; an immutable named-node weight map
  applies an additional per-node multiplier. Empty map means all nodes, omitted names in a
  nonempty map mean zero. Resolve names against the current rig each invocation.
- Evaluate the delegate exactly once even at zero weight, preserving spring progression.
  Validate its complete rotation-only result before masking/blending; never hide invalid channels.
- Blend incoming to delegated local rotations by shortest-arc slerp. Preserve translations,
  scales and node membership exactly. Zero contribution returns the original pose.
- Forward per-instance and full resets. Retain no new instance state or clock.
- Preserve every existing API contract. Explicitly pin new public signatures.

## Delivery sequence

1. Plan commit on a separate stacked feature branch.
2. Component implementation, executable consumer fade example, live showcase usage, documentation.
3. Direct contract/math/lifecycle/validation tests, API guard and applicable Java 25 builds.
4. One focused review, correct actual findings, publish branch and verify exact remote CI terminal state.
5. Deliver source/evidence archive. Native graphics verification remains deliberately deferred.
