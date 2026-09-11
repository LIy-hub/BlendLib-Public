![BlendLib — models and animation for Minecraft Fabric](https://raw.githubusercontent.com/LIy-hub/BlendLib-Public/main/docs/assets/branding/blendlib-wordmark-white.png)

**Bring your models to life in Minecraft.**

BlendLib is a **GLB 2.0 model and animation library for Minecraft Fabric**. It connects an artist's Blender workflow to a mod's runtime, bringing supported meshes, skeletons and keyframe animation into the content you create.

[Downloads](https://www.curseforge.com/minecraft/mc-mods/blendlib/files/all) · [GitHub](https://github.com/LIy-hub/BlendLib-Public) · [Documentation](https://github.com/LIy-hub/BlendLib-Public/blob/main/docs/release/README.md) · [Report an issue](https://github.com/LIy-hub/BlendLib-Public/issues)

## Models, motion, and the tools between them

- **Load your models.** Work with rigid and skinned models through a validated GLB 2.0 asset pipeline.
- **Give them movement.** Use keyframe animation, procedural poses, sockets and animation events.
- **Connect them to your mod.** Bind models to entity, block-entity and item hosts, with APIs for animation synchronization.
- **Keep creating.** Iterate with the separate Blender exporter, resource reload support and diagnostics.

**From Blender to your mod:** export a supported GLB, descriptor and textures, then connect them to BlendLib's model and animation runtime. See the [export checklist](https://github.com/LIy-hub/BlendLib-Public/blob/main/docs/release/blender-export-checklist-v1.md) for supported profiles and asset requirements.

## Installing BlendLib

**For players:** install BlendLib when another mod requires it. BlendLib is a library; installing it alone does not add creatures, items or gameplay.

**Beta.3 supports 15 Minecraft Java Edition versions:**

- **Java 21:** Minecraft 1.21.1, 1.21.2, 1.21.3, 1.21.4, 1.21.5, 1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10 and 1.21.11.
- **Java 25:** Minecraft 26.1, 26.1.1, 26.1.2 and 26.2.

1. Install **Fabric Loader 0.19.5 or newer** and **Fabric API for your exact Minecraft version**.
2. Download the matching BlendLib **runtime** JAR from the Files tab.
3. Put it in your instance's `mods` folder alongside the mod that requires it.

Install **one** BlendLib runtime JAR. Each `1.0.0-beta.3+<Minecraft version>` file targets one exact game version. The runtime includes BlendLib's API, core, common and client modules. Follow the dependent mod's client/server installation instructions.

[Beta.3 release notes and dependencies](https://github.com/LIy-hub/BlendLib-Public/blob/main/docs/release/beta3-release-notes.md) · [Runtime JARs, sources and checksums on GitHub](https://github.com/LIy-hub/BlendLib-Public/releases/tag/v1.0.0-beta.3)

## For mod developers and artists

Start with the [documentation](https://github.com/LIy-hub/BlendLib-Public/blob/main/docs/release/README.md), [Blender export checklist](https://github.com/LIy-hub/BlendLib-Public/blob/main/docs/release/blender-export-checklist-v1.md) and [troubleshooting guide](https://github.com/LIy-hub/BlendLib-Public/blob/main/docs/release/diagnostic-troubleshooting-v1.md). The [Fabric 26.1.2 tutorial](https://github.com/LIy-hub/BlendLib-Public/blob/main/docs/release/developer-tutorial-26.1.2.md) provides integration examples from the Alpha API baseline; compile against your target-version JAR because native rendering callbacks differ between Minecraft releases.

The separate exporter requires **Blender 5.1+**. It is unchanged in Beta.3 and remains available in the [Beta.1 release](https://github.com/LIy-hub/BlendLib-Public/releases/tag/v1.0.0-beta.1%2B26.1.2).

## Current Beta scope

BlendLib is in active Beta. APIs and behavior may change; there is no stable API/ABI guarantee. Test integrations in a separate instance before modpack adoption.

- Runtime models must use the supported **strict GLB 2.0 subset**. BlendLib does not directly load `.blend`, FBX, OBJ or external `.gltf` + `.bin` files.
- This release is for **Fabric**. NeoForge and cross-version JAR compatibility are outside its scope.
- Visual models and animation events do not decide server-authoritative collision, damage, hits or drops.
- The 15-version release record covers builds, JAR checks, isolated server checks and client startup/resource reload. Those checks did not enter a world. Full visual coverage, multiplayer behavior, repeated-reload leak testing, Iris/Sodium compatibility, experimental GPU rendering and hardware performance remain unverified or incomplete. Older adapters exclude unsupported experimental GPU paths.

[Detailed verification record](https://github.com/LIy-hub/BlendLib-Public/blob/main/docs/release/beta3-verification.md) · [Beta.1 capability boundaries](https://github.com/LIy-hub/BlendLib-Public/blob/main/docs/release/beta-release-notes.md)

## 中文简介

**让模型走进 Minecraft，让创意动起来。**

BlendLib 是 Minecraft Fabric 的 GLB 2.0 模型与动画前置库。它连接 Blender 创作与模组运行时，提供模型加载、骨骼与关键帧动画、程序化姿态、挂点与事件、宿主绑定、动画同步、资源重载和诊断能力。

玩家只需在依赖它的模组要求时安装；开发者可以用它构建自己的模型和动画内容。单独安装本库不会添加生物、物品或玩法。Beta.3 覆盖上方 15 个游戏版本，1.21.x 使用 Java 21，26.x 使用 Java 25，并需要 Fabric Loader 0.19.5+ 与对应版本的 Fabric API。**只安装一份与游戏版本精确匹配的运行库 JAR。**

项目仍处于 Beta，API 与行为可能变化；构建与启动检查不等于完整的游戏内画面、多人、光影兼容或性能验收。模型和动画不承担服务端碰撞、伤害等权威玩法判定。开发与排错请查阅上方文档。

---

Created by [LIy-hub](https://github.com/LIy-hub) / Liy_Hub. Non-add-on code: [Apache-2.0](https://github.com/LIy-hub/BlendLib-Public/blob/main/LICENSE). Separate Blender add-on: [GPL-3.0-or-later](https://github.com/LIy-hub/BlendLib-Public/blob/main/blender-addon/LICENSE).
