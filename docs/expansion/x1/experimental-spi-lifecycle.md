# X1 Experimental SPI, Capability Protocol, and Lifecycle

Status: R11 makes the controlled Experimental host contract explicitly `1.1.0` and awaits a fresh r11 independent review. The earlier X1 r10 isolated review remains historical evidence only; this change does not accept a runtime adapter, alter a stable v1 descriptor or wire capability, or remove the Experimental fourth-axis boundary.

## Explicit boundary and protocol version

All controlled provider types are in `com.liy.blendlib.spi.experimental` and carry `@ExperimentalBlendLibSpi(protocol = "1.1.0")`. The protocol has three bounded version components and requests use a strict half-open `CapabilityVersionRange [minInclusive, maxExclusive)`. Capability and provider identities are canonical existing `BlendResourceId` values. `1.0.0` remains the historical initial capability data-plane version; `1.1.0` is the current host contract, whose bounded compatible line is `[1.1.0, 1.2.0)`.

This protocol is separate from:

1. BlendLib API SemVer;
2. asset schema/Profile version;
3. Minecraft adapter target/JAR version.

It does not turn current v1 descriptor `extensions`, `extensions_used`, `extensions_required`, or semantic animation payloads into a capability envelope. Existing v1 required descriptor extensions still use their current rejection behavior.

`CapabilityRegistry` deliberately remains a generic request/offer data plane and can still represent historical `1.0.0` metadata. Version negotiation does not select a historical throwable policy: every current host callback boundary treats every `Error` as terminal and restores the exact same object after required terminal cleanup, while ordinary non-`Error` failures are contained as bounded diagnostics. A current X6 generation is a narrower adapter policy: it intersects every caller-supplied request with `[1.1.0, 1.2.0)` before discovery and rejects any frozen selected offer outside that range. Thus a broad caller range cannot silently select a `1.0.0` offer into a current X6 lifecycle.

## Negotiation model

`CapabilityRegistry` is synchronized and has one irreversible state machine:

```text
REGISTERING --discover--> DISCOVERED --freeze(generation)--> FROZEN
```

Provider metadata (`providerId`, immutable copied offers) is read only at registration. After safely capturing and validating `providerId`, registration reserves that canonical ID and the exact provider object for its monotonic operation epoch before it invokes `offers()` or acquires an iterator. A registered or already-reserved ID is CAP-001 immediately; its contender's offer callback and iterator do not run. Different IDs may collect metadata concurrently, while discovery/freeze remains unavailable until every registration operation has either committed or rolled back. Offer collection acquisition and traversal are each normalized to bounded `BLENDLIB-X1-CAP-013` diagnostics, including caller-supplied capability exceptions; attacker codes/messages are not retained. Each collection is traversed exactly once with a fixed limit of 1,024 offers. Only a fully copied, validated immutable offer list atomically replaces the reservation. Ordinary, fatal, and traversal failures remove only their exact reservation epoch, so retries are safe and cannot delete a newer reservation or publish a partial provider. Cleanup reads the currently stored reservation and compares its primitive epoch before removing the key; it does not rely on boxed-value identity, including beyond the `Long` cache boundary. Discovery first reserves a registry operation epoch, then traverses the caller request collection exactly once outside the registry monitor, with a fixed limit of 1,024 requests, before an epoch-checked commit. Null, duplicate, oversized, or failed request traversal reports bounded `BLENDLIB-X1-CAP-017`; nested or racing mutation reports `BLENDLIB-X1-CAP-008`. Registry reads do not wait for caller iteration. Discovery runs only over the registered metadata snapshot and never invokes a provider lifecycle callback. Registration after discovery is rejected; registration, discovery, and another freeze after FROZEN report `BLENDLIB-X1-CAP-007`.

Each offer declares provider id, capability id, exact protocol version, and bounded priority. For each request:

1. offers are filtered by canonical capability id and bounded range;
2. reporting/candidate order is priority descending then provider-id ordinal ascending;
3. duplicate provider identities and duplicate capability claims inside one provider are explicit registration errors;
4. two or more different compatible claims at the highest priority produce a conflict, never a registration-order selection;
5. a required unknown/range mismatch/top tie yields a failed immutable selection and makes the plan unpublishable;
6. an optional unknown/mismatch/top tie may use only its predeclared `CapabilityFallback`, with an observable warning diagnostic and no selected provider; absent fallback is forbidden at request construction.

`CapabilityPlan` is immutable, generation-pinned, and exposes structured `CapabilityDiagnostic` codes. `CapabilityPlan` and `CapabilitySelection` have no public constructor. A valid plan is bound to the exact `CapabilityRegistry` object, that registry's private one-shot freeze event, the exact plan object stored by that registry, and an independently retained private registry freeze generation. The package-level construction helper cannot mint a lifecycle-valid plan without the private registry event and exact registry state-machine publication. The plan retains its original ordered requests and the complete exact provider-object/offer snapshot. Every lifecycle entry recomputes and cross-checks provenance, the plan generation against that independent registry record, request requirement, capability id, version range, outcome, offer, diagnostic, deterministic order, selected binding, and provider object identity. Any forged or inconsistent shape fails before ownership or callbacks with bounded `BLENDLIB-X1-CAP-016`. `requirePublishable()` raises the first valid error diagnostic, making a required failure fail closed before a generation is published.

All provider, adapter, capability, fallback, request, offer, receipt-read, and diagnostic identity fields crossing this protocol use one bounded identity policy: canonical ASCII `BlendResourceId`, maximum 256 UTF-16 code units. Provider registration rejects an invalid identity before reading its offer collection. Invalid identities are omitted from failure-diagnostic identity fields rather than retaining attacker-sized input. Diagnostic messages remain at most 512 code units and replace control/unpaired-surrogate content; fallback explanations reject control/unpaired-surrogate content. Exception normalization retains only a safely obtained bounded class simple name and never invokes untrusted `getMessage()` or `toString()`.

## Provider contracts

`BlendProvider` is the common identity, immutable offer metadata, and lifecycle contract. The named opt-in provider interfaces are:

- `AssetProfileProvider` for semantic asset-profile support metadata;
- `RenderBackendProvider` for semantic backend capability metadata;
- `HostRendererProvider` for supported `HostKind` metadata;
- `MaterialProvider` for semantic material capability metadata;
- `PlatformAdapter` for typed `HostRegistrationSpec<H>` acceptance and `RegistrationReceipt` output.

None accepts or returns a GLB accessor, parsed descriptor tree, mutable asset, bone matrix, Minecraft identifier, render type/pipeline, raw graphics object, payload, or private/reflection handle.

## Public Experimental inventory and necessity

| Type(s) | Necessary controlled purpose | Why it is not stable facade API |
|---|---|---|
| `ExperimentalBlendLibSpi` | makes deliberate opt-in and current host protocol `1.1.0` visible in source and Javadoc; `1.0.0` remains historical data-plane metadata | provider ABI can evolve independently |
| `CapabilityVersion`, `CapabilityVersionRange` | bounds separately versioned protocol compatibility | ordinary model registration never negotiates providers |
| `CapabilityRequirement`, `CapabilityFallback`, `CapabilityRequest` | declares required/optional semantics and an explicit safe optional fallback | descriptor v1 payload remains unavailable for this purpose |
| `CapabilityOffer` | immutable provider-id/capability/version/priority claim | an ordinary consumer should not choose a backend |
| `CapabilityErrorCode`, `CapabilityDiagnostic`, `CapabilityNegotiationException` | structured control-plane failure behavior | stable registration errors have their own `REG` family |
| `CapabilitySelectionOutcome`, `CapabilitySelection`, `CapabilityPlan`, `CapabilityRegistry` | deterministic discovery and an immutable generation-pinned plan | no hot path should invoke this control plane |
| `BlendProvider`, `AssetProfileProvider`, `RenderBackendProvider`, `HostRendererProvider`, `MaterialProvider` | common provider contract plus four specialized metadata contracts share identity/offers/lifecycle without renderer leakage | these are platform/provider-author integration points |
| `ProviderLifecycleStage`, `ProviderLifecycleContext`, `ProviderLifecycleState`, `ProviderLifecycleResult`, `ProviderLease`, `ProviderLifecycleSession` | enforces generation-scoped prepare/apply/publish/retire and shared close-once ownership after pin drain | snapshots, ownership bookkeeping, and runtime internals remain outside the public SPI data surface |
| `PlatformAdapter`, `PlatformAdapterControl` | intentional bridge from complete stable spec to a version-specific platform bootstrap | only platform authors install an adapter; public `BlendLib` signatures remain SPI-free |

There are no other X1 Experimental public types. New provider convenience APIs must justify themselves with a protocol/compatibility ADR rather than being added casually.

## Generation lifecycle and failure matrix

`ProviderLifecycleSession` owns only an already frozen, publishable plan and the exact provider object instances bound by its registry snapshot. It never discovers, re-queries provider metadata, or switches providers. Later `offers()` mutation cannot alter the frozen generation, and a same-id substitute object is rejected. Its scope is an explicit generation number; `ProviderLease` pins a published generation until a snapshot consumer closes the lease.

| Stage | Allowed work | Failure behavior |
|---|---|---|
| register | snapshot provider identity/offers | duplicate/invalid identity is rejected before discovery |
| discover | bounded metadata-only deterministic comparison | request traversal is outside the monitor; invalid input is CAP-017; no provider callback, world, parsing, or ordering winner |
| freeze | immutable capability selections | required conflict/mismatch/unknown is unpublishable |
| prepare | bounded background CPU/resource preparation | containable provider failure marks only this session FAILED with CAP-009; no publish |
| apply | adapter-thread binding | containable provider failure marks only this session FAILED with CAP-010; no publish |
| publish | make fully prepared plan pin-capable | only `APPLIED` sessions may publish |
| retire | stop new binds and wait for leases | both provider retire callbacks and ownership release wait for every pin; retire then close failures remain cumulative |
| close | release this session's identity owner; the final shared owner makes one terminal close attempt | individual containable close failure is isolated as CAP-011, retained with retire failures, and never retried |

Provider object identities use package-private shared ownership across overlapping generations and `PlatformAdapterControl`. Active handles keep the provider strongly reachable; weak identity keys avoid equating distinct providers whose `equals()` methods agree. Acquire/release is synchronized, the final release marks the object terminal before invoking `close()`, duplicate release is harmless, a close failure remains terminal, and a closed object cannot be acquired again (`CAP-015`). A generation's `retire(context)` runs exactly once only after that generation's last lease drains, immediately before its ownership release, remains generation-scoped, and must not disrupt another published generation sharing the object. Once retire/close callbacks begin, overlapping `retire()` or `close()` calls wait outside the session monitor for the same bounded completion. The final immutable result (or original fatal failure) is published to that completion before the active transition is cleared, and that completion stays attached after terminal state: initiator, waiter, and post-completion observers receive the same result object or rethrow the same original fatal instead of constructing a new terminal result. Timeout or interruption is an explicit `CAP-008` failure, and interruption restores the thread flag. Direct provider callback reentry is rejected before it can await its own completion.

Every external metadata, request traversal, lifecycle, adapter-registration, host-identity, and terminal-close callback executes outside the owning registry/session/control monitor. Before invocation, the owner records an operation kind and monotonic epoch; after invocation it revalidates epoch, state, plan provenance, and adapter/provider identity before committing. Registry registration uses per-ID and per-object reservations rather than a global callback lock: a same-ID contender is rejected without executing offers, while different IDs may execute metadata callbacks concurrently. Duplicate host detection uses an operation-first snapshot and lock-free linear equality checks rather than a caller-controlled hash-map key; it then commits only after epoch/adapter revalidation. A thread-local callback marker rejects direct nested control-plane mutation, while the relevant active operation rejects unsafe racing mutation. Nested prepare/apply/publish/retire/close, provider-id/offer/request traversal, host equality, adapter register, install, or uninstall therefore yields structured `CAP-008`, `CAP-013`, `CAP-017`, or `REG-005` behavior instead of overwriting outer state. A callback may wait for another thread without that thread deadlocking on the owner monitor. Retire remains idempotent and close/ownership handles remain exact-once.

Boundary code catches ordinary non-`Error` `Throwable` values, emits only a fixed diagnostic plus safely obtained bounded type name, and clears the active transition in `finally`. Every `Error` is terminal rather than ordinary success/failure control flow: the session/control first stops new operations, detaches adapter/registration state where applicable, releases every acquired ownership handle exactly once, records terminal lifecycle state, and then rethrows the selected exact original object. After terminal prepare/apply, the session stays `RETIRING` with a terminal completion active while ownership release and provider close finish; only the fully accumulated immutable diagnostics and `CLOSED` state are published, and then the transition is cleared. Concurrent retire/close observers wait on that completion and rethrow the identical primary `Error`, while a secondary close failure is retained as `CAP-011` without replacing it. Adapter-control rollback uses the same exact-once rule but makes dual-failure retention explicit: an original terminal primary keeps identity and suppresses secondary cleanup; terminal cleanup outranks an ordinary primary and suppresses it; two containable install failures become one bounded stable diagnostic naming both safe types. The rollback close callback runs before `endOperation`, so it cannot reattach an adapter early. Direct callback reentry remains `CAP-008`/`REG-005`, and interrupted or timed-out observers cannot publish early completion.

Submit, animation advance, sampling, and socket query are specifically outside this state machine. They may only consume a frozen plan/generation and must not perform provider discovery, re-selection, parsing, I/O, or fallback switching.

## Compatibility and non-goals

- Experimental SPI callers must plan for ABI/protocol changes independently of ordinary stable facade compatibility.
- A historical `1.0.0` offer may remain inspectable by the generic registry, but it must opt in to the current compatible line before it can participate in a current X6 generation; no caller range reinstates an old `Error` containment rule.
- The selection plan is deliberately generation scoped, not world/session scoped. World/session identity remains owned by the platform/runtime instance layer.
- A fallback declaration is metadata; an adapter must still prove semantic equivalence and apply existing hard limits before it publishes.
- X1 does not add a new profile, descriptor extension payload, network capability negotiation, GPU backend, material implementation, or live platform adapter.

## Agent Innovation

None. The lifecycle session and pin lease are the minimal pure-Java enforcement surface needed to realize X0's stated provider freeze/generation requirements without exposing renderer internals.
