# BlendLib

BlendLib is a Fabric library for strict GLB 2.0 models and animation on Minecraft.
It is intended for mod developers and players whose mods require BlendLib.

**Beta.1 is an early test release. APIs and behavior may change.**

## Requirements

- Minecraft 26.1.2
- Fabric Loader 0.19.3
- Fabric API 0.154.2+26.1.2
- Java 25

Install the BlendLib runtime JAR alongside Fabric API and the mod that depends on it.
The runtime embeds BlendLib API, core and common modules. Developer sources, Javadoc and the
separate Blender exporter are available through the GitHub release.

## What it provides

Strict rigid/skinned GLB loading, animation, version-specific Fabric client adapters, resource reload
and diagnostics. Beta.1 integrates the X1–X9 expansion code, including animation v2, procedural
pose/socket/event support, authoring tools and experimental material/rendering/profile APIs.

Runtime assets must be `.glb`. BlendLib does not load `.blend`, FBX, OBJ or external `.gltf` + `.bin`.
Visual models and animation events never decide server-authoritative collision, damage, hits or drops.

## Current limits

Only Minecraft 26.1.2 Fabric is supported by this JAR. Separate 26.2 and NeoForge candidates are
not part of this release. There is no stable API/ABI or advanced-rendering performance promise.
Ordinary Showcase entity visuals have bounded user acceptance. Full material/item/block-entity
visuals, two-client synchronization, 20-reload leak testing, Iris/Sodium and hardware performance
remain unaccepted. Test in a separate instance/world before modpack adoption.

## 中文简介

BlendLib 是 Minecraft Fabric 的严格 GLB 2.0 模型与动画前置库。本次 Beta.1 纳入完整
X1–X9 合并代码，但不是稳定版；未完成的完整画面、同步、重载、光影兼容与性能验收仍保留。
安装运行库 JAR、Fabric API 及使用本库的模组即可，版本要求见上方。

Source and issues: [GitHub](https://github.com/LIy-hub/BlendLib-Public).
Non-add-on code is Apache-2.0. The separately distributed Blender exporter is GPL-3.0-or-later.
