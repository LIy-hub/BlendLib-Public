# BlendLib

<img src="./docs/assets/blendlib-logo.png" alt="BlendLib" width="640">

[项目 Logo 与品牌资源](./docs/assets/branding/README.md)

BlendLib 是面向 Minecraft Fabric 模组的严格 GLB 2.0 模型与动画运行库。当前公开 Beta
版本为 `1.0.0-beta.1+26.1.2`，只支持 Minecraft 26.1.2、Fabric Loader 0.19.3、
Fabric API `0.154.2+26.1.2` 与 Java 25。

## 当前状态

这是早期测试版本，不是稳定版。API 与运行行为可能变化，请勿用于关键生产环境；
仅支持本页声明的 Minecraft、Fabric 与 Java 环境。

已知限制：

- 运行时只接受严格 GLB 2.0，不读取 `.blend`、FBX、OBJ 或外部 `.gltf` + `.bin`。
- 本 JAR 只适配 Minecraft 26.1.2；26.2 必须使用独立 adapter JAR。
- 视觉模型和动画事件不参与服务端权威碰撞、伤害、命中、掉落或其他玩法判定。
- 当前 Beta 不提供稳定 API/ABI 承诺；不兼容变更会记录在 Changelog 与发布说明中。

Beta.1 纳入 X1–X9 扩展的合并代码，包含动画、程序化姿态、宿主适配、Blender 工具链和
实验性渲染能力。普通 Showcase 实体视觉已有用户确认；完整材质矩阵、物品与方块实体视觉、
双客户端同步、20 次热重载泄漏、Iris/Sodium 兼容性及真实硬件性能仍未全部验收。
这不是稳定 `1.0.0` 或 X1–X9 全轨道验收通过。具体能力与限制见
[Beta.1 发布说明](./docs/release/beta-release-notes.md) 和
[X1–X9 alpha 合并记录](./docs/expansion/integration/x1-x9-alpha-merge.md)。

## 导航

- [当前阶段与证据账本](./docs/implementation-progress.md)
- [历史批准设计基线](./docs/README.md)（架构、设计和实施计划的来源基线，不是当前实现状态）
- [Beta 发布与法律文档](./docs/release/README.md)
- [X1–X9 alpha 合并记录](./docs/expansion/integration/x1-x9-alpha-merge.md)
- [X8 平台与生态候选](./docs/expansion/x8/README.md)（独立候选；不改变任何 Gate）

## 获取、源码与许可证

- 源码与问题跟踪：[GitHub](https://github.com/LIy-hub/BlendLib-Public)
- Minecraft 发布页：[CurseForge](https://www.curseforge.com/minecraft/mc-mods/blendlib)
- 作者：LIy-hub（`2734855720@qq.com`）

非 Add-on 源码使用根目录 [Apache License 2.0](./LICENSE)，并以 [NOTICE](./NOTICE)
记录项目 notice；`blender-addon/` 保持独立的 GPL-3.0-or-later 授权，详见
[Add-on LICENSE](./blender-addon/LICENSE)。这保留既有 alpha 许可证决定，不把任何
X8 历史 `LICENSE-PENDING`/LicenseRef 记录恢复为当前法律状态。

## 本地构建与制品参考

`buildRelease` 和以下命令是本地入口，不是本次合并执行记录，也不能单独证明当前制品、
客户端视觉或任何 Gate：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat clean check
.\gradlew.bat :blendlib-core:test
.\gradlew.bat :blendlib-showcase:runServer
.\gradlew.bat :blendlib-showcase:runClient
.\gradlew.bat buildRelease
git diff --check
```

`buildRelease` 还需要 `blender_executable` 指向可用的 Blender 5.1+，并只生成本地
`build/release/` 与 `build/local-maven/` 输出。每次重建的身份应由当次
`build/release/SHA256SUMS` 与相应 evidence 记录确认；aggregate Javadoc 也是构建产物，
不是 checked-in 文档。

X8 另有显式的 `x8AssembleLocalCandidate` 本地候选入口。它可通过
`publishPublicAlpha` 准备本地消费者坐标，并保持 Fabric 26.2、NeoForge WAITING bridge、
datagen、examples/providers、converter/template ZIP、inventory 与 SHA-256 同 26.1.2 runtime
分离；它不把 26.2 或 NeoForge 内容嵌入 26.1.2 runtime JAR。详见
[X8 integration handoff](./docs/expansion/x8/integration-handoff.md)。
