# X3 test evidence（historical r1/r2/r5/r6/r7/r8/r9/r10、formal r11/r12 failures + current r13 repair）

日期：2026-08-09。本页只记录 JVM、source、bytecode 与 release evidence；它不是 Minecraft visual、real lifecycle/reload、network、renderer 或 hardware performance evidence。

> formal r1 source review 为 **FAIL**（3 Medium + 1 Low），r5 为 **FAIL（3 Medium）**，r6 为 **FAIL（1 Medium + 2 Low）**，r7 为 **FAIL（1 Medium）**，independent r8 source review 为 **FAIL（0 Critical / 0 High / 3 Medium / 1 Low）**，formal r9 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 2 Low）**，fresh independent r10 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**。下面所有历史数字都只是 implementation-side evidence，不能作为 independent PASS 结论。formal r11 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，formal r12 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**。当前是 r13 directed compatibility-governance repair；fresh independent r13 review 为 **PENDING**。

## r9/r10 findings、r11/r12 formal failures 与 r13 evidence boundary

r8 的 exact-long identity/counter evidence 仍是窄域历史事实，但 r8 independent review 发现 late revision、legal finite pose intermediate 与最大 UTF-16 automatic-next diagnostic 可在 live owner 局部改变后才失败，因此为 **FAIL（0 Critical / 0 High / 3 Medium / 1 Low）**。r9 的候选修复为：owner-only `FrameStage` 完整 prospective frame transaction（complete snapshot + commit-capacity preparation 后才提交 live owner state）、double intermediates 后的 checked finite/positive float publication，以及带 surrogate-safe truncation 的 256-UTF-16 `NEXT_CYCLE` detail。stage 可变但不对 X3 可见；只承诺 pre-commit failure 不改变 live/observable owner state。formal r9 review 的结果是 **FAIL（0 Critical / 0 High / 1 Medium / 2 Low）**，不能被写作 stable API、review PASS 或 integration-ready。r10 fresh review 的实际结论为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**：Medium 有两处 ADDITIVE 根因——Runtime 在 double accumulator 前以 float 计算 effective `layer.weight × mask`，Scratch 又在 additive translation/scale 与 quaternion delta/Hamilton 链中提前 float-narrow；Low 是 finite generation acknowledgement 的精确 `2^64` ABA。r11 candidate 同时修复两者：所有 ADDITIVE multiplication/MAC、relative delta、slerp 和第二 Hamilton 都保持 primitive double 到最终 checked float，weight-one canonical group endpoint 不丢 sample；rejected offer 改为 fresh private ticket 的 identity-CAS handshake，不使用有限 generation。formal r11 review 实际为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**；formal r12 review 也为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，fresh independent r13 review 为 **PENDING**。

| r9 artifact | 已记录的自动化证据 | 适用边界 |
|---|---|---|
| `03-focused-red` | `62 tests / 6 failures` | 保存的 focused TDD red；不是 clean baseline certification。 |
| `08-focused-after-internal-test-migration` | 首次 preflight invalid；授权 resumed `69 / 0 / 0 / 0` | 该记录当时针对 dirty worktree candidate 的 focused green，非 primary terminal proof。 |
| `09-frame-atomicity-coverage` | XML `10 / 0 / 0 / 0` | hash-bound coverage snapshot；无 stdout/stderr/exit terminal capture。 |
| `10a / 10b / 10c / 10d` | `29/241` full core；`10b` 原 multi-task 命令实际 fresh `39/276`，其 `8/51` 仅 expected-XML 子集；`11/91` X3 matrix；`4/9` consumer fixtures，均 0 fail/error/skip | implementation-side JVM evidence；不能把整条 10b 命令称为 filtered matrix。 |
| `11-root-check-final-code` | root-seven `114 / 832 / 0 / 0 / 0` | automated `check`，不含 real client/server run。 |
| `12-boundary-final-code` | jar/boundary/classload/jdeps/bytecode/legacy-consumer evidence | candidate relative to detached parent 的 boundary evidence；不构成长期 API stability 承诺。 |
| `13-pre-doc-build-release` | `buildRelease --rerun-tasks` exit 0、62 tasks、root-seven `114 / 832 / 0 / 0 / 0`、local-Maven `2 / 5 / 0 / 0 / 0`、total `116 / 837 / 0 / 0 / 0` | 文档冻结前 baseline；SHA256SUMS `17 valid / 17 unique / 0 malformed / 0 duplicate / 0 missing / 0 mismatch`，SHA-256 `C51C16DFD6E2D210982D44CB77A2E9DB66B24FCF4F5683E248350EC84A3AFBBE`。 |

最终 binding 仅由在冻结文档哈希生成后产生的外部 `#15 final-docs-bound-build-release` 和 `#16 final-release-sha` 决定；本文不预断其结果，artifact 缺失或不匹配即 binding 未证明。

## r10 historical directed implementation evidence（不是 independent review verdict）

所有 r10 命令使用 worktree `D:\BlendLib-worktrees\x3-procedural-events` 和隔离 `GRADLE_USER_HOME=D:\BlendLib-r10-gradle\x3-procedural-events`。以下均为 implementation-side candidate evidence；其后 fresh independent r10 review 实际为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，不因这些历史 green 数字撤销。

| r10 artifact | raw result | scope and qualification |
|---|---|---|
| `01-red/VALID-RED.md` | focused command 编译后执行 `30 tests / 4 failures` | 行为 RED，不是 fixture/编译错误；baseline runtime SHA-256 为 `62318884E9714560E78A2EB67D305D5D46FFCCA1BB7688DBF5A48EB3E869EFE8`。初次网络 fetch 另存为 INVALID，未计入。 |
| `02-focused-green/VALID-GREEN.md` | `30 / 0 / 0 / 0` | `FrameAtomicity` 13 + `ExactWrap` 17；锁定 two-contributor `Float.MIN_VALUE` translation/quaternion/scale raw-bit `0x1`，以及 wrap/capture/retry owner/queue/latest/revision 原子性。 |
| `03-full-core` | `29 suites / 245 tests / 0 / 0 / 0` | final strengthened focused tests 后的完整 Core。 |
| `04-x2-task-bound` | `8 suites / 51 tests / 0 / 0 / 0` | 三条独立且 task 紧邻 filters 的正确替代命令；这才是 r10 X2 task-bound 证据，不把 r9 `#10b` 的 `39/276` 或 r10 replay 的 `39/280` 命令误称为 filtered。 |
| `05-x3-matrix/command-scoped-rerun` | `11 suites / 92 tests / 0 / 0 / 0` | Core task 后立即附 Core filters、client task 后立即附 client filters；先前 task/filter 排序错误的 `34/263` run 已标 INVALID。 |
| `06-api-fabric-consumer` | `4 suites / 9 tests / 0 / 0 / 0` | API + Fabric consumer fixtures。 |
| `07-root-check` | seven modules `114 suites / 836 tests / 0 / 0 / 0` | `check --rerun-tasks` successful；初次 foreground watchdog timeout 无 XML、已标 INVALID，随后 isolated-home background terminal capture 才是本行 evidence。 |
| `08-boundary` | surface/jdeps/classload/legacy/hot-bytecode all passed | API jar SHA equal; Core class-list and Runtime `javap -protected` diff 0; API/Core classload `70/70`,`237/237`; forbidden entry/constant pool 0; legacy parent-compiled consumer prints `LEGACY_4ARG_LINKED=true`; `evaluate` only `fmul` is additive, override winner has `f2d` before `dmul` throughout. |

正式 r10 release evidence 只能在当时的九份文档冻结 hash 后由唯一一次 `buildRelease --rerun-tasks` 产生；这些历史 records 不替代 r11/r12 的 formal verdict，也不替代本次 r13 docs-bound release。r13 的唯一 release 只能在本轮九份文档 hash 冻结后执行；在其发生、严格 XML 和独立 SHA256SUMS 都记录前，本节不预断任何 r13 release binding 结果。

## r11 directed repair、TDD 与 final-hash evidence（不是 independent review verdict）

所有 r11 命令使用 `D:\BlendLib-worktrees\x3-procedural-events`，唯一持续使用的隔离 home 为 `D:\BlendLib-r11-artifacts-20260809\gradle-user-home`。production 修改前的 Runtime/Scratch SHA 已记录；每次行为 RED 都与编译/fixture failure 区分。r10 review 的 Medium 要求同时覆盖 Runtime effective-weight 与 Scratch additive path，Low 要求 exact `2^64` 的公共诊断行为而非 r11-only reflection failure。

| r11 artifact | raw result | scope and qualification |
|---|---|---|
| `01-focused-red/VALID-RED.md` | `33 tests / 3 failures / 0 errors` | 初始永久 TDD RED：两组 dyadic subnormal effective weight、translation/scale control、quaternion group identity 与 exact-wrap ABA；非 compile/fixture RED。 |
| `11-translation-endpoint-red` | `16 / 1 / 0 / 0` | public `weight=1` translation `±Float.MAX_VALUE -> ±Float.MIN_VALUE` endpoint 丢 raw subnormal 的有效行为 RED。 |
| `12-r10-backport-behavior-red` | cf8 clone `18 / 1 / 0 / 0` | r10 baseline 先失败于 public missing-overflow-diagnostic assertion，非 r11 ticket-field reflection。 |
| `21-quaternion-double-red/VALID-RED.md` | `37 / 1 / 0 / 0` | production 尚未改动时，fully-public fixed raw quaternion/group endpoint RED；expected canonical `Float.MIN_VALUE` component 被旧 intermediate float 污染。不同 override/prior-additive base 的 control 同时防止错误 direct replacement。 |
| `22-focused-post-quaternion` / `23-focused-expanded-final-hash` | `37 / 0 / 0 / 0`；`8 suites / 110 tests / 0 / 0 / 0` | post-fix focused evidence，覆盖 FrameAtomicity、ExactWrap、reviewer/runtime/source/procedural classes。 |
| `24-cf8-final-exactwrap-behavior-red` | **INVALID** | clone/patch 成功但 wrapper 从 candidate cwd 解析，得到 XML `0`；保留且不计行为结果。 |
| `25-cf8-final-exactwrap-behavior-red-valid` | cf8 clone `18 / 1 / 0 / 0` | final ExactWrap patch SHA-256 `932852555E8CD2606F0FB98CFB17A3428B3781FE3BC5B1EED1241AF8D378E0B5`；实际 clone cwd；唯一失败为 public diagnostic line 455 `expected 1 but was 0`。 |
| `26-core-full-final-hash` | `29 suites / 252 tests / 0 / 0 / 0` | full Core。 |
| `27-x2-task-bound-final-hash` | core `3/26` + common `2/11` + client `3/14` = `8/51/0/0/0` | 三个独立 task invocation；common 必须计真实 `2/11`，不是错误的 `2/5`。 |
| `28-x3-task-bound-final-hash` | core `6/75` + client `5/18` = `11/93/0/0/0` | 正确 task/filter ordering 的 X3 matrix。 |
| `29-consumer-fixtures-final-hash` / `30-root-check-final-hash` | consumer `4/9/0/0/0`；root strict-seven `114/843/0/0/0` | root 只计七模块，明确排除 local-Maven XML。 |
| `31-java25-boundary-final-hash` | candidate/parent jar tasks、analysis、filter 均 exit 0 | Java 25.0.2；API classload `70/70`、Core `238/238`（parent 237，唯一新增 package-private ticket）；external protected blocks `168/168` exact；jdeps/source/constant-pool forbidden `0`；legacy parent-compiled candidate-run `LEGACY_4ARG_LINKED=true`；Scratch additive methods `fmul=0`。 |

中间独立审计也必须保留，而不是选择性抹去：`x3-r11-precommit-design-audit-20260809-004447` 发现 translation endpoint Medium 与 legacy-test fingerprint Low；`x3-r11-precommit-design-audit-r2-20260809-011558163` 发现 quaternion double-delta Medium（其固定 seed 200,000 forced-MIN 样本全部污染，99,924 sign flips）；numeric advisor 认为 `sumThree` once-round 的 1 ULP 反例不属于当前 contract，signed-zero raw mismatch 是非阻塞 observation，并要求 ticket test 用 reference identity 而非 record value equality。上述 Medium/Low 都在后续 TDD/修复中处理；最终 `x3-r11-final-hash-design-audit-20260809-022255355` 报告 **NON-VERDICT — NO-BLOCKER（0C/0H/0M/0L）**，REPORT SHA-256 `DC82021CAB4610B9C78955A0051BCA6B4DEE43803793297E9D8763A1CE786267`、manifest SHA-256 `E1C579E63EFF27E0B24130A60913D4234C8E90F26AA0375A794DF693DE090B39`。它不是 formal PASS；formal r11 review 后来实际为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，r12 的不合规 T10 candidate 又由 r13 compatibility-governance repair 撤回。

普通 accepted ingress 不新增 ticket allocation；ticket 的唯一 `new` 位于 bounded queue 已拒绝的异常路径。此 bytecode/source evidence 不是 hardware allocation/CPU measurement。真实 client/network/reload/visual/Iris/Sodium/hardware performance Gate 仍为 **WAITING**。r11 历史 release 记录不构成 r13 binding；r13 的九文档 hash 冻结后才允许唯一一次 `buildRelease`，并须把 root XML 与 local-Maven XML 分开、独立解析 17 条 SHA256SUMS。

## r8 historical exact-long counterexample

在 r7 candidate `45d4f658ed625746d245c926583f2749cf9575b3` 的生产 runtime 尚未改动时，先将 reviewer 反例固化为永久 public-path regression，并以 Java 25.0.2、独立 Gradle home `D:\BlendLib-r8-gradle\x3-procedural-events` 串行执行。有效 red `04-red-exact-wrap-x3-provenance.log` / `xml-04-red-exact-wrap-x3-provenance` 为 `47 tests / 4 failures / 0 errors`：duration `Math.nextDown(2^-53)`、delta/speed `1` 的 exact quotient `9007199254740993` 被旧实现发为 `9007199254740994`，terminal 的 raw bits 应为 `0x1.0p-106`，X3 quiet-arm 后下一 marker crossing 也必须带同一 exact identity；exact `2^63` 不能 cast saturation，必须在 revision/latest snapshot/provenance 前 fail closed。相同 red XML 的 bounded exact-rational oracle 为 `270 cases / 26 finiteLongMismatches / 6 overflowAcceptances`。

后续实际 red 继续发现并固化 mutation transaction 边界：`09-red-long-maximum-frame-discontinuity.log` 为 `10 / 2 / 0`，证明 Long.MAX 后 direct command 与 rejection 会先污染 owner；`11-red-queued-command-transaction.log` 为 `12 / 1 / 0`，证明同一 captured queued prefix 的两个合法 group 可发生前组局部提交、后组溢出。它们不是编译失败：测试分别读取 retry、latest snapshot、owner controller 状态/sequence/counter 和 queue/backlog，要求 direct call 失败后可干净重试、queued prefix 失败后完整保留并稳定重复失败。automatic-next 进入 tiny LOOP、`Double.MIN_VALUE`/`nextUp` subnormal、multi-controller 后置 overflow、128-group/deferred 边界也被永久覆盖。

修复没有改变 public/protected surface 或旧四参数 snapshot constructor。r8 的 `observerLoopWrapCount` exact quotient 与 counter-overflow handling 是 narrow historical evidence，不能代表 every-frame atomicity。r9 candidate 以 `FrameStage` 取代“all outcomes are preflighted”的表述：它允许 disposable stage mutation，但把 live controller/backlog、revision、`latestSnapshot()` 和 captured FIFO prefix 的消费延后到 complete snapshot 和 capacity preparation 成功之后；`finally` 清理 captured/staged references。当前没有已测量的 per-instance storage 或 CPU allocation 数值可以写入本页。

### r8 implementation-side evidence（不是 fresh review verdict）

- `17-green-scratch-cleanup-focused.log` / `xml-17-green-scratch-cleanup-focused`：exact-long + real X3 provenance 的 `2 suites / 56 tests / 0 failures / 0 errors / 0 skipped`；包含 scratch-slot reflection cleanup。
- `18-full-blendlib-core-test-final-code.log` / `xml-18-full-blendlib-core-test-final-code`：`28 suites / 228 tests / 0 failures / 0 errors / 0 skipped`。
- `19-x2-eight-suite-matrix-final-code.log` / `xml-19-x2-eight-suite-matrix-final-code`：`8 suites / 51 tests / 0 / 0 / 0`。
- `20-focused-x3-procedural-client-final-code.log` / `xml-20-focused-x3-procedural-client-final-code`：`11 suites / 88 tests / 0 / 0 / 0`。
- `21-api-fabric-consumer-fixtures.log` / `xml-21-api-fabric-consumer-fixtures`：`4 suites / 9 tests / 0 / 0 / 0`。
- `23-root-check-final-code.log` / `xml-23-root-check-final-code`：只统计该 root `check --rerun-tasks` 实际执行的七模块，`113 suites / 819 tests / 0 failures / 0 errors / 0 skipped`（API 350、API consumer 7、core 228、Fabric client 152、Fabric common 21、Fabric consumer 2、showcase 59）；不混入 local-Maven fixture。`22` 的 Gradle stdout 虽显示成功，但 command-scoped XML copy 失败，故明确无效且不计入此数。
- `24-boundary-gradle-tasks.log`：core jar、Fabric consumer 与 showcase boundary tasks 为 `15 actionable tasks / 15 executed`。`25-source-jdeps-javap-classload-legacy-boundary.log`：r7/r8 `javap -public` 的 130 个 class、0 differing lines；api/core `jdeps` platform/client edges `0/0`；v2 forbidden I/O/parse、`Math.rint`/`BigInteger` hits 都为 0；independent classload/constant-pool 为 API `70/70`、core `234/234`、common `22`，forbidden entries/pool hits 皆 0；r6 legacy binary 仍 `LEGACY_4ARG_LINKED=true`。
- `26-bounded-performance-exact-wrap.log`：ordinary fast path 与 huge exact fallback 在各自 2-second JUnit timeout 内完成；huge path `truncated=true`、segments `0`、identity `629145600`，证明有界 CPU/segment 行为，但不等同 hardware performance Gate。

无效尝试保留以避免误计：`01` 仅 cold-download 到 100% 且无 Gradle 终态；`02/03` 为外层 wrapper，未得到可归属的完整 run；`08` compileTestJava accessor error 且 XML 是旧时间戳；`16` compile lambda error 且无 XML；`22` 如上为 copy/capture failure。只有 `04`、`09`、`11` 是本轮行为 red；它们的 mismatch/overflow acceptance 与局部提交在后续 green suite 中归零。旧 r8 轮次中提到的 `28/29` future artifact 名称不构成当前 binding，也不得作为已执行证据。本页所记录的 r9 implementation-side baseline 是 `r9-13-pre-doc-build-release`；最终 binding 仅由在冻结文档哈希生成后产生的外部 `#15 final-docs-bound-build-release` 与 `#16 final-release-sha` 决定；本文不预断其结果，artifact 缺失或不匹配即 binding 未证明。

上述均为 implementation-side JVM/source/package evidence。真实 client/network/reload/20-reload/visual/Iris/Sodium 仍为 WAITING；硬件 allocation/CPU/long-run 也仍为 WAITING，且没有可据以声称当前 staging storage shape 或 CPU cost 的硬件测量。它们不改变 independent r8 **FAIL（0 Critical / 0 High / 3 Medium / 1 Low）**、formal r9 **FAIL（0 Critical / 0 High / 1 Medium / 2 Low）** 或 fresh independent r10 review **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）** 的状态；formal r11 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，formal r12 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**；fresh independent r13 review 为 **PENDING**。

## r12 quaternion endpoint candidate（formal failure，不是 independent PASS）

formal r11 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**。Scratch 的 fixed weight-one/current==rest canonical sample 是 `[BEB66C14,00000004,BF4EABB7,3EF0DCEA]`；旧 stable public `Transform` publication 将其投影为 `[BEB66C13,00000004,BF4EABB6,3EF0DCE9]`。r12 试图以 double length² 的 T10 branch raw-preserve public `Transform`，却构成 formal r12 的 Medium：未经批准改变 stable public value semantics。r12 Low 还包括把 `9.000002953` 写成上界；精确保守值是 `9.000003072433528` ULP，且 r12 evidence 未绑定三份 code/test hash 与 artifact。上述均为历史 r12 文档/治理错误，不是 r13 证明。

r12 的 VALID RED、Green XML、三 code/test hash、full-core、X2/X3 matrix、consumer、root check 与 Java 25 oracle 都保留为 implementation-side historical artifacts；它们不将 T10 升格为契约，也不能覆盖 formal r12 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**。r11 numeric advisor 的 normal `sumThree` 1 ULP 与 signed-zero 仍是 nonblocking observations，未升级为 public raw-bit contract。历史 timeout/compile wrapper 均保留为 INVALID/INCOMPLETE，不能替代 terminal evidence。

## r13 stable `Transform` publication repair（不是 independent review verdict）

r13 的设计不批准 T10 或 raw-preserving public `Transform`。唯一 public value rule 回到 parent stable semantics：`new Transform(...).rotation()` 是输入 rotation 的 `Quaternion.normalized()` projection。X2/X3 exact endpoint 分层：Scratch 保存 canonical raw sample，fully-public runtime 发布 stable projection；前者不是 public raw-bit promise。固定 r11 sample、`[0,0,0,0x3F800004] -> 0x3F800000`、q/-q、signed zero、subnormal、permutation、current!=rest、override 与 prior-additive 的 deterministic/rotation-equivalence/general/atomic controls 都是永久测试。

### r13 VALID RED

所有 r13 工件位于 `D:\BlendLib-r13-artifacts-20260809`，隔离 home 是 `gradle-user-home`。生产未改的单链命令为：`./gradlew.bat :blendlib-core:test --tests com.liy.blendlib.core.CoreValueContractsTest --tests com.liy.blendlib.core.animation.v2.AnimationV2FrameAtomicityTest --rerun-tasks --no-daemon --max-workers=1 --console=plain`。首次外层 watchdog 工件按 `01-red-focused/INVALID.md` 保留，不能计为行为 RED；同一命令的完成 XML 是 VALID RED：Core `6 tests / 2 failures / 0 errors / 0 skipped`，SHA-256 `8ADF9B9DB2A792EA6B785681A1B40B8CD922E17518274D5853680B8167FAF2AA`；Frame `19 / 1 / 0 / 0`，SHA-256 `68AE75D15CCAA89858EF49534D01A9A27CDD24916A6949F6CE81268F47C00AF5`。失败分别证明 public `0x3F800004` 没有 stable projection、100k deterministic projection 不一致，以及 fully-public fixed endpoint 仍发布 raw bits；Scratch direct raw assertion 自身通过。

### r13 VALID GREEN 与冻结输入 hash

恢复 stable `Transform` 后，同一 command 的 `02-green-focused` XML 为 Core `6 / 0 / 0 / 0`（SHA-256 `8B984444BFAF405CACD1A6DF038AA91E7707C47653213E6695D4AD1F2B843F80`）与 Frame `19 / 0 / 0 / 0`（SHA-256 `6537DFC720444F8F4338A6613B0B70A59935D2E28189FAD38483C3972E7FF802`）。冻结输入 hash 为 `Transform.java` `D7160B4582A4964FA1E0815A47E36B22E4D60F091EF69CB4A16A0B3DD98106B4`（blob `a73df9f7a3e7a00ab730954e221b83bd8e93a599`，与 `1a6ce8fc` parent 逐字节相同）、`CoreValueContractsTest.java` `903D5B8A0F80219EEE5B7FF5699F30AAF5B7176F252BF0F5A73225AE2F18BBFB`、`AnimationV2FrameAtomicityTest.java` `E7B25FE91BF995F8AFB33EDD74FF0D5FA6108D0C16D8686C7C7F7DBDEDDFDC97`。`Quaternion.java` 不变。

### r13 final-code gates 与 release binding

冻结输入 hash 下的 final-code gates 均已完成且各命令 XML 为 `0 failures / 0 errors / 0 skipped`：`03-expanded-focused` 为 `9 suites / 116 tests`，`04-full-core` 为 `29 / 255`，`05-x2-task-bound` 的三条独立命令为 core `3 / 26`、common `2 / 11`、client `3 / 14`（合计 `8 / 51`），`06-x3-task-bound` 为 `11 / 93`，`07-api-fabric-consumers` 为 `4 / 9`，`08-root-check` 严格只计七个 root 模块为 `114 / 846`。各 command、stdout、stderr、exit 和 command-scoped XML 都保存在 `D:\BlendLib-r13-artifacts-20260809\03-expanded-focused` 至 `08-root-check`，不是 independent review verdict。

Java 25.0.2 dual-baseline evidence 位于 `D:\BlendLib-r13-artifacts-20260809\09-java25-dual-baseline`：candidate 与 detached stable `1a6ce8fc` 各完成 `1,000,000` public `Transform` normalized-projection cases、`2` fixed Scratch/raw-to-public projection cases，且各为 `8,800,000` allocated bytes per `100,000` probe cases；e640 的观察值仍是 T10 raw public `0x3F800004`。candidate/stable `Transform.java` SHA-256 均为 `D7160B4582A4964FA1E0815A47E36B22E4D60F091EF69CB4A16A0B3DD98106B4`、blob 均为 `a73df9f7a3e7a00ab730954e221b83bd8e93a599`，并与 parent `1a6ce8fc` diff 为零；e640 source SHA-256 是 `48F4D43CF0EE335D9A5E053963A9364D2399B6C14696DF9AF8B2436B7A210C85`、blob `cd2cd5bcb640d50346d643ad8b60066116e13dec`。`Quaternion.java` 在 candidate/e640/stable 三侧均为 SHA-256 `290E42C3DD9973DFA0B0841B99BCA7476E05D16F43137D00DB570CCB3A0011B0`；class inventory/classload 都是 `308 / 308 / 308`，jdeps forbidden count 都是 `0 / 0 / 0`，candidate/stable public surface 与 constant-pool diffs 均为零，legacy e640-compiled consumer 在 candidate 成功输出 `LEGACY_E640_CONSUMER_LINKED=true`。原始 legacy classpath wrapper、以及 static-boundary 脚本的 PowerShell hash-expression 失败都保留为 **INVALID**；它们不是行为或 API failure，也没有被计作成功。有效的原始 probe、javap/jdeps、inventory、classload 与 constant-pool outputs 仍完整保留。

这些 final-code gate 是 implementation-side evidence，不能写为 r13 final-gate PASS 或 independent review PASS。九份文档 hash 冻结后才允许唯一一次预留编号 `10-docs-bound-build-release` 的 `buildRelease --rerun-tasks --no-daemon --max-workers=1 --console=plain`；本段冻结时该 release 尚未执行，之后其 root XML、独立 local-Maven XML、17/17 SHA 与 release artifact 必须只读独立核验。fresh independent r13 review 保持 **PENDING**；真实 client/network/reload/20-reload/visual/Iris/Sodium/hardware allocation/CPU/long-run 仍为 **WAITING**。

## r7 fresh-r6 counterexample：permanent red 后的 exact clip-duration candidate

在 r6 candidate `27f84c30baa4901bc5437c5881553074e49b2cb6` 的生产 runtime 尚未改动时，r7 先新增 X2/X3 permanent regression，并以独立 Gradle home `D:\BlendLib-r7-gradle\x3-procedural-events` 执行：

```powershell
.\gradlew.bat :blendlib-core:test `
  --tests 'com.liy.blendlib.core.animation.v2.AnimationV2RuntimeTest' `
  --tests 'com.liy.blendlib.core.procedural.ProceduralRigContractsTest' `
  --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

预期红灯实际为 `50 tests completed, 2 failed`，完整 stdout/stderr：`D:\BlendLib-r7-artifacts-20260807\01-red-exact-duration-boundaries.log`。第一项是 reviewer 的精确事实：duration `0x1.0p-2`（`0.25`）、input `Math.nextDown(duration)` = `0x1.fffffffffffffp-3`（`0.24999999999999997`）；旧 owner 把 playhead 错置为 `0x0.0p0`，而不是保留 nextDown。第二项从 exact X2 runtime 驱动 X3 marker，永久要求 `nextDown` 时 event count `0`、后续 `duration-nextDown` 时恰为 `1`，并检查 segment、terminal anchor、epoch/occurrence、source revision 与 replay fence。

额外 adversarial red：duration `1.0D / 3.0D`、input `Math.nextDown(1.0D)`。`input / duration` 的 double quotient 向上为 `3.0`，但 `input % duration` 是非零 remainder，真实只完成两个 wrap 加 partial；旧单独 `floor(time / duration)` 会提前增加 epoch/occurrence。日志 `D:\BlendLib-r7-artifacts-20260807\03-red-nonbinary-modulo-wrap.log` 为 `1 test completed, 1 failed`。同一永久测试还覆盖 exact `1.0D` 与 `Math.nextUp(1.0D)`。

r7 的最小修复只收紧 X2 playhead/observer traversal boundary：正的微小 residual 仍推进，strictly-sub-duration candidate 不 snap，exact duration 仅跨一次，`nextUp(duration)` residual 进入下一 LOOP/automatic-next state；LOOP completed-wrap count 从与 terminal 相同的 `% duration` 事实推导，不能直接 floor quotient。`EPSILON` 保留在 clip compatibility、transition/interpolation 与 quaternion 等非 timeline 数值域。新增永久测试还覆盖非零 start near-end、LOOP/ONCE/HOLD/automatic-next、speed `2x`、zero/`Double.MIN_VALUE` residual、X3 provenance/replay；既有 `visualEventObserverBoundsMultiLoopTraversalAndArmsLateObserversWithoutHistoryBurst` 继续覆盖 multi-loop 及 128-segment truncation/fail-closed。

已完成的 r7 implementation-side evidence（不是 review verdict）：

- 定向 exact X2 + X3：`51 / 0 failures / 0 errors / 0 skipped`，`D:\BlendLib-r7-artifacts-20260807\04-focused-exact-duration-and-modulo-green.log`。
- 完整 core：`27 suites / 214 tests / 0 / 0 / 0`，`05-core-full-green.log`。
- X2 compatibility/determinism：`8 suites / 51 tests / 0 / 0 / 0`，完整 stdout/stderr 为 `06b-x2-eight-suite-green-complete.log`；最初的 `06-x2-eight-suite-green.log` 被工具外层输出截断，不能作为完整成功证据，故由 `06b` 替代。
- API + Fabric consumer fixture：`4 suites / 9 tests / 0 / 0 / 0`，`07-api-fabric-consumer-fixtures-green.log`。
- root `check --rerun-tasks`：35 actionable tasks；**只汇总该命令实际执行的七个模块**，canonical XML 为 `112 suites / 805 tests / 0 failures / 0 errors / 0 skipped`，`08-root-check-r7-green.log`。它不包含 `blendlib-local-maven-consumer-fixture` 的 `2 suites / 5 tests`。
- pre-documentation `buildRelease --rerun-tasks`：62 actionable tasks；含 local-Maven fixture 后为 `114 suites / 810 tests / 0 / 0 / 0`，`09-build-release-r7-pre-doc-green.log`。
- initial documentation writeback 后的 `buildRelease --rerun-tasks`：62 actionable tasks；canonical XML 为 `114 suites / 810 tests / 0 failures / 0 errors / 0 skipped`，完整 stdout/stderr 为 `14-build-release-r7-final-doc-bound.log`，每命令独立 XML 位于 `xml-14-build-release-r7-final-doc-bound-canonical`。其中仅 release 含 local-Maven consumer 的 `2 suites / 5 tests`；因此它不能混入前述 root `check` 的 `112 / 805`。
- 该 release tree 的独立 SHA-256 复算为 `17 parsed / 17 unique paths / 0 malformed / 0 duplicate paths / 0 missing / 0 mismatches`，`15-release-sha17-r7-final-doc-bound.log`。
- r7 source/jdeps boundary：core/client procedural source 为 `39 / 11` Java files、forbidden hits `0 / 0`；唯一命名 mutable X2 runtime 的文件仍为 `ProceduralRigRuntime.java` 与 `ProceduralVisualEventTimelineRegistry.java`，registry 只读 `latestSnapshot,plan`，mutation hits `0`。Java 25 `jdeps` 为 core/client `73 / 11` classes，forbidden edges `0`，`16-procedural-source-jdeps-boundary-r7.log`（raw 为 `16a`、`16b`）。
- r7 javap/public-signature boundary：旧四参数 `AnimationV2EvaluationSnapshot` public constructor 为 `1`，六个 observer types 的 public constructors 为 `0`；registry bytecode 为 `plan=2 / latestSnapshot=1 / X2 mutator=0`，API main sources 的 `AnimationV2` refs `0`，本轮两个 production file 的 public/protected declaration diff `0`，`17-javap-api-public-signature-r7.log`。
- 隔离 URLClassLoader + constant-pool probe：core `234 / 234` classes discovered/loaded+resolved、core platform entry/pool hits `0 / 0`、Fabric common client entry/pool hits `0 / 0`，`18-classload-boundary-r7.log`；独立 `:blendlib-core:verifyCoreJarBoundary --rerun-tasks` 为 `4 actionable tasks: 4 executed`，`19-core-jar-boundary-r7.log`。r6 的 legacy binary fixture 在当前 API/core jars 仍输出 `LEGACY_4ARG_LINKED=true`、revision `7`、observer controllers `0`，`20-legacy-binary-compatibility-r7.log`。
- `22/23` 是 boundary-documentation writeback 后的 prior binding check：`buildRelease --rerun-tasks` 为 62 actionable tasks，canonical XML `114 suites / 810 tests / 0 failures / 0 errors / 0 skipped`，`22-build-release-r7-final-boundary-doc-bound.log` / `xml-22-build-release-r7-final-boundary-doc-bound-canonical`；独立 SHA 为 `17 / 17 / 0 malformed / 0 duplicate / 0 missing / 0 mismatches`，`23-release-sha17-r7-final-boundary-doc-bound.log`。
- `26/27` 是本页随后一次 documentation-bound 的 prior binding check：同为 62 actionable tasks、canonical XML `114 / 810 / 0 / 0 / 0` 与独立 SHA `17 / 17 / 0 / 0 / 0`，`26-build-release-r7-final-document-bound.log` / `xml-26-build-release-r7-final-document-bound-canonical` / `27-release-sha17-r7-final-document-bound.log`。
- 本页这一次固定 final writeback 后的最终 `buildRelease --rerun-tasks` 使用 artifact `30-build-release-r7-final-evidence-bound.log`，canonical XML 为 `114 suites / 810 tests / 0 failures / 0 errors / 0 skipped`；该命令在本文件最后写入之后执行，每命令独立 XML 位于 `xml-30-build-release-r7-final-evidence-bound-canonical`。
- 同一最终 evidence-bound release tree 的独立 SHA-256 复算使用 `31-release-sha17-r7-final-evidence-bound.log`，结果为 `17 parsed / 17 unique paths / 0 malformed / 0 duplicate paths / 0 missing / 0 mismatches`。

以上均为 r7 implementation-side evidence，不能抵消其随后 fresh independent **FAIL（1 Medium）**；真实 client/network/reload/visual/performance 也仍为 WAITING。

## r6 fresh-r5 counterexample：先 red，再实现候选修复

在未修改 r6 生产代码的基线 `2ff27a67b944c56a886860b72374c191790552d3` 上，先把 reviewer 的三个 Medium 扩展为永久测试，并真实执行：

```powershell
.\gradlew.bat :blendlib-core:test `
  --tests 'com.liy.blendlib.core.procedural.ProceduralRigContractsTest' `
  --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

结果为预期红灯：`39 tests completed, 6 failed`，日志为 `D:\BlendLib-r6-artifacts-20260807\01-red-procedural-contracts-r6.log`。六项是同三条 Medium 的永久边界扩展：

- X2 `(start,end]` 的 `nextDown/end/nextUp` 和 `0.2500000005`：旧 `1e-9` epsilon 使尚未跨越的 marker 提前发出；duration `0.25` 之外 `0.2500000005` 也被错误接受。
- arm@0 后一/多次 skipped X2 publication 的 history catch-up、zero-movement revision anchor、超过 128 real segment 时 fail closed、以及 failed X3 frame 的 same-exact-snapshot retry。
- strict-v1 已接受的 `(1,1.000005,1)` 与 `(1e-7,1.09e-6,1e-7)` identity scale：旧实现以 X 轴复制到三轴。

r6 implementation candidate 的最小结构改动为：

- X2 每 controller 在 immutable exact snapshot 内冻结 bounded continuation。每条 non-empty publication 带实际 revision、真实 segment 和前后 `(timeline,time,revision range,loopEpoch,occurrence)` anchor；no-movement revision 合并到 anchor range，最多保留 128 real forward segments。被剪掉 anchor、truncation、command/rejection discontinuity 或任何不精确连续性均不能被 X3 猜测。
- X3 的 committed observation 使用上述全部事实定位 anchor，逐 publication replay budget 内 skipped crossing；找不到 exact anchor 一律 `VISUAL_EVENT_PROVENANCE` + nonpublishable，失败 frame 不更新 observation/replay，明确恢复需 retire/recreate X3 runtime 后由新 scope arm latest。
- marker 与 provenance 都采用相同的 exact `(start,end]`：duration/segment bound 去除 epsilon，`end` 一次、`nextUp(end)` 延至真实后续 traversal；`composeOffset` 对 base scale 三轴各自相乘，最终仍只由 `Transform` strict-v1 作 absolute validity 判断。

已完成的 r6 implementation-side JVM evidence（不是 review verdict）：

- 定向 `ProceduralRigContractsTest`：`41 / 0 failures / 0 errors / 0 skipped`，`D:\BlendLib-r6-artifacts-20260807\14-focused-procedural-contracts-r6-final-green.log`；它额外以反向输入构造两个 controller，验证每个 controller 的 skipped/current publication 都保留真实 revision，输出仍是 canonical 顺序；timeline 起点 marker 仍由 catalog 拒绝，duration 等号则允许。
- 完整 core：`211 / 0 / 0 / 0`，`D:\BlendLib-r6-artifacts-20260807\15-core-full-r6-final-green.log`。
- X2 compatibility/determinism：`8 suites / 49 tests / 0 / 0 / 0`，`D:\BlendLib-r6-artifacts-20260807\09-x2-eight-suite-compatibility-r6.log` 与 `10-x2-eight-suite-xml-summary-r6.log`。
- API + Fabric consumer fixture：`4 suites / 9 tests / 0 / 0 / 0`，`D:\BlendLib-r6-artifacts-20260807\11-api-fabric-consumer-fixtures-r6.log` 与 `12-api-fabric-consumer-xml-summary-r6.log`。
- root `check --rerun-tasks`：35 actionable tasks 成功；**fresh command-scoped** canonical XML 为 `112 suites / 802 tests / 0 failures / 0 errors / 0 skipped`，`D:\BlendLib-r6-artifacts-20260807\16-root-check-r6.log`。先前 `17-root-check-xml-summary-r6.log` 错把 root check 未执行的 local-Maven `2 suites / 5 tests` 混入，故其 `114 / 807` 不能再作为 root-check 数字。
- `buildRelease --rerun-tasks`：62 actionable tasks 成功；结束后 canonical XML 仍为 `114 / 807 / 0 / 0 / 0`，`D:\BlendLib-r6-artifacts-20260807\18-build-release-r6.log` 与 `20-build-release-xml-summary-r6.log`。
- 独立 release SHA256：`17 entries / 17 unique / 0 malformed / 0 duplicate / 0 missing / 0 mismatch`，`D:\BlendLib-r6-artifacts-20260807\19-release-sha-independent-r6.log`。
- source/bytecode/classload/API boundary：core/client forbidden import scan `0/0`、唯一 X2 runtime reader 为 `ProceduralRigRuntime` 与 `ProceduralVisualEventTimelineRegistry`、Java 25 `jdeps` `73/11` class 且 `0` forbidden、保留 4-arg snapshot public constructor `1`、六个 observer 类型 public constructor `0`、registry `plan=2/latestSnapshot=1/mutator=0`；classload/source-boundary Gradle gate 18 actionable tasks 成功。日志：`21-procedural-source-jdeps-boundary-r6.log`、`22-procedural-javap-api-boundary-r6.log`、`23-procedural-classload-source-boundary-r6.log`。

以上均为 implementation-side evidence，不能推翻 fresh independent r6 的 **FAIL（1 Medium + 2 Low）**；真实 client/network/reload/visual/performance 也仍为 WAITING。

## r5 formal-r1 counterexample：先 red，后实现候选修复

生产代码未改动时，新增四个永久 `ProceduralRigContractsTest` 回归后实际执行：

```powershell
.\gradlew.bat :blendlib-core:test `
  --tests 'com.liy.blendlib.core.procedural.ProceduralRigContractsTest' `
  --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

结果为预期红灯：`32 tests completed, 4 failed`。四项分别覆盖 LOOP wrap、automatic-next leftover entry、same-snapshot failed-frame retry / command seek、合法 strict-v1 scale identity 及 underflow/overflow、以及 duplicate descriptor 在 dedup 前超出 raw visit budget。日志：`D:\BlendLib-r5-artifacts-20260807\02-red-procedural-contracts-warm.log`。

r5 implementation candidate 的变更为：

- X2 owner snapshot 增加 immutable、每 controller 最多 128 段的 automatic observer traversal；LOOP 和 automatic `next` 发出真实 segment，occurrence/loop epoch 单调，command/rejection 为 no-event discontinuity，trace truncation 明确诊断并由 X3 fail closed。
- X3 只接受 exact `latestSnapshot()` object 的 matching terminal trace；late first observation 只 re-arm，failed frame 不在 final commit 前消耗 revision/observer cursor/replay identity。
- `composeOffset` 只约束 multiplier；final scale 仍须 finite/positive，zero-underflow、overflow 与 non-finite fail closed。
- attachment graph 在 child dedup 前以全图 4096 raw descriptor visit 硬限制读取；owner、edge 与 active claim 限制保持独立。

## r5 implementation-side 自动门禁（不是 review verdict）

已执行：

```powershell
.\gradlew.bat :blendlib-core:compileJava :blendlib-core:compileTestJava `
  --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :blendlib-core:test `
  --tests 'com.liy.blendlib.core.procedural.ProceduralRigContractsTest' `
  --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :blendlib-core:test --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :blendlib-fabric-common:test :blendlib-fabric-client:test `
  --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :blendlib-core:test :blendlib-fabric-common:test :blendlib-fabric-client:test `
  --tests '*AnimationV2ReviewerRegressionTest' `
  --tests '*AnimationV2RuntimeTest' `
  --tests '*AnimationV2SourceBoundaryTest' `
  --tests '*AnimationIntentReconcilerTest' `
  --tests '*AnimationIntentBoundaryTest' `
  --tests '*ClientAnimationV2ReviewerRegressionTest' `
  --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :blendlib-api-consumer-fixture:check :blendlib-fabric-consumer-fixture:check `
  --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat check --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat buildRelease --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

compile 为 `BUILD SUCCESSFUL`（日志 `D:\BlendLib-r5-artifacts-20260807\03-compile-after-production-edits.log`）。定向 suite 在当前 r5 candidate 中为 `34 tests / 0 failures / 0 errors / 0 skipped`（日志 `D:\BlendLib-r5-artifacts-20260807\12-focused-procedural-contracts-post-epsilon.log`）；完整 core 与 Fabric common/client suite 也通过（`08-core-full-test-r5.log`、`09-fabric-common-client-test-r5.log`）。永久覆盖包含 LOOP tail/post-wrap 与 multi-loop epoch/occurrence、automatic-next entry、late first observation、command seek/state-change discontinuity、same-snapshot retry、observer truncation、旧 public 四参数 snapshot constructor、raw duplicate/infinite iterator 和 claim 不泄漏。

本候选随后完成完整自动门禁：root `check --rerun-tasks` 为 `BUILD SUCCESSFUL`、`35 actionable tasks: 35 executed`（`13-root-check-r5.log`）；写回本节后再执行的 root `buildRelease --rerun-tasks` 为 `BUILD SUCCESSFUL`、`62 actionable tasks: 62 executed`（`25-build-release-r5-post-evidence.log`），包含 core jar boundary、API/Fabric consumer fixture、local-Maven consumer、release inventory/negative fixture、documentation/runtime archive 与末尾 SHA verifier。r5 root/release XML 汇总为 `114 suites / 799 tests / 0 failures / 0 errors / 0 skipped`，其中 local-Maven consumer 为 `2 / 5 / 0 / 0 / 0`（`27-root-release-xml-summary-r5.log`）。直接运行的 X2 compatibility matrix 为 `8 suites / 48 tests / 0 failures / 0 errors / 0 skipped`（`21-x2-regression-matrix-r5.log`、`22-x2-regression-xml-summary-r5.log`）；两项 consumer fixture 为 API `3 / 7`、Fabric `1 / 2`，合计 `4 suites / 9 tests / 0 failures / 0 errors / 0 skipped`（`23-api-fabric-consumer-fixtures-r5.log`、`24-api-fabric-consumer-xml-summary-r5.log`）。

release 的独立 SHA-256 复算为 `17 entries / 17 unique / 0 malformed / 0 duplicate / 0 missing / 0 mismatch`（`26-release-sha-independent-post-evidence-r5.log`）。独立 boundary audit 也已重跑：source scan 为 core `39` 文件、client `11` 文件、platform/I/O forbidden hits 均 `0`，仅 `ProceduralRigRuntime.java` 和 `ProceduralVisualEventTimelineRegistry.java` 名称化 X2 runtime，registry 仅调用 `plan`/`latestSnapshot` 且 mutation hits `0`（`19-procedural-source-boundary-r5.log`）；Java 25 `jdeps` 为 core procedural `73` class、client procedural `11` class、forbidden hits `0`（`18-procedural-jdeps-r5.log`）；Java 25 `javap` 保留恰好一个旧 public 四参数 `AnimationV2EvaluationSnapshot` constructor，observer trace 无 public constructor，registry 的 plan/latestSnapshot bytecode read 与 X2 mutator hits `0`（`20-procedural-javap-boundary-r5.log`）。

这些是 implementation-side 自动证据，不是 self-review verdict。formal r1 的 FAIL 不因此撤销；fresh independent r5 source review 的实际结论是 **FAIL（3 Medium）**。真实 client/reload/visual/performance/Iris/Sodium 与未来网络 Gate 也继续 `WAITING`。

## r2 remediation 的历史定向证据

当前 worktree 的 r2 定向命令：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
$env:Path="$env:JAVA_HOME\bin;$env:Path"

.\gradlew.bat :blendlib-core:test --tests 'com.liy.blendlib.core.procedural.ProceduralCoreSourceBoundaryTest' --tests 'com.liy.blendlib.core.procedural.ProceduralRigContractsTest' --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :blendlib-fabric-client:test --tests 'com.liy.blendlib.fabric.client.procedural.ProceduralPresentationContractsTest' --tests 'com.liy.blendlib.fabric.client.procedural.DeterministicTwoBoneIkSolverTest' --tests 'com.liy.blendlib.fabric.client.procedural.ProceduralClientSourceBoundaryTest' --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

两条命令均已 `BUILD SUCCESSFUL`。它们覆盖：

- H1：public marker catalog + exact bound X2 runtime/`latestSnapshot()` identity、actual controller/state/playhead crossing、raw/copy snapshot/empty interval negative、failed frame 不消耗 crossing、七类 typed event 与 client void consumer dispatch。
- H2：snapshot 的 opaque plan identity，拒绝同 model/generation/cardinality 但 hierarchy 不同的 plan/snapshot 混用。
- H3/M2：complete graph compile + publish、同 generation 分别编译 `A→B`/`B→A` 后的组合拒绝、foreign graph/generation reject、missing owner、8/9 edge、20k controlled failure。
- M1：custom map canonical key order、complete payload dedup key，以及 65 UTF-16 code-unit field text fail closed。
- 仅 `ProceduralRigRuntime` 与 private registry 允许 direct X2 runtime type；source + `javap` bytecode 固定只读 `plan()` / `latestSnapshot()`，拒绝 advance/queue/submit/intent mutation。

r2 当时还实际执行了：

```powershell
.\gradlew.bat :blendlib-core:test :blendlib-fabric-client:test --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :blendlib-core:test :blendlib-fabric-common:test :blendlib-fabric-client:test --tests '*AnimationV2ReviewerRegressionTest' --tests '*AnimationV2RuntimeTest' --tests '*AnimationV2SourceBoundaryTest' --tests '*AnimationIntentReconcilerTest' --tests '*AnimationIntentBoundaryTest' --tests '*ClientAnimationV2ReviewerRegressionTest' --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

两条均为 `BUILD SUCCESSFUL`。第一条的当前 XML 为 core 27 suites / 187 tests、client 39 suites / 152 tests，均为 0 failures / 0 errors / 0 skipped；X3 `ProceduralRigContractsTest` 为 19 tests，`ProceduralCoreSourceBoundaryTest` 为 2 tests，client presentation fixture 为 2 tests。第二条是 X2 compatibility 的完整 core/common/client 定向矩阵，17 actionable tasks，0 test failure。

r2 当时还执行并通过了 root `check --rerun-tasks --no-daemon --max-workers=1 --console=plain`（35 actionable tasks）及 `buildRelease --rerun-tasks --no-daemon --max-workers=1 --console=plain`（62 actionable tasks）。后者覆盖 API/Fabric consumer fixture、local Maven consumer、release inventory/negative fixture、documentation/runtime archive 与 release SHA verification。文档写回后，增量 `buildRelease --no-daemon --max-workers=1 --console=plain` 也通过（62 actionable tasks），并对 `SHA256SUMS` 的 17 个条目独立复算为 0 malformed / 0 missing / 0 mismatch。这些都是历史 JVM/package evidence。真实 Minecraft Gate 从未运行，历史 release 数字亦不能替代 fresh independent r5 source review。

## r1 historical Red -> fix -> green

| 原始复现 | 修复 | 复验 |
|---|---|---|
| core `ProceduralRigContractsTest` 14 tests 中 5 个红项：同 model/generation sibling instance、倒退 revision、caller-order dependent rotation、caller-supplied child ancestry、event replay/unchecked batch、diagnostic overflow | runtime 构造时 exact-`ModelInstance` scope + monotonic revision watermark；complete canonical operation key/q-sign normalization；`ProceduralAttachmentGraph`；private batch + provenance/replay fence；error-latched diagnostic collector | core X3 matrix 16 tests, 0 failures/errors |
| client `DeterministicTwoBoneIkSolverTest` 4 tests 中 1 个红项：unrelated bones 和 caller-injected parent transforms 能形成伪链 | `ClientIkRigSnapshot.capture(plan, snapshot)` 导出 hierarchy/parent transforms；request/result constructors hidden；solver require `root -> middle -> end`；result exact two rotations | client X3 matrix 8 tests, 0 failures/errors |

红测命令（修复前）为：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :blendlib-core:test --tests 'com.liy.blendlib.core.procedural.ProceduralRigContractsTest' --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :blendlib-fabric-client:test --tests 'com.liy.blendlib.fabric.client.procedural.DeterministicTwoBoneIkSolverTest' --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

两条命令按预期失败，分别得到 5 和 1 个上述红项；不存在“先绿后改”的记录。

## r1 historical 已执行的 green 命令

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
$env:Path="$env:JAVA_HOME\bin;$env:Path"

.\gradlew.bat :blendlib-core:test --tests 'com.liy.blendlib.core.procedural.*' --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :blendlib-fabric-client:test --tests 'com.liy.blendlib.fabric.client.procedural.*' --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :blendlib-core:test :blendlib-fabric-client:test --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :blendlib-core:check :blendlib-fabric-client:check --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat check --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat buildRelease --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat buildRelease --no-daemon --max-workers=1 --console=plain
```

这组历史 Gradle 任务报告 `BUILD SUCCESSFUL`。最终 root `check` 为 35 actionable tasks、42 秒；无缓存 `buildRelease` 为 62 actionable tasks，并覆盖 local Maven consumer、release inventory/negative fixture、documentation/runtime archive 与 `verifyReleaseSha256`；最终 cached `buildRelease` 再次通过（62 tasks，10 秒）。构建输出只有既有 serial/deprecation、LWJGL native-access 和 Javadoc warning；这些数字不是未提交 r2 remediation 的 release 证据。

历史 r1 XML 汇总为 114 suites、776 tests、0 failures、0 errors、0 skipped。其 X3 定向矩阵为：

| scope | suites | tests | failures/errors |
|---|---:|---:|---:|
| `com.liy.blendlib.core.procedural` | 3 | 16 | 0 / 0 |
| `com.liy.blendlib.fabric.client.procedural` | 3 | 8 | 0 / 0 |
| core module total | 27 | 181 | 0 / 0 |
| client module total | 39 | 151 | 0 / 0 |

## 覆盖矩阵

| 证据 | 覆盖内容 |
|---|---|
| `ProceduralRigContractsTest` | exact instance scope、equal/rollback revision、q/−q/`-0.0` canonicalization、真实 child edge cycle 与 8/9-depth、2,000 个不同 caller permutation、overflow error retention、event provenance/conflict/replay/new loop、attachments/socket/visibility、owner thread、hostile iterable 与并发 immutable reads。 |
| `ProceduralRigRuntimeTest` | `Offset -> LookAt -> final socket` 的 final-pose 顺序。 |
| `ProceduralCoreSourceBoundaryTest` | core procedural 源码无 platform/I/O/provider discovery/X2 runtime mutation path。 |
| `DeterministicTwoBoneIkSolverTest` | reachable endpoint golden、far/near clamp、pole singular fallback、unrelated hierarchy reject、request/result construction fence、0/1/3/wrong-operation output rejection。 |
| `ProceduralPresentationContractsTest` | 三类 attachment 的 canonical presentation、七类 typed event 的 void dispatch、callback isolation。 |
| `ProceduralClientSourceBoundaryTest` | client procedural 源码无 platform/I/O/network/X2 runtime mutation 或 mutable global；IK request/result 无 public constructor。 |

额外独立 bytecode/source audit：对 core 63 个 procedural `.class` 与 client 11 个 procedural `.class` 运行 Java 25 `jdeps --multi-release 25 --ignore-missing-deps -verbose:class`；`net.minecraft`、`net.fabricmc`、LWJGL、Blaze3D 和 `not found` 命中均为 0。相同 procedural source 的 platform/I/O/runtime-mutation forbidden scan 也为 0。`build/release/SHA256SUMS` 有 17 条记录，使用独立 `Get-FileHash -Algorithm SHA256` 重算为 0 malformed、0 missing、0 mismatch。

## 仍然 WAITING

- 真实单客户端与双客户端 visual evidence：bone/slot/socket、三类 attachment、七类 event、hidden/missing anchor、IK reachable/clamped/singular。
- 真实 lifecycle/reload：generation replacement、rescope/disconnect、late callback、旧 snapshot/replay fence retire、20-reload。
- Iris/Sodium、CPU fallback、GL state leakage、真实 texture/material/variant 组合与硬件 allocation/CPU/long-run benchmark。
- 若未来引入网络：独立 version/unknown/malformed/oversize/scope/sequence negative tests，并证明 v1 payload 不受影响。

这些 Gate 没有被 JVM/source/release evidence 解除；X3 仍为 Experimental candidate。历史结论保持准确：formal r1 为 **FAIL（3 Medium + 1 Low）**，fresh r5 为 **FAIL（3 Medium）**，fresh r6 为 **FAIL（1 Medium + 2 Low）**，fresh r7 为 **FAIL（1 Medium）**，independent r8 为 **FAIL（0 Critical / 0 High / 3 Medium / 1 Low）**，formal r9 为 **FAIL（0 Critical / 0 High / 1 Medium / 2 Low）**，fresh independent r10 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，formal r11 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，formal r12 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**；当前 r13 repair 的 fresh independent review 为 **PENDING**。
