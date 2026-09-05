# X6 Variant, Layer, and Material Adapter Candidate

**Status:** R14 final source review passed, but the later formal-integration completion-order probe
reproduced its race 96/96 times across eight JVMs, so formal integration remains **BLOCKED**. R15
then recorded R3 **FAIL (0C/4H/2M/0L)**, R4/R5 non-verdict design, valid RED (6 tests / 1 intended
assertion failure), and a frozen source candidate with pre-documentation source/test
**PASS (0C/0H/0M/0L)** plus static/binary **PASS**. This documentation reconciliation still awaits
independent docs/full-source review. It is not shared runtime integration, release acceptance, or a
real-client/network/reload/visual/Iris/Sodium/hardware/performance result; all such gates remain
**WAITING**.

X6 adds one bounded, generation-scoped preparation route in the Fabric client render package. It
does not change stable v1 descriptors/API, core/common code, Fabric entrypoints, build metadata, or
the existing P4 render path. R13 adds only an explicitly Experimental client dispatcher/admission
seam; it consumes prebuilt geometry and snapshots, then emits through the same verified Minecraft
26.1.2 public collector and standard `RenderTypes` used by P4.

## R15 X4 terminal-epoch relation; X6 remains signal-only

R15 changes only X4 host production/tests/contracts and documentation; it makes no X6 production,
dispatcher, reload, entrypoint, renderer, resource, or shared-owner wiring change. X4 now has one
terminal epoch `E`: opening it rejects new prepare and submit admission, every open snapshot remains
a future-`B` source, and only a submit hold admitted before `E` may finish. X4 seals and replays
`F = select(Ae*, select(select(B*, C*), D*))`. An ordinary non-reentrant public X4
`retire()`/`close()` waits for all open-snapshot, admitted-work, and retained-membership sources;
only exact renderer/provider/revoke callback owner or contribution owner reentry coalesces into the
outer owner and avoids self-wait.

That synchronous X4 rule must not be projected onto X6. `X6PreparedRenderPlan.close()` and its final
submit hold remain signal-only: they request the registered lifecycle-owner bridge, return without a
physical-release acknowledgement, and never close the provider on the caller. The serialized X6
lifecycle owner remains the only physical close path.

## r12 history and r13 owner-drain submission lifetime

R12 established the deferred close/submit-hold defect and its formal review is retained as
**FAIL (0C/1H/1M/1L)**. R13 preserves the close-request linearization point but replaces the
ownerless/direct-drain model. A successful plan is created only by the additive seven-argument
factory overload with public Experimental `X6LifecycleDrainDispatcher`. Before X6 pins an X1
`ProviderLease`, the factory registers one prebuilt bridge runnable and receives an `Admission`.
The real host must retain that bridge in a bounded, serialized lifecycle-owner facility through
reload/shutdown and must not invoke it inline or before `requestDrain()`.

`X6PreparedRenderPlan.close()` and the final admitted submit hold only request that bridge. Once a
close request linearizes, later `X6PlanSubmitter.submit` calls are rejected; an earlier submit keeps
its exact pin until its `finally` drains. Neither close nor submit physically closes the provider on
its caller. The lifecycle-owner bridge is the only physical close path, and runs outside the plan
and bridge monitors. Thus callback-reentrant close returns without waiting for itself, while an
ordinary caller also receives a nonblocking request result rather than a synchronous drain promise.

Same-request-thread inline bridge execution records a contract violation, makes later submission
fail closed, and does not advance the request state or physically close. A distinct lifecycle-owner
worker may legitimately drain before `requestDrain()` returns exactly once; the returning request
must never overwrite `DRAINING` or `TERMINAL`. A late request failure after such an early terminal
run cannot alter the already returned owner invocation, but it is retained in the bounded
completion and a defensive later owner replay observes the final every-`Error` primary/suppression
selection. Request failures never escape plan close or submit.

Submit/release precedence follows X1 1.1: any `Error` beats an ordinary throwable, while two
`Error` objects preserve the first one and retain a distinct later failure as suppression evidence
when representable. Ordinary suppression bookkeeping cannot replace that selected terminal; an
`Error` thrown by bookkeeping remains an unswallowed `Error`.

The legacy six-argument factory descriptor remains linkable and is deprecated without removal. It
performs its original pure pre-pin validation and then returns
`BLENDLIB-X6-LIFECYCLE-001` / `LIFECYCLE_OWNER_REQUIRED` for an otherwise successful ownerless
request without pinning. The r12 implementation intentionally removes `ACC_SYNCHRONIZED` from the
Java-public `X6PreparedRenderPlan.close` method; its JVM descriptor remains `()V`. The modifier
delta lets the zero-hold caller leave the monitor before it requests the owner bridge. Unknown
third-party `Modifier.isSynchronized` consumers remain a compatibility risk, not a compatibility
claim. X6 itself creates no thread, executor, thread-local, or unbounded queue for this work.

## Boundary

The X6 render-feature production implementation remains intentionally confined to
`blendlib-fabric-client/.../client/render/X6*.java`. It creates no custom `RenderType`,
`RenderPipeline`, custom shader, additive path, PBR path, raw GL call, reflection use, resource
lookup, parser invocation, capability discovery, or provider callback during submit.

ADR-016 remains the controlling boundary: the only emitted standard routes are the already proven
solid, cutout/cutout-cull, and translucent routes from `MaterialRenderMapper` and
`Minecraft2612StaticRigidRenderBackend`. X6 does not reinterpret a rejected intent as a nearby
visual substitute.

`rigid_v1` remains unchanged. The generic v1 `extensions` payload remains unretained, and a
non-empty `extensions_required` remains rejected by its existing decoder. The X6 manifest is a
separate, prepare-only input and is not an extension field, a profile upgrade, a codec, or an
automatic migration.

## Frozen preparation flow

```text
isolated manifest/code inputs + prepared geometry + effects + X1 providers
  -> X6VariantSelectionEngine + complete X6MaterialPlan + controlled static/skinned geometry catalog
  -> X6VariantPlanCompiler / X6RenderLayerPlanner
  -> X6MaterialProviderGeneration (one-pass input snapshot -> current-X6 request-range intersection -> identity preflight -> metadata -> register -> discover -> freeze -> selected-version guard -> prepare -> apply -> publish)
  -> X6PreparedRenderPlanFactory (pre-pin lifecycle-owner admission + exact-generation ProviderLease + frozen capability/provider binding)
  -> immutable X6PreparedRenderPlan
  -> X6PlanSubmitter (prepared snapshot + public collector only)
```

Every stage checks the same `BlendModelKey` and non-negative generation. A plan can be published
only when its variants, layers, complete material map, controlled geometry catalog, exact binding
snapshot/handle, X1 capability decision, and provider lease match exactly. The factory validates
every static primitive identity and node/palette access, or every skinned primitive identity,
captured-mesh index, primitive node, and canonical `(node, skin, joint)` equality against the
exact handle's immutable skin-joint-node table before it asks X1 for a pin. The plan then accepts
future frame snapshots only when they retain that exact handle object and frozen constant-time
skinned mesh-count shape. A captured `SkinnedRenderSnapshot` also retains its exact private
source `SkinnedRenderHandle`; `ModelRenderSnapshot.skinned` compares that identity in O(1) before
any X6 callback can observe a mesh. A `RETIRING`, `CLOSED`, pin-race, missing handle, foreign
same-key/generation handle or capture, invalid node/skin/joint, or invalid mesh is a local X6
rejection, never a partially published plan or a collector-time index exception. Closing an X6
plan requests release of only its bridge-owned `ProviderLease`; X1 does not run retire/close
callbacks until all plans and snapshot consumers of that generation drain.

Submit rejects a stale model/generation, a closed lease, or a catalog/snapshot binding mismatch.
It accepts controlled static/rigid geometry and captured `skinned_v1` geometry. Skinned catalog
entries freeze both the exact `PreparedSkinnedRenderPrimitive` identity and the matching captured
mesh index; submit checks both before consuming the immutable `SkinnedRenderSnapshot`, without
revalidating the complete catalog or resolving a skin/joint table. It respects
an extraction cull result, renders base draws, renders the pre-sorted layer sequence, then
delegates frozen attachment/equipment snapshots to a supplied `ModelRenderBackend`. No selected
part is rediscovered; a visibility variant removes a target and later layers do not resurrect it.

## Variant matrix

| Variant kind | Prepared effect | Submit consequence | Unsupported/mismatch behavior |
| --- | --- | --- | --- |
| `SKIN` | texture replacement | retains primitive and standard material route | missing/wrong effect fails the plan |
| `MATERIAL` | prevalidated `RenderMaterial` | switches only among exact standard P4 routes | non-standard route fails the plan |
| `MESH` | catalog part id | retains a replacement binding already owned by the same catalog; checks static/skinned mode, node, and skin compatibility; no array copy | absent/foreign/incompatible catalog binding fails the plan |
| `EQUIPMENT` | already prepared `ModelRenderSnapshot` | delegated after resolved layers | wrong generation fails the plan |
| `DAMAGE_STAGE` | bounded ARGB tint | multiplies frozen draw tint | wrong effect fails the plan |
| `PART_VISIBILITY` | final boolean | hidden part is absent from the draw map | wrong effect fails the plan |

Selection is deterministic: rules are ordered by priority descending then canonical rule id. A
top-priority tie is `BLENDLIB-X6-VAR-004`; insertion/registration order is never a winner. A
matched rule wins, otherwise a declared default wins, otherwise the baseline is retained with
`BLENDLIB-X6-VAR-005`. Bounds are 128 variants, 256 rules, 512 distinct parts, and eight
predicates per rule. After selection, same-part effects compose only in frozen `MESH -> MATERIAL
-> SKIN -> DAMAGE_STAGE -> PART_VISIBILITY` order. More than one selected effect of the same kind,
or `EQUIPMENT` combined with an ordinary geometry effect on the same target, fails closed rather
than relying on selector or map insertion order.

## Layer matrix

The planner sorts by phase, numeric order, and canonical layer id. Two entries claiming the same
phase/order/part fail rather than use insertion order. A selector is resolved to an existing
prepared part before publication. A per-bone selector must also resolve its canonical bone id to
an explicit `(node, skin, joint)` binding for the selected skinned primitive. During final plan
preparation it freezes only influenced triangle offsets; submit references the captured immutable
mesh payload and never looks up a bone id or re-emits the whole mesh. Static/rigid per-bone targets
fail closed.

| Layer | Fixed phase | X6 state | Material/light semantics |
| --- | --- | --- | --- |
| `GLOW` | `POST_BASE` | supported | final target route with emissive/full-bright vertex light |
| `OVERLAY` | `OVERLAY` | supported | explicit override or final target material |
| `DAMAGE_FLASH` | `OVERLAY` | supported | explicit override or final target material; frozen default/custom ARGB flash multiplier is applied at submit |
| `ATTACHMENT` | `ATTACHMENT` | supported | exact prepared child snapshot, no material lookup |
| `SECONDARY_TEXTURE` | `POST_BASE` | supported | explicit standard override or final target material |
| `PER_BONE_PART_TEXTURE` | `POST_BASE` | supported | explicit standard override or final target material plus frozen canonical bone subset |
| `OUTLINE` | `PRESENTATION` | supported bounded CPU shell | final/explicit source texture and tint, forced public cutout no-cull route, frozen dark tint, and fixed normal offset; no custom `RenderType`/shader |
| `SHADOW` | `PRESENTATION` | supported bounded CPU flatten | final/explicit source texture and tint, forced public translucent no-cull route, frozen dark tint, and fixed model-space flatten transform; no custom `RenderType`/shader |

Layer capacity is 16. Blend/cull/light/texture-target semantics and a frozen presentation operation
are recorded explicitly for auditability. Material precedence is an explicit layer source override,
otherwise the final target draw after `MESH -> MATERIAL -> SKIN -> DAMAGE_STAGE`; required layer
semantics then derive glow/outline/shadow behavior. Thus no layer can fall back to a catalog
primitive material after variants compose. Each executable layer submission stores its final
material, standard `RenderType`, semantics, target binding, and (where applicable) bone subset.
The submitter only consumes those plan-time values; it does not create a `RenderType` or resolve a
part map per frame. In particular, additive blending is not introduced by a semantic enum value.

The submit hot path reuses a stateless standard-route backend through plan-time `RenderType`
bindings, frozen part/layer lists, and a per-thread quaternion scratch. It creates no per-frame
map, backend, quaternion, catalog scan, or bone-to-part `Stream` predicate. Each public collector
submission creates one fixed `FrozenGeometryRenderer` callback object carrying only immutable
geometry references and scalar frame values; the former nested capturing `VertexSink` lambda is
eliminated. Its callback-local pose/consumer state is cleared after use and never retains a plan,
lease, or thread-local scratch. This is source/unit allocation evidence only; the performance gate
remains `WAITING`.

## Material and provider matrix

| Intent | X6 result |
| --- | --- |
| opaque, legal cutout, legal translucent | accepted only through the existing `MaterialRenderMapper` validation |
| additive | rejected; P4 has no approved additive public path |
| complex PBR | rejected by ADR-016 boundary |
| custom shader | rejected by ADR-016 boundary |

`X6MaterialProviderGeneration` is adapter-private control-plane glue over the already experimental
X1 `MaterialProvider` protocol. Before X1 registration it makes one bounded pass over caller-owned
provider input without calling its `size`, `contains`, or array-conversion methods. It then performs
an identity-only pass over that private snapshot before reading supported material-capability Sets.
A duplicate canonical provider id, including the same provider object appearing twice, is an X6
`CAPABILITY_FAILURE`/`ERROR` whose subject is the duplicate id and whose message preserves X1's
`BLENDLIB-X1-CAP-001: Provider identity is already registered or being registered`. The result is
independent of input order: there is no winner, supported-capability metadata is not read, and X6
fails before `CapabilityRegistry.register`, offers, or `ProviderLifecycleSession` construction.
No contender receives `offers`, `prepare`, `apply`, `retire`, or `close`; all remain caller-owned.

R11 explicitly adopts the current Experimental host contract `1.1.0`. `CapabilityRegistry` remains
a generic versioned request/offer data plane and may still represent historical `1.0.0` metadata,
but a current X6 generation is not generic: it intersects every caller-supplied request with
`[1.1.0, 1.2.0)` before discovery and rejects a frozen selected offer outside that line. Therefore a
broad caller range such as `[1.0.0, 2.0.0)` cannot silently choose a historical `1.0.0` provider,
while a current compatible offer still publishes normally. Version negotiation never restores a
legacy throwable policy: every directly thrown `Error` remains terminal and escapes as the exact
original object after required cleanup; ordinary non-`Error` callback/input failures remain bounded
diagnostic or explicit-fallback input.

For duplicate-free input, provider ids and supported material-capability Sets are copied through a
bounded ordinary-`Throwable` containment boundary. A null or ordinary runtime input/metadata
failure is excluded with an X6 diagnostic, allowing only an explicit X1 optional fallback; a
duplicate-id error never falls back. Every directly thrown `Error` instead remains fatal with exact
identity: it is never normalized into diagnostics or a safe-string payload, and post-session
cleanup completes before it escapes. Until `ProviderLifecycleSession` is constructed, every
provider remains caller-owned: X6 never calls `close()` for metadata, registration, discovery, or
frozen-plan failure. Once a publishable X1 plan creates the session, all later X6 rejection retires
through that X1 owner and retains its complete retire/close result. X1 still registers and owns the
    exact external provider object for offers and lifecycle callbacks,
    preserving shared close/pin-race semantics. The final X6 plan retains only immutable capability
    outcome/provider-id/fallback-id metadata; its bridge privately owns the exact X1 lease, and
    submit receives neither a provider object nor the raw lease.
Required capability failures, priority ties, invalid provider metadata, null/nonfatal callback
failures, retirement/close state, and pin races remain local to the affected model/generation. The
consumer fixture demonstrates the public metadata/lifecycle contract without depending on any
client implementation class.

The plan factory owns one best-effort release from the instant pin acquisition succeeds until a
complete success result can return the lease-owning plan. Its guard includes provider-binding
materialization, plan construction and immutable copies, and result construction. Any post-pin
`Error`, including `AssertionError`, `LinkageError`, `OutOfMemoryError`, and `ThreadDeath`, is
re-thrown as the same object only after the factory completes one exact-once release attempt. A
primary error retains precedence and suppresses distinct release failures when representable. With
an ordinary primary, the first fatal release context wins: launcher, worker, then terminal cleanup.
One package-private atomic release controller is the sole owner of the raw `ProviderLease` during
failed publication. Launcher, worker, and fallback paths receive only that controller; every
launch/start/await ambiguity finishes through its cached exact-once terminal close result, so no raw
lease can double-close or leak. Suppression bookkeeping skips self-suppression and
suppression-disabled metadata, retains ordinary originals whenever representable, and never swallows
an `Error` thrown by `addSuppressed`. A fatal pin before any lease is returned performs no fabricated
release. The pin/assembly, release-controller launcher, and suppression test seams are
  package-private; the old six-argument public factory descriptor remains linkable, while r13 adds
  its separate lifecycle-owner overload and bridge-owned successful plan-close path.
When a started worker itself reaches a terminal lease-close failure, X6 reports that started-worker
terminal condition; a true synchronous fallback close failure is reported separately and is never
mislabelled as a started-worker terminal close. Launch, start, and await faults retain their own
diagnostic paths and their exact-once controller result.
The normal potentially terminal failure release runs once on a named, non-daemon platform worker.
The synchronous factory owner joins that worker to termination, continues waiting across
interruption, then restores the interrupt flag before it returns or rethrows; this is required to
observe cleanup fatal identity. A host must therefore schedule the complete factory call on its
preparation/lifecycle-owner worker. A later successful plan close remains signal-only and can be
called reentrantly; the admitted bridge alone performs physical drain on the lifecycle owner. There
is no shared executor or detached cleanup task.

## Diagnostics and fallback

X6 diagnostics are local stable codes and do not rename v1 or X1 errors:

| Family | Meaning |
| --- | --- |
| `BLENDLIB-X6-MANIFEST-001` | malformed or unsupported isolated manifest input |
| `BLENDLIB-X6-VAR-*` | bounded selection, duplicate, unknown, tie, or baseline-fallback outcome |
| `BLENDLIB-X6-LAYER-*` | capacity, duplicate, placement/phase, target, or missing required standard layer material |
| `BLENDLIB-X6-MAT-001` | advanced/nonstandard material rejected before publication |
| `BLENDLIB-X6-CAP-*` | duplicate canonical identity, X1 selection, or provider-lifecycle failure localized to this generation; the duplicate wrapper preserves X1 `BLENDLIB-X1-CAP-001` |
| `BLENDLIB-X6-SNAPSHOT-*` | generation mismatch or released provider lease |
| `BLENDLIB-X6-GEOMETRY-001` | incompatible static/rigid geometry or effect binding |
| `BLENDLIB-X6-LIFECYCLE-001` | an otherwise valid plan lacks the required lifecycle owner, or its same-request-thread inline owner contract was violated |

Failure never changes a currently published generation. The future reload owner must retain the
existing last-known-good plan or existing missing-model behavior according to its own current
reload policy; it must not partially publish an X6 plan. See
[integration handoff](integration-handoff.md) for the exact unimplemented wiring work.

## Evidence and remaining gates

The frozen/bound unit suite covers all six variants, data/code manifest parity, deterministic ties,
same-part composition/conflicts, exact catalog handle/node/mesh checks, baseline fallback and
bounds, strict UTF-8 byte boundaries, standard and rejected material modes, complete material
coverage, all eight layer types, final layer inheritance/override routes, canonical skinned bone
subsets, ordering/conflicts/generation checks, static and captured `skinned_v1` public-collector
plan submission, X1 selection/pins/retirement/race/fatal metadata boundaries, and a public-API-only
external consumer provider. Provider regressions additionally cover both duplicate-id input orders,
the same object repeated, a genuinely one-pass hostile collection, exact CAP-001 mapping, and the
pre-registry zero-metadata/offer/lifecycle-callback ownership fence. The public consumer fixture
also uses a latch-coordinated 128-by-4 deterministic close matrix instead of a fixed scheduler spin
assumption. It proves release-complete then close, release in a real terminal callback then waiting
close, close-complete then release, and close-complete then terminal release plus waiting close. A
separate blocking harness proves the controller stays free while a real last-pin release and
duplicate session close wait on the provider callback. Its main body shares one monotonic deadline,
while cleanup uses separately bounded recovery joins. Commands and scope are recorded in
[test evidence](test-evidence.md).

Still `WAITING`: a production caller for `X6LifecycleDrainDispatcher`, production reload/entrypoint
wiring, resource ownership, network behavior, real reload behavior, manual/in-game client and
visual validation, entity/block/world host coverage, 20-reload stress, performance/hardware, and
Iris/Sodium compatibility. This track makes no claim about any of those gates. The R15
pre-documentation source/test and static/binary PASS results do not turn them into PASS.
The R5 duplicate-provider contract repair advances none of them.
The r8 ownership and race-harness repair, r9 fatal-boundary repair, r10 Error/controller repair,
r11 protocol/worker-diagnostic repair, r13 owner-drain repair, and R15 X4 terminal-epoch repair
likewise advance no runtime Gate. Exact R15 commands, counts, artifact paths, and report/manifest
SHA-256 values are recorded in [test evidence](test-evidence.md).

## Agent Innovation

The following pre-r13 adapter-private mechanisms are explicitly recorded as **Agent Innovation**.
They do not expand the stable v1 API, Profile, descriptor, pipeline, or X1 lifecycle contract. The
R15 documentation candidate remains unaccepted pending independent docs/full-source review and
formal integration; neither its source/static PASS nor this reconciliation is accepted runtime wiring.

The R5 duplicate-provider repair records no Agent Innovation; it only enforces X1's existing
canonical provider-identity contract before registration.
The R6 coordinated consumer race harness also records no Agent Innovation and changes no
production lifecycle semantics.
The r8 post-pin ownership guard records no Agent Innovation; it closes an exact-generation lease
according to the already selected X1 lifecycle and changes no public or rendering contract.
The r9 fatal-boundary repair, r10 exact-once controller repair, and r11 protocol/worker-diagnostic
repair record no new rendering route, stable public API, or runtime wiring. R11 makes the controlled
Experimental host version and X6 selection boundary explicit; it remains under independent review.
The r13 owner-drain repair is likewise a lifecycle safety/compatibility repair, not a new render
route or an accepted runtime worker. Its small Experimental public dispatcher/admission seam is
additive solely so a future host can own bounded scheduling explicitly.
The R15 terminal-epoch repair is also a stricter X4 behavioral-compatibility repair. It changes no
X6 production/wiring and records no new X6 Agent Innovation.

| Mechanism | Motivation | Rejected alternative | Compatibility and tests |
| --- | --- | --- | --- |
| Prepare-time per-bone triangle-offset subset | make a per-bone layer select only positively influenced triangles while sharing the captured skinned payload | redraw the whole part, or expose new GPU/shader state | static paths fail closed; canonical `(node, skin, joint)` identity is factory-fenced; skinned subset and zero-influence regressions cover it |
| CPU outline/shadow fallback | keep bounded presentation effects on already verified public cutout/translucent routes | custom `RenderType`, shader, raw GL, or unverified pipeline | no new rendering API or route is introduced; real visual/GPU/Iris/Sodium evidence remains `WAITING` |
| Hostile-provider containment plus caller-owned pre-session inputs | prevent control-plane metadata/input faults from breaking explicit fallback or bypassing X1 shared identity ownership | directly close rejected providers, or make discovery/fallback submit-time work | no X1 API changes; provider ownership, pin-drain, fatal identity, null/hostile input, and cleanup aggregation regressions cover it |
| Frozen deferred geometry renderer | remove nested plan/lease-capturing emission callbacks from collector submission | retain nested capturing vertex lambdas | the callback owns only immutable geometry/scalars; source checks cover the structural boundary while allocation benchmarks remain `WAITING` |
