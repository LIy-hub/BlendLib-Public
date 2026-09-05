# Schema 与公共 API Alpha 冻结记录

状态：`1.0.0-beta.1+26.1.2` 的兼容性与边界记录；它不是稳定 API/ABI、P0–P8 或
release Gate PASS，也不替代用户审核。

## 冻结范围

| 项目 | Alpha 契约 |
|---|---|
| 资源 schema | `schemas/blendlib-model-v1.schema.json`，Draft 2020-12，`format_version: 1` |
| descriptor profiles | 仅 `blendlib:rigid_v1` 与 `blendlib:skinned_v1` |
| 运行时载体 | 严格 GLB 2.0；不读取 `.blend`、FBX、OBJ 或外部 `.gltf` + `.bin` |
| 资源位置 | `assets/<namespace>/blend_models/<path>.json`、`models3d/<path>.glb` 和外置 `textures/.../*.png` |
| 坐标 | 右手系、`+Y` 向上、`+Z` 向前、`+X` 向右；Blender 转换只在导出/导入边界发生一次 |
| adapter | Minecraft 26.1.2 / Fabric Loader 0.19.3 / Fabric API `0.154.2+26.1.2` / Java 25；26.2 必须为独立 adapter JAR |
| 版本 | `1.0.0-beta.1+26.1.2`；当前 source line 不以历史 RC 字符串替代 |

Schema 禁止未知顶层字段。`materials` 中每个实际 GLB material slot 必须具有映射；
`base_color` 必须是具体的外置 `textures/.../*.png` 资源 ID。`cutout_threshold`
仅能随 `cutout` 使用。详见 [设计规格第 4–6 节](../design-v1.md) 与
[GLB profile](../glb-profile-v1.md)。

## 严格 GLB 与安全边界

v1 只接受 TRIANGLES、`POSITION`、`NORMAL`、`TEXCOORD_0`，以及 skinned
profile 所需的 `JOINTS_0` / `WEIGHTS_0` 与 inverse bind matrices。支持
LINEAR、STEP 和 quaternion slerp；拒绝 CUBICSPLINE、morph targets、sparse
accessor、Draco、Meshopt、内嵌贴图、vertex color 和多 UV。

加载器必须维持既有 hard limits、URI/path 拒绝规则和稳定诊断。数值及完整
限制见 [错误码与安全上限](../error-codes-v1.md)；任何 schema/profile/limit 的
不兼容变更都需要先有 Accepted ADR 和来源真相更新。

## 面向消费者的公共 API

以下是 Alpha 面向消费者的高层入口。它们由 `blendlib-api`、Fabric common facade
或 26.1.2 client adapter 提供；示例见 [开发者教程](./developer-tutorial-26.1.2.md)。

| 层 | 允许的入口 | 用途 |
|---|---|---|
| 纯语义 API | `BlendResourceId`、`BlendModelKey`、`BlendAnimationKey`、`BlendInstanceKey` | 无 I/O 的命名与实例身份 |
| 服务端语义 | `BlendAnimations` | 对 `Entity` / `BlockEntity` 触发或设置 persistent 动画语义 |
| 实体 client adapter | `BlendEntityRenderer`、`BlendEntityRendererBuilder`、`BlendEntityRenderers`、`SkinnedAnimationStateSelector`、`SyncedSkinnedAnimationStateSelector`、`SkinnedAnimationVisualEventHandler` | 客户端提取快照与 renderer 注册 |
| 方块实体 client adapter | `BlendBlockEntityRenderer`、`BlendBlockEntityRendererBuilder`、`BlendBlockEntityRenderers` | 客户端提取快照与 renderer 注册 |
| 物品 client adapter | `BlendLibItemBinding`、`BlendLibItemModelBindings` | 显式注册普通 vanilla marker item 到模型 key 的绑定 |
| 只读客户端服务 | `BlendLibClientServices`、其 diagnostics/commands/model lookup 外观 | 诊断、只读查询与已准备快照提交的适配器入口 |

### 禁止把实现细节冻结给消费者

业务模组不得依赖 `blendlib-core` 的 parser/descriptor/model 类型，也不得以
`fabric.client.animation`、`fabric.client.reload`、`fabric.client.render`、
`fabric.client.network` 或 `fabric.common.network` 作为消费 API。它们是 adapter
集成实现细节，即使某些 Java 类型暂时是 `public`，也不构成 Alpha 兼容性承诺。

common/server 源代码不得导入任何 client 类。服务端 API 只接受动画 key、speed
和 seed；模型、GLB、矩阵、材质、hitbox、伤害或碰撞决策不能通过这条边界。

## X1–X9 扩展与版本规则

X1–X9 的 facade/SPI、动画、程序化表现、host、variant/material、X7 后端、X8 平台生态和
X9 profile 候选均有独立的 [扩展文档](../expansion/README.md)。它们不能静默改变本页
`rigid_v1`/`skinned_v1` 的接受集或把 experimental surface 伪装为 Alpha 稳定 ABI。

- Alpha 期间，schema、profile、API 与运行行为仍可能变化。
- 26.2 adapter 必须保持独立；26.1.2 JAR 不能作为其兼容性证据。
- 新增不兼容 API、schema、profile、版本或验收标准前，必须先提 ADR 并更新来源真相。
- 任何 Javadoc、sources、SHA-256 和本地 Maven 坐标只能由实际 Alpha 构建输出确认，不能仅引用本文。
