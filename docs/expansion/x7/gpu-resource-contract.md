# X7 GPU resource and candidate batching contract

## Scope and status

This contract describes the adapter-private B1 candidate in
`blendlib-fabric-client/.../render/x7gpu`. The R1 review recorded **FAIL (0C/0H/4M/1L)**; this
document records the corrective contract, not a fresh review verdict. It is not a public API and
is not connected to P4, X4, X6, reload, publication, a renderer, or hardware. “Candidate” means
prepared data/ownership only, never an executed GPU draw, performance result, or compatibility
claim.

ADR-X7004 separately records a D2a CPU-only composition of D1 registry parent ownership with X4
snapshots and an additive X6 plan/drain overload. That composition neither adopts this B1 candidate
nor changes this document's no-GPU/no-draw status; collector callback and RenderPass completion
ownership remain **WAITING**.

ADR-X7006 adds only the production D1 Minecraft handoff/fence spine and two package-private Fabric
event scopes. It does not attach this B1 candidate to D1, does not wire `X7GpuGeneration.retire` or
its scheduler, and does not enable allocation, upload, pass creation, draw, or live-resource
shutdown.

## Canonical policy boundary

`X7GenerationPerformancePlan.Published` remains the sole backend/fallback authority. Resource
B1 has no receipt, no CPU/GPU enum, no fallback reason, no capability discovery, and no policy
publication method. `X7GpuResourceFactory` exposes only:

| Resource attempt | Meaning |
|---|---|
| `SUCCESS` | one complete typed vertex/index resource pair is owned locally |
| `RESOURCE_FAILURE(stage, cause, cleanup)` | an allocation/assembly transaction failed; all owned prior handles were closed or their cleanup failure is retained |

Current package boundaries prevent B1 from consuming the canonical policy proof. A future
integration owner must bind the resource attempt to the exact canonical generation/handle/model/
geometry/material/LOD identities and map it *before* publishing the canonical plan. B1 does not
pretend that integration exists; a resource failure cannot rewrite an already-published plan.

## Immutable input and deterministic byte layout

`X7ImmutableGeometryView` is a non-public, non-owning view. It deliberately does not expose or
recreate a second `StaticGeometry` truth. An integration owner must eventually adapt the existing
immutable geometry boundary; B1 must not inspect a registry or decoded asset during submit.

The one supported B1 layout is fixed independent of host byte order:

| Buffer | Layout | Validation |
|---|---|---|
| Vertex | `POSITION_NORMAL_UV_F32`: eight IEEE-754 floats (position xyz, normal xyz, uv) in little-endian order | non-empty bounded vertex count; all values finite; checked stride/count byte total |
| Index | `UINT32_LE`: one four-byte unsigned index per element in little-endian order | non-empty bounded triangle multiple; every index in `[0, vertexCount)`; checked byte total |

Staging is direct for upload handoff but always explicitly `ByteOrder.LITTLE_ENDIAN`. It is owned
by the transaction and is closed after every render-owner assertion, allocation, or assembly path,
whether that path succeeds, returns `RESOURCE_FAILURE`, or rethrows an `Error`.

## Typed 26.1.2 allocation and abort ownership

The production adapter's owner-thread sequence is:

```text
RenderSystem.assertOnRenderThread()
  -> RenderSystem.tryGetDevice() or RenderSystem.getDevice()
  -> GpuDevice.createBuffer(label supplier, COPY_DST | VERTEX, direct vertex bytes)
  -> GpuDevice.createBuffer(label supplier, COPY_DST | INDEX, direct index bytes)
```

The first assertion is inside the transaction's cleanup-covered range. The pair is all-or-nothing:
success makes exactly one vertex and one index wrapper visible, while every failure attempts a
direct close of each already-acquired owner token exactly once. B1 deliberately does not ask
`GpuBuffer.isClosed()` as a cleanup gate.

Expected runtime failures become `RESOURCE_FAILURE`; their original cause remains primary and a
runtime cleanup failure is recorded and suppressed. An `Error`, including a synthetic OOME test
path, is cleaned up and rethrown with identity preserved. A fatal cleanup error becomes primary
with the original failure suppressed. `X7SharedGeometryResources` also attempts both physical
closes, aggregates failures, and leaves only a failed buffer retryable. The game-owned `GpuDevice`
is never closed by X7.

## Generation drain and render-owner handoff

`OPEN` accepts `X4_SNAPSHOT`, `X6_PLAN`, `X6_DRAIN_DISPATCHER`, and `X7_RESOURCE_LEASE` holds.
Retirement immediately rejects every new lease. When every hold reaches zero, the final release
may be on any thread and causes one self-driven handoff:

```text
final release -> integration-supplied render-owner handoff
              -> owner assertion -> RenderSystem.queueFencedTask
              -> physical pair close
```

B1 does not invent an unverified Minecraft cross-thread queue. The supplied render-owner executor
seam must execute the handoff task on the real render owner; only there does the adapter use the
verified fenced Minecraft call. The releasing thread never needs to be the render owner.

The local state machine is:

```text
OPEN -> RETIRING -> FENCE_QUEUED -> CLOSING -> CLOSED
                    \-> CLOSE_FAILED -- explicit owner retry --> RETIRING
```

`FENCE_QUEUED` and its counter happen only after queue acceptance. Re-entrant callback delivery
before acceptance is deferred. A handoff rejection, queue failure, callback error, or partial
buffer close records the real cause and enters `CLOSE_FAILED`, rather than falsely reporting
`CLOSED` or a queued fence. It refuses new admission. An idempotent explicit owner retry schedules
one new handoff; only a successful retry reaches `CLOSED`.

Actual B1-to-D1 attachment, X4/X6 pass completion, allocation/draw, and live-resource shutdown
remain **WAITING**. The trusted D1 executor/fence spine from ADR-X7006 is installed in production,
but B1's separate local scheduler remains intentionally unwired and is not another lifecycle truth.

## Static and skinned candidate plans

The static key requires generation + model + geometry + `RenderType` route + material + LOD +
format + primitive mode + index type. Planner input order is retained: only adjacent equal keys
are coalesced, so crossing a generation/material/route/LOD/model/format boundary cannot merge.
Bounded limits reject excessive batch, model, or instance work before a candidate plan is returned.

The plan describes `DRAW_MULTIPLE_INDEXED_CANDIDATE_WAITING` and instance counts only. It has no
pass/pipeline/per-instance-transform contract, so it makes no actual multi-index or instanced call.

`CPU_SKINNED_UPLOAD_CANDIDATE` accepts the existing `CpuSkinner` output, packs it, and explicitly
does not claim GPU skinning. Shader, SSBO, pipeline, GPU-skinning, real draw, and instancing work
are all **WAITING**.

## Diagnostics and visibility boundary

All B1 adapter/owner types, including `X7ImmutableGeometryView`, are package-private. A test from
a different package reflects their modifiers to prevent accidental public API.

The immutable diagnostic snapshot contains resource counts/bytes, individual X4/X6/X7 holds,
accepted fence and successful physical-close counts, optional close failure, and candidate
batch/model/instance counts. The synchronized collector computes all three `Math.addExact` results
first, then commits all fields together; an overflow leaves every counter unchanged. It never
changes any lease, GPU wrapper, fence, canonical plan, or backend state.
