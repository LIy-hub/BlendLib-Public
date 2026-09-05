# X7 shared generation lifecycle (D1 candidate)

## Scope

This is the reload-side integration boundary for X7 B1's local resource candidate. It is not a GPU
backend enablement. Production uses the existing complete CPU handles and standard `RenderType`
route only.

## Ownership and publication

`ClientModelRegistry` retains the sole active `AtomicReference`. It delegates lifecycle decisions
to one package-private `ClientGenerationResourceOwner`; the owner does not cache a second active
generation. `ClientModelReloadListener.apply` constructs all ordinary CPU render handles first,
wraps the complete generation in `PendingGenerationTransaction`, then requests its single
CPU-freeze/CAS publication. The owner first takes one atomic transaction claim, moving every
close callback out of the transaction and into that claim before freeze, CAS, publication, or
unpublished cleanup. A replay or concurrent duplicate cannot take a second claim and is rejected
without creating detached cleanup or touching an active generation's callbacks.

After that one-shot transaction claim, `ModelRegistryGeneration` performs one permanent atomic
owner binding before freeze, lifecycle indexing, an active CAS, retirement, or any owner-visible
publication mutation. Binding the same owner is idempotent; a foreign owner is rejected without
adding an indexed record, changing either active reference, or changing the generation retirement
bit. Initial registry adoption follows the same order: bind first, then create its owner record,
then expose the initial active generation. There is no detach operation: a successful binding stays
with that owner through later freeze, stale, or shutdown cleanup, preventing ABA-style reuse by a
second registry. A foreign loser transfers only its own callback obligation to its detached
unpublished cleanup state machine; it never retires the foreign generation or retries through the
generation's real owner. A failed detached close remains observable and can be retried by closing
the loser registry.

Candidate construction or freeze failure becomes `ABORTED`; the old generation stays current.
Stale, duplicate-current, closed-registry, and explicit-abort candidates are also aborted rather
than replacing a newer active generation. Every registered close callback transfers to the same
owner-controlled drain/close state machine before an unpublished candidate record can disappear.
An abort that wins before the publication claim is adopted as one aborted-cleanup claim; once the
publication claim wins, direct abort rejects and cannot change its terminal publication. After a
successful CAS, that claim completes publication without a second failable terminal mark, then the
displaced generation is retired under the same owner monitor.
No background completion may publish a partial candidate after `apply` returns.

## Render-resource boundary

ADR-X7006 installs a production render-owner callback spine only through the no-argument
`ClientModelRegistry.createMinecraft2612Client()` adapter factory. The public constructor remains
CPU-only. The trusted owner uses `Minecraft.getInstance()::execute`, asserts inside the handed-off
task, and calls `RenderSystem.queueFencedTask`; it exposes no caller-supplied executor/device
surface.

T1a/T1b still treat `apply` as non-render-owner work. They do not call the B1 resource factory,
`GpuDevice`, `GpuBuffer`, or a GPU upload/close API, and they attach no empty/dormant resource
callback. A complete CPU candidate without any GPU resource remains the only selected route.

T1b provides the package-private `CompletedGenerationResourceSet` and one package-private registry
forwarder. The factory rejects zero resources and accepts only one already-complete composite for
one exact generation object. D1 asserts the installed render owner before admission, then uses its
existing monitor as the common linearization point for attachment, retire, supersede, and shutdown.
Each lifecycle record has one attachment slot which freezes on retirement. A losing admission never
queues a fence, changes a lease, or closes caller-owned resources; caller cleanup races only on the
set's ownership monitor and therefore has exactly one winner with the D1 move.

An accepted set's close callback synchronously closes or throws on D1's already-fenced render
thread. It does not call `X7GpuGeneration.retire`, queue a second fence, or return before physical
close. The close sequence becomes eligible only after the last exact D1 lease releases. A failed
physical close leaves the set and callback retained in `CLOSE_FAILED`; a fresh non-zero D1 attempt
retries only callbacks that failed. A successful set callback clears its retained generation/action,
and successful terminal D1 close removes the record and its slot. No production code constructs or
attaches a set in T1b; focused tests use generic fake physical close actions only.

## T1a Fabric pass-host scope

The package-private `X7Minecraft2612PassOwnerHost` registers
`LevelRenderEvents.AFTER_SOLID_FEATURES` and
`LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN` exactly once from the client entrypoint. Each event
asserts the render thread and executes one phase-only scope synchronously before returning. The
scope receives no retainable `LevelRenderContext`, target, pose stack, buffer source, device,
encoder, or pass. It is empty in T1a because no prepared X7 draw exists.

## Lease admission and shutdown

`ModelRegistryGeneration` can admit a `GenerationRenderResourceLease` only for the exact active
generation object and identical handle object stored in its immutable map. Retired, stale, cloned,
and cross-generation handles are rejected. Ordinary reload retirement still uses the T1a
`queueFencedTask` path after the exact generation drains; an accepted queue entry is not physical
close and is not reinterpreted as `CLOSED`.

T1c adds a separate shutdown-only path for the trusted registry. Its package-private coordinator
closes future creator admission before any device access, freezes D1 publication/attachment/lease
admission, and seals one immutable attempt-identified final batch only when there is no outstanding
creator, publication transaction, lease, render handoff, or ordinary fenced callback. Every
publication first holds a private D1 permit from before its one transaction claim until its
publication or abort callbacks are record-owned. A nonzero cutoff count is retained as typed
`OUTSTANDING_TRANSACTIONS`; after final shutdown begins, a new unclaimed transaction remains
caller-owned. Final-batch requests never also enter `queueFencedTask`. A client-only, version-pinned
Mixin brackets the unique original
`Minecraft.renderFrame(Z)V` `RenderSystem.flipFrame` call: it creates one BlendLib-owned `GpuFence`
before the original present and zero-polls it only after that present returns. Only a signaled owned
fence lets D1 synchronously execute the batch's physical-close callbacks. Timeout, wait failure,
adapter mismatch, a missing post-present callback, and all outstanding-work cases retain exact
non-closed diagnostics; they close at most the owned fence object. The `CLIENT_STOPPING` callback is
a non-throwing fallback and does not drain RenderSystem's private FIFO or call the game-owned device
close.

The public trusted factory remains per-call and returns isolated registries. Only the registry
selected by the sealed `BlendLibClientServices` bootstrap installs the one client-wide shutdown
coordinator/hook; secondary factory registries keep ordinary `closeRegistry()` behavior unless
they become that primary registry.

No production resource creator or attachment caller exists in T1c, so the coordinator has no live
GPU resource to drain in production. Actual allocation/upload/draw and genuine-client shutdown with
live resources remain **WAITING** despite the implemented lifecycle spine.

## Explicitly not wired here

- any duplicate X4/X6/B1 hold beyond the existing D1 parent composition;
- actual collector/pass completion holds;
- a B1 resource attempt, GPU upload, `RenderPass`, pipeline, draw, batch, or skinning path;
- a real resource-set creator/production attachment caller or verified live-resource shutdown;
- a benchmark capture owner or any hardware/visual/compatibility claim.
