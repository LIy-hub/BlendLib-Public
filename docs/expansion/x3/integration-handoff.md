# X3 integration handoff（尚未接线）

此文件给未来独立集成任务设定边界；它**不**表示任何 Minecraft/Fabric lifecycle、renderer、payload 或 reload 接线已经完成。

## X2 -> X3

**r9/r10 findings、r11/r12 formal failures 与 r13 endpoint repair（candidate only）。** future integration 只能消费成功提交的 X2 snapshot：owner 必须先在 owner-only `FrameStage` 复制 live controller/backlog，完整求值 delta、captured ingress prefix、rejection、command、diagnostic 与 pose，构造 immutable snapshot 并完成 live commit-capacity preparation，之后才可消费该 FIFO prefix、复制 staged state、写入 revision 与 `latestSnapshot()`。stage 可以发生 disposable mutation；文档与接线不得把它表述为“所有状态从未修改”。任何 pre-commit failure 都不得改变 live/observable owner state，X3 也不得读取或推断 stage state。formal r9 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 2 Low）**。r10 fresh review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**：Runtime 的 ADDITIVE effective layer×mask 先 float-multiply、Scratch additive translation/scale/quaternion path 又过早 float-narrow，二者都会丢合法 subnormal；finite generation 在精确 `2^64` 后 ABA 是 Low。r11 candidate 要求所有 ADDITIVE double operands 贯穿 relative delta、slerp 和第二 Hamilton 至最终 checked publication，并以 rejection-only fresh ticket 的 captured-identity CAS 取代 generation。`x3-r11-final-hash-design-audit-20260809-022255355` 的 no-blocker 结论是 **NON-VERDICT（0C/0H/0M/0L）**，不是 integration acceptance；formal r11 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，formal r12 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，fresh independent r13 review 为 **PENDING**。

## r12 formal failure 与 r13 `Transform` endpoint handoff（candidate only）

weight-one/current==rest 的 direct-copy path 已在 Scratch 中保留 intended canonical raw bits；r11 Medium 的 public projection 可观察差异发生在 `toTransform()` 的 stable normalization。formal r12 的 Medium 是 T10 在 `Transform` private storage boundary 绕过该 stable behavior，且没有已批准 source-of-truth/Accepted ADR 授权 public value delta。r13 恢复无条件 `rotation.normalized()`；没有修改 `Quaternion.normalized()`、Runtime 或 Scratch。这个行为选择不替代 X2/X3 frame/ownership constraints。

integration consumer 不得把 Scratch raw endpoint 当作 public `Transform` contract：`0x3F800004` 仍稳定发布为 `0x3F800000`。两层规则是 Scratch canonical raw endpoint 与 fully-public `Transform` 的 `Quaternion.normalized()` publication projection。fixed r11 sample、q/-q、signed zero、subnormal、permutation、current!=rest、override、prior-additive 的 deterministic/rotation-equivalence/atomic controls 保持。r12 Low 的 `9.000002953` 低于精确保守 `9.000003072433528` ULP，且 r12 evidence 未绑定三 hash/artifact；它是历史错误而非 r13 证据。r13 自动化/设计审计不构成 formal PASS；fresh independent r13 review 为 **PENDING**。

1. 一个未来的单一 owner 必须先完成 X2 own frame 的 `advance` 并取得已经发布的 immutable `AnimationV2EvaluationSnapshot`；之后才可在其指定的 X3 owner thread 调用 `ProceduralRigRuntime.evaluate`。若 plan 有 visual-event catalog，runtime 构造还必须绑定同一 exact X2 runtime，evaluate 只能收到该 runtime 的 `latestSnapshot()` object，而不是 value-equal copy。integration 不得把近 duration 的 clock value 归并成 crossing：`nextDown(duration)` 没有 event/provenance/replay，exact duration 恰一次，`nextUp(duration)` 只消费 owner 已发布的真实 residual；LOOP epoch/occurrence 必须同 owner 的 modulo terminal 一致，并且只能是 exact-long binary quotient 的身份。若 owner 因不可表示 wrap 或累计 counter overflow fail closed，integration 必须保留上一成功 snapshot，不得合成、饱和或重试成伪 provenance。
2. X3 plan 必须在该 future reload/apply owner 完成 asset/profile 验证后冻结为 `(model key, generation, bone schema, hierarchy, sockets, surfaces, complete child attachment graph, visual marker catalog)`；child graph 必须从一个完整 descriptor-owner map `compile` 并由完整同-identity plan set `publish`。production generation 必须来自唯一 client resource-generation owner，而不是 session/world/instance-local counter；禁止在 `evaluate`、renderer 或 callback 内解析名称、加载资源或发现 provider。
3. apply owner 必须持有当前 published graph，并在 generation replacement、disconnect 或 shutdown 时显式 `close()` 旧 graph。建议顺序为：prepare 新 generation 的 graph/plans -> publish 新 generation -> 原子替换 owner 引用 -> 等旧引用不可达后 close 旧 graph。不要依赖 weak-reference/GC 清理；同 generation 重建必须先 retire 旧 graph，容量失败必须追查未退役 owner，不能驱逐 active claim。
4. `ProceduralRigRuntime` 的构造 scope 是完整 `ModelInstance`（model key、typed instance 与 generation），不是首帧推断或裸 entity id。generation 改变、world/session/instance rescope、disconnect 或 resource replacement 都必须 retire 对应 graph/runtime、`latest` snapshot 和 replay fence；新 scope 必须建新 runtime。含 marker 的 plan 必须使用三参数 constructor 绑定 exact X2 runtime；两参数 constructor 仅兼容空 marker catalog。
5. 平台 callback 可以把有界、immutable **directive** 数据交给**新的**显式 integration owner，但 callback 不得直接 advance X2、evaluate X3、触及 renderer、解析资源、修改 snapshot 或提交 per-frame visual-event/provenance tuple。visual event 只能从 plan marker catalog 与 bound X2 crossing 导出。未来若需要网络，必须另设独立且版本化的协议；严禁偷运既有 v1 payload。
6. 若已 armed 的 X3 收到 `VISUAL_EVENT_PROVENANCE`（history 被裁剪、trace truncated、command/rejection discontinuity、anchor 失配），integration owner 必须 retire/recreate 该 scope 的 `ProceduralRigRuntime`，由新 runtime 在 latest terminal 明确 arm；不得把一次失败评价或其后的任意 snapshot 当成隐式 reset。相反，若同一 exact X2 snapshot 仅因其他 X3 validation 失败，修正输入后可重试该 snapshot，continuity/replay 不会被失败帧消耗。

## X3 -> X4 / X6

- X4 只读 `ProceduralFrameSnapshot`；它可以在没有 snapshot 时按自身 documented skip contract 处理，但不能让 renderer 回写 pose、hook、event 或 X2 playhead。
- X6 只可消费 `SemanticSurfaceOverride` 的 semantic id，并在自己的 prepare/apply generation 内解析 texture/material/variant。X3 不管理 provider、resource handle 或 material lifetime。
- socket、attachment 和 event 都是 semantic/frozen 输出。任何 Minecraft item/block/entity、world、particle/sound/camera、GL backend 绑定都必须留在未来 platform adapter，且不得为 X3 增加 authority acknowledgement。

## client presentation 与 IK

- 未来 renderer 可以实现 X3 的 void attachment resolver / visual-event listener；listener 的异常必须不反向影响已发布 snapshot。
- `ExperimentalClientIkSolver` 仅从 `ClientIkRigSnapshot.capture(plan, snapshot)` 的冻结 hierarchy/input 产生受控 `RotationOffset` directive。future integration 必须在 X3 frame boundary 捕获其 immutable 输出，不能让 renderer 或 callback 注入 parent transform、直接改 local pose 或绕过 exact two-directive result contract。
- 不得用此 seam 发送 gameplay result、命中、碰撞、伤害或任何 server authority。若有此类产品需求，需新的经审批设计和独立协议。

## 仍需独立证据（WAITING）

独立 r8 review 是 **FAIL（0 Critical / 0 High / 3 Medium / 1 Low）**；formal r9 review 是 **FAIL（0 Critical / 0 High / 1 Medium / 2 Low）**；r10 fresh independent review 是 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**；formal r11 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**；formal r12 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**；r13 remediation 是 implementation candidate，fresh independent r13 review 为 **PENDING**。`r9-13-pre-doc-build-release` 的 `116 suites / 837 tests / 0 failures / 0 errors / 0 skipped` 和 SHA256SUMS `17/17` verification 是历史文档冻结前自动化 baseline，不是 integration acceptance。r9 `#10b` 的真实 `39 / 276` 与 r10 replay 的 `39 / 280` 都不能替代独立 task-bound `8 / 51`。r13 的 docs-bound release 尚未运行；最终 binding 只能由这轮九份文档冻结 hash 后唯一一次 `buildRelease --rerun-tasks` 和独立 SHA 验证产生，缺失或不匹配即 binding 未证明。

- 真实单客户端与双客户端 visual evidence：bone/slot/socket、所有三类 attachment、七类 event、hidden/missing anchor、IK reachable/clamped/singular fallback。
- lifecycle/reload：generation replacement、rescope/disconnect、late callback、bounded ingress、旧 snapshot/replay fence retire、20-reload。
- renderer compatibility：Iris/Sodium、CPU fallback、无 GL state leakage、真实 texture/material/variant combination。
- 性能：硬件 allocation/CPU 基准与长时稳定性；JVM 单测不替代这些 Gate。
- 若未来引入网络：version/unknown/malformed/oversize/scope/sequence negative tests，且必须证明 v1 payload 不受影响。

未完成以上 Gate 前，X3 保持 Experimental candidate，不改变任何已有 P0--P8 状态。
