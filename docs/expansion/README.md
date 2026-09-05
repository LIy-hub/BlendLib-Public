# BlendLib 扩展轨道 X0

状态：X0 已由 /root/x0_independent_review_r4（gpt-5.6-sol/max）独立审查，Verdict: PASS、无 findings；r1--r3 FAIL 历史仍保留。X1、X5、X9 各自也已获其指定 fresh gpt-5.6-sol/max 单轨 PASS；其既有 formal r2 PASS 的范围和历史仍见原 integration 记录。X2 isolated internal runtime candidate `9035b25649f8ef91b95afb354b76be877a9d7ce0` 已获 `/root/review_x2_animation_v2_r5`（gpt-5.6-sol/max）fresh Verdict: PASS、无 findings，并已由专门 r1 integration 任务按五个原始单亲提交线性合入 `agentloop/blendlib-expansion`。这只给出 **X2 isolated internal runtime reviewed PASS / integration REVIEW**；必须等待新的 gpt-5.6-sol/max integration review，绝不称为 integration PASS。X4 历史 r5 输入 `751170ce6eed775149f42087f1d2371ef62499d9` 曾为 FAIL（2 High）；修复后的候选 `01ea2a5141d2748c5ac2eca4b7b168e4297e5baf` 已获 `/root/review_x4_host_adapters_r9`（gpt-5.6-sol/max）独立 Verdict: PASS、无 findings，并已按 11 个原始单亲提交线性进入正式树。它只给出 **X4 isolated candidate reviewed PASS / formal integration REVIEW**；仍必须等待新的 gpt-5.6-sol/max 正式集成复审。X3 候选 `af198bc1f094be8c79fba13cd2da958cf9695087` 已获 `/root/review_x3_procedural_events_r4`（gpt-5.6-sol/max）fresh Verdict: PASS、无 findings，并按六个原始单亲提交正式集成；它仅为 **正式集成候选，等待 fresh formal review**，不解锁下游。X6 的历史 `e66e57a7b2cedeb0ac36cc988bf2b29aaf2e82b7` FAIL / 禁止集成记录保留；R15 candidate `2d8a34e1bee13f7e010d5ac798858e27d13b4e1f` 已唯一线性进入正式 `eb6be3e6e834fef5fc44eb5e8a0e3ee11c225746`（tree/patch-id exact），且 fresh gpt-5.6-sol/max final formal integration review 已给出 **PASS（0C/0H/0M/0L）**。审查报告为 `D:\BlendLib-review-artifacts\x6-r15-formal-integration-final-review-20260829-204846364\FINAL_REVIEW.md`（SHA-256 `77A5FB8283D2E4EF3BB21EB5056EE2E764F8CE1BD7719735E5EF56E26FA8A2C6`），其 self-excluded manifest 为同目录 `VALIDATED_MANIFEST.md`（SHA-256 `73724F1DC98E7912EC10E231D54435AFBFC8FDA1C2C4DCE3C3AA486BD745D6CD`）。该审查未运行 Gradle，而是独立复核两套 manifests、XML 与 release SHA17；两个未保留 numeric OS-exit 的限制均被判为 nonblocking，且绝不把任一 wrapper OS exit 写成 0。X6 当前为 **fresh formal integration PASS**。X7 的 policy、GPU B1、D1、D2a、D2b 与 benchmark 六个独立候选均获 bounded independent PASS，并已按 14 个原 shared 提交加 4 个 patch-equivalent benchmark cherry-pick 进入正式树；当前只形成 **formal integration candidate awaiting independent review**，不称 X7 PASS、complete 或性能 PASS。X8 仍为 **LOCKED / PENDING**，直到 X1--X7 全部完成。上述状态不构成 X0--X9 总验收、P3--P8 Gate PASS、发布稳定性、visual、sync、reload、Iris/Sodium、hardware、performance 或许可证结论。

当前用户授权只允许一个隔离的 X8 实现/共享接线候选在不改变上述依赖事实的前提下落盘；它不是
X8 Gate 的解锁，不提升 X1--X7、P0--P8、release、许可证或任何动态验收。X8 仍为
**LOCKED / PENDING**，直到 X1--X7 全部完成。

## 目的与权限

本目录为后续 X0--X9 扩展建立隔离的架构、兼容性和协作契约。它有三项明确边界：

1. 它不改写既有 v1 设计、既有 ADR、现有实现进度账本或任何生产/测试/资源文件。
2. 它保留当前 GOAL 的硬架构护栏：运行时仅处理严格版本化 GLB/Profile；api/core 保持纯 Java；Identifier 只在 adapter；资源、实例、不可变 RenderSnapshot 分离；submit 不做 I/O/JSON/GLB；网络只同步语义状态；视觉事件只驱动表现；common/server 不加载 client；不使用 raw GL、私有反射或不稳定 API；每个 Minecraft 目标有独立 JAR；未知 required 输入拒绝；CPU 路径是可靠回退。
3. 它不把新能力偷渡进 v1。新的 SPI ABI、capability negotiation、payload、Profile 或 wire 语义均是 Proposed 或 Experimental，直到独立 ADR、实现、测试和新审查完成。

“Accepted by current GOAL authority”只用于本目录中不改变上述既有护栏的规划/治理决定；它不等同于本地项目 owner 对新 runtime API、schema、payload 或 provider 合同的接受。

## 当前继承事实

捕获时的精确文件清单、逐文件 SHA-256、排除清单和 source before/after 证明位于：

- [X0 基线库存](x0-baseline-inventory.md)
- [机器可读 included 清单](manifest/eligible-files.tsv)
- [included 清单的双解析验证](manifest/eligible-files-validation.json)
- [机器可读 excluded 清单](manifest/excluded-files.tsv)
- [checkpoint 字节保留修复与逐路径差异](manifest/checkpoint-byte-preservation-correction.json)
- [修复后 577 条路径的 raw tree 清单](manifest/corrected-checkpoint-tree-manifest.tsv)
- [全新隔离 checkout 的字节与 Java 25 构建验证](manifest/fresh-checkout-validation.json)
- [本地 Git 元数据修复前后记录](manifest/repository-metadata-repair.json)

捕获继承的是一个本地、未发布、非稳定的工作树，而不是一个新的 v1 正式版本。既有 P0--P8 的账本仍是其 Gate 的唯一来源；X0 不提升其中任何状态。

X0 capture-time/history 中，独立审计当时为整体 FAIL，且有六项 repair：速度/visual-event budget、同步 timeline speed 公式、deferred callback level identity、strict GLB accessor 语义、skin hierarchy/influence/inverse-bind，以及动画 culling bounds；完整历史清单见 [扩展进度台账](../expansion-progress.md)。这不是“当前 source”状态。实时 `D:\BlendLib\docs\implementation-progress.md:71-124` 记录六项均已有独立 reviewer PASS，独立 audit rereview 也返回 overall PASS；当前扩展基线包含这些 repair。该 PASS 只关闭六项 audit findings，不提升 P3 REVIEW、P4 WAITING、P5 IN_PROGRESS 或 P6/P7/P8 WAITING，也不代表 v1、整体项目或本次 X1/X5/X9 integration PASS。X 轨道不能用来掩盖、降级或替代剩余 visual、双客户端、Iris/Sodium、20-reload、性能 Gate。

当前实时 ADR-021 在捕获时为 Accepted：它只授权在接收时捕获 ClientLevel 对象，并在 deferred callback 执行时作 exact-object level guard 的 client-private seam；它不改变 wire/API/version，也不使 P6 通过。

## 文档地图

- [扩展架构与分层](architecture.md)
- [四轴版本与 capability proposal](versioning-capabilities.md)
- [线程、generation 与生命周期 proposal](threading-lifecycle.md)
- [X0--X9 所有权、DAG 与 worktree 规则](module-ownership.md)
- [扩展 ADR 索引](adr/README.md)
- [扩展进度台账](../expansion-progress.md)
- [X2 internal animation-v2 runtime](x2/README.md)
- [X2 r1 integration evidence (review pending)](integration/x2-r1.md)
- [X3 procedural candidate](x3/README.md)
- [X3 r1 formal integration record (fresh formal review pending)](integration/x3-r1.md)
- [X4 r1 formal integration evidence (review pending)](integration/x4-r1.md)
- [X6 r15 formal integration PASS record](integration/x6-r1.md)
- [X7 r1 formal integration candidate (fresh review pending)](integration/x7-r1.md)
- [X1/X5/X9 formal remediation integration](integration/x1-x5-x9-r1.md)
- [X1/X5/X9 formal remediation record](integration/x1-x5-x9-r1-remediation.md)
- [X8 platform/ecosystem local integration candidate](x8/README.md)

## 非目标

本 X0 包不包含 X1+ 生产代码、测试代码、资源、build 配置、entrypoint、metadata、既有 ADR 或既有 progress ledger 编辑。它也不选择许可证、不 push/tag/publish/deploy，并不声称任何 visual、sync 或 performance 证据。
