# ADR-X7007: Minecraft 26.1.2 final-present owned-fence shutdown

## Status

Proposed / Experimental X7 T1c bounded repair. The immutable R1 independent review remains
**FAIL (0C/1H/0M/0L)** because it found that the public trusted factory had been changed to a
shared mutable singleton. The repair restores per-call registry identity and also closes a later
pre-rereview audit finding in publication-versus-final-batch linearization. Independent R2 reviewed
repair candidate `b3660bb6007ba89847461f5f0cc3c43f4d0d7478`, tree
`dacf56e6080c50f13cbdbc6cad9e376196c8d9b6`, and returned **PASS (0C/0H/0M/0L)**. Its report
SHA-256 is `FADB6814BD02E499E9DEC5CE367E15EA477F53EC533495B9DE960AFB4FE8438F`; its self-excluded
validated-manifest SHA-256 is `15E3639FCCBD86C25399FBCE434713176EB24463213FD7862C2644FC81C0A369`.
The formal `agentloop/blendlib-expansion` branch was fast-forwarded to the same commit/tree. This
ADR still does not enable a production resource creator, attachment caller, allocation, upload,
pass, draw, live-resource shutdown, or performance claim.

## Context

`CLIENT_STOPPING` runs after the normal render loop. A fence first queued there has no guaranteed
later present or `RenderSystem.executePendingTasks()` poll. The public RenderSystem pending-fence
queue is a private global FIFO and exposes neither ownership nor a drain receipt. D1 therefore
cannot truthfully interpret registry retirement, a stop callback, a timeout, or a non-null game
device as permission to close a live buffer.

Minecraft 26.1.2 has exactly one
`RenderSystem.flipFrame(TracyFrameCapture)` invocation in private `Minecraft.renderFrame(Z)V`.
The ordinary window-close path reaches it after `Minecraft.isRunning()` becomes false and before
`CLIENT_STOPPING`. That is the one pinned interval in which BlendLib can seal a quiescent D1 batch,
create its own fence before vanilla's original present, and poll that same fence after the present
returns.

## Decision

### Admission and D1 authority

The public no-argument `createMinecraft2612Client()` remains a factory: every call returns a fresh
trusted render-owner registry with isolated generation, publication, lookup, and close state.
Public `ClientModelRegistry()` stays CPU-only and ownerless. The client still has exactly one
final-present hook owner, but it is selected only when the unforgeable sealed
`ClientModelLookupBootstrap` capability reaches the registry chosen by the synchronized
`BlendLibClientServices.initialize` production bootstrap. Repeating bootstrap with that same
installed registry/backend and unchanged runtime is idempotent; the existing one-time runtime
completion remains supported. A different registry is rejected before it can install a hook.

A package-private coordinator changes `RUNNING -> QUIESCING` before any final-path device access.
That transition closes creator admission. Already-admitted creators are counted; a creator still
in flight at cutoff causes a terminal `FAILED_OUTSTANDING_CREATORS`, and any later attachment is
rejected by D1 while caller ownership remains intact. No production creator or attachment caller
exists in T1c.

D1 remains the sole lifecycle and physical-close authority. Under its existing monitor, shutdown
freezes publication, attachment, and lease admission, retires current records without calling the
ordinary scheduler, and does exactly one of the following:

- seals one immutable shutdown-only `FinalCloseBatch` with a nonzero attempt ID; or
- retains a precise non-closed outcome for an outstanding lease, undelivered render handoff, or
  already queued/invoking normal fence.

Each publication obtains a private D1 permit under that same monitor before it claims its pending
transaction. The permit remains counted until publication or abort cleanup, including every close
callback, has transferred into a D1 record. A final cutoff with a nonzero count fails as typed
`OUTSTANDING_TRANSACTIONS`, retaining both live and cutoff counts; it cannot seal an incomplete
batch or report `DRAINED`. A transaction admitted before the cutoff but finishing afterward is
retained terminally without entering the ordinary fence FIFO. Once a final batch or terminal
shutdown exists, a new transaction is rejected before claim and remains caller-owned. Ordinary
`closeRegistry()` retains its pre-existing abort-and-normal-fence cleanup behavior.

A record selected for the final batch cannot also enter `queueFencedTask`. A late normal handoff
checks its attempt before queueing and becomes a no-op after shutdown invalidation. Existing global
FIFO work is classified as external and is never drained, stolen, cleared, or re-registered.

After a successful owned-fence poll, D1 synchronously runs the batch callbacks on the asserted
render thread. Successful callbacks are never replayed. Physical-close failure remains D1
`CLOSE_FAILED` with the cause and failed callback retained, while logical shutdown forbids a new
retry after teardown begins. Timeout, wait failure, missing final present, adapter mismatch, and
late/duplicate completion remain terminal non-`CLOSED`; they never run a physical callback.

`CompletedGenerationResourceSet`'s internal close name now describes any verified D1 completion,
not only a RenderSystem FIFO callback. It retains exact generation identity, resource count, byte
count, ownership, and close action until successful D1 close. This is still a generic fake-tested
attachment primitive, not a real `GpuBuffer` set.

### Owned final fence and presentation hook

The package-private production adapter accepts only two audited Minecraft class SHA-256 values:
the Mojang/runtime resource-stream form
`AC3890B42C594A1FF7BF3C3EC945C85CCCBD8CB076711D55A20181ED3DD99A7A`, and the design artifact's
Loom project-processed form
`85AACE61CCED0D3388E3C53F8CED84096031D1B94E5CDE662F00CC90F1E28D7C`. The latter differs only by
the audited `blockModelResolver`/`missTime` visibility widening; both retain the same exact
`renderFrame(Z)V`/unique-`flipFrame` contract. No version-pattern or third hash is accepted. On the
asserted render thread the adapter performs only:

```text
RenderSystem.getDevice().createCommandEncoder().createFence()
GpuFence.awaitCompletion(0L)
GpuFence.close()
```

It never exposes or closes the game device. The coordinator uses an injected monotonic clock, a
50,000,000 ns wall-clock budget, zero-timeout polls only, and a secondary finite poll cap. These
are fail-safe infrastructure bounds, not a portable GpuFence timeout unit or a performance result.

A required client-only Mixin adds two `require=1` `@Inject` callbacks at the unique pinned invoke:

```text
BEFORE: admission closed -> D1 final batch sealed -> one owned fence created
ORIGINAL RenderSystem.flipFrame(...) exactly once and unconditionally
AFTER: present-return proof -> bounded zero-poll -> D1 synchronous physical close on signal
```

There is no `@Redirect`, cancellation, extra present, direct `RenderSystem.flipFrame` call, raw
OpenGL, `executePendingTasks`, buffer timeout close, or `GpuDevice.close`. Ordinary running frames
perform no shutdown work. If the original present throws, the AFTER callback is absent and
`CLIENT_STOPPING` later records a missing-final-present failure instead of closing resources.

### CLIENT_STOPPING fallback

The existing listener ordering is retained. Its boundary catches every `Throwable` and returns so
vanilla teardown continues. The coordinator call is idempotent: a completed `DRAINED` state is
unchanged; otherwise it freezes admission, invalidates any owned attempt, closes only the owned
fence object best-effort, retains one immutable failure diagnostic, logs it once, and never claims
that `CLIENT_STOPPING` drained the global FIFO.

## Consequences

- T1c establishes a real, version-pinned final-present/fence infrastructure and truthful failure
  states without activating live resources.
- Normal reload retirement continues to use the existing render-thread handoff plus
  `queueFencedTask`; its behavior is not replaced by the shutdown path.
- A successful fake final batch can reach `DRAINED` only after the original present returned and
  the owned fence signaled. Every failure remains observably non-closed.
- An in-flight publication at cutoff is a typed non-closed blocker; late handoffs cannot create a
  batch-external FIFO entry or leave an active close attempt behind.
- Mixin compatibility is now a real client-only surface and must be verified against exact target
  renderer mods before live-resource activation.

## Waiting

The R2 bounded PASS and formal fast-forward do not promote X7 completion, X8, any P0--P8 Gate, or
unexecuted client/server/runtime/visual/Iris/Sodium/hardware/JFR/JAR/performance results. Production
buffer allocation/upload and completed-set creation/attachment; canonical
source-to-generation/model/geometry/material/LOD mapping; target/pipeline; actual
`CommandEncoder`/`RenderPass`; buffer binding/draw; last-use/pass-completion receipt; shutdown with
real live resources; repeated reload; real client/server runs; window/programmatic/exception quit
paths; visual acceptance; exact Iris/Sodium and combined compatibility; hardware/JFR; benchmark
eligibility; operational bound acceptance; every performance claim; X7 completion; and X8 unlock
remain **WAITING**.
