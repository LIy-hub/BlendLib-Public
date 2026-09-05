# X0 扩展架构与分层

状态：分层治理决定为 Accepted by current GOAL authority；任何新的受控 SPI 或 capability runtime 合同仍为 Proposed/Experimental。

## 三层消费者边界

| 层 | 目标包/命名空间 | 可依赖方向 | 兼容承诺 | 禁止泄漏 |
|---|---|---|---|---|
| 稳定语义 API | com.liy.blendlib.api，及同一 MC target 的公开 adapter facade | consumer -> API；adapter -> API/core | 纯语义 API 在同一 BlendLib major 内兼容；adapter facade 只在同一 Minecraft target 内兼容 | mutable arrays、GLB JSON、Minecraft internal GL、backend/registry handle、world/entity 状态 |
| 受控 SPI | 建议 com.liy.blendlib.spi.experimental 与 adapter-private provider bridge | adapter integration -> immutable SPI proposal；不得反向让 core 依赖 MC | 未接受前无消费者 ABI 承诺；接受后才按独立 SPI protocol 轴定义 | raw GL、private/reflection、未冻结 provider 实例、可变 JSON/payload、热路径 I/O |
| internal implementation | core loader/model/runtime、fabric client reload/render/sync、backend/cache 等实现包 | 只能由拥有者或专门 integration 任务修改 | 当前无消费者兼容承诺，即使源码技术上为 public | 不能被示例、fixture 或外部 mod 当作稳定入口 |

当前 dirty WIP 中有技术可见的 core/client 类型，不因此获得稳定或 SPI 身份。X1 的 API/SPI 任务必须先建立 API-surface scan、显式 internal 标注或包迁移策略；不能仅靠 Javadoc 把已经可编译的实现暴露当成受控 ABI。

## 固定运行数据流

~~~text
authoring Blender
        -> strict GLB/Profile + descriptor
        -> reload prepare (I/O, parse, CPU validation)
        -> immutable ModelAsset/resource generation
        -> per-instance animation state
        -> immutable RenderSnapshot
        -> adapter submit (consume only)

server semantic animation state
        -> versioned semantic payload
        -> client session/world-scoped state
        -> extraction and snapshot
~~~

X0 不改变此流。尤其：

- Blender、FBX、OBJ、外部 glTF+bin 均不是 runtime 输入。
- Resource generation、instance state 和 snapshot 不能互相承载对方的可变职责。
- submit、advance、socket query 不得触发 loader、JSON、GLB、provider discovery 或 registry lookup。
- client presentation 不得决定服务端伤害、碰撞、命中、掉落或其他权威玩法。
- provider/backend 若未来存在，也必须在 reload lifecycle 内选定并把不可变计划钉在 generation/snapshot 上。

## 受控能力的放置

Capability proposal 只允许作为 adapter-controlled 的扩展点；它不是 core 解析任意 payload 的许可：

1. 纯 API/core 不引用 Minecraft/Fabric types，也不由 provider 把 Identifier 或 RenderType 传回其中。
2. Provider 的 metadata 在 discovery/freeze 阶段处理；资源和后端工作分别在 prepare/apply 完成。
3. 每个 snapshot 必须同时钉住 model key、generation 和 immutable selected-capability plan。
4. 失败隔离以 provider/model/generation 为界，不得污染其他 world、session 或当前已发布 generation。
5. CPU/标准 Minecraft path 是基础语义；高级 GPU/provider path 只能在 publish 前证明等价，否则 fail closed。

## 当前 v1 与未来扩展的防火墙

ADR-016 的现行 RC 边界不允许保留或暴露 descriptor extensions payload，且 nonempty required extension 继续拒绝为 EXT-001。故 X0 不能把已有 generic extensions object 或内部 backend seam 描述成一个已存在的 v1 capability API。

未来高级 Profile、材质、pipeline 或 wire 变化必须使用新 profile/schema/API/protocol 版本，并通过独立 ADR。它们不得覆盖 rigid_v1/skinned_v1，也不得静默改变现有 material rejection、required-extension rejection 或 semantic network boundary。
