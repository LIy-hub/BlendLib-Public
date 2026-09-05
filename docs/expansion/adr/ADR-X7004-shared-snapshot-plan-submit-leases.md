# ADR-X7004: Compose one registry-generation lease with X4 snapshots and X6 plans

## Status

Proposed / Experimental X7 D2a implementation candidate. This ADR records a narrow lifecycle
composition only. It is not a renderer, GPU, render-pass, hardware, compatibility, performance,
client/server, or visual acceptance; fresh independent review remains required.

## Context

ADR-X7003 made `ClientGenerationResourceOwner` the one counter and lifecycle truth for active and
retiring registry generations. X4 already pins an X1 `ProviderLease` through a prepared snapshot
and terminal drain. X6 already pins an X1 `ProviderLease` through a pre-admitted lifecycle bridge
and prepared-plan drain. Those pins cannot stand in for registry render-resource ownership: they
are different owners with different close contracts.

`ClientModelView` is intentionally an inspection value. It cannot carry a package-private
`ModelHandle`, and an external implementation may return a synthetic or unmanaged view. Likewise,
`ModelRenderSnapshot` is immutable prepared CPU data and must not become a mutable lease carrier:
doing so would change its public shape/copy contract and could make a copied snapshot close an
owner it never acquired.

The current Minecraft `SubmitNodeCollector` accepts deferred callbacks. Returning from
`BlendRenderer.submit` or `X6PlanSubmitter.submit` does not prove that the callback ran, ran once,
or reached RenderPass completion. There is no approved callback cancellation/drain/abandonment
receipt. Therefore this integration must not pretend that submit return owns a pass-completion
lease.

## Decision

### Source-bound opaque public ports

`ClientGenerationLease` and `ClientGenerationLeaseBinding` are the additive public cross-package
types. Both constructors are private. There is no public managed-lease factory. The former exposes
only `unavailable()`, `managed()`, `requireExactHandle(ModelRenderHandle)`, and idempotent
`close()`. The latter exposes the immutable `snapshot()`, `managed()`,
`requireCompatible(ClientModelLookup)`, `requireExactSnapshot(ModelRenderSnapshot)`,
`permitsFrozenCpuRoute(ModelRenderSnapshot)`, `requireManagedForPlan()`, `transferToPlan()`, and
idempotent `close()`. The route method returns only a primitive after exact-snapshot/caller-state
validation; it exposes no X7 policy type or lifecycle capability. The one-shot transfer
returns a `PlanTransferReceipt` with only idempotent `close()`; neither type exposes a raw-lease
extractor, registry owner, GPU handle, or arbitrary closeable factory. Its only public static
creation method is `unavailable(ModelRenderSnapshot)`, which cannot be managed.

Managed issuance is package-private inside reload. `ClientModelRegistry.installServiceLookup(
ClientModelLookupBootstrap)` accepts a sealed bootstrap marker whose sole permitted implementation
has a private constructor inside `BlendLibClientServices`. It returns the registry's one concrete
`RegistryBackedModelLookup`; there is deliberately no no-argument registry-to-lookup accessor. An
ordinary external lookup cannot construct the bootstrap marker, the concrete lookup, a managed
lease, or a managed composite. Malicious reflection is outside this Java source/API trust boundary.

`ClientModelLookup.acquireGenerationLease(BlendModelKey)` remains a keyed default returning the
singleton explicit unmanaged fallback, preserving old source and binary-compatible implementations.
`ClientModelLookup.acquireGenerationLeaseBinding(BlendModelKey, ModelRenderSnapshot)` is a second
keyed default and returns the explicit unmanaged composite. The trusted lookup samples its own
current view and D1 parent, then accepts the caller-supplied snapshot only after validating exact
source lookup identity and exact render-handle identity before constructing the composite; no
caller-provided view or registry is provenance. A snapshot alone does not identify a registry when
two registries share a raw handle. Source authenticity therefore comes from the protected issuer:
external code cannot obtain a trusted issuer for an arbitrary second registry, while fake/default
lookups can produce only the unmanaged fallback.
D1 revalidates under the same owner lock that increments the sole lease count:

```text
current generation identity == sampled generation
  && lifecycle state == PUBLISHED
  && map-owned ModelHandle identity == sampled raw handle
  && map-owned ModelHandle.renderHandle() == sampled render handle by identity
```

Retirement winning this race or a changed raw map entry throws fail-closed. The resulting
source-bound composite rejects a wrapper that presents another concrete lookup as its source; it
never downgrades a managed source to unmanaged. Only the exact current map-owned missing fallback
and a current not-discovered synthetic missing fallback return explicit no-resource ownership,
because neither has a render resource to retain. That normalization rechecks under D1's owner
lock that the generation remains current/PUBLISHED and that the key is respectively still absent
or the exact same map-owned missing entry/render handle; absent synthetic fallbacks compare
key/generation/missing semantics rather than two freshly allocated handle objects. A loaded sample
never takes this fallback, including when it races a later missing generation.

### X4 snapshot composition

For an unresolved registry-backed X4 frame, the adapter performs all existing structural checks,
builds its immutable snapshot, then requests the source-bound composite from its own lookup before
pinning the X1 provider generation. The composite verifies the concrete lookup identity before
admission. A failed provider pin or snapshot admission closes the acquired obligations in reverse
order; both close attempts occur and the existing X4 failure selector preserves one primary with
safe suppression. A captured external snapshot performs no lookup and receives only the explicit
unmanaged fallback, preserving current CPU/provider-only compatibility.

`X4PreparedSnapshot` owns the shared parent alongside its existing `ProviderLease`. Its close
request still admits no new X4 submit holds, and both parents remain held until all previously
admitted X4 submissions drain. The existing monitor-out terminal callback closes the provider and
shared parent, attempts both even on failure, and feeds their aggregated result through the
unchanged terminal epoch/interrupt protocol.

No per-submit shared child is acquired. The actual standard CPU collector route remains unchanged.
The D2b route primitive is read after exact binding admission and before X1 pinning, then combined
only with that same immutable snapshot's existing `RenderVisibility`; it does not change the
snapshot shape or introduce another culling truth.

### X6 plan/drain composition

The historical six-argument and seven-argument factory descriptors remain linkable and preserve
their ordered diagnostics: the six-argument path returns its pre-existing owner-required failure,
while a successful seven-argument provider-only call has no new
`LIFECYCLE_OWNER_REQUIRED` warning. A new additive entry point accepts a source-bound composite and
lifecycle dispatcher:

```java
prepareManaged(..., ClientGenerationLeaseBinding sharedGenerationBinding,
               X6LifecycleDrainDispatcher lifecycleOwner)
```

The factory never looks up a registry from a snapshot and creates no global side map. It does not
offer a public `ModelRenderSnapshot + ClientGenerationLease` admission overload, because same raw
handle identity cannot prove source ownership across two registries. The caller owns the opaque
parent through X6 structural prevalidation and lifecycle-bridge admission. On successful
admission, a single binding monitor atomically moves the D1 parent from caller-owned state into a
distinct plan close receipt before provider pinning. A caller close that wins first makes transfer
fail before pin; a transfer that wins makes later caller closes no-ops; a second prepare with the
same binding fails before pin. A pin failure closes the receipt. After a provider/receipt pair
attaches, only the bridge can atomically detach that exact pair back to the factory on an
unsuccessful assembly/publication path, preventing duplicate stale-alias close. On success,
`X6LifecycleDrainBridge` owns both parents. It physically closes provider and shared receipt only
on its lifecycle owner after `X6PreparedRenderPlan.close()` and every previously admitted X6
submission has drained.

The managed entry point validates that the composite remains source-bound, open, and exact for its
own immutable snapshot before lifecycle admission or provider pinning. An unavailable, stale,
cross-handle, or wrapper-substituted composite fails before provider pin and remains caller-owned.
No hot submit path performs registry map lookup, I/O, parsing, or lifecycle admission.

D2b reads the binding's CPU-route primitive before the existing admission/transfer/pin sequence and
stores it in the plan. The plan deliberately does not copy its binding snapshot's visibility: X6
continues to accept later compatible snapshots, and each one remains the sole owner of its existing
immutable `RenderVisibility`. Submit reads those prepared primitives only; it does not invoke X7
policy or a callback/pass lease.

### Public compatibility rationale

`ClientGenerationLease`, `ClientGenerationLeaseBinding`, its close-only transfer receipt, the two
keyed default lookup methods, and `prepareManaged` are additive. The public `ClientModelView` canonical constructor/components, all `ModelRenderSnapshot`
constructors/factories/accessors, X4 public types, existing X6 factory descriptors,
`ProviderLease`, `RenderSubmissionContext`, and `BlendRenderer` descriptors are retained. The
opaque port exists because package-private D1 lease state cannot safely cross API, host, and render
packages without either exposing D1 internals or constructing a second lifecycle truth.

## Consequences

- D1 remains the only registry render-resource counter; X1 provider pins are composed, never
  reinterpreted as D1 ownership.
- Registry-backed X4 and the additive managed X6 plan path can retain an exact old generation
  across reload retirement until their existing drains complete.
- External/captured/unmanaged snapshots stay CPU/provider-only and are explicitly diagnosed as
  shared-owner unavailable rather than claiming a fabricated owner lease.
- Production rendering continues to use only the existing standard `RenderType` and
  `SubmitNodeCollector` CPU route. This ADR adds no raw GL, `GpuDevice`, `GpuBuffer`, B1 factory,
  custom pipeline, GPU allocation, or draw call.

## Waiting

Actual deferred collector callback ownership, callback abandonment/cancellation, RenderPass/draw
completion ownership, X7 budget/LOD/animation policy selection, GPU allocation/draw, benchmark/performance evidence,
Iris/Sodium checks, hardware/client/server runs, and visual acceptance remain **WAITING**.
