# BlendLib 1.0.0-beta.2 — Fabric version ports

Beta.2 provides a separate complete runtime for each Minecraft Java release below.
Choose the exact Minecraft version. Install one BlendLib JAR plus the matching Fabric API;
do not combine different version JARs. Fabric Loader 0.19.3 or newer is required.

| Minecraft | Java | Tested Fabric API | Build family |
| --- | --- | --- | --- |
| 1.21.1 | 21 | 0.116.17+1.21.1 | legacy |
| 1.21.2 | 21 | 0.106.1+1.21.2 | legacy |
| 1.21.3 | 21 | 0.114.1+1.21.3 | legacy |
| 1.21.4 | 21 | 0.119.4+1.21.4 | legacy |
| 1.21.5 | 21 | 0.128.2+1.21.5 | legacy |
| 1.21.6 | 21 | 0.128.2+1.21.6 | legacy |
| 1.21.7 | 21 | 0.129.0+1.21.7 | legacy |
| 1.21.8 | 21 | 0.136.1+1.21.8 | legacy |
| 1.21.9 | 21 | 0.134.1+1.21.9 | modern |
| 1.21.10 | 21 | 0.138.4+1.21.10 | modern |
| 1.21.11 | 21 | 0.141.6+1.21.11 | modern |
| 26.1 | 25 | 0.145.1+26.1 | modern |
| 26.1.1 | 25 | 0.145.4+26.1.1 | modern |
| 26.1.2 | 25 | 0.154.2+26.1.2 | modern |
| 26.2 | 25 | 0.153.0+26.2 | modern |

The API/core source is reused without changes. Each runtime includes model loading, strict GLB 2.0
validation, animation, procedural poses, host bindings, network synchronization and the CPU rendering
implementation. Consumers must compile against the JAR for their target Minecraft version:
native rendering callback signatures change between Minecraft releases.

## Platform adaptations

- 1.21.1–1.21.8 use native immediate vertex submission, matching entity/block-entity callbacks and
  item rendering hooks. 1.21.4 uses native baked item models and preserves display transforms.
  Twenty unused 26.x experimental GPU prototype classes and their shader resources are isolated;
  common ownership semantics and the released CPU renderer remain present.
- 1.21.9–1.21.11 retain the collector-based CPU renderer. Material tests verify actual single-sided
  and double-sided cutout pipeline selection. 1.21.9 lacks public world-render pass-owner events;
  1.21.9/1.21.10 lack independently owned samplers, so the unreleased direct-GPU prototype explicitly
  refuses native work. It never changes shared vanilla texture sampler state.
- 26.1–26.1.2 reuse the original unobfuscated rendering interfaces. 26.2 adapts local vertex formats,
  bind-group layouts, draw argument ordering, final present and explicit command submission.
- The 1.21.9–1.21.11 final-present adapter uses exact production/development class hashes and the
  actual `runTick` → `Window.updateDisplay` call. The 26.2 adapter uses its verified `GpuSurface.present` call.

## Verification and limits

Per-artifact build, test, server, client and publication results are recorded in
[the verification matrix](multiversion-progress.md), with exact SHA-256 values.
Server checks use fresh isolated loopback profiles and require readiness, a clean stop and exit 0.
Client checks install the release JAR through production Fabric, require resource reload with zero
BlendLib diagnostics, and check normal window close. They never join a world.

No new in-game visual acceptance, multiplayer gameplay acceptance, Iris/Sodium compatibility,
GPU rendering or performance acceptance is implied. This remains a Beta without a stable API/ABI guarantee.
Minecraft being a release version does not make BlendLib stable. Existing strict format and
server-authority boundaries from [Beta.1](beta-release-notes.md) continue to apply.

The Blender exporter is unchanged by this release. Non-Add-on code remains Apache-2.0;
`blender-addon/` remains GPL-3.0-or-later.

Source and issues: [GitHub](https://github.com/LIy-hub/BlendLib-Public).
Downloads: [CurseForge](https://www.curseforge.com/minecraft/mc-mods/blendlib).
