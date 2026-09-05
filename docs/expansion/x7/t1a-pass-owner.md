# X7 T1a trusted render owner and pass-host implementation ledger

## Status

Implementation candidate from exact base
`191a5552f540a2c9689304189931c555d6788ccb`. Focused verification passed, the independent review
returned **PASS (0C/0H/0M/0L)**, and the exact candidate was fast-forwarded into the formal branch.
This is a host/lifecycle-spine tranche only, not X7 completion.

Review artifact:
`D:\BlendLib-review-artifacts\x7-t1a-owner-review-20260830-092904793\FINAL_REVIEW.md`
(SHA-256 `B13E265658FD325739C01A7D12F5A7E6925F5D502DF2138CF5A9B079D4A155BC`),
with self-excluded manifest SHA-256
`CEB25826AFC876FA2DD6BB763B8BD97FADB0A33290A0167E84E9E3BE9294A58F`. Reviewed/formally
integrated identity is commit `8e0e58db3e1b67fb80adc160bd72d3d177a5fab9`, tree
`97f1e612b8687424c52f56bf4ed2a195109975d2`.

## Implemented boundary

- `ClientModelRegistry()` and `publish(...)` retain their CPU-only compatibility behavior.
- The one additive public method is the no-argument
  `ClientModelRegistry.createMinecraft2612Client()` factory. It returns only a registry and installs
  no caller-supplied executor, device, callback, or mutable owner.
- The package-private production owner is fixed to `Minecraft.getInstance()::execute`,
  `RenderSystem.assertOnRenderThread`, and `RenderSystem.queueFencedTask`. Its package-private test
  ports cover both inline and delayed execute behavior.
- The production client entrypoint uses that trusted factory and installs one package-private pass
  host. The host's duplicate-init guard registers exactly one callback at
  `AFTER_SOLID_FEATURES` and one at `BEFORE_TRANSLUCENT_TERRAIN`.
- Each Fabric callback asserts the render thread and runs one phase-only scope synchronously. The
  scope receives no Fabric context, target, pose stack, buffer source, device, encoder, or pass.
- T1a's production scope is empty because there is no prepared X7 draw.

## D1 and shutdown truth

D1 remains the single generation-resource lifecycle and physical-close truth. This tranche adds no
resource attachment and no empty/dormant close callback. It does not call or register
`X7GpuGeneration.retire`, `X7GpuCloseScheduler`, or X7's nested hold counters. X4 and X6 preserve
their existing D1 parent composition.

A future D1 resource close callback must synchronously perform its physical close or throw on the
already-fenced render thread; it cannot queue a second fence/counter and return. Existing D1 tests
retain the observable `FENCE_QUEUED` state until callback execution, `CLOSE_FAILED` on a thrown
physical close, and failed-callback-only retry.

`CLIENT_STOPPING` still calls `ClientModelRegistry.close()` before client-service initialization.
That starts retirement but does not guarantee another `RenderSystem.executePendingTasks()` poll and
therefore does not drain `queueFencedTask`. GPU allocation and resource attachment stay disabled;
shutdown with live resources remains **WAITING**.

## Focused verification

One authorized offline Java-25/no-daemon/one-worker Gradle invocation ran the trusted bootstrap,
pass host, D1 close truth, entrypoint ordering, public surface, and adapter boundary suites:

```powershell
.\gradlew.bat :blendlib-fabric-client:test `
  --tests 'com.liy.blendlib.fabric.client.reload.ClientModelRegistryRenderOwnerBootstrapTest' `
  --tests 'com.liy.blendlib.fabric.client.X7Minecraft2612PassOwnerHostTest' `
  --tests 'com.liy.blendlib.fabric.client.reload.ClientGenerationResourceOwnerTest' `
  --tests 'com.liy.blendlib.fabric.client.BlendLibClientEntrypointLifecycleTest' `
  --tests 'com.liy.blendlib.fabric.client.api.ClientGenerationLeaseContractTest' `
  --tests 'com.liy.blendlib.fabric.client.render.x7gpu.X7GpuApiBoundaryTest' `
  --tests 'com.liy.blendlib.fabric.client.render.x7gpu.boundary.X7GpuAdapterSurfaceBoundaryTest' `
  --offline --no-daemon --max-workers=1 --console=plain
```

Result: process exit `0`; `BUILD SUCCESSFUL in 18s`; 13 actionable tasks (9 executed, 4 from
cache). Fresh XML is 7 suites / 45 tests / 0 failures / 0 errors / 0 skipped. The existing compile
warnings are deprecation/removal/unchecked/serial warnings and did not fail this matrix. No retry,
module/root build, client/server, benchmark, or network run was performed.

## Waiting

Complete exact-generation resource attachment; buffer allocation/upload; target and pipeline
selection; `CommandEncoder`/`RenderPass`; draw/binding; pass-completion receipt; shutdown with live
resources; repeated reload; real client/server; visual acceptance; Iris/Sodium; hardware/JFR;
benchmark eligibility; and every performance conclusion remain **WAITING**.
