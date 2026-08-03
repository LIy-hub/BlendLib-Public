# Fabric 26.1.2 开发者教程

本教程只使用本地候选公共 API。将它放在你的 Fabric 模组的 client source set
或 server/common source set 中时，仍需遵守各自的类加载边界。

## 1. 定义语义 key 和资源布局

模型 key 是无扩展名的语义身份；其 descriptor 位置由
`BlendModelKey.descriptorResourceId()` 固定推导。下例的模型文件必须在：

```text
assets/example/blend_models/actor.json
assets/example/models3d/actor.glb
assets/example/textures/blendlib/actor_body.png
```

```java
import com.liy.blendlib.api.BlendAnimationKey;
import com.liy.blendlib.api.BlendModelKey;

final class ExampleBlendKeys {
    static final BlendModelKey ACTOR = BlendModelKey.parse("example:actor");
    static final BlendAnimationKey IDLE = BlendAnimationKey.parse("example:idle");
    static final BlendAnimationKey ATTACK = BlendAnimationKey.parse("example:attack");

    private ExampleBlendKeys() {}
}
```

构造这些 key 只做格式校验，不读取资源。资源 ID 使用小写、namespace-qualified
`namespace:path`；不要在 `BlendModelKey` 中加入 `.json` 后缀。

## 2. 服务端触发语义动画

common/server 源集只导入 `BlendAnimations` 和纯 API。不要在服务器保留模型 key、
GLB、render state 或 client 类型。

```java
import com.liy.blendlib.fabric.common.animation.BlendAnimations;

void startAttackOnServer(MyEntity entity, long deterministicSeed) {
    BlendAnimations.entity(entity).trigger(ExampleBlendKeys.ATTACK, 1.0F, deterministicSeed);
}

void setIdleLoopOnServer(MyBlockEntity blockEntity) {
    BlendAnimations.blockEntity(blockEntity).setPersistent(ExampleBlendKeys.IDLE);
}
```

`trigger` 是 transient 命令；`setPersistent` 用于 tracking-start 可以重放的当前
状态。传输的是动画 key、start tick、sequence、speed、seed 和 persistent 语义，
不是模型资源或玩法判定。视觉动画不得成为伤害、命中或碰撞的权威来源。

## 3. 客户端注册实体 renderer

以下代码应只在 `ClientModInitializer` 或等价 client-only 初始化路径执行：

```java
import com.liy.blendlib.fabric.client.entity.BlendEntityRenderer;
import com.liy.blendlib.fabric.client.entity.BlendEntityRenderers;

BlendEntityRenderers.register(
        ExampleEntities.ACTOR,
        context -> BlendEntityRenderer.<MyEntity>builder(context, ExampleBlendKeys.ACTOR)
                .synchronizedSkinnedAnimation((entity, request) -> ExampleBlendKeys.IDLE)
                .shadowRadius(0.45F)
                .build());
```

`synchronizedSkinnedAnimation(fallback)` 是标准同步动画消费者接口：它读取 BlendLib
已接受的语义同步状态，并在没有状态时使用 fallback。高层 builder 在 extraction
阶段生成不可变快照；`submit` 不读取文件、不解析 JSON/GLB，也不访问 entity/world。

静态或刚体模型把该配置替换为 `.staticRestPose()`。若需要自定义选择器，使用
`.skinnedAnimation(...)` 或带 `SyncedSkinnedAnimationStateSelector` 的重载；不要
访问内部同步 store 或 packet 类型。

## 4. 客户端注册方块实体 renderer

```java
import com.liy.blendlib.fabric.client.blockentity.BlendBlockEntityRenderer;
import com.liy.blendlib.fabric.client.blockentity.BlendBlockEntityRenderers;

BlendBlockEntityRenderers.register(
        ExampleBlockEntities.ANIMATED_ALTAR,
        context -> BlendBlockEntityRenderer.<MyBlockEntity>builder(context, ExampleBlendKeys.ACTOR)
                .syncedSkinnedAnimation(ExampleBlendKeys.IDLE)
                .build());
```

方块实体实例 identity 始终包含 dimension 与 block position；这避免跨维度复用。
服务端可在方块实体加载后调用 `setPersistent`，新开始跟踪的客户端会获得当前语义
状态。不要直接创建或缓存 `BlendInstanceKey.BlockEntity` 作为跨世界持久对象。

## 5. 注册物品 marker binding

在 client-only 初始化中，以普通 vanilla item JSON 作为 marker，并显式绑定它：

```java
import com.liy.blendlib.fabric.client.item.BlendLibItemBinding;
import com.liy.blendlib.fabric.client.item.BlendLibItemModelBindings;
import net.minecraft.resources.Identifier;

BlendLibItemModelBindings.register(new BlendLibItemBinding(
        ExampleItems.ACTOR_ITEM_ID,
        ExampleBlendKeys.ACTOR,
        Identifier.withDefaultNamespace("item/stick")));
```

无需也不得编写 `blendlib:model` JSON type。26.1.2 adapter 在自己的 client
entrypoint 中安装 public before-bake hook；未注册 item 保持原有 vanilla model。

## 6. 查询诊断与验证

开发环境使用：

```text
/blendlib assets
/blendlib inspect <model-id>
/blendlib diagnostics [model-id]
```

一次资源重载只应产生有界摘要和每 generation/asset 的去重主错误。先阅读
[诊断与排错索引](./diagnostic-troubleshooting-v1.md)，再修复 descriptor/GLB；
不要以反射、原始 OpenGL、改变 server hitbox 或跳过严格加载来规避诊断。

## 7. 消费者边界检查

本地 consumer fixture 证明了 server/common 使用 `BlendAnimationKey`、
`BlendModelKey`、`BlendResourceId` 和 `BlendAnimations` 时无需依赖 core 或 client
实现。正式 Alpha 构建后，空白消费者必须从本地 Maven 只引用已发布的公共坐标再完成
一次编译/运行验证；在该制品证据产生前，不要把项目内 fixture 当作 Maven 发布证据。
