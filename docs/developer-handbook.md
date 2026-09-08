# BlendLib 模组开发者手册

面向使用 BlendLib 制作 Fabric 模组的 Java 开发者与模型作者。

本文按 **2026-09-09、本仓库源码 `f7b1a8d212b7f395b015a1fe8a0b6af383737dfe`、Beta.2** 核对。完整 Java 示例以 **Minecraft 26.1.2 / Java 25** 为编译基线；1.21.x 和 26.2 的差异见版本章节。本文描述已经存在的接口及其限制，不增加 API 兼容性承诺。

BlendLib 负责受支持 GLB 模型的加载、动画与渲染。实体 AI、物品行为、方块逻辑、存档、伤害与碰撞由你的模组负责。先完成一个静态模型，再接动画、同步和程序化姿态，通常最容易定位问题。

## 目录

1. [选择接入方式与能力范围](#scope)
2. [版本、运行环境与依赖](#dependencies)
3. [源码集与生命周期](#lifecycle)
4. [第一个静态模型](#first-model)
5. [资源 ID、文件布局与模型身份](#resources)
6. [Blender 制作与导出](#blender)
7. [完整 descriptor 与字段参考](#descriptor)
8. [材质与贴图](#materials)
9. [实体模型与本地动画](#entities)
10. [服务端动画同步](#synchronization)
11. [方块实体接入](#block-entities)
12. [物品接入](#items)
13. [程序化姿态与整体旋转](#procedural)
14. [动画事件、挂点与附件](#events-sockets)
15. [资源重载、模型查询与诊断](#diagnostics)
16. [错误排查表](#troubleshooting)
17. [性能、线程与资源所有权](#performance)
18. [公开 API 参考表](#api)
19. [通用注册与实验性 SPI](#experimental)
20. [多版本适配与升级](#migration)
21. [接入验证与发布检查](#verification)
22. [常见问题、配套资料与术语](#reference)

<a id="scope"></a>
## 1. 选择接入方式与能力范围

### 1.1 本手册推荐的普通消费路径

| 要做的事情 | 使用的入口 | 典型运行位置 |
| --- | --- | --- |
| 实体静态模型 | `BlendEntityRenderer.builder(...).staticRestPose()` | 客户端初始化注册 |
| 实体本地动画 | `.skinnedAnimation(selector)` | 客户端提取动画快照 |
| 实体服务端同步动画 | 服务端 `BlendAnimations.entity(...)`；客户端 `.synchronizedSkinnedAnimation(fallback)` | 服务端状态变化 + 客户端呈现 |
| 方块实体静态模型 | `BlendBlockEntityRenderer.builder(...).staticRestPose()` | 客户端初始化注册 |
| 方块实体同步骨骼动画 | `.syncedSkinnedAnimation(fallbackKey)` | 客户端；模型必须有 skin |
| 物品静态模型 | `BlendLibItemModelBindings.register(binding)` | 客户端初始化注册 |
| 实体附加旋转 | `.poseModifier(...)`、`.rootRotation(...)` | 已配置动画的实体提取阶段 |
| 资源与错误查询 | `BlendLibClientServices.models()`、`.diagnostics()` | 客户端服务初始化及资源发布后 |

这些注册方法不会自动创建你的 `EntityType`、`BlockEntityType`、物品、方块或玩法。它们接收业务模组已经注册的对象。

### 1.2 当前普通消费路径的能力矩阵

| 能力 | 当前范围 |
| --- | --- |
| 静态/刚体 GLB | 支持严格 `blendlib:rigid_v1` |
| 骨骼蒙皮 GLB | 支持严格 `blendlib:skinned_v1`，CPU 路径 |
| 实体节点动画 | `.skinnedAnimation` 名称保留，但其实现也接受有动画状态的 `rigid_v1` |
| 实体骨骼动画 | 支持本地选择和同步选择 |
| 方块实体同步动画 | 标准工厂要求 skinned 模型，不能照搬实体刚体动画结论 |
| 普通物品 marker 绑定 | 当前是基础姿态快照路径，没有动画采样；不能据通用 `AnimationRequest.loop` 推断它会播放物品动画 |
| 本地动画视觉事件 | 本地推进动画时可使用事件回调 |
| 标准同步动画视觉事件 | 当前绝对时间采样路径不派发事件；不能依赖它播放事件粒子/声音 |
| 节点程序化姿态 | 实体公开回调支持旋转覆盖，不提供节点位移/缩放修改 |
| 挂点 | 可在 descriptor 声明；实体有单挂点可视标记。通用查询到附件绘制尚无完整高层消费流程 |
| 多控制器、分层混合、扩展材质/后端 | 存在内部或实验性实现，不作为普通接入教程的稳定能力 |
| 暂停、恢复、seek、清除持续动画 | 当前普通 facade 没有统一操作接口 |
| `.blend`、FBX、OBJ、外部 `.gltf + .bin` 运行时加载 | 不支持 |
| NeoForge | 不属于当前 Beta.2 公开运行库范围 |

本手册以实际默认 Fabric 入口为主线。`BlendLib.entity(...).register()` 等平台无关注册协议见[第 19 节](#experimental)，不把它作为安装 Beta.2 后默认即可运行的替代路线。

<a id="dependencies"></a>
## 2. 版本、运行环境与依赖

### 2.1 精确版本配对

当前源码记录的 Beta.2 目标如下。Fabric API 列为发布记录中的配对版本，便于复现；升级依赖后需要重新验证。

| Minecraft | Java | Fabric API | 本库源码构建族 |
| --- | --- | --- | --- |
| 1.21.1 | 21 | `0.116.17+1.21.1` | legacy |
| 1.21.2 | 21 | `0.106.1+1.21.2` | legacy |
| 1.21.3 | 21 | `0.114.1+1.21.3` | legacy |
| 1.21.4 | 21 | `0.119.4+1.21.4` | legacy |
| 1.21.5 | 21 | `0.128.2+1.21.5` | legacy |
| 1.21.6 | 21 | `0.128.2+1.21.6` | legacy |
| 1.21.7 | 21 | `0.129.0+1.21.7` | legacy |
| 1.21.8 | 21 | `0.136.1+1.21.8` | legacy |
| 1.21.9 | 21 | `0.134.1+1.21.9` | modern |
| 1.21.10 | 21 | `0.138.4+1.21.10` | modern |
| 1.21.11 | 21 | `0.141.6+1.21.11` | modern |
| 26.1 | 25 | `0.145.1+26.1` | modern |
| 26.1.1 | 25 | `0.145.4+26.1.1` | modern |
| 26.1.2 | 25 | `0.154.2+26.1.2` | modern |
| 26.2 | 25 | `0.153.0+26.2` | modern |

配合 Fabric Loader `0.19.3` 基线或满足所选制品要求的版本使用。核对下载 JAR 内的 `fabric.mod.json`，并使用精确对应游戏版本的 runtime。

例如 26.1.2 的文件是 `blendlib-fabric-1.0.0-beta.2+26.1.2.jar`。`-sources.jar` 是阅读源码用的附件，不是运行库。一个游戏实例只安装一份 BlendLib runtime。

获取入口：[GitHub Beta.2 发布页](https://github.com/LIy-hub/BlendLib-Public/releases/tag/v1.0.0-beta.2)、[CurseForge 文件页](https://www.curseforge.com/minecraft/mc-mods/blendlib/files/all)。当前仓库没有据此确认可用于所有 Beta.2 版本的公共 Maven 仓库，不要凭包名猜 Maven Central 坐标。

### 2.2 在已有 Fabric 工程中引用本地 release JAR

先使用目标版本的正常 Fabric/Loom 工程，把下载的 runtime 放到工程 `libs/`。下面只展示新增的 BlendLib 依赖，不替换你现有的 Minecraft、Loader、Fabric API 或映射配置。

**26.x / 本仓库使用的非混淆 Loom 路线，Kotlin DSL：**

```kotlin
dependencies {
    implementation(files("libs/blendlib-fabric-1.0.0-beta.2+26.1.2.jar"))
}
```

**1.21.x / 需要 remap 的 Loom 路线，Kotlin DSL：**

```kotlin
dependencies {
    modImplementation(files("libs/blendlib-fabric-1.0.0-beta.2+1.21.1.jar"))
}
```

Groovy DSL 对应写法：

```groovy
dependencies {
    // 26.x 工程。
    implementation files('libs/blendlib-fabric-1.0.0-beta.2+26.1.2.jar')
}
```

```groovy
dependencies {
    // 1.21.x remap 工程。
    modImplementation files('libs/blendlib-fabric-1.0.0-beta.2+1.21.1.jar')
}
```

选择与你的目标相符的一段。1.21.x 的运行 JAR 需要 Loom 在开发环境重映射；映射命名不同还会影响示例中的 Minecraft 类名。26.x 的示例不能直接粘到使用另一套配置名的旧 Loom 工程。

编译依赖不会自动为最终玩家安装前置。发布你的模组时声明 BlendLib 为必需依赖，让玩家单独安装匹配的运行库；普通消费场景不使用 `include(...)` 再把整套 BlendLib 嵌入业务 JAR。

### 2.3 声明运行依赖

将下列字段合并进你已有的 `fabric.mod.json`，保留你的 `id`、入口、版本展开与其他依赖。这里是依赖片段，不是完整模组元数据。

```json
{
  "depends": {
    "minecraft": "26.1.2",
    "java": ">=25",
    "fabricloader": ">=0.19.3",
    "fabric-api": "*",
    "blendlib": "1.0.0-beta.2+26.1.2"
  }
}
```

开发构建仍应锁定本节表中的 Fabric API 版本。Beta 期间先锁定验证过的 BlendLib 文件与校验和；不要仅依赖版本号 `+` 后的文本来隔离游戏版本，同时约束 `minecraft` 并选择正确文件。

### 2.4 从本仓库源码构建

以下命令在 BlendLib 仓库根执行，用于获得指定版本运行库：

```powershell
.\gradlew.bat -p versions/legacy "-Pminecraft_version=1.21.1" build
```

```powershell
.\gradlew.bat -p versions/modern "-Pminecraft_version=26.1.2" build
```

输出位置分别为 `versions/legacy/build/<Minecraft>/libs/` 和 `versions/modern/build/<Minecraft>/libs/`。这些任务不会把制品发布到外部平台。

根目录构建仍保留 `1.0.0-beta.1+26.1.2` 基线。`buildRelease` 与 `build/local-maven/` 的现有制品不是自动对应 Beta.2。仓库中的 `examples/independent-consumer` 和 local-Maven fixture 可以研究工程结构，但其 RC/Alpha/Beta.1 属性必须按实际制品更新后再使用。

<a id="lifecycle"></a>
## 3. 源码集与生命周期

推荐在已使用 Loom `splitEnvironmentSourceSets()` 的工程中保持以下分工：

| 位置 | 放什么 | 不放什么 |
| --- | --- | --- |
| `src/main/java` | 实体/物品/方块注册、玩法状态、动画 key、服务端 `BlendAnimations` 调用 | `fabric.client.*`、Minecraft 客户端类 |
| `src/client/java` | renderer 注册、模型 key、pose/event 回调、物品 binding、诊断工具 | 服务端伤害/奖励/存档判定 |
| `src/main/resources` | descriptor、GLB、PNG、正常语言与物品模型资源 | `.blend` 美术工程、构建日志 |
| `art/` 或独立美术目录 | `.blend`、来源与许可、导出报告 | 运行时需要直接读取的模型引用 |

客户端 renderer 与 item binding 从 `ClientModInitializer.onInitializeClient()` 或你现有的等价客户端初始化路径注册。不要从公共静态初始化器引用客户端辅助类，否则独立服务器可能在类加载时失败。

生命周期顺序为：

```text
模组内容注册
  → 客户端 renderer / item binding 注册
  → Minecraft 资源重载：发现 descriptor，加载并校验 GLB/PNG，准备当前 generation
  → 客户端提取：读取实体状态、选择动画、产生不可变快照
  → 渲染提交：只消费已准备快照
  → 卸载、重载、断线：由适配器清理对应运行状态
```

业务模组不调用 `BlendLibClientServices.initialize()`、`BlendAnimations.initializeCommon()` 或物品 hook 的安装方法；库自己的入口负责这些初始化工作。服务查询需要等 `BlendLibClientServices.isInitialized()` 成立；有服务也不等于你要的资源已经完成首次重载。

下面的 Java 示例均给出完整类。按注释指定的源码集保存，各类同属 `example.blendlib` 包。注册方法接收你已有的内容类型，避免手册替你决定实体 AI、注册流程或方块实现。

<a id="first-model"></a>
## 4. 第一个静态模型

### 4.1 先使用已有的完整模型资源

仓库的 [strict model-pack 模板](../templates/model-pack/README.md) 含完整静态 descriptor、GLB 和 PNG。把模板中 `assets/blendlib_template/` 的三个资源子目录复制到你的 `src/main/resources/assets/example/`：

```text
src/main/resources/assets/example/
├── blend_models/starter_rigid.json
├── models3d/starter_rigid.glb
└── textures/blendlib/starter_rigid__staticsurface.png
```

将 descriptor 中资源 ID 的 namespace 从 `blendlib_template` 改为 `example`，保持材质槽名称 `StaticSurface`。本例的完整 descriptor：

<!-- handbook-json: descriptor/starter_rigid.json -->
```json
{
  "format_version": 1,
  "profile": "blendlib:rigid_v1",
  "mesh": "example:models3d/starter_rigid.glb",
  "units_per_block": 1.0,
  "materials": {
    "StaticSurface": {
      "base_color": "example:textures/blendlib/starter_rigid__staticsurface.png",
      "mode": "opaque",
      "double_sided": false,
      "emissive": false
    }
  }
}
```

只复制模组所需的 `assets/` 资源，不把模板的 `pack.mcmeta` 当成所有版本通用的资源包元数据。模板中的 `variants/`、`materials/`、`sockets/`、`events/` sidecar 是参考数据，strict runtime 不会自动读取。

### 4.2 定义模型 key

文件：`src/client/java/example/blendlib/HandbookModels.java`。

<!-- handbook-java: client/HandbookModels.java -->
```java
package example.blendlib;

import com.liy.blendlib.api.BlendModelKey;

public final class HandbookModels {
    public static final BlendModelKey STARTER = BlendModelKey.parse("example:starter_rigid");
    public static final BlendModelKey ACTOR = BlendModelKey.parse("example:actor");

    private HandbookModels() {
    }
}
```

`ACTOR` 供后续自制动画模型使用，构造这个 key 不会读取尚不存在的文件。

### 4.3 完成第一条渲染路径

实体使用[第 9 节](#entities)完整辅助类中的 `registerStatic(type)`；物品使用[第 12 节](#items)的 `registerStatic(itemId)`。在自己的客户端入口传入已经注册的类型或 ID，只选你需要的接入方式。

启动开发客户端后先执行：

```text
/blendlib assets
/blendlib inspect example:starter_rigid
/blendlib diagnostics example:starter_rigid
```

模型能被发现且没有加载错误后，再进入测试世界召唤自己的实体或获取自己的物品，检查尺寸、朝向、贴图与光照。这样可以区分“资源没有加载”和“业务内容没有注册/生成”。

<a id="resources"></a>
## 5. 资源 ID、文件布局与模型身份

### 5.1 名称对应关系

| 名称 | 示例 | 对应内容 |
| --- | --- | --- |
| 模型 key | `example:actor` | Java 中的 `BlendModelKey`，不带扩展名 |
| descriptor 资源 ID | `example:blend_models/actor.json` | `assets/example/blend_models/actor.json` |
| GLB 资源 ID | `example:models3d/actor.glb` | `assets/example/models3d/actor.glb` |
| 贴图资源 ID | `example:textures/blendlib/actor_body.png` | 具体外置 PNG，带 `textures/` 和 `.png` |
| 动画状态 key | `example:attack` | descriptor `animation.states` 中的逻辑状态名 |
| GLB clip 名 | `Attack` | GLB 内实际动画名称，大小写精确匹配 |
| 节点/骨骼名称 | `TailBone` | GLB 内唯一名称，用于姿态回调中的 rig 查询 |
| 挂点节点路径 | `ActorRoot/Armature/HandSocket` | 从默认 scene root 开始的完整节点层级路径 |

`BlendModelKey.parse("example:actor")` 只做身份校验。`descriptorResourceId()` 推导 descriptor 路径；不要把 GLB 路径、`assets/` 前缀或 `.json` 后缀塞进模型 key。

资源 namespace/path 使用小写可接受字符，路径不能是绝对路径、网络地址或包含 `..`、`.`、空段的逃逸路径。GLB 内 clip、material、node 名称是另一套名称，不应为了符合资源 ID 规则而擅自改变它们。

### 5.2 资源、实例、快照各自表示什么

| 类型 | 表示什么 | 使用建议 |
| --- | --- | --- |
| `BlendModelKey` | 可跨重载复用的模型身份 | 可保存为 `static final` |
| `BlendInstanceKey.Entity` | 连接会话中的实体实例 | 不用裸 entity ID 作为跨重连身份 |
| `BlendInstanceKey.BlockEntity` | 维度 + 方块位置 | 不用位置单独区分跨维度实例 |
| `BlendInstanceKey.Item` | 当前 v1 的无状态物品身份 | 不能当成每个 ItemStack 独立动画状态 |
| `BlendInstanceKey.Ephemeral` | 调用方局部会话中的临时身份 | 只是身份类型，不会自动注册世界特效 renderer |
| `ModelInstance` | 实例 key + 模型 key + generation | 不是可播放/可修改的动画控制器 |
| 渲染快照/句柄 | 某次资源代次和提取时刻的准备结果 | 由高层 renderer 管理，不长期缓存内部对象 |

<a id="blender"></a>
## 6. Blender 制作与导出

### 6.1 制作约束

使用附带 exporter 时最低 Blender 版本为 **5.1.0**。基本输入应满足：

- 使用明确导出 collection，例如 `BlendLibExport`，保持导出根和需要按名称查找的骨骼/节点清晰、唯一。
- Blender 中 `+Z` 向上、`-Y` 向前；推荐 1 Blender Unit 对应 1 block。
- 运行网格使用三角形，包含位置、法线与 UV0。
- skin 每顶点最多 4 个有效权重，归一化且索引有效；inverse bind matrices 必须存在。
- 应用或烘焙负缩放、非均匀缩放；strict v1 要求有限、正值、均匀的节点缩放。
- 约束、驱动、程序化修改器和物理效果应烘焙为受支持的网格或关键帧。
- 动画使用 `LINEAR` 或 `STEP`；旋转线性通道按四元数插值处理。不要导出 `CUBICSPLINE`。
- 附带 exporter 要求每个材质恰好一个外置 PNG image node，不能是 packed image。包含多张图的复杂节点网络会使导出失败；先烘焙/整理为受支持的单图材质。
- 外置 PNG 由 descriptor 绑定；Blender 节点材质、法线贴图、金属度/粗糙度网络不会自动转换成运行时 PBR。

坐标只在导出/导入边界转换一次：`X = Blender X`、`Y = Blender Z`、`Z = -Blender Y`。不要在 renderer 再补一次同样的轴转换。

### 6.2 CLI 导出

在 BlendLib 仓库根执行，下列路径换成你的 Blender、源文件和模组工程。参数从 `--` 后开始：

```powershell
& 'D:\Program Files\Blender\blender.exe' --background `
  --python blender-addon\scripts\export_blendlib.py -- `
  --blend art\example\actor\source.blend `
  --project-root D:\MyFabricMod `
  --namespace example `
  --model-id actor `
  --profile blendlib:skinned_v1 `
  --collection BlendLibExport
```

静态或节点刚体模型使用 `--profile blendlib:rigid_v1`。可用 `--output-resource-root` 指定资源目录，默认 `src/main/resources`；`--report` 指定报告位置。

成功输出包括 descriptor、GLB 和外置 PNG。保存导出报告中的文件哈希、节点数、顶点/索引数、动画名称与 bounds。实际材质槽和 clip 名应以导出结果为准，再编写逻辑动画状态映射。

### 6.3 不支持的运行格式与特性

| 项目 | 处理方式 |
| --- | --- |
| `.blend`、FBX、OBJ、外部 `.gltf + .bin` | 在离线工具中转成受支持 GLB，不作为 runtime 输入 |
| Morph targets / shape keys | 当前 strict v1 不支持 |
| Draco、Meshopt、sparse accessor | 当前 strict v1 拒绝 |
| 顶点颜色、多 UV 集 | 当前 strict v1 拒绝 |
| 任意 Blender shader/物理/约束实时运行 | 烘焙或重做为受支持表现 |
| 相机、灯光节点 | strict v1 不作为运行灯光，相应数据可被忽略并产生警告 |
| GLB 内图片/纹理/采样器 | 非空 `images`、`textures` 或 `samplers` 数组都会被 strict loader 拒绝；附带 exporter 剥离这些数据，PNG 由 descriptor 外置引用 |

### 6.4 Blockbench / GeckoLib 离线转换

仓库提供 Python 3.11+ 工具，先查真实参数：

```powershell
python tools/model-converter/blendlib_model_converter.py --help
```

示例命令从仓库根运行，输入路径相对 `authoring`：

```powershell
python tools/model-converter/blendlib_model_converter.py --source-root authoring --input models/clockwork.bbmodel --texture textures/clockwork.png --out generated-pack --namespace example --model machines/clockwork --source-units-per-block 16
```

它主要处理受支持的立方体几何、节点层级和线性/阶梯动画，输出 rigid GLB；不会执行 Molang、GeckoLib controller 或任意事件脚本。默认拒绝不能忠实转换的内容和已有输出。`--allow-lossy` 需要逐项审核报告中的丢失项，`--force` 会允许替换经过检查的已有输出文件。完整边界见[转换指南](expansion/x8/converter-guide.md)。

<a id="descriptor"></a>
## 7. 完整 descriptor 与字段参考

### 7.1 动画模型示例

路径为 `assets/example/blend_models/actor.json`。本例要求你的 GLB 实际包含 `Body` 材质、`Idle` / `Walk` / `Attack` clip，以及从默认 scene root 开始的 `ActorRoot/Armature/HandSocket` 节点层级。它是完整配置示例，不会生成这些 GLB 内容；若资源名称或层级不同，应同时修改映射。

<!-- handbook-json: descriptor/actor.json -->
```json
{
  "format_version": 1,
  "profile": "blendlib:skinned_v1",
  "mesh": "example:models3d/actor.glb",
  "units_per_block": 1.0,
  "materials": {
    "Body": {
      "base_color": "example:textures/blendlib/actor_body.png",
      "mode": "opaque",
      "emissive": false,
      "double_sided": false
    }
  },
  "animation": {
    "initial_state": "example:idle",
    "states": {
      "example:idle": {
        "clip": "Idle",
        "loop": true,
        "speed": 1.0,
        "blend_seconds": 0.15
      },
      "example:walk": {
        "clip": "Walk",
        "loop": true,
        "speed": 1.0,
        "blend_seconds": 0.15,
        "events": [
          { "time_seconds": 0.25, "event": "example:step" }
        ]
      },
      "example:attack": {
        "clip": "Attack",
        "loop": false,
        "speed": 1.0,
        "next": "example:idle",
        "blend_seconds": 0.08,
        "events": [
          { "time_seconds": 0.25, "event": "example:swing" }
        ]
      }
    }
  },
  "sockets": {
    "example:hand": { "node": "ActorRoot/Armature/HandSocket" }
  },
  "extensions_used": [],
  "extensions_required": [],
  "extensions": {}
}
```

`Walk` 和 `Attack` 的时长必须覆盖 0.25 秒事件时刻。删除不需要的 `sockets` 或 `events` 是允许的；不能引用不存在的节点/clip 后期待运行时补齐。

### 7.2 顶层字段

| 字段 | 必需 | 用途与限制 |
| --- | --- | --- |
| `format_version` | 是 | 当前值为 `1` |
| `profile` | 是 | `blendlib:rigid_v1` 或 `blendlib:skinned_v1` |
| `mesh` | 是 | 指向资源中的 GLB；包含 `models3d/` 和 `.glb` |
| `units_per_block` | 否 | 每 block 对应多少模型单位，默认 `1.0`，有限正值；渲染使用其倒数缩放 |
| `materials` | 是 | 非空对象；为实际 GLB material slot 提供映射 |
| `animation` | 否 | 初始状态与动画状态表；动画路径需要相应有效配置 |
| `sockets` | 否 | 挂点资源 ID 到默认 scene root 起完整节点路径的映射 |
| `extensions_used` | 否 | 扩展名称元数据，不自动启用功能 |
| `extensions_required` | 否 | 当前 strict decoder 要求为空；非空以 `BLENDLIB-EXT-001` 拒绝 |
| `extensions` | 否 | 当前 decoder 接受对象形状但不保留其数据供运行消费；不是自定义业务配置入口 |

未知顶层字段会被拒绝。当前实际 schema/decoder 接受上面三个 extension 字段，部分历史冻结文档的笼统描述不应替代当前字段行为。普通模型建议省略这些字段或保持空值。

`units_per_block: 16` 的意思是模型空间 16 单位显示为 1 block，不是放大 16 倍。不要再在根节点缩放中重复补偿。

### 7.3 动画字段

| 字段 | 必需 | 含义 |
| --- | --- | --- |
| `animation.initial_state` | 是 | 已在 `states` 中声明的逻辑状态 |
| `animation.states` | 是 | 非空状态表，最多 256 个状态 |
| `states.<id>.clip` | 是 | GLB 中实际 clip 名称 |
| `loop` | 是 | 是否循环播放 |
| `speed` | 是 | 有限、正数、最大 64 的 clip 速度倍数 |
| `next` | 否 | 非循环状态完成后进入的已声明状态 |
| `blend_seconds` | 否 | 有限非负过渡时长；实际过渡取决于使用的动画路径 |
| `events` | 否 | 按 clip 时间声明表现事件；每状态最多 4,096 个 |
| `events[].time_seconds` | 是 | 非负、位于有效 clip 时间范围内的秒数 |
| `events[].event` | 是 | 命名空间限定的事件 ID |

不循环且没有 `next` 的状态会保持终点姿态；循环状态通常不配置 `next`。完整校验还会检查初始状态、后继状态和 clip 引用，JSON Schema 通过只是第一步。

descriptor 的动画状态与 `AnimationRequest` 是两套不同层次的数据：标准 Fabric 动画路径按状态 key 选择 descriptor 配置，不能通过只构造一个 `AnimationRequest` 来改变标准 renderer 的速度或混合行为。

<a id="materials"></a>
## 8. 材质与贴图

### 8.1 材质字段

| 字段 | 默认 | 含义 |
| --- | --- | --- |
| `base_color` | 必填 | 具体外置 PNG 资源 ID，例如 `example:textures/blendlib/actor_body.png` |
| `mode` | `opaque` | schema 可表达 `opaque`、`cutout`、`translucent`、`additive`；实际 adapter 只接受支持子集 |
| `emissive` | `false` | 标准路径采用 full-bright 光照值，不等于动态光源或 bloom |
| `double_sided` | `false` | 双面意图；与实际渲染路径是否可表达相关 |
| `cutout_threshold` | 缺省 | 仅 `cutout` 可用，schema 范围 `[0,1]`，标准路径限制更窄 |

材质对象的 key 是 GLB 材质槽名称，例子中的 `Body` 不能随意改成文件名。纹理 ID 使用具体文件路径；它与部分原版 item JSON 省略 `.png` 的纹理写法不同。

### 8.2 当前标准材质映射的支持子集

以下来自当前共享 `MaterialRenderMapper` 与 26.1.2 默认 backend。其他版本虽然复用该判断，仍应检查目标版本实际画面。

| 组合 | 默认路径结果 |
| --- | --- |
| `opaque` + `double_sided: false` | 支持 |
| `opaque` + `double_sided: true` | 拒绝，不能精确映射 |
| `cutout` + 单面或双面 | 支持；阈值省略或精确为 `0.1` |
| `cutout_threshold: 0.5` | schema 合法，但默认 adapter 拒绝 |
| `translucent` + `double_sided: true` | 支持该映射；透明排序与重叠效果仍需视觉检查 |
| `translucent` + `double_sided: false` | 拒绝 |
| `additive` | 默认路径拒绝 |

被拒绝时会产生 `BLENDLIB-MAT-004` 和 missing model。遇到错误先修改材质意图或资源设计，不把 `double_sided` 当成无条件可开的开关。

对于常规带透明边缘的薄片模型，可优先测试 `cutout`；对于真正半透明对象，使用受支持组合后检查背面、穿插和多个对象重叠。这里不承诺任意 PBR 材质、光影 mod 桥接或所有透明排序情况。

<a id="entities"></a>
## 9. 实体模型与本地动画

### 9.1 定义公共动画 key

文件：`src/main/java/example/blendlib/HandbookAnimations.java`。服务端与客户端共享同一逻辑状态 key。

<!-- handbook-java: main/HandbookAnimations.java -->
```java
package example.blendlib;

import com.liy.blendlib.api.BlendAnimationKey;

public final class HandbookAnimations {
    public static final BlendAnimationKey IDLE = BlendAnimationKey.parse("example:idle");
    public static final BlendAnimationKey WALK = BlendAnimationKey.parse("example:walk");
    public static final BlendAnimationKey ATTACK = BlendAnimationKey.parse("example:attack");

    private HandbookAnimations() {
    }
}
```

### 9.2 三种 renderer 配置

文件：`src/client/java/example/blendlib/HandbookEntityBindings.java`。

<!-- handbook-java: client/HandbookEntityBindings.java -->
```java
package example.blendlib;

import com.liy.blendlib.fabric.client.entity.BlendEntityRenderer;
import com.liy.blendlib.fabric.client.entity.BlendEntityRenderers;
import com.liy.blendlib.fabric.client.entity.SkinnedAnimationVisualEventHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

public final class HandbookEntityBindings {
    private HandbookEntityBindings() {
    }

    public static <E extends Entity> void registerStatic(EntityType<E> type) {
        BlendEntityRenderers.register(type, context ->
                BlendEntityRenderer.<E>builder(context, HandbookModels.STARTER)
                        .staticRestPose()
                        .shadowRadius(0.45F)
                        .build());
    }

    public static <E extends Entity> void registerLocalAnimated(
            EntityType<E> type,
            SkinnedAnimationVisualEventHandler<E> visualEvents) {
        BlendEntityRenderers.register(type, context ->
                BlendEntityRenderer.<E>builder(context, HandbookModels.ACTOR)
                        .skinnedAnimation((entity, request) -> {
                            var velocity = entity.getDeltaMovement();
                            double horizontalSpeedSq = velocity.x * velocity.x + velocity.z * velocity.z;
                            return horizontalSpeedSq > 0.0001D
                                    ? HandbookAnimations.WALK
                                    : HandbookAnimations.IDLE;
                        })
                        .onSkinnedVisualEvent(visualEvents)
                        .shadowRadius(0.45F)
                        .shadowStrength(1.0F)
                        .build());
    }

    public static <E extends Entity> void registerSyncedAnimated(EntityType<E> type) {
        BlendEntityRenderers.register(type, context ->
                BlendEntityRenderer.<E>builder(context, HandbookModels.ACTOR)
                        .synchronizedSkinnedAnimation((entity, request) -> HandbookAnimations.IDLE)
                        .shadowRadius(0.45F)
                        .build());
    }
}
```

在你的客户端入口中，以 `HandbookEntityBindings.registerStatic(你的EntityType)`、`registerLocalAnimated(你的EntityType, 你的事件处理器)` 或 `registerSyncedAnimated(你的EntityType)` 选择一种注册。这里的中文参数说明指你的业务对象，不是另一个需要实现的 BlendLib API。事件处理器可直接写 lambda；需要忽略事件时使用 `(entity, eventKey) -> { }`。

同一实体类型只注册一个最终 renderer。不要给它同时调用三种辅助方法。

### 9.3 选择器的正确使用方式

- `SkinnedAnimationStateSelector` 返回 `BlendAnimationKey`，不是 GLB clip 字符串，也不是 `AnimationRequest`。
- 选择器在提取阶段执行，应只读已有客户端状态，不读文件、不发包、不改变实体玩法。
- 示例用水平速度决定 idle/walk，只是最小选择策略；你的模组可使用已经同步的动作状态、飞行/游泳状态或动画状态机。
- 本地路径连续返回相同当前状态 key 不会每帧重播。
- 如果 `attack` 自动 `next` 到 `idle`，但选择器仍持续返回 `attack`，下一次提取会再次触发攻击。让业务状态及时退出攻击条件，或用服务端离散触发。
- 一次性动作的重新播放不能靠“每帧重复返回同一个 key”表达；需要清楚管理状态切换，或使用同步命令的序号语义。

`staticRestPose()`、`skinnedAnimation()`、同步配置和自定义 `snapshotFactory()` 是互斥的提取路线。`.poseModifier()`、`.rootRotation()`、事件与 socket marker 要先配置动画路线，再追加；一个 builder 只接受一个 pose modifier 和一个 root rotation selector。

### 9.4 大模型与 culling

阴影半径是视觉阴影大小，不是实体命中盒。模型的包围范围、旋转和相机裁剪也不能替代服务器碰撞数据。巨大模型、离屏后重新进入视野、倒置和快速旋转需要在游戏中检查；不要通过改服务端碰撞盒来掩盖纯渲染裁剪问题。

<a id="synchronization"></a>
## 10. 服务端动画同步

### 10.1 完整服务端调用辅助类

文件：`src/main/java/example/blendlib/HandbookServerAnimations.java`。

<!-- handbook-java: main/HandbookServerAnimations.java -->
```java
package example.blendlib;

import com.liy.blendlib.fabric.common.animation.BlendAnimations;
import com.liy.blendlib.fabric.common.animation.SyncedAnimationState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class HandbookServerAnimations {
    private HandbookServerAnimations() {
    }

    public static SyncedAnimationState setEntityIdle(Entity entity) {
        return BlendAnimations.entity(entity).setPersistent(HandbookAnimations.IDLE);
    }

    public static SyncedAnimationState triggerEntityAttack(Entity entity, long seed) {
        return BlendAnimations.entity(entity).trigger(HandbookAnimations.ATTACK, 1.0F, seed);
    }

    public static SyncedAnimationState setBlockEntityIdle(BlockEntity blockEntity) {
        return BlendAnimations.blockEntity(blockEntity).setPersistent(HandbookAnimations.IDLE);
    }

    public static SyncedAnimationState triggerBlockEntityAttack(BlockEntity blockEntity, long seed) {
        return BlendAnimations.blockEntity(blockEntity).trigger(HandbookAnimations.ATTACK, 1.0F, seed);
    }
}
```

这些方法在**服务端线程、对象已加入服务端世界且尚未移除**时调用。公共代码在两端都可能执行时，先按项目的方式确认服务端路径。不要从实体/方块实体构造器、客户端交互分支或工作线程直接触发。

### 10.2 `trigger` 与 `setPersistent`

| 操作 | 当前跟踪者 | 后续开始跟踪者 | 典型用途 |
| --- | --- | --- | --- |
| `trigger(key)` | 发送一次新命令 | 不补发这个历史临时动作 | 攻击、受击、一次性动作 |
| `setPersistent(key)` | 发送并更新可重放状态 | 补发当前持续状态 | 待机、持续工作、长期姿态 |

临时触发不会替换服务器保留的持续状态。先设置 idle，再 trigger attack，新开始跟踪的客户端会获得持续 idle，不一定观看已经发生的攻击。

`persistent` 在这里是**服务器运行期保留用于跟踪重放**。它没有替你的模组写 NBT，也不保证跨服务器重启或对象卸载保留。把机器是否运行、实体当前形态等业务状态存入你自己的存档，在对象重新加入世界时根据业务状态恢复 `setPersistent`。

### 10.3 同步数据与速度

传输的 `SyncedAnimationState` 包含：

| 字段 | 用途 |
| --- | --- |
| `animationKey` | 客户端 descriptor 中的逻辑状态 |
| `startGameTick` | 服务器开始时间，客户端以此推导当前动画时间 |
| `sequence` | 顺序与去旧判断，由服务分配 |
| `speed` | 有限正数，最大 `64.0F` |
| `seed` | 同步载荷携带的种子字段；当前标准 strict 动画采样没有使用它生成随机相位 |
| `persistent` | 是否为可供后续跟踪重放的状态 |

同步速度缩放时间线，descriptor 状态的 `speed` 继续影响 clip 速度。修改两处速度前先明确你希望控制的层次。速度 0 不是暂停命令，负数不是倒放。

服务器只发送 key 和时间语义，不发送 GLB、纹理、骨骼矩阵或整套动画采样数据。客户端必须安装引用这些状态的有效资源；服务端成功发包不证明客户端存在该状态。

### 10.4 何时调用

在“进入某状态”或“一个动作真正发生”时调用，而不是无条件每 tick 调用。同一 key 的重复同步命令仍会产生新开始时间和序号，可能不断重启动作并增加网络量。

标准 entity 同步选择器只在没有已接受同步状态时使用 fallback，不能把 fallback 理解为“每次临时动作结束就自动恢复的服务器状态”。动作后继由 descriptor 的 `next` 或后续服务端命令表达。

当前没有普通 facade 的 `stop()` / `clearPersistent()`。可根据需求切到已声明的 idle/静止状态；不要用不存在的动画 key 清空，也不要访问内部 registry 手动清理。

伤害、命中、消耗、掉落由服务端玩法计时决定。标准同步动画路径按绝对时间采样且不派发历史视觉事件；声音/特效若必须跟随服务端动作，可由业务模组自己的表现同步消息或明确的客户端状态转换触发。

<a id="block-entities"></a>
## 11. 方块实体接入

文件：`src/client/java/example/blendlib/HandbookBlockEntityBindings.java`。

<!-- handbook-java: client/HandbookBlockEntityBindings.java -->
```java
package example.blendlib;

import com.liy.blendlib.fabric.client.blockentity.BlendBlockEntityRenderer;
import com.liy.blendlib.fabric.client.blockentity.BlendBlockEntityRenderers;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class HandbookBlockEntityBindings {
    private HandbookBlockEntityBindings() {
    }

    public static <T extends BlockEntity> void registerStatic(BlockEntityType<T> type) {
        BlendBlockEntityRenderers.register(type, context ->
                BlendBlockEntityRenderer.<T>builder(context, HandbookModels.STARTER)
                        .staticRestPose()
                        .build());
    }

    public static <T extends BlockEntity> void registerSyncedSkinned(BlockEntityType<T> type) {
        BlendBlockEntityRenderers.register(type, context ->
                BlendBlockEntityRenderer.<T>builder(context, HandbookModels.ACTOR)
                        .syncedSkinnedAnimation(HandbookAnimations.IDLE)
                        .build());
    }
}
```

从客户端入口给你的 `BlockEntityType` 选择一种注册。同步动画模型需要 `skinned_v1` 且有合法 skin；普通实体的刚体动画支持不能套用到这里。服务端调用第 10 节的方块实体辅助方法。

方块实体实例按维度与位置区分。删除、更换、卸载区块后，不应在业务代码继续使用旧实例的动画状态。

renderer 注册不创建方块，也不自动修改原版 blockstate JSON。你的业务方块应保留所需的原版外壳或为空的基础表现，避免原版几何和 BlendLib 几何重复绘制。方块朝向、碰撞形状和交互逻辑仍由业务模组配置；普通 builder 没有实体版的 `rootRotation`/`poseModifier` 对称入口。

<a id="items"></a>
## 12. 物品接入

### 12.1 注册显式 binding

文件：`src/client/java/example/blendlib/HandbookItemBindings.java`。

<!-- handbook-java: client/HandbookItemBindings.java -->
```java
package example.blendlib;

import com.liy.blendlib.fabric.client.item.BlendLibItemBinding;
import com.liy.blendlib.fabric.client.item.BlendLibItemModelBindings;
import net.minecraft.resources.Identifier;

public final class HandbookItemBindings {
    private HandbookItemBindings() {
    }

    public static void registerStatic(Identifier itemId) {
        BlendLibItemModelBindings.register(new BlendLibItemBinding(
                itemId,
                HandbookModels.STARTER,
                Identifier.fromNamespaceAndPath("example", "item/actor_item")));
    }
}
```

在客户端初始化时传入你的物品资源 ID，例如 `example:actor_item`。三个参数分别是物品 ID、BlendLib 模型 key、原版基础模型 ID；最后一个不是 GLB 路径，也不是 descriptor 路径。

当前 binding 注册对完全相同的重复项允许复用，对冲突项明确拒绝。仍应在业务模组中集中注册一次。`find(itemId)` 与 `bindings()` 用于只读查询。

### 12.2 26.1.2 普通 marker 资源

`assets/example/items/actor_item.json` 使用原版 item model 定义：

```json
{
  "model": {
    "type": "minecraft:model",
    "model": "example:item/actor_item"
  }
}
```

`assets/example/models/item/actor_item.json` 作为原版基础模型：

```json
{
  "parent": "minecraft:item/handheld",
  "textures": {
    "layer0": "minecraft:item/stick"
  }
}
```

这里用原版 stick 贴图提供无需新增 PNG 的示例基础资源，BlendLib 模型本体仍使用 descriptor 的外置 PNG。调整第一/第三人称、GUI、地面等展示变换时，修改这份基础模型或其父模型的正常 `display` 配置，并逐场景检查。

BlendLib 通过已安装的模型加载 hook 替换显式注册的 marker。不要添加自定义 `"type": "blendlib:model"`；当前没有供此用途使用的公开 JSON type。

### 12.3 物品动画和版本限制

当前标准 marker 路线没有调用动画采样器，不能承诺 GLB 内的动画会自动播放，更不能承诺每个 ItemStack 独立的一次性动作。主线示例使用静态 rigid 模型。

1.21.1–1.21.3 不使用上面新的 `assets/<namespace>/items/` 格式；使用该版本的正常 `models/item/<item>.json`。1.21.4 及之后按目标版本的 item model 格式组织资源。1.21.x 的资源 ID Java 类型通常是 `ResourceLocation`（此处指 Mojang 映射）；见第 20 节。不要把 26.x 的 JSON/Java 回调未经核对复制到所有旧版本。

<a id="procedural"></a>
## 13. 程序化姿态与整体旋转

### 13.1 在关键帧上叠加小幅旋转

`poseModifier` 在基础动画采样后、最终姿态准备前运行。回调得到只读上下文和旋转视图，返回原视图或用 `withRotation` / `withRotations` 派生的新视图。

下例在名为 `TailBone` 的节点本地 Y 轴上叠加小幅摆动。它通过四元数相乘保留基础动画旋转；直接写固定绝对旋转会覆盖原关键帧。

文件：`src/client/java/example/blendlib/HandbookProceduralPoses.java`。

<!-- handbook-java: client/HandbookProceduralPoses.java -->
```java
package example.blendlib;

import com.liy.blendlib.fabric.client.entity.BlendEntityRendererBuilder;
import com.liy.blendlib.fabric.client.entity.BlendEntityRotation;
import net.minecraft.world.entity.Entity;

public final class HandbookProceduralPoses {
    private HandbookProceduralPoses() {
    }

    public static <E extends Entity> BlendEntityRendererBuilder<E> addTailSway(
            BlendEntityRendererBuilder<E> animatedBuilder) {
        return animatedBuilder.poseModifier((entity, context, basePose) -> {
            var node = context.rig().nodeIndex("TailBone");
            if (node.isEmpty()) {
                return basePose;
            }
            int index = node.getAsInt();
            BlendEntityRotation base = basePose.rotation(index);
            double seconds = context.clientGameTimeInTicks() / 20.0D;
            double angle = Math.sin(seconds * 3.0D) * 0.12D;
            float sine = (float) Math.sin(angle * 0.5D);
            float cosine = (float) Math.cos(angle * 0.5D);
            BlendEntityRotation combined = BlendEntityRotation.normalized(
                    base.x() * cosine - base.z() * sine,
                    base.w() * sine + base.y() * cosine,
                    base.x() * sine + base.z() * cosine,
                    base.w() * cosine - base.y() * sine);
            return basePose.withRotation(index, combined);
        });
    }

    public static <E extends Entity> BlendEntityRendererBuilder<E> fixCanonicalRoot(
            BlendEntityRendererBuilder<E> animatedBuilder) {
        return animatedBuilder.rootRotation((entity, request) -> BlendEntityRotation.IDENTITY);
    }
}
```

在 `.skinnedAnimation(...)` 或 `.synchronizedSkinnedAnimation(...)` 后、`.build()` 前调用 `addTailSway(builder)`。示例缺少 `TailBone` 时保留原姿态；如果名称重复，rig 查询会报歧义，应修复模型命名。

`fixCanonicalRoot` 只是展示整体旋转入口，会用单位旋转替换普通实体朝向，不能当作“保持实体原有朝向”。正常实体不需要调用它；飞行/翻滚模型应提供从业务姿态计算出的完整归一化四元数。

### 13.2 回调数据

| 调用 | 用途 |
| --- | --- |
| `context.instanceKey()` / `modelKey()` / `generation()` | 确认当前实例与资源代次 |
| `context.animationKey()` / `animationTimeSeconds()` | 查看本次实际采样的状态和局部动画时间 |
| `context.clientGameTimeInTicks()` | 带提取时间信息的客户端时钟 |
| `context.rig().nodeIndex(name)` | 查找唯一节点；缺失返回空，歧义报错 |
| `context.rig().requireNodeIndex(name)` | 要求节点存在且唯一 |
| `basePose.rotation(index)` | 读取当前节点旋转 |
| `basePose.withRotation(index, rotation)` | 返回单节点旋转覆盖视图 |
| `basePose.withRotations(map)` | 返回多个节点旋转覆盖视图 |

这些视图不允许增加节点或修改平移/缩放。只能返回本次回调收到的 pose 或从它派生的 pose，不能返回缓存的上一帧结果。不同资源包可以改变节点索引，不把索引缓存为跨 generation 的静态常量。回调中的 rig 类型目前来自客户端 animation 包，这属于现有 adapter 暴露范围；调用提供的视图，不据此直接构建或控制内部 runtime。

<a id="events-sockets"></a>
## 14. 动画事件、挂点与附件

### 14.1 视觉事件

descriptor 中 `events` 的时间单位是秒，事件 ID 如 `example:swing`。`SkinnedAnimationVisualEventHandler` 的签名是 `onVisualEvent(entity, eventKey)`，只提供实体和事件 ID，不包含自定义 JSON payload、世界变换、clip 时间或服务器伤害信息。

第 9 节 `registerLocalAnimated` 接收的回调适用于本地推进路径。你可以在里面按 event key 调用业务模组的本地声音或粒子功能。标准同步路径每次从绝对时间采样，不派发这条事件列表；不要依赖同一回调在同步实体上实现可靠的特效时序。

第 9 节的最小 selector 只选择 idle/walk，因此可以观察第 7 节 Walk 中的 `example:step`。要观察 `example:swing`，还需要让本地业务动作状态选择 ATTACK 并在动作结束后退出该条件；仅在 descriptor 声明攻击事件不会自动启动攻击。

视觉事件不是游戏规则触发器。服务端攻击是否命中、扣血或消耗物品，应在业务模组的服务端逻辑完成；客户端事件只表现已经决定的动作。

### 14.2 声明挂点

第 7 节的 `"example:hand": { "node": "ActorRoot/Armature/HandSocket" }` 表示从该完整层级路径取挂点。路径必须从默认 scene 的实际根节点开始，以 `/` 连接每一级名称；找不到或有歧义时加载失败。它与 pose 回调中 `rig().nodeIndex("TailBone")` 使用唯一短名称的规则不同。只有目标本身就是 scene root 时，socket 路径才可能只有一个名称。

若需要一个偏移点，可在美术资产中设置对应节点/骨骼层级；当前 socket schema 没有任意 `offset` 字段。

实体动画 builder 的 `.skinnedSocketMarker(BlendResourceId.parse("example:hand"))` 可配置一个表现用挂点标记。它不是“在手上渲染任意 ItemStack”的附件 API，也不返回可缓存的世界矩阵。

### 14.3 `SocketQuery` 的真实范围

`SocketQuery.of(modelInstance, socketId)` 只建立查询请求。当前解析实现位于程序化 core 快照中；普通稳定 facade 没有直接提供 `querySocketWorldTransform` 或附件 renderer。

做武器、挂件、拖尾时，先确认所需消费入口是否存在。不能为了实现一个附件就把内部 pose cache、矩阵数组或 core snapshot 变成业务模组的长期依赖。需要高级集成时，把它作为针对精确版本的单独 adapter 扩展设计，并明确重载、坐标空间、所有权与视觉验证要求。

<a id="diagnostics"></a>
## 15. 资源重载、模型查询与诊断

### 15.1 用 key 重查当前资源

资源重载会产生新的 generation。可长期保存语义 key；加载结果、模型视图、节点索引、快照和渲染句柄应按其资源代次使用。

高层 renderer 已接入库的提取与重载流程。普通消费模组不需要自己的 GLB loader，不应在每帧或 `submit` 中读取 descriptor/PNG。

### 15.2 完整诊断辅助类

文件：`src/client/java/example/blendlib/HandbookDiagnostics.java`。

<!-- handbook-java: client/HandbookDiagnostics.java -->
```java
package example.blendlib;

import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.api.BlendLibClientServices;
import java.util.function.Consumer;

public final class HandbookDiagnostics {
    private HandbookDiagnostics() {
    }

    public static void inspect(BlendModelKey key, Consumer<String> output) {
        if (!BlendLibClientServices.isInitialized()) {
            output.accept("BlendLib 客户端服务尚未初始化");
            return;
        }
        var view = BlendLibClientServices.models().resolve(key);
        output.accept("model=" + key.value()
                + ", generation=" + view.generationId()
                + ", discovered=" + view.discovered()
                + ", missing=" + view.missing());
        for (var diagnostic : BlendLibClientServices.diagnostics().diagnostics(key)) {
            output.accept(diagnostic.code() + " " + diagnostic.location()
                    + " " + diagnostic.message());
        }
    }
}
```

把 `output` 接到你自己的调试命令或日志。这是按需检查工具，不应每帧输出全部诊断。`discovered` 表示被发现；`missing` 还可以表示资源虽被发现但校验失败后使用回退，不能仅看前者判断成功。

### 15.3 客户端命令

| 命令 | 用途 |
| --- | --- |
| `/blendlib assets` | 查看当前资源代次、发现的模型与诊断摘要 |
| `/blendlib inspect example:actor` | 查看指定模型结果与主诊断 |
| `/blendlib diagnostics` | 查看当前资源代次的诊断 |
| `/blendlib diagnostics example:actor` | 只看指定模型的相关诊断 |

这些是客户端诊断入口，不应当成独立服务器控制台命令。资源修改后使用 F3+T 或目标平台等价资源重载，再检查新的 generation 和日志。

### 15.4 资源包覆盖

资源按 Minecraft 的资源包优先级解析。只覆盖 descriptor 但仍引用较低优先级旧 GLB/PNG，可能产生名称、材质或动画不匹配。一起核对最终生效的 descriptor、GLB 和 PNG，而不是只看你编辑的那个文件。

在独立资源包中覆盖时，资源包自己的 `pack.mcmeta` 必须匹配目标游戏版本。模组资源目录不需要照搬模板资源包的历史 pack format。

<a id="troubleshooting"></a>
## 16. 错误排查表

先记录精确游戏/库版本、模型 key、出错资源、诊断 code/location、首次有效错误和 exporter 报告。洋红/黑 missing model 是明确失败回退，不是正常模型加载成功。

| 现象或代码 | 首先检查 | 下一步 |
| --- | --- | --- |
| `NoClassDefFoundError` 指向 client 类，服务器无法启动 | 公共入口/静态常量是否引用客户端辅助类 | 移入 client source set，检查公共类依赖 |
| 类存在但方法签名不匹配 | Minecraft 版本、映射、runtime JAR 是否配对 | 用目标版本 JAR 重新编译，排除重复 JAR |
| 模型没有被发现 | descriptor 是否在 `assets/<ns>/blend_models/` | 检查打包输出、namespace、key 和资源优先级 |
| `BLENDLIB-DESC-001` | `format_version` | 保持当前支持的 `1` |
| `BLENDLIB-DESC-002` | 字段类型、未知字段、资源路径、PNG 后缀、引用 | 按诊断 location 修正 descriptor |
| `BLENDLIB-GLB-001` / `002` | GLB header、长度、chunk/JSON 布局 | 从源资产重新严格导出 |
| `BLENDLIB-GLB-014` / `015` | accessor 越界、索引、primitive、非有限数值 | 检查 GLB 导出报告和网格数据 |
| `BLENDLIB-SCENE-004` / `005` | 节点循环、层级、transform、缩放 | 修复资产层级和变换后重新导出 |
| `BLENDLIB-SCENE-006` | 相机/灯光 | 检查是否误带入；该类数据不产生运行灯光 |
| `BLENDLIB-SKIN-001` | skin、joint、权重、inverse bind matrices | 检查 profile 一致性和每顶点 4 权重限制 |
| `BLENDLIB-ANIM-006` / `007` | 时间顺序、采样、插值 | 使用合法 LINEAR/STEP 通道并核对 clip |
| `BLENDLIB-MAT-003` | GLB 每个 material slot 的名称映射 | 补齐正确 material key |
| `BLENDLIB-MAT-004` | 材质组合是否在第 8 节支持子集中 | 修正双面、透明模式或 cutout 阈值 |
| `BLENDLIB-EXT-001` | 是否声明 required extension | 移除不支持的依赖，重新使用 strict profile |
| `BLENDLIB-LIMIT-001` | 哪一项超过硬上限 | 简化或拆分资产，不以运行时绕过处理 |
| `BLENDLIB-PERF-001` | 顶点/joint 数量 | 优化资产；警告不等于帧率保证 |
| `BLENDLIB-X1-REG-001` | 是否误走通用 `.register()` 且无平台适配器 | 普通模组改用本手册 Fabric 注册主线 |
| `BLENDLIB-X1-REG-002` / `003` | 通用 spec 是否缺模型/动画 | 补齐语义配置 |
| `BLENDLIB-X1-REG-004` | 同一类别和 host 是否重复通用注册 | 集中注册，排除重复初始化 |
| `BLENDLIB-X1-REG-005` / `006` | 平台适配器失败或回执不一致 | 向该适配器维护者提供诊断与最小复现 |
| `BLENDLIB-X1-REG-007` | 通用 item 请求是否为 ONCE/HOLD | 当前语义 item 仅接受 LOOP；不代表 marker 会播放 |
| 实体动画始终停留在初始姿态 | 是否用了 `staticRestPose()`、状态名是否指向实际 clip | 使用正确动画路线并检查 descriptor |
| 同步事件声音/粒子不触发 | 是否依赖标准同步采样的事件回调 | 按第 14 节使用独立表现同步或本地事件路线 |
| 每次动画刚开始就重新开始 | 是否每 tick 同步同一个 key | 在业务状态变化时发命令 |
| 一次性动作反复重播 | selector 条件是否一直返回 attack | 在动作结束时更新业务状态 |
| 物品模型存在但不播放动画 | marker 路线是否被误当成动画控制器 | 当前按静态路径使用 |
| 重进世界后机器动画丢失 | 是否仅依赖 runtime persistent 状态 | 保存业务状态，并在重新加载时恢复同步 |
| 朝向错误或比例差 16 倍 | 坐标是否二次转换、`units_per_block` 是否重复补偿 | 统一资产尺度和一次边界转换 |
| 姿态节点在换资源包后错位 | 是否跨 generation 缓存 node index | 按当前 rig 名称重新定位 |

提交问题时附上最小 descriptor、GLB、PNG、导出报告、发生步骤和日志；资产有分发限制时使用可共享的最小复现模型。问题入口见[项目 Issues](https://github.com/LIy-hub/BlendLib-Public/issues)。

<a id="performance"></a>
## 17. 性能、线程与资源所有权

### 17.1 资产限制不是性能目标

| 项目 | 默认硬上限 |
| --- | ---: |
| 单个 GLB | 64 MiB |
| 单模型顶点 | 1,000,000 |
| 单模型索引 | 3,000,000 |
| 节点 / 刚体动画节点 | 各 4,096 |
| Skin joints | 512 |
| 层级深度 | 256 |
| 动画 clip | 256 |
| 总关键帧样本 | 1,000,000 |
| 单 clip 时长 | 600 秒 |
| 材质槽 | 256 |
| 挂点 | 512 |
| descriptor 动画状态 | 256 |
| 单状态 / 全 descriptor 视觉事件 | 4,096 / 16,384 |

高于 100,000 顶点或 128 joints 等情形可能出现性能警告。上限是拒绝异常资产的边界，不表示在上限附近渲染大量实例仍可保持可接受帧率。先减少几何、权重和材质分段，再用你的目标场景测量。

### 17.2 普通模组应遵守的操作边界

- 服务端动画命令在服务端线程执行；客户端回调只读当前可用状态。
- 不在选择器、pose modifier、事件回调或提交路径读取文件、解析 JSON/GLB、下载素材或阻塞等待。
- 不逐帧构造新的模型 key，不逐帧注册 renderer，也不逐帧打印所有诊断。
- 不跨重载保存 raw render handle、core 数组、node index 或资源 lease。
- 让库的正常卸载/断线 hook 管理高层 renderer 的运行状态；业务状态由你的模组自行恢复。
- 性能测量只在明确需要时开启，不把采集器当成普通 gameplay API。

### 17.3 可用测量入口

`BlendLibClientServices.performanceMeasurements()` 返回 `ClientRenderMeasurementService`，提供采集 session、帧测量快照和动画指标。`tryBeginExclusiveCapture()` 可能返回空，应尊重已有采集所有者；session 在拥有它的正确线程完成并关闭。

这些数据是 CPU 耗时/计数等观测，不自动证明 GPU 效果、硬件性能或所有第三方渲染兼容。服务类型中有为内部验证保留的 public 方法；普通消费者只使用文档化的测量入口，不调用名称含内部 capability/authority 的桥接方法。

### 17.4 高级快照提交

`BlendRenderer.submit(...)` 只接收已经准备的 `ModelRenderSnapshot`，不负责从 model key 加载资源。自定义 `BlendEntitySnapshotFactory` / `BlendBlockEntitySnapshotFactory` 也属于高级 adapter 扩展。

当前这些签名会触及文档标为实现细节的 `fabric.client.render` 类型，公共/内部边界尚未完全收敛。普通接入优先使用高层 builder；必须编写自定义提交时锁定精确版本、显式管理 generation 和快照生命周期，并单独验证目标游戏版本。不要把它当成跨版本稳定的通用绘制接口。

<a id="api"></a>
## 18. 公开 API 参考表

这里按消费目的整理类型及主要方法，不逐条展开 `equals`、`hashCode`、record accessor 和所有重载。类名不是稳定性等级；当前整套发布仍是 Beta。

### 18.1 纯 Java API：`com.liy.blendlib.api`

| 名称 | 主要用途 / 方法 |
| --- | --- |
| `BlendResourceId` | `parse`、`of`；资源 namespace/path 身份 |
| `BlendModelKey` | `parse`、`of`、`fromResourceId`、`fromDescriptorResourceId`、`descriptorResourceId` |
| `BlendAnimationKey` | `parse`、`of`、`fromResourceId`；逻辑动画状态身份 |
| `BlendInstanceKey` | `entity`、`blockEntity`、`item`、`ephemeral`；分域实例身份 |
| `ModelInstance` | 实例 key、模型 key、非负 generation |
| `SocketQuery` | `of`；对指定模型实例命名挂点的纯查询描述 |
| `BlendLib` | `entity`、`blockEntity`、`item` 通用语义 builder 入口 |
| `EntityRegistrationBuilder<H>` | `model`、`animation`、`build`、`register` |
| `BlockEntityRegistrationBuilder<H>` | 方块实体通用语义配置，同样的构建流程 |
| `ItemRegistrationBuilder<H>` | 物品通用语义配置；只接受 LOOP 请求 |
| `HostKind` | ENTITY / BLOCK_ENTITY / ITEM 宿主类别 |
| `HostRegistrationSpec<H>` | 不可变配置；`animationFor(host)` 动态求请求 |
| `RegistrationReceipt` | 适配器接受通用注册后的回执 |
| `AnimationRequest` | `loop`、`once`、`hold`、`withSpeed`、`withTransition` |
| `AnimationRequestSource<H>` | `requestFor(host)` 动态提供语义请求 |
| `PlaybackMode` | LOOP / ONCE / HOLD |
| `BlendApiDiagnostic` | 结构化注册诊断 |
| `BlendApiDiagnosticCode` | `BLENDLIB-X1-REG-001` 至 `007` 的注册失败代码 |
| `BlendDiagnosticSeverity` | 注册诊断严重程度 |
| `BlendRegistrationException` | `diagnostic()` 获取结构化失败 |

`AnimationRequest` 的速度范围是 `[1/64, 64]`，过渡时长范围是 `[0, 60 秒]`。这是语义请求校验，和 descriptor/server 速度的“正数至 64”边界并不完全相同。

### 18.2 Fabric server/common

| 名称 | 主要用途 |
| --- | --- |
| `fabric.common.animation.BlendAnimations` | `entity` / `blockEntity` 选择服务端目标 |
| `BlendAnimations.EntityAnimationTarget` | `trigger`、`setPersistent`；有默认速度/seed 和显式参数重载 |
| `BlendAnimations.BlockEntityAnimationTarget` | 方块实体的对应同步操作 |
| `fabric.common.animation.SyncedAnimationState` | 同步状态值；普通发送由 facade 创建 |
| `fabric.BlendFabricResourceIds` | 当前目标的 Minecraft 资源 ID 与 `BlendResourceId` 互转，属于平台层 |

不直接使用 `ServerAnimationStateRegistry`、网络 payload/codec 或客户端同步 store。

### 18.3 Fabric entity adapter：`fabric.client.entity`

| 名称 | 主要用途 |
| --- | --- |
| `BlendEntityRenderers` | `register(type, provider)` |
| `BlendEntityRenderer` | `builder(context, modelKey)` 构建入口及实际渲染器 |
| `BlendEntityRendererBuilder` | 静态/动画/同步/自定义快照、pose/root rotation、event/socket marker、阴影与 `build` |
| `SkinnedAnimationStateSelector` | 选择本地动画 key |
| `SyncedSkinnedAnimationStateSelector` | 自定义读取可选的同步语义状态 |
| `SkinnedAnimationVisualEventHandler` | 本地推进动画的表现事件观察回调 |
| `BlendEntityPoseModifier` | 在采样姿态上派生节点旋转 |
| `BlendEntityPoseContext` | 实例、模型、generation、实际采样状态、时钟与 rig 视图 |
| `BlendEntityRotationPose` | `nodeIndices`、`rotation`、`rotationOverrides`、`withRotation(s)` |
| `BlendEntityRotation` | `IDENTITY`、`normalized(x,y,z,w)` |
| `BlendEntityRootRotationSelector` | 当前提取帧的完整模型根旋转 |
| `BlendEntitySnapshotFactory`、`BlendEntitySnapshotRequest` | 高级自定义快照提取与输入 |
| `BlendEntityRenderState` | 平台渲染状态载体；一般由 renderer 管理 |

### 18.4 Fabric block entity / item

| 名称 | 主要用途 |
| --- | --- |
| `blockentity.BlendBlockEntityRenderers` | `register(type, provider)` |
| `blockentity.BlendBlockEntityRenderer` | `builder(context, modelKey)` |
| `blockentity.BlendBlockEntityRendererBuilder` | `staticRestPose`、`syncedSkinnedAnimation`、`snapshotFactory`、`build` |
| `blockentity.BlendBlockEntitySnapshotFactory`、`BlendBlockEntitySnapshotRequest` | 高级自定义快照提取 |
| `blockentity.BlendBlockEntityRenderState` | 平台渲染状态载体 |
| `item.BlendLibItemBinding` | item ID、模型 key、原版 base model ID |
| `item.BlendLibItemModelBindings` | `register`、`find`、`bindings` |

### 18.5 Fabric client services：`fabric.client.api`

| 名称 | 主要用途 |
| --- | --- |
| `BlendLibClientServices` | `isInitialized`、`models`、`renderer`、`diagnostics`、`commands`、`performanceMeasurements` |
| `ClientModelLookup` | `snapshot`、`resolve`；资源所有权相关方法属于高级集成 |
| `ClientModelView` | `key`、`generationId`、`discovered`、`missing`、`primaryDiagnostic`；不要缓存其 raw handle |
| `ClientRegistryView` | 当前资源代次、模型视图集合、诊断集合 |
| `ClientDiagnostic`、`ClientDiagnosticSeverity` | 客户端可读诊断值 |
| `BlendRenderer` | 已准备快照的 `submit`；目标版本专用 |
| `ClientRenderMeasurementService` | 明确启用的测量服务 |
| `ClientRenderMeasurementSession` | 测量会话与释放 |
| `ClientRenderMeasurementSnapshot`、`ClientAnimationRuntimeMetrics` | 不可变测量结果 |
| `fabric.client.command.ClientDiagnosticsService` | `assets`、`inspect`、`diagnostics` |
| `fabric.client.command.ClientDiagnosticsCommandRegistry` | 与游戏命令注册分开的诊断命令分发 |

`initialize`、`skinnedAnimationRuntime`、bootstrap token、registry/lease 桥接与内部 backend 的 public 可见性不表示它们是普通业务模组应直接调用的入口。

<a id="experimental"></a>
## 19. 通用注册与实验性 SPI

### 19.1 先理解 `.build()` 和 `.register()`

`BlendLib.entity(host).model(key).animation(request).build()` 创建一个不可变语义配置。它不注册 Fabric renderer、不读取资源、不产生游戏对象。

`.register()` 会把配置提交给已安装的平台适配器。当前默认客户端入口没有自动安装这条通用注册协议对应的 adapter。普通模组直接调用可能得到 `BLENDLIB-X1-REG-001`，而不是模型显示出来。

下面完整类只展示无需平台适配器的 `.build()`，适合工具或对语义配置的理解，不替代第 9/11/12 节。

文件：`src/main/java/example/blendlib/HandbookSemanticSpec.java`。

<!-- handbook-java: main/HandbookSemanticSpec.java -->
```java
package example.blendlib;

import com.liy.blendlib.api.AnimationRequest;
import com.liy.blendlib.api.BlendLib;
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.HostRegistrationSpec;
import java.time.Duration;

public final class HandbookSemanticSpec {
    private HandbookSemanticSpec() {
    }

    public static <H> HostRegistrationSpec<H> describeEntity(H host) {
        return BlendLib.entity(host)
                .model(BlendModelKey.parse("example:actor"))
                .animation(AnimationRequest.loop(HandbookAnimations.IDLE)
                        .withSpeed(1.0D)
                        .withTransition(Duration.ofMillis(150)))
                .build();
    }
}
```

通用 item 配置只接受 `LOOP`，动态 `AnimationRequestSource` 每次求值也校验这个条件。`ModelInstance` 和 `SocketQuery` 仍只是语义数据，不产生控制器或世界坐标结果。

### 19.2 SPI 面向谁

`com.liy.blendlib.spi.experimental` 面向明确编写平台/提供者扩展的人。当前 host protocol 为 `1.1.0`，与普通 API、descriptor schema 和 Minecraft adapter 版本分别管理；源码中还有历史 `1.0.0` 元数据，不应据此选用过时宿主行为。

| 主要类型 | 用途 |
| --- | --- |
| `ExperimentalBlendLibSpi` | 标记单独管理的实验性协议 |
| `BlendProvider` | 提供者身份、能力声明和生命周期回调 |
| `PlatformAdapter`、`PlatformAdapterControl` | 接受语义 host 注册，控制 adapter 安装和卸载 |
| `AssetProfileProvider` | 声明支持的 Profile 标识 |
| `HostRendererProvider` | 声明支持的宿主种类 |
| `MaterialProvider` | 声明支持的材质能力 |
| `RenderBackendProvider` | 声明支持的后端标识 |
| `CapabilityVersion`、`CapabilityVersionRange` | 能力版本和兼容范围 |
| `CapabilityOffer`、`CapabilityRequest`、`CapabilityRequirement` | 提供/请求能力的描述 |
| `CapabilityRegistry`、`CapabilityPlan`、`CapabilitySelection` | 能力登记、协商计划和选择结果 |
| `CapabilityFallback`、`CapabilitySelectionOutcome` | 回退策略和选择结果类别 |
| `CapabilityDiagnostic`、`CapabilityErrorCode`、`CapabilityNegotiationException` | 协商诊断与失败 |
| `ProviderLifecycleSession`、`ProviderLifecycleContext`、`ProviderLifecycleResult` | 生命周期管理、上下文与结果 |
| `ProviderLifecycleStage`、`ProviderLifecycleState` | 阶段与状态 |
| `ProviderOwnership`、`ProviderLease` | 所有权、资源使用期和释放 |

能力声明不等于提供了 renderer 实现。例如实现 `supportedRenderBackends()` 不会自动切换 Minecraft 渲染后端，声明 Profile 也不会改变 strict decoder 的接受集。

从 [第三方提供者示例](../examples/third-party-providers/README.md)、[SPI 包契约](../blendlib-api/src/main/java/com/liy/blendlib/spi/experimental/package-info.java) 和[生命周期契约](expansion/x1/stable-surface.md)开始。普通模组不安装全局 adapter，不用 provider 去接管其他模组的资源；扩展作者需要独立验证协议范围、能力缺失回退、重载、退休、释放和异常行为。

### 19.3 已有实现但不提升为普通 API 的范围

多控制器动画、程序化快照、host adapter、variant/material、GPU 后端和高级 Profile 候选都有各自源码与历史文档。它们的存在不意味着当前默认入口已经连接，更不意味着 runtime、视觉或性能通过。只按你的扩展目的查阅[扩展文档索引](expansion/README.md)，不要把历史阶段编号写进普通模组用户操作流程。

<a id="migration"></a>
## 20. 多版本适配与升级

### 20.1 共享内容与版本专属内容

| 通常可共享 | 必须核对目标版本 |
| --- | --- |
| 模型/动画 key 字符串 | Minecraft 类名、包、泛型、renderer callback |
| strict v1 descriptor 与受支持 GLB/PNG | Fabric 事件和注册签名 |
| 业务动画状态语义 | Loom/remap 配置与 Java 版本 |
| 对事件/动作的业务命名 | 原版 item JSON、pack format、render state 生命周期 |
| API 纯数据设计 | renderer collector、材质/原生 backend 的实际行为 |

1.21.x 的 Java 基线为 21；26.x 为 25。当前 ports 复用纯 API/core 源码，但原生 renderer 接口发生变化，因此一个版本编译好的业务模组也不能直接推断在另一个版本运行。

### 20.2 主要 adapter 差异

| 游戏范围 | 需要知道的差异 |
| --- | --- |
| 1.21.1 | 实体在旧 render 回调中提取并立即提交；没有后续版本相同的 render-state/collector 接口 |
| 1.21.2–1.21.8 | 使用目标版本的提取/原生 render 路线；低层提交桥是 legacy adapter |
| 1.21.1–1.21.3 物品 | 旧 item render 接入和基础模型变换 |
| 1.21.4 物品 | 原版 special wrapper 与模型状态提取路径不同于前后版本 |
| 1.21.5 及之后物品 | 使用相应版本的模型加载/special model 接口 |
| 1.21.9–1.21.11 | collector 路线，但原生类型及实验性 GPU 可用条件仍不同 |
| 26.1–26.1.2 | 本手册完整 Java 示例的 26.x API 基线 |
| 26.2 | 顶点布局、bind group、draw/提交接口和呈现流程有独立适配 |

Mojang 映射的 1.21.x 常见 `ResourceLocation` 对应 26.1.2 示例中的 `Identifier`；如果你的项目用其他映射，按 IDE 和目标依赖改名。不要仅全局替换类名后就声明适配完成。

### 20.3 升级顺序

1. 记录正在使用的 Minecraft、Loader、Fabric API、BlendLib 版本与 runtime 哈希。
2. 阅读目标版本发布说明，替换对应依赖和必要的原生调用。
3. 编译业务模组；检查有没有直接依赖 core、render internals 或过期 example 配置。
4. 加载静态模型，再检查动画、同步、物品不同视角和资源重载。
5. 用独立服务器和至少两个客户端验证跟踪进入/离开、重连与持久业务状态恢复。
6. 每个发布游戏版本单独保留结果，不用一个版本的成功替代全部版本。

Beta.2 发布声明没有稳定 API/ABI 保证；部分旧 Javadoc 中“stable / 同 major 兼容”表述不应被理解为当前整个发布已经达到这个承诺。迁移时以精确版本源码、制品与最新发布说明共同核对。

<a id="verification"></a>
## 21. 接入验证与发布检查

### 21.1 手册示例与验证边界

本手册的 Java 类是可独立编译的 API 使用样例；它们接收你的模组类型，不自动生成完整的生物、方块或物品注册工程。静态模型使用仓库中真实的模板 GLB/PNG；动画 descriptor 是供自制 `Actor` 资产遵循的配置，必须用实际包含对应 clip、material、node 的 GLB 验证。

编译证明签名和依赖关系成立。JSON/schema 检查证明字段形状成立。它们都不能代替启动、游戏内模型画面、同步行为或性能验收。

本次手册核验（2026-09-09）：9 个完整 Java 类与项目 README 的 1 个独立入门类，使用 Java 25.0.2、Beta.2+26.1.2 runtime JAR 及目标依赖编译通过，未把 BlendLib 项目源码输出加入编译路径。两份 descriptor 均通过 Draft 2020-12 schema 与 release decoder；重命名 namespace 后的静态模板通过 release strict GLB loader。该 runtime 的 SHA-256 为 `01a1cabe230da43222fe30baab23d3f31ed7f09868601c7d81cac9b82f5d83de`。动画示例 GLB 由读者自备，本次未执行游戏启动或视觉验收。

### 21.2 最小接入检查

- [ ] `libs/` 与运行实例使用同一个目标版本 runtime，排除重复 BlendLib。
- [ ] 公共源码能在无客户端类的服务端环境加载。
- [ ] descriptor、GLB、PNG 全部进入你的最终 JAR，`.blend` 和临时文件未误打包。
- [ ] 只在客户端入口注册 renderer/item binding；同一内容类型没有重复覆盖。
- [ ] 初次 reload 和 F3+T 后检查 diagnostics；错误模型没有被当成成功。
- [ ] 静态模型大小、轴向、光照、正反面、透明边缘正确。
- [ ] 动画 key 与 clip 对应，idle/walk/attack 切换、next 和结束保持符合预期。
- [ ] 程序化节点缺失/重名能得到明确处理；更换资源包后没有复用旧索引。
- [ ] 物品第一/第三人称、左右手、GUI、地面和展示场景均检查过。
- [ ] 专用服务器启动、加入/退出和停止正常。
- [ ] 两客户端同时观察，先后进入跟踪范围、重连和维度变化的表现符合业务设计。
- [ ] 区块/实体卸载及服务器重启后，根据已保存业务状态恢复必要动画。
- [ ] 使用大量实例与代表性资产检查 CPU、内存和长时间重载；记录机器与场景。

### 21.3 准确记录验证结果

| 验证层 | 应记录什么 | 不能据此推出什么 |
| --- | --- | --- |
| 静态/编译 | 版本、命令、exit code、样例范围 | 游戏内显示正确 |
| 启动/资源重载 | 实际制品哈希、日志、诊断、正常退出 | 已进入世界或多人同步正确 |
| 游戏内视觉 | 实际模型、视角、动作、截图/录屏与观察结果 | 所有目标版本、所有材质都通过 |
| 多人行为 | 双客户端、跟踪/重连/卸载等复现步骤 | 动画可作为游戏规则权威 |
| 性能 | 硬件、实例数、资产复杂度、帧时间/内存 | 不同硬件或 GPU 后端的性能 |

当前 Beta.2 发布矩阵记录的客户端检查没有进入世界；不能把库的发布记录替代你的消费模组测试。参阅[多版本验证记录](release/multiversion-progress.md)和[客户端验收清单](manual-client-acceptance-v1.md)。

### 21.4 发布你的模组

发布包应包含业务代码、模型 descriptor、GLB、PNG 和正常 Minecraft 资源，并在模组元数据与分发平台设置中说明对应 Minecraft 版本、Fabric API 和 BlendLib 前置。

模型、动画、贴图及转换输入的来源和许可由你的项目核实。BlendLib 非 Add-on 代码使用 Apache-2.0，Blender Add-on 单独使用 GPL-3.0-or-later；具体分发义务阅读各自 LICENSE/NOTICE，不将一个目录的许可套用到全部第三方素材。

<a id="reference"></a>
## 22. 常见问题、配套资料与术语

### 22.1 常见问题

**安装 BlendLib 后为什么没有新生物或物品？**

它是运行前置，内容由依赖它的业务模组提供。你仍需创建自己的实体、物品、方块和注册逻辑。

**服务端需要 Blender 或读取 GLB 吗？**

不需要。Blender 是离线创作工具，模型加载和渲染在客户端。使用动画同步的模组需要服务端对应运行前置与公共接口。

**可以直接读远程 URL 或用户任意磁盘路径作为模型吗？**

当前 descriptor 使用 Minecraft 资源系统中的限定资源 ID，拒绝网络/绝对路径和路径逃逸。先在离线阶段准备资源，再放进模组/资源包。

**能直接迁移 GeckoLib controller 吗？**

离线转换器只转换明确支持的资产内容，不执行 GeckoLib 控制器/Molang/脚本。业务状态机、事件和特效需重新映射到 BlendLib 当前可用接口。

**把 `AnimationRequest.once` 传到实体 `.skinnedAnimation` 可以吗？**

不可以，后者的 selector 返回 `BlendAnimationKey`。普通 Fabric 路线通过 descriptor 状态配置 `loop`/`next`，或用服务端 trigger 表达动作。

**`SocketQuery` 为什么没有返回手部坐标？**

它是请求值，当前普通 facade 缺少完整结果查询和附件绘制流程。见第 14 节，不直接缓存或提取内部骨骼矩阵。

**`emissive` 为什么没照亮旁边的方块？**

默认路径使用 full-bright 光照值，不创建世界动态光源，也不保证 bloom。

**JSON Schema 通过，为什么仍是 missing model？**

Schema 不验证 GLB 内实际材质/clip/node 引用，也不判断当前 adapter 能否表达材质模式。继续检查 loader 和客户端 diagnostics。

**能不能用同一个 BlendLib JAR 覆盖 15 个版本？**

当前每个 release JAR 对应一个精确 Minecraft 版本。共享源码和相同语义 key 不构成二进制通用性。

**能不能把所有 `public` 类都当成 API？**

不能。库为了跨模块适配、内部生命周期和测试留有 public 类型；业务模组优先使用本手册列出的入口。高级签名中仍有内部类型暴露，需要锁定版本并承担额外迁移验证。

### 22.2 配套资料

| 资料 | 适合查什么 |
| --- | --- |
| [发布文档入口](release/README.md) | 当前版本说明与安装/验证导航 |
| [Beta.2 依赖与适配说明](release/beta2-release-notes.md) | 精确版本依赖、发布范围 |
| [descriptor JSON Schema](../schemas/blendlib-model-v1.schema.json) | 可用字段与结构约束 |
| [strict GLB Profile](glb-profile-v1.md) | 容器、几何、skin 与动画限制 |
| [错误码与上限](error-codes-v1.md) | 稳定代码、资源限制与诊断含义 |
| [Blender 导出清单](release/blender-export-checklist-v1.md) | 导出前检查与 CLI |
| [完整静态资源模板](../templates/model-pack/README.md) | 可复用 descriptor + GLB + PNG |
| [转换器指南](expansion/x8/converter-guide.md) | Blockbench/GeckoLib 离线输入边界 |
| [较短的 26.1.2 教程](release/developer-tutorial-26.1.2.md) | 基础接入速览；保留历史 Alpha 基线措辞 |
| [API 稳定性规则](api-stability.md) | 设计层次与兼容轴；结合当前 Beta 发布声明阅读 |
| [第三方提供者示例](../examples/third-party-providers/README.md) | 有意参与实验性 SPI 的扩展作者 |

### 22.3 关键源码定位

这些链接用于查证本文与调试边界，不表示内部实现都应该被业务模组导入。

- [纯 API 入口](../blendlib-api/src/main/java/com/liy/blendlib/api/BlendLib.java)、[语义动画请求](../blendlib-api/src/main/java/com/liy/blendlib/api/AnimationRequest.java)。
- [descriptor 解码](../blendlib-core/src/main/java/com/liy/blendlib/core/descriptor/DescriptorDecoder.java)、[资产上限](../blendlib-core/src/main/java/com/liy/blendlib/core/limits/BlendAssetLimits.java)。
- [实体 builder](../blendlib-fabric-client/src/client/java/com/liy/blendlib/fabric/client/entity/BlendEntityRendererBuilder.java)、[方块实体同步工厂](../blendlib-fabric-client/src/client/java/com/liy/blendlib/fabric/client/blockentity/SyncedSkinnedBlockEntitySnapshotFactory.java)。
- [服务器同步 facade](../blendlib-fabric-common/src/main/java/com/liy/blendlib/fabric/common/animation/BlendAnimations.java)、[服务器跟踪与卸载行为](../blendlib-fabric-common/src/main/java/com/liy/blendlib/fabric/common/animation/ServerAnimationSyncService.java)。
- [客户端动画采样](../blendlib-fabric-client/src/client/java/com/liy/blendlib/fabric/client/animation/runtime/SkinnedAnimationRuntime.java)、[物品静态提取](../blendlib-fabric-client/src/client/java/com/liy/blendlib/fabric/client/item/BlendLibItemSpecialRenderer.java)。
- [默认材质映射](../blendlib-fabric-client/src/client/java/com/liy/blendlib/fabric/client/render/MaterialRenderMapper.java)、[默认客户端初始化](../blendlib-fabric-client/src/client/java/com/liy/blendlib/fabric/client/BlendLibClientEntrypoint.java)。
- [现代版本源码适配](../versions/modern/build.gradle.kts)、[旧版本 API 差异](../versions/legacy/API_ADAPTATION.md)。

### 22.4 术语

| 术语 | 含义 |
| --- | --- |
| descriptor | 将 GLB、材质、逻辑动画状态与挂点关联起来的 BlendLib JSON |
| Profile | 严格声明的资源接受范围，如 rigid_v1 / skinned_v1 |
| clip | GLB 内实际关键帧动画片段 |
| state / key | 业务可选择的逻辑动画身份，可以映射到某个 clip |
| host | 模型依附的实体、方块实体或物品 |
| generation | 一次发布的资源代次，用于隔离重载前后的结果 |
| extraction / 提取 | 读取当前客户端对象状态并准备不可变渲染快照 |
| submit / 提交 | 将已准备的快照交给目标版本渲染路径 |
| persistent | 当前服务器生命周期内可供跟踪重放的动画状态，不等于存档 |
| API | 普通消费者使用的入口与数据契约 |
| SPI | 有意实现平台/能力提供者的扩展协议 |
| missing model | 资源缺失或失败后的明确可见回退模型 |

维护本文时，应先对照源码与实际制品更新示例，再更新能力矩阵。特别注意默认初始化是否接通通用注册、物品/方块实体动画范围、同步事件行为和内部类型边界的变化。
