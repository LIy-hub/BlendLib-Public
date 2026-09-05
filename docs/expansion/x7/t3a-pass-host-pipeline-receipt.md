# X7 T3a Minecraft 26.1.2 pass-host, static-pipeline, and ordinary-receipt boundary

## Status and scope

T3a adds only client-private infrastructure from the formal X7 base
`c76777069ce00845171eeadc3a8f1f26b897aa53` / tree
`3efc5104b81f7a3bcefb11019dd433cae5e5ee40`. It is not a draw path, resource
attachment, D1 integration, batch/instancing implementation, skinning implementation, or X7
completion claim.

The implementation was based on the two frozen local reports:

- `D:\BlendLib-review-artifacts\x7-t3-api-probe-r2-20260904-214729381\REPORT.md`, SHA-256
  `4EF3B77602B66E76CE63F09A60C8ADA44FB1C66A4D8434286823CC43D5CFDC7E`;
- `D:\BlendLib-review-artifacts\x7-t3-target-pipeline-probe-20260904-220330550\REPORT.md`, SHA-256
  `F7C715DEAE75875F2C9404F7E128937B5A4F0C174761A5FBAF07EE2A4A5C6E1B`.

The target is the exact local Minecraft client-only `26.1.2` JAR and Fabric rendering-v1
`23.3.1+e9207d814c` surface. The implementation never uses a Mixin, reflection, raw OpenGL,
`createRenderPass`, or a draw call.

## Phase-owned main target scope

`X7Minecraft2612PassOwnerHost` keeps the existing one-time Fabric listeners at
`AFTER_SOLID_FEATURES` and `BEFORE_TRANSLUCENT_TERRAIN`. Each listener delegates to the new
package-private `X7Minecraft2612TargetScope` synchronously:

```text
Fabric listener
  -> RenderSystem.assertOnRenderThread()
  -> Minecraft.getInstance().getMainRenderTarget()
  -> getColorTextureView() + getDepthTextureView()
  -> one immediate private target-work invocation
  -> return to Fabric
```

Absent target/views and any resolver or work exception fail closed: no work is dispatched. The
host and target scope retain no `LevelRenderContext`, `RenderTarget`, target view, encoder, or
pass. The current production work is intentionally empty, so this T3a code never creates a GPU
command encoder or a render pass. A future T3b owner may use only the synchronous private work
window and must still avoid escaping views or context.

## Static rigid probe pipeline ABI

Client bootstrap calls `X7Minecraft2612StaticPipeline.registerProductionOnce()` before it creates
the pass host. The registration checks the public `RenderPipelines.getStaticPipelines()` list for
the exact location before registering; a collision, repeat attempt, builder failure, or unexpected
registration result makes the private pipeline state `REJECTED`. It never silently overwrites a
vanilla or third-party location and never registers on reload.

| Item | Frozen T3a value |
| --- | --- |
| Pipeline | `blendlib:pipeline/x7_static_rigid` |
| Vertex shader | `blendlib:core/x7_static_rigid` |
| Fragment shader | `blendlib:core/x7_static_rigid` |
| Resources | `assets/blendlib/shaders/core/x7_static_rigid.vsh` / `.fsh` |
| Snippet | `RenderPipelines.MATRICES_PROJECTION_SNIPPET` |
| Primitive mode | `VertexFormat.Mode.TRIANGLES` |
| Depth/stencil | `DepthStencilState.DEFAULT` |
| Cull | `true` |
| Uniforms | exactly `DynamicTransforms`, `Projection`, both `UNIFORM_BUFFER` |

The format is deliberately the B1 32-byte layout: `Position` f32x3 at offset 0, 12 bytes of
padding for the unbound f32 normal, and `UV0` f32x2 at offset 24. It does **not** bind
`VertexFormatElement.NORMAL`, whose normalized byte format is incompatible with B1's f32 normals.
No custom vertex element is registered. The tiny shaders import only the dynamic-transform and
projection blocks needed by the snippet and declare no sampler, normal, fog, lighting, material,
palette, or custom global.

The static registration is metadata only. A genuine client resource-reload/shader-compilation
proof, especially the timing of first registration relative to `ShaderManager.apply`, remains
**WAITING**. T3a therefore does not treat registration as pipeline readiness for a draw.

## Ordinary submission receipt

`X7Minecraft2612SubmissionReceipt.afterClosedPass(CommandEncoder, Runnable)` is package-private
and receives a caller-owned encoder only long enough to create an owned fence. The caller must
already have closed its pass; `CommandEncoder.createFence()` is the local API enforcement point
for that ordering. The receipt retains neither encoder, target, view, pass, D1 object, nor
shutdown fence—only its own `GpuFence` wrapper and its one internal consumer.

```text
caller closes its pass
  -> receipt creates its own fence
  -> RenderSystem.queueFencedTask schedules a continuation only
  -> continuation asserts render thread and calls ownFence.awaitCompletion(0)
  -> false: schedule one later continuation, without a loop or consumption
  -> true: invoke consumer once, then close the owned fence once
```

Its states are `ENQUEUED`, `FENCE_PENDING`, `FENCE_SIGNALED`, `REJECTED`, `FENCE_FAILED`, and
`CLOSE_FAILED`. Creation/scheduling faults reject; render-thread or own-fence polling faults are
`FENCE_FAILED`; consumer or owned-fence close faults are `CLOSE_FAILED`. The original throwable is
retained for internal diagnosis (with later close errors suppressed) and the consumer is not run on
any failure path.

The scheduler's internal fence and the T1c final-present shutdown fence are explicitly not ordinary
completion receipts. A queue callback is only a chance to zero-poll this receipt's own fence.
T3a does not attach the receipt to D1; until a future integration owner supplies the exact
consumer, an unsignaled or rejected receipt leaves any prospective resource unconsumed.

## Verification and remaining gates

Focused source/fake tests cover phase ordering, null/exception fail-closed behavior, no retained
target fields, exact public target/pipeline surface, ABI offsets and uniforms, collision/duplicate
registration, exact shader resources, pass-close-before-fence semantics, FIFO delay, false/true
zero-polls, one-shot consumption, owned-fence exact-once close, and failure diagnostics.

Still **WAITING**: client/reload shader compilation, real target/pass binding, T2a1 resource-island
attachment, D1 consumption integration, material/texture/LOD policy, direct draw, batching,
instancing, GPU skinning, real client/server/hardware/visual/performance evidence, Iris/Sodium,
and T1c policy for an ordinary receipt that remains pending during shutdown.

## Agent Innovation

None. This tranche deliberately implements only the frozen T3a contract.
