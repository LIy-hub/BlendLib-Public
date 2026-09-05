# X6 Runtime Integration Handoff

**Status:** R14 final source review passed, but the subsequent formal-integration completion-order
probe reproduced the blocking race 96/96 times in eight JVMs. Formal integration therefore remains
**BLOCKED**. R15 then recorded R3 **FAIL (0C/4H/2M/0L)**, R4/R5 non-verdict design, valid RED
(6 tests / 1 intended assertion failure), and a frozen candidate with pre-documentation source/test
**PASS (0C/0H/0M/0L)** plus static/binary **PASS**. This documentation reconciliation awaits
independent docs/full-source review; it is not integration or release. Required shared integration,
including a production dispatcher caller, remains unstarted.

## R15 cross-contract boundary

X4 now opens one terminal epoch `E`. Opening `E` rejects new prepare and submit admission; open
snapshots remain future-`B` sources, and only pre-`E` admitted submit holds (`H`) may finish. X4
seals and replays `F = select(Ae*, select(select(B*, C*), D*))`. Ordinary non-reentrant public X4
`retire()`/`close()` callers wait for every open-snapshot, admitted-work, and retained-membership
source. Only exact renderer/provider/revoke callback owner or contribution owner reentry coalesces
into the outer owner and must not self-wait; a foreign caller still waits.

X6 deliberately remains signal-only. Plan close/final-hold release only requests the registered
`X6LifecycleDrainDispatcher`; the serialized lifecycle owner is the sole physical provider-close
path, and neither an ordinary nor callback X6 caller receives a synchronous drain acknowledgement.
R15 changes no X6 production or wiring. Exact automated evidence is bound by path and SHA-256 in
[`test-evidence.md`](test-evidence.md); manual client, network, reload, visual, Iris/Sodium,
hardware, and performance gates remain **WAITING**.

## Exact future owner work

1. In the existing client reload owner (currently centered on
   `blendlib-fabric-client/.../reload/ClientModelReloadListener`), add a prepare-only branch that:
   - resolves X6 manifest bytes from an explicitly approved resource location;
   - calls `X6VariantManifestParser.parseForPrepare(byte[])` once, before decoding or snapshot creation;
   - creates canonical part-to-controlled static `PreparedRenderPrimitive` bindings or, for
     `skinned_v1`, part-to-`PreparedSkinnedRenderPrimitive` plus exact captured-mesh-index bindings;
     it also creates canonical `(node, skin, joint)` bone bindings without copying vertex/index
     arrays. Before pinning, the factory must prove each tuple against the exact
     `SkinnedRenderHandle` skin-joint table (`skin.joints[joint] == node`), including skin/joint
     bounds;
   - creates code-side `X6VariantEffectCatalog`, `X6MaterialPlan`, and `X6RenderLayerPlanner`
     inputs from bounded client configuration only;
   - invokes `X6MaterialProviderGeneration.prepareAndPublish` with an explicit provider registry
     owner and explicit `CapabilityRequest` set built for the current Experimental compatible line
     `[1.1.0, 1.2.0)`. The bridge independently intersects any caller-supplied request with that
     line and validates frozen selected offers, so a broad request cannot select a historical
     `1.0.0` offer into the current X6 lifecycle; generic X1 registry data-plane tooling may still
     represent historical metadata. The supplied provider collection stays
     caller-owned until that method has constructed an X1 `ProviderLifecycleSession`. Duplicate
     canonical provider ids, including the same object twice, must produce the same exact X1
     `BLENDLIB-X1-CAP-001`-backed X6 error in either input order, with no winner, before supported
     capability metadata, registry registration, offers, session construction, or lifecycle
     callbacks. No duplicate contender is closed. Only non-error null/metadata exclusion may reach
     an explicit optional fallback. Registry, discovery, and frozen-plan rejection must not close
     a supplied provider. Once the session exists, a failed candidate retires it through X1 and
     consumes the complete retirement result (including close diagnostics); and
    - invokes the additive seven-argument `X6PreparedRenderPlanFactory.prepare` with the exact
      prepared binding snapshot and a real `X6LifecycleDrainDispatcher`, only after variants,
      layers, the complete `X6MaterialPlan`, controlled geometry catalog, and provider generation
      share model key and generation. Its `register(prebuiltRunnable)` implementation must retain
      that exact runnable before pinning in a bounded, serialized lifecycle-owner facility and must
      never run it inline or before `Admission.requestDrain()`. It keeps the bridge reachable through
      reload/shutdown until request acknowledgement is final; an early owner terminal does not
      permit discard because a late request failure may change the final replay outcome. The whole
      synchronous factory call must run on the reload preparation/lifecycle-owner worker, never the
      render/controller thread, because a post-pin failure waits for its named non-daemon release
      worker to finish before selecting fatal precedence. The legacy six-argument overload remains
      linkable only for compatibility and returns owner-required after its legacy pre-pin checks;
      it must not be used as a production success path.

2. In that reload owner's apply/publish step, atomically publish only a fully successful
    `X6PreparedRenderPlan`. On any diagnostic error, retain the prior published plan or follow the
    existing missing-model policy. Never publish a partial variant/layer/provider state. Retire the
    old provider generation only after every plan/snapshot lease has drained; closing a plan is the
    required release-request action. An r13 `close()` return is a close **request**, not a physical-release
    acknowledgement: it immediately rejects later submit admission, while an earlier admitted
    callback retains its hold until its `finally` drains it. Zero/final-hold paths may only signal
    the registered bridge; neither caller may physically close the lease. The lifecycle-owner bridge
    must perform physical close outside plan/bridge monitors, and the host must not wait for it from
    a callback. A same-request-thread inline bridge run is a fail-closed contract violation; a
    distinct owner worker may run before `requestDrain()` returns and must drain exactly once. A
    request return/throw may advance only a still-requesting bridge and must not overwrite
    `DRAINING` or `TERMINAL`. If a request fails after an early terminal run, retain the exact
    failure for final completion/defensive owner replay; do not expose it from plan close or submit.
    Do not add a second post-pin construction path outside
   `X6PreparedRenderPlanFactory`: its package-private-tested atomic release controller is
   responsible for one best-effort release until the complete success result returns. Every post-pin
    `Error` retains exact identity; launcher, worker, and fallback receive no raw lease and all
    launch/start/await ambiguity resolves through that controller's exact-once terminal result.
    A terminal lease-close failure reported by a started worker is distinct from a true synchronous
    fallback-close failure, so the diagnostic must not mislabel one as the other. Terminal
    primary/release precedence, suppression-bookkeeping terminal propagation, and the controlled
    fallback or `may remain pinned` diagnostics are part of ADR-X6001.

3. In the eventual entity/block/world render-host seam, hand the extraction-produced
   `ModelRenderSnapshot`, `RenderSubmissionContext`, and existing public `ModelRenderBackend` to
   `X6PlanSubmitter.submit`. The host must preserve the exact culling result, must not construct a
   plan in submit, and must not add registry/resource/provider access to the hot path. For a
   skinned plan, submit may perform only the frozen exact-handle/key/generation checks and the
   captured skinned-mesh-count shape fence; it must not rescan the catalog, remap joints, or use a
   stream/capturing predicate.

4. Any X4 or host integration must hand over a frozen parent snapshot and, for attachments, an
   already prepared child snapshot. It must not mutate X4 state or sample entity/world data to
   resolve a layer at submit. For `skinned_v1`, it must hand over the existing captured
   `SkinnedRenderSnapshot` that supplied every catalog mesh index and was captured from that
   exact `SkinnedRenderHandle`. The O(1) source-handle identity fence rejects a same-key,
   same-generation, same-count capture from another handle before any collector callback; it does
    not reskin, remap, or scan a mesh at submit. This future X6 wiring requests no further X4 source
    change. That sentence does not describe the R15 candidate itself: R15 separately tightens X4
    terminal-epoch production/tests/contracts, while changing no X6 production or wiring. The r13
    committed X4 production/test/document scope remains recorded below as history.

5. A future owner must register the resource policy, reload ordering, diagnostics surface,
   lifecycle owner, and fallback behavior in the project-level docs/progress ledger only after an
   approved integration plan. This X6 candidate deliberately does not edit those shared files.

## Agent Innovation boundary

The candidate deliberately confines its new behavior to prepared data: canonical per-bone offsets,
CPU-side OUTLINE/SHADOW fallback geometry, hostile-provider containment with explicit ownership,
and `FrozenGeometryRenderer` execution. It does not claim GPU rendering, visual parity, or a
performance result; those remain future integration and real-client evidence.
The R5 duplicate-provider repair adds no Agent Innovation; it enforces the existing X1 CAP-001
identity contract at the adapter boundary.
The R6 consumer race repair is test-only, adds no Agent Innovation, and changes no production
lifecycle semantics.
The r8 factory ownership guard, r9 fatal-boundary repair, r10 exact-once controller repair, and r11
protocol/worker-diagnostic repair add no new rendering route, runtime wiring, or stable public API.
R11 makes the controlled Experimental current-host selection and diagnostic distinction explicit;
it remains subject to fresh review.
The r12 hold/drain repair and r13 owner-drain repair record no new rendering route or shared
runtime wiring. R13's Experimental dispatcher/admission and additive factory overload make host
ownership explicit; its public diagnostic/ABI deltas are documented, but no production caller is
provided here. The `close` JVM descriptor `()V` stays linkable while its synchronized-modifier delta
remains a reflective-consumer compatibility risk, not an acceptance claim.

## Preconditions before wiring

- The R15 frozen source candidate has pre-documentation source/test PASS (0C/0H/0M/0L) and
  static/binary PASS only. Independent docs/full-source review, controlled shared wiring, and a later
  formal integration review are still required. The prior 96/96 race keeps formal integration
  blocked; this handoff records no runtime or release verdict.
- The owner must show that each selected host uses only the public Minecraft 26.1.2 collector and
  existing P4 standard routes.
- The owner must add integration tests covering initial load, failed replacement, successful
  replacement, stale snapshot rejection, provider pin drain, duplicate/repeated provider identity
  in both input orders with no offer/lifecycle callback, a latch-coordinated close/pin race with
  exact-once terminal callbacks, every session-close invocation in an independent worker, four
  deterministically observed close/release orderings, a real terminal-callback wait, one shared
  monotonic main-body deadline, separately bounded recovery joins, post-pin fatal identity and
  cleanup precedence including worker construction/start failure, fallback-close failure, and
  suppression bookkeeping failure, and no-submit-time discovery.
- Manual/real-client, network, visual ordering, attachments, culling, reload stress, Iris/Sodium,
  hardware, and performance evidence remains required. Unit/static results do not substitute for
  those gates.

## Explicit r13 scope and non-actions

Commit `55f61510a66f0ea8af6190aa276d5993295a754f` did modify these X4 paths:

- production: `blendlib-fabric-client/src/client/java/com/liy/blendlib/fabric/client/host/DefaultX4HostAdapter.java`,
  `blendlib-fabric-client/src/client/java/com/liy/blendlib/fabric/client/host/Minecraft2612X4PlatformAdapter.java`,
  and `blendlib-fabric-client/src/client/java/com/liy/blendlib/fabric/client/host/X4HostFailureSelector.java`;
- permanent test: `blendlib-fabric-client/src/test/java/com/liy/blendlib/fabric/client/host/X4X6ErrorPolicyCompatibilityTest.java`;
- docs: `docs/expansion/x4/README.md` and `docs/expansion/x4/integration-handoff.md`.

Those changes implement/record X1 1.1 every-`Error`, failed-revoke/retry, and exact terminal-identity
compatibility. X2 paths are unchanged. The r13 commit did not alter `ClientModelReloadListener`,
Fabric entrypoints, `fabric.mod.json`, Gradle files, API/core/common packages, the existing P4 fixture
matrix, or any progress/index document. It did not add an X4 runtime entrypoint, renderer wiring,
resource, dispatcher, shared lifecycle-owner integration, a runtime provider, resource location,
production lifecycle dispatcher, or owner task.

## Explicit R15 scope and non-actions

R15 executable changes are confined to four X4 host production files and five X4 host tests. X1
production, X6 render production, common production, Gradle, resources, entrypoints, metadata, and
all shared runtime wiring are unchanged. The package-private
`X4PreparedSnapshot.ReleaseListener` method now carries the already allocated exact submission hold;
the public/protected descriptors and all seven `X4HostLifecycleState` constants are unchanged, and
no submit allocation is added. This documented internal limitation must not be represented as a
public ABI change. Changing it would change executable hashes and invalidate the retained gates.
