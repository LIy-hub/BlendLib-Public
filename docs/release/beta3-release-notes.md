# BlendLib 1.0.0-beta.3 — Fabric dependency compatibility

Beta.3 fixes dependency declarations that required one exact Fabric Loader and Fabric API version.
Each JAR now accepts Fabric Loader **0.19.5 or newer** and the tested Fabric API version below or
newer for the **same Minecraft version**. Minecraft itself remains an exact-version dependency.

The builds use the latest stable Loader and latest Fabric API for each existing target, checked
against [Fabric Meta](https://meta.fabricmc.net/v2/versions/loader) and
[Fabric Maven](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml)
on 2026-09-11. Older Minecraft targets already had their latest Fabric API; this release fixes
their Loader compatibility and removes the exact Fabric dependency restriction.

| Minecraft | Java | Fabric API used for build and startup checks |
| --- | --- | --- |
| 1.21.1 | 21 | 0.116.17+1.21.1 |
| 1.21.2 | 21 | 0.106.1+1.21.2 |
| 1.21.3 | 21 | 0.114.1+1.21.3 |
| 1.21.4 | 21 | 0.119.4+1.21.4 |
| 1.21.5 | 21 | 0.128.2+1.21.5 |
| 1.21.6 | 21 | 0.128.2+1.21.6 |
| 1.21.7 | 21 | 0.129.0+1.21.7 |
| 1.21.8 | 21 | 0.136.1+1.21.8 |
| 1.21.9 | 21 | 0.134.1+1.21.9 |
| 1.21.10 | 21 | 0.138.4+1.21.10 |
| 1.21.11 | 21 | 0.141.6+1.21.11 |
| 26.1 | 25 | 0.145.1+26.1 |
| 26.1.1 | 25 | 0.145.4+26.1.1 |
| 26.1.2 | 25 | 0.155.3+26.1.2 |
| 26.2 | 25 | 0.160.0+26.2 |

Replace the previous BlendLib runtime with the Beta.3 JAR for your exact game version. Install
one BlendLib runtime, Fabric Loader 0.19.5+, and the corresponding Fabric API from the table or
a newer version for that game. Source JARs are for development and do not belong in `mods`.

Per-file checks and SHA-256 hashes are recorded in [the Beta.3 verification record](beta3-verification.md).
Startup checks use isolated profiles and the packaged runtime. Clients only load their initial
resources; the test process is then terminated. This checks dependency resolution, initialization
and resource reload, not normal shutdown or in-game visuals. The model/animation APIs, renderer
implementation and Blender exporter are unchanged; [Beta.2 platform limits](beta2-release-notes.md)
still apply. This remains a Beta without a stable API/ABI guarantee.

## 中文说明

Beta.3 修复了依赖声明中的精确版本限制：此前即使安装同一 Minecraft 版本的较新 Fabric，
也可能因为 Loader 或 Fabric API 版本不完全相等而无法启动。

全部 15 个版本现在使用 **Fabric Loader 0.19.5** 构建，并允许 **0.19.5 或更新版本**。
Fabric API 要求为上表中的版本或同一游戏版本的更新版本；Minecraft 仍须精确匹配。
26.1.2 的 API 更新至 **0.155.3+26.1.2**，26.2 更新至 **0.160.0+26.2**。
其余目标原先已经使用对应游戏版本的最新 API，此次更新其 Loader 与依赖范围。

安装时替换旧运行库，只保留一个对应游戏版本的 Beta.3 JAR。源码包用于开发，请勿放入 `mods`。
验证记录区分构建、专用服务器启动、客户端初始化与资源重载；未进行新的游戏内画面、多人玩法、
Iris/Sodium 兼容性或性能验收。模型、动画 API、渲染实现及 Blender 导出插件保持原有版本的行为。
