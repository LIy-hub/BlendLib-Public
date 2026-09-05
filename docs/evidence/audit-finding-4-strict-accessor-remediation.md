# Audit Finding 4: strict accessor remediation

Status: three local review FAIL rounds remediated by the original implementer;
independent rereview pending.

Date: 2026-08-01 (Asia/Shanghai)

This repair changes no descriptor schema, public API, resource format, wire
format, diagnostic-code allocation, Minecraft/Fabric version, or runtime
version. It enforces rules already required by BlendLib's strict GLB 2.0
profile and the Khronos glTF 2.0 specification:

- `normalized=true` is invalid for `FLOAT` and `UNSIGNED_INT` accessors.
- strict `JOINTS_0` is non-normalized U8/U16; strict `WEIGHTS_0` is
  non-normalized FLOAT or normalized U8/U16; indices are non-normalized.
- declared `min`/`max` arrays have the accessor component count, contain
  finite values representable by the accessor component type, satisfy
  component-wise ordering, and exactly match the decoded BIN extrema after
  the glTF-required single-precision interpretation for FLOAT metadata.
- `POSITION` and animation sampler input accessors declare both `min` and
  `max`. Other v1 accessors may omit them, but declared values are never
  trusted without validation.
- every accessor declaration is preflighted in source index order, including
  unused accessors. Layout/metadata are cached once, and the aggregate extrema
  scan is rejected before its first BIN read if it exceeds the configured
  limits-derived component budget.

The implementation uses existing `BLENDLIB-GLB-015` diagnostics. Field-shape,
normalization, ordering, and extrema errors point at the exact
`/accessors/<index>/normalized`, `/min[/component]`, or `/max[/component]`
location. No ADR or new diagnostic code is required.

## Regression evidence

All final commands used Java 25, `--no-daemon`, and one Gradle worker.

1. Focused `GlbAccessorReaderTest`, `ModelAssetLoaderTest`,
   `CoreValueContractsTest`, and `DescriptorDecoderTest`: exit `0`. The final
   XML contains respectively 7, 8, 4, and 7 tests, with zero
   failures/errors/skips.
2. Full `:blendlib-core:test :blendlib-fabric-client:test --rerun-tasks`:
   exit `0`; core 20 suites / 85 tests and client 33 suites / 138 tests, with
   zero failures/errors/skips.
3. Focused `P7ReferenceAssetGeneratorPerfTest`: exit `0`; 3 tests, zero
   failures/errors/skips. The explicit benchmark fixture generator now emits
   exact POSITION and animation-input bounds too.
4. Fixed-revision Khronos derivation `--verify`: exit `0`. The two GLB hashes
   are `051b36110442d893472bbdc23ab59e04904008b1c2ea181d26eaeca2fbcfbced`
   (SimpleSkin) and
   `7a15f0f3e90e748bdb38ec4b36bdcf6a741faf4ee90f628ddc1868330778b7d5`
   (AnimatedCube); the derivation-manifest hash is
   `70768d79de1126019a0835ea0a3a1ba7e9736f9c977ab50ad36851b739577e57`.
5. `git diff --check`: exit `0`.

Focused XML SHA-256 values:

| Test suite | SHA-256 |
|---|---|
| `GlbAccessorReaderTest` | `91f9367c494c330accbf1b8ae56795ad5f6dc827ded90efdb45e60bb0b1273c9` |
| `ModelAssetLoaderTest` | `3fd879a69b7eb3d7d25940a8962a2a6c4472011bbded6f4e6f8f81dc8c64c820` |
| `CoreValueContractsTest` | `97369500a78c9fe4197c94b9e549d1437159da8a5d2ec2f6f2d81bd497a6bff9` |
| `DescriptorDecoderTest` | `fe81bf6d8a9e65057c92d172eed31eb097df2f85884ff1398f73833c8132892c` |
| `KhronosDerivedFixtureTest` | `acf4515d03c9fafeebff09d43ca945b0dbdf4466b0b0b73ef596de17721acece` |
| `P7ReferenceAssetGeneratorPerfTest` | `46d014126064433ce00e78187a2f8e09a82adfb758b4cec31a94d122d7a9e5d4` |

The first full-core adaptation run correctly exposed four now-stale fixtures
that lacked required POSITION or animation-input bounds. Those fixture
generators were repaired and the fixed-revision Khronos derivatives were
regenerated deterministically before the all-green runs above. A temporary
attempt to strict-load the P7 generator directly from the Showcase test
source set failed compilation because that consumer intentionally has no
core implementation dependency; that attempt was fully removed, and the
generator contract remains covered without weakening the module boundary.

The first independent local reviewer then found that validation was still
reference-triggered: a malformed unused accessor could evade `info()`. The
original implementer corrected this with `validateAll()` at the deterministic
combined-load preflight. The new unused-accessor matrix proves the exact
`/accessors/4/normalized` failure requested by review, plus malformed min/max
shape, length, finiteness, ordering, actual extrema and layout, and a valid
unused control. A limits-derived aggregate scan-budget regression proves the
loader rejects overlapping declared-bound work before scanning, while the
cached control proves repeated `info()` returns the same validated metadata.

The second local review required a genuinely cumulative scan-budget boundary
and allocation-safe accessor-count coverage. The budget test now uses two
20-component FLOAT scalar accessors with a budget of 37 components: accessor
0 is legal by itself, while including accessor 1 deterministically fails at
`/accessors/1/count` before any extrema BIN scan. The 16,384 declaration cap
is now checked before the reader allocates its metadata caches. Both the
ordinary strict GLB load and a direct `GlbDocument` construction prove the
16,384 boundary is accepted.

The third local review rejected a one-entry relaxation of the generic JSON
array ceiling that had been introduced solely to reach the typed accessor
diagnostic. That relaxation is removed: the default ceiling is again 16,384
for every JSON array. Consequently an ordinary untrusted GLB with 16,385
accessors is rejected during JSON parsing, before a full accessor AST or any
reader cache allocation, and is reported through the existing
`BLENDLIB-GLB-002` container diagnostic at the root location. A directly
constructed `GlbDocument`, which bypasses parsing, is independently rejected
before reader cache allocation as `BLENDLIB-LIMIT-001` at `/accessors`.
Dedicated regressions also prove a bare JSON array accepts 16,384 and rejects
16,385, and that a descriptor's unrelated `extensions_used` array cannot
bypass the same default ceiling.

No client/server process was started. No staging, commit, push, publication,
deployment, or formal server/world operation was performed.

Specification reference:
<https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html>
