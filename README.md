# BlendLib

BlendLib 是面向 Minecraft Fabric 模组的严格 GLB 2.0 模型与动画运行库。首个公开 Alpha
版本为 `1.0.0-alpha.1+26.1.2`，只支持 Minecraft 26.1.2、Fabric Loader 0.19.3、
Fabric API `0.154.2+26.1.2` 与 Java 25。

## 当前状态

这是早期测试版本，不是稳定版。API 与运行行为可能变化，请勿用于关键生产环境；
仅支持本页声明的 Minecraft、Fabric 与 Java 环境。

已知限制：

- 运行时只接受严格 GLB 2.0，不读取 `.blend`、FBX、OBJ 或外部 `.gltf` + `.bin`。
- 本 JAR 只适配 Minecraft 26.1.2；26.2 必须使用独立 adapter JAR。
- 视觉模型和动画事件不参与服务端权威碰撞、伤害、命中、掉落或其他玩法判定。
- 当前 Alpha 不提供稳定 API/ABI 承诺；不兼容变更会记录在 Changelog 与发布说明中。

## 获取与源码

- 源码与问题跟踪：[GitHub](https://github.com/LIy-hub/BlendLib-Public)
- Minecraft 发布页：[CurseForge](https://www.curseforge.com/minecraft/mc-mods/blendlib)
- 作者：LIy-hub（`2734855720@qq.com`）

完整源码通过 GitHub 公开。非 Add-on 源码使用 [Apache License 2.0](./LICENSE)，并随发布
JAR 携带 `META-INF/LICENSE` 与 `META-INF/NOTICE`；`blender-addon/` 保持独立的
GPL-3.0-or-later 授权，详见 [Add-on LICENSE](./blender-addon/LICENSE)。

## 本地构建

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat clean check
.\gradlew.bat buildRelease
git diff --check
```

`buildRelease` 还需要 `blender_executable` 指向可用的 Blender 5.1+。安装、兼容性与
Alpha 发布包说明见 [release 文档](./docs/release/README.md)。
