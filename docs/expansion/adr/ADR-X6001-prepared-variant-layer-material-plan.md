# ADR-X6001: Keep X6 Variants, Layers, and Materials in Adapter-Private Prepared Plans

**Status:** R14 final source review passed, but the later eight-JVM completion-order probe reproduced
the formal-integration race 96/96 times, so formal integration remains **BLOCKED**. R15 then recorded
R3 **FAIL (0C/4H/2M/0L)**, R4/R5 non-verdict design, valid RED (6 tests / 1 intended assertion
failure), and a frozen source candidate with pre-documentation source/test
**PASS (0C/0H/0M/0L)** plus static/binary **PASS**. This reconciliation awaits independent
docs/full-source review. The ADR still records an Experimental adapter boundary, not accepted
shared runtime integration, a release, or a completed host rollout.

## Context

X6 needs bounded variant selection, ordered presentation layers, and controlled material provider
negotiation without weakening strict v1, opening a custom render pipeline, or moving load-time work
into Minecraft's submit path. ADR-016 prohibits unverified custom pipeline/shader behavior, and the
existing P4 adapter has verified only public standard material routes. X1 already provides a
generation-scoped `CapabilityPlan` and `ProviderLifecycleSession`, but it must not leak provider
metadata/callbacks into rendering.

## Decision

1. X6 is implemented only in the Fabric client `render` package as immutable prepare-time data and
   a submit-only public-collector bridge. No stable v1 API/core/common, entrypoint, descriptor, or
   v1 profile change is made. R13's only public addition is the explicitly Experimental client
   lifecycle-owner dispatcher/admission seam described below.
2. X6 manifest input is isolated/versioned (`format_version=1`) and rejected unless parsed during
   prepare. It is not a v1 `extensions` payload and does not change the v1 rule that required
   extensions are rejected.
3. Variant compilation retains only catalog-owned geometry identities: exact
   `PreparedRenderPrimitive`/`StaticGeometry` references for static paths, or exact
   `PreparedSkinnedRenderPrimitive` plus captured snapshot mesh indices for `skinned_v1`. The exact
   skinned handle owns an immutable skin-joint-node table, and factory binding verifies every
   canonical `(node, skin, joint)` against it before any provider pin. X6 does not deep-copy
   vertices or indices. Mesh replacement names another catalog binding and must match skinning
   mode/node/skin identity. Same-part effects compose in an explicit frozen order; an ambiguous
   same-kind overlap fails rather than using insertion order.
4. Layers have fixed phases and explicitly recorded semantics. A layer material is an explicit
   override or else is derived only from its final variant draw after mesh/material/skin composition;
   final material, standard route, tint/culling/light semantics, and any per-bone triangle subset
   are frozen before submit. Glow/overlay/damage/attachment/secondary/per-bone routes use those
   prepared standard materials or snapshots. Outline uses a bounded
   CPU normal shell on the existing public cutout route; shadow uses a bounded CPU flatten on the
   existing public translucent route. Neither path creates a `RenderType`, pipeline, shader, or raw
   GL state.
5. Material modes delegate to the existing standard mapper. Additive, complex PBR, and custom
   shaders fail closed before plan publication.
6. The X1 provider bridge makes one bounded ordinary-Throwable-contained snapshot of caller-owned
   provider input, identity, and supported material capabilities. Current X6 intersects every
   caller request with its `1.1.0` compatible protocol line `[1.1.0, 1.2.0)` before generic
   register/discover/freeze and rejects any frozen selected offer outside that line. The generic X1
   registry may still represent historical `1.0.0` data-plane metadata, but a broad caller request
   cannot promote it into a current X6 lifecycle. It then performs register/discover/freeze/
   prepare/apply/publish in preparation. Before `ProviderLifecycleSession` exists, X6 never closes
   an input provider; a publishable frozen plan creates that session before later X6-specific
   validation, so all post-session cleanup consumes X1's cumulative retire/close result. Every
   directly thrown `Error` is terminal, is never converted into a diagnostic or safe-string
   payload, and keeps its original identity through lifecycle cleanup. Ordinary
   `RuntimeException` metadata/callback failures remain bounded diagnostic input.
    `X6PreparedRenderPlan` carries immutable selected capability/provider-id/fallback-id metadata;
    its bridge privately owns the same-generation `ProviderLease`, while plan close only signals that
    bridge and never releases the pin on its caller. Factory pinning checks X1's live `PUBLISHED` state and maps ordinary
   retirement/close races to a local failure. If pin acquisition itself throws any `Error`, no
   lease exists and that exact object is rethrown without an invented release. After a pin succeeds,
   the factory remains responsible for one best-effort release until a complete success result can
   return the plan. Every failure in that interval, including plan construction, immutable copies,
   provider-binding materialization, and result construction, crosses one `Throwable` guard. Every
   `Error` is fatal and retains exact identity: a primary error keeps precedence and suppresses
   distinct release failures when representable; otherwise the first fatal release failure wins
   (launcher, worker, then cleanup context) and suppresses the primary plus other distinct release
   failures. Ordinary release failures never mask the original controlled diagnostic. A single
   package-private atomic exact-once release controller owns the raw lease after post-pin failure:
   launcher, worker, and caller fallback receive only that controller, so launch/start/await
   ambiguity cannot infer a raw-lease ownership transfer, double-close, or leak. The controller
   caches one terminal close result and waits across interruption before restoring the interrupt
   flag. The normal potentially terminal release runs on one named, non-daemon platform worker
   rather than the factory caller; the factory still joins it before selecting precedence.
   Suppression bookkeeping skips self-suppression and suppression-disabled metadata, contains
   ordinary bookkeeping throwables, and never swallows an `Error` thrown while appending metadata.
   A terminal close failure returned by a started worker has a distinct diagnostic from a true
   synchronous fallback-close failure; launch, start, and await faults retain their own
   controller-result paths rather than being collapsed into that worker-terminal diagnostic.
    Therefore the eventual host must call the factory from a preparation/lifecycle-owner worker,
    because failed publication may wait for its release worker. A successful plan close is a
    signal-only, reentrant-safe caller action; the admitted bridge, not that caller, runs physical
    drain on the lifecycle owner. The deterministic pin/assembly, release-launcher, and suppression
    seams used to prove these cases are package-private and do not expand the stable v1 API. Submit
    never discovers capabilities or invokes providers.
7. Factory publication binds an exact `ModelRenderHandle` from a prepared snapshot and validates
   static node/palette or skinned primitive/node/mesh/joint identities before pinning. It freezes
   the constant-time captured-skinned mesh shape fence at that point. Submission checks exact
   key/generation/lease/handle and that O(1) shape only; it does not revalidate the catalog or scan
   bones. It honors culling, emits prebuilt static or captured skinned geometry through the same public collector/standard
   `RenderTypes`, and delegates already prepared attachments through `ModelRenderBackend`. It also
   consumes RenderTypes, part lookup, and layer targets frozen during plan construction.

### R12 finding and r13 owner-drain constraint

The r12 finding makes X1 1.1's every-`Error` selection rule explicit at the X4/X6 handoff:
an `Error` wins an ordinary throwable, while two `Error` objects retain the first exact object;
distinct non-selected failures are best-effort suppression evidence. A failed X4 membership revoke
after the underlying X1 session has terminalized retains the selected terminal request and retries
only its exact receipt, rather than reporting a false-live membership.

R13 supersedes the r12 direct-drain description. A successful plan now requires the public
Experimental `X6LifecycleDrainDispatcher`: before X6 pins its provider lease, the factory gives
the host one prebuilt bridge runnable through `register(Runnable)` and receives an `Admission`.
The host must retain that runnable in a bounded, serialized lifecycle-owner facility through
reload/shutdown; it must neither run it inline nor discard it merely because an owner finishes
before `requestDrain()` returns. Registration rejection, `null`, or an inline run during
`register` fails before a provider pin. An admitted early run observed before bridge attach/publish
fails closed; the factory cancels the admission and, if it already acquired a pin, uses the existing
exact-once failed-publication release path.

For a prepared X6 plan, `close()` and the final submit-hold release still linearize only a drain
**request**. They reject later submits or signal the registered bridge, but never physically close
the `ProviderLease` on the submit/close caller. Only the admitted lifecycle-owner bridge performs
that close, outside plan and bridge monitors. A same-request-thread inline bridge run records a
contract violation and leaves the plan fail-closed; a distinct lifecycle-owner thread may drain
before `requestDrain()` returns exactly once, and the returning request must not overwrite
`DRAINING` or `TERMINAL`. If a late request failure arrives after that early terminal run, it cannot
change the already returned owner invocation; it is retained in completion and a later defensive
owner replay observes the final every-`Error` primary/suppression selection.

The new seven-argument `X6PreparedRenderPlanFactory.prepare(..., X6LifecycleDrainDispatcher)` is
additive. The legacy six-argument descriptor remains linkable and is deprecated without removal:
it retains its original pure pre-pin validation, then returns
`BLENDLIB-X6-LIFECYCLE-001` / `LIFECYCLE_OWNER_REQUIRED` for an otherwise successful ownerless
request without pinning. There is deliberately no X6-owned thread, executor, thread-local, or
unbounded queue; the host owns the bounded scheduling facility.

`X6PreparedRenderPlan.close:()V` retains its JVM descriptor. R12 deliberately removes only its
`ACC_SYNCHRONIZED` modifier so a zero-hold close can leave the monitor before invoking a potentially
blocking owner request. The physical provider close is now owner-bridge work rather than caller
work. The modifier delta is an intentional ABI-compatible descriptor-preserving change; unknown
reflective `Modifier.isSynchronized` consumers remain a compatibility risk, not a compatibility
claim.

### R15 X4 terminal epoch and the unchanged X6 owner boundary

R15 gives X4 one terminal epoch `E`: opening `E` rejects new prepare and submit admission, every
open snapshot remains a future-`B` source, and only pre-`E` admitted submit holds (`H`) may finish.
X4 seals and replays `F = select(Ae*, select(select(B*, C*), D*))`. An ordinary non-reentrant public
X4 `retire()`/`close()` waits for open snapshots, admitted work, and retained membership sources;
only exact renderer/provider/revoke callback owner or contribution owner reentry coalesces into its
outer owner and avoids self-wait.

This stricter synchronous X4 behavior does not alter this ADR's X6 decision. X6 plan close and final
hold release remain signal-only requests to `X6LifecycleDrainDispatcher`; the lifecycle owner alone
performs physical provider close. R15 makes no X6 production or wiring change. Exact current gate
counts and report/manifest SHA-256 values are recorded in
[`../x6/test-evidence.md`](../x6/test-evidence.md).

## Consequences

This decision yields deterministic diagnostics and contained failure: invalid inputs cannot replace
an existing published generation. It intentionally does not provide runtime resource resolution,
entrypoint registration, a custom material route, a custom outline/shadow shader, or any visual
compatibility guarantee. Actual in-game ordering and appearance of the bounded public fallback
paths remain separate evidence tasks.

The code duplicates only the small, public-API pose/vertex emission sequence needed to emit an X6
selected primitive. It does not copy the immutable geometry payload and does not change P4's
existing backend contract. If future work wants a shared emission primitive, that is a separately
reviewed refactor outside this candidate's ownership scope.

## Agent Innovation

Prepare-time bone triangle subsets, bounded CPU outline/shadow presentation, hostile-provider
containment with X1 ownership preservation, and the frozen deferred geometry renderer are recorded
as Agent Innovation. Their motivation is predictable CPU fallback and lifecycle safety without a
new renderer API; the rejected alternatives are whole-mesh bone redraw, custom GPU/shader state,
direct provider close, and nested plan-capturing callbacks. They remain adapter-private and covered
by unit/structural regressions. This records neither independent-review acceptance nor GPU,
allocation, visual, reload, or Iris/Sodium evidence.
The r12 hold/drain and every-`Error` alignment are safety repairs only: they record no new render
route, protocol version, shared wiring, or accepted Agent Innovation.

## Alternatives rejected

- Extend generic v1 descriptor extensions: rejected because v1's closed extension contract must
  remain unchanged.
- Use a custom `RenderType`, shader, PBR/additive pipeline, or raw GL: rejected by ADR-016 and
  absent verified 26.1.2 public-path evidence.
- Resolve variants/layers/providers in submit: rejected because it would introduce I/O, mutable
  discovery, callback work, and stale-generation ambiguity into the hot path.
- Create a custom outline/shadow pipeline: rejected because ADR-016 permits only the already proven
  public standard material routes. The accepted X6 fallback is explicit, frozen, and CPU-side.

## Required evidence before acceptance

The exact R15 candidate has pre-documentation source/test PASS (0C/0H/0M/0L) and static/binary PASS,
but independent docs/full-source review and formal integration are still pending; the independent
96/96 completion-order reproduction keeps integration blocked. Controlled shared wiring approval, a real production
dispatcher caller, reload/failure lifecycle tests, real visual validation, host coverage, reload
stress, performance, hardware, and Iris/Sodium validation are all still required. This ADR alone
changes no runtime gate state.
