# X4 共享集成交接

状态：**R14 final source review 为 PASS；随后 formal integration 的独立 completion-order probe 在 8 个 JVM 中稳定复现 96/96 race，因此 formal integration 仍为 BLOCKED。R15 依次完成 R3 FAIL（0C/4H/2M/0L）、R4/R5 non-verdict design、valid RED（6 tests / 1 intended assertion failure）以及冻结 source candidate 的 pre-documentation source/test PASS（0C/0H/0M/0L）与 static/binary PASS。** 当前只是等待独立 docs/full-source review 的 documentation candidate，不是 integration、release 或真实客户端 Gate PASS。manual client、network、reload、visual、Iris/Sodium、hardware、performance 及 shared owner bootstrap/renderer evidence 均为 WAITING。本文只列出 X4 未获写权限的接线；不要求或授权本候选直接修改 entrypoint、existing renderer、metadata 或 build。

在 shared owner 获得新的独立 source review 前，不得把 r12 自动化结果当作接线验收。特别是，X4 terminal owner 必须保留 X1 1.1 every-`Error` 的 exact selected object：任一 `Error` 胜 ordinary failure，两个 `Error` 保留先发生者。若 membership revoke 在 X1 已 terminal 后失败，owner 只能以 retained exact receipt retry，不能把 adapter 当作仍有可用 membership，也不能用新的 ordinary/fatal cleanup 覆盖原 selected terminal。

R13 没有把 X6 drain 调度接到 X4 或任何 production host。未来 owner 若把 X4 snapshot 与
X6 plan 一起使用，必须向 X6 新七参数 factory 提供真实的
`X6LifecycleDrainDispatcher`：factory 在 pin 前调用 `register(prebuiltRunnable)`，host 保留该
bridge 到 request acknowledgement 已最终化；bridge 不得 inline。`close()`/final submit hold
只能 request，只有 lifecycle-owner bridge 可物理 close provider。same-thread inline request
run 必须 fail closed；不同 owner worker 在 request 返回前完成是允许的，且 late request
failure 只能进入 completion/defensive replay，不能泄漏到 X4 submit/close。X4 不拥有该
dispatcher，也不以任何 X4 fallback worker 代替它。

## R15 X4 synchronous terminal contract

R15 的 X4 adapter 只打开一个 terminal epoch `E`。`E` 一旦打开就同时拒绝新的 prepare 与
submit admission；仍 open 的 snapshot 是必须排空的 future-`B` source，只有 `E` 前已准入的
submit hold（`H`）可以完成。最终结果只按
`F = select(Ae*, select(select(B*, C*), D*))` seal 一次并重放。普通、非重入的 public
`retire()` / `close()` 必须等待 open snapshot、pre-`E` admitted work 与 retained membership
source 排空；只有 exact renderer/provider/revoke callback owner 或 contribution owner 的重入
调用合并到 outer owner 且不得自等，foreign caller 仍等待。

这条同步 X4 caller contract 不改变下方 X6 交接的 signal-only 边界：X6 plan close/final hold
只请求 `X6LifecycleDrainDispatcher`，物理 provider close 仍只能由 lifecycle owner 执行。R15
没有修改 X6 production 或 wiring。自动化结果与 report/manifest SHA-256 统一记录在
[`../x6/test-evidence.md`](../x6/test-evidence.md)；它们不能替代任何 WAITING runtime Gate。

## 已交付的可消费表面

- r8 server-classload boundary repair：client `X4ServerClassloadBoundaryTest` owns only its X4 source/client-output boundary (5 tests) and requires both a nonzero scan and the `X4HostAdapter.class` sentinel; Showcase `X4ShowcaseServerClassloadBoundaryTest` owns common+Showcase initialization/bytecode isolation (6 tests). Showcase validates each actual api/core/common/Showcase `ProtectionDomain`/`CodeSource` against its exact module production directory or marker-containing JAR, maps inspected namespaces to those locations, and refuses parent/another-module masking. There remains no client → Showcase Gradle task dependency or unguaranteed sibling output path.
- `Minecraft2612X4PlatformAdapter`：显式 `install()` 返回 opaque installation receipt，`uninstall(receipt)` 精确 owner-safe；close/install 竞争在 `GLOBAL_X4_CONTROL_GATE → adapter monitor` 内重新验证 ownership，detached 状态单调且 callback 不在 adapter monitor 内执行；immutable stable binding snapshot、重复/重入 fail-closed。
- `X4HostAdapters`：10 个 typed factory，全部要求 integration owner 传入已 publish 的真实 `ProviderLifecycleSession`，并生成 generation-pinned `X4HostAdapter` 生命周期对象；若 shared owner 使用 `X4HostAdapterRegistry`，factory adapter 自带 internal lifecycle reservation。package-external legacy adapter 不实现/拿不到 reservation；它必须以 versioned experimental `X4ManagedHostAdapter.takeOwnership(adapter)` 迁移后再 register，并在 transfer 后只通过 handle 驱动 lifecycle。不得再包装 managed handle；其 terminal lifecycle 是单个 owner-thread transaction，raw dispatch seal 前并发 close 可升级 retire，seal 后同一 in-flight observer 共享 outcome 而不会重试 raw delegate；仅 completed retire 后的新 close 才会开始下一事务，failure 会永久重放。
- `X4HostFrames` / `X4HostConfigurations`：target-specific immutable frame/configuration。
- `X4PreparedSnapshot`：exact generation pin、missing diagnostic、caller-owned drain lease；不公开 raw `ModelRenderSnapshot`，只能由 adapter submit hold 消费。
- `X4ShowcaseCatalog`：7 formal + 3 explicit-experimental consumer fixtures。

## 需要共享 owner 的精确改动

| 顺序 | owner / 建议接线点 | 必须动作 | 不可做 |
|---|---|---|---|
| 0 | X4 test owner / Showcase test task | 保持 common+Showcase isolation proof 在 `blendlib-showcase` X4 test source：从实际 loaded production `CodeSource` 取得并 fail-closed 验证 exact module output/JAR；受检 namespace 只能从该 location 定义，client namespace 必须拒绝，common callback 可调用、Showcase 只 class-init | 不把 Showcase output probe 放回 client test；不新增 client → Showcase task dependency；不从 parent/another module mask missing artifact；不在非 Fabric mutable-registry 生命周期调用 Showcase callback |
| 1 | client bootstrap / shutdown owner | 在明确接受 X1 experimental SPI 后，保存一个 `Minecraft2612X4PlatformAdapter` 实例和 `install()` 返回的 receipt；shutdown 用同一实例 `uninstall(receipt)` | 不在 static initializer、普通 consumer 或 server/common entrypoint 自动 install；不直接 `PlatformAdapterControl.global().uninstall()` |
| 2 | existing entity / block-entity / marker-item registration owner | 读取 `adapter.bindings()` 的 immutable snapshot，按 `X4StableHostSeam` 将 opaque stable spec 翻译到既有 renderer registration；保持 token 的 typed/opaque 边界 | 不以 reflection/裸 id 识别 host；不把 stable ITEM 自动转成 X4 Held Item |
| 3 | target renderer extraction owner | 从 reload/generation owner 取得已 publish 的 `ProviderLifecycleSession`，为每个真实 Fabric target 建立 scope + local `X4HostIdentity`，freeze 一次，在 extract 阶段 `prepare` 或 `extract`，把 lease 放入对应 render state | 自造 lease、adapter retire 共享 session、submit 内 resolve、访问 resource/world/controller 或重新采样动画 |
| 4 | reload / generation owner | publish 新 generation 后 retire 旧 target adapter；仅在旧 caller lease `close()` 排空后释放旧 generation resources；为新 generation 建新 frozen adapter | 原地改写旧 `X4PreparedSnapshot` 或以 current lookup 替换旧 handle |
| 5 | Showcase client/source-boundary owner | 选择显式 debug/fixture runtime registration，并把 Showcase client source-boundary allow-list 正式扩展为 `com.liy.blendlib.fabric.client.host.*`；随后可将 catalog 的 FQCN 改为普通 imports | 不把 `X4ShowcaseCatalog` 隐式接入每次 client startup；不扩大 server/main source surface |
| 6 | verification owner | 在真实 client 执行 armor、projectile、held item、first-person、GUI、world object、VFX；单独 explicit opt-in 测 Experimental；执行 F3+T 和 repeated reload/resource checks | 把 unit/mock renderer green 当作视觉/compatibility Gate PASS |

## 交接协议

1. 共享 owner 在合入前确认全局 `PlatformAdapterControl` 没有其他 provider；若存在，停止并选择唯一 adapter owner，而不是覆盖它。
2. bindings 的 `revision()` 只用于 deterministic snapshot ordering，不能作为 world/entity identity 或 reload generation。
3. target renderer 应把 `X4PreparedSnapshot` 作为 render-state 字段；render state 生命周期结束必须 `close()`，包括 cull、world disconnect 和 renderer replacement 路径。close 后不可取得新的 submit hold；renderer integration 不得尝试读取或保存 raw `ModelRenderSnapshot`，submit 必须走 adapter 的原子 hold。
4. missing snapshot 仍可交给 `BlendRenderer` 的既有 fallback；UI/diagnostic 只能读取同 generation 的 `X4MissingModelDiagnostic`，不得触发 reload。
5. shared integration 必须补/更新它自己拥有的 source-boundary、entrypoint lifecycle 和 real-client tests；X4 的 server isolation unit test 不是 server launch proof。

## 合入验收清单

- [ ] 唯一 manual `PlatformAdapter` owner 已确定，install/uninstall 对称。
- [ ] shutdown 只使用保存的 exact receipt；不得用 raw global cleanup 掩盖 receipt-less install/close residue。
- [ ] 现有 X1 ENTITY/BLOCK_ENTITY/ITEM binding 各有可观察 Fabric registration，不改变 stable API。
- [ ] X4 Held Item 与 existing marker-item adapter 保持不同路径。
- [ ] renderer extract/submit 分界审计证明 submit 无 lookup/I/O。
- [ ] reload/disconnect/cull 都 drain `X4PreparedSnapshot` lease。
- [ ] ordinary public terminal caller 同步等待 single-`E` source drain；仅 exact renderer/provider/revoke callback owner 或 contribution owner reentry coalesce，且不自等。
- [ ] Showcase source allow-list 由其 owner 正式更新，不扩大 common/server classpath。
- [ ] 真实 client、visual、F3+T、repeated reload、Iris/Sodium、performance evidence 分别记录；未做项保持未通过。

## r7 task-graph evidence and exact reruns

The retained red clone is `D:\BlendLib-review-temp\x4-r7-red-20260807-120001`. With no
`blendlib-showcase/build/classes/java/main`, its standalone client boundary invocation ran 3 tests
and failed exactly the 2 checks that hard-coded that unavailable output; its first full
`check --rerun-tasks --no-daemon --max-workers=1 --console=plain` likewise failed at client
186 tests / 2 failures. The repair changes test ownership only: no production, Gradle, entrypoint,
metadata, or lifecycle source changes are permitted or made.

The required fresh repair verification is:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat :blendlib-fabric-client:test --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat :blendlib-fabric-client:test :blendlib-showcase:test --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat check --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

The retained fresh repair clone is
`D:\BlendLib-review-temp\x4-r7-repair-20260807-120002`, created at repair code commit
`d4ed4e55f8d87ae247a1cfdd20c1010f4b74a886` with no client or Showcase production output. On
Java 25.0.2, standalone `:blendlib-fabric-client:test --rerun-tasks --no-daemon --max-workers=1
--console=plain` exited 0 in **44.3 s** with **185/0/0/0** (tests/failures/errors/skipped), and
Showcase main output was still absent. With no standalone Showcase prewarm, combined
`:blendlib-fabric-client:test :blendlib-showcase:test --rerun-tasks --no-daemon --max-workers=1
--console=plain` exited 0 in **45.2 s** with client **185/0/0/0** and Showcase **62/0/0/0**.

The focused X4 client command in this clone was **56/0/0/0**, Showcase X4 was **3/0/0/0**,
Minecraft 26.1.2 platform was **11/0/0/0**, fifth managed + external registration was
**9/0/0/0**, and the independent X1 primary-by-cleanup rollback matrix was **2/0/0/0**. This
clone's first full `check --rerun-tasks --no-daemon --max-workers=1 --console=plain` exited 0 in
**63.7 s** with **758/0/0/0**. Java 25.0.2 `buildRelease --rerun-tasks --no-daemon --max-workers=1
--console=plain` exited 0 in **78.1 s**; all **17/17** `SHA256SUMS` entries matched. Common main
and Showcase main source, bytecode, and directory-scoped `jdeps` forbidden-client scans were all
zero. Do not substitute a JAR-wide Showcase `jdeps` result for this proof: the shipped Showcase JAR
intentionally includes its client source set. These automatic results remain unit/build evidence
only. Real Fabric client, visual, F3+T, 20 reload, dedicated runtime, Iris/Sodium, CPU/GPU, and
performance evidence remain WAITING.

## r8 fail-closed classload evidence and exact reruns

The retained red clones are
`D:\BlendLib-review-temp\x4-r8-red-empty-url-20260807-104258` and
`D:\BlendLib-review-temp\x4-r8-red-empty-output-20260807-104258`. The first proves the old
empty-URL loader incorrectly parent-loaded the API marker (**1/1/0/0**, exit 1); the second proves
the old client scanner accepted an empty class directory (**1/1/0/0**, exit 1). The repair is
strictly test/doc scoped: no production, Gradle, entrypoint, metadata, lifecycle, or shared
integration source is changed.

Two retained no-prewarm candidate clones begin at
`de035048048a63123f2d32ec0e6b9987dec2cd15` with only the two repaired test-file diffs applied:

- `D:\BlendLib-review-temp\x4-r8-repair-client-20260807-110515`: its first command was only
  client test, returning **188/0/0/0**; all Showcase main/client/test outputs remained absent.
  Its subsequent combined client+Showcase test returned client **188/0/0/0** and Showcase
  **66/0/0/0**, including repaired boundary **5/0/0/0** and **6/0/0/0**.
- `D:\BlendLib-review-temp\x4-r8-repair-check-20260807-110745`: its first command was full
  `check`, returning **765/0/0/0**. `buildRelease` returned 0 and an independent manifest read
  recomputed **17/17** SHA-256 entries. Focused counts are client X4 **59/0/0/0**, Showcase X4
  **7/0/0/0**, platform **11/0/0/0**, managed+external **9/0/0/0**, and X1 rollback **2/0/0/0**.

The repeat protocol is:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat :blendlib-fabric-client:test --rerun-tasks --no-daemon --max-workers=1 --console=plain
# Confirm blendlib-showcase/build/classes/java/main, client, and test are still absent here.
.\gradlew.bat :blendlib-fabric-client:test :blendlib-showcase:test --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat check --rerun-tasks --no-daemon --max-workers=1 --console=plain
.\gradlew.bat buildRelease --rerun-tasks --no-daemon --max-workers=1 --console=plain
```

Directory-scoped common/Showcase main source and class scans, plus `jdeps`, remain zero for the
forbidden client namespaces. This evidence is still only automatic unit/build evidence. **WAITING
— fresh r9 independent review**; real Fabric client, visual, reload, and Iris/Sodium gates remain
unclaimed.
