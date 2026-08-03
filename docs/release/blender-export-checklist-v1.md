# Blender 导出清单 v1

适用对象：`blender-addon/` 的本地 Blender 5.x exporter。manifest 声明的最低
Blender 版本为 5.1.0；运行时读取的仍只有严格 GLB 和外置 PNG。

## 导出前

- [ ] 将源文件保持在业务美术目录，例如 `art/<namespace>/<model-id>/source.blend`；
  不要把 `.blend` 打入运行 JAR。
- [ ] 使用一个明确 collection；导出根唯一，且 node/bone/action 原始名称不重复。
- [ ] namespace、model id 和逻辑动画 key 仅用 `[a-z0-9._/-]`；路径不能含
  `.`、`..`、空段或绝对路径。
- [ ] 采用 Blender `+Z` 向上、`-Y` 向前、1 Blender Unit = 1 block；不要在
  renderer 中补轴向。导出边界唯一转换为 Minecraft `X=X`、`Y=Z`、`Z=-Y`。
- [ ] 网格已经三角化，包含 normals 和 UV0；没有待运行的物理、相机或灯光。
- [ ] 约束、驱动和程序化修改器已烘焙为受支持节点/骨骼关键帧或最终网格。
- [ ] skinned 资产每顶点最多 4 个有效且归一化的权重；应用或烘焙负/非均匀缩放。
- [ ] 使用 `rigid_v1`（静态/刚体节点动画）或 `skinned_v1`（skin/骨骼动画）；
  不使用 CUBICSPLINE、morph target、Draco、Meshopt、sparse accessor、多 UV、
  vertex color 或运行时内嵌贴图。

## CLI 导出

只把 exporter 参数放在 Blender 的 `--` 后面：

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

可选参数是 `--output-resource-root`（默认 `src/main/resources`）和 `--report`。
导出器只接受 `blendlib:rigid_v1` 或 `blendlib:skinned_v1`，并在写 descriptor
前执行严格 post-export validator。

## 预期输出与复现

```text
<project-root>/src/main/resources/assets/<namespace>/
├─ blend_models/<model-id>.json
├─ models3d/<model-id>.glb
└─ textures/blendlib/<model-id>__<material>.png
```

- [ ] descriptor 的 `format_version` 是 `1`，profile 与资产匹配。
- [ ] descriptor 中每一个 material slot 都指向已有、具体 `.png` 外置资源。
- [ ] GLB 不携带运行贴图；图片由 Minecraft TextureManager 管理。
- [ ] exporter report 记录 GLB/descriptor/texture SHA-256、结构摘要、bounds、
  node count、vertex/index count 和动画名。
- [ ] 对同一 `.blend` 连续导出两次，比较 normalized structure 与 hash/report；
  差异必须先解释，不能静默接受。
- [ ] 把生成资产放入独立资源包/开发模组后，以严格 loader 与客户端 diagnostics
  验证，而不是仅检查文件能被 Blender 再打开。

## 导出失败时

先保留 exporter report 和输入 `.blend`，然后按
[诊断与排错索引](./diagnostic-troubleshooting-v1.md) 检查资源 ID、外置 PNG、profile、
GLB/descriptor 与 hard limits。不要把 FBX、OBJ 或 `.blend` 加入运行时回退路径。

## Add-on 许可证边界

`blender-addon/` 整个目录已获用户授权使用 GPL-3.0-or-later；其 manifest 和
`blender-addon/LICENSE` 是唯一该范围的法律文本。这个授权不扩展到 runtime、
Showcase、test-assets 或任何其他项目目录。详见
[许可证与发布元数据](./local-license-metadata.md)。
