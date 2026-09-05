# ADR-X7005: source-bound frozen CPU policy projection

## Status

Proposed / Experimental X7 D2b implementation candidate. This is a structural CPU-route
composition only, not a performance, hardware, renderer, callback/pass, GPU, client/server, or
visual acceptance. Fresh independent review remains required.

## Context

ADR-X7004 already makes `ClientGenerationLeaseBinding` the opaque source-bound handoff for an
exact D1-admitted snapshot. The X7 foundation was package-private in a separate package, so it
could not be used by reload without publicizing policy types or creating a second map/owner.

`ModelRenderSnapshot.visibility()` is already the completed immutable per-frame instance decision
that `BlendRenderer` and X6 honor. `CullingMetadata.cullable()` is descriptive culling input, not
a dynamic-bounds-completeness signal. A later X6 frame may reuse a plan's exact handle while
carrying a different immutable snapshot and visibility.

## Decision

1. The six X7 policy sources have one package-private home in `client.reload`; they are moved,
   not copied. No X7 policy type becomes public.
2. `X7GenerationPerformancePlan` gains a private-proof `CpuSubsetBinding`/publication route that
   retains only the source facts D2b actually has: generation, exact render-handle identity, and
   exact source-view identity. It is always `CPU/CAPABILITY_UNAVAILABLE`. The full
   geometry/material/LOD `Binding` remains untouched for a future owner with those real facts;
   D2b never fills it with synthetic identities.
3. `ClientGenerationLeaseBinding` privately owns one immutable frozen CPU projection when the
   trusted reload lookup creates its source-bound composite. Its only additive public projection
   surface is `permitsFrozenCpuRoute(ModelRenderSnapshot)`: it revalidates the exact snapshot and
   caller-owned state, returns a primitive, and exposes no X7 type, D1 owner/lease, GPU object, or
   pass receipt. External/missing unavailable bindings retain existing CPU compatibility but never
   become managed.
4. X4 reads that route before X1 pinning, combines it once with the exact snapshot's existing
   visibility, and stores only the resulting private primitive in `X4PreparedSnapshot`. The
   constructor rejects any primitive that disagrees with `RenderVisibility`; a `CULLED` snapshot
   remains culled even if its metadata says `cullable=false`.
5. Managed X6 reads and copies only the frozen CPU-route primitive before its existing
   admission/transfer/pin sequence. It does not copy the binding snapshot's visibility: every
   later compatible snapshot remains the sole owner of its own frozen `RenderVisibility`. X6
   submit reads the saved route plus that existing snapshot field; it never invokes X7 policy,
   lookup, registry, budget, or callback completion logic.
6. The policy collector records a private freeze-time structural snapshot only. Existing
   `ClientRenderMeasurementCollector` and its public four-component result remain the only CPU
   timing/count surface. There is no budget ledger, LOD history, animation recommendation, or
   benchmark integration.

## Compatibility and consequences

`ClientModelView`, every `ModelRenderSnapshot` public constructor/factory/accessor,
`ClientModelLookup`, `ClientRenderMeasurementSnapshot`, historical X6 `prepare` descriptors, and
the additive `prepareManaged` descriptor remain unchanged. The binding's one primitive method is
additive and covered by descriptor/reflection tests. D1 remains the sole resource owner, and the
close-only plan transfer receipt is unchanged.

Production rendering remains on existing CPU `RenderType`/`SubmitNodeCollector` code. This ADR
adds no GPU allocation/draw, B1 factory, raw GL, custom pipeline, per-submit child lease, registry
lookup in submit, or RenderPass completion claim.

## Waiting

Actual budget ownership, LOD history, animation work, GPU candidate/resource/draw behavior,
deferred callback/pass ownership, hardware/Iris/Sodium compatibility, benchmarks, client/server
execution, and visual acceptance remain **WAITING**.
