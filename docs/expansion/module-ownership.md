# X0--X9 所有权、DAG 与 Worktree 规则

状态：任务编号和依赖逐字遵循 GOAL；本页记录所有权边界，不自行实现或提升 X1+ Gate。X7 r1 状态为 **formal integration candidate awaiting independent review**。

## 不可重映射的 DAG

~~~text
X0 基线与扩展契约
 ├─ X1 公共 API/SPI
 ├─ X5 Blender 工具链
 └─ X9 高级资产 Profile

X1
 ├─ X2 动画系统 v2
 ├─ X4 新宿主适配器
 └─ X6 Variant、RenderLayer、材质扩展

X2 -> X3 程序化骨骼与视觉事件
X6 -> X7 性能与高级渲染后端
X1--X7 -> X8 平台、生态、示例和最终集成
~~~

| 轨道 | 功能 | 未来主要所有者范围 | 共享文件限制 |
|---|---|---|---|
| X0 | checkpoint、契约、ADR、台账 | docs/expansion/**，docs/expansion-progress.md | 不改既有 docs/ADR/progress 或生产文件 |
| X1 | Facade、Builder、稳定 API 与受控 SPI | blendlib-api；限定 adapter public facade/consumer fixture | 不能独占 settings、versions、entrypoint、fabric.mod.json |
| X2 | animation v2 | core animation/model/test fixture | 不改 X1 API contract 或 shared build |
| X3 | procedural bones、attachments、visual events | client extraction/snapshot/presentation，core pure math | 不把 visual event 送入 common/server authority |
| X4 | host adapters | client entity/block/item/GUI/VFX adapter 与 Showcase consumer | adapter target 专属，禁止修改 core semantics |
| X5 | Blender 工具链 | blender-addon、validator、asset reports/test assets | source art/runtime boundary不变 |
| X6 | Variant、RenderLayer、material extension | adapter material/variant seam、fixtures | ADR-016 RC boundary前不得启用 payload/custom pipeline |
| X7 | performance、LOD、高级 backend | client reload generation/policy owner、adapter-private `render/x7gpu`、Showcase `perf/x7` tooling | CPU fallback、pass owner 与真实硬件 Gate 不变；integration-only 文件仍由正式集成任务拥有 |
| X8 | platform、生态、示例、最终集成 | dedicated adapter/bridge/datagen/docs/examples | 依赖 Gate 仍 LOCKED/PENDING；当前仅有隔离 implementation/integration candidate，专门 integration owner 处理共享文件且不提升上游状态 |
| X9 | advanced asset Profile | schema/Profile proposal、validator/fixture | 不污染 rigid_v1/skinned_v1 |

## 当前 X7 所有权状态

X7 r1 的正式树状态记录在 [X7 r1 formal integration candidate](integration/x7-r1.md)。
`client.reload` 中的 generation owner 是发布、retirement、shared lease 与冻结 CPU policy
projection 的单一内部 owner；X4/X6 只保留其准备阶段已验证的 opaque/primitive 结果。
`render/x7gpu` 仍是 adapter-private 的资源与批处理结构候选，Showcase `perf/x7` 仍是只能产生
structural WAITING 的离线证据工具。实际 RenderPass/deferred-callback completion owner、GPU draw
owner 与 trusted benchmark capture owner 均未分配且保持 WAITING；后续任务不得通过第二套 owner
或 submit-time redecision 绕过这个缺口。

## 共享文件与 integration-only

以下文件只能由专门 integration 任务修改：根 settings.gradle.kts、根 build.gradle.kts、gradle.properties、Fabric entrypoint、fabric.mod.json、公共版本、既有 docs/implementation-progress.md、既有 ADR/contract/evidence。任何轨道需要这些改动时只提交建议；integration owner 用显式路径合入。

## Worktree/branch 约定

每个写入任务使用一个干净 worktree 和一个唯一 branch，例如：

~~~text
D:\BlendLib-worktrees\x2-animation
agentloop/x2-animation
~~~

X0 的隔离继承 worktree 是 D:\BlendLib-agentloop、branch 为 agentloop/blendlib-expansion。它只承载 checkpoint 与 X0 文档。不得自动删除任何 worktree，不得让两个 writer 同时拥有同一文件。

实现任务固定使用新的 gpt-5.6-terra/ultra；每个审查使用新的 gpt-5.6-sol/max，且 reviewer 不写实现。实现者永不审查自己的工作。

## 既有审计/Phase Gate 分离

X0 capture-time/history 的独立审计结论为 FAIL，并列出六项 repair（速度/visual-event budget、同步 timeline speed、deferred level identity、strict accessor、skin hierarchy/influence/inverse-bind、animated culling bounds；见 [expansion-progress.md](../expansion-progress.md) 的 capture 清单）。这六项不是 X0--X9 编号，也不能被重新标记为“普通风险”。随后实时 `D:\BlendLib\docs\implementation-progress.md:71-124` 记录每项独立 reviewer PASS，且独立 audit rereview overall PASS；当前扩展基线包含这些 repair。该结果仅关闭六项 audit findings：P3 REVIEW、P4 WAITING、P5 IN_PROGRESS、P6/P7/P8 WAITING 以及 visual/sync/Iris/20-reload/performance Gate 均不提升，更不构成 v1、整体项目或本次 integration PASS。
