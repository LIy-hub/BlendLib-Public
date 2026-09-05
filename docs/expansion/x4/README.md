# X4：26.1.2 新宿主适配器

状态：**R14 final source review 为 PASS；随后 formal integration 的独立 completion-order probe 在 8 个 JVM 中稳定复现 96/96 race，因此 formal integration 仍为 BLOCKED。R15 依次完成 R3 FAIL（0C/4H/2M/0L）、R4/R5 non-verdict design、valid RED（6 tests / 1 intended assertion failure）以及冻结 source candidate 的 pre-documentation source/test PASS（0C/0H/0M/0L）与 static/binary PASS。** 当前七文档 reconciliation 仍等待独立 docs/full-source review，不是 shared runtime integration、release 或真实客户端 Gate 通过。manual client、network、reload、visual、Iris/Sodium、hardware、performance、shared bootstrap/renderer evidence 均为 WAITING。

X4 把新宿主限制在 `blendlib-fabric-client` 的 `host` 包。它不改变 X1 的三项 `HostKind`，不改 common/server、entrypoint、metadata、build 配置或既有 renderer 注册。所有实际渲染都经已有的 `ClientModelLookup` 与 `BlendRenderer`：在 prepare/extract 固定不可变 `ModelRenderSnapshot`，submit 只消费该 snapshot。每个 factory 还必须显式接收 integration owner 已 publish 的 X1 `ProviderLifecycleSession`；X4 对每个 prepared snapshot 只持有该 session 给出的真实 `ProviderLease`，绝不自造布尔型 lease，也不拥有或 retire 该共享 session。

## r12 X1 every-Error / X4 lifecycle repair candidate

r12 对齐 X1 1.1 的 every-`Error` 选择规则：ordinary primary 加任意 cleanup `Error` 时选择 cleanup `Error`；两个 `Error` 时保留先发生的原对象；两个 ordinary failure 时保留 primary。不同对象只作为 best-effort suppressed evidence。suppression-disabled、duplicate-object 和 ordinary suppression bookkeeping failure 都不能替换已选 terminal；bookkeeping 自身抛出的 `Error` 不会被吞掉。

`DefaultX4HostAdapter` 还把 final submit/release outcome 回写到仍待 retry 的 failed-revoke terminal request。若 X1 generation 已 terminal 而 exact membership 的第一次 revoke 抛出，X4 保持 `CLOSING`，禁止假存活 prepare/submit，并在后续 terminal call 只 retry 同一 receipt；最终 `CLOSED`、drain completion、selected throwable identity 与 X1 provider retire/close exact-once 必须一致。此候选没有改变 X1 1.1 协议含义，也没有接线 shared owner。

## r13 X6 owner-drain relation — committed X4 scope without new wiring

R13 保留并修复上述 X4 every-`Error`/failed-revoke 行为。提交
`55f61510a66f0ea8af6190aa276d5993295a754f` 实际修改三份 X4 production：
`DefaultX4HostAdapter`、`Minecraft2612X4PlatformAdapter`、`X4HostFailureSelector`；一份 X4
permanent test：`X4X6ErrorPolicyCompatibilityTest`；以及两份 X4 docs：本 README 与
`integration-handoff.md`。它没有修改 X4 host API、`X4HostAdapter` Javadoc、entrypoint、renderer、
reload 或 shared bootstrap 接线。该 permanent test 只证明 X4 与 X6 对 selected fatal
identity/suppression、failed-revoke exact-receipt retry 的兼容性，不是 X4 已经调度 X6 provider
close 的证据。

未来 shared owner 若选择创建 X6 plan，必须把真实、bounded、serialized、non-inline 的
`X6LifecycleDrainDispatcher` 传给 X6 的新七参数 factory，并保存其 admission bridge 至少到
request acknowledgement 完成；X4 不实现、替代或隐式拥有该 dispatcher。当前不存在生产
dispatcher caller，因此 X4/X6 的真实 host、reload、visual 与 compatibility Gate 继续 WAITING。

## R15 single terminal epoch repair

R15 以一个 terminal epoch `E` 收敛所有 X4 terminal path。`E` 一旦打开就同时拒绝新
prepare 与新 submit admission；仍 open 的 snapshot 都是必须排空的 future-`B` source，只有
`E` 前已经准入的 submit hold（`H`）可以完成。terminal result 只按
`F = select(Ae*, select(select(B*, C*), D*))` seal 一次，并向所有 observer 重放同一结果。
普通、非重入的 public `retire()` / `close()` 会等待 open snapshot、pre-`E` admitted work 与
retained membership source 排空；只有 exact renderer/provider/revoke callback owner 或
contribution owner 的重入调用合并到 outer owner 且不得自等，foreign caller 仍必须等待。

这是兼容边界内刻意收紧的行为，不改变 public/protected JVM descriptor，也不增删或重命名
`X4HostLifecycleState` 的七个 enum constant。package-private
`X4PreparedSnapshot.ReleaseListener` 使用两参数 internal method 传递已经分配的 exact
`SubmissionHold`；它不增加 submit allocation，也不是 public ABI。若改变该 internal
descriptor，九个 executable hash 会变化，全部保留 gate 必须重跑。X6 则保持 signal-only：
plan close/final hold 只请求 lifecycle-owner bridge，物理 provider close 不在 X6 caller 上执行。
R15 没有修改 X6 production 或 wiring。

## 目标矩阵

| 层级 | 目标 | Factory | 冻结配置 / frame 不变量 | Showcase fixture |
|---|---|---|---|---|
| Formal | Armor | `X4HostAdapters.armor` | owner scope、armor slot、base/overlay layer | `armor` |
| Formal | Projectile | `projectile` | 有界寿命、有限且有界 yaw/pitch | `projectile` |
| Formal | Held Item | `heldItem` | hand/context；不等同 ordinary marker-item path | `held-item` |
| Formal | First-person hand | `firstPersonHand` | hand 与 view-session scope | `first-person-hand` |
| Formal | GUI preview | `guiPreview` | viewport、translation、uniform scale、caller scissor epoch 只读 | `gui-preview` |
| Formal | World Object | `worldObject` | stable world/dimension scope 与 revision | `world-object` |
| Formal | Persistent VFX | `persistentVfx` | owner、寿命和 active-instance budget | `persistent-vfx` |
| Experimental | Player replacement | `playerReplacement` | explicit opt-in、player session、local-only policy | `player-replacement-experimental` |
| Experimental | Mount/composite entity | `mountComposite` | explicit opt-in、有限无环 graph、root | `mount-composite-experimental` |
| Experimental | Multi-entity composite | `multiEntityComposite` | explicit opt-in、有限无环 graph、root | `multi-entity-composite-experimental` |

Experimental factory 和 configuration 都要求 `X4ExperimentalAccess.optIn(...)`。默认 `disabled()` 会在构造阶段 fail closed；它不是 feature flag，也不会触发 provider discovery 或网络行为。

## 生命周期与重载

一个 builder 只在 configure 阶段可变；成功 `build()` 后即封存其 model key、scope/local identity 与 target configuration。

| 阶段 | 允许 | 明确禁止 |
|---|---|---|
| configure | builder 收集纯值并验证 | lookup、I/O、renderer work |
| freeze | 固定配置；同一 adapter 可持续创建多个 frame snapshot | late mutation、late target change |
| prepare / extract | frame validation；从已发布 `ClientModelLookup` 获取 handle，或验证既有 captured snapshot；为 exact generation 获取一个真实 `ProviderLease` | submit-time lookup、parse、world mutation、伪 lease |
| submit | adapter 取得短生命周期的 internal submit hold 后才将 raw snapshot 交给 `BlendRenderer` | registry lookup、JSON/GLB/resource I/O、animation/world sampling、公开 raw snapshot escape |
| retire | 打开/加入 terminal epoch；拒绝新 prepare 与新 submit admission；open snapshot 作为 future-B source 必须 close；仅 epoch 前已准入的 submit hold 可完成 | 替换为新 generation、让既有 snapshot 新开 submit、提前发布终态或释放真实 pin |
| close | 打开/升级同一 terminal epoch 为 close；同样拒绝新 prepare/submit admission、等待 open snapshot close，并仅允许 epoch 前已准入的 submit hold 完成 | reload、network、重新注册、close/submit TOCTOU、source 排空前发布终态 |

调用者规则：普通、非重入的 public `retire()` / `close()` 是同步 terminal observer；若它与 open snapshot 或 epoch 前已准入工作重叠，就会等待这些 source 排空，并重放同一个 sealed terminal outcome `F`。只有 exact renderer/provider/revoke callback owner 或 contribution owner 的重入调用会合并到其 outer owner 且不自等；该例外不会重新开放任何 admission。

每个 `X4PreparedSnapshot` 固定 exact handle generation。reload 后旧 lease 的 `generation()` 不会改变；新 adapter 的 prepare 才能绑定新 generation。公开表面只给出 host/key/generation/missing diagnostic 等 immutable evidence，**不再公开 `ModelRenderSnapshot` 或 `snapshot()` accessor**；raw input 只能由 `DefaultX4HostAdapter` 持有的 package-private submit hold 在一次 submit 内读取。`retire()` / `close()` 打开的 terminal epoch 会立即拒绝新的 missing diagnostic/read hold 与 submit admission；已经原子取得的 submit hold 完成前不会释放底层 `ProviderLease`。`X4HostLeaseDiagnostics` 与 `drainCompletion()` 提供 active lease、in-flight submit 与终态排空证据。

若 lookup 返回 missing handle，snapshot 仍可提交既有 fallback，但必须保留 exact generation 的 `X4MissingModelDiagnostic`（其中封装结构化 `ClientDiagnostic`）。captured path 的普通 `X4SnapshotFrame.extracted(...)` 拒绝 missing handle；missing captured snapshot 只能用 `extractedMissing(...)` 并验证同一 model key/generation 的诊断。对于已经由 skinned/existing path 提取好的 snapshot，captured API 仍强制 model、transform、light、overlay、tint 与 visibility 全部相等。

## X1 bridge

`Minecraft2612X4PlatformAdapter` 是显式手动安装的 experimental X1 `PlatformAdapter` bridge：

1. bootstrap owner 显式创建并调用 `install()`，保存返回的 `X4PlatformInstallationReceipt`；X4 不自动安装。
2. bridge 验证 ITEM loop policy、在 monitor 外执行用户 animation/equality callback、拒绝重复和重入，并只在成功后发布 immutable `X4StableHostBinding`。
3. binding 只携带 stable semantic `HostRegistrationSpec`、acceptance revision 与 existing Fabric seam (`ENTITY_RENDERER`、`BLOCK_ENTITY_RENDERER`、`MARKER_ITEM_RENDERER`)；它不偷渡 raw renderer/world/resource handle。
4. shutdown owner 只能通过同一实例的 `uninstall(receipt)` 卸载。每个 instance 有唯一 provider id，receipt 同时绑定 instance identity 和 installation revision，因此 foreign/stale/ABA receipt 都不会移除其他 adapter。

`install()` 不是“先写 local flag、再调 global”的两步操作，而是带 operation epoch 的可恢复事务：close 在 global publish 前取消并等待；若 global owner 已发布而 receipt 尚未提交，install owner 必须先完成 exact rollback 才结束。close 的无锁 ownership observation 只是诊断点，事务决策前必须按 `GLOBAL_X4_CONTROL_GATE → adapter monitor` 的唯一嵌套锁序重新读取 exact owner；`globalDetached` 只能单调前进，不能由过期的 `false` observation 猜测或回写。所有外部 callback 均不在 adapter monitor 内执行；close lifecycle/test hooks 还会在 global gate 释放后执行。若外部 X1 owner 先 detach，本 adapter 的 control `close()` callback 会保持外部操作直到本事务观察到 owner 已失去，避免随后第二次无条件 uninstall 误删 replacement。此机制不授权 consumer 直接调用 `PlatformAdapterControl.global().uninstall()`；共享 owner 仍只可使用其 receipt。

`X4HostAdapterRegistry.register()` 不把普通 `state()` observation 当作 commit 证明。factory adapter 使用同一 lifecycle monitor 的 internal reservation；包外实现则只能通过版本化 experimental `X4ManagedHostAdapter.takeOwnership(adapter)` 转移后续生命周期 ownership。raw external adapter 会带迁移提示 fail closed；managed handle 的 public API **不**暴露 reservation 或 internal marker，也不能再次 transfer 成嵌套 managed handle。reservation 存在时 prepare fail closed，其他线程的 retire/close 在 adapter monitor 外等待。managed terminal 调用形成一个 owner-thread 事务：在 raw delegate dispatch 尚未 seal 前，并发 `close()` 可以把 `retire()` upgrade 为 close；seal 后同一 in-flight transaction 的 observer 只等待/重放同一个已发布 outcome，绝不再次调用 raw delegate，owner-thread reentry 则 fail fast。只有成功 retire 已完全结束后的新 `close()` 才会建立下一事务；failure outcome 会保留并由所有后续 terminal caller 重放。registry 先建立 internal pending membership，commit 将该 exact membership 交给 adapter；只在 operation 仍 current/open 且 membership 尚未撤销时才激活并返回 receipt。direct `close()` / `retire()` 和 release-failure terminal path 都先在 adapter monitor 外撤销 exact membership，再使终态可观察；撤销物理移除记录，`registrations()` 不依赖 lazy `state()` filter。receipt 同时绑定 registry owner token、adapter identity、revision 和 membership token，阻止 ABA/stale receipt 删除 replacement。registry 从不在 registry monitor 内调用 adapter，adapter 也不在自身 monitor 内调用 membership revoke，因此没有 ABBA lock order。duplicate、registry close、commit failure 和 reentry 都会 abort reservation 而不留下 membership；abort failure 也会在 nested `finally` 后清除 active-operation sentinel。`registry.close()` 首个 caller 建立 shared completion，所有并发 caller 等待同一个 normal aggregate 或同一 fatal identity；owner-thread reentry 不自等。

现有 entity、block-entity 和 ordinary marker-item renderer 注册的最终接线不在 X4 写权限内；所需精确改动列在 [integration-handoff.md](integration-handoff.md)。这也刻意保证 X4 Held Item 不会悄悄改写 ordinary marker-item 语义。

## 可执行证据与剩余 Gate

已实现的 unit-level evidence：

- `X4HostAdapterContractsTest`（6 XML tests）：七个 Formal target、三个 Experimental target、Builder/target validation、generation pin、missing diagnostic、captured snapshot、lease drain、registry conflict。
- `X4HostAdapterRepairContractsTest`（6 XML tests）：同一 frozen adapter 的连续 lease、close/submit race、真实 session drain、structured missing evidence、composite scope/root/connectivity、registry receipt ABA 与 close 聚合。
- `X4HostAdapterSecondRepairContractsTest`（14 XML tests）：public raw-snapshot escape source/reflection boundary、close-after-submit、真实 barrier 双线程 prepare、retire/drain、全部 registry lifecycle state、unmanaged adapter fail-closed、factory-managed lifecycle reservation 与真实 prepare/close/retire boundary、reservation-protected register-vs-close/retire（64 + 64 permutations）、duplicate/registry-close/commit-failure/owner-reentry abort、interrupted lifecycle waiter、freeze/reentry/replacement race、shared close aggregate/fatal/interrupt/reentry。
- `X4HostAdapterFourthRepairContractsTest`（8 XML tests）：1024 次 direct close/retire 的物理 membership 撤销与 replacement、128 次 close/retire-vs-register race、stale receipt ABA、submit 与 failed-prepare 的 primary/cleanup fatal identity + suppression、CLOSING 中 final pin release fatal 的 exceptional drain，以及 256 次 abort failure 后 registry reuse。
- `X4ManagedHostAdapterFifthRepairContractsTest`（6 XML tests）：delegate callback 的 owner-thread terminal reentry fail-fast、普通/checked/`OutOfMemoryError`/`ThreadDeath` 的并发 observer 同一 failure identity 与单次 raw close、raw dispatch 前 retire-to-close upgrade、dispatch 后同一 in-flight retire 的 close coalesce，以及 completed retire 后的下一 close transaction。
- `external.X4ExternalManagedRegistrationContractTest`（3 XML tests）：package-external legacy adapter 只能经 public `X4ManagedHostAdapter.takeOwnership(...)` 注册；reflection 证明 handle 不公开 internal reservation/marker，并覆盖 managed close、exact stale receipt 与 replacement retire、managed-handle nesting rejection/原始 claim 保留，以及 drain observation failure 的 tentative-claim rollback。
- `Minecraft2612X4PlatformAdapterContractsTest`（11 XML tests）：X1 receipt、duplicate、ITEM policy、callback re-entry、exact install receipt、foreign/stale ABA、`OutOfMemoryError`/`ThreadDeath` 原对象重抛，以及 128 次 close-before-global、close-after-global-before-receipt、确定性 false ownership read → global publish → close decision 交错、rollback callback failure/fatal、external replacement transaction interleave。新增交错同时证明 cancelled install 无 receipt/全局残留、replacement 无需 raw cleanup 即可安装卸载，并核对单次 publish/rollback/global close。
- `X4X6ErrorPolicyCompatibilityTest`（6 XML tests）：X1/X4 terminal every-`Error` primary/suppression identity、failed membership revoke 的 exact receipt retry、single terminal epoch 的 submit/release/contribution 聚合，以及 successful membership retry 与 submit selection 只发布一个 sealed terminal identity。X6 owner-drain request-failure 行为仍由既有 X6 suite 覆盖；本次没有 X6 production/wiring change。
- client `X4ServerClassloadBoundaryTest`（5 XML tests）：只扫描 X4 client `host` source 和该模块自己的 `build/classes/java/client/.../host` production output，拒绝 server/common entrypoint、I/O 与解析 seams；扫描必须至少读取一个 class 且实际扫描 `X4HostAdapter.class`，因此 empty output、缺 sentinel 和 only-unrelated class 均 fail closed；独立 `:blendlib-fabric-client:test` 不探测任何 Showcase 目录。
- Showcase `X4ShowcaseServerClassloadBoundaryTest`（6 XML tests）：由 Showcase test task 的实际 production `ProtectionDomain`/`CodeSource` 定位 api/core/common/Showcase classpath entries；其 loader 只从每个 namespace 的已验证 module output/JAR 读取 inspected class，绝不在 `findLoadedClass` miss 后回退 parent。验证拒绝空/缺失/非 file URL、test output、empty/missing-marker/wrong-module directory 与非 JAR；directory/JAR（含 percent-encoded space path）均可用。client namespaces fail closed，JDK 与 server-side Fabric/Minecraft support types 仍可由 parent 提供；以 `initialize=true` 加载真实 common/Showcase entrypoint、只调用 common 的真实 Fabric callback，并完整扫描 common/Showcase production bytecode 的 client/Blaze3D references。Showcase callback 会做真实 registry registration，仍只能由真实 Fabric mutable-registry 生命周期调用。
- `X4ShowcaseCatalogTest`（1 XML test）：十个 Showcase fixture 均执行 freeze → prepare → snapshot-only submit，且 submit 前后 lookup count 不变。

修复后的 client-focused command 为 `:blendlib-fabric-client:test --tests 'com.liy.blendlib.fabric.client.host.X4*' --tests 'com.liy.blendlib.fabric.client.host.Minecraft2612X4PlatformAdapterContractsTest' --tests 'com.liy.blendlib.fabric.client.host.external.X4ExternalManagedRegistrationContractTest'`，R15 XML 合计为 **9 suites / 65 tests、0 failures、0 errors、0 skipped**（6 + 6 + 14 + 8 + 6 + 5 + 3 + 11 + 6）；Showcase X4 tests 为历史独立 **7 tests、0 failures、0 errors、0 skipped**（6 boundary + 1 catalog）。这是 unit-level evidence，不替代下列真实 client Gate。

R15 冻结自动化还记录：exact compatibility class **6** tests、focused X6 **8 suites / 113**、full Fabric client **55 / 323**、root seven-module **134 / 1,033**、X1 lifecycle **69**、X6 consumer **5**，以及 corrected Gate 7 的 **8/8** independent exact-method JVM rounds。每项均为 0 failure/error/skip；精确 artifact 路径、report/manifest SHA-256、stale `master-status.txt` 排除和 invalid old launcher 排除统一见 [`../x6/test-evidence.md`](../x6/test-evidence.md)。

建议复跑：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat :blendlib-fabric-client:test :blendlib-showcase:test --no-daemon --max-workers=1 --console=plain
```

## r7 server-classload task-graph repair

**Finding / red evidence.** The old client-only boundary test directly required
`blendlib-showcase/build/classes/java/main`, although `:blendlib-fabric-client:test` did not
declare a task dependency that produces it. In retained zero-output clone
`D:\BlendLib-review-temp\x4-r7-red-20260807-120001`,
`:blendlib-fabric-client:test --tests 'com.liy.blendlib.fabric.client.host.X4ServerClassloadBoundaryTest' --rerun-tasks --no-daemon --max-workers=1 --console=plain`
returned exit 1: 3 tests ran, the two common+Showcase checks failed solely because that Showcase
output did not exist, and the client source check passed. A separate first full
`check --rerun-tasks --no-daemon --max-workers=1 --console=plain` from the same zero-Showcase-
output clone also returned exit 1 with client **186 tests, 2 failures**. This was a test task-graph
defect, not a client-reference finding.

**Fix / fresh-clone evidence.** The two checks that require common+Showcase production classes
now live in Showcase X4 test source. The client test only verifies its own source/output boundary;
the Showcase test derives class locations from actual loaded production classes instead of an
unguaranteed sibling `build/classes` path. The retained fresh zero-output clone
`D:\BlendLib-review-temp\x4-r7-repair-20260807-120002` was created at repair code commit
`d4ed4e55f8d87ae247a1cfdd20c1010f4b74a886` with both client and Showcase production outputs
absent. With Java 25.0.2, its standalone
`:blendlib-fabric-client:test --rerun-tasks --no-daemon --max-workers=1 --console=plain` returned
exit 0 in **44.3 s**, with client **185 tests, 0 failures, 0 errors, 0 skipped**; Showcase main
output remained absent afterwards. Without a standalone Showcase prewarm, the subsequent combined
`:blendlib-fabric-client:test :blendlib-showcase:test --rerun-tasks --no-daemon --max-workers=1 --console=plain`
returned exit 0 in **45.2 s**, with client **185** + Showcase **62** tests (247 total, all zero
failure/error/skip).

The same retained clone recorded focused client X4 **56/0**, Showcase X4 **3/0**, Minecraft 26.1.2
platform **11/0**, fifth managed + external registration **9/0**, and X1 independent rollback
matrix **2/0**. Its first full `check --rerun-tasks --no-daemon --max-workers=1 --console=plain`
returned exit 0 in **63.7 s**, **758 tests, 0 failures, 0 errors, 0 skipped**. Java 25.0.2
`buildRelease --rerun-tasks --no-daemon --max-workers=1 --console=plain` returned exit 0 in
**78.1 s** and its 17 `SHA256SUMS` entries all matched. Forbidden client-reference scans were zero
for common main source (9 files), common main bytecode (15 classes), Showcase main source (27 files),
and Showcase main bytecode (40 classes); `jdeps --ignore-missing-deps -verbose:class` on each of
those two main-output directories was also zero. The packaged Showcase mod JAR intentionally carries
its separate client source set, so its JAR-wide `jdeps` reports client dependencies and is not the
server/main boundary proof.

## r8 classload-boundary fail-closed repair

**Fresh red evidence retained.** Two independent zero-output clones at base
`de035048048a63123f2d32ec0e6b9987dec2cd15` exposed the two review blockers before this repair.
In `D:\BlendLib-review-temp\x4-r8-red-empty-url-20260807-104258`, an empty validated-location
URL was given to the old `ClientBlockingLoader`; its only test exited 1 in **20.7 s** with
**1/1/0/0** because `assertThrows(ClassNotFoundException)` saw the API class successfully loaded
from the parent classpath. In
`D:\BlendLib-review-temp\x4-r8-red-empty-output-20260807-104258`, a JUnit empty client-output
directory was given to the old scanner; its only test exited 1 in **19.6 s**, **1/1/0/0**, because
the scan accepted zero `.class` files. Both clones and their Gradle outputs are retained.

**Repair.** The Showcase test loader now validates each marker CodeSource before construction:
the source must be a non-empty `file:` URL and either the expected module's
`build/classes/java/main` directory containing that marker or a JAR containing it. It maps each
inspected api/core/common/Showcase namespace to that exact validated location and defines only
from it; an absent location/class therefore raises `ClassNotFoundException` instead of delegating
to parent or another module's output. Client namespaces are rejected before delegation, while
ordinary JDK and explicitly required server-side Fabric/Minecraft support types remain parent
loadable. Negative coverage includes null/empty URL, unsupported scheme/path type, missing/empty
directory, missing marker, test output, wrong module output, and a missing inspected location;
positive coverage includes directory and JAR locations with percent-encoded space paths. The
client bytecode scanner now requires a nonzero class count plus a scanned `X4HostAdapter.class`
sentinel, with separate empty/missing-sentinel/only-unrelated negatives.

**Fresh no-prewarm green evidence.** The retained clone
`D:\BlendLib-review-temp\x4-r8-repair-client-20260807-110515` starts at `de035…` with exactly the
two candidate test-file diffs applied and no client or Showcase output. Its first Gradle command,
`:blendlib-fabric-client:test --rerun-tasks --no-daemon --max-workers=1 --console=plain`, exited
0 in **43 s**: client **188/0/0/0**, including the repaired client boundary **5/0/0/0**. Afterwards
Showcase main/client/test output directories were all still absent. With no standalone Showcase
prewarm, its subsequent combined client+Showcase command exited 0 in **43 s**: client
**188/0/0/0**, Showcase **66/0/0/0**, repaired Showcase boundary **6/0/0/0**, and catalog
**1/0/0/0**.

The separate retained clone
`D:\BlendLib-review-temp\x4-r8-repair-check-20260807-110745` also began with no client or
Showcase output and the same two candidate test diffs. Its **first** Gradle command,
`check --rerun-tasks --no-daemon --max-workers=1 --console=plain`, exited 0 in **69 s** with
**765/0/0/0** across 108 XML suites. Its Java 25
`buildRelease --rerun-tasks --no-daemon --max-workers=1 --console=plain` exited 0 in **77 s**;
an independent recomputation matched **17/17** `SHA256SUMS` entries. Focused XML counts were
client X4 **59/0/0/0** (previous 56), Showcase X4 **7/0/0/0** (previous 3), platform **11/0/0/0**,
managed+external **9/0/0/0**, and X1 rollback **2/0/0/0**. Forbidden-client scans remained zero
for common main source **9** files, common main bytecode **15** classes, Showcase main source
**27** files, and Showcase main bytecode **40** classes; directory-scoped
`jdeps --ignore-missing-deps -verbose:class` was also zero for both main outputs.

**Gate.** This is a test-only repair; production/build/config/shared metadata diffs remain zero.
At that historical r8 checkpoint the result was **WAITING — fresh r9 independent review**, not a
real-client, visual, reload, or Iris/Sodium PASS claim; the current R15 review state is recorded above.

尚未完成的非单元 Gate：共享 bootstrap/renderer integration、manual/真实 Fabric client、network、各 fixture 视觉核验、F3+T reload、连续 reload/resource-release、Iris/Sodium、hardware、性能与内存验证。这些不能由本 README、mock backend 或 R15 自动化 PASS 宣称通过。

## Agent Innovation [X4-AI-1, X4-AI-2, X4-AI-3]

### X4-AI-1 — generation lease handoff ledger

**动机。** 一个 frame snapshot 的 transform/light/animation 数据必须可每帧变化，但其 model generation 不能在 reload/retire 中被偷换；原先单个 active snapshot 加布尔 close bit 同时限制吞吐且无法证明资源何时真正可释放。

**实现与诊断。** X4 显式注入已经 publish 的 `ProviderLifecycleSession`，每次 prepare 获得一个 X1 `ProviderLease`，并在 `X4PreparedSnapshot` 内以 close-request + in-flight-submit hold 维护精确排空。raw `ModelRenderSnapshot` 不再是 public escape surface，而是 submit hold 的内部输入。`X4HostLeaseDiagnostics`、`drainCompletion()`、session retire/close counter 共同提供诊断。`X4HostAdapterRepairContractsTest` 与 `X4HostAdapterSecondRepairContractsTest` 覆盖真实双线程 prepare、double-close、retire-before-close、close/submit race、raw escape boundary 和真实 session drain。

**备选方案与兼容性。** 备选方案是 adapter 自己保存 `AtomicBoolean`，或让它独占/retire session；前者不 pin generation，后者会错误结束同一 generation 的其他 consumer。当前方案不改变 X1/v1 stable surface，但要求未来 shared generation owner 在 factory 调用时传入其真实 session，故共享 bootstrap/reload integration 仍是 WAITING。

### X4-AI-2 — capability-scoped ownership receipts

**动机。** kind + identity 或常量 provider id 都会在 unregister/reinstall 后出现 ABA：旧 owner 或另一 adapter 可以删除后来者。

**实现与诊断。** `X4HostAdapterRegistry` 返回 opaque registration receipt（private owner token + adapter identity + revision + exact membership token）。factory 继续使用 package-private lifecycle reservation；external adapter 则用 versioned experimental `X4ManagedHostAdapter.takeOwnership(...)` 把全部后续 lifecycle call 转给 managed handle，且 public API 不泄漏 reservation/marker、拒绝 managed-handle nesting。managed handle 的 terminal transaction 记录 owner thread 并只发布一次 immutable outcome：dispatch seal 前 close 可升级 retire，seal 后同一 in-flight observer 重放该结果且不会 raw retry，owner reentry fail-fast；只有 completed retire 后的新的 close 才会开始 successor transaction，而 failure outcome 会永久重放。registry 先放置 pending membership，commit 后只在 exact operation 仍有效时激活；adapter 的 direct terminal path 在终态前物理 revoke 该 token，故 inspectable registry record 不会靠 state reread 隐藏。retire/uninstall 都做 exact receipt CAS；registry close 还共享一个 completion/result。registration cleanup、submit cleanup 与 terminal cleanup 都使用同一 primary/cleanup selector：primary fatal 优先，否则 cleanup fatal 优先，普通 primary 优先，并保留不同 failure 的 suppressed evidence。平台 callback 对 `VirtualMachineError` 与 `ThreadDeath` 先恢复本地不变量、通过既有 `PlatformAdapterControl` 释放 exact owner、再以同一 fatal 对象重抛。`X4HostAdapterRepairContractsTest`、`X4HostAdapterSecondRepairContractsTest`、`X4HostAdapterFourthRepairContractsTest`、`X4ManagedHostAdapterFifthRepairContractsTest`、external managed contract 与 `Minecraft2612X4PlatformAdapterContractsTest` 覆盖 ABA、terminal membership、all-state registration、reservation close/retire/abort/reentry/interrupt、abort reuse、fatal identity/suppression、managed migration、managed terminal exact-once/reentry/upgrade/coalesce/successor、shared close aggregate/fatal/interrupt、OOME、ThreadDeath、normal callback 和 rollback。

**备选方案与兼容性。** 备选方案是公开 registry reservation SPI、公开全局 `uninstall()`、复用常量 provider id 或以字符串查表；它们分别会把 internal lock protocol 泄漏给 external caller，或无法表达当前 owner。该创新只扩展 X4 experimental client bridge，X1/v1 stable API 不变；shared owner 需要保存 installation receipt，不能再裸调 global uninstall。external implementer 必须显式选择 managed migration handle，并在 transfer 后不再直接驱动 raw delegate；这是受控兼容边界，不是把 reservation 变成新的 public SPI。

### X4-AI-3 — recoverable global-owner publication transaction

**动机。** 一个 receipt-less global install 比单纯的 local leak 更危险：close 可以令 `install()` 抛错，却把 adapter 留在 X1 control；随后一个检查后再 `uninstall()` 的 rollback 又可能删除外部 replacement。

**实现与诊断。** bridge 以 operation epoch 记录 install、global publish、receipt commit、rollback 和 completion。close 在 publish 前取消，在 publish 后等待 exact cleanup；其事务分类只使用 `GLOBAL_X4_CONTROL_GATE → adapter monitor` 内的 fresh ownership revalidation，detached 状态保持单调。外部 X1 detach 进入 bridge `close()` callback 时会成为 completion barrier；transaction 观察 owner 已失去后不再发起第二次 global uninstall。`Minecraft2612X4PlatformAdapterContractsTest` 用 barrier 覆盖 before-global、after-global-before-receipt、false-read 后 publish 的精确旧缺口、global install/rollback callback normal/fatal、128 次重复 race 与 external replacement interleave。

**备选方案与兼容性。** 备选是只用 instance-local lock 或 static X4 gate；它们都不能约束 raw X1 external caller。当前方案不改变 X1 API，也不假设外部 caller 持有 X4 lock，而是利用 X1 已有 close callback 来保持 replacement 前的可观察一致性。该行为仅影响未完成 receipt 的 X4 experimental installation；已完成的 receipt uninstall 语义保持不变。
