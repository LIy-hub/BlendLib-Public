# ADR-X7008: Canonical pre-publication generation resource bridge

## Status

Experimental X7 T2a1 resource-island implementation reached its accepted R3/formal review at the
frozen `f3bd664` baseline. T2a2 now implements the bounded pre-CAS D1 adoption continuation on
an isolated branch. It remains awaiting a fresh independent review; this ADR does not declare a
T2a2 PASS. This decision records an internal package and ownership correction only. It does not
activate a production resource creator, allocation, upload, rendering path, draw, GPU backend, or
any X7 completion gate.

## Context

The historical B1 resource pair lived in `render.x7gpu`, while canonical generation policy and D1
ownership live in `reload`. Java subpackages do not share package-private access, so B1 could not
legally consume the frozen policy without an unsafe public bridge or reflection. Its historical
`X7GpuGeneration` / scheduler / lease state also duplicated D1's retirement, fence, retry, and
physical-close authority.

T1b's generic post-publication attachment is historical evidence only. T2a2 deletes that direct
seam rather than retaining an alias: the only forward route carries one complete aggregate in the
transaction and adopts it into D1 before registry CAS.

## Decision

### One canonical package and one aggregate

The ownership/preparation island is rehomed to the exact package
`com.liy.blendlib.fabric.client.reload`:

- immutable geometry staging and limits;
- the private buffer/device/factory seam and 26.1.2 wrapper;
- full immutable resource key, format/index/mode values, and completed vertex/index leaf;
- `X7GenerationResourceBridge` and `CompletedGenerationResourceSet`.

All of these types are package-private. No public or protected descriptor exposes a bridge,
device, buffer, leaf, aggregate, callback, executor, fence, or proof type.

`CompletedGenerationResourceSet` owns an immutable deterministic list of complete leaves for one
identical `ModelRegistryGeneration` object. Each key includes generation, model, geometry,
material route, LOD, vertex format, primitive mode, and index type. It rejects duplicate,
cross-generation, half, missing, and unclassified coverage. Its count and byte totals are checked
sums derived from leaves, never supplied as aggregate metadata.

`X7GenerationResourceBridge` derives an `AuthoritativeGenerationInventory` directly and solely
from the immutable exact generation's `handles()` map. For every map-owned `ModelHandle`, it
enumerates both the exact `ModelRenderHandle.primitives()` static universe and its
`skinnedPrimitives()` universe, including missing-model fallback handles. The inventory constructor
is private and accepts no caller geometry or expected-cardinality collection. Its private proof
retains and rechecks exact handle/render-handle/primitive/geometry/material/LOD identities against
the generation, then requires exactly one prepared binding per derived entry. It independently
rejects omitted, empty/subset, extra, duplicate, unclassified, cross-generation, and
identity-mismatched handle/model/geometry/material/LOD entries. Full keys are derived from those
authoritative bindings and include model, geometry, material route, LOD, vertex format, primitive
mode, and index type. The bridge verifies every GPU/successful-attempt key and permits CPU-only
only when the whole authoritative inventory classifies zero GPU entries, including the genuine
zero-renderable-generation case.

### Ownership and close behavior

Before a later transaction claim, the aggregate is caller-owned. The canonical hand-off is a
package-private one-way `CALLER_OWNED_COMPLETE -> CLAIM_OWNED_COMPLETE -> D1_OWNED` sequence:
claim wins over caller close, and the claim owner either adopts exactly once into D1 or closes the
aggregate after its own pre-allocation/insertion failure. Construction and bridge-composition
failure close every created leaf exactly once with one shared selector: the first fatal
`Error`/`OutOfMemoryError` is promoted over a non-fatal primary while exact throwable identities
and deterministic suppression order are retained. Physical close uses deterministic leaf order,
retains only failed leaves, and retries only those failed leaves. It owns no independent
generation, lease, fence, retry scheduler, active pointer, or render-owner handoff.

### T2a2 pre-CAS adoption

`PendingGenerationTransaction` is a closed immutable payload: `CPU_ONLY` retains only the exact
candidate identity and `COMPLETE_SET` retains the same candidate plus one nonempty exact
`CompletedGenerationResourceSet`. Its state transition is
`PREPARED_CALLER_OWNED -> CLAIMED -> POLICY_FROZEN -> PUBLISHED`, or
`PREPARED_CALLER_OWNED -> ABORTED_CALLER_OWNED -> CLAIMED_ABORT -> ABORTED` for a direct
caller abort. The transaction monitor performs the caller-to-claim aggregate transition and the
unique transaction claim together; replay and a second claim are rejected.

D1 acquires a publication-admission permit before claim, preallocates the one lifecycle record and
its close bookkeeping, and then claims. It catches `Throwable` from policy freeze and validation.
Under the D1 monitor it validates shutdown, identity, foreign owner, duplicate/current, retirement,
and staleness; it inserts that same record, adopts the aggregate into D1, marks physical
`resourceReady`, and only then enables the record's one CAS proof. The opaque package-private
pre-CAS probe exposes record identity, payload classification, derived count/bytes, ownership, and
readiness but never a raw resource or closing capability. A detached loser is cleanup-ready and
D1-owned, but never CAS-authorized.

CAS loss, CAS throw, stale, foreign, duplicate, freeze, and shutdown all retain the same already
D1-owned record for retirement/close; no loser returns the complete set to caller ownership or
modifies a foreign/current winner. CPU-only follows the identical permit/claim/freeze/CAS shape
with zero physical count and bytes. Existing partial-close retry and final-shutdown terminal rules
remain owned by D1.

### Superseded local lifecycle model

For future integration, this ADR supersedes only the B1-local lifecycle model in ADR-X7002,
the untyped callback payload direction in ADR-X7003, and ADR-X7006/T1b post-publication attachment
as the forward canonical path. Historical candidate documents and review evidence remain intact.
T1a/T1c D1 fence and shutdown contracts are unchanged.

## Consequences

- `X7GpuGeneration`, its lease/hold/scheduler/render-owner classes, and generation lifecycle
  diagnostics are removed rather than wrapped or connected to D1.
- Retained `render.x7gpu` diagnostics are numeric candidate-batch observations only. The retained
  CPU-skinned candidate remains an unowned draw-domain value with its own private packed payload.
- T2a2 changes only `PendingGenerationTransaction`, `ClientGenerationResourceOwner`,
  `ClientModelRegistry`, `ClientModelReloadListener`, and the package-private aggregate's retired
  T1b shim. It adds no public/protected ABI and keeps the production listener explicitly CPU-only.
- No code in this tranche calls `GpuDevice`, creates a buffer, uploads, opens a pass, binds,
  batches, instances, skins on GPU, or draws.

## Waiting

The R3 directly affected bridge/boundary run completed successfully on 2026-09-04 (19 tests):
`X7GenerationResourceBridgeTest` XML SHA-256
`A7354110B1286668F6170FBE826C982F8F9BEE754677D4BEE34E10AFA0C1948C`,
`X7ResourceIslandBoundaryTest` XML SHA-256
`5CA0331DC9B0792A3803C5142254A1DA19452524160AC23D6F9AADB16D4BB0F8`, and
`X7GpuAdapterSurfaceBoundaryTest` XML SHA-256
`F2D8CAC59CFD401FC0E705D5F150775946816AB95764784D0078B4CFD34B3FE2`.
An independent fresh Sol/max review of the T2a2 implementation is still **WAITING**. Production
CPU/GPU route composition, real allocation/upload, pass completion,
draw/batching/instancing/skinning, reload/runtime/client/server/visual/compatibility/hardware/
performance evidence, X7 completion, and X8 unlock remain **WAITING**.
