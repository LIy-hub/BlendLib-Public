# 诊断与排错索引 v1

完整稳定代码表、字段形状和安全上限以
[错误码与安全上限](../error-codes-v1.md) 为准。本页给出不改变资源/运行时边界的
排错顺序。

## 先收集什么

1. 保留当前 resource reload 的摘要和 `/blendlib diagnostics [model-id]` 输出。
2. 记录 model key、descriptor resource ID、JSON pointer 或 glTF index，以及
   `cause_summary`；不要只截取最后一行异常。
3. 保留原始 descriptor、GLB、外置 PNG 和 exporter report 的 SHA-256。
4. 在隔离开发 run 目录复现；不要修改正式服务器、正式世界或其他业务模组的资产。

诊断稳定字段是：`severity`、`code`、`model_key`、`resource_id`、
`json_pointer_or_gltf_index`、`message`、`cause_summary`。正常 production reload
日志应是 generation 级摘要；主错误按 generation/asset 去重。

## 代码 → 首要动作

| 代码 | 首要检查 | 允许的修复 |
|---|---|---|
| `BLENDLIB-DESC-001` | `format_version` 是否为 `1` | 用冻结 schema 重写 descriptor，不升级 schema 号 |
| `BLENDLIB-DESC-002` | 顶层对象、资源 ID、外置 PNG 路径和 JSON pointer | 修正 descriptor；拒绝绝对路径、网络 URI 和 traversal |
| `BLENDLIB-GLB-001` / `BLENDLIB-GLB-002` | GLB header、declared length、chunk/JSON 布局 | 从源 `.blend` 重新严格导出；不要在运行时接收 `.gltf` + `.bin` |
| `BLENDLIB-GLB-014` / `BLENDLIB-GLB-015` | accessor、index、non-finite 顶点数据 | 修复/重导出 GLB，保留失败样本用于回归测试 |
| `BLENDLIB-SCENE-004` / `BLENDLIB-SCENE-005` | node cycle、scene reference、TRS/matrix 是否有限 | 修复 hierarchy/transform；不要在 renderer 静默修正 |
| `BLENDLIB-SCENE-006` | 是否误导出 camera/light | 从运行资产移除；该项只会警告 |
| `BLENDLIB-SKIN-001` | joint、inverse bind、权重和 profile | 以 `skinned_v1` 重导出；每顶点最多 4 权重 |
| `BLENDLIB-ANIM-006` / `BLENDLIB-ANIM-007` | 单调 time、LINEAR/STEP、clip 与事件范围 | 烘焙/重采样；不要使用 CUBICSPLINE |
| `BLENDLIB-MAT-003` | 每个实际 GLB material slot 是否有 descriptor mapping | 补齐命名一致的 material entry |
| `BLENDLIB-MAT-004` | 26.1.2 exact public material path 是否存在 | 保持 missing model，按 ADR-014/ADR-016 处理；不得静默换 cull/blend/threshold 语义 |
| `BLENDLIB-LIMIT-001` | file/vertex/index/node/skin/clip 等 hard limit | 简化、拆分或重新导出资产；不能放宽 limit |
| `BLENDLIB-PERF-001` | 高顶点/skin-joint 性能警告 | 优化资产；警告不放宽 hard limit 或 P7 benchmark |
| `BLENDLIB-EXT-001` | `extensions_required` 是否包含当前不支持扩展 | 移除/替换扩展，或先取得 ADR 批准的扩展实现 |

## `BLENDLIB-MAT-004` 的当前含义

该代码已经由 P4 reload 路径实际声明和发出，不是“保留待实现”代码。当 descriptor
material intent 在 26.1.2 的验证过的 public render path 上无法精确表达时，模型
变为 missing-model handle，并带字段级诊断。已知 P4 支持子集以
[ADR-014](../adr/ADR-014-p4-material-culling-and-emissive-26-1-2.md) 为准：不要用
boolean outline 参数伪装 culling，不能把 additive 或不等价的 cutout threshold
静默重映射。

ADR-016 已被接受，并保留 strict material/pipeline 边界：未获新 ADR 与相应 adapter-only
证据前，不能把 material extension、raw OpenGL、反射或 Minecraft/Fabric 私有 API 当作
诊断规避方法。

## missing model 与重载

missing model 是稳定、小型、有界的洋红/黑 fallback，不会吞掉 schema/安全诊断。
若只有某一资源包发生错误，按资源包优先级检查 descriptor/GLB/PNG 是否来自同一
最终组合，而不是把 lower-priority 文件当作有效覆盖。F3+T 或等价 reload 后需保留
reload summary、generation id 和 diagnostics 输出。

启动 smoke 只能说明入口与 reload 未立即崩溃；不能证明模型视觉、材质、动画、
socket、Iris/Sodium 或双客户端同步通过。需要的人工步骤在
[客户端验收清单](../manual-client-acceptance-v1.md)。

## Alpha 与扩展边界

当前源线是 `1.0.0-beta.1+26.1.2`。X1–X9 的合并候选不会扩大
`rigid_v1`/`skinned_v1` 的严格加载接受集，也不把实验性 Profile、平台候选或历史
自动化证据变成当前运行/视觉/Gate 结论。
