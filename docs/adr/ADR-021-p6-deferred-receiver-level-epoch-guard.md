# ADR-021: P6 Deferred Client Receiver Level-Epoch Guard

Status: Accepted
Proposed: 2026-07-30
Accepted: 2026-08-01
Decision authority: local project owner

## Context

The accepted v1 design fixes the semantic identity boundary: entity state is
scoped to a connection session, block-entity state is scoped to a dimension,
and state must not cross a dimension transition. It also permits a bounded
unknown-target queue only for targets in the current connection/dimension.

The current client receiver queues every payload onto the client executor, but
reads `context.client().level` only when that queued callback eventually runs.
If an A-level payload is queued, the client changes to B, and then the callback
runs, the receiver supplies B to `ClientAnimationSyncRuntime`. The runtime can
therefore begin a B epoch and enqueue the old A target as an unknown B target.
That is incompatible with the accepted no-cross-dimension/session semantic
boundary, even though the v1 packet intentionally carries no dimension or
session field.

## Evidence and investigation boundary

- `ClientAnimationPayloadReceivers.java` currently reads
  `context.client().level` inside both deferred executor lambdas.
- `ClientAnimationSyncRuntime.receive(...)` establishes the active epoch from
  the level supplied by that deferred callback before it creates an entity or
  block-entity command / unknown-target entry.
- A test-only A-to-B interleaving harness was compiled and entered JUnit on
  2026-07-30, but its direct `Level.OVERWORLD` setup stopped at Minecraft's
  unbootstrapped registry initialisation before either receiver callback ran.
  The resulting XML is retained under
  `blendlib-fabric-client/build/test-results/test/` and is described in
  `docs/evidence/P6-deferred-receiver-epoch-investigation.md`. It is not a
  behavioral reproduction and must not be reported as one.
- Independent static review against the local fixed 26.1.2/Fabric API found
  the receiver API signatures usable, but rejected that test harness because
  it mutates Fabric global receiver registration, reflects private static
  state, and uses `Unsafe`; it is unsuitable as a stable green regression.

## Decision

Without changing any v1 payload field, public API, diagnostic allocation,
Minecraft/Fabric version, or acceptance threshold, make the client receiver
capture the observed `ClientLevel` at packet receipt. The queued callback must
deliver only if `context.client().level` is still that exact captured level
object. If it is null or a different object, it must drop the old callback
before calling `ClientAnimationSyncRuntime.receive(...)` or touching the
unknown-target queue.

The implementation should isolate this as a client-private, testable
level-epoch delivery seam. Its regression must use deterministic sentinels or
a dedicated client test seam, not Fabric implementation classes, global
receiver mutation, `Unsafe`, a real client, or a server. It must cover entity
and block-entity payloads across A-to-B replacement, disconnect/null, and the
same-level delivery control.

## Consequences and Gate handling

This ADR authorizes only the receive-time `ClientLevel` object-identity guard
and its deterministic client-private regression seam. P6 remains `WAITING`
until the implementation, review, and all original P6 Gate evidence are
complete. This decision does not reinterpret prior P6 smoke, client, sequence,
disconnect, or dimension evidence; it does not authorize a P6 Gate PASS,
commit, release, push, publication, deployment, or operation of a formal
server/world.
