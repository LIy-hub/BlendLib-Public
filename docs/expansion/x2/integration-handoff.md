# X2 integration handoff（尚未接线）

本文件是给后续共享集成任务的约束，不代表以下任何接线已完成。

## 必须保留的不变量

1. 不得修改或复用既有 v1 `SyncedAnimationState` payload 语义来偷运 X2 controller/scope/generation 字段；若确有网络需求，新增独立、明确版本化的 X2 semantic payload 和 codec，并为 malformed/unknown/version mismatch 建立拒绝测试。
2. 不得把 `AnimationV2InstanceRuntime.advance`、`ClientAnimationV2Runtime.advance` 或 pose sampling 放进网络 callback、reload listener、renderer 或任意 registry/asset discovery 路径。
3. 不得在现有 `BlendLibClientEntrypoint`、v1 sync store、v1 controller 或 X1 stable facade 中隐式启用 X2。启用点必须是新的显式 Experimental integration。
4. X2 snapshot 不含 renderer、world、resource handle 或 payload。未来 X4/X6 只能消费不可变 snapshot；它们不能回写 X2 playhead 或从 render path 改变 controller state。

## 未来接线顺序

1. **冻结计划发布。** 一个未来的 reload apply owner 在真实 generation 发布后创建 `AnimationV2InstancePlan`。该 owner 必须先完成全部 asset/profile/adapter 验证，之后才在 client owner 上调用 `ClientAnimationV2Runtime.install(plan, scope)`；不得在 `advance` 中加载或发现计划。
2. **scope 建立。** platform adapter 生成 `AnimationIntentScope`：新连接 session、当前 world/dimension identity、完整 typed `BlendInstanceKey`、以及发布 generation。session/world/instance 任一值变更都必须 retire/`disconnect` 旧 runtime；仅 generation 变化可由 `install` 携带已接受 intent，且只能在新 frozen whitelist 与 plan revalidation 后重放。不能用裸 entity id 复用旧状态。
3. **语义 ingress。** 未来独立 X2 codec/adapter 仅构造 immutable `AnimationIntent` 后调用 `receiveIntent`，并处理 `QUEUE_OVERFLOW`。callback 可跨线程，但不能直接调用 `advance`、解析资源或触碰 X4/X6 渲染对象；owner 在 snapshot boundary 捕获完整的 256 条 bounded queue，并对每条 intent 先作 exact scope fence。错误 scope 只能发布 `SCOPE_REJECTED`，不得与 active `(controller, sequence)` 建组、消耗其 128 组预算、影响 watermark/retention，或产生 core sequence rejection；仅 active scope 再处理至多 128 个完整组。预算外 active 组必须整体保留到下一帧，不能改为 FIFO 前缀发布、无界 drain 或静默丢弃。r9 candidate 不再把 whole-frame safety 表述为 r8 式的“only preflight before mutation”：在 next revision check 后，owner 把 controller/backlog 复制到 owner-only `FrameStage`，在 stage 上完整处理 captured prefix、rejection、command、diagnostic 与 pose，构造 complete immutable snapshot 并准备 live commit capacity；仅此后才消费 captured FIFO prefix 并提交 live controller/backlog、revision 与 `latestSnapshot()`。stage 可以可变，保证只限 pre-commit failure 不改变 live/observable owner state；async captured prefix 失败后仍留队，direct frame call 的失败则不入队。later equal/newer conflict/plan rejection 必须把既有 sequence 撤销为中立状态；stale sequence 必须先于任何 retention/state 删除短路。formal r9 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 2 Low）**；r10 fresh review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，formal r11 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，formal r12 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，fresh independent r13 review 为 **PENDING**。

   r10 的 finite-generation equality acknowledgement 已被精确 `2^64` ABA Low 否定：capture 后 producer suffix 即使回到同一 generation 也可能被错误清除。r11 只在 bounded offer 已拒绝时创建新的私有 ticket，producer `AtomicReference` set 是线性化点，owner 仅以 captured ticket reference 的 CAS acknowledgement 清除它；同 command reference 的重复 rejection 也不能复用身份，distinct suffix、失败/retry 和连续 owner frames 都保留 pending diagnostic 的一次性语义。r10 Medium 的两处 ADDITIVE 根因也必须同时关闭：Runtime 的 effective layer×mask 在任何乘法前升为 `double`，Scratch 的 translation/scale、relative quaternion delta、slerp 和第二次 Hamilton 都保持 primitive `double` 至最终 checked publication；不可表示/non-finite/non-positive scale 仍 fail closed。r11 final-hash design audit 为 **NON-VERDICT — NO-BLOCKER（0C/0H/0M/0L）**，不能写作 independent PASS；formal r11 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，formal r12 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，fresh independent r13 review 才为 **PENDING**。

### r12 formal failure 与 r13 quaternion endpoint handoff（candidate only）

formal r12 的 Medium 是 r12 以 T10 改写 stable public `Transform` value semantics，却没有获批 source-of-truth/Accepted ADR。Proposed expansion handoff 不能批准这类 stable public delta。r13 恢复 stable parent 的无条件 `rotation.normalized()` publication；T10 不被批准，`Quaternion.normalized()`、Runtime、Scratch、ABI、任何 public/protected descriptor 与 class inventory 均未改变。

该 handoff 不接受 raw-preserving public `Transform` delta：固定 `[0,0,0,0x3F800004]` 仍按稳定语义发布为 `0x3F800000`。X2/X3 的 exact endpoint 改为清晰的两层：Scratch 保留 canonical raw sample；public runtime 的 `Transform` 是该 sample 的 `Quaternion.normalized()` 稳定 publication projection。q/-q、signed zero、subnormal、permutation 及 current!=rest/override/prior-additive 的 general/atomic/deterministic controls 继续测试。r12 Low 的 `9.000002953` 是低于精确保守 `9.000003072433528` ULP 的历史文档错误，且 r12 evidence 未绑定三 hash/artifact；不得作为当前证明。fresh independent r13 review 为 **PENDING**，不是 acceptance。
4. **唯一推进点。** 一个明确的 client/extraction owner 每帧提供 `ClientAnimationV2FrameTime` 并调用 `advance` 一次。X4 host adapter 从 `latestSnapshot().evaluation()` 读取已发布快照；没有快照时按其自身 documented missing/skip contract 处理。
5. **persistent replay。** tracking/rebind 仅从相同 session/world/typed-instance 的 generation-only replacement 后 `persistentReplay()` 获得排序且已重新验证的 intent。rescope、disconnect、world/instance/session change 后 replay 必须为空；旧 generation callback 不可跨 scope 转发。
6. **X6 接口。** X6 variant/material 工作只可把 snapshot 的确定性 controller/playhead/pose 作为只读输入；variant/material 决策不得改变 X2 sequence、mask、layer priority 或 timing。任何 material resource 必须在其自己的 prepare/apply generation 中冻结。

## 必需的后续证据

- 新 payload 的 version/unknown/oversize/sequence/scope negative tests；不得修改 v1 wire fixture。
- lifecycle/reload 的 generation-only rebind/revalidation、disconnect、dimension change、entity-id reuse、late callback、queue overflow、complete-group drain-budget、late conflict neutralization 与 stale non-mutating tests。
- X4 host 对 snapshot 的真实单客户端和双客户端 visual evidence；X6 的 variant/material 组合 evidence。
- 20-reload、Iris/Sodium、性能采样和 CPU fallback 的独立 Gate 证据。

`r9-13-pre-doc-build-release` 是文档冻结前实现侧 baseline：`buildRelease --rerun-tasks` exit 0、62 tasks、root-seven `114 / 832 / 0 / 0 / 0`、local-Maven `2 / 5 / 0 / 0 / 0`、合计 `116 / 837 / 0 / 0 / 0`，独立 SHA256SUMS 复算 `17 valid / 17 unique / 0 malformed / 0 duplicate / 0 missing / 0 mismatch`（SHA-256 `C51C16DFD6E2D210982D44CB77A2E9DB66B24FCF4F5683E248350EC84A3AFBBE`）。它不使 X2 成为 stable API，也不是 final docs-bound PASS。最终 binding 仅由在冻结文档哈希生成后产生的外部 `#15 final-docs-bound-build-release` 与 `#16 final-release-sha` 决定；本文不预断其结果，artifact 缺失或不匹配即 binding 未证明。

formal r9 review 已是 **FAIL（0 Critical / 0 High / 1 Medium / 2 Low）**；r10 fresh independent review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**；formal r11 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，r11 final-hash audit 仍只是 non-verdict；formal r12 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**；fresh independent r13 review 为 **PENDING**。在上述证据完成且由独立 reviewer 通过前，X2 的状态保持 Experimental/internal，Gate 保持 WAITING。
