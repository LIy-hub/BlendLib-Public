# ADR-X1001: Stable facade uses typed immutable semantic registration specifications

Status: Proposed/Experimental implementation candidate; X1 r10 final independent review is PASS, while the original X1/X5/X9 integration r1 is FAIL and its remediation awaits a fresh integration review. The r8 provider-control-plane repairs and r9 registry-reservation cleanup do not alter this stable-facade decision.

## Context

X0 requires a small ordinary-consumer facade without leaking assets, GLB internals, poses, matrices, platform identifiers, or renderer objects. It also requires a diagnostic outcome when no platform adapter exists and deterministic duplicate handling.

## Decision

X1 implements `BlendLib.entity`, `BlendLib.blockEntity`, and `BlendLib.item` as generic stable builders. They produce `HostRegistrationSpec<H>` and accept only existing `BlendModelKey` plus typed `AnimationRequestSource`. `ModelInstance`, `AnimationRequest`, and `SocketQuery` are immutable semantic values using existing Blend* key types.

Registration is explicit fail-closed: missing model/source, absent adapter, duplicate target, normalized adapter failure, invalid receipt, and unsupported item playback emit structured X1 registration diagnostics. Equal `(HostKind, host)` targets are rejected rather than being silently overwritten or selected by registration order. Items are stateless in X1 and accept `LOOP` only; their public specification stores a validated dynamic-source wrapper, so the generated `animationSource()` accessor cannot expose the raw source and bypass this rule. Entity and block-entity sources still allow `ONCE`/`HOLD`. One-shot/hold item state awaits a separately designed persistent ItemStack identity.

Platform adapter install/register/uninstall uses a two-phase operation epoch. External provider-id, adapter-registration, host-identity, item-source, and final-close callbacks run without holding the control monitor; nested or racing mutation is rejected before it can replace adapter state, publish a stale receipt, or leak an ownership handle. Duplicate host detection snapshots accepted registrations after claiming the operation, compares equality outside the monitor without caller `hashCode()`, and commits only after epoch/adapter revalidation. A failed install consumes rollback close failure before ending its operation and uses a documented dual-failure order: original fatal primary first, otherwise fatal cleanup, otherwise stable diagnostic. The selected fatal suppresses the other failure; two containable failures retain both safe type names in `REG-005`. Adapter failures never call untrusted exception messages or `toString()`, and containable external throwables are not exposed through the stable public cause chain. The existing public cause-taking constructor is retained for source/binary compatibility with local caller failures. Adapter and receipt identities must satisfy the X1 canonical 256-code-unit identity boundary, and stable diagnostic text replaces controls and unpaired surrogates.

Internally, completed registration delegates to the controlled `PlatformAdapterControl`, but no public `BlendLib` method takes or returns an experimental SPI type. An ordinary consumer imports only `com.liy.blendlib.api`; platform bootstrap deliberately opts into the experimental package. The checked sample is self-contained, and a real Java 25 compiler invocation compiles that source alone against only the built API runtime JAR with implicit source discovery disabled. Any future SPI protocol evolution must preserve the stable facade's signatures and its `BLENDLIB-X1-REG-001` through `-007` failure semantics, or require a separate stable-API compatibility decision.

## Alternatives rejected

- A raw `Object`/map facade: loses source-safe host typing and exposes opaque mutable state.
- New ModelKey aliases: duplicates existing canonical `BlendModelKey` semantics.
- A facade accepting parsed asset, GLB, pose, matrix, platform identifier, render type, payload, or renderer object: violates stable API/resource-instance-snapshot boundaries.
- A no-adapter no-op: makes a consumer believe a registration rendered when none can exist.

## Compatibility impact

This is additive API only. It does not change existing keys, descriptor schema, Profile, payload, adapter artifact target, or P0--P8 Gate. A real platform adapter remains a separate integration task.
