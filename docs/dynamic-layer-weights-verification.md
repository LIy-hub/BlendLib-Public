# Dynamic clip-layer weights verification

## Implemented contract

- Immutable bounded `AnimationV2LayerWeights`, keyed by controller/layer pair, captures a complete
  frame-local multiplier map. Values are finite [0,1]; omissions mean one; unknown targets reject.
- Existing `advance` and `advanceAtFrame` signatures retain configured-weight behavior. New distinct
  `advanceWeightedAtFrame` methods preserve source compatibility with null rejection-list calls.
- Core evaluator uses the same override-priority, normalization, bone mask and additive ordering.
  Zero contribution does not pause clocks, transitions, automatic next, sequence handling or observers.
- Fabric `extractLayered` overload preflights targets before binding/advancing instance clocks. The
  entity builder callback captures weights once during extraction; no callback reaches render submit.
- Effective pre-mask/pre-priority weights accompany immutable evaluation snapshots; old public
  snapshot constructors and layer records are unchanged. The runnable inspector labels these honestly.
- Per-actor runnable cue example fades the upper clip layer without changing sequences or procedural
  composition. Native graphics verification remains explicitly deferred.

## Verification

- Official Java 25 / Minecraft 26.3 `-Prunnable_examples=true build verifyRuntimeJar verifyRunnableExamples`
  passed, including packaged asset evaluation, duplicate/retrigger/reload cues, phase fade and inspection.
- Focused core dynamic-weight and frame-atomicity checks: 27 tests passed. Fabric runtime, builder and
  exact retained public `javap` boundary checks: 52 tests passed.
- New weights/Key exact descriptor sets, generic types and record shape are pinned; original constructor
  and method boundaries remain. Entity callback signature is pinned as well.
- An initial existing core no-Predicate bytecode guard caught a stream in target validation; validation
  was changed to a bounded loop and focused atomicity tests passed. No guard was relaxed.

## Critical review and final boundaries

One independent critical review identified a cue ordering bug: a rejected unknown-weight frame could
freeze the cue cache's first elapsed-time/sequence capture. The entity extraction bridge now validates
captured weights against descriptor layer pairs before invoking the command/cue source. A regression
uses the actual runtime cue cache: reject at tick 10, retry at tick 20, require a fresh 1.0-second
capture, preserve repeated cues, and prove the rejected sequence does not advance its watermark.
No second review cycle was used.

Root aggregate check ran 1,426 tests: API 354, core 306, Fabric client 649, common 21, API consumer 7,
Fabric consumer 7 and showcase 82. The only failure was the previously reproduced Linux baseline
`X7ArtifactVerifierTest.symlinkEscapeFailsClosedWhenTheFilesystemPermitsPortableSymlinks` diagnostic
substring assertion; one showcase case was skipped. The verifier correctly rejected the symlink.
No test, baseline or assertion was weakened. Final fix runs and exact-head remote CI are included in
the delivery evidence. Native graphics acceptance remains deferred; no merge/release/tag was made.

Final post-review-fix root run passed all 306 core, 650 Fabric client and both 7-test consumer
fixture suites, including the new real cue-cache rejection/retry test.
