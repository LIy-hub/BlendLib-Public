# BlendLib 设计基线与 Alpha 文档入口

> **来源基线说明。** 本文、`architecture.md`、`design-v1.md` 与
> `implementation-plan.md` 保留为已批准的历史设计基线。下方“尚未开始实现”是
> 该基线形成时的原始状态，**不表示当前 `D:\BlendLib` 工作区尚未实现**。当前
> 阶段/Gate 状态以 [实施进度账本](./implementation-progress.md) 为准；当前
> `1.0.0-alpha.1+26.1.2` 源线的制品、哈希和法律资料以
> [release/](./release/README.md) 与其 evidence 为准。历史 RC 文字只保留为对应
> 历史记录，不能覆盖当前 Alpha 身份。

状态（历史设计基线）：架构基线已确定，尚未开始实现。

工作名称：`BlendLib`

模组 ID：`blendlib`

Java 包根：`com.liy.blendlib`

首个运行目标：Minecraft Fabric 26.1.2、Java 25

计划中的独立实现目录：`D:\BlendLib`

## 产品定位

BlendLib 是一个面向 Fabric 模组开发者的 Blender 模型运行库：

- Blender 是模型、骨架和动画的创作端。
- GLB 2.0 是唯一的主要运行时模型载体。
- BlendLib 负责资源校验、加载、缓存、动画采样、实例状态和渲染提交。
- 使用 BlendLib 的业务模组只负责注册渲染绑定、提供状态并触发动画。
- 服务端不解析模型，也不执行任何渲染代码；服务端只在需要时同步动画状态和事件。

BlendLib 的核心差异不是“再做一个通用动画库”，而是提供一条稳定的 Blender → GLB → Fabric 工作流，并使实体、物品、方块实体和世界特效共享同一模型运行时。

## 文档入口

- [项目 Logo 与品牌资源](./assets/branding/README.md)
- [总体架构](./architecture.md)
- [v1 设计规格](./design-v1.md)
- [实施计划](./implementation-plan.md)
- [Alpha 发布与法律文档](./release/README.md)
- [X1–X9 alpha 合并记录](./expansion/integration/x1-x9-alpha-merge.md)
- [X8 平台、生态与本地候选集成](./expansion/x8/README.md)

## 已固定的架构决策

以下决策属于 v1 基线，实施阶段不得静默更改：

1. 游戏运行时不读取 `.blend`、FBX 或 OBJ。
2. v1 使用严格、显式声明的 GLB 2.0 子集，不宣称支持全部 glTF。
3. `.blend` 源文件保留在美术目录，但不打入运行 JAR。
4. GLB 保存几何、层级、骨架和动画；Minecraft 贴图通过资源 ID 外置。
5. 模型资源是不可变共享对象，动画控制器是每实例独立对象。
6. 渲染阶段只消费不可变快照，不访问实体、世界或服务端状态。
7. 资源解析必须发生在资源重载阶段，`submit` 热路径不得读取文件或解析 JSON/GLB。
8. 公共 API 与 Fabric 版本适配层分离；26.1.2 和 26.2 不共用同一运行 JAR。
9. v1 渲染只使用 Minecraft/Blaze3D 抽象，不直接调用 OpenGL。
10. 视觉模型不得成为服务端碰撞、伤害判定或命中盒的权威来源。

## v1 完成定义

`1.0.0` 只有在以下能力全部完成后才成立：

- Blender 一键导出和命令行资产校验。
- 静态/刚体 GLB 渲染。
- 骨骼蒙皮和关键帧动画。
- 动画循环、一次性播放、状态切换和混合。
- 实体、物品、方块实体适配。
- 服务端到客户端的实体/方块实体动画同步。
- 资源包覆盖和热重载。
- 独立服务器无客户端类加载错误。
- 有界缓存、重载资源释放和性能基准。
- Showcase 模组、开发文档、Javadoc 和可消费制品。

形态键、Draco、任意 Blender 节点材质、材质动画、布料/刚体物理和通用 PBR 光影桥接不属于 v1。GPU 蒙皮不作为公共能力承诺，但如果 CPU 蒙皮无法通过性能 gate，可以作为内部后端实现。
