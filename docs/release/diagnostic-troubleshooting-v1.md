# 诊断与排错索引 v1

完整错误代码、字段形状和安全上限见
[错误码与安全上限](../error-codes-v1.md)。本页提供面向使用者的排错顺序。

## 先收集什么

1. 保留当前资源重载摘要和 `/blendlib diagnostics [model-id]` 输出。
2. 记录 model key、descriptor resource ID、JSON pointer 或 glTF index，以及
   `cause_summary`；不要只截取最后一行异常。
3. 保留原始 descriptor、GLB、外置 PNG 和 exporter report 的 SHA-256。
4. 在隔离开发目录复现，不要直接修改正式服务器、世界或其他模组资产。

诊断字段包括 `severity`、`code`、`model_key`、`resource_id`、
`json_pointer_or_gltf_index`、`message` 和 `cause_summary`。正常重载日志按 generation
汇总，并对同一资源的重复主错误去重。

## 代码与首要动作

| 代码 | 首要检查 | 建议修复 |
|---|---|---|
| `BLENDLIB-DESC-001` | `format_version` 是否为 `1` | 使用 v1 schema 重写 descriptor，不擅自升级 schema 号 |
| `BLENDLIB-DESC-002` | 顶层对象、资源 ID、外置 PNG 路径和 JSON pointer | 修正 descriptor；拒绝绝对路径、网络 URI 和 traversal |
| `BLENDLIB-GLB-001` / `BLENDLIB-GLB-002` | GLB header、declared length、chunk/JSON 布局 | 从源文件重新严格导出，不在运行时使用 `.gltf` + `.bin` |
| `BLENDLIB-GLB-014` / `BLENDLIB-GLB-015` | accessor、index、non-finite 顶点数据 | 修复或重新导出 GLB |
| `BLENDLIB-SCENE-004` / `BLENDLIB-SCENE-005` | node cycle、scene reference、TRS/matrix 是否有限 | 修复 hierarchy/transform，不依赖 renderer 静默修正 |
| `BLENDLIB-SCENE-006` | 是否误导出 camera/light | 从运行资产移除；该项为警告 |
| `BLENDLIB-SKIN-001` | joint、inverse bind、权重和 profile | 以 `skinned_v1` 重导出；每顶点最多 4 个权重 |
| `BLENDLIB-ANIM-006` / `BLENDLIB-ANIM-007` | 单调 time、LINEAR/STEP、clip 与事件范围 | 烘焙或重采样，不使用 CUBICSPLINE |
| `BLENDLIB-MAT-003` | 每个实际 GLB material slot 是否有 descriptor mapping | 补齐命名一致的 material entry |
| `BLENDLIB-MAT-004` | 当前 26.1.2 public material path 是否能精确表达材质意图 | 修正为受支持材质；不要静默改变 cull、blend 或 threshold 语义 |
| `BLENDLIB-LIMIT-001` | file、vertex、index、node、skin、clip 等 hard limit | 简化、拆分或重新导出资产，不放宽安全限制 |
| `BLENDLIB-PERF-001` | 顶点或 skin-joint 规模 | 优化资产；警告不会放宽 hard limit |
| `BLENDLIB-EXT-001` | `extensions_required` 是否包含不支持的扩展 | 移除或替换扩展 |

## 材质与 missing model

当 descriptor 的材质意图无法在 Minecraft 26.1.2 的 public render path 上精确表达时，
BlendLib 返回 missing-model handle 并提供字段级诊断。不要用 outline 参数伪装 culling，
也不要把 additive 或不等价的 cutout threshold 静默重映射。raw OpenGL、反射和
Minecraft/Fabric 私有 API 不是受支持的规避方式。

missing model 是小型、有界的洋红/黑 fallback，不会吞掉 schema 或安全诊断。若错误只在
某一资源包出现，请按资源包优先级确认 descriptor、GLB 和 PNG 来自预期的最终组合，然后
重新加载资源并查看 generation 摘要与 diagnostics 输出。

## Alpha 提示

这是早期测试版本，API 与行为可能变化，请勿用于关键生产环境。仅支持声明的 Minecraft
26.1.2、Fabric Loader 0.19.3、Fabric API `0.154.2+26.1.2` 与 Java 25 环境。
