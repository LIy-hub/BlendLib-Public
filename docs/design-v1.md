# BlendLib v1 设计规格

## 1. 版本与兼容基线

### 1.1 首发环境

- Minecraft：26.1.2
- Fabric Loader：0.19.3
- Fabric API：`0.154.2+26.1.2`
- Java：25
- Mod ID：`blendlib`
- Java package：`com.liy.blendlib`
- 初始开发版本：`0.1.0-alpha.1+26.1.2`

### 1.2 版本体系

BlendLib 同时维护三种版本：

- Asset schema：整数 `format_version`，只在资源结构不兼容时递增。
- BlendLib API：语义化版本。
- Minecraft adapter：以构建元数据标记目标 Minecraft 版本。

资源 schema v1 应能在 26.1.2 和未来 26.2 adapter 之间保持一致。

## 2. Blender 创作契约

### 2.1 源文件

建议业务模组采用：

```text
art/<namespace>/<model-id>/source.blend
art/<namespace>/<model-id>/textures/
```

`art/` 不进入运行 JAR。发行源码包是否包含 `.blend` 由业务模组自行决定。

### 2.2 坐标与单位

BlendLib canonical asset space：

- 右手坐标系。
- `+Y` 向上。
- `+Z` 为模型正前方。
- `+X` 为模型右方。
- 1 个模型单位等于 1 个 Minecraft 方块。
- 模型根原点应位于自然挂载点；实体通常放在脚底中心，方块通常放在锚点中心。

Blender 创作约定：

- `+Z` 向上。
- `-Y` 为模型正前方。
- 1 Blender Unit 等于 1 方块。

导出 add-on 统一执行：

```text
Minecraft X = Blender X
Minecraft Y = Blender Z
Minecraft Z = -Blender Y
```

坐标转换只能在导出/导入边界执行一次。渲染适配器不得为单个模型偷偷补轴向修正。

### 2.3 命名

- namespace、model id、逻辑动画 key 使用 `[a-z0-9._/-]`。
- Blender node/bone/action 原始名称允许大写，但必须唯一。
- socket 推荐使用 `socket.<name>` 节点或骨骼。
- 重复节点名只有在 descriptor 使用完整路径且路径唯一时才允许。
- 业务含义使用逻辑动画 key，不能在 Java 中依赖 Blender Action 的显示名称。

### 2.4 导出前要求

- 网格可三角化。
- 必须存在 normals 和 UV0。
- skinned profile 每个顶点最多 4 个有效权重，权重归一化。
- 非均匀/负缩放必须在导出前应用或烘焙。
- 约束、驱动、程序化修改器必须烘焙为受支持的节点/骨骼关键帧或最终网格。
- 摄像机、灯光、物理、粒子系统不导出。
- Blender 材质只用于识别 material slot；运行材质由 descriptor 决定。

## 3. 运行资源布局

```text
src/main/resources/
└─ assets/<namespace>/
   ├─ blend_models/<path>.json
   ├─ models3d/<path>.glb
   └─ textures/<category>/<path>.png
```

模型 key `example:dragon` 固定解析为：

```text
assets/example/blend_models/dragon.json
```

descriptor 内的 `mesh` 再引用：

```text
example:models3d/dragon.glb
```

资源包可以分别覆盖 descriptor、GLB 或贴图。覆盖后的组合仍必须通过完整校验。

## 4. Descriptor schema v1

### 4.1 完整示例

```json
{
  "format_version": 1,
  "profile": "blendlib:skinned_v1",
  "mesh": "example:models3d/dragon.glb",
  "units_per_block": 1.0,
  "materials": {
    "Body": {
      "base_color": "example:textures/entity/dragon.png",
      "mode": "opaque",
      "emissive": false,
      "double_sided": false
    },
    "Eyes": {
      "base_color": "example:textures/entity/dragon_eyes.png",
      "mode": "translucent",
      "emissive": true,
      "double_sided": false
    }
  },
  "animation": {
    "initial_state": "example:idle",
    "states": {
      "example:idle": {
        "clip": "Idle",
        "loop": true,
        "speed": 1.0
      },
      "example:walk": {
        "clip": "Walk",
        "loop": true,
        "speed": 1.0,
        "blend_seconds": 0.15
      },
      "example:attack": {
        "clip": "Attack",
        "loop": false,
        "speed": 1.0,
        "next": "example:idle",
        "blend_seconds": 0.08,
        "events": [
          {
            "time_seconds": 0.22,
            "event": "example:attack_whoosh"
          }
        ]
      }
    }
  },
  "sockets": {
    "example:mouth": {
      "node": "Armature/Root/Spine/Neck/Head/Mouth"
    }
  },
  "extensions_used": [],
  "extensions_required": [],
  "extensions": {}
}
```

### 4.2 顶层字段

| 字段 | 必需 | 规则 |
|---|---|---|
| `format_version` | 是 | v1 必须为 `1` |
| `profile` | 是 | `blendlib:rigid_v1` 或 `blendlib:skinned_v1` |
| `mesh` | 是 | 同 namespace 或其他已加载资源 namespace 的 GLB |
| `units_per_block` | 否 | 默认 `1.0`，表示多少模型单位等于一方块；渲染比例为其倒数 |
| `materials` | 是 | 每个实际 GLB material slot 必须有映射 |
| `animation` | 否 | 无动画模型可以省略 |
| `sockets` | 否 | 逻辑 socket 到唯一节点路径 |
| `extensions_used` | 否 | 声明可选扩展 |
| `extensions_required` | 否 | 未支持任一项时整个模型失败 |
| `extensions` | 否 | 所有扩展数据必须放在这里 |

未知顶层字段是 schema 错误，避免拼写错误静默生效。

### 4.3 Profile

#### `blendlib:rigid_v1`

- 支持静态模型和节点刚体动画。
- 每个 mesh primitive 绑定到一个节点。
- 顶点不读取 `JOINTS_0/WEIGHTS_0`。
- 节点 translation、rotation、scale 可以动画。
- 适合武器、机械、法阵部件和多节点变形演出。

#### `blendlib:skinned_v1`

- 支持一个或多个 skin。
- 读取 `JOINTS_0`、`WEIGHTS_0` 和 inverse bind matrices。
- 每个顶点最多 4 个权重。
- 支持骨骼 translation、rotation、scale 动画。
- 不支持 dual-quaternion skinning。

## 5. GLB 支持档案

| 能力 | v1 |
|---|---|
| GLB container version 2 | 支持 |
| 外部 `.gltf` + `.bin` | 不支持 |
| Primitive mode TRIANGLES | 支持 |
| `POSITION` | 必需 |
| `NORMAL` | 必需 |
| `TEXCOORD_0` | 必需 |
| indices U16/U32 | 支持 |
| node TRS | 支持 |
| node matrix | 支持，加载时分解/归一化 |
| skins | skinned profile 支持 |
| `JOINTS_0/WEIGHTS_0` | skinned profile 支持 |
| LINEAR interpolation | 支持 |
| STEP interpolation | 支持 |
| CUBICSPLINE | v1 不支持 |
| morph targets | 不支持 |
| sparse accessor | 不支持 |
| Draco/Meshopt | 不支持 |
| embedded images | 不作为 v1 材质来源 |
| vertex colors | 不支持 |
| multiple UV sets | 不支持 |
| cameras/lights | 忽略并警告 |

加载器必须检查 GLB magic/version/declared length/chunk bounds、bufferView/accessor 边界、stride、component type、索引范围、NaN/Infinity、节点循环、层级深度和动画时间单调性。

Accessor 严格语义遵循 glTF 2.0：`normalized=true` 不得用于 `FLOAT` 或
`UNSIGNED_INT`；`JOINTS_0` 只允许非 normalized 的 U8/U16，`WEIGHTS_0`
只允许非 normalized FLOAT 或 normalized U8/U16。任何已声明的 `min`/`max`
必须是与 accessor component 数量一致的有限数值数组，按 component type
解释、逐分量满足 `min <= max`，并与 BIN 中实际解码的 extrema 一致。
`POSITION` 与 animation sampler `input` 必须同时声明 `min` 和 `max`；v1
不额外要求其他 accessor 声明 bounds，但一旦声明仍执行上述严格校验。
严格加载在解析任何 mesh/skin/animation 引用前按索引预检全部 accessor，
包括未引用声明；布局与 metadata 只缓存一次，并在扫描 BIN extrema 前对
所有声明 bounds 的 component 总数执行统一硬预算，因此重叠 accessor 不能
造成无界重复扫描。

## 6. 基础材质

v1 材质是“渲染意图”，不是完整 PBR：

```json
{
  "base_color": "example:textures/entity/dragon.png",
  "mode": "opaque",
  "emissive": false,
  "double_sided": false,
  "cutout_threshold": 0.5
}
```

`mode` 可选：

- `opaque`
- `cutout`
- `translucent`
- `additive`

规则：

- `cutout_threshold` 只允许用于 `cutout`。
- `emissive` 表示使用对应 adapter 的发光/fullbright 路径，不表示物理发光。
- 外置 PNG 由 Minecraft TextureManager 管理。
- normal/ORM/height 等贴图留给 material extension。
- 默认 backend 应使用标准 RenderType/RenderPipeline，优先兼容 Iris/Sodium。

扩展接口以后可以提供：

- `MaterialExtensionDecoder`
- `MaterialResolver`
- `RenderLayerProvider`

业务材质扩展不得改变 core 的网格或动画语义。

## 7. 动画状态机

### 7.1 v1 语义

每个实例在 v1 只有一个全身 `main` controller：

- 初始状态。
- 显式触发状态。
- loop/non-loop。
- speed。
- 非循环完成后的 `next`。
- 进入状态时的 cross-fade。
- 时间点视觉事件。

速度组合语义固定如下：网络触发的 `speed` 是 controller timeline 的倍速，descriptor state 的
`speed` 是该 state 内 local clip time 的倍速。对当前 state，局部时间增量唯一按
`real_seconds * network_speed * descriptor_state_speed` 计算。客户端 correction 和 tracking replay
必须从 payload 指定的起始 state 沿同一 controller timeline 解析 `non-loop + next`，与连续 tick
推进到达相同 state/local time；该解析不得重放已经过去的 presentation events。

v1 不支持：

- 条件表达式 DSL。
- 多 controller 骨骼混合。
- additive pose layer。
- bone mask。
- root motion 驱动服务端位移。

业务模组负责判断“何时攻击/行走/受击”，BlendLib 负责可靠播放和插值。

### 7.2 混合

- translation/scale 使用线性插值。
- rotation 使用 normalized quaternion slerp。
- blend 曲线使用 smoothstep。
- 非法或负 `blend_seconds` 是加载错误。
- 状态被服务端 correction 覆盖时，使用目标状态配置的 blend；严重时间漂移可以 snap。

### 7.3 动画事件

动画事件只用于客户端声音、粒子、拖尾和视觉挂点回调。

禁止用动画事件决定：

- 伤害。
- 碰撞。
- 物品消耗。
- 掉落或其他权威玩法结果。

这些结果必须由服务端业务逻辑决定。

## 8. Instance key 与生命周期

`BlendInstanceKey` 是带类型的 sealed key：

- Entity：连接 session + entity id。
- Block entity：dimension + block pos。
- Item：v1 仅 stateless/loop；持久 ItemStack 动画延后。
- Ephemeral：调用方提供 session-local id。

清理时机：

- entity removed/untracked。
- world disconnect。
- block entity unload。
- resource generation 被替换且实例重绑定完成。
- bounded idle timeout。

禁止只用裸 entity id 作为跨世界、跨连接永久 key。

## 9. 公共 API

以下代码展示 v1 目标形状，实施时允许因 26.1.2 映射名称做机械调整，但语义不得改变。

### 9.1 模型与动画 key

```java
public final class ExampleBlendModels {
    public static final BlendModelKey DRAGON =
            BlendModelKey.parse("example:dragon");

    public static final BlendAnimationKey IDLE =
            BlendAnimationKey.parse("example:idle");

    public static final BlendAnimationKey ATTACK =
            BlendAnimationKey.parse("example:attack");
}
```

这些 key 使用 BlendLib 自己的 `BlendResourceId`，不依赖 Minecraft 类型。Fabric adapter 可以额外提供 `Identifier` 转换工具。创建 key 不进行 I/O；资产在客户端资源重载阶段发现。

### 9.2 实体绑定

```java
EntityRenderers.register(ModEntities.DRAGON, context ->
        BlendEntityRenderer.builder(context, ExampleBlendModels.DRAGON)
                .shadowRadius(1.2F)
                .rootTransform(DragonTransforms::root)
                .build()
);
```

如业务模组已有自定义 renderer，可使用低级接口：

```java
BlendLibClient.renderer().submit(
        renderState.blendSnapshot(),
        poseStack,
        collector
);
```

### 9.3 服务端触发

```java
BlendAnimations.entity(dragon)
        .trigger(ExampleBlendModels.ATTACK);
```

持久状态：

```java
BlendAnimations.entity(dragon)
        .setPersistent(ExampleBlendModels.IDLE);
```

服务器 API 只接受 animation key、速度/seed 等语义参数，不接受模型对象。

### 9.4 方块实体

```java
BlockEntityRenderers.register(
        ModBlockEntities.ALTAR,
        context -> BlendBlockEntityRenderer.builder(
                context,
                BlendModelKey.parse("example:altar")
        ).build()
);
```

### 9.5 物品

物品通过 `blendlib:model` special model 类型引用模型：

```json
{
  "type": "blendlib:model",
  "model": "example:dragon_staff"
}
```

v1 物品支持静态姿态和基于稳定时钟的 loop 动画。需要服务端同步的一次性 ItemStack 动画留到后续版本，因为它需要稳定 stack identity/data component 设计。

## 10. 网络协议

### 10.1 实体动画命令

逻辑字段：

```text
entity_id
animation_key
start_game_tick
sequence
speed
seed
persistent
```

### 10.2 方块实体动画命令

逻辑字段：

```text
block_pos
animation_key
start_game_tick
sequence
speed
seed
persistent
```

当前连接已确定 dimension；跨 dimension 状态不能复用。客户端收到未知目标时可以在短 TTL 队列等待实体/方块实体出现，超时后丢弃并输出 debug 诊断。

### 10.3 一致性

- `sequence` 对每个实例单调递增。
- 小于等于已应用 sequence 的命令丢弃。
- tracking start 发送当前持久状态。
- transient 动画不要求离线补发。
- 模型资源 key 不通过网络传输，由客户端渲染绑定决定。
- 客户端缺少模型时使用 missing model，不影响服务端。

## 11. Resource reload 与缓存

### 11.1 Registry

模型 registry 以整代不可变 map 表示：

```text
ModelRegistryGeneration {
    generationId
    Map<BlendModelKey, ModelHandle>
    Diagnostics
}
```

apply 阶段完成后一次性替换当前 generation，不允许逐模型暴露半加载状态。

### 11.2 缓存

- Descriptor/GLB parse cache 只在当前 resource generation 内有效。
- pose cache 必须有最大容量和 LRU/时间淘汰。
- static backend handle 按 `ModelAsset` 共享。
- world disconnect 清理 instance/controller cache。
- reload 清理旧 generation 的 pose/backend cache。

### 11.3 热路径约束

以下方法路径不得执行资源 I/O 或 JSON/GLB 解析：

- entity/block entity `submit`。
- item special renderer `submit`。
- animation `advance/sample`。
- socket query。

## 12. 安全上限

默认硬限制：

| 项目 | 硬上限 |
|---|---:|
| 单 GLB 文件 | 64 MiB |
| 单模型顶点 | 1,000,000 |
| 单模型索引 | 3,000,000 |
| nodes | 4,096 |
| rigid animated nodes | 4,096 |
| skin joints | 512 |
| hierarchy depth | 256 |
| clips | 256 |
| 总关键帧采样 | 1,000,000 |
| 单 clip 时长 | 600 秒 |
| material slots | 256 |
| sockets | 512 |

建议警告阈值低于硬上限，例如超过 100,000 顶点或 128 skin joints 时输出性能警告。

禁止：

- `file:` URI。
- 绝对路径。
- `..` 路径穿越。
- 网络 URI。
- descriptor 引用资源目录外任意文件。

## 13. 诊断

每条诊断包含：

```text
severity
code
model_key
resource_id
json_pointer_or_gltf_index
message
cause_summary
```

稳定错误码示例：

- `BLENDLIB-DESC-001`：schema version 不支持。
- `BLENDLIB-GLB-001`：header/declared length 无效。
- `BLENDLIB-GLB-014`：accessor 越界。
- `BLENDLIB-SCENE-004`：节点循环。
- `BLENDLIB-ANIM-006`：动画时间非单调。
- `BLENDLIB-MAT-003`：material slot 未映射。
- `BLENDLIB-LIMIT-001`：资产超过硬上限。
- `BLENDLIB-EXT-001`：required extension 不支持。

生产日志每次 reload 输出摘要；完整逐项诊断写入开发日志。开发环境提供客户端命令：

```text
/blendlib assets
/blendlib inspect <model-id>
/blendlib diagnostics [model-id]
```

## 14. 缺失模型

资源失败时返回稳定的 missing model：

- 明显洋红/黑材质。
- 固定小型几何，不依赖失败资产。
- bounds 有限。
- 有 animation 的正常资产，其 bounds 必须保守覆盖 rest pose、全部 decoded clip、任意
  LINEAR/STEP 时刻和任意 state cross-fade；不得只使用关键帧采样或 rest-pose AABB。
- 日志只对同一 generation/资产报告一次主错误，避免每帧刷屏。

缺失模型不能吞掉 schema/安全错误；诊断仍必须可查询。

## 15. 性能与资源验收

v1 的不可协商约束：

- `submit` 中零文件读取、零模型解析。
- animated bounds 只能在 load/reload preparation 中计算；entity frustum AABB 与
  entity/block-entity snapshot culling metadata 必须直接消费当前 generation handle 的预计算包络。
- 静态/刚体路径不得产生与顶点数成比例的每实例每帧堆分配。
- 所有 cache 有容量上限或 generation 生命周期。
- 20 次资源重载后，旧 backend handle 数量不得持续增长。
- world disconnect 后 instance 数量回到零。
- 乱序网络包不会导致状态回退。
- 参考场景在本机无 shaderpack 条件下达到稳定 60 FPS：
  - 100 个可见 10k 三角形刚体实例。
  - 25 个可见 20k 三角形、64 骨骼 skinned 实例。
- 性能测试记录 p50/p95 frame time、animation preparation、submit CPU 和 live allocation。

如果 CPU skinning 未达到 skinned 场景目标，`1.0.0` 前必须加入 GPU skinning 或更严格 LOD，而不能删除性能验收。

## 16. 兼容性

- 默认使用标准 Minecraft/Blaze3D 抽象，不调用原始 OpenGL。
- 无 shaderpack 时必须有完整可见 fallback。
- Iris/Sodium 作为首发兼容目标进行 smoke test。
- PBR/自定义 wireframe/ghost 等效果通过扩展层实现，不进入基础材质语义。
- dedicated server 启动测试必须验证 common entrypoint 不触发客户端类加载。
- 26.2 adapter 必须复用全部 core tests 和资源 fixtures。

## 17. v1 非目标

- 多 controller 和骨骼 mask。
- root motion 权威移动。
- ItemStack 持久动画同步。
- morph target/shape key。
- CUBICSPLINE。
- Draco/Meshopt。
- 内嵌 GLB 贴图。
- 动态材质节点图。
- 自动服务端 hitbox。
- 远程下载模型。
- 在运行时执行 Blender Python。

## 18. 规范参考

- [Khronos glTF 2.0 Specification](https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html)
- [Blender glTF 2.0 Import/Export Manual](https://docs.blender.org/manual/en/3.3/addons/import_export/scene_gltf2.html)
- [Fabric Basic Rendering Concepts](https://docs.fabricmc.net/develop/rendering/basic-concepts)
- [Fabric Entity Rendering Guide](https://docs.fabricmc.net/develop/entities/first-entity)
- [Fabric Block Entity Renderers](https://docs.fabricmc.net/develop/blocks/block-entity-renderer)
