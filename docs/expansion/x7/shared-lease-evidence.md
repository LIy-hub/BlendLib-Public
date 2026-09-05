# X7 D2a shared-generation lease evidence boundary

This is an implementation-evidence map for ADR-X7004, not a review verdict or runtime acceptance.

| Boundary | D2a evidence | Deliberate limit |
|---|---|---|
| D1 registry | one owner-lock exact admission: active/PUBLISHED/map-handle/render-handle identity/count | no public owner or second active pointer |
| Cross-package API | private-construction `ClientGenerationLease` and `ClientGenerationLeaseBinding`; binding exposes validation, one primitive exact-snapshot CPU-route check, caller-to-plan one-shot `transferToPlan()`, and `close()`; its returned receipt exposes only `close()`, never a raw-lease extractor; sealed private-bootstrap service issuer; keyed defaults | no `GpuBuffer`, `GpuDevice`, RenderSystem, raw `ModelHandle`, owner, X7 policy type, or public managed-composite factory exposure |
| X4 | trusted source binding is acquired before X1 pin and released only after X4 close plus admitted-submit drain; D2b stores one CPU-route-plus-existing-visibility primitive; real absent and map-owned missing views normalize only after owner-locked exact current checks; same-raw-h fake wrapper fails source-identity admission | captured/external snapshots remain unmanaged CPU/provider-only; metadata cannot override immutable `RenderVisibility` |
| X6 | `prepareManaged` validates before pin, reads its CPU-route primitive before transfer, atomically moves one caller binding into an opaque receipt after admission, and bridge drains provider plus that receipt; caller close/double prepare fail closed; same-raw-h external B fallback fails with pin count zero | baseline six/seven-argument `prepare` descriptors remain provider-only compatibility paths; later compatible snapshots retain their own visibility |
| CPU submit | X4/X6 read only frozen route primitives plus existing snapshot visibility; no lease acquisition, policy factory, lookup, or registry map access at `BlendRenderer` or `SubmitNodeCollector` callback boundary | submit return is not callback, RenderPass, or draw completion |

Focused source tests cover exact/stale/cross/foreign/missing admission, bootstrap-capability and
public-method boundaries, X4 real-adapter same-raw-h wrapper rejection before X1 pin, real
registry absent/map-owned missing composition and loaded-supersede non-downgrade, X4
reload-retention parent composition, X6 foreign-default rejection before provider pin, atomic
admission-transfer/plan-drain composition including caller-close and duplicate-prepare races, and
six/seven-argument diagnostics compatibility plus raw-null source unambiguity. They do not prove
hardware execution, a deferred callback drain, a RenderPass lifetime, or visual output. Those
items remain **WAITING**.

## Focused verification record

The initial focused invocation exited `1` during `compileClientJava`: the new explicit unavailable
lease singleton had its `bindingKey` and `bindingGeneration` constructor arguments reversed. No
test class ran. The one permitted repeat of the same invocation, after that one-argument-order
repair, exited `0` with `BUILD SUCCESSFUL` (Gradle 9.6.0):

```text
.\\gradlew.bat :blendlib-fabric-client:test
  --tests com.liy.blendlib.fabric.client.api.ClientGenerationLeaseContractTest
  --tests com.liy.blendlib.fabric.client.api.ClientAdapterContractsTest
  --tests com.liy.blendlib.fabric.client.reload.ClientGenerationResourceOwnerTest
  --tests com.liy.blendlib.fabric.client.host.X4SharedGenerationLeaseCompositionTest
  --tests com.liy.blendlib.fabric.client.host.X4HostAdapterContractsTest
  --tests com.liy.blendlib.fabric.client.render.X6PreparedRenderPlanFactoryFailureTest
  --tests com.liy.blendlib.fabric.client.render.X6PlanCloseSubmitRaceTest
```

Final XML results (all failure/error/skip counts are zero; total: 108 tests):

| Suite | Tests | XML SHA-256 |
|---|---:|---|
| `ClientGenerationLeaseContractTest` | 5 | `35AB8E51E383F1456F533BD17B63E31D419ACA0EEDB678DF26D4593C28A79DDC` |
| `ClientAdapterContractsTest` | 3 | `DB54E0AC15791CA349185A6EAACC035F39916CDD262F4C31E28053169062C5F5` |
| `ClientGenerationResourceOwnerTest` | 27 | `107D09FAAB25A96F235AD18A92ABCA821EC276AFE3727460990398E69D42B293` |
| `X4SharedGenerationLeaseCompositionTest` | 2 | `0C7A6CA17A3EC76D575CA0FDD1D2B83B32294545D7117360D8AC4A874C4F2ACE` |
| `X4HostAdapterContractsTest` | 6 | `8E1EED1F210FDA5405F539F6ABB452679ED80CD01342B369FA01F86F0BCE788A` |
| `X6PreparedRenderPlanFactoryFailureTest` | 46 | `D96C4594D58F64EB07F14DD92AB164395B6C1C561430A600FEEEC1713937C4C2` |
| `X6PlanCloseSubmitRaceTest` | 19 | `F54777ABE28641E39F065A659ACB76E1E13CA8D419C222D6352705D414922EE1` |

## D2a repair R2 focused verification record

The first R2 invocation of the focused command exited `1` after test execution: 78 tests ran and
one `ClientGenerationLeaseContractTest` reflection assertion failed because the test accidentally
looked up the historical six-argument descriptor as `prepareManaged`. Production compilation had
completed. The only repair swapped that assertion back to `prepare` and checked the binding entry
as `prepareManaged`; no lifecycle implementation behavior changed between the two R2 invocations.

The one permitted repeat of the same focused command exited `0` with `BUILD SUCCESSFUL` (Gradle
9.6.0):

```text
.\gradlew.bat :blendlib-fabric-client:test
  --tests com.liy.blendlib.fabric.client.api.ClientGenerationLeaseContractTest
  --tests com.liy.blendlib.fabric.client.host.X4SharedGenerationLeaseCompositionTest
  --tests com.liy.blendlib.fabric.client.render.X6PreparedRenderPlanFactoryFailureTest
  --tests com.liy.blendlib.fabric.client.render.X6PlanCloseSubmitRaceTest
  --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

R2 XML results (all failure/error/skip counts are zero; total: 78 tests):

| Suite | Tests | XML SHA-256 |
|---|---:|---|
| `ClientGenerationLeaseContractTest` | 6 | `8470FA1481DF63439B0C29EC0FDF92B78196ECE9E0BF68F6FA5DD389EF09CA43` |
| `X4SharedGenerationLeaseCompositionTest` | 5 | `6AC9AAAF93A06F85560FC20C91D8A22F17D02BFAE18DCF57E1F14B3F6470ECE5` |
| `X6PreparedRenderPlanFactoryFailureTest` | 48 | `4DA21A6D049C29AE7F9C999A524A265A0C3A40195B381B323B3C096FE4B247AD` |
| `X6PlanCloseSubmitRaceTest` | 19 | `AF8CF5389A505CC519D49C471FEC94B9772F61FDCED3719BB4A736F70816F34E` |

## D2b frozen CPU-route supplement

The D2b targeted result is 11 XML suites / 93 tests / 0 failures / 0 errors / 0 skipped with
outer Gradle exit `0` and `BUILD SUCCESSFUL in 18s`; it ran once with no retry. The exact command
and per-suite XML SHA-256 values are retained in
[`test-evidence-policy.md`](test-evidence-policy.md). This is implementation evidence, not a
fresh independent review or a callback/pass, GPU, hardware, performance, or visual result.
