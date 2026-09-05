# X7 benchmark capture and evidence contract

Status: tooling/schema only. This contract does not establish a GPU, CPU, FPS, visual,
Iris, Sodium, shaderpack, reload, or X7 Gate result.

## Frozen workload

X7 reuses the existing P7ReferenceScenario in blendlib-showcase. It does not
define a second scene or permit a reduced fixture. Schema version 1 requires an
exact projection of this identity:

| Field | Required value |
|---|---:|
| Scenario format | blendlib-showcase-p7-reference-scene-v1 |
| Rigid workload | 100 instances, 10,000 triangles and 30,000 vertices each |
| Skinned workload | 25 instances, 20,000 triangles, 60,000 vertices, and 64 joints each |
| Total target geometry | 1,500,000 triangles |
| Warm-up / sample window | 600 / 1,800 completed frames |
| Rigid model | blendlib_showcase:p7/rigid_10k |
| Skinned model | blendlib_showcase:p7/skinned_20k_64j |

The exact P7 layout, camera, per-frame 100/25 submission guard, and client
preflight remain owned by the existing P7 implementation. X7 evidence records
that frozen identity rather than replacing its controller or scene.

## Schema v1

X7BenchmarkEvidence is a pure-Java, offline evidence envelope. Its canonical
JSON uses exactly these top-level fields, in this order:

    schema_version
    capture_class
    gate_status
    capture_id
    source_revision
    scenario
    environment
    backend
    sampling
    metrics
    allocation_evidence_key
    artifact_manifest
    required_extensions
    optional_extensions

source_revision records a full lowercase Git commit and tree SHA-1.
environment requires Java, OS, Minecraft, Fabric Loader, Fabric API,
GPU/vendor/driver, shaderpack, and Iris/Sodium states. When an observation is
not available it must be the explicit string UNKNOWN; a hardware comparison
then remains WAITING.

backend records the backend class, concrete backend name, capability,
fallback state, and whether that selection was verified. sampling records
the frozen warm-up/sample counts and reload generation. metrics records
finite non-negative ordered p50/p95/p99 values for FPS, frame time in
milliseconds, CPU render time in milliseconds, and allocation bytes.

The allocation_evidence_key must name a JFR_ALLOCATION entry in the artifact
manifest. Every artifact entry records a stable unique key, canonical relative
path, lowercase SHA-256, positive byte count, and artifact kind. The manifest
path is deliberately self-excluding: it must not appear in its own entries,
avoiding a recursive self-hash claim.

The only accepted gate status in this schema is WAITING. Capture data cannot
self-issue a performance, hardware, visual, or release PASS.

## Strict parsing and canonicalization

X7BenchmarkEvidenceCodec implements a local strict JSON parser rather than
accepting permissive replay data. It rejects:

- duplicate object keys, trailing JSON, malformed escapes, non-finite numeric
  conversions, and unknown/missing schema fields;
- wrong schema version, invalid full commit/tree IDs, blank environment
  fields, unsafe artifact paths, duplicate artifact keys or paths, and invalid
  hashes/byte counts;
- unordered, negative, NaN, or infinite percentiles;
- scenario or 600/1,800 sampling drift;
- unknown required extensions. Schema v1 supports no required extensions.

R1 makes decoding a semantic boundary, not a raw-token factory: `parse` returns
only an evidence object that has passed the same frozen-schema validation as a
directly constructed envelope. It accepts only the exact canonical field,
artifact, and extension ordering emitted by the writer. Lone raw or escaped
UTF-16 surrogates and malformed UTF-8 fail before canonical hashing; a valid
supplementary code point remains a stable UTF-8 round trip.

The parser is deliberately bounded: input is limited to 1,048,576 bytes and
characters, nesting depth 64, 50,000 tokens, schema-object entries 14, array
entries 4,096, strings 16,384 UTF-16 code units, and numeric tokens 128
characters. Limits fail with the codec exception rather than recursing to a
stack overflow or allocating an unbounded document.

Canonical JSON has stable field ordering, sorted extension lists and artifact
entries, UTF-8 hashing, and number formatting independent of default locale
or timezone. Finite `Double.MAX_VALUE` and `Double.MIN_VALUE` use bounded
canonical exponent notation when plain notation would exceed the 128-character
numeric-token ceiling; negative zero is rejected rather than silently becoming
positive zero. Directly constructed envelopes pass the same string, path,
count, Unicode, numeric, manifest-byte, and aggregate-byte checks before they
can encode. The bounded writer stops before either parser input ceiling, and a
successfully encoded envelope must strict-decode to an equal value. The codec
exposes a SHA-256 of that canonical byte sequence for repeatable evidence
indexing.

## Offline validation, retained-artifact closure, and result binding

X7ArtifactVerifier is an offline tool with two deliberately different results.
It checks path components with `NOFOLLOW_LINKS`, rejects symbolic links,
junctions/reparse points and unprovable Windows reparse state, and requires
each resolved real path to stay beneath the root. It streams declared files for
byte-count and SHA-256 verification. The canonical evidence JSON itself must
be retained at `manifest_path`; it is self-excluded from artifact declarations
but included in the exact root inventory and bound by its bytes, SHA-256, byte
count, and inventory digest. The bounded inventory limits directory depth to
16, entries to 128, relative paths to 512 characters, each role to its
documented cap, and all retained bytes (including the canonical manifest) to
256 MiB. JSON/raw/environment reports cap at 1 MiB; capture logs, PNG, and JFR
use distinct 16 MiB, 32 MiB, and 128 MiB caps.

For the finite inventory, every pair of regular files, including a declared
artifact and the self-excluded manifest, is checked with `Files.isSameFile`.
This detects NTFS hard links even when Windows supplies no `fileKey`; real-path,
case-alias, hash, and byte checks remain in force. Any unlisted file, duplicate
entity, traversal, self-reference, missing entry, overflow, or manifest
substitution fails closed.

`STRUCTURALLY_VALID_WAITING` is not trusted hardware input. Standard Java
cannot make a Windows parent-junction precheck followed by pathname open
race-free. Only a `SecureDirectoryStream` relative-handle traversal (or a
future integration-owner equivalent sealed capture handle) may yield
`TRUSTED`. A normal Windows/provider traversal is at most structural retention;
an unsupported secure provider, reparse observation, or race observation cannot
promote it. Thus no ordinary path precheck/open/recheck can be described as a
hardware-trusted capture.

Successful verification and validation objects have owner-private constructors.
They bind canonical evidence digest, real root identity, manifest digest/bytes,
and complete inventory digest. Validation and structural diagnostics reverify
that binding against the current root before consumption, so callers cannot
replay a retained token across evidence/root or use it after a retained
manifest/artifact changes. That binding is not a trusted capture receipt.

X7BenchmarkEvidenceValidator intentionally exposes only two outcomes:

| Outcome | Meaning |
|---|---|
| INVALID | Schema, scenario, manifest, hash, or byte evidence is malformed or fails closed. |
| STRUCTURALLY_VALID_WAITING | Evidence and retained artifacts agree, but this package has no trusted capture owner/capability. This includes ordinary Windows traversal, a trusted traversal without an owner receipt, and synthetic, unit-test, source-replay, no-shader, CPU, and hardware-shaped local examples. |

No validator outcome is a Gate pass or trusted hardware input. There is no
hardware-eligibility enum, boolean, factory, or result state in this package;
reflection can construct at most INVALID or STRUCTURALLY_VALID_WAITING.

For a future trusted real-hardware record, the repaired contract requires six
independent hashed roles: a structured P7 completion receipt, canonical raw P7
frame samples, JFR allocation recording, capture log, environment/backend
report, and PNG screenshot. Raw samples must contain exactly 1,800 measured
frames after the inherited 600-frame warm-up, exactly 100 rigid and 25 skinned
submissions on every row, and recompute FPS/frame-time/CPU/allocation
p50/p95/p99 exactly. The P7 receipt is duplicate-key-hostile structured JSON,
binds the frozen 600/1,800 and 100/25 workload, client conditions, and JFR
filename; it is bound by the role digest rather than a substring claim. JFR has
its own role hash, size, and `FLR\0` magic check; PNG, environment report, and
log each have independent role hashes. `UNKNOWN`, missing, mismatch, the exact
`SYNTHETIC_TEST_ONLY` marker, an untrusted traversal, or absence of a sealed
integration-owner receipt is untrusted. The code-owned trusted context also
pins the inherited P7 source commit/tree, scenario, Java 25 and Fabric runtime.
This tooling revision deliberately has no trusted capture owner or capability,
so it cannot express a hardware-eligible token. Adding one is a new
implementation that requires fresh independent review.

X7BenchmarkComparator is structural diagnostics only in this revision. It
returns WAITING_FOR_TRUSTED_OWNER when both current tokens are freshly bound
structural waiting records, and NOT_COMPARABLE when either token is invalid,
stale, or unbound. It can retain scenario/backend/environment differences as
non-authoritative diagnostics, but it has no COMPARABLE state, numeric delta
output, or GPU/backend/speed conclusion. A future trusted comparison belongs
to a new owner-capability implementation with an independent review; genuine
compatible captures and the separate X7 acceptance evidence remain required.

## Client boundary

X7ClientMeasurementSnapshotAdapter is client-only and accepts only the
existing public ClientRenderMeasurementSnapshot and
ClientAnimationRuntimeMetrics. It converts existing CPU/cache observations
without accessing Minecraft, raw OpenGL, JFR, files, capture controls, or GPU
identity. Capture lifecycle and actual hardware evidence remain outside this
adapter.

## Required real-world evidence remains waiting

Before a genuine hardware comparison or X7 performance conclusion, an
integration owner must retain and seal a fresh isolated P7 run with the frozen
camera/layout/client conditions and all-host submission guard, 600 warm-up
frames, 1,800 raw samples, valid JFR/profiler allocation evidence, capture
log/environment report/screenshot, secure traversal or an equivalent
owner-held capture handle, verified complete inventory, hardware identity,
backend/fallback state, relevant shaderpack/Iris/Sodium state, and separate
visual/reload acceptance evidence. This tooling neither runs nor seals that
workflow and cannot substitute synthetic, source, build, or smoke output for
it. Hardware, GPU, JFR, shaderpack, Iris, Sodium, visual, reload, performance,
and X7 Gate status all remain **WAITING**.
