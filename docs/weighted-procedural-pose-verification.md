# Weighted procedural pose verification

## Functional scope

`WeightedPoseComponent` adds dynamic [0,1] weighting and immutable named local-node masks around
an existing procedural component or whole pipeline. Weights multiply; empty mask selects all.
It uses existing extraction context and shortest-arc quaternion interpolation. No renderer,
networking, item playback, layer-controller or existing API contract is changed.

A zero-weight component still evaluates once, allowing springs to advance while invisible.
Node membership, translation and scale violations are rejected before masking, including at
zero weight. Transform construction guarantees finite normalized rotation. Both lifecycle reset
methods forward to the delegate. Masks resolve against each current rig with no new retained state.

## Automated evidence

- Root Java 25 focused tests: `WeightedPoseComponentTest` (6 cases), exact compiled public ABI
  and generic signature pin (1 case), existing `ProceduralPoseComponentsTest` pass.
- Executable `ProceduralPoseConsumerProbe` asserts actual tick-driven fade at start, midpoint,
  completion, and masked-node preservation; existing suite executes the probe.
- Minecraft 26.3: `-Prunnable_examples=true build verifyRuntimeJar verifyRunnableExamples` passes,
  including the opt-in runnable showcase using time-varying aim weights.
- One independent focused read-only review found no must-fix bugs; no second review loop.
- `git diff --check` passes.

Tests cover immutability, multiplicative masks, shortest-path quaternion signs, stage order,
zero-weight delegate invocation, complete output validation, invalid weights and names, ambiguous
names, same-time spring behavior, instance/generation isolation and reset propagation.

## Verification boundary

No native graphics/client visual result is claimed. Desktop visual verification is deliberately
deferred by user instruction. The live showcase compiles and packages; its visual appearance is
not yet manually accepted. This change neither releases nor tags the library.

## Local environment notes

The first modern invocation used a root multi-project task path and was rejected before execution;
it was corrected to the documented standalone `versions/modern` build command above. An initial
root aggregate check exposed only injected `JAVA_TOOL_OPTIONS` startup banners in the retained
`javap` text comparison, not a descriptor change. The established local quiet-test JVM init script
removes that environment banner while retaining the required sandbox selector configuration;
no repository baseline was relaxed for this environment artifact.

Root aggregate rerun with the helper passed all 643 client tests, 297 core tests, 354 API tests,
21 common tests, and both 7-test consumer fixtures. It stopped in the unrelated showcase suite:
`X7ArtifactVerifierTest.symlinkEscapeFailsClosedWhenTheFilesystemPermitsPortableSymlinks` expects
a particular diagnostic substring after correctly rejecting the symlink. The exact same test
fails on unchanged baseline `7e0a143` in this cloud Linux environment. This is recorded as a
pre-existing local aggregate limitation; the test and verifier remain unchanged. The independent
26.3 suite has 116 tests with zero failures. Exact-head remote CI is recorded in the delivery evidence.
