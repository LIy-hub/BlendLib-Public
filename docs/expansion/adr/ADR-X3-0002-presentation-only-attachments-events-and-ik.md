# ADR-X3-0002：附件、视觉事件和 client IK 仅为 presentation seam

状态：Proposed / Experimental candidate。

## 背景

attachment、sound/particle/trail 等视觉效果以及 IK 很容易将 Minecraft runtime、renderer 或 gameplay authority 泄漏进 core pose 评价路径。

## 决定

X3 core 只使用 semantic descriptor：attachment 为 `ITEM`/`BLOCK`/`CHILD_MODEL` 的 id 和 bone/socket anchor；child model graph 只能由 configuration-time 的完整 generation-wide descriptor-owner map 编译，并由同一 graph identity 的完整 plan set publish。raw descriptor visit 在 dedup 前有独立 4096 硬上限，owner/unique-edge 各自仍是 4096；同代唯一 claim 与退役规则由 [ADR-X3-0003](ADR-X3-0003-generation-claim-and-retirement.md) 定义，frame 不可临时声明 child/ancestor。surface override 为 semantic texture/material id；visual event vocabulary 固定为七种 typed payload。event 只能由 frozen `ProceduralVisualEventMarker` catalog、exact bound X2 runtime/latest snapshot identity 与该 snapshot 的真实 bounded automatic traversal/rolling continuation 导出。marker 命中严格为 owner segment `(start,end]`（`end` 一次，`nextUp(end)` 延后）；owner 自身不得将 `nextDown(duration)` epsilon-snap 为 end，且 exact end/`nextUp(duration)` residual 与 non-binary modulo epoch 必须先由 X2 正确发布。armed X3 observation 以 timeline/time/revision/loopEpoch/occurrence 精确定位并 replay 每一条 budget 内连续 publication。LOOP wrap/automatic-next 使用 owner-issued identity；seek/rejection、truncation、超出 128-segment history 或 missing committed anchor 使整帧 fail closed，不得以 newest segment 成功发布并静默漏 crossing，且 future integration 必须用新 runtime 明确 reset/re-arm。raw caller/hook event 一律 fail closed。batch 是 deterministic、bounded、deduplicated/conflict-failing 的 frozen data，并受 runtime scope-local replay fence 约束；custom field maps canonicalize，过长 UTF-16 field text 拒绝而不截断。

client 只暴露 void attachment/event presentation seam。回调没有 acknowledgement 或 state-change return，失败被隔离。`ExperimentalClientIkSolver` 只从冻结 plan/snapshot 导出的 hierarchy 求解固定一轮数学；request 不接受 caller parent transform，成功结果只能是 root 后 middle 的两个受控 `RotationOffset` directives，rejected 结果零 directive；它不产生命中、伤害、网络消息或 server authority。

### r8 provenance scope, r9 review outcome, and r10 transaction boundary

presentation seam 只能接受 X2 已成功发布的 exact-long epoch/occurrence。r8 的 quotient/counter evidence 是该窄域的历史 implementation-side evidence；它不是 “all frame failures preflighted” 的证明。independent r8 review 已为 broader frame-boundary defects 给出 **FAIL（0 Critical / 0 High / 3 Medium / 1 Low）**。r9 candidate 的 qualified transaction semantics 以 [ADR-X3-0001](ADR-X3-0001-frozen-procedural-frame-boundary.md) 为准：只有 complete immutable snapshot 与 commit preparation 都成功后，live owner state 才提交；stage 不对 presentation seam 可见。若 binary quotient 不能装入 `long`、累计 counter 溢出或其他 pre-commit evaluation 失败，renderer/listener 都不得自行饱和、猜测或补造 event identity。formal r9 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 2 Low）**。r10 fresh review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**：winner/ADDITIVE effective layer×mask 在 Runtime 先 float-underflow，Scratch additive translation/scale/quaternion 又提前 float-narrow；exact `2^64` generation ABA 可吞 capture 后 suffix。r11 candidate 以 all-double additive operands/relative delta/slerp/second Hamilton 和 only-on-rejection fresh ticket identity-CAS 修复这些边界，仍只允许已提交 snapshot 驱动 presentation。final-hash no-blocker audit 是 **NON-VERDICT（0C/0H/0M/0L）**，formal r11 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，formal r12 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**；fresh independent r13 review 为 **PENDING**，不得将任一实现侧证据写作 formal PASS。

### r12 formal failure 与 r13 Transform endpoint consequence（candidate only）

r11 Medium 的 fixed sample 说明 final pose 的 Scratch canonical raw endpoint 与 public `Transform` projection 必须区分。formal r12 的 Medium 是以 double-length² T10 让 finite in-band raw quaternion 绕过 stable publication，未经批准改变 stable public value semantics。r13 恢复无条件 `Quaternion.normalized()` `Transform` publication；`Quaternion.normalized()`、Runtime、Scratch 与 ABI/public-protected surface 均未改变。

presentation consumer 不能观察或依赖 public raw-preserving delta：`0x3F800004` 仍变为 `0x3F800000`。Scratch raw bits 仅为内部 canonical endpoint，fully-public `Transform` 是 `Quaternion.normalized()` projection；q/-q、signed zero、subnormal、permutation、current!=rest、override、prior-additive 仍检查 determinism/rotation-equivalence/general/atomic controls。这不改变 event timing/provenance、attachment authority 或 gameplay seam。r12 Low 的 `9.000002953` 小于精确保守 `9.000003072433528` ULP，且 r12 evidence 未绑定三 hash/artifact；它是历史错误。formal r12 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，fresh independent r13 review 为 **PENDING**。

## 后果

- core 可保持 pure Java，future renderer 自行在 adapter 中解释 semantic id；唯一 X2 runtime identity binding 仍只读且由 source/bytecode contract 限定，不是 renderer 或 callback backchannel。
- X4/X6 可以只读消费 snapshot/semantic override，不能回写 X2/X3。
- 真实视觉、资源绑定、compatibility 和 gameplay 需求都需要后续独立 Gate，而不能由本 ADR 推断为已完成。`r9-13-pre-doc-build-release` 是历史文档冻结前自动化 baseline；r9 `#10b` 的 `39/276`、r10 replay `39/280` 不得与正确 task-bound `8/51` 混写。r13 docs-bound release 尚未运行；最终 binding 只能由本轮九文档 hash 冻结后的唯一一次 `buildRelease --rerun-tasks` 与独立 SHA 验证决定，artifact 缺失或不匹配即 binding 未证明。formal r9 为 **FAIL（0 Critical / 0 High / 1 Medium / 2 Low）**，fresh independent r10 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，formal r11 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，formal r12 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，fresh independent r13 review 为 **PENDING**。
