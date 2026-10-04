# Standard two-bone IK implementation plan

## Grounded gap and scope

The experimental `DeterministicTwoBoneIkSolver` consumes an exact-plan X3
`ClientIkRigSnapshot`. Standard `poseComponents` consumes `ClientAnimationPoseContext`
and `LocalPose`. Connect the ordinary path additively; do not synthesize X3 snapshots,
change provenance, alter old solver behavior, or introduce a second animation engine.

Base: developer-entry remote `34ef323adf8a727611b238cd12edbfe33ba0b446`, with the
named-skin runtime `dbc4bffc90075db02bfe335eac0c0f6c120d8ae0`. Retain its quickstart.

## Contract decided before implementation

- Add `TwoBoneIkPoseComponent` and immutable `TwoBoneIkTarget` model-space target/pole
  positions. Resolve three distinct, unique names in the current generation's rig,
  requiring the direct root -> middle -> end hierarchy. Require the exact pose node set.
- Sample the target callback once per invocation. The component is stateless; it owns no
  entity cache, resource lookup, timing system, network state or lifecycle bookkeeping.
- Compose the incoming local pose through all ancestors, including rotated ancestors and
  positive uniform scale. Preserve every translation/scale, the end local rotation,
  unrelated nodes and input immutability. Replace only root/middle local rotations.
- Reuse the established analytic two-bone construction, in a small internal ordinary-pose
  helper if useful. Keep the legacy X3 implementation and exact-plan guards unchanged.
- Clamp distances to the reachable interval with a scale-relative numerical guard.
  A target at the root uses the current first-segment direction. A collinear pole uses a
  deterministic perpendicular. Zero-length/too-small segments retain the exact input.
- Surface outcomes through an optional immutable diagnostic observer, without retaining
  mutable last-result state. Bad chain/name/pose inputs and non-finite/overflow calculations
  fail descriptively before returning a pose. No invalid quaternion may enter output.
- Strict v1 `Transform` already rejects non-positive and non-uniform node scales. Composed
  numerical underflow/overflow is rejected; no matrix inversion, shear or reflected-scale
  claim is made. Extremely small degenerate segments use the safe unchanged-pose result.
- Existing `WeightedPoseComponent` supplies blending and named masks; zero weight still
  runs the delegate. Later limits/modifiers, partial weights or one-joint masks can move
  the endpoint off the target, which is documented rather than called a solver failure.

## Real consumer and verification

Add an opt-in 26.3 three-joint mechanical arm with packaged GLB/descriptor/textures,
ordinary renderer registration, moving model-space target and final end-socket markers.
Headless verification loads those actual assets and checks final extracted socket reach,
not only the analytic helper. Existing demo/default registrations remain unchanged.

Test non-axis-aligned input, rotated/scaled ancestors, reachable and far/near targets,
root target, collinear pole, zero/near-zero lengths, finite numeric bounds, direct-chain
validation, channel preservation, weights 0/0.5/1, masking, repeated invocation, shared
component across entities/rigs/generations and resets. Pin additive public ABI/consumer
compilation, retain X3 regressions, include tests in official 26.3 selection. Run focused
and full applicable tests, official 26.3 build/JAR/example checks, one independent focused
review, publish this branch and verify exact-commit CI to terminal outcomes. Deliver a
cumulative Library bundle with source, JARs, checksums and scoped evidence.

Non-goals: terrain sampling, foot locking, FABRIK, arbitrary chains, end orientation,
server authority, gameplay collision, animation networking, new animation engine,
main merge, tags/releases/CurseForge, EULA or security changes. Native graphics remains
explicitly deferred; headless success is not a visual pass.
