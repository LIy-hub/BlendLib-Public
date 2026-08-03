# Schema 与公共 API Alpha 冻结记录

状态：`1.0.0-alpha.1+26.1.2` 公开 Alpha 的兼容性说明；不是稳定 API 承诺。

## 冻结范围

| 项目 | Alpha 契约 |
|---|---|
| 资源 schema | `schemas/blendlib-model-v1.schema.json`，Draft 2020-12，`format_version: 1` |
| descriptor profiles | `blendlib:rigid_v1`、`blendlib:skinned_v1` |
| 运行时载体 | 严格 GLB 2.0；拒绝 `.blend`、FBX、OBJ 与外部 `.gltf` + `.bin` |
| adapter | Minecraft 26.1.2 / Loader 0.19.3 / Fabric API `0.154.2+26.1.2` / Java 25 |
| 版本 | `1.0.0-alpha.1+26.1.2`；26.2 只能由独立 adapter JAR 支持 |

Schema 禁止未知顶层字段，并维持既有 material 映射、URI/path、安全上限和稳定诊断规则。
视觉模型与动画事件不得决定服务端权威碰撞、伤害、命中或掉落。

面向消费者的高层入口包括纯语义 key、`BlendAnimations` 服务端 facade、实体/方块实体/item
的 26.1.2 client adapter，以及 `BlendLibClientServices` 只读服务。`blendlib-core` parser、
reload/render/network 实现包不属于消费者兼容承诺。

Alpha 期间，schema、profile、API 与运行行为可能变化。请勿用于关键生产环境，并仅在本文
声明的 Minecraft 26.1.2、Fabric 与 Java 环境中使用。
