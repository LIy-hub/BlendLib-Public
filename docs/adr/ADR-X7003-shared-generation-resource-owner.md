# ADR-X7003: Shared generation resource owner is CPU-first until a legal render owner exists

## Status

Proposed / Experimental X7 D1 repair candidate. This record adds an internal lifecycle boundary
only. Focused verification is recorded separately and does not accept a renderer, GPU backend,
hardware result, compatibility result, or manual visual gate; independent review remains required.

ADR-X7006 is the additive T1a successor for the trusted Minecraft 26.1.2 handoff/fence spine and
the Fabric pass-host callbacks. It does not revise this ADR's single-owner state machine, and it
attaches no resource or close callback.

## Context

The X7 B1 candidate owns a local, adapter-private resource transaction and a truthful local close
state machine, but it is intentionally not connected to reload publication, the active model
registry, X4/X6 holders, or a real render-pass owner. Fabric reload `apply` is game-thread work,
not proof of `RenderSystem` ownership. The current collector callback owns a `VertexConsumer`, not
a live `RenderPass`, target, pipeline, or completion lifetime.

Therefore a reload implementation must not allocate a `GpuBuffer`, call an X7 GPU factory, or
publish a partially uploaded candidate from `apply` merely because it is called on the game thread.

## Decision

`ClientGenerationResourceOwner` is the only generation-resource lifecycle coordinator. The
registry keeps its one `AtomicReference<ModelRegistryGeneration>` as the only active-generation
truth; the owner has lifecycle records only for current, retiring, retryable-failed, or
unpublished-cleanup work, but no second active pointer. A successfully closed or aborted-and-drained
record is removed from the generation index and clears its generation/callback references.

`PendingGenerationTransaction` owns a complete immutable CPU candidate before any publication.
The resource owner first takes its one atomic claim, which transfers the close-callback list out of
the transaction. That claim alone may freeze, publish, or transfer callbacks to one unpublished
cleanup record. Replaying a consumed transaction is rejected before cleanup; an abort that wins
before the claim becomes the one aborted-cleanup claim, while abort rejects after a publication
claim has won. The pre-publication boundary is:

```text
CPU_PREPARED -> PUBLICATION_CLAIMED -> POLICY_FROZEN -> PUBLISHED
      \-> ABORTED -- one aborted-cleanup claim --> RETIRING
```

Only a complete CPU candidate can reach the single registry CAS. An invalid, stale, duplicate-current,
closed-registry, or explicitly aborted candidate leaves the already-published generation active; any
registered close callbacks still enter the owner drain/close state machine. Current production has
no legal render owner, so the listener freezes the full existing CPU / standard-`RenderType`
candidate synchronously and suppresses every GPU experiment. It does not invoke B1 factory/device
code or start asynchronous half-publication. Once CAS succeeds, the claim completes its non-failable
published terminal state and the owner retires the displaced generation before leaving the owner
monitor; it never calls a separately abortable post-CAS `markPublished()` step.

For a generation with adapter-private close callbacks installed by a future approved owner, the
post-publication lifecycle is:

```text
PUBLISHED -> RETIRING -> FENCE_QUEUED -> CLOSING -> CLOSED
                         \-> CLOSE_FAILED -- explicit retry --> FENCE_QUEUED
```

`GenerationRenderResourceLease` is an idempotent exact-generation/exact-handle hold. New holds
are admitted only for the registry's current `PUBLISHED` generation and the identical map-owned
handle. Retirement rejects stale or cross-generation admission. The owner schedules its fence only
after the last lease releases. A non-zero attempt ID follows each queued close. Retry runs only
callbacks that failed previously; already successful callbacks are never closed again.
Terminal close removes the owner record, while `CLOSE_FAILED` deliberately keeps its record and
failed callback set retryable. The owner skips self-suppression when two callbacks throw the same
`Throwable` instance so callback aggregation cannot strand a record in `CLOSING`.
An aborted duplicate-current transaction uses a detached cleanup record so it cannot retire the
active generation; that record remains reachable through the same generation's explicit retry
until its callbacks close successfully.

The public no-argument registry owner has no `RenderOwnerCallbacks`. ADR-X7006 adds one trusted,
no-argument Minecraft 26.1.2 registry factory whose package-private callback implementation is
fixed to Minecraft execute, render-thread assertion, and fenced task APIs. T1a installs no resource
or close callback, so this spine is not GPU enablement. A client-stop hook asks the registry to
retire all known current and retiring generations; it does not pretend that client stop drains a
queued fence.

## Consequences

- No raw GL, public `GpuBuffer`, `GpuDevice`, custom pipeline, renderer switch, or second resource
  owner is introduced.
- The current CPU backend remains the only selected rendering route.
- X4 snapshot, X6 plan/drain, and actual pass-completion leases remain future integration work;
  this D1 boundary neither rewrites nor claims their wiring.
- Close diagnostics report actual state, outstanding holds, fence attempts, and close failure type
  without treating an allocation, a queue request, or a candidate plan as a draw.

## Waiting

An ADR-approved real pass/render-owner seam, canonical B1 attempt-to-policy mapping, X4/X6/pass
lease attachment, GPU draw/pipeline work, compatibility, client/server lifecycle runs, hardware
evidence, and visual acceptance all remain **WAITING**.
