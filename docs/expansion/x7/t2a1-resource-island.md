# X7 T2a1 canonical resource-island implementation ledger

## Status

T2a1 is a bounded internal implementation tranche from the T2a0 frozen design. It rehomes the
package-private B1 resource-preparation island to `reload`, replaces B1-local lifecycle truth with
a leaf-backed complete aggregate, and adds a canonical pre-publication bridge. It is not T2a2 and
does not alter D1 publication or enable real GPU work.

The first formal R1 review is **FAIL**. Its four bounded findings received a repair, but independent
R2 then recorded **0C/0H/1M/0L**: H1/M2/M3 were closed and M1 remained open because two
caller-supplied lists could jointly omit a real generation primitive. R3 replaces that list-defined
universe with an authoritative exact-generation inventory. This ledger must not be read as a PASS,
X7 completion, or X8 unlock until a fresh independent Sol/max review exists.
The finalized R2 artifact is
`D:\BlendLib-review-artifacts\x7-t2a1-resource-island-review-r2-20260904-232114646\FINAL_REVIEW.md`
(SHA-256 `7CBBC0115FC1C402F89E3858EFD0D5BD78C8C7BEB104C432CB4FFD0F0FEBCE73`).

## Repaired boundary

- `CompletedGenerationResourceSet` is non-empty, exact-generation, immutable in leaf membership,
  deterministic by complete key, and derives checked count/byte totals from leaf resources.
- Its package-private ownership hand-off is one-way:
  `CALLER_OWNED_COMPLETE -> CLAIM_OWNED_COMPLETE -> D1_OWNED`. Claim makes caller close
  ineffective; a claimant can close only after its own D1 pre-allocation/insertion failure.
- The frozen `ClientGenerationResourceOwner` still has one deprecated package-private T1b direct
  attach shim because T2a1 may not modify that T2a2-owned file. The shim internally performs both
  steps, is the sole production caller, and must be deleted by T2a2. The canonical bridge does not
  use it.
- `X7GenerationResourceBridge` constructs its private `AuthoritativeGenerationInventory` solely
  from the exact immutable `ModelRegistryGeneration.handles()` map. It enumerates static and
  skinned primitives for every map-owned loaded or missing handle, retains exact
  handle/render-handle/primitive/geometry/material/LOD identities, and derives all full keys.
  Callers cannot construct the inventory or supply a candidate geometry universe. Freeze rejects
  omitted/empty/subset/extra/duplicate/unclassified/cross-generation and
  handle/model/geometry/material/LOD mismatch; successful attempt keys are verified; CPU-only is
  permitted only after whole-inventory zero-GPU classification or a genuinely zero-renderable
  generation.
- Aggregate construction and bridge assembly share fatal-promoting cleanup selection: the first
  fatal `Error`/`OutOfMemoryError` wins over a non-fatal primary while retaining exact object
  identities and deterministic suppressed exceptions. Aggregate close continues across leaves,
  retains only failed leaf tokens, and retries only those leaves.
- B1 `X7GpuGeneration`, lease, hold kind, scheduler, render-owner classes, and lifecycle
  diagnostics/test are removed. Retained `render.x7gpu` values remain draw-domain candidates with
  no resource owner or factory call.
- `PendingGenerationTransaction`, `ClientGenerationResourceOwner`, `ClientModelRegistry`, and
  `ClientModelReloadListener` remain untouched in T2a1.

## Historical R1/R2 and pending R3 repair evidence

The earlier broad 60-test T2a1 run is historical evidence only; its R1 review nevertheless failed
the four contracts above. The user-directed R3 repair must not rerun that historical set. R2's
single remaining M1 finding is specifically the jointly omitted caller-list universe. R3's single
authorized focused command covers only the directly affected bridge/boundary guards:

```powershell
.\gradlew.bat :blendlib-fabric-client:test `
  --tests 'com.liy.blendlib.fabric.client.reload.X7GenerationResourceBridgeTest' `
  --tests 'com.liy.blendlib.fabric.client.reload.X7ResourceIslandBoundaryTest' `
  --tests 'com.liy.blendlib.fabric.client.render.x7gpu.boundary.X7GpuAdapterSurfaceBoundaryTest' `
  --offline --no-daemon --max-workers=1 --console=plain
```

R3 ran that exact command once on 2026-09-04 and it exited 0 after compiling the changed
client/test sources. It ran 19 tests with no failures or errors: bridge XML SHA-256
`A7354110B1286668F6170FBE826C982F8F9BEE754677D4BEE34E10AFA0C1948C`; reload-boundary XML
SHA-256 `5CA0331DC9B0792A3803C5142254A1DA19452524160AC23D6F9AADB16D4BB0F8`; outside-package
modifier-boundary XML SHA-256 `F2D8CAC59CFD401FC0E705D5F150775946816AB95764784D0078B4CFD34B3FE2`.
This is focused structural evidence only, not an independent review or X7 completion evidence.

The checked-in `b3660bb` ABI descriptor manifest SHA-256 is
`23B6D0FB25F4F0C0798FE775814283E60EF9A3B395363D6A8B7813106953C4DF` and its public-class
inventory SHA-256 is `7F2E626DC59F1D8E7548B3018E91CDB14318FF6F7EED9589EDBE573035BCF032`.
Compiled output coverage includes API/core/fabric-common leakage; the repository has no
fabric-server module, so that part is explicitly N/A. None of this evidence is client, visual,
GPU, runtime, compatibility, hardware, benchmark, X7-complete, or X8 evidence.

## T2a2 handoff

T2a2 must replace the untyped callback-list/post-publication attachment direction with a
transaction carrying either CPU-only or this exact aggregate, have D1 adopt the aggregate before
registry CAS, call the two ownership steps directly, and delete the deprecated T1b direct shim. It
must not reintroduce B1 generation/lease/fence/scheduler truth.

## Waiting

The R3 focused validation completed as recorded above. Fresh independent Sol/max review remains
**WAITING**. All real resource allocation/upload/close scheduling, pass/draw behavior, live reload
and shutdown, actual client/server runs, visual acceptance, Iris/Sodium, hardware/JFR,
performance/benchmark evidence, X7 completion, and X8 remain **WAITING**.

## 2026-09-05 supersession note

The R1/R2/R3 chronology above remains immutable: R1 and R2 failed, while R3 passed the bounded
T2a1 repair. Its then-pending formal-review wording is superseded only by the later T2a2/T2a3
packets and the current [X7 final formal-integration ledger](../integration/x7-final-r1.md).
That ledger preserves this tranche's source/formal mapping and all three review hashes. Current
status is **bounded formal integration candidate / PENDING fresh final Sol review**, not X7
completion or a runtime, visual, hardware, reload, or performance PASS. X8 remains **LOCKED**.
