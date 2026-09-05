# BlendLib 1.0.0-beta.1+26.1.2

A Beta update for Minecraft 26.1.2 Fabric developers. This release integrates the X1–X9
expansion source into the existing strict GLB 2.0 runtime. It is an early test release with
experimental APIs, not stable 1.0.0; API and behavior may change.

## Requirements and installation

- Minecraft **26.1.2**, Fabric Loader **0.19.3**, Fabric API **0.154.2+26.1.2**, Java **25**.
- Put `blendlib-fabric-1.0.0-beta.1+26.1.2.jar` in `mods/`, alongside Fabric API and the mod
  that uses BlendLib. Remove the previous BlendLib runtime JAR to avoid duplicate mod IDs.
- The runtime embeds BlendLib API, core, and common modules. Sources, Javadoc and inventories
  are developer downloads; do not put them in `mods/`.
- Showcase is an optional, separate diagnostics/consumer artifact. Phase-only scene commands
  and summonable Showcase entities have been retired.
- The Blender exporter is an optional separate GPL add-on for Blender 5.1+.

## Changes from Alpha.1

- Integrates expansion API/SPI, animation v2, procedural pose/socket/event work, host adapters,
  Blender authoring improvements, material/variant and experimental rendering/profile work.
- Restores strict accessor/skin validation, animated bounds, receive-time world guards, and
  merged packaging/consumer contracts. Fixes client Mixin package isolation.
- Includes the official folded-B icon and pixel wordmark.
- Keeps Fabric 26.2, NeoForge, datagen and ecosystem candidate artifacts separate from this
  Minecraft 26.1.2 runtime; this release does not advertise those platforms as supported.

## Known limits and verification scope

Ordinary Showcase entity visuals received bounded user acceptance on the merged source.
That does not establish full item/block-entity visuals, the complete material matrix,
two-client synchronization, 20-reload leak checks, Iris/Sodium compatibility, real hardware
performance/GPU targets, or aggregate P3–P8/X1–X9 completion. Those remain unaccepted.
Experimental APIs and advanced rendering paths have no stable compatibility or performance promise.

Only strict `.glb` runtime assets are supported: no `.blend`, FBX, OBJ, or external `.gltf` + `.bin`.
Visuals never decide server-authoritative collision, damage, hit detection, or drops.
Test in a separate instance/world before adopting this Beta in a modpack.

## 中文说明

本次以 **Beta.1** 公开更新完整 X1–X9 合并源线，不宣称稳定 1.0.0 或全部阶段通过。
普通 Showcase 实体视觉已由用户确认；完整材质、物品/方块实体画面、双客户端同步、
20 次热重载泄漏、Iris/Sodium 和真实硬件性能尚未全部验收。
仅支持上方列出的 Minecraft 26.1.2/Fabric/Java 组合；26.2 与 NeoForge 候选不随本 JAR 发布。

Non-add-on code: **Apache-2.0**. Blender exporter: **GPL-3.0-or-later**, distributed separately.
Source and issues: [GitHub](https://github.com/LIy-hub/BlendLib-Public).
Minecraft downloads: [CurseForge](https://www.curseforge.com/minecraft/mc-mods/blendlib).
