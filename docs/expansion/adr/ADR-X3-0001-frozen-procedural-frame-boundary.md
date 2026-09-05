# ADR-X3-0001：冻结 procedural frame，禁止回写 X2

状态：Proposed / Experimental candidate。

## 背景

程序化 bone、socket、attachment 与 visual event 需要在同一 deterministic frame 中观察动画姿态，同时不能把 renderer/callback/extension 写入已隔离的 X2 runtime。

## 决定

X3 接收已经发布的 `AnimationV2EvaluationSnapshot`，并以 `ProceduralRigPlan + ProceduralFrameInput` 生成原子发布的、深冻结 `ProceduralFrameSnapshot`。X3 owner 只读 X2；所有程序化写入都表示为有限的 `ProceduralOperation`，按固定顺序在 X3 local scratch 上执行。

为使 visual-event provenance 不可由 caller 伪造，存在一个刻意最小的 read-only binding 例外：只有 `ProceduralRigRuntime` 与它的 private `ProceduralVisualEventTimelineRegistry` 可以持有 exact `AnimationV2InstanceRuntime` identity。它们只调用 `plan()`（配置时验证 marker 的 controller/state/clip）和 `latestSnapshot()`（逐帧 object-identity 校验），再只读该 exact immutable snapshot 内由 X2 owner 产生的 bounded automatic traversal 与 rolling continuation；绝不调用 `advance`、enqueue/command/intent、callback、reload 或 render mutation。continuation 对每 controller 最多保留 128 个真实 forward segment，并用 owner-issued `(timeline,time,revision range,loopEpoch,occurrence)` anchor 证明 publication 连续性。X3 只可从自身已提交 observation 的 exact anchor 向后消费连续 publication；因此 budget 内 skipped X2 publication 可补发每个真实 crossing，不会把 latest segment 当作充分证据。marker 采用严格 `(start,end]`，没有 epsilon 右端扩张；event 的 source revision 是实际跨越该 marker 的 X2 publication。该 owner crossing 同样是 exact representable-double：`nextDown(duration)` 必须保留真实 playhead/segment/anchor，exact duration 只跨一次，`nextUp(duration)` 的真实 residual 才可进入下一 LOOP 或 automatic-`next` occurrence；非二进制 duration 的 epoch/occurrence 必须由同一 modulo terminal 推导，不能以独立 quotient floor 提前生成 provenance。command/rejection、truncation、被剪掉的 anchor 或无法定位 committed anchor 均为 nonpublishable/fail-closed discontinuity，不能隐式 re-arm；future integration 要恢复时必须 retire/recreate X3 runtime，使新 scope 的 first observation 明确 arm latest。首次 late observer 不补历史，失败 frame 不提交 observation/replay，same exact snapshot 可重试。source 与 `javap` bytecode contract 固定允许文件、允许方法与禁止 mutation；其余 X3 procedural 类不得引用 X2 runtime。

scope 在 runtime 构造时绑定为完整 `ModelInstance`（typed instance、model key、generation），再加严格单调的**已成功发布** evaluation revision。不能以首帧或 sibling instance 复用 scope；scope/generation/revision/cardinality/error 失败不能替换 `latest`。revision watermark、event observation 与 replay fence 只在 final snapshot commit 一起更新，因此失败 frame 不消耗同一 exact X2 snapshot 的重试资格。generation replacement/rescope 必须创建新的 runtime，因此 snapshot 和 event replay state 都不跨 scope 存活。graph 的同代唯一 claim、显式退役及 retire-vs-commit 线性化见 [ADR-X3-0003](ADR-X3-0003-generation-claim-and-retirement.md)。X3 runtime 是 single-owner evaluation；并发读取只读取 snapshot。

### r8 exact-wrap scope, r9 review outcome, and r10 frame transaction

r8 的 exact binary64 significand/exponent quotient 仍是 narrow historical fact：可装入非负 `long` 的 epoch/occurrence 不应经 rounded `double` identity 推导，terminal 仍采用同一 `% duration` remainder。它不证明 r8 对所有普通 frame failure 都在 live owner mutation 前结束。independent r8 review 已以 **FAIL（0 Critical / 0 High / 3 Medium / 1 Low）** 记录 late revision、legal finite pose intermediate 与最大 UTF-16 automatic-next diagnostic 的 broader frame-boundary failures。

当前 r9 candidate 在 owner-only `FrameStage` 上复制并评价完整 prospective frame，先得到 complete immutable snapshot、完成 commit capacity preparation，再消费 captured FIFO prefix 并提交 live controller/backlog、revision 与 `latestSnapshot()`。stage 可发生 disposable mutation；承诺仅限 pre-commit failure 不改变 live/observable owner state。formal r9 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 2 Low）**。r10 fresh review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**：Runtime effective layer×mask 的 float multiply 和 Scratch additive translation/scale/quaternion 中间 float publication 是同一 ADDITIVE Medium 的两个根因；finite-generation overflow capture 在 exact `2^64` 后 ABA 是 Low。r11 在该 transaction 上使用 pre-multiply primitive double through translation/scale、relative delta、slerp 和 second Hamilton，最终才 checked publication；只有 current/rest 相同 canonical group rotation 的 weight-one endpoint 直取 canonical sample，其他 base 不被覆盖。overflow 则使用 rejected-offer-only fresh ticket，captured identity CAS 不能误清同 reference/different suffix 的后继 rejection。final-hash design audit 的 no-blocker 结论是 **NON-VERDICT（0C/0H/0M/0L）**，formal r11 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，formal r12 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**；fresh independent r13 review 为 **PENDING**；X3 只消费已成功提交的 snapshot，绝不从 stage 推断 provenance。

### r12 formal failure 与 r13 Transform endpoint amendment（candidate only）

formal r11 Medium 的 fixed case 表明：weight-one/current==rest canonical sample 可被 Scratch raw-copy，随后 public `Transform` publication 可按 stable normalization 投影。formal r12 的 Medium 是 T10 私有 storage branch 绕过该 stable publication，未经批准改变了 public value semantics。r13 恢复 parent 的无条件 `rotation.normalized()`，不修改 `Quaternion.normalized()`、Runtime、Scratch、ABI、record shape 或 public/protected surface。

ADR 不接受 public raw-preserving delta：`[0,0,0,0x3F800004]` 继续按 stable parent 发布为 `0x3F800000`。两层 endpoint 为 Scratch canonical raw bits 与 fully-public `Transform` 的 `Quaternion.normalized()` projection；固定 r11 sample、q/-q、signed zero、subnormal、permutation、current!=rest、override、prior-additive 继续覆盖 rotation-equivalence/general/atomic/deterministic behavior。r12 Low 的 `9.000002953` 小于精确保守 `9.000003072433528` ULP，且 r12 evidence 未绑定三 hash/artifact；它是历史文档错误。formal r12 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，fresh independent r13 review 为 **PENDING**。

## 后果

- 可对 pose、socket、visibility、attachment 与 event 使用同一个 final frame，而不是各自重新采样。
- extension 得不到可变 pose/X2 runtime，hook/raw per-frame event 不能伪造 marker；合法 event 只能由 frozen catalog 加 exact latest X2 snapshot 的真实 crossing 导出。hook 执行和输入 capture 可有界地 fail closed；diagnostic truncation 也不能抹去已经观察到的 ERROR。
- 平台接线仍需单独实现；此 ADR 不创建 reload/network/renderer 入口，也不修改 v1/X2 contract。

## 非决定

本 ADR 不规定 X4 host、X6 provider/material、Minecraft renderer、游戏规则、协议版本或 stable API。`r9-13-pre-doc-build-release` 的自动化结果是历史文档冻结前 baseline，不是 final docs-bound PASS；r9 `#10b` 的 `39/276` 和 r10 replay 的 `39/280` 也不是正确 task-bound `8/51`。r13 docs-bound release 尚未运行；最终 binding 只能由这轮九份文档冻结 hash 后唯一一次 `buildRelease --rerun-tasks` 与独立 SHA 验证决定，artifact 缺失或不匹配即 binding 未证明。formal r9 为 **FAIL（0 Critical / 0 High / 1 Medium / 2 Low）**，fresh independent r10 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，formal r11 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，formal r12 review 为 **FAIL（0 Critical / 0 High / 1 Medium / 1 Low）**，fresh independent r13 review 为 **PENDING**；真实集成仍为 WAITING。
