# Layer visual-event verification

## Delivered contract

The additive entity callback consumes descriptor markers from the existing immutable v2
observer traversal. Its cursor has no independent playback engine. Events identify the real
controller/layer/state, loop epoch, occurrence and sampled effective weight. Legacy callbacks
and server gameplay authority remain unchanged.

The instance-local cursor is retired with its layered runtime. First observation, absolute
commands, skipped publications and truncated controller paths do not replay history. Automatic
intervals are (start,end]; time-zero events are not synthesized. Catch-up retains at most the
last one clip-local second per controller, with a publication-wide 16,384-event cap. Overflow
consumes and drops the complete batch. Zero sampled effective weight consumes without emitting
or backfilling. Consumer exceptions propagate; the failed batch and undelivered suffix are not
retried. See layered-animation.md for the complete public semantics.

## Verification performed

- All 15 new core cursor tests passed, covering owner provenance, first/duplicate/gap observations,
  loops, automatic next, outgoing blends, accepted and rejected command discontinuities, masks,
  weights, instance isolation, controller-local catch-up, truncation and exact/overflow budgets.
- Root aggregate check ran 1,446 tests: API 354, core 321, Fabric client 654, Fabric common 21,
  API consumer 7, Fabric consumer 7 and showcase 82. All suites except the known showcase
  baseline assertion passed. Client tests include real callback delivery, null listeners,
  repeated extraction, zero-weight suppression, exception no-replay, lifecycle/generation
  reset, builder preconditions, and exact retained javap API compatibility.
- The sole root failure is the unchanged Linux baseline
  X7ArtifactVerifierTest.symlinkEscapeFailsClosedWhenTheFilesystemPermitsPortableSymlinks
  diagnostic substring assertion; the verifier rejects the symlink. One showcase case is skipped.
  No test, assertion or baseline was weakened.
- Official Java 25 / Minecraft 26.3 final command passed:
  `-Prunnable_examples=true build verifyRuntimeJar verifyRunnableExamples`.
  All 138 official-version tests passed. This builds the library and runnable consumer JARs and verifies runtime isolation, actual
  packaged descriptor events through the actor-owned callback counter, immutable inspection,
  pair bounds, independent layers, dynamic weights, repeated calls and no backfill.
- One independent critical review found no material issues. It checked provenance, lifecycle,
  bounded work, exception consumption, legacy behavior, compatibility and consumer telemetry.
- `git diff --check` passed.

Exact-head remote CI results, commit/tree mapping, JAR hashes and source archive accompany the
separate delivery bundle. Native game graphics testing remains explicitly deferred. No merge,
release, tag or distribution-platform publication was performed.
