# X7 B1 GPU resource test evidence

## Boundary and review disposition

This evidence establishes deterministic unit behavior and candidate bytecode/API boundaries only.
The fresh R1 review of `2060a8de5f452358004b2ccda977c58003aff3b3` remains **FAIL
(0 Critical / 0 High / 4 Medium / 1 Low)** until a new independent review inspects this repair.
It does not establish a renderer draw, a real pass, GPU skinning, static instancing, hardware
performance, Iris/Sodium behavior, client/server integration, or a manual visual result. Those
areas remain **WAITING**.

## Actual 26.1.2 dependency probe

The candidate worktree uses Minecraft `26.1.2` and the local Loom POM
`net.minecraft:minecraft-clientOnly-043a8b3edf:26.1.2`. The committed boundary test compares its
actual GAV and this exact POM SHA-256 rather than accepting a 64-character string:

```text
653DAE8BC25ECA9F552B164B4EB44A13C6120A6DBCAA2CEB1CE5221D228D8E28
```

The candidate archive SHA-256 is
`3851C524851F026CFDF20506BD45BC3D0FCF8370FF093519B07CCD4235A807ED`.
The reconnaissance formal-tree archive SHA-256 is
`A73F3DE0A959FE052B0D1BBA51786932808BF610A82EDDA4F59B175565B2A4D0`.
For this observed pair only, archive comparison found 14,226 entries in each archive, no missing
entries, no entry-content SHA-256 differences, no compressed-size differences, and 431 timestamp
differences. This limited result explains that pair's different whole-archive hashes; it is not a
claim that arbitrary Loom archives are byte-identical.

The API guard hashes the actual class entries and parses the class-file method table to compare the
following exact JVM descriptors:

| Class entry | SHA-256 | Required descriptor(s) |
|---|---|---|
| `com/mojang/blaze3d/systems/RenderSystem.class` | `4DE685BAF0CD591E0D11F26369D56D5EA648930C612D2E3F5B2FE2C4D1943890` | `assertOnRenderThread()V`; `queueFencedTask(Ljava/lang/Runnable;)V`; `getDevice()Lcom/mojang/blaze3d/systems/GpuDevice;`; `tryGetDevice()Lcom/mojang/blaze3d/systems/GpuDevice;` |
| `com/mojang/blaze3d/systems/GpuDevice.class` | `D701CB85FFC7C30135C7EFC8EFBC338856311B4A77482B70071464D45FF3A4C3` | `createBuffer(Ljava/util/function/Supplier;ILjava/nio/ByteBuffer;)Lcom/mojang/blaze3d/buffers/GpuBuffer;` |
| `com/mojang/blaze3d/buffers/GpuBuffer.class` | `5C31547251F508408FC2196CABC6CBB5D1656259FB2770D316C837DE13D9B52E` | `isClosed()Z`; `close()V` |
| `com/mojang/blaze3d/systems/RenderPass.class` | `EDDCDE89FA7E534CE164FFA2ED4B1FD3EA1E694A020E392ED7C9F92C5EBC0906` | `drawIndexed(IIII)V`; `drawMultipleIndexed(Ljava/util/Collection;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/vertex/VertexFormat$IndexType;Ljava/util/Collection;Ljava/lang/Object;)V`; `close()V` |

The probe also confirms the actual `RenderPass` package is
`com.mojang.blaze3d.systems`; `com.mojang.blaze3d.pipeline.RenderPass` is absent and is not used.

## R1 repair coverage

| Review finding | Corrective evidence |
|---|---|
| M1: second forgeable backend/fallback truth | old receipt/candidate types are deleted; source guard rejects legacy policy names; factory exposes only `SUCCESS`/`RESOURCE_FAILURE(stage,cause,cleanup)`; no B1 bridge claims canonical consumption |
| M2: abort ownership and cleanup loss | the first cleanup-ledger allocation plus assertion, vertex, index, assembly, `isClosed`, and `close` Runtime/Error/OOME failpoints verify staging release, exact prior-buffer close attempts, primary/suppressed error preservation, and no partial pair |
| M3: cross-thread drain and false terminal state | monitor-out handoff, off-thread/interrupted/late release, handoff Error, scheduled owner-assertion Runtime/Error, queue rejection, callback error, partial close, re-entry, late duplicate callback, accepted-fence counting, admission refusal, and idempotent retry tests exercise `CLOSE_FAILED` truthfully without creating a production thread or executor |
| M4: public adapter surface and weak evidence | outside-package modifier reflection covers B1 types; exact POM/GAV/hash/descriptors and compiled-bytecode forbidden-reference guards are automated |
| L1: partial diagnostics on overflow | near-limit test proves all `Math.addExact` values are local before the one-field-set commit |

The focused run before the final module check used:

```text
gradlew.bat :blendlib-fabric-client:test --tests "com.liy.blendlib.fabric.client.render.x7gpu.*" --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

The corrected focused run returned exit `0`. Its seven X7 XML suites contained
32 tests, 0 failures, 0 errors, and 0 skipped:

| Test class | Coverage |
|---|---|
| `X7GeometryStagingTest` | exact direct little-endian bytes, invalid index/non-finite rejection, bounded byte-count rejection, CPU staging close |
| `X7GpuResourceFactoryTest` | transaction-only outcome, first-helper-allocation/assertion/vertex/index/assembly failure, exact cleanup, `isClosed` avoidance, Runtime/Error/OOME and suppression behavior |
| `X7GpuGenerationLifecycleTest` | mixed X4/X6/X7 drain, monitor-out handoff, off-thread/interrupted/late release, scheduled owner assertion, rejection/retry, partial close, re-entry, late duplicate callback, and exactly-once successful physical close |
| `X7StaticBatchPlannerTest` | no cross generation/model/material/route/LOD/format merge, stable input order, limits, reset-only diagnostics, and atomic overflow |
| `X7CpuSkinnedUploadCandidateTest` | real `CpuSkinner` output accepted solely as `CPU_SKINNED_UPLOAD_CANDIDATE`, no GPU-skinning claim |
| `X7GpuApiBoundaryTest` | policy/forbidden-source and bytecode guards plus exact POM/class-hash/method-descriptor probe |
| `boundary.X7GpuAdapterSurfaceBoundaryTest` | outside-package reflection rejects every public B1 adapter/owner type |

## Required module check

The final required module command ran once after the scoped source/document audit:

```text
gradlew.bat :blendlib-fabric-client:check --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

The retained Gradle daemon evidence records `BUILD SUCCESSFUL in 53s`, a successful
`BuildActionResult`, and daemon `Runtime.exit(0)`. The retained module XML aggregate contains
67 suites, 370 tests, 0 failures, 0 errors, and 0 skipped. No independent numeric outer-wrapper
exit sidecar was retained, so this result is
**`BUILD_BEHAVIOR_PASS_WITH_EXIT_EVIDENCE_LIMITATION`**. It remains a build/test gate only, not a
runtime, hardware, renderer, compatibility, or visual acceptance.

## Explicit WAITING items

- real P4 `StaticGeometry` bridge and X4/X6/shared generation-owner wiring;
- reload/publication/retirement, future canonical resource-attempt mapping, and actual CPU
  fallback/render routing;
- a production render-owner executor, render-pass ownership, transforms, actual draw, and real
  `drawMultipleIndexed`/static instancing;
- GPU skinning, shaders, SSBOs, and custom pipeline work;
- hardware behavior, repeatable benchmarks/performance proof, Iris/Sodium compatibility;
- client/dedicated-server integration and manual visual acceptance;
- any promotion to stable/public API or original P0--P8/X7 performance acceptance.
