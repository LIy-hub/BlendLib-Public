# X3 设计：冻结程序化 frame

本文件描述 Experimental candidate 的可验证语义；它不把任何 X3 类型提升为 stable API。

## 1. 配置期、精确 scope 与 revision

`ProceduralRigPlan` 在配置期冻结 `(modelKey, generation, BoneSchema)`、parent slots、socket/surface slots、hook 和 plan attachment。字符串解析、资源加载、provider discovery 都不在 `evaluate` 热路径中。

`ProceduralRigRuntime` 必须在构造时接收**一个精确不可变** `ModelInstance`。该 instance 的 typed instance key、model key 和 generation 都与 runtime 绑定；同 model/generation 的 sibling instance 也会被拒绝。generation replacement、rescope 或 disconnect 必须创建新 runtime，不能复用旧 `latest` 或 event replay state。

每个成功发布 scope 的 input revision 必须严格大于 runtime 的水位线；等值和倒退 revision 以 `STALE_REVISION` fail closed。水位线与 `latest`、visual-event cursor 和 replay fence 只在 final graph commit 的同一线性化点前进；因此任一失败 frame 都不替换 `latest`，也不消耗 revision，调用方可以用同一 exact X2 snapshot 在修正 frame 输入后重试。

每帧 `ProceduralFrameInput` 只捕获完整 `ModelInstance`、已发布的 `AnimationV2EvaluationSnapshot`、最多 256 directives、最多 64 非 child attachment 与最多 128 raw visual-event entries。caller iterable 一次性有界冻结；超限、`RuntimeException` 或 hostile `AssertionError` 都成为 bounded failure。raw visual event 不是发布入口：只要它非空，runtime 就以 `VISUAL_EVENT_PROVENANCE` fail closed。

## 2. 真实 child-model 图，而非 caller lineage

`ProceduralAttachmentGraph.compile` 只从每个 owner model 实际拥有的 `ProceduralAttachmentDescriptor.ChildModel` 边导出有向图；descriptor 不再接受 self-reported ancestor chain。

- graph 在配置期接收一个 non-empty、单 generation 的完整 owner map：每个 `CHILD_MODEL` target 也必须是 map owner。它迭代地拒绝自环和任意 directed cycle；最长路径以**边数**计，8 条边合法，9 条边拒绝；owner/edge 各最多 4096，因此 20k hostile input 以 `IllegalArgumentException` 而非 VM stack failure 结束。
- plan 的直接 child 边必须与 graph 中该 model 的直接 child 边精确一致；所有 owner plan 必须注册到**同一 graph identity**并一次 `publish` 后才可建立 child runtime。两份同 generation 的局部 `A→B`/`B→A` graph 不能被组合成 reload。没有 `CHILD_MODEL` 的单模型 plan 才可取得安全默认空 graph。
- multi-owner 或含 child edge 的 complete publication 还必须取得 class-loader-wide generation claim：同 generation 的第二个 claim-required graph identity 即使自身完整、无环也拒绝，避免 `G1: A→B` 与 `G2: B→A` 的 runtime 跨 graph 重新组合成环。无 child edge 的单 owner 默认 graph 不占 claim；exact same complete graph/plan set 可幂等 publish，different generation 可并存。claim table 最多 256 项，只弱引用 graph；显式 `close()` 才是正常释放路径。
- graph `close()` 永久且幂等。partial/foreign publish、同代冲突、容量失败发生在状态提交前，graph 不泄漏 claim，并可在前置条件修复后重试；retired graph 则永不可重用。所有锁只按 `graph -> generation registry` 获取，registry 不回调 graph。
- frame attachment 可以是 `ITEM` 或 `BLOCK`，但不能临时注入 `CHILD_MODEL`；child topology 只能来自已验证的 plan graph。

这样 frame evaluation 不需要、也不会相信 caller 声称的 ancestry。

## 3. 评价顺序与 canonical directive

同一帧 directive 先按 `(priority ASC, source id ASC, complete operation canonical key ASC)` 排序。canonical key 覆盖每个 operation 的全部语义字段；quaternion 先 normalize，再统一 q/−q 的符号并清除 `-0.0`。完全相同的 semantic directive 只保留一份；同 priority 对同一 exclusive 属性（look-at、bone visibility、surface visibility、surface override）的不同写入为 `HOOK_CONFLICT`。

执行顺序固定：

```text
X2 immutable local pose
  -> sorted Offset
  -> sorted LookAt
  -> sorted RotationOffset (experimental IK output)
  -> hierarchy model transforms
  -> bone visibility + parent propagation
  -> semantic surface visibility/override
  -> final sockets
  -> attachments
  -> typed visual-event resolution/replay fence
  -> deep-frozen ProceduralFrameSnapshot atomically published
```

`Offset` 只允许有限 translation、canonical quaternion 与正 uniform scale multiplier（`1/64..64`）。这个上下界只约束 multiplier，不重定义 strict-v1 base scale 的合法范围：合法的 `0.001`、`20000` 在 multiplier `1` 时原样保留，且 `(1,1.000005,1)` 或 `(1e-7,1.09e-6,1e-7)` 这类已被 `Transform` strict-v1 校验接受的逐分量值也必须 value/bit 精确保留。非 1 multiplier 对 `base.scale.{x,y,z}` 各自相乘后才交给唯一的 absolute strict-v1 `Transform` 校验；任一轴 zero-underflow、overflow 或 non-finite 都 fail closed。`LookAt` 使用 `+Y` up、`+Z` forward、正 `+X` yaw；degenerate target 保留旧朝向并给 warning。`RotationOffset` 是受控 local pre-rotation，固定在 look-at 后执行。

## 4. 可见性、socket 与 attachment

bone visibility 初始 true；父骨骼隐藏会递归隐藏子骨骼。mesh/primitive visibility 初始 true，但 owning bone 隐藏时强制 false。`SurfaceOverride` 只有 semantic texture/material `BlendResourceId`。

socket 永远从最终 model transform 求值。公开查询复用 `SocketQuery`：scope mismatch 为 `SOCKET_SCOPE_MISMATCH`，隐藏为 `HIDDEN_SOCKET`，不存在为 `SOCKET_MISSING`。attachment anchor 是 bone 或 socket；resolved 输出按 attachment id 排序，缺失/隐藏 anchor 安全跳过并产生 diagnostic。descriptor 不携带 `ItemStack`、`BlockState`、world 或 renderer。

## 5. 视觉事件：provenance、冲突与持久 replay fence

唯一 typed vocabulary 为 `SOUND`、`PARTICLE`、`CAMERA_SHAKE`、`TRAIL_START`、`TRAIL_STOP`、`SOCKET_EFFECT` 和 `CUSTOM_CLIENT_EVENT`。

事件配置不是 per-frame claim。`ProceduralVisualEventMarker` 只在 `ProceduralRigPlan` 中冻结 `(controller id, X2 state/timeline, marker index, time, event id, priority, typed payload)`；带 catalog 的 `ProceduralRigRuntime` 必须在构造时绑定 exact `AnimationV2InstanceRuntime`。旧两参数构造器只对空 catalog 保持兼容，非空 catalog 在构造期以稳定错误立即拒绝。registry 在冻结时以该 runtime 的 immutable plan 验证 controller、state、clip duration 与 X3 bone schema；它在 evaluate 时要求 `evaluation == sourceRuntime.latestSnapshot()`，再验证这个 owner-produced snapshot 的 terminal playhead 和 immutable bounded observer traversal。

`ProceduralVisualEventProvenance` 由这个 private registry 发行，包含 controller id、timeline id、loop epoch、marker index、occurrence、**该实际 X2 publication 的** source revision 与真实非空 `[segmentStart, nextUp(segmentEnd))`。命中规则严格等价于 owner-produced `(segmentStart,segmentEnd]`：marker=`segmentEnd` 恰好一次，`nextUp(segmentEnd)` 与任何真正较大的 float 都不在本段发出、也不会进入 replay fence；duration validation 同样不使用 epsilon。owner 的 playhead/traversal 也必须遵守相同的 actual-double 边界：`nextDown(duration)` 仍是未到达，exact duration 只完成一次 crossing，`nextUp(duration)` 的 residual 才进入下一 LOOP/automatic-`next` occurrence。r8 将 finite non-negative binary64 拆为 significand/exponent 后求 exact completed-wrap floor：常见路径为 primitive 无分配除法，极大 exponent shift 才进入至多 116 位的固定 long division；它不以 rounded double quotient 猜 epoch。bit-width 先证明 `>Long.MAX_VALUE`，累计 counter 再以 exact add/increment 检查；上述 exact quotient/counter overflow 失败在 transition、snapshot、revision 和 provenance 之前 fail closed。terminal 仍是同一 `% duration` remainder，故 identity 与 terminal 不相互矛盾。

X2 runtime 在每 controller 每次 owner advance 至多冻结 128 段真实 automatic forward traversal，并把它们连同 bounded rolling continuation 一起冻结进当前 exact snapshot。continuation 的每个 non-empty publication 带其 source revision 与前后 anchor；anchor 是 `(timeline,time,revision range,loopEpoch,occurrence)`，连续 no-movement publication 只扩展其 revision range 而不消耗 segment budget。LOOP wrap 与 automatic `next` entry/leftover time 的每段都有单调 occurrence，LOOP wrap 额外推进 loop epoch。已 armed X3 必须使用所有已提交 observation 字段精确定位一个 continuation anchor，并消费之后每个连续 publication；一至多次 budget 内 skipped X2 publication 因此完整 catch-up。首次 late observer 仍只 arm latest terminal、不 burst historical path；完整 X3 frame 成功提交前 observation/replay 都不前进，因此 failed frame 可用 same exact snapshot 重试。

command 或 sequence rejection 使 continuation 从新 terminal anchor 重置；truncation 清除 partial trace；超过 128 real segments 时最旧 anchor 被丢弃。已 armed observation 遇到上述任一情况、copy/fake snapshot、unknown controller/state/marker、terminal facts 不匹配、空/倒转 interval、revision/scope 不一致或无法找到 exact committed anchor，均以 `VISUAL_EVENT_PROVENANCE` 整帧 nonpublishable/fail closed，绝不发布最新 segment 而静默丢 crossing。它也不隐式 re-arm：future integration 必须 retire/recreate 该 X3 runtime（新 scope 的 first observation 才 arm latest）来执行明确恢复/reset。

batch 按 `(priority, event id, complete provenance, complete payload)` canonical 排序。一次 frame 的 identity 为 `(event id, controller id, timeline id, loop epoch, marker index, occurrence)`：完全相同 event 去重并给 `VISUAL_EVENT_DEDUPLICATED`，同 identity 内容不同为 `VISUAL_EVENT_CONFLICT`。payload comparison 覆盖所有 typed fields；`CUSTOM_CLIENT_EVENT` field map 按 canonical key order 冻结，任何超过 64 UTF-16 code units 的 field key/value 都拒绝而非截断。batch 构造器不是 public；只有验证后的 resolver 能冻结它。

runtime 还持有 scope-local、无淘汰的 replay fence（最多 4096 identity）：同一已成功发布 marker identity 的后续 frame 仅给 `VISUAL_EVENT_REPLAYED`，不会再次发布。fence 将超限时以 `VISUAL_EVENT_REPLAY_FENCE_OVERFLOW` fail closed，不能静默驱逐旧 identity。crossing observation 与 fence 都只在完整 snapshot 成功发布后提交，因此失败 frame 不消耗 identity；新 runtime（rescope/reload）拥有新 fence。

`SOCKET_EFFECT` 只在 final visible socket 存在时携带 frozen transform；缺失/隐藏时被拒绝且不访问客户端对象。

### r9 review outcome、r10 findings 与 r11 frame-boundary correction（candidate only）

r8 的 exact-long identity 与 counter-overflow evidence 只覆盖相应的 exact quotient/counter domain；它没有证明所有 ordinary frame failure 都在 live owner mutation 前结束。independent r8 review 因 late revision、legal finite pose intermediate 和最大 UTF-16 automatic-next diagnostic 的 frame-consistency 缺陷判定 **FAIL（0 Critical / 0 High / 3 Medium / 1 Low）**。r9 候选先计算 next revision，然后把 live controller/backlog 复制到 owner-only `FrameStage`，在 stage 上完成 controller advance、captured ingress、rejection、command、diagnostic 和 pose；complete immutable snapshot 与 commit capacity 都准备成功后才消费 FIFO prefix 并提交 live controller/backlog、revision 与 `latestSnapshot()`。stage 是 disposable mutable storage；只承诺 pre-commit failure 不改变 live/observable owner state，不能把它写作“没有任何 state mutation”。formal r9 review 实际为 **FAIL（0 Critical / 0 High / 1 Medium / 2 Low）**。

r10 fresh review 实际为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**。Medium 的两个 ADDITIVE 根因必须分开描述：Runtime 的 effective layer×mask 先 float-multiply，合法 dyadic subnormal weight 已在 accumulator 前归零；Scratch 又将 additive translation/scale 与 quaternion delta/Hamilton 的中间量在最终 publication 前收窄 float，因而失去 legal subnormal/group endpoint。Low 是 captured/consumed finite generation 在精确 `2^64` rejection 后 ABA，capture 后 suffix 可被静默消费。r11 candidate 令 layer/mask、translation、scale、relative quaternion delta、normalization、slerp 与第二次 Hamilton 在每次乘法/乘加之前都为 primitive `double`，仅最终统一 finite/positive guard 才回到 float；weight-one 仅当 current/rest 是同一 canonical group rotation 才直取 canonical sample，避免覆盖不同 override/prior-additive base。overflow 改为 only-on-rejection 的 fresh private ticket，producer set 与 owner captured-identity CAS 是线性化点，不使用有限 generation。r11 final-hash design audit 为 **NON-VERDICT — NO-BLOCKER（0C/0H/0M/0L）**，不能替代 formal r11 review 的 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**。

### r12 formal failure 与 r13 `Transform` publication projection（candidate only）

Scratch direct-copy 不是错误：它是 X2/X3 内部 canonical raw endpoint。formal r12 review 的 Medium 则指出，r12 的 T10 private helper 让 public `Transform` 绕过稳定的无条件 normalization，未经批准地改变 stable public value semantics；Proposed expansion ADR 不可自行授权该 delta。r13 不改全局 `Quaternion.normalized()`，也不向 Scratch/Runtime 加 trusted API、preimage、iteration 或 raw-pose provenance，而是恢复 parent 的 `Transform` 无条件 `rotation.normalized()` publication。

r13 设计定义两层而不批准 public raw preservation：Scratch 中 `weight == 1` / current==rest canonical group sample 的 raw bits 可作内部精确断言；fully-public runtime 只能发布该 rotation 的 `Quaternion.normalized()` projection。故 `[0,0,0,0x3F800004]` 仍存为 `0x3F800000`，ABI、record shape、public/protected descriptors、far/zero/non-finite guard 保持 stable parent 行为。q/-q、signed zero、subnormal、permutation、current!=rest、override、prior-additive 的 determinism/rotation-equivalence/atomicity 均仍是永久测试。r12 Low 的 `9.000002953` 小于精确保守 `9.000003072433528` ULP，且 r12 evidence 未绑定三 hash/artifact；它只作为历史文档错误保留。formal r12 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**；fresh independent r13 review 为 **PENDING**。

`r9-13-pre-doc-build-release` 是文档冻结前自动化 baseline：`buildRelease --rerun-tasks` exit 0、62 tasks、`116 suites / 837 tests / 0 failures / 0 errors / 0 skipped`；SHA256SUMS 为 `17 valid / 17 unique / 0 malformed / 0 duplicate / 0 missing / 0 mismatch`，文件 SHA-256 `C51C16DFD6E2D210982D44CB77A2E9DB66B24FCF4F5683E248350EC84A3AFBBE`。这不是 final docs-bound PASS。最终 binding 仅由在冻结文档哈希生成后产生的外部 `#15 final-docs-bound-build-release` 与 `#16 final-release-sha` 决定；本文不预断其结果，artifact 缺失或不匹配即 binding 未证明。

## 6. 客户端实验 IK seam

`ExperimentalClientIkSolver` 保持 explicit opt-in、无平台类型。`ClientIkRigSnapshot.capture(plan, snapshot)` 从已验证的 X3 plan/snapshot 取得 parent graph 与 model transforms，并要求 snapshot 的 opaque plan identity 恰为传入 plan；同 model/generation/cardinality 但 hierarchy 不同的 plan/snapshot 不能混用。`ClientIkRequest.forRig` 不接收 caller 注入的 parent transform、snapshot 或 iteration count。

solver 只接受 `root -> middle -> end` 的真实 direct hierarchy，其他 index 或 unrelated bones 产生 `REJECTED`。当前 solver 是固定 one-pass analytical 计算，处理 reachable、far/near clamp 与 pole singular fallback。成功 `ClientIkResult` 只能由 package-controlled factory 创建，且必须恰好输出 root 后 middle 的两个 `RotationOffset`；rejected 结果恰好零 directive。它不产生 gameplay authority、命中、伤害、网络消息或 server state。

attachment/event presentation seam 仍是 void callback；callback failure 被隔离，不能改变已经发布的 snapshot。

## 7. 上限、诊断与所有权

| 项目 | 规则 |
|---|---|
| hierarchy depth / hooks / commands | 256 / 32 / 256 |
| diagnostics / visual events / replay fence / X2 continuity history | 64 / 128 / 4096 / 每 controller 最多 128 real forward segments |
| attachments / child edge depth / graph owners / raw descriptor visits / graph edges | 64 / 8 / 4096 / 4096 / 4096 |
| active generation graph claims | 256（weak entry；正常路径显式 `close()`） |
| identifier / diagnostic message | 256 UTF-16 code units |
| custom event fields / field text | 16 / 64 UTF-16 code units |
| translation magnitude | `<= 16384` per component |
| scale multiplier | finite, uniform, `1/64..64` |

诊断 collector 在第 64 格保留 overflow marker：前 63 格为 detail，晚到的 ERROR 优先替换 retained non-error detail，且独立 error latch 不会因 truncation 失真。因此 `DIAGNOSTIC_OVERFLOW` 不能掩盖已经发生的 ERROR。

`evaluate` 是 owner-thread-only；成功 snapshot 通过 `AtomicReference` 原子替换，所有输出 collection 都是 immutable copy。snapshot/replay/crossing 的最终 commit 在 graph lock 内再次检查 active plan，与 `close()` 线性化；退役竞态不发布半完成 frame。r8 JVM 定向测试对 odd `>2^53` identity 和 `2^63` counter overflow 的结果仅是历史 implementation-side evidence；r8 的 broader independent review 已 FAIL，formal r9 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 2 Low）**，r10 fresh independent review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，formal r11 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，formal r12 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，fresh independent r13 review 为 **PENDING**。final-hash audit 的 no-blocker non-verdict 也不等同于真实 client lifecycle、renderer、reload、network 或 hardware performance Gate，后者仍见 [integration-handoff.md](integration-handoff.md)。
