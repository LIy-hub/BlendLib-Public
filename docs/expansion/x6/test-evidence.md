# X6 Test Evidence

The newest retained gates below were run in the isolated candidate worktree with Java 25.0.2. They
are source, unit, compile, and static/binary evidence only. They are not current `buildRelease`,
integration, manual-client, network, reload, visual, Iris/Sodium, hardware, or performance evidence.

## R15 terminal-epoch candidate — authoritative current evidence

The accurate chronology is: R14 final source review **PASS**; subsequent formal integration
**BLOCKED** after an independent eight-JVM completion-order probe reproduced the race 96/96 times;
then R15 R3 **FAIL (0C/4H/2M/0L)**, R4/R5 non-verdict design, valid RED (6 tests / 1 intended
assertion failure / 0 errors/skips), and the frozen R15 pre-documentation source/test
**PASS (0C/0H/0M/0L)** plus static/binary **PASS**. This seven-document reconciliation still awaits
independent docs/full-source review and is neither integrated nor released.

| Review boundary | Exact report | Report SHA-256 | Validation manifest SHA-256 |
| --- | --- | --- | --- |
| R14 final source PASS | `D:\BlendLib-review-artifacts\x6-r14-final-source-review-20260809-225150857\FINAL_REVIEW.md` | `798A6401D0C7A8E92634718AC138944CBA8A8FC9286ACDD31E30F2DC6E3EA4B2` | `A853218C0DD626A2EF352C215F3BD2CEAB654B50BB852212232FD0D86B53CE82` (`VALIDATED_MANIFEST.md`) |
| formal completion-order blocker | `D:\BlendLib-review-artifacts\x6-x4-completion-order-20260810-004543464\REPORT.md` | `CA223DA8E84B49AC7EE4CF28E8D41034964EB1E364B2B535C22541B4FB84F8B2` | `00879C330B36CFDAF10E895B30672DAE3A006056A8D799294A6878DD4F6791F4` (`VALIDATED_MANIFEST.md`) |
| R15 pre-documentation source/test PASS | `D:\BlendLib-review-artifacts\x6-r15-final-pre-doc-source-review-20260811-132536133\FINAL_REVIEW.md` | `65F566F4F8C6834A16129C16974CD1599CBD390CDB92BF33EE9406F6C585B33A` | `7226228624C76C143A43D65FDFDD4B5EB38DBCE75D2BF072875655C9D12B553F` (`VALIDATED_MANIFEST.md`) |
| R15 static/binary PASS | `D:\BlendLib-review-artifacts\x6-r15-binary-boundary-audit-20260811-133118406\REPORT.md` | `0ED9CC1FA7C5DAA6F6EA9309D5BDA75F31DA5B503B326F63C2B0AAF9D58EBF66` | `5CB4E94CADE33D5C7901777740A8ADC034B9A1205A72474A3153DE4C82CAEA5C` (`VALIDATED_MANIFEST.md`) |

Every numerical result below has 0 failures, 0 errors, and 0 skipped tests. The retained artifacts
were created against the exact pre-documentation eleven-path freeze; this docs-only phase may reuse
them only while all nine executable SHA-256 values remain identical and the final sixteen-path
status/diff-check freeze passes independent review.

| Gate | Exact retained artifact | Result | Artifact manifest SHA-256 |
| --- | --- | ---: | --- |
| exact compatibility class | `D:\BlendLib-AgentLoop-Task\artifacts\x6-r15-terminal-order-repair-exact6-rerun-380591-20260811-113211190` | 1 suite / **6 tests** | `0D3390328FA5E5A4C576622EE749DA1207C515A4F9BD9470660E43BA829AE3AB` (`sha256.txt`) |
| test compilation | `D:\BlendLib-AgentLoop-Task\artifacts\x6-r15-terminal-order-repair-compile-test-java-seven-file-20260811-120517019` | `compileTestJava` PASS | `771F47CD67297B8360F71364E9353C0F410D7F4E832F8AE8274CBCE585EFC1AD` (`sha256.txt`) |
| focused X4 | `D:\BlendLib-AgentLoop-Task\artifacts\x6-r15-terminal-order-repair-focused-x4-host-package-seven-file-20260811-120935229` | **9 suites / 65 tests** | `35FEF9F558AEAA65E686A1F7472C54EA49F6479C6E608FFEE7072428912BF4B0` (`sha256.txt`) |
| focused X6 regression | `D:\BlendLib-AgentLoop-Task\artifacts\x6-r15-terminal-order-repair-focused-x6-render-eleven-file-20260811-122752045` | **8 suites / 113 tests** | `1DD730AEC85BA97009349438CA409498F4424A533115760A412952B8A8F73DF0` (`sha256.txt`) |
| full Fabric client | `D:\BlendLib-AgentLoop-Task\artifacts\x6-r15-terminal-order-repair-full-fabric-client-eleven-file-20260811-123228245` | **55 suites / 323 tests** | `A9E7FE0A2A33DFD54C88F7C82E8526FE5D7D623802FB1AABC1895E46FE29124E` (`sha256.txt`) |
| root seven-module strict check | `D:\BlendLib-AgentLoop-Task\artifacts\x6-r15-terminal-order-repair-root-check-seven-module-eleven-file-20260811-124029622` | **134 unique module/suite keys / 1,033 tests** | `0742B852AE2186B31E6B062EDD2D9F2293340CF8F2F005F751ED85CF30D0A3B8` (`sha256.txt`) |
| X1 lifecycle | `D:\BlendLib-AgentLoop-Task\artifacts\x6-r15-terminal-order-repair-gate6a-provider-lifecycle-eleven-file-20260811-124755062` | 1 suite / **69 tests** | `8440A1D0087F990628AA689D81657C6AF1B504782A2AFC9C861B8708F9C4CB56` (`sha256.txt`) |
| X6 public consumer | `D:\BlendLib-AgentLoop-Task\artifacts\x6-r15-terminal-order-repair-gate6b-x6-consumer-eleven-file-20260811-125141841` | 1 suite / **5 tests** | `6250FD264003864E02AC0C7DE1AA8980223CF0F72AE20F3684809BF5AE5FC12B` (`sha256.txt`) |
| corrected Gate 7 | `D:\BlendLib-AgentLoop-Task\artifacts\x6-r15-terminal-order-repair-gate7-eight-independent-retries-rerun-eleven-file-20260811-130445270` | **8/8** independent exact-method JVM rounds | `6460AED6FC92607A0562EAF7A9404B39BA3F7B8AE3A93EFC824E9B724996E90C` (`sha256.txt`); `SUMMARY.txt` `2A122BE8534EBEB3B429AC18C5FC501F1DDDE6BB223D16F470C1EC4DCB7EDE89` |

Corrected Gate 7's stale `master-status.txt` says `RUNNING` and 7/8, but the orchestrator had exited;
that file is deliberately outside the 89-entry master manifest and is not final evidence. The final
summary, eight fresh per-round results/manifests, and postflight establish 8/8. The earlier launcher
artifact `D:\BlendLib-AgentLoop-Task\artifacts\x6-r15-terminal-order-repair-gate7-eight-independent-retries-eleven-file-20260811-125746732`
failed before Gradle and copied stale six-test XML; it is **INVALID** and excluded. The first focused
X4 `HASH_STOP_PROVISIONAL_INVALID` artifact is likewise excluded.

R15 changes no X6 production or wiring. X4's single `E` rejects new prepare/submit admission, treats
open snapshots as future-`B` sources, permits only pre-`E` admitted `H` to finish, and seals
`F = select(Ae*, select(select(B*, C*), D*))`. Ordinary public X4 terminal callers wait; only exact
renderer/provider/revoke callback owner or contribution owner reentry coalesces without self-wait.
X6 remains signal-only and delegates physical close exclusively to its lifecycle owner.

Static/binary review also records one nonblocking internal limitation: package-private
`X4PreparedSnapshot.ReleaseListener` accepts the already allocated `SubmissionHold`. Public/protected
descriptors and the seven lifecycle enum constants are unchanged, and submit adds no allocation.
Changing that internal descriptor would change executable hashes and invalidate every retained gate.

All manual client, network, reload, visual, Iris/Sodium, hardware, and performance gates remain
**WAITING**.

## Historical r12 candidate and r13 owner-drain evidence

At that historical checkpoint, r11 formal integration remained **BLOCKED**. The r12 formal source review was
**FAIL (0C/1H/1M/1L)**; its evidence is retained at
`D:\BlendLib-AgentLoop-Task\artifacts\x6-r12-lifecycle-repair-20260809-132417292`. It is not an
integration verdict, a real-client result, Iris/Sodium result, hardware result, or release result.
The later r13 owner-drain candidate is recorded below: its independent pre-documentation code/test
review passed, but its full formal source review was **FAIL (0C/0H/1M/0L)** solely for the stale
X4-scope statement in the X6 handoff. The then-pending r14 documentation state is historical and is
superseded by the authoritative R15 chronology above; runtime integration remains unstarted.

| Command or retained artifact | Observed result | r12 purpose |
| --- | --- | --- |
| `red-focused-004` | **VALID RED**: 10 tests, 8 intended assertion failures, 0 errors/skips; X4 four and X6 four | immutable-base counterfactual: every-`Error` precedence/state and close-vs-submit lifetime defects reproduce with valid fixtures. |
| `green-focused-003-finally` | 10 tests, 0 failures/errors/skips | permanent focused matrix: all five throwable kinds, selected identity/suppression, failed-revoke retry, four callback routes, two submits, close failure, and reentrant close. |
| `full-client-001`; `api-consumers-showcase-001`; `root-check-seven-001` | XML respectively 300/0/0/0, 434/0/0/0, and 1010/0/0/0 | client, API/consumer/Showcase and strict root-seven regression commands returned 0. `buildRelease` was intentionally **not** run by r12. |
| `repeat-01` through `repeat-08` | eight independent Java 25 no-daemon JVM runs, each XML 10/0/0/0; 80/0/0/0 total | repeated race/error matrix with no retained worktree Java process. |
| `external-x6-close-submit-gradle-003.log`; `external-x4-full-chain-gradle-003.log` | Java 25 standalone markers `EXTERNAL_X6_CLOSE_SUBMIT_OK` and `EXTERNAL_X4_FULL_CHAIN_OK` | independent artifact-source probes use the public X6 factory/submit/close and the public X4 builder plus real X1 session; the only package seam is deterministic registry membership injection. |
| `external-r12-classload.log` | `EXTERNAL_R12_CLASSLOAD_OK targets=5` | actual resolved client classpath loaded all five r12 production targets. |
| `abi-javap/descriptor-matrix.json` | all five public/protected JVM descriptor sets equal base; `X6PreparedRenderPlan.close:()V` is unchanged | r12 historical descriptor evidence. The sole text/modifier delta is removal of `ACC_SYNCHRONIZED`; r13 retains that descriptor but supersedes r12 caller-side physical close with lifecycle-owner bridge work after the caller leaves the monitor. Unknown reflective consumers remain a review risk. |
| `hotpath-forbidden-005/hotpath-summary.json` | source/constant-pool/`jdeps` forbidden hits 0/0/0; no added `ThreadLocal`; successful candidate/base public `submit` `new` opcodes 0/0; close/release leave the monitor before `ProviderLease.close` | no submit-time provider discovery, I/O, JSON/GLB parsing, raw GL, reflection, or newly allocated success-path hold. The one `new` in `releaseSubmissionHold` is the defensive underflow failure branch, not the successful drain path. |

The retained invalid/incomplete harness attempts are not counted as RED or GREEN: `red-focused-001`
(test syntax), `red-focused-002` (wrong helper owner), `red-focused-003` (fixture id case), the
first direct external `javac` invocation (Windows command-line length), external Gradle init
attempt 001 (top-level scope) and 002 (extra X4 builder argument), the first baseline compile
(timeout while initializing a separate cache; offline rerun completed), `hotpath-forbidden` through
`-004` (scan/method-boundary criteria errors), and the first final-GOAL line counter (assumed a
newline form incorrectly). Their logs/artifacts remain retained; none is relabelled as evidence.

| Command | Result | Coverage purpose |
| --- | --- | --- |
| `./gradlew.bat :blendlib-fabric-client:compileTestJava --rerun-tasks --no-daemon --max-workers=1 --console=plain` | `BUILD SUCCESSFUL` | X6 render implementation and its regression suite compile against Minecraft 26.1.2 public APIs. |
| `./gradlew.bat :blendlib-fabric-client:test --tests 'com.liy.blendlib.fabric.client.render.X6*' --tests 'com.liy.blendlib.fabric.client.render.SkinnedRenderBackendContractsTest' --rerun-tasks --no-daemon --max-workers=1 --console=plain` | `BUILD SUCCESSFUL`, 67 tests, 0 failures/errors/skips across 7 XML suites | all six variants; final variant-material inheritance and explicit layer override; exact handle/snapshot identity; static/skinned submission; canonical per-bone subsets; provider containment; post-pin ownership; all-`Error` fatal precedence; existing skinned backend compatibility. |
| `./gradlew.bat :blendlib-fabric-client:test --tests 'com.liy.blendlib.fabric.client.render.X6PreparedRenderPlanFactoryFailureTest' --rerun-tasks --no-daemon --max-workers=1 --console=plain` | `BUILD SUCCESSFUL`, 27 tests, 0 failures/errors/skips | exact post-pin release ownership; primary/cleanup `AssertionError`, `LinkageError`, `OutOfMemoryError`, and `ThreadDeath` identity; suppression appender failure; pre-start fallback close; controlled-worker termination/interruption; and successful plan ownership. |
| `./gradlew.bat :blendlib-api:test --tests 'com.liy.blendlib.spi.experimental.ProviderLifecycleSessionTest' --rerun-tasks --no-daemon --max-workers=1 --console=plain` | `BUILD SUCCESSFUL`, 69 tests, 0 failures/errors | underlying X1 lifecycle state, pin drain, shared ownership, and exact-once terminal behavior remain green. |
| `./gradlew.bat :blendlib-fabric-consumer-fixture:test --tests 'com.liy.blendlib.fixture.fabric.x6.X6ConsumerMaterialProviderTest' --rerun-tasks --no-daemon --max-workers=1 --console=plain` | `BUILD SUCCESSFUL`, 5 tests, 0 failures/errors/skips | public API-only external provider, optional fallback, nonfatal lifecycle exception, a deterministic 128-by-4 retire/close/release matrix, and a real blocked-terminal-callback observer-close harness. |
| Eight fresh sequential JVM executions of the preceding consumer-class command | 8/8 passed; 40 class tests; 1,024 matrix rounds; 4,096 deterministic ordering cases; 107.7 seconds total (13.2--14.4 seconds per process) | cross-process scheduler stability of the final post-audit harness. |
| `./gradlew.bat :blendlib-fabric-consumer-fixture:test --tests 'com.liy.blendlib.fixture.fabric.FabricConsumerFixtureBoundaryTest' --rerun-tasks --no-daemon --max-workers=1 --console=plain` | `BUILD SUCCESSFUL`, 2 tests, 0 failures/errors | consumer fixture dependency boundary remains valid. |
| `./gradlew.bat :blendlib-fabric-client:test --rerun-tasks --no-daemon --max-workers=1 --console=plain` | `BUILD SUCCESSFUL`, 189 tests, 0 failures/errors/skips across 39 XML suites | complete related client regression surface. |
| `./gradlew.bat check --rerun-tasks --no-daemon --max-workers=1 --console=plain` | `BUILD SUCCESSFUL`, 36 actionable tasks executed; 764 tests, 0 failures/errors/skips across 105 XML suites | complete multi-module compile, test, and configured boundary verification. |
| `./gradlew.bat buildRelease --rerun-tasks --no-daemon --max-workers=1 --console=plain` | `BUILD SUCCESSFUL`, 63 actionable tasks executed | release, Local Maven consumer, Blender add-on, inventory, archive, and final checksum gates. |
| Independent PowerShell recomputation of `build/release/SHA256SUMS` | 17/17 checked; 0 malformed, missing, or mismatched | release manifest integrity independent of the Gradle verifier. |
| Source scan plus Java 25 `javap -c -p -s -verbose` and `jdeps --multi-release 25 -verbose:class -filter:none` on `X6PreparedRenderPlanFactory` | source 0 forbidden hits; bytecode 0 forbidden hits; `jdeps` exit 0, 0 unresolved references | the repaired factory adds no provider registry, parser, resource/file-I/O, raw-LWJGL, or `RenderSystem` dependency. |
| Java 25 JShell `Class.forName` over the resolved client runtime classpath | exit 0; factory, `PlatformLeaseReleaseWorker`, and submitter loaded | compiled classload boundary on the actual resolved client classpath. |
| Java 25 `jdeprscan --release 25` over all 72 compiled `X6*.class` files and resolved client runtime classpath | exit 0; 0 unresolved references; one intentional `ThreadDeath` check reported deprecated-for-removal | deprecation inventory is explicit and limited to required fatal-boundary compatibility. |

The suite directly checks that `X6PlanSubmitter` contains no material provider, capability registry,
manifest parser, resource manager, file I/O, GLB parser, raw LWJGL, or `RenderSystem` reference. It
also structurally checks the frozen renderer path: no nested `(pose, consumer)` or `emit(...)` lambda
captures a prepared plan or lease. It exercises an actual `SubmitNodeStorage` collection submission
with two base draws and every non-attachment static-safe X6 layer callback. The test invokes the
public callbacks through a test storage to verify the frozen DAMAGE_FLASH, OUTLINE, and SHADOW
ARGB/geometry consequences, while the original public storage still records each submission.

The skinned regressions use an actual `SkinnedRenderHandle`, retain the selected canonical
`(node, skin, joint)` target, and prove that only triangles positively influenced by its joint are
emitted. Factory preparation rejects a skin/joint range error and a canonical-node mismatch against
the exact handle's immutable skin-joint table before pinning; submit retains only the exact-handle
and skinned-mesh-count shape fence. Zero-influence and static per-bone requests fail closed before
publication.

The R3 repair was test-first: the expanded X6 targeted command initially reported 28 tests with 7
expected failures on the prior candidate. Those failures covered caller-owned pre-session providers,
post-session X1 retirement/close aggregation, null and hostile one-pass provider collections,
fatal cleanup identity, canonical skin-joint validation, and the removal of submit-time catalog
validation. The R3 post-fix 28-test run exercised those cases, including exact
`VirtualMachineError`/`ThreadDeath` rethrows and ordinary cleanup diagnostics. Provider input
snapshotting does not invoke untrusted collection `size`, `contains`, or array-conversion methods;
an explicit optional fallback remains usable after nonfatal input exclusion.

The R4 repair was likewise test-first: before its source change, the expanded X6 targeted command
reported 29 tests with 1 expected failure. Two different `SkinnedRenderHandle` instances carried
the same key, generation, and mesh count, but the foreign capture's first vertex was `x=99.0` and
reached the deferred callback through the first handle's X6 plan. The final 29-test result instead
rejects that pair while constructing the model snapshot, before a callback/renderer/consumer can
observe the foreign payload. `SkinnedRenderSnapshot` now retains its private exact source handle
and compares it by identity in O(1), while preserving the existing key/generation and mesh-count
defenses. The non-X6 skinned backend contract also covers same-handle success, same-version foreign
handle rejection, old-generation rejection, output-count mismatch, immutable output capture, and
the existing missing/static paths.

The R5 repair was test-first against the exact prior production source: the expanded X6 targeted
command reported 32 tests with 2 expected failures. The forward/reverse distinct-provider case and
the repeated-object/hostile iterator-only case showed the old order winner: the generation was
`publishable=true` and a provider reached `offers()` once. The direct X1 control already rejected
the contender as `DUPLICATE_PROVIDER_ID` / `BLENDLIB-X1-CAP-001` before its offers. The final
32-test run has 0 failures/errors. Both orders, repeated object identity, metadata-first/last
hostility, and a collection that rejects a second iterator acquisition now produce one exact X6
`ERROR` / `CAPABILITY_FAILURE`: subject is the duplicate canonical id and message is
`BLENDLIB-X1-CAP-001: Provider identity is already registered or being registered`. No contender
reaches supported-capability metadata, offers, prepare, apply, retire, or close, including after the
failed generation is closed; providers remain caller-owned. Explicit optional fallback remains
limited to non-error null or ordinary metadata exclusion. This repair records no Agent Innovation;
it enforces the frozen X1 provider-identity contract.

The fresh r6 formal review reported one Medium test-harness defect. It did not report a production
source defect. Across eight Java 25 executions of the consumer-class command above, the reviewer
observed 1 failure and 7 passes: the failure reported expected `RETIRING` but actual `PUBLISHED`.
The old test started its close thread, performed only 10,000 `Thread.onSpinWait()` iterations, and
asserted state before proving that the worker had been scheduled or had invoked `session.close()`.
A separate local scripted reproduction on pre-R6 base commit
`0666e6e8688f35fb4d0716feb3cc63fb4f3ab15e` reported 8 passes and 0 failures. The two observations
are retained separately; neither is treated as stable-red evidence or as proof that excludes a
production defect.

The R6 executable-code repair changes only the public consumer test. Each class execution now runs
128 fresh provider/session/lease races across four close/join/pin-release orderings. A ready latch,
start barrier, invocation latch, state wait, and body join share one monotonic `System.nanoTime()`
deadline; cleanup uses separately bounded recovery joins. The test confirms the actual `RETIRING`
state before requiring pin rejection with the exact X1 `INVALID_LIFECYCLE_STATE` diagnostic. Lease
release, idempotent session close, bounded join/interrupt, worker failure capture,
and exact-once RETIRE/CLOSE assertions are guaranteed by cleanup that preserves the primary failure
and suppresses any cleanup failures. The method-level gate passed all 128 iterations. An owner-local
scripted run on the uncommitted R6 worktree reported that eight further independent Gradle/JVM
executions passed 8/8, covering 32 class tests and 1,024 race iterations in 121.4 seconds
(13.0--18.0 seconds per process). This test-only repair records no Agent Innovation and changes no
production lifecycle semantics.

The R6 consumer and boundary XML suites contained 4 and 2 tests respectively. Those figures are
retained as historical R6 evidence rather than presented as the current r8 result.

The fresh r7 review of `5408768d2594790cf0cc2de5715145f097ff601e` returned `FAIL` on two
ownership/concurrency gaps. First, two old matrix branches invoked `session.close()` synchronously
on the controller before releasing the pin even though X1 permits that close to wait. A controlled
probe proved the controller did not complete until an external rescue released the pin. Second,
the factory caught only controlled/runtime failures after pin acquisition, so a post-pin
`OutOfMemoryError` or `ThreadDeath` skipped release; the retained review probe observed the same
fatal object with the generation still `RETIRING`. These are r7 findings, not runtime or visual
acceptance evidence.

The r8 factory suite was written against the prior cleanup logic and initially produced 5 expected
failures across 8 tests: real post-pin `OutOfMemoryError` and `ThreadDeath` left the generation
`RETIRING`; fatal-primary cleanup was not attempted; ordinary cleanup replaced the controlled
primary; and fatal cleanup did not suppress that primary. The completed 10-test suite now covers
the full post-pin ownership interval, exact fatal identities, both fatal-primary/fatal-cleanup
directions, ordinary diagnostic precedence, pin fatal before lease acquisition, success ownership,
and a real blocking last-pin release. The potentially terminal `lease.close()` runs exactly once on
a named non-daemon platform worker. Its synchronous lifecycle owner joins through interruption and
restores the interrupt flag only after cleanup completes, so no completed call leaves a worker or
an unobserved cleanup throwable. The blocking test proves the terminal provider callback runs on
that release worker, not the controller or factory owner, and that all participating threads end.

The r8 consumer test contains 5 tests. Each class execution performs 128 rounds of all four real
orderings: release-complete then close; release in an active terminal callback then an observer
close waiting in `ProviderLifecycleSession.awaitRetirement`; close-complete then release; and
close-complete, active terminal release, then a second waiting close. Every potentially blocking
session/lease close has its own non-daemon worker, ready/start/invocation coordination, and the one
shared body deadline; recovery has a separate shared cleanup deadline. The dedicated blocking
harness independently proves the controller can release a real provider callback while both the
last-pin release and duplicate close are off-controller. Final eight-JVM evidence is 8/8 passes,
40 class tests, 1,024 matrix rounds, and 4,096 deterministic ordering cases in 116.6 seconds.

The final r8 multi-module `check` contains 105 XML suites and 747 tests with 0
failures/errors/skips. `buildRelease` executes all 63 actionable tasks. Independent recomputation
matches all 17 `SHA256SUMS` entries. The paired manifest fixtures remain byte-identical at 1,251
bytes and SHA-256 `533B547E31286A232383698068EFCC258CB647275F4860238CABEC926344BA25`.
Source, bytecode, classload, `jdeps`, and `jdeprscan` checks are recorded in the table above; the two
`ThreadDeath` deprecation reports are the intentional fatal-compatibility checks, not unresolved
platform references.

The r9 repair was test-first on production baseline
`e66e57a7b2cedeb0ac36cc988bf2b29aaf2e82b7`. After only the two permanent H1 tests were added,
the factory class had 12 tests with 2 expected failures: cleanup `AssertionError` and
`LinkageError` were converted into nonpublishable diagnostics instead of escaping as the original
objects. The completed 27-test factory suite makes every `Error` fatal, including primary and
cleanup `AssertionError`, `LinkageError`, `OutOfMemoryError`, and `ThreadDeath`. It proves exact
identity, exact safely representable suppression arrays, ordinary runtime diagnostics, real
generation `CLOSED`/exact-once terminal callbacks, the named worker's no-survivor behavior, and
the existing interruption-restoring join.

R9 also adds deterministic package-private seams only for the release launcher and suppression
appender. Tests prove that ordinary suppression bookkeeping is contained, self-suppression and
suppression-disabled throwables retain the selected original, and `ThreadDeath` or
`OutOfMemoryError` thrown while adding metadata escapes as that exact object. Constructor
`OutOfMemoryError`, start `ThreadDeath`, and start `RuntimeException` each preserve caller
ownership before a normal start return; the lifecycle owner fallback-closes once. The suite covers
successful, fatal, and ordinary fallback close outcomes, records all safely representable primary,
launcher, and fallback failures, and proves a real controller remains free while the lifecycle
owner performs the synchronous fallback terminal callback. R9 changes no public API and records no
Agent Innovation.

At that r9 checkpoint, the candidate did not claim real client runtime validation, GPU or
allocation/performance evidence, visual parity, reload stress, or Iris/Sodium compatibility. Those
runtime, reload, visual, GPU, performance, and compatibility gates were `WAITING`; this historical
statement is superseded for source-review status by the r13 pre-documentation PASS below, not for
runtime integration status.

## R10 Error boundary and controller evidence

The r10 repair is test-first on candidate
`7b78820b5dfca610b158532ea9d0c47e1d264596`, still with production baseline
`e66e57a7b2cedeb0ac36cc988bf2b29aaf2e82b7`. The first permanent targeted run retained
51 tests with 12 expected failures: eight direct `Error` paths were being diagnosed rather than
escaping with the original object, and four post-pin handoff paths could double-close or leak a
lease. The repair makes every directly thrown `Error` terminal throughout the X1/X6 boundary:
provider input, id, metadata, offers, prepare, apply, retire, close, cleanup, diagnostics, and
suppression bookkeeping complete terminal cleanup where applicable and then rethrow the same
object. It never turns such an `Error` into a diagnostic or safe-string payload. In contrast,
hostile ordinary `RuntimeException` inputs and callbacks remain safely normalized to bounded
diagnostics/fallback behavior.

After a post-pin publication failure, a single package-private atomic controller now has the only
raw `ProviderLease` reference. Launcher, worker, and fallback receive only that controller; its
cached exact-once terminal result covers launch/start/await ambiguity, prevents raw-lease
double-close, and prevents a leak. The default release remains on a named non-daemon worker, the
factory joins it, and interrupted joining restores the interrupt flag only after terminal outcome
selection.

| Command or artifact | Result | r10 coverage purpose |
| --- | --- | --- |
| `02-permanent-red-x6-targeted.log` | 51 tests; 12 expected failures on the unmodified r10 candidate | permanent red proof for direct-`Error` identity and controller handoff gaps before production repair |
| `04-second-green-x1-x6-targeted.log` | `BUILD SUCCESSFUL`; 142 tests, 0 failures/errors | `ControlPlaneHardeningTest` 91, `X6MaterialProviderGenerationTest` 20, and `X6PreparedRenderPlanFactoryFailureTest` 31 exercise X1/X6 direct `Error` identity, ordinary containment, suppression, terminal cleanup, and exact-once controller release |
| `06-authorized-drift-regressions.log` | `BUILD SUCCESSFUL`; 228 tests, 0 failures/errors | allowed legacy fixtures are explicitly ordinary-runtime hostile fixtures, while their `Error` controls remain fatal |
| `09-full-api-client-green.log` | `BUILD SUCCESSFUL`; API 351 plus client 202 tests, 0 failures/errors | complete API/client regression surface after the authorized fixture corrections |
| Original r9 source SHA-256 `E772925032582EB23C4392B963CFC09886AFABC1C9431547613BB3D3FD0EEE60`; `11-r9-original-probe-compile.log` | expected compile failure, three references to the removed raw-lease handoff seam | exact immutable original r9 independent probe was compiled against the r10 test runtime classpath first and was not edited |
| `X6R10DerivedFatalProbe.java`, SHA-256 `9B1E52BA20F0A1D43C9AB5C1A62884297F9D5B50F46728E96DCA47EEEE7756C7`; `12-r10-derived-vs-r9-original.diff`; `13-r10-derived-probe-compile.log`; `14-r10-derived-probe-run.log` | compile success; 20/20 probe checks pass | separately named r10-derived adaptation for the controller-only seam; it is not represented as the original independent r9 probe |
| Eight fresh JVM runs of `X6ConsumerMaterialProviderTest` (`15-consumer-x6-matrix-8jvm.log`) | 8/8 passed in 105.8 seconds; 40 class tests, 1,024 matrix rounds, 4,096 deterministic ordering cases | fresh cross-process consumer lifecycle/ownership evidence |
| `16-full-check.log`; `18-final-test-xml-counts.log` | `BUILD SUCCESSFUL`; 36 actionable tasks; final XML inventory 107 suites / 783 tests / 0 failures / 0 errors / 0 skipped | full multi-module verification after r10 repair |
| `17-build-release.log` | `BUILD SUCCESSFUL`; 63 actionable tasks | release, local-Maven consumer, Blender add-on, inventory, archive, and release checksum gates |
| Independent PowerShell SHA-256 recomputation of `build/release/SHA256SUMS` (`19-release-sha256-recompute.log`) | 17 checked; 0 malformed, missing, or mismatched | release manifest independently agrees with the generated artifacts |

The original r9 probe is retained only as an immutable historical source and its current
compile-failure is expected because r10 deliberately removes raw lease access from the
package-private worker seam. The r10-derived source is a separate artifact with a complete diff
and distinct SHA-256; its green result does not replace or relabel the original r9 evidence.

These are source, unit, consumer, and release-build results only. They do not constitute a fresh
independent r10 review or real client runtime, GPU, allocation/performance, reload-stress, visual,
or Iris/Sodium acceptance. Those gates remain historical evidence; the current R11 candidate still
requires its own fresh review.

## R11 protocol-contract and release-worker diagnostic evidence

R11 makes the controlled Experimental host contract explicit as `1.1.0`, with current X6
compatibility `[1.1.0, 1.2.0)`. It deliberately leaves `CapabilityRegistry` as a generic versioned
request/offer data plane: historical `1.0.0` metadata remains representable there, but current X6
intersects every caller request with its current line before discovery and validates the frozen
selected offer after freeze. The permanent adversarial test uses a broad caller range
`[1.0.0, 2.0.0)`: a sole `1.0.0` offer cannot publish a current generation, while a `1.1.0` offer
does publish. The same R11 contract states that every callback `Error` retains exact-object terminal
escape after cleanup, while ordinary non-`Error` failures remain bounded diagnostic input.

| Command or artifact | Result | R11 coverage purpose |
| --- | --- | --- |
| `02-permanent-red-x1-x6.log`, SHA-256 `A31D1B4B28CEB5F7A9FF7F57E2DE1368A337406C588E60DC90E29091E61E7263` | 53 tests; 2 expected failures before the production repair | showed both defects: standard current-X6 request selected a historical offer, and a started worker terminal close was falsely reported as a worker-start/fallback failure. |
| `05-focused-green-complete-r11.log` and `05-focused-green-complete-r11-client.log`; final API contract rerun `13-final-api-protocol-contract-r11.log` | `BUILD SUCCESSFUL`; 60 focused tests initially, then final `ExperimentalProtocolVersionContractTest` 3/3 | `ExperimentalProtocolVersionContractTest` locks `1.1.0`/historical-data semantics and directly exercises the all-`Error` boundary plus ordinary-`RuntimeException` classification; `X6MaterialProviderGenerationTest` 23 includes the broad-range legacy/current adversarial pair; `X6PreparedRenderPlanFactoryFailureTest` 34 distinguishes started-worker terminal close, launch/start/await paths, and true fallback-close diagnostics. |
| `14-final-full-check-r11.log`; `18-final-canonical-test-xml-counts-r11.log` | `BUILD SUCCESSFUL`; 36 actionable tasks; canonical module XML inventory 106 suites / 787 tests / 0 failures / 0 errors / 0 skipped | complete final R11 multi-module check. The inventory intentionally counts only the seven current module `build/test-results/test` roots and does not relabel retained historical reviewer-fixture XML as current output. |
| `07-consumer-8jvm-r11-summary.log` | 8/8 fresh sequential JVMs passed; 40 consumer-class tests | cross-process lifecycle/ownership stability after the protocol and controller repair. One outer command timeout before a test result is retained as an interrupted attempt; its replacement is a fresh successful JVM and is the counted fifth run. |
| `15-final-buildRelease-r11-background.log`; `16-final-release-sha256-recompute-r11.log` | final `buildRelease` `BUILD SUCCESSFUL`; 63 actionable tasks; SHA256SUMS 17 entries / 17 unique / 0 malformed / 0 missing / 0 mismatches | local release, Local Maven consumer, Blender add-on, inventories, archive, and independent final hash verification. |
| `17-final-api-and-x6-boundary-r11.log` | final annotation bytecode default and public constant are `1.1.0`; API JAR `jdeps` reports only `java.base`; submitter/factory forbidden source scans are 0 | confirms the controlled protocol default in final compiled API output and retains X6's no-submit-discovery/private-render-dependency boundary. |

The R11 artifacts are retained outside the worktree at
`D:\BlendLib-review-artifacts\x6-r11-implement-20260807-150000000`. They are historical evidence:
r11 formal integration remains BLOCKED and the later r13 repair below does not claim r11 runtime
integration, real-client visual behavior, reload stress, GPU/allocation/performance evidence, or
Iris/Sodium compatibility.

## r13 owner-drain repair — TDD, frozen candidate, and final-code gates

R13 is a lifecycle safety repair, not a shared host rollout. The code/test candidate was frozen on
branch `agentloop/x6-r13-owner-drain-repair` at base
`2531894c4f3cd56c1bd6b21413c6ecc9fd134a9a`. Its 17 production/test paths have aggregate SHA-256
`1965048639038DE91002E6ADE8B20EA5741638451E6FA5BD97A880AA6C022502`; the manifest is
`D:\BlendLib-AgentLoop-Task\artifacts\x6-r13-owner-drain-repair-20260809-162017992\final-code-test-manifest-B.md`
(file SHA-256 `0DF67A06ED94BDD63AFFCEE7BE7B8E877803C874C5A14D27A30DECDAEA219D65`). The later document
overlay is intentionally excluded from that code/test binding.

The fresh independent pre-documentation review is **PASS (0C/0H/0M/0L)**:
`D:\BlendLib-review-artifacts\x6-r13-independent-code-test-review-20260809-190946332\FINAL_REVIEW.md`
(SHA-256 `C4EA4E5CCFCA93F197413707A5F72E3842969ADAA185EC89CD50E2B64BBF4463`). Its self-excluded
validated manifest is SHA-256
`96B06E394709A9BE2E9BAAE8AC725471A2FC0AD07D557E000FF9E5D0EB3A1590`. The review is strictly a
frozen code/test verdict; it does not approve these documentation overlays, shared integration,
release, publication, or deployment.

The later full r13 formal source/document review was **FAIL (0C/0H/1M/0L)**:
`D:\BlendLib-review-artifacts\x6-r13-owner-drain-independent-review-20260809-203430562\FINAL_REVIEW.md`
(SHA-256 `D58F244A5CE587BE5D7BB6867FFECAB40294F1698143263892BAD902C93AAD08`). Its sole Medium is the
stale X4-scope statement in `docs/expansion/x6/integration-handoff.md`; the r14 documentation
correction was then **PENDING**. The current R15 status above supersedes that historical checkpoint;
the old result is not a PASS, acceptance, shared integration, or runtime verdict.

| TDD artifact | Result | Locked behavior |
| --- | --- | --- |
| `p0-requesting-race-replay-valid-red-012` | **VALID RED**, 15 tests / 2 assertion failures / 0 errors; XML SHA-256 `E40F2BF5C7BC2E7B8880469C3F5AE9BC4A9EA801DBCB5C3271A8937367BBA43A` | same-request-thread inline work incorrectly advanced/drained, and a distinct lifecycle owner could not complete before request acknowledgement. The original `-010` logs are retained, but have no XML and are not used alone as structured evidence. |
| `p0-requesting-race-replay-green-013` | 15 / 0 / 0; XML SHA-256 `2EFC8285139622BB3B4B75CA582187915681909C5BD5E84927DD7DDA6A0BD0EE` | same-thread reentry remains fail-closed with zero caller physical close; a distinct owner may complete exactly once before request return without acknowledgement overwriting terminal state. |
| `p0-late-request-terminal-valid-red-014` | **VALID RED**, 19 / 4 / 0; XML SHA-256 `AE2622A2211409BA47C385A925D76B94B08F455BBE708627BC225BDB7A24E8BB` | late ordinary/`Error` request failure after early terminal owner completion was absent from final completion/replay selection. |
| `p0-late-request-terminal-green-015` | 19 / 0 / 0; XML SHA-256 `7CA122902191DE0E8F8B0996A34C8D89066B499BC0EC82D4DD2CBB6FC96A52C9` | four combinations of request ordinary/`Error` × initial owner success/provider `Error`, exact final replay/suppression identity, close/submit containment, and exact-once physical close. An already returned first owner invocation is intentionally not retroactively changed. |

The post-fix final-code gates were rerun with the retained r13 Gradle home. They bind to the frozen
17-path manifest above, not to older 004/007/008/009 results:

| Retained gate | XML result |
| --- | ---: |
| `final-frozen-016-focused-owner-x4-x6-test-results` | 3 suites / 66 tests / 0 failures / 0 errors / 0 skipped |
| `final-frozen-017-expanded-x6-test-results` | 7 suites / 105 / 0 / 0 / 0 |
| `final-frozen-018-focused-x4-x6-test-results` | 16 suites / 169 / 0 / 0 / 0 |
| `final-frozen-019-full-fabric-client-test-results` | 55 suites / 322 / 0 / 0 / 0; XML aggregate `1254F3623547A76603902F2C3397FBFF8ADA7D6DAC85A7AA5D1BCCF3DA003FD5` |
| `final-frozen-020-task-bound-x1-lifecycle-test-results` | 1 suite / 69 / 0 / 0 / 0 |
| `final-frozen-021-task-bound-x4-x6-owner-test-results` | 3 suites / 66 / 0 / 0 / 0 |
| `final-frozen-022-api-consumers-showcase-*` | 40 suites / 427 / 0 / 0 / 0 (API 354, Fabric consumer 7, Showcase 66) |
| `final-frozen-023-root-seven-*` | 134 suites / 1,032 / 0 / 0 / 0; XML aggregate `340E2723268081AA10019CA87F86DC4E41ABD0B10084542CBE3241B4AD8F6541` |
| `final-frozen-024` through `-031` | eight independent Java 25 no-daemon focused runs, each 61 / 0 / 0 / 0; 488 tests total |

External Java 25 public-factory evidence is retained in
`final-external-java25-probe-032`: direct `javac`/`java` runs cover seven-argument dispatcher
admission, ordinary active-submit drain, pre-return distinct-owner terminal, same-thread inline
fail-closed, late request failure exact replay, and legacy six-argument pre-pin owner-required/no-pin
behavior. The final marker is `EXTERNAL_X6_R13_OWNER_DRAIN_PROBE_OK mode=all`; source SHA-256 is
`70EE5D3663FBBA2D9D36AE8C3A3229F1B86F7D67654C901BDEB55599D8E95F34` and the artifact manifest is
`500176AED6E0BA60170166B09F73719DC8F65E648B3D3C66F5C50DF9A0F45611`.

The Java 25 ABI/hot-path evidence is retained in `final-abi-hotpath-033`, report SHA-256
`F1E920B41E91587A0EACC3F3FFD34D757A0A44A4275F3FCFE36F88A38FCCC810`. It verifies the additive
public dispatcher and nested `Admission`, `requestDrain()`/`cancel()` methods, the additive
seven-argument factory overload, preserved deprecated six-argument descriptor, and new
`LIFECYCLE_OWNER_REQUIRED` diagnostic. `X6PreparedRenderPlan.close` retains JVM descriptor `()V`
while its `ACC_SYNCHRONIZED` flag is removed. It also records zero successful-path `new` allocation
for submit/close/request bookkeeping and no X6-owned thread/executor/thread-local/queue; the bridge's
request-thread identity comparison is not a spawned worker.

`INVALID.md` in the r13 artifact is authoritative for invalid attempts. In particular, the
counterfactual cold-home/create/run attempts are **INVALID**, not RED or GREEN, and must not be
retried with another Gradle home; the retained r12 source/formal probe is the applicable historical
counterfactual evidence. The fixture-timing attempt `expanded-x6-post-fix-fixture-005` is likewise
INVALID. All such logs remain preserved rather than being relabelled.

Still `WAITING`: a real production caller of `X6LifecycleDrainDispatcher`, bounded host scheduler
implementation, reload/shutdown wiring, real Fabric-client/visual validation, F3+T and repeated
reload/resource-release evidence, host coverage, performance/hardware, and Iris/Sodium. The r13
code/test PASS and the external probes intentionally do not claim any of these runtime gates.
