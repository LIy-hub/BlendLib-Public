# X7 T1c final-present owned-fence shutdown implementation ledger

## Status

Isolated implementation candidate from exact base
`3914f89f1203ade13e90c4959801b910657eccdf`, tree
`40f8863b73ccf4ab00b9c7a1b8762cd5448ceb1d`. R1 candidate
`18d4a96daad26147361c79a80cbd0000a5973252` retains its immutable independent **FAIL
(0C/1H/0M/0L)** history. The scoped repair candidate
`b3660bb6007ba89847461f5f0cc3c43f4d0d7478`, tree
`dacf56e6080c50f13cbdbc6cad9e376196c8d9b6`, then received independent R2 **PASS
(0C/0H/0M/0L)** and was formally fast-forwarded into
`D:\BlendLib-agentloop` / `agentloop/blendlib-expansion` at that exact commit and tree.
This bounded static/focused-evidence PASS does not activate production allocation, attachment,
upload, pass, draw, or live-resource shutdown.

R2 artifact:
`D:\BlendLib-review-artifacts\x7-t1c-shutdown-fence-review-r2-20260830-121725081\FINAL_REVIEW.md`
(SHA-256 `FADB6814BD02E499E9DEC5CE367E15EA477F53EC533495B9DE960AFB4FE8438F`), with
self-excluded validated-manifest SHA-256
`15E3639FCCBD86C25399FBCE434713176EB24463213FD7862C2644FC81C0A369`.

The mandatory read-only design input is
`D:\BlendLib-review-artifacts\x7-t1c-shutdown-fence-design-20260830-101523389\REPORT.md`, SHA-256
`6A5520963C160DE6929A3EB9D923D0FDCA6FBB50B5539BF8F63EADF25BED68B8`; its self-excluded manifest
SHA-256 is `7FE7F24DA3D02ADDEF5363B469D44189DA1C330BBDBCE4A8E791A8631F62C8EE`. Its verdict is conditional
GO for this bounded infrastructure and blocked for production live-resource activation.

## Implemented boundary

- `ClientFinalFrameShutdownCoordinator` is package-private. `QUIESCING` closes creator admission
  before any final-path device access and snapshots the exact cutoff count. There is no production
  creator caller.
- D1 atomically freezes publish/attachment/lease admission and either seals one immutable final
  batch with a nonzero attempt ID or retains precise terminal non-closed publication-transaction,
  lease, handoff, or external normal-fence outcomes. Every publication holds a D1 permit from
  before its transaction claim through publication/abort callback transfer. A nonzero cutoff count
  is immutable evidence for typed `OUTSTANDING_TRANSACTIONS`; it cannot seal a partial batch or
  report `DRAINED`. Normal and final registration are mutually exclusive.
- A stale normal handoff now checks attempt authority before queueing, so final-path invalidation
  cannot leave a new no-op fence in the global FIFO. The ordinary reload
  `Minecraft.execute -> RenderSystem.queueFencedTask` route otherwise remains unchanged.
- Successful owned-fence completion asks D1 to run its exact batch synchronously. Successful
  callbacks are not replayed; physical-close failure remains retained and shutdown-terminal retry
  is forbidden. Failure/timeout/duplicate/late completion runs no buffer callback.
- Generic completed-set diagnostics retain exact generation identity, ownership, physical resource
  count, and byte count. Its internal completion contract now accepts either verified D1 fence
  proof; no real resource is constructed.
- `Minecraft2612OwnedFinalFenceAdapter` is package-private and accepts exactly two audited class
  hashes: Mojang/runtime resource-stream bytes
  `AC3890B42C594A1FF7BF3C3EC945C85CCCBD8CB076711D55A20181ED3DD99A7A`, and the design artifact's
  Loom project-processed bytes
  `85AACE61CCED0D3388E3C53F8CED84096031D1B94E5CDE662F00CC90F1E28D7C`. The variants differ only
  in the audited `blockModelResolver`/`missTime` visibility widening and retain the same exact
  `renderFrame(Z)V`/unique-`flipFrame` contract; every other hash fails closed. The adapter owns
  only one `GpuFence`, uses `awaitCompletion(0L)`, and closes only that fence object.
- A client-only required Mixin brackets the unique
  `Minecraft.renderFrame(Z)V -> RenderSystem.flipFrame(TracyFrameCapture)` invoke with BEFORE and
  AFTER `require=1` injections. It is not a redirect, cannot cancel or repeat the original present,
  and performs no ordinary-frame shutdown work.
- `CLIENT_STOPPING` keeps its original registration order, catches every failure, records/logs one
  immutable non-closed fallback snapshot, and returns so vanilla teardown continues. It does not
  call `executePendingTasks`, close a buffer on timeout, close the game device, or claim to drain the
  private global FIFO.
- The trusted no-argument factory remains the only new public registry factory and returns a fresh,
  state-isolated registry on every call. It does not install the global hook. Only the registry
  selected by synchronized `BlendLibClientServices.initialize` through its sealed lookup bootstrap
  installs the one final-present coordinator/hook; repetition with the same installed
  registry/backend and unchanged runtime is idempotent, while a different primary is rejected
  first. Secondary registries retain ordinary close semantics.
  Public `ClientModelRegistry()` remains CPU-only and ownerless; no public callback, executor,
  fence, resource, or device descriptor is added.

## R1 failure and repair

The immutable R1 artifact is
`D:\BlendLib-review-artifacts\x7-t1c-shutdown-fence-review-r1-20260830-113122238\FINAL_REVIEW.md`,
SHA-256 `CD43A7DAB790B07F9F70A9518D463D18EBEFA84744E4C48BB4CCBDF8D5C70056`.
Its High finding proved that `createMinecraft2612Client()` had silently become a process-wide
mutable singleton, coupling publication and close state across callers. The repair restores the
accepted per-call factory semantics and moves unique hook installation behind the existing sealed
production-service capability.

A later pre-rereview concurrency audit found that a publication paused after claim but before the
D1 monitor was absent from the final-batch snapshot. The repair adds the pre-claim D1 permit,
typed cutoff count/failure, post-terminal normal-queue prohibition, and terminal handoff-attempt
consumption. R2 independently reviewed both the factory and cutoff repairs and returned the bounded
PASS recorded above.

## Focused verification

The initial authorized implementation test invocation was:

```powershell
.\gradlew.bat :blendlib-fabric-client:test `
  --tests 'com.liy.blendlib.fabric.client.reload.ClientFinalFrameShutdownCoordinatorTest' `
  --tests 'com.liy.blendlib.fabric.client.reload.ClientGenerationFinalCloseBatchTest' `
  --tests 'com.liy.blendlib.fabric.client.reload.Minecraft2612FinalFenceContractTest' `
  --tests 'com.liy.blendlib.fabric.client.reload.ClientGenerationResourceOwnerTest' `
  --tests 'com.liy.blendlib.fabric.client.reload.ClientGenerationResourceAttachmentTest' `
  --tests 'com.liy.blendlib.fabric.client.reload.ClientModelRegistryRenderOwnerBootstrapTest' `
  --tests 'com.liy.blendlib.fabric.client.BlendLibClientEntrypointLifecycleTest' `
  --tests 'com.liy.blendlib.fabric.FabricMetadataBoundaryTest' `
  --offline --no-daemon --max-workers=1 --console=plain
```

Result history is retained rather than rewriting the first failure as a green aggregate:

- The exact eight-class command above compiled production and tests, then ran 65 tests: 63 passed,
  2 `Minecraft2612FinalFenceContractTest` evidence assertions failed, 0 errors, 0 skipped, process
  exit `1`, `BUILD FAILED in 18s`. The first assertion expected the Mojang/runtime raw hash while
  Gradle intentionally supplied the second accepted Loom-processed pin; the second attempted to
  load Fabric's lifecycle Mixin through the context classloader at a stale entry path. No lifecycle
  behavior test failed.
- The first approved contract-only repair rerun ran 4 tests: 3 passed, 1 failed, 0 errors, 0
  skipped, process exit `1`, `BUILD FAILED in 9s`. Protection-domain JAR reading was correct, but
  the constant still used the stale entry order.
- A read-only JAR check then fixed the exact resolved Fabric lifecycle 4.1.1 entry as
  `net/fabricmc/fabric/mixin/event/lifecycle/client/MinecraftMixin.class`, 3,581 bytes, SHA-256
  `BD720CF3F709F6742C2813EC0FA4B0214884CFA38233403A3E89B7393D39EBE2`. The second approved
  contract-only rerun passed 4/4 with 0 failures, 0 errors, 0 skipped, process exit `0`,
  `BUILD SUCCESSFUL in 9s`.

At the R1 candidate commit, the final retained XML was
`blendlib-fabric-client/build/test-results/test/TEST-com.liy.blendlib.fabric.client.reload.Minecraft2612FinalFenceContractTest.xml`,
1 file / 4 tests / 0 failures / 0 errors / 0 skipped, SHA-256
`68D024315076EE56F26D0D546ED02CAE354D15682911887AE00BA98905050802`. Combined evidence covers
all 65 named tests, but the other 61 passing tests were deliberately not rerun after the contract
evidence-only repair, so there is no claim that the original aggregate invocation was green. No
module/root build, client, server, benchmark, performance, or network command ran.

The R1 High repair plus publication-cutoff repair used exactly one additional focused invocation:

```powershell
.\gradlew.bat :blendlib-fabric-client:test `
  --tests 'com.liy.blendlib.fabric.client.reload.ClientModelRegistryRenderOwnerBootstrapTest' `
  --tests 'com.liy.blendlib.fabric.client.BlendLibClientEntrypointLifecycleTest' `
  --tests 'com.liy.blendlib.fabric.client.reload.Minecraft2612FinalFenceContractTest' `
  --tests 'com.liy.blendlib.fabric.client.reload.ClientGenerationFinalCloseBatchTest' `
  --tests 'com.liy.blendlib.fabric.client.reload.ClientFinalFrameShutdownCoordinatorTest' `
  --offline --no-daemon --max-workers=1 --console=plain
```

It exited `0` with `BUILD SUCCESSFUL in 18s`; 14 tasks were 3 executed / 11 up-to-date.
The five fresh XML suites contain 30 tests / 0 failures / 0 errors / 0 skipped:

- `ClientModelRegistryRenderOwnerBootstrapTest`: 5 tests, SHA-256
  `BF0BE987F142F5ACA80051AA9056ACFB256C33DBF3BE1CA167D67FB88E80352D`;
- `BlendLibClientEntrypointLifecycleTest`: 1 test, SHA-256
  `5A86E27251D30419B0ACEBCC30D0FD5280DB9D8FD4FF634694519D552B38C917`;
- `Minecraft2612FinalFenceContractTest`: 4 tests, SHA-256
  `15D8E5D2BD4E3225F899A34244C9DFA2A3AB024A90C94D856AC3C670ED3E6BC6`;
- `ClientGenerationFinalCloseBatchTest`: 7 tests, SHA-256
  `B4024FF7F922802844C091F7D183C35FCF00AAD6316E5BDCCD3518C83D393A9A`;
- `ClientFinalFrameShutdownCoordinatorTest`: 13 tests, SHA-256
  `EF38A480E9458D3E51F53EE16463907D5DB504372CF096EAB5B219CECF10F971`.

The SHA-256 of the UTF-8, LF-terminated, filename-sorted `hash  filename` list for those five XML
files is `092B2B2AA0478D105FFBC2FCD66A7135A948966DC0FC5BD946D41AD9D0722D1D`.
There was no repair rerun and no other test suite, module/root build, client, server, benchmark,
performance, or network command ran. R2 independently rehashed and parsed this retained 5-suite /
30-test evidence, then returned the bounded PASS; it did not rerun Gradle or replace any runtime
validation.

## Independent R2 acceptance and formal fast-forward

R2 reviewed candidate `b3660bb6007ba89847461f5f0cc3c43f4d0d7478`, tree
`dacf56e6080c50f13cbdbc6cad9e376196c8d9b6`, and reported **PASS (0C/0H/0M/0L)**. Its review
also retains R1's immutable failure rather than rewriting historical evidence. The R2 report records
the five XML suites as 30 tests with 0 failures, 0 errors, and 0 skipped, and binds their
filename-sorted hash-list to
`092B2B2AA0478D105FFBC2FCD66A7135A948966DC0FC5BD946D41AD9D0722D1D`.

Formal closeout independently confirmed that both
`agentloop/x7-t1c-shutdown-fence` and the formal
`agentloop/blendlib-expansion` HEAD resolve to that exact commit/tree. The formal branch reflog
records `merge b3660bb6007ba89847461f5f0cc3c43f4d0d7478: Fast-forward` at
`2026-08-30 12:20:36 +0800`; no cherry-pick, rewrite, or additional production/test/resource/build
change is represented by this metadata record.

## Waiting

R2's bounded PASS and the formal fast-forward do not promote X7 completion, X8, any P0--P8 Gate,
or any unexecuted runtime, visual, compatibility, hardware, JAR, or performance result. Real buffer
allocation/upload; a production completed-set creator/attachment caller; canonical
source-plan mapping; target/pipeline; actual `CommandEncoder`/`RenderPass`; binding/draw;
last-use/pass-completion receipt; shutdown with real live resources; repeated reload; real
client/server; window/programmatic/exception quit evidence; visual acceptance; exact Iris/Sodium
and combined compatibility; hardware/JFR; operational timeout acceptance; benchmark eligibility;
every performance claim; X7 completion; and X8 unlock remain **WAITING**.
