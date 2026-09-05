# ADR-X7002: Generation-scoped GPU resources and batching preparation

## Status

Proposed / Experimental B1 implementation candidate. The historical R1 review of commit
`2060a8de5f452358004b2ccda977c58003aff3b3` remains **FAIL (0 Critical / 0 High /
4 Medium / 1 Low)**. The corrective candidate at
`ae8131ed0f10ab0662d5020b7f89dd00bad63a97` has a fresh independent R2 **PASS (0 Critical /
0 High / 0 Medium / 0 Low)**. That bounded review accepts B1 only as a dependency for the D1
shared-lifecycle candidate; it does not promote an X7 renderer, hardware, compatibility,
performance, client/server, or manual-visual gate.

## Context

`X7GenerationPerformancePlan.Published` is the only canonical B1 backend/fallback decision.
It is deliberately package-private in a different package from this adapter. Existing P4
`StaticGeometry` has no read-only geometry accessor, and X4/X6 retain separate
snapshot/plan/drain lifecycles. The current B1 package therefore cannot legally consume the
canonical proof, and must not recreate it with a receipt, enum, factory, reflection, or a public
bridge.

The Minecraft 26.1.2 adapter still needs a narrow typed staging/allocation transaction whose
ownership can be unit-tested without a client or a hardware claim. It also needs a local
generation drain contract that remains truthful when the last hold is released off the render
thread or an accepted queue/callback/close fails.

## Decision

### One canonical policy truth; one resource transaction result

- `X7GpuResourceFactory` returns only an adapter-local resource `Attempt`: either `SUCCESS` with
  one complete `X7SharedGeometryResources` pair, or `RESOURCE_FAILURE` with a resource failure
  stage, original cause, and any cleanup failure. It contains no backend choice, CPU fallback
  reason, capability result, policy proof, or publication operation.
- The former local preparation receipt and resource candidate are removed. This package cannot
  mint `GPU_CANDIDATE`, `CPU_FALLBACK`, or a substitute fallback reason.
- A future integration owner, if and when it can legally access both boundaries, must map a
  resource attempt to the exact canonical policy identities before publishing
  `X7GenerationPerformancePlan.Published`. B1 deliberately does not pretend that bridge already
  exists. A resource failure cannot mutate an already-published plan.

### Typed staging and abort transaction

- B1 accepts only non-public `X7ImmutableGeometryView`, a non-owning immutable adapter contract.
  It neither copies nor replaces `StaticGeometry`; the missing P4 bridge remains integration work.
- B1 packs exactly `POSITION_NORMAL_UV_F32`: position xyz, normal xyz, and uv, eight floats per
  vertex. Indices are `UINT32_LE`; triangle cardinality, finite components, index range, checked
  byte arithmetic, and explicit per-buffer limits are validated before allocation.
- Direct staging storage uses declared `ByteOrder.LITTLE_ENDIAN`, never host-native order. The
  transaction closes staging in `finally` after every assertion, allocation, construction success,
  or failure.
- The first `assertOnRenderThread()` is inside that abort-covered scope. The 26.1.2 production
  wrapper then obtains a game `GpuDevice` through `tryGetDevice()`/`getDevice()` and calls
  `GpuDevice.createBuffer(Supplier, int, ByteBuffer)`. Vertex usage is exactly
  `GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_VERTEX`; index usage is exactly
  `GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_INDEX`.
- Every acquired vertex/index handle has a direct, exact-once transaction-owner close attempt;
  no possibly-throwing `isClosed()` gate is used. The fixed two-handle cleanup ledger exists
  before allocation, so an `Error`/synthetic OOME path does not need to construct cleanup state
  after failure. Expected `RuntimeException`s return `RESOURCE_FAILURE`; an `Error` is cleaned up
  and rethrown with its identity retained. Cleanup failures are preserved as the recorded cleanup,
  as a primary fatal error, or as suppressed exceptions; none are silently discarded.
- A complete resource pair becomes visible only after both typed allocations and construction
  succeed. `GpuDevice` is game-owned and is never closed by B1.

### Generation drain, render-owner handoff, and retry

`X7GpuGeneration` owns one immutable generation/model/geometry key and has these meaningful
states:

```text
OPEN -> RETIRING -> FENCE_QUEUED -> CLOSING -> CLOSED
                    \-> CLOSE_FAILED -- owner retry --> RETIRING
```

`OPEN` admits X4 snapshot holds, X6 plan holds, X6 drain-dispatcher holds, and X7 resource
leases. Retirement rejects new admission. The final release may occur on any thread; it schedules
exactly one explicit integration-supplied `handoffToRenderThread` task and does not first assert
the releasing caller is the render owner. On the real owner, the adapter asserts ownership and
uses the verified `RenderSystem.queueFencedTask` path.

`FENCE_QUEUED` and its diagnostic counter occur only after queue acceptance. The callback is
re-entry safe: a synchronous callback before acceptance is deferred until acceptance completes.
Physical close tries both owned buffers even if the first fails, retains successfully closed
resources as released, and makes only a failed handle retryable. A handoff rejection, fence queue
failure, callback `Error`, or partial close moves to `CLOSE_FAILED`, retains the actual cause,
rejects new admission, and does not claim `CLOSED` or a successful physical close. The explicit,
idempotent owner retry schedules one new handoff; a successful retry is the only path to `CLOSED`.

This is a local B1 contract. It does not wire existing X4/X6 holders, reload, publication, or the
real render-owner executor into these calls.

### Candidate batch and CPU-skinned upload plans

Static planning retains its complete immutable key: generation, model, geometry, `RenderType`
route identity, material, LOD, vertex-format identity, primitive mode, index type, and index
count. It preserves input order by coalescing only adjacent equal keys. Explicit limits bound
batches, distinct models, instances per batch, and total instances. Its output remains only a
`DRAW_MULTIPLE_INDEXED_CANDIDATE_WAITING` plan; it owns no `RenderPass`, issues no draw, reorders
nothing, and enables no instancing.

The skinned preparation path continues to accept a verified `CpuSkinner` `CpuSkinnedMesh` result
as `CPU_SKINNED_UPLOAD_CANDIDATE`. It packs pose-scoped CPU output into the declared layout and
does not assert GPU skinning, shaders, SSBOs, custom pipelines, or skinning buffers.

### Adapter privacy, durable API guard, and diagnostics

`X7ImmutableGeometryView` and all B1 owner/adapter types are package-private. A test from a
different package reflects their modifiers so an accidental public surface is a regression.

The API boundary test pins the actual local `minecraft-clientOnly-043a8b3edf:26.1.2` POM GAV and
SHA-256, the four exact `RenderSystem`/`GpuDevice`/`GpuBuffer`/`RenderPass` class-entry hashes,
and each required JVM method descriptor. It compares values, not hash length. For the observed
candidate/formal Loom archive pair only, the archive comparison recorded 14,226 entries in each
archive, no missing entries, no content-hash or compressed-size differences, and 431 timestamp
differences. That limited observation does not claim every archive build is byte-identical.

`X7GpuDiagnosticsCollector` computes every `Math.addExact` result in locals and assigns its three
batch counters only after all computations succeed. Overflow therefore leaves the full prior
snapshot intact. Diagnostics report local resource/hold/fence/physical-close observations and an
optional close failure; they do not publish or alter backend policy.

## Consequences

The corrective B1 behavior is limited to deterministic typed staging, transactional resource
pairing, a supplied render-owner handoff seam, truthful local drain/retry state, candidate-only
static batching, CPU-skinned upload preparation, and adapter-local diagnostics/testing seams.

The following remain **WAITING**:

- a real P4 `StaticGeometry` bridge and X4/X6/shared generation-owner wiring;
- reload/publication/retirement ownership, canonical resource-attempt mapping, and actual CPU
  fallback/render routing;
- a production render-owner executor, `RenderPass` ownership, transforms, real
  `drawMultipleIndexed`, static instancing, and any draw;
- GPU skinning, shaders, SSBOs, custom pipelines, and skinning buffers;
- hardware behavior, repeatable benchmark/performance evidence, Iris/Sodium compatibility,
  client/dedicated-server integration, and manual visual acceptance;
- any promotion to stable/public API or an original P0--P8/X7 performance gate.

No raw GL, LWJGL API, `VertexBuffer`, custom OpenGL implementation package, reflection, file
access, background worker, registry/provider discovery, or submit-time backend switch is
introduced by B1.
