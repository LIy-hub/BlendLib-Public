# X7 policy test evidence

## Evidence boundary

X7 is a deterministic, reload-private policy foundation. Its evidence may establish repaired
policy outcomes, source boundaries, and D2b's narrow frozen CPU-route composition. It does not
establish real GPU work, hardware performance, Iris/Sodium compatibility, resource lifetime, or a
manual visual result.

The R1 independent review remains **FAIL (0C/0H/3M)** until a fresh reviewer evaluates this repair.
The repair tests below cover the three R1 findings; they are not a reviewer verdict or a performance
claim.

## Historical R1 repair evidence

After the R1 targeted repair and its identity-consumption follow-up, the historical exact command
below completed with the focused retry invocation's exit code `0`. This record predates the D2b
package move and is retained only as historical R1 repair evidence:

```text
gradlew.bat :blendlib-fabric-client:test --tests 'com.liy.blendlib.fabric.client.perf.x7.*' --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

The initial focused invocation stopped in `compileTestJava` with exit code `1` because the
accessor-sealing test migration left one old instance-decision getter reference. After that direct
test-source correction and explicit retry authorization, the JUnit XML summary under
`blendlib-fabric-client/build/test-results/test` was: five XML files, 19 tests, 0 failures, 0
errors, and 0 skipped.

| Test class | Deterministic repair coverage |
|---|---|
| `X7GenerationPerformancePlanTest` | capability/prepare/upload failure matrix; CPU-before-publication fallback; frozen generation/handle/model/geometry/material/LOD binding; stale and cross-identity rejection; private-modifier/no-public-constructor evidence for normal same-package Java-source ownership, with adversarial reflection outside the unnamed-module trust boundary; no submit-time backend switch |
| `X7BudgetPolicyTest` | non-negative checks; ACCEPT/DEGRADE/CPU_FALLBACK/REJECT from a frozen published plan; per-model and cumulative limits; checked bone/vertex-times-animated-instance work; exact product boundaries; zero limits; order-independent immutable accumulation; Long.MAX_VALUE multiplication and cumulative overflow rejection with no admitted usage |
| `X7CullingAndLodPolicyTest` | independent instance/primitive/bone decisions; hidden bone and primitive cannot cull a broader scope; UNKNOWN/incomplete bounds stay drawable at every scope; stale/cross-handle/cross-primitive/cross-bone rejection; thresholds, hysteresis, material/generation LOD ownership, and invalid distance/history/bands |
| `X7AnimationWorkAndMetricsTest` | typed instance-only work recommendation with mandatory active generation/exact-handle validation; stale-generation and cross-handle rejection before REDUCED/PAUSED/REUSE_POSE; unknown/incomplete visibility stays conservative; FULL/REDUCED/PAUSED/REUSE_POSE semantics; scope-typed metric recording; immutable metrics snapshot and reset |
| `X7SourceBoundaryTest` | source/compiled-bytecode guard for platform references, raw GL, I/O, reflection, provider discovery, and thread/executor references; explicit reload-private six-owner X7 source boundary |

No timing test is used as proof of performance. Existing unrelated compiler warnings may be emitted
by the module; the focused command succeeded without an X7 compiler warning.

## D2b frozen CPU-route composition

D2b moves the six policy sources into the reload-private source-owning domain rather than exposing
an X7 type across host/render packages. A private-proof CPU-subset route binds only generation,
exact handle, and exact source-view identity; it does not fabricate X6 geometry/material/LOD
identities. `ClientGenerationLeaseBinding` exposes only a primitive exact-snapshot CPU-route check.
X4 stores that route combined with the same snapshot's existing `RenderVisibility`; managed X6
stores only the route so a later compatible snapshot retains its own visibility. Submit source
tests forbid policy factory/lookup/registry work and deferred callbacks remain outside policy and
lease completion.

The one authorized D2b targeted invocation completed on 2026-08-30 with outer exit code `0` and
`BUILD SUCCESSFUL in 18s` (`13 actionable tasks: 13 executed`); it was not rerun:

```text
gradlew.bat :blendlib-fabric-client:test --tests com.liy.blendlib.fabric.client.reload.X7GenerationPerformancePlanTest --tests com.liy.blendlib.fabric.client.reload.X7CullingAndLodPolicyTest --tests com.liy.blendlib.fabric.client.reload.X7BudgetPolicyTest --tests com.liy.blendlib.fabric.client.reload.X7AnimationWorkAndMetricsTest --tests com.liy.blendlib.fabric.client.reload.X7SourceBoundaryTest --tests com.liy.blendlib.fabric.client.reload.X7CpuPolicyProjectionCompositionTest --tests com.liy.blendlib.fabric.client.api.ClientGenerationLeaseContractTest --tests com.liy.blendlib.fabric.client.host.X4SharedGenerationLeaseCompositionTest --tests com.liy.blendlib.fabric.client.host.X4HostAdapterContractsTest --tests com.liy.blendlib.fabric.client.render.X6PreparedRenderPlanFactoryFailureTest --tests com.liy.blendlib.fabric.client.render.X6RenderLayerPlanTest --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

The XML aggregate under `blendlib-fabric-client/build/test-results/test` is 11 suites / 93 tests /
0 failures / 0 errors / 0 skipped. Every listed XML suite has zero failures, errors, and skips.

| XML suite | Tests | SHA-256 |
|---|---:|---|
| `ClientGenerationLeaseContractTest` | 6 | `41B801BC38A101A0BA55934EE5BC7FEB600D9D50217A27B9B20B8A82C1585936` |
| `X4HostAdapterContractsTest` | 6 | `EA5887026D405E1E4C8F29EF2CE980CBD1841A6F6A3498BCB44D7C458B4F74AE` |
| `X4SharedGenerationLeaseCompositionTest` | 6 | `2F23FE05CAE47D3B3A24C16CEF08F91DEAA5B2D75F7EFCE204916FC45226323A` |
| `X7AnimationWorkAndMetricsTest` | 4 | `136C14B4EEF6830AA7D017E3BFDBCF1FFFCE4D9316D2E42584393B97EFA6926B` |
| `X7BudgetPolicyTest` | 5 | `8E92BEA25A2FB59E3929D8792784F00199DBB8BEB3A961C83D7E1025342F273B` |
| `X7CpuPolicyProjectionCompositionTest` | 3 | `F3367103CE830857B754EE2263AD509575B426B9900A7FD2311D94439695EFFF` |
| `X7CullingAndLodPolicyTest` | 5 | `6176FBABA9F38D9D3443E3C7A585EFD7D5612CF7F6C034E580B9BF46B2790FC0` |
| `X7GenerationPerformancePlanTest` | 5 | `CC8C7CFDB4D02CF833D96C39BD3ACB62D5046E373E0339FEB17FD5FB22E47591` |
| `X7SourceBoundaryTest` | 1 | `46C951E8F8676CE534E9829778F6F0618F41EDFA0BA46F2FF571BE8F339A622E` |
| `X6PreparedRenderPlanFactoryFailureTest` | 48 | `492105BC71A17B7415521E856B792C2981761A117FD38456293D5D090B13629D` |
| `X6RenderLayerPlanTest` | 4 | `8ED1F22D6FFA9B8EBFF2FCFE0945F1C4A81B4773C1C2393EC3A56027169516B9` |

This establishes only structural CPU-route composition, source/API boundaries, and the selected
X4/X6 ownership cases. It does not establish budget, LOD, animation, GPU, pass-completion,
benchmark, hardware, or visual evidence.

## Formal module gate

The retained one-time module evidence is classified as
`BUILD_BEHAVIOR_PASS_WITH_EXIT_EVIDENCE_LIMITATION`. The required module gate was run once after
the prior repaired source scope and its evidence document were complete:

```text
gradlew.bat :blendlib-fabric-client:check --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

The retained lower-level evidence records `BUILD SUCCESSFUL in 51s`, a successful `BuildAction`
result, and daemon `Runtime.exit(0)`; its current module XML aggregate is 60 files, 340 tests,
0 failures, 0 errors, and 0 skipped. The invoking wrapper's numeric exit-code sidecar was not
retained, so this module evidence does not claim wrapper exit code `0`. No root `check` or release
build is used as X7 evidence.

## Waiting evidence

The following remain **WAITING**: actual GPU/backend and batching implementation; GPU resource
allocation/upload/close; shared mesh/vertex ownership; callback/pass-completion ownership;
budget/LOD/animation policy projection; CPU route behavior beyond the focused structural paths;
Iris/Sodium and incompatible-hardware behavior; real performance profiling; server/client
integration; manual visual acceptance; and fresh independent review.
