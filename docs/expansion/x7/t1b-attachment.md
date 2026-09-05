# X7 T1b generic D1 attachment implementation ledger

## Status

Implementation candidate from exact base
`8e0e58db3e1b67fb80adc160bd72d3d177a5fab9`. R1 review failed only its deterministic race-evidence
gate; the M1 remediation and focused result are recorded below, and fresh independent rereview
remains required. This tranche implements only an internal attachment lifecycle primitive. It is
not a GPU allocation, upload, pass, draw, shutdown-drain, runtime, visual, compatibility, hardware,
or performance result.

## Complete-set and ownership boundary

- `CompletedGenerationResourceSet` is package-private, non-empty, and bound to one exact
  `ModelRegistryGeneration` object identity. Its physical close action is fixed at construction.
- A set is created only in caller-owned complete state. It is not a mutable bag and cannot receive
  later partial resources.
- `ClientModelRegistry` adds only a package-private forwarder. Its existing public no-argument
  constructor remains CPU-only and rejects attachment because it has no render owner. No public
  constructor or method exposes the set, callback, `Runnable`, executor, GPU resource, or device.
- D1 asserts its trusted render owner before admission. Under D1's existing monitor it requires the
  same current generation object, its exact lifecycle record, `PUBLISHED`, non-shutdown,
  non-frozen, and an empty single attachment slot.
- The irreversible caller-to-D1 move and slot write occur under that same monitor. Rejection never
  closes caller resources, changes a D1 lease, or queues a fence. A caller cleanup versus D1 move
  has one winner through the set's own ownership monitor.

## Single close and retry truth

Retire, supersede, and registry close freeze the attachment slot under the D1 monitor. The attached
set becomes the first normal callback in D1's existing fenced close attempt, before claim-time
callbacks. It synchronously performs physical close or throws on the already-fenced render thread;
it cannot enqueue a second fence/counter/close.

A successful callback is never replayed. Every thrown `Throwable`, including a sneaky checked
failure, exits the set's closing state and retains its generation/action as `D1_CLOSE_FAILED` for
the existing explicit D1 retry. Runtime exceptions and errors preserve identity; checked failures
are wrapped with their cause. Caller-owned failure is likewise retained as caller-owned failed
state and cannot later transfer to D1. References clear only after successful physical close and
terminal D1 cleanup.

There is no production creator or attachment caller in this tranche. Tests use non-empty generic
fake close actions. `PendingGenerationTransaction`, reload listener, entrypoint, pass host, trusted
owner, X4/X6, and every `render.x7gpu` class remain unchanged.

## Initial focused verification (R1 history)

The only authorized command for this candidate is:

```powershell
.\gradlew.bat :blendlib-fabric-client:test `
  --tests 'com.liy.blendlib.fabric.client.reload.ClientGenerationResourceAttachmentTest' `
  --tests 'com.liy.blendlib.fabric.client.reload.ClientGenerationResourceOwnerTest' `
  --tests 'com.liy.blendlib.fabric.client.reload.ClientModelRegistryRenderOwnerBootstrapTest' `
  --tests 'com.liy.blendlib.fabric.client.api.ClientGenerationLeaseContractTest' `
  --tests 'com.liy.blendlib.fabric.client.render.x7gpu.X7GpuApiBoundaryTest' `
  --tests 'com.liy.blendlib.fabric.client.render.x7gpu.boundary.X7GpuAdapterSurfaceBoundaryTest' `
  --offline --no-daemon --max-workers=1 --console=plain
```

Result: process exit `0`; `BUILD SUCCESSFUL in 18s`; 13 actionable tasks (9 executed, 4 from
cache). Fresh XML is 6 suites / 50 tests / 0 failures / 0 errors / 0 skipped, including 10 tests in
the new attachment suite. The compile output contains only inherited deprecation/removal/
unchecked/serial warnings. No retry, module/root build, client/server, benchmark, or network run was
performed.

## R1 review failure and M1 remediation

The fresh R1 review retained verdict **FAIL (0C/0H/1M/0L)**. Its report is
`D:\BlendLib-review-artifacts\x7-t1b-attachment-review-20260830-100337499\FINAL_REVIEW.md`
(SHA-256 `A309205D85F80C29E52A950979A6014437C31BC1E16B4C8CA78C909E4CDE09C8`), with
self-excluded manifest SHA-256
`798D1184D849F48F95F2BF08E6E923500927FDBDF6B0EEF42A672D9FC0593A5D`. M1 found that the original
attach-versus-supersede test released both competitors together and accepted either result, so one
passing run did not prove both required interleavings. The FAIL remains immutable history.

The repair adds only a package-private `AttachmentAdmissionBarrier` and package-private overload.
Normal production admission delegates through a NOOP barrier. The test barrier runs after exact-
generation capture and close-callback preallocation, but before D1 monitor acquisition; it changes
no admission check, ownership move, slot write, fence, or close semantics. Two deterministic tests
combine that barrier with the existing transaction claim hook:

1. publication waits at its claim hook while attachment crosses its admission barrier first;
   attachment returns `ATTACHED`, then supersede queues exactly one fence and closes once;
2. attachment waits at its admission barrier while publication completes retirement/terminal
   cleanup first; attachment then rejects, caller ownership remains intact, caller closes once, and
   no attachment fence exists.

Exactly one remediation command ran:

```powershell
.\gradlew.bat :blendlib-fabric-client:test `
  --tests 'com.liy.blendlib.fabric.client.reload.ClientGenerationResourceAttachmentTest' `
  --offline --no-daemon --max-workers=1 --console=plain
```

Result: process exit `0`; `BUILD SUCCESSFUL in 13s`; 13 actionable tasks (3 executed, 10
up-to-date). Fresh XML is 1 suite / 11 tests / 0 failures / 0 errors / 0 skipped, SHA-256
`2DB06F260557669AED8A14BFCA7E884181D8B796BDEF51D5923377EB35190455`. The other five original
focused suites were not rerun. No module/root build, client/server, benchmark, or network run was
performed. This is remediation evidence awaiting fresh independent rereview, not a PASS verdict.

## Waiting

Real `GpuBuffer` or any other physical resource; allocation/upload; canonical source plan to exact
generation/model/geometry/material/LOD mapping; a production completed-set creator/attachment
caller; target/pipeline; `CommandEncoder`/`RenderPass`; buffer binding/draw; pass-completion receipt;
bounded deterministic shutdown with live resources; repeated reload; real client/server; visual
acceptance; Iris/Sodium; hardware/JFR; benchmark eligibility; and every performance claim remain
**WAITING**.
