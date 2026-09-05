# X2：Experimental/internal animation-v2 runtime

状态：Proposed/Experimental implementation candidate。X2 只实现一个隔离的内部动画运行时、语义 intent 协调器和无平台依赖的 client owner；它不是新的稳定 API、wire 协议、reload 机制或宿主接入。independent r8 review 为 **FAIL（0 Critical / 0 High / 3 Medium / 1 Low）**；formal r9 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 2 Low）**；fresh independent r10 review 已为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**；formal r11 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**；formal r12 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**。r13 是定向兼容治理修复，fresh independent r13 review 为 **PENDING**。

## 已实现边界

| 层 | X2 内容 | 明确不做 |
|---|---|---|
| `blendlib-core` | 纯 Java 的冻结计划、骨骼 schema/mask、controller/state/layer 运行时、不可变 evaluation snapshot | Minecraft/Fabric 类型、资源发现、解析、I/O、renderer、共享 registry |
| `blendlib-fabric-common` | `AnimationIntent`、精确 scope、单 owner sequence/persistent replay 协调 | payload 编解码、网络注册、客户端类型、模型/渲染数据 |
| `blendlib-fabric-client` | 并发 intent 入队、单 owner 推进、不可变 client snapshot、tick/partial-tick 时间换算 | lifecycle/reload/render/network callback 注册、资源加载、host adapter |

所有新增类型都在 `animation.v2` 子包中。既有 v1 `AnimationController`、`SyncedAnimationState`、payload、entrypoint、reload 和 extraction/render 链路均未改动。

## 运行时契约

### r9 historical transaction、r10 findings 与 r11 directed correction（candidate only）

r8 的 exact-long/counter-specific evidence 不能外推成所有 ordinary frame failure 都在 live mutation 前结束。r9 candidate 在 next-revision check 后把 live controller/backlog 复制到 owner-only `FrameStage`，在 stage 上完成 bounded ingress prefix、rejection、command、diagnostic、transition 与 pose 的完整 prospective evaluation；只有 complete immutable snapshot 已构造、live capacity 已准备后，才消费 captured FIFO prefix，并提交 controller/backlog、revision 与 `latestSnapshot()`。stage 是 disposable mutable storage，因此只承诺 pre-commit failure 不改变 live/observable owner state；不能声称 stage 从未改变。`AnimationV2TransformScratch` 使用 double intermediates 完成 additive/interpolation，最后才作 finite/positive float publication；`NEXT_CYCLE` detail（含 prefix/ellipsis）上限为 256 UTF-16 code units，并避免 surrogate split。formal r9 review 的结果是 **FAIL（0 Critical / 0 High / 1 Medium / 2 Low）**，不能写作 review PASS、integration ready 或稳定 API/SPI 结论。

r10 fresh review 实际为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**。其 Medium 是同一 ADDITIVE 契约的两处独立根因：Runtime 将合法 `layer.weight × mask.weightAt` 先作 `float` 乘法，dyadic subnormal effective weight 会在进入 accumulator 前归零；Scratch 又在 additive translation/scale 与 quaternion relative-delta/Hamilton 路径的最终 publication 前收窄中间值，合法 subnormal 与 weight-one group endpoint 会丢失。其 Low 是 generation 的有限模 `long` acknowledgement 在精确 `2^64` rejection 后 ABA，可能静默吞掉 capture 后的 suffix diagnostic。

r11 candidate 在所有参与 ADDITIVE accumulator 的 layer/mask、translation、scale、relative quaternion delta、slerp 与第二次 Hamilton 运算前使用 primitive `double`，只在统一 finite/positive guard 处回到 `float`；`weight == 1` 仅在 current/rest 为同一 canonical group rotation 时直取 canonical sample，其他 override/prior-additive base 继续走 general double product。overflow 改为 rejected offer 才创建的私有 ticket：producer 的 `AtomicReference` set 是线性化点，owner 只以 captured ticket identity 的 CAS acknowledgement 清除它，故同 command reference、不同 suffix、capture/commit 和 exact `2^64` 都不依赖有限 generation。普通 accepted ingress 不分配 ticket，public/protected surface 不变。`x3-r11-final-hash-design-audit-20260809-022255355` 的 **NON-VERDICT — NO-BLOCKER（0C/0H/0M/0L）** 仅是冻结候选审计，不能替代 formal r11 review 的 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**；formal r12 review 同样为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，fresh independent r13 review 才为 **PENDING**。

### r12 formal failure 与 r13 双层 quaternion endpoint repair（candidate only）

formal r12 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**。其 Medium 是未经已批准 source-of-truth/Accepted ADR 授权而改变 stable public `Transform` value semantics：T10 让近单位 quaternion 不再无条件走稳定的 `Quaternion.normalized()` publication。Proposed/Experimental expansion ADR 不能自行批准这种 stable public value delta。r13 恢复 parent 的无条件 `rotation.normalized()` 构造语义；不批准 T10，也不再声称 public `Transform` raw-preserving。

X2/X3 的 exact endpoint 现在明确分为两层：`AnimationV2TransformScratch` 在 `weight == 1`、current/rest 同 canonical group rotation 时保存 canonical raw sample；fully-public runtime 随后经 `Transform` 发布时必须得到稳定的 `Quaternion.normalized()` projection。固定 r11 sample、q/-q、signed zero、subnormal、permutation、current!=rest、override 和 prior-additive 仍由确定性/rotation-equivalence/atomicity 测试覆盖。Scratch raw bits 是内部 canonical endpoint，不是 public `Transform` 的稳定 raw-bit promise。r12 Low 也保留为历史错误：其 `9.000002953` 低于精确保守值 `9.000003072433528` ULP，且 r12 evidence 未绑定三份 code/test hash 与 artifact；这不是 r13 的 T10 证明。fresh independent r13 review 为 **PENDING**，不是 PASS。

- `BoneSchema` 和 `BoneMask` 在配置期完成名称解析、重复检查和数值验证；热路径仅按骨骼 index 作 O(1) mask 读取。
- 一个 `AnimationV2InstancePlan` 在运行前冻结。controller 以 `(priority ASC, canonical id ASC)` 排序；layer 同样排序。
- callback 线程只能向固定容量的 `ArrayBlockingQueue` offer 不可变 `AnimationIntent`/`AnimationV2Command`；offer 返回 `QUEUED` 或 `QUEUE_OVERFLOW`，owner 也发布 overflow/drain diagnostic，不存在静默丢弃。
- owner 在一个 snapshot boundary 捕获完整的有界 ingress（callback queue 至多 256 条；core 还可合并至多 256 条 synchronous frame command），再按 `(controller id, sequence)` 对整个 snapshot/backlog 建组。每帧至多推进 128 个**完整组**；未入预算的组完整留在有界 owner backlog，绝不静默丢弃或只发布组前缀。完全相同的命令只应用一次，任一字段不同的同组命令整组 fail-closed。r9 candidate 在 owner-only `FrameStage` 中复制 live controller/backlog 后处理 captured prefix、rejection 与 command group，并在 complete immutable snapshot 和 commit capacity 已准备后才消费 prefix、提交 live owner state。stage 可发生 disposable mutation；pre-commit failure 保留 live/observable owner state 和 captured async prefix，direct failing frame command 不入队。已发布序列后来收到 equal/newer 的冲突或非法值时会撤销到初始中立状态；低于 watermark 的 stale 值在任何破坏性路径前返回 stale，零 retention/state 变更。formal r9 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 2 Low）**；r10 的 generation equality 方案已被 exact `2^64` ABA Low 否定，r11 的 captured-ticket CAS 语义与 formal r11 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**、formal r12 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）** 及 fresh independent r13 **PENDING** 状态以上节为准。
- client 每帧先推进旧 controller，再在该 frame boundary 应用 due 的绝对 playhead command；因此 `startTick` 计算出的绝对时间不会再消费同一帧 delta。
- owner 每次推进发布一个不可变 `AnimationV2EvaluationSnapshot`；读取者通过原子引用观察，不能接触可变 playhead。
- 每个 owner advance 还冻结最小的 observer-only traversal：每 controller 至多 128 段真实 automatic forward interval，段内 occurrence 严格单调，LOOP wrap 才推进 loop epoch。snapshot 同时带 immutable rolling continuation：每个真实 non-empty publication 记录 exact source revision 与前后 `(timeline,time,revision range,loopEpoch,occurrence)` anchor；连续 no-movement revision 合并到 anchor range，不消耗 segment budget。X3 只能从已成功 commit 的 exact anchor 向后消费连续 publication；预算内未交给 X3 的一到多次 publication 可精确 catch-up，找不到 anchor、旧 anchor 被 128-segment budget 剪掉、trace truncation 或 command/sequence discontinuity 时必须 fail closed，不能只接受最新 segment。automatic `next` 的 entry 与剩余时间会被逐段记录；command/sequence rejection 明确标为 discontinuity 并重置 continuation，不伪造 seek crossing；超出 trace budget 清空 partial segment、发布 `OBSERVER_TRAVERSAL_TRUNCATED`。这是给 bound X3 provenance consumer 的只读事实，不提供 X2 mutation/callback 能力。既有 public 四参数 `AnimationV2EvaluationSnapshot` 构造器仍保持兼容并产生 empty observer trace；observer 类型没有 public mutable constructor/mutator，X3 仍必须以 `latestSnapshot()` object identity 验证 runtime provenance。
- playhead 与 observer traversal 的 clip 边界使用 exact representable-double 语义，而不是 `EPSILON`：严格小于 duration 的 candidate 保持其真实值，等于 duration 恰跨一次，`nextUp(duration)` 把真实 residual 带入下一 LOOP 或 automatic-`next` state。LOOP terminal 仍使用同一 `% duration` 的实际 remainder；completed-wrap identity 不再把商先舍入回 binary64。runtime 将两个有限非负 binary64 值拆为 significand/exponent：常见指数关系走 primitive 无分配除法，正 exponent shift 的慢路径做至多 116 位的固定二进制长除，先以 bit width 证明是否超过 `Long.MAX_VALUE`。因此所有可装入非负 `long` 的 exact quotient 都准确；不可装入 quotient 或累计 `Math.addExact`/`Math.incrementExact` overflow 都在任何 transition、counter、snapshot、revision 或 X3 provenance 可观察事实前以稳定 `v2 controller loop count overflow` fail closed。这个慢路径不分配 `BigInteger`/数组，也不随输入时间大小循环。`EPSILON` 仍只服务于 clip compatibility、transition/interpolation 和 quaternion 等非 timeline 数值域。
- 同 priority override 的 quaternion 先选一个共享 hemisphere reference：若 rest 有非零权重则用规范化 rest，否则用 controller/layer canonical 顺序的首个有效 winner；其余贡献按与 reference 的 dot 对齐，dot 接近零时使用 canonical tie-break，最后规范化为 canonical 表示。更高 controller priority 覆盖较低 priority；additive 在 override 后按固定顺序叠加。任何 non-zero exclusive layer 与任一其他 non-zero override/additive 贡献冲突，overlap、exclusive、低 priority 抑制和 no-op 都有结构化诊断。
- controller/layer 的 static diagnostic 在各自 canonical 排序后收集，再进入每 snapshot 64 条的有界 collector；反序等价配置不能改变保留集合或 truncation marker。
- `LOOP`、`ONCE`、`HOLD` 与 `next` 链都在 core 内部执行；`HOLD` 正常推进至 clip 末端后保持。中断中的 crossfade 会冻结当前已混合 presentation，而不是回退到 raw source。`next` 环和单次推进的 transition 数均有硬上限与诊断。
- `AnimationIntentScope(sessionId, worldId, instanceKey, generation)` 的入站值必须完全相等，且每条 intent 必须在 `(controller id, sequence)` 建组、plan 校验、watermark、retention 或 client core sequence rejection 之前独立 fence；错误 scope 只能得到 `SCOPE_REJECTED`，不得加入 active group 或消耗其完整组预算。只有 session/world/typed instance 都相同的 generation-only plan replacement 可携带已接受语义 intent，随后必须按新 controller whitelist 和新 plan 重新校验；旧 generation 的排队 callback 仍被拒绝。session/world/instance 变化、disconnect 和 rescope 都清理 retention。

## 硬限制与失败语义

| 项目 | X2 上限/规则 |
|---|---|
| controllers / layers / states | 16 / 16 / 128 |
| bones / keyframes | 4096 / 4096 |
| speed | 有限且在 `1/64..64`；完整 state × intent 结果也必须在该区间，client 以 `PLAN_REJECTED`、core direct command 以 `COMMAND_RATE_REJECTED` 拒绝 |
| layer 与 mask weight | 有限且在 `[0, 1]`；重复 mask/name/index、越界、NaN/Infinity 立即拒绝 |
| clip / transition / advance | 600 s / 60 s / 单次 600 s（advance 记录 clamp diagnostic） |
| diagnostics / next 链 / observer traversal | 每 snapshot 64 条 / 单次推进 64 次 / 每 controller 每次 advance 128 段 |
| controller / animation semantic identifier | core state/command/rejection/observer 与 common intent 的 controller/animation `BlendResourceId`/`BlendAnimationKey` 均最多 256 UTF-16 code units；256 接受，257 拒绝 |
| callback ingress / owner scheduler | callback queue 256 条；每 frame 至多 128 个完整 `(controller, sequence)` 组；core 单次 captured raw snapshot 至多 512 条（完整 callback queue + 完整 synchronous frame batch），另有 256 条 deferred frame buffer 且满时显式 backpressure；overflow、backpressure 与延期均有显式 outcome/diagnostic |
| retained controller | 仅 frozen-plan whitelist 中的 controller，最多 16；未知 controller 在 retention 前拒绝 |

时钟使用有符号 tick 差：正常倒退 clamp 到零，而 `long` 自然回绕产生的小正差仍可前进。late join 的 playhead 以 `(currentTick - startTick + partialTick) / 20` 加上已冻结 state 与 intent rate 计算。

## 已验证的自动化证据

已通过：

- `:blendlib-core:test`：mask/weight/speed/limit 拒绝、256/257 UTF-16 semantic identifier fence、override/additive/mask/crossfade、priority/exclusive conflict、LOOP/ONCE/HOLD/next-cycle、full-semantic command group、非 FIFO 邻接的 `0/128` conflict、完整组跨帧延期、over-budget frame command 不丢组员、late same-sequence 中立撤销、frame boundary、interrupted manual/automatic blend、共享 reference 的 `+170/-170`、rest/sample、q/-q、180-degree、dot-zero/near-zero quaternion、canonical static diagnostic 截断、effective-rate lower/upper bound、bounded command ingress，以及黄金 pose/playhead。
- `:blendlib-fabric-common:test`：persistent replay、full semantic duplicate/conflict permutation、stale conflict/plan-invalid 在 watermark 前短路且不删除较新 persistent、controller whitelist、plan-before-retention、generation-only rebind、新 session/world/instance fence、错误 session/world/instance/old-or-future generation 与 active duplicate 同批时的逐条 scope fence、disconnect 和 semantic source boundary。
- `:blendlib-fabric-client:test`：tick/partial/late/future/wrap/large-delta frame boundary、concurrent callback conflict、非 FIFO 邻接的 `0/128` conflict、complete-group over-budget deferral/liveness、late same-sequence core neutralization、错误 scope 与 active duplicate 同批时保持 active core state/replay、bounded ingress overflow/drain、generation reload、stale queued callback、replacement-plan/effective-rate rejection、scope replacement、disconnect 和 client source boundary。
- r8 exact-long 定向回归：odd `>2^53` finite-long identity、exact `2^63` fail-closed、270-case exact-rational sweep（从 `26` 个 finite-long mismatch / `6` 个 overflow acceptance 归零）、`Double.MIN_VALUE`/subnormal、automatic-next tiny LOOP、multi-controller 后置 overflow，以及 `Long.MAX_VALUE` direct/queued rejection-command transaction。这些是 r8 的 narrow implementation-side evidence；r8 独立 review 后来仍以 broader frame defects 判定 FAIL，不能把原先的 preflight 叙述或 scratch-shape 估算当作当前 r9 结论。
- 热路径结构证据：`AnimationV2SourceBoundaryTest` 对 `evaluate` 子段断言按 bone scratch sampling、无 `LayerSample`、无临时 layer `ArrayList`、无临时 `AnimationV2Pose`；`AnimationV2ReviewerRegressionTest` 运行 1024 个稳定帧并验证确定性和有限输出。该证据不等同于硬件 allocation/CPU 基准。

这些测试是源码和 JVM 单元证据，不是视觉、双客户端、真实网络、真实 reload 或性能 Gate 证据。

## r9 implementation-side evidence through #13

- `r9-03-focused-red` 是保存的 focused TDD red：`62 tests / 6 failures`；它不是独立 clean-baseline certification。
- `r9-08` 的首次 preflight 失败，不计作 green evidence；授权 resumed focused run 为 `69 tests / 0 failures / 0 errors / 0 skipped`，仍绑定 dirty candidate。
- `r9-09` 只保存 `AnimationV2FrameAtomicityTest` 的 `10 / 0 / 0 / 0` XML coverage snapshot，没有独立 terminal Gradle capture。
- `r9-10a` full core 为 `29 suites / 241 tests / 0 / 0 / 0`；`10b` 的原 multi-task 命令实际 fresh 运行了 `39 suites / 276 tests`，其中 `8 / 51 / 0 / 0 / 0` 仅是其 expected-XML 子集，不能把整条命令称为 filtered matrix；`10c` X3 filtered matrix 为 `11 / 91 / 0 / 0 / 0`；`10d` consumer fixtures 为 `4 / 9 / 0 / 0 / 0`。
- `r9-11` root `check` 为 7 modules、`114 / 832 / 0 / 0 / 0`；`r9-12` 是 candidate/parent boundary、source/bytecode/classload/legacy-consumer evidence，不是 live client/reload/visual proof。
- `r9-13-pre-doc-build-release` 是文档冻结前 baseline：`buildRelease --rerun-tasks` exit 0、62 tasks、root-seven `114 / 832 / 0 / 0 / 0`、local-Maven `2 / 5 / 0 / 0 / 0`、合计 `116 / 837 / 0 / 0 / 0`；SHA256SUMS 为 `17 valid / 17 unique / 0 malformed / 0 duplicate / 0 missing / 0 mismatch`，SHA-256 `C51C16DFD6E2D210982D44CB77A2E9DB66B24FCF4F5683E248350EC84A3AFBBE`。它不是 docs-bound PASS。最终 binding 仅由在冻结文档哈希生成后产生的外部 `#15 final-docs-bound-build-release` 和 `#16 final-release-sha` 决定；本文不预断其结果，artifact 缺失或不匹配即 binding 未证明。
- r10 的正确 task-bound X2 替代证据使用三个独立、任务紧邻 filters 的命令，严格 XML 为 `8 suites / 51 tests / 0 / 0 / 0`；它是 implementation-side candidate evidence，不是 independent review。r9 `#10b` 的原 multi-task 命令实际为 `39 / 276`，r10 对该错误 scope 的 replay 为 `39 / 280`，两者均不能冒充 task-bound `8 / 51`。r10/r11 的 focused red/green、full core、X3、consumer、root check 与 boundary 数字见 [X3 test evidence](../x3/test-evidence.md)。

## Gate 状态

WAITING：新的版本化 X2 payload/adapter、真实 client lifecycle/reload ownership、X4 host application、X6 variant/material consumption、单/双客户端 visual 证据、20-reload、Iris/Sodium，以及硬件 allocation/CPU/long-run 测量都尚未接入或执行。r8/r9/r10/r11/r12/r13 的 JVM、source、bytecode 和 package evidence 不替代这些 Gate；r9 formal review 为 **FAIL（0 Critical / 0 High / 1 Medium / 2 Low）**，r10 fresh review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，formal r11 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，formal r12 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，fresh independent r13 review 为 **PENDING**。X2 不改变 P0--P8 或扩展台账状态。

后续共享接线的精确限制见 [integration-handoff.md](integration-handoff.md)。
