# X7 T6b trusted capture plumbing

This client-only plumbing retains an X7 P7 evidence package without issuing a performance Gate.
It is deliberately incomplete until the separately owned T3, T4, T5, generated-source-identity,
and public Minecraft screenshot-completion integrations are handed off.

## Ownership and lifecycle

`P7BenchmarkCaptureController` is the only live P7 owner. Its fixed state path is:

`DISABLED -> PREFLIGHT -> WARMUP -> SAMPLE -> SEALING -> WRITING -> COMPLETE`

Any nonterminal state may become `INVALID`. The controller acquires one opaque
`ClientRenderMeasurementSession`, bound to its render thread and monotonic epoch. The exclusive
measurement owner mints one opaque, one-shot capability into its own active-owner ledger; only the
controller can transfer that capability to the internal authority, and revocation removes the
same ledger entry. Legacy `beginCapture`, `completeFrame`, and `endCapture` calls cannot take over
an exclusive session; they invalidate it and the actual holder closes it exactly once.

The controller fixes a random capture id, owner/session epochs, render-thread identity, source,
environment/backend identity, generation, and strict completed-frame sequence. It accepts exactly
600 warm-up and 1,800 sample frames, each with exactly 100 rigid and 25 skinned submissions.
Frame records are immutable and bounded. Render callbacks do not parse JFR, hash, encode JSON/PNG,
or write files.

The package-private live integration port currently returns no T3/T4/T5 completed receipts and no
Minecraft screenshot callback. `GeneratedP7BuildIdentity` is likewise an unavailable sentinel
until the integration owner supplies an exact generated commit/tree/clean identity for this build.
That is intentional: P7 invalidates before sample capture rather than treating a requested policy,
candidate backend, test fixture, system property, or runtime override as proof. Those unavailable
seams can never authorize a live token.

## Retained package

The bounded writer accepts only its immutable payload, stopped recording, screenshot completion,
isolated output root, and in-memory owner lease. It writes one fresh same-volume staging directory:

`<isolated-output>/x7/<capture-id>.partial-<nonce>/`

Then, in order, it retains and hashes exactly these payload roles:

1. `p7-report.json`
2. `raw-p7-samples.txt`
3. `allocation.jfr`
4. `capture.log`
5. `environment.txt`
6. `capability.txt`
7. `scene.png`
8. `owner-receipt.json`
9. `artifact-manifest.json`

The receipt is written only after the seven preceding hashes exist; the manifest is written last
and excludes itself. The writer uses `CREATE_NEW` and no-follow opens for files, bound byte caps,
full-write loops, immediate creation-ledger entries, JFR `FLR`/in-sample allocation-event
validation, PNG signature plus exact 1920x1080 decode validation, raw-to-report percentile
recomputation, artifact verification, structural validation, force/hash boundary rechecks,
root/parent/staging identity revalidation, and a same-volume `ATOMIC_MOVE` without replacement.

Failure cleanup operates only over the exact in-memory creation ledger in reverse order. If safe
cleanup cannot finish, it attempts an atomic rename of the still-proven owned staging directory to
a unique `.failed-<nonce>` sibling; if its identity cannot be proven, it retains it without any
pathname cleanup. It never glob-cleans any run, world, source, cache, or arbitrary user directory.
Reparse/symlink aliases are rejected, and observable hard-link aliases are rejected before a
writer operation. A provider without a public hard-link count is not sufficient to enable trusted
issuance.

`capability.txt` format `x7_capability_report_v3` freezes the completed producer route, active
fallback reason, and verification bit at both capture boundaries alongside the full named
T3/T4/T5 start/end/delta inventory. Any unknown, duplicate, omitted, reordered, or contradictory
field is structurally invalid; a well-formed offline package still remains only WAITING.

## Trust boundary

Offline parsing and ordinary retained packages remain only
`STRUCTURALLY_VALID_WAITING`; the existing offline comparator remains non-comparable. Retained
JSON, JFR, PNG, hash, receipt, reflection-created object, or serialized object cannot recreate a
`TrustedCapture` because it is looked up by identity in the live authority's consumed lease ledger.

The runtime comparator accepts only two compatible authority-issued tokens once each and reports
`MEASURED_ONLY_GATE_WAITING`; it has no PASS, eligible, improved, or regressed conclusion.
Production authority requires the service-minted capability, exact generated clean source identity,
exact owner/session/frame binding, real finalized artifacts, atomic publication, and completed
T3/T5 (plus T4 for GPU skinning) evidence. This candidate lacks the generated identity and real
producer seams, so production trusted issuance is impossible on every provider. The package-private
identity seam used by focused tests is not public API and does not change these production
conditions. The unavoidable public client-source-set writer bridge independently verifies that its
immediate caller is the controller from the same class loader and module before it can transfer a
capability.

## Reviewed client-source-set ABI exception

`blendlib-showcase` and `blendlib-fabric-client` are separately compiled client source sets. The
following public types and members are therefore an explicitly reviewed bridge exception, not a
stable API, SPI, serialization format, or authority issuer:

- `ClientRenderMeasurementService` exposes generic telemetry ownership plus four `Object`-typed
  P7 bridge calls only because the measurement owner and internal authority are in separate client
  source sets. Each bridge call checks the exact controller/authority class, class loader, module,
  active exclusive-session epoch, and render owner before it touches the private ledger.
- `CapturePayload` and its immutable nested receipt records are the controller-to-writer carrier.
  They have no mutable state, output path, source override, capability, token constructor, or
  deserialization route. A payload cannot mint or reactivate authority.
- `P7EvidencePackageWriter`, its opaque lease carrier, and structural `WriteResult` are the
  controller-to-writer bridge. `LiveCaptureLease` has no public constructor; `WriteResult` exposes
  no pending or active trusted token; production entrypoints refuse before staging when the
  owner-held publisher is absent.

The exact public method/constructor/nested-carrier set is locked by
`P7CaptureAbiSurfaceTest`, while `ClientRenderMeasurementServiceTest` retains direct-caller and
ownership rejection coverage. The exception does not add or change public API/core/common/server/
network signatures. Any integration that can collapse these two client source sets must remove or
narrow this exception before exposing another bridge.

## Integration still required

The integration owner must wire the existing client entrypoint lifecycle hooks, generated build
commit/tree/clean resource, isolated P7 task plumbing, public screenshot completion callback, and
the immutable T3/T4/T5 completed-frame receipts. Until those handoffs are real and independently
verified, T6b has no GPU, hardware-performance, visual, or Gate PASS claim.
