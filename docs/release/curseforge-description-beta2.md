# BlendLib

BlendLib is a Fabric library for strict GLB 2.0 models and animation on Minecraft. It is intended for mod developers and players whose mods require BlendLib.

**Beta.2 provides 15 exact-version Fabric runtimes. APIs and behavior may change.**

## Requirements

* Minecraft 1.21.1–1.21.11: Java 21
* Minecraft 26.1, 26.1.1, 26.1.2 and 26.2: Java 25
* Fabric Loader 0.19.3 or newer
* Fabric API matching your exact Minecraft version

Install one BlendLib runtime JAR for your exact Minecraft version, alongside Fabric API and the mod that depends on it. Do not combine different BlendLib version JARs. Each runtime embeds the API, core, common and client implementation. [The Beta.2 release notes](https://github.com/LIy-hub/BlendLib-Public/blob/main/docs/release/beta2-release-notes.md) list the tested Fabric API version for every target. Runtime and source JARs with checksums are available in the [GitHub release](https://github.com/LIy-hub/BlendLib-Public/releases/tag/v1.0.0-beta.2). CurseForge files become available as their individual reviews complete.

## What it provides

Strict rigid/skinned GLB loading, animation, procedural poses, sockets and events, host bindings, network synchronization, CPU rendering, resource reload and diagnostics. Beta.2 ports the Beta.1 feature baseline through native Minecraft/Fabric interfaces. Native rendering callback signatures differ between game versions; developers must compile against the target-version JAR.

Runtime assets must be `.glb`. BlendLib does not load `.blend`, FBX, OBJ or external `.gltf` + `.bin`. Visual models and animation events never decide server-authoritative collision, damage, hits or drops.

## Verification and current limits

All 15 final runtime JARs passed complete builds and JAR checks, isolated dedicated-server readiness and clean stop, and production Fabric client startup/resource reload followed by normal close. The public GitHub version matrix also passed all 15 targets. These clients did not enter a world.

This release remains a Beta without a stable API/ABI guarantee. No new in-game visual, multiplayer gameplay, Iris/Sodium, experimental GPU or performance acceptance is claimed. Older Minecraft adapters explicitly isolate unsupported, unreleased GPU prototypes; the API/core and released CPU rendering functionality remain present. NeoForge is outside this release. [The verification record](https://github.com/LIy-hub/BlendLib-Public/blob/main/docs/release/multiversion-progress.md) separates these results and boundaries. Test in a separate instance/world before modpack adoption.

## 中文简介

BlendLib 是 Minecraft Fabric 的严格 GLB 2.0 模型与动画前置库。Beta.2 为 1.21.1–1.21.11、26.1、26.1.1、26.1.2 和 26.2 提供 15 份独立运行库。1.21.x 使用 Java 21，26.x 使用 Java 25；请选择精确游戏版本的一个 JAR，配套安装 Fabric API。全部版本已通过构建、隔离服务端和客户端启动/资源重载检查，但仍是 Beta，未新增游戏内画面、多人玩法、实验 GPU 或性能验收。

Source and issues: [GitHub](https://github.com/LIy-hub/BlendLib-Public). Non-add-on code is Apache-2.0. The separately distributed Blender exporter is unchanged and remains GPL-3.0-or-later; its package is available in the [earlier GitHub releases](https://github.com/LIy-hub/BlendLib-Public/releases).
