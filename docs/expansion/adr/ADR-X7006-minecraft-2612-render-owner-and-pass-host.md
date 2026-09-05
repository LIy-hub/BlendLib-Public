# ADR-X7006: Trusted Minecraft 26.1.2 render-owner spine and Fabric pass host

## Status

Proposed / Experimental X7 T1a host plus T1b generic D1-attachment candidate. T1a focused evidence
is recorded in `docs/expansion/x7/t1a-pass-owner.md`; its independent review returned **PASS
(0C/0H/0M/0L)** and the exact candidate is formally fast-forwarded. T1b focused verification is
recorded in `docs/expansion/x7/t1b-attachment.md` and still requires fresh independent review.
Neither result is a RenderPass, draw, real GPU-resource, runtime, visual, compatibility, hardware,
or performance acceptance, and X7 remains incomplete.

## Context

D1 already owns the sole active registry-generation reference, lease counter, retirement decision,
fence request, synchronous close callbacks, failure retention, and retry. Its public
`ClientModelRegistry()` compatibility path intentionally has no render owner. Production previously
used that path as well, so a future complete resource set could not legally hand close work from an
arbitrary final-release thread to Minecraft's render thread.

Minecraft/Fabric 26.1.2 also exposes two public, synchronous world-render phases that occur after the
immediately preceding vanilla batch/pass has returned:

- `LevelRenderEvents.AFTER_SOLID_FEATURES` for a future opaque/cutout owner;
- `LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN` for a future translucent owner.

The current CPU collector route has no prepared X7 draw and does not expose a live vanilla
`RenderPass`. A T1a host must therefore establish the real callback scopes without manufacturing a
pass, target, completion receipt, allocation, or draw.

## Decision

### Trusted registry bootstrap

The public no-argument `ClientModelRegistry()` constructor remains CPU-only and ownerless. Exactly
one additive public bootstrap is permitted:

```java
public static ClientModelRegistry createMinecraft2612Client()
```

It has no arguments and returns only `ClientModelRegistry`. It privately installs
`Minecraft2612GenerationRenderOwner`; no public constructor or method accepts an executor,
`Runnable`, `RenderOwnerCallbacks`, `GpuDevice`, `GpuBuffer`, `RenderPass`, or `RenderSystem`.

The production owner is fixed to the exact 26.1.2 calls:

```text
Minecraft.getInstance()::execute
RenderSystem::assertOnRenderThread
RenderSystem::queueFencedTask
```

`Minecraft.execute` may run a handoff inline or later. D1 therefore performs the render-thread
assertion inside the handed-off task, never at admission or on the releasing caller. It records
`FENCE_QUEUED` only after the fenced callback is accepted. A fence callback that has not run leaves
the generation observably non-`CLOSED`.

### D1 remains the single physical-close truth

T1a attaches no resource set and registers no empty or dormant close callback. It does not wire
`X7GpuGeneration.retire`, `X7GpuCloseScheduler`, or any X7 hold kind into D1. X4 snapshots and X6
plans retain their existing D1 parents and do not acquire duplicate B1 counters.

T1b adds only a package-private generic attachment primitive. A non-empty set exists only after all
of its future physical components are complete and carries the exact `ModelRegistryGeneration`
object identity. Admission asserts the installed render owner, then linearizes exact-current,
`PUBLISHED`, non-shutdown, single-slot admission together with retire, supersede, and registry close
under the existing D1 monitor. The irreversible caller-to-D1 ownership move occurs inside that
monitor; every rejection leaves caller ownership unchanged, and a caller/D1 close race has exactly
one winner.

D1's fenced callback contract remains strict: on the already-fenced render thread, the accepted set
must synchronously perform its complete physical close or throw. It never queues a second
fence/counter/close. Successful callbacks are not replayed; any thrown `Throwable` moves the set out
of `D1_CLOSING`, preserves its generation and close action, and leaves the record in D1
`CLOSE_FAILED` for the existing explicit retry. Only successful terminal close clears the retained
set/generation/action references. T1b supplies fake close actions in tests only; no production
creator currently calls the attachment seam.

### Package-private Fabric pass host

`X7Minecraft2612PassOwnerHost` is package-private and is installed exactly once by the production
client entrypoint. Its duplicate-init guard is inside the host. Tests inject a package-private
registrar and never register against Fabric's un-unregisterable global events.

Each real event callback has the exact synchronous scope:

```text
Fabric callback entry
  -> require non-null LevelRenderContext token
  -> RenderSystem.assertOnRenderThread()
  -> invoke one phase-only package-private scope
  -> scope returns or throws
  -> Fabric callback returns or propagates the throw
```

The phase-only scope receives no `LevelRenderContext`, target, pose stack, buffer source, device,
encoder, or pass to retain. T1a installs an empty scope because there is no prepared X7 draw. No
Mixin is added and no active vanilla pass is fabricated or nested.

### Client stop

The production entrypoint keeps `CLIENT_STOPPING -> ClientModelRegistry.close()` ordering before
client-service initialization. This asks D1 to reject new leases and retire known generations; it
does not drain `RenderSystem.queueFencedTask`. Once shutdown begins, a later
`RenderSystem.executePendingTasks()` poll is not guaranteed.

T1a/T1b therefore keep GPU allocation and real-resource attachment disabled. Shutdown with live X7
resources remains **WAITING** for a separate bounded, deterministic final-fence policy. An accepted
fake attachment whose fence has not run remains observably `FENCE_QUEUED`/D1-owned; it is not closed
early. A future timeout/failure must retain a terminal diagnostic and avoid closing buffers before a
signaled fence; it must never close the game-owned device.

## Consequences

- Production now has a trusted render-thread/fence callback spine and two real Fabric event scopes.
- D1 now has one internal, single-slot, exact-generation ownership-transfer primitive for an
  already-complete non-empty set. There is no production attachment caller and no real resource.
- Public `publish(...)`, CPU generation construction, `ModelRenderSnapshot`, `ClientModelLookup`,
  `ClientGenerationLease`, `ClientGenerationLeaseBinding`, X4 descriptors, X6 historical
  descriptors, and the selected CPU backend are unchanged.
- The host owns only synchronous callback timing. It owns no target, pipeline, pass, buffer, draw,
  completion receipt, resource set, or performance conclusion.
- The existing adapter-private B1 state machine remains isolated structure and is not a second D1
  lifecycle truth.

## Waiting

Actual buffer allocation/upload and construction of a real completed set; canonical source-to-
generation/model/geometry/material/LOD mapping; production attachment wiring; target and pipeline
selection; `CommandEncoder`/`RenderPass` creation; buffer binding and draw; pass-completion receipt;
shutdown with live resources; repeated reload; real client/server; visual acceptance; Iris/Sodium;
hardware/JFR; benchmark eligibility; and every performance claim remain **WAITING**.
