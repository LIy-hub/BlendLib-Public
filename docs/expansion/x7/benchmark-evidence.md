# X7 benchmark tooling evidence

Status: implementation/tooling evidence only. Real hardware capture, GPU
performance, shaderpack, Iris, Sodium, visual, reload-performance, and X7
Gate evidence remain WAITING.

## Scope actually implemented

Only the following new X7-owned paths were added:

| Path | Purpose |
|---|---|
| blendlib-showcase/src/main/java/com/liy/blendlib/showcase/perf/x7/ | Pure-Java schema, strict codec, validator, artifact verifier, and comparator. |
| blendlib-showcase/src/client/java/com/liy/blendlib/showcase/perf/x7/ | Narrow adapter around the existing public client measurement snapshot. |
| blendlib-showcase/src/test/java/com/liy/blendlib/showcase/perf/x7/ | Synthetic fixture factory and focused schema/validator/comparison/boundary tests. |

The implementation imports the existing P7ReferenceScenario as its sole
workload authority. It adds no renderer registration, GPU backend, scene
spawner, Gradle task, entrypoint, resource, network path, JFR capture
lifecycle, or Minecraft/server start operation.

## Synthetic fixture boundary

Every fixture remains test-only because this candidate has no integration-owner
sealed trusted-capture receipt. Adversarial fixtures retain the exact
`SYNTHETIC_TEST_ONLY` marker; a separate fully shaped fixture deliberately has
valid raw/receipt/JFR/PNG bytes only to prove that such a package remains
`WAITING` without trusted traversal and an owner receipt. A CPU_CAPTURE proves
only local structural retention. No fixture can produce a hardware-comparison
result from this package.

The focused tests also cover:

- missing GPU identity, which remains WAITING;
- incorrect artifact hash and byte count, which fail INVALID;
- unordered percentiles, NaN, and non-finite JSON conversion;
- frozen-scenario drift and an incorrect sample count;
- duplicate JSON keys, duplicate artifact keys, self-listed manifests, and an
  unknown required extension;
- synthetic, unit-test, source-replay, and no-shader-smoke inputs, all of
  which are forbidden from becoming hardware evidence;
- incompatible Fabric environments and CPU-only captures, both of which the
  comparator refuses;
- the client adapter source boundary: no Minecraft, raw OpenGL, file/JFR
  access, or capture lifecycle control.

## R2 repair-review closure

The preceding R1 repair was independently reviewed again and failed with four
Medium and two Low findings. This R2 patch closes those findings without
changing renderer, capture-controller, Gradle, P7, resource, network, or
public platform ownership:

- M1 — canonical closure: direct construction and encoding now share the strict
  parser limits; writer output tracks UTF-8 bytes/characters, finite extreme
  doubles use bounded notation, negative zero is rejected, and the canonical
  document must fit parser limits and strict-decode equal to its source. The
  boundary suite includes `Double.MAX_VALUE`, `Double.MIN_VALUE`, negative-zero,
  exact string/array limits, and the 4,096 × 16 KiB aggregate case.
- M2 — hard-link closure: the bounded full inventory, including the
  self-excluded manifest identity, is pairwise checked with `Files.isSameFile`.
  Real NTFS hard-link tests run when supported; a deterministic injected
  same-file seam covers filesystems that cannot create one.
- M3 — TOCTOU truthfulness: only `SecureDirectoryStream` relative-handle
  traversal can be `TRUSTED`. Ordinary Windows/provider traversal is
  `STRUCTURALLY_VALID_WAITING`, never a hardware trust claim. Unsupported
  secure traversal, reparse observations, and controlled ancestor-swap/race
  signals fail closed. No caller-controlled eligibility boolean remains.
- M4 — raw evidence: hardware-shaped packages require a bounded canonical raw
  P7 sample role (exact 600/1,800/100/25) and recompute envelope p50/p95/p99,
  including CPU. The P7 receipt is parsed structurally and bound by role digest;
  JFR is independently hash/size/magic-checked; PNG, environment, and log are
  distinct hashed roles. The absent sealed owner receipt keeps every candidate
  non-eligible.
- L1 — the evidence account now reports the actual comparator suite count and
  the exact sum of all X7 suites.
- L2 — traversal has deterministic depth, entry, relative-path, per-role, and
  total-byte limits. Binary JFR/PNG caps are role-specific rather than forced
  through the 1 MiB JSON cap.

The focused adversarial suite covers canonical/schema/Unicode/aggregate limits,
unlisted inventory, portable symlink handling, real and injected hard links,
manifest aliasing, controlled reparse and ancestor-race seams, insecure-provider
non-trust, token replay, raw P7 row/count/recompute drift, strict receipt
parsing, fake JFR, whitespace-padded `UNKNOWN`, missing hardware roles, and GPU
identity mismatches. The portable symlink case is skipped only when the host
denies symlink creation; the deterministic reparse/same-file seams still run.

## R3 trusted-owner state-model repair

The R2 independent review found that a same-JVM caller could use reflection to
relabel an ordinary Windows structural token because the old result types still
contained `ELIGIBLE` and `COMPARABLE` values. This repair removes those values
and their supporting hardware-comparison state from the current type model:

- validation exposes only `INVALID` and `STRUCTURALLY_VALID_WAITING`; there is
  no hardware-eligibility enum, boolean, public/private factory, or receipt
  seam that can express a trusted hardware result;
- comparison exposes only `NOT_COMPARABLE` and
  `WAITING_FOR_TRUSTED_OWNER`, emits no numeric deltas, and retains
  `NO_AUTOMATIC_GPU_SPEED_CLAIM` as its only conclusion;
- an ordinary Windows token remains structurally waiting even when its private
  constructors are accessed reflectively, because the forbidden enum values do
  not exist to pass to those constructors.

This package is therefore a structural validator and diagnostic tool only. A
real trusted capture owner/capability, sealed receipt, and numeric hardware
comparison must be introduced as a new implementation and receive a new
independent review. All real hardware comparison evidence remains WAITING.

## Previous R2 automated evidence

The R2 focused new-package command completed successfully:

    .\gradlew.bat :blendlib-showcase:test --tests 'com.liy.blendlib.showcase.perf.x7.*' --rerun-tasks --no-daemon --max-workers=1 --console=plain
    exit: 0
    result: BUILD SUCCESSFUL; 18 actionable tasks executed

The command compiled the repaired X7 main/client paths and ran 39 focused tests:
0 failures, 0 errors, and 1 skipped portable-symlink test. The retained XML is under
`blendlib-showcase/build/test-results/test/`:

- X7ArtifactVerifierTest: 11 tests, 0 failures, 0 errors, 1 skipped;
- X7BenchmarkComparatorTest: 5 tests, 0 failures, 0 errors;
- X7BenchmarkEvidenceCodecTest: 8 tests, 0 failures, 0 errors;
- X7BenchmarkEvidenceValidatorTest: 7 tests, 0 failures, 0 errors;
- X7ClientMeasurementSnapshotAdapterSourceBoundaryTest: 1 test, 0 failures,
  0 errors;
- X7HardwareEligibilityTest: 5 tests, 0 failures, 0 errors;
- X7RawP7SamplesTest: 2 tests, 0 failures, 0 errors.

The exact suite sum is 11 + 5 + 8 + 7 + 1 + 5 + 2 = 39.

This pre-R3 focused result is tooling verification only, not a runtime,
hardware, performance, or visual result. It does not cover the R3 state-model
repair. Historical R1 module-check output likewise predates this work.

## R3 focused automated evidence

The R3 focused command completed successfully:

    .\gradlew.bat :blendlib-showcase:test --tests 'com.liy.blendlib.showcase.perf.x7.*' --rerun-tasks --no-daemon --max-workers=1 --console=plain
    exit: 0
    result: BUILD SUCCESSFUL; 18 actionable tasks executed

The retained XML under `blendlib-showcase/build/test-results/test/` reports 40
focused tests: 0 failures, 0 errors, and 1 skipped portable-symlink test.

- X7ArtifactVerifierTest: 11 tests, 0 failures, 0 errors, 1 skipped;
- X7BenchmarkComparatorTest: 5 tests, 0 failures, 0 errors;
- X7BenchmarkEvidenceCodecTest: 8 tests, 0 failures, 0 errors;
- X7BenchmarkEvidenceValidatorTest: 7 tests, 0 failures, 0 errors;
- X7ClientMeasurementSnapshotAdapterSourceBoundaryTest: 1 test, 0 failures,
  0 errors;
- X7HardwareEligibilityTest: 6 tests, 0 failures, 0 errors;
- X7RawP7SamplesTest: 2 tests, 0 failures, 0 errors.

The exact suite sum is 11 + 5 + 8 + 7 + 1 + 6 + 2 = 40. The new Windows
reflection regression confirms that the public state shapes omit `ELIGIBLE` and
`COMPARABLE`, and that an ordinary structurally waiting token can only yield
`WAITING_FOR_TRUSTED_OWNER`, never a numeric hardware comparison or GPU-speed
claim.

This focused result proves only the X7 structural-tooling behavior. It is not
real hardware, performance, runtime, client/server, reload, or visual evidence.

No client or server was started for this X7 tooling change. No real JFR,
profiler output, FPS capture, GPU identity, driver observation, shaderpack,
Iris/Sodium capture, screenshot, reload measurement, or actual benchmark
artifact is claimed or committed here.

## Evidence still required

A real future evidence package must pass the fail-closed offline verifier and
then remain subject to independent X7 review. It must include genuine,
compatible hardware capture artifacts, a secure/owner-held traversal path, a
sealed integration-owner receipt, and the P7 frozen-workload proof. Until then:

- GPU performance conclusion: WAITING;
- CPU/GPU fallback performance comparison: WAITING;
- Iris/Sodium/shaderpack performance compatibility: WAITING;
- reload-generation performance and resource-release evidence: WAITING;
- visual/all-host acceptance: WAITING;
- X7 performance Gate: WAITING.
