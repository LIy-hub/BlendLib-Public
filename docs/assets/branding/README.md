# BlendLib 项目 Logo

2026-09-05 经项目所有者确认，采用「空间折面 B」作为项目标志。文字组合标以该符号作为
BlendLib 的首字母 B，后接 MC 风格的像素字 `lendLib`，保持石墨灰与浅灰配色。

本目录是正式品牌资源入口。2026-09-13 经项目所有者确认，独立折面 B 与带字组合标
分别采用参数化 SVG 母版，白底与透明 PNG 从对应母版导出。带字组合为折面 B + `lendLib`，
使用提供的 Minecraft.otf 真实排版并转为轮廓，整体名称保持 `BlendLib`。

## 正式资源

| 用途 | 透明背景 | 白色背景 | 尺寸 |
| --- | --- | --- | --- |
| 独立 B 矢量母版 | [透明 SVG](./parametric/generated/blendlib-icon-master.svg) | [白底 SVG](./parametric/generated/blendlib-icon-white.svg) | 36u × 36u，可无损缩放 |
| 独立 B 图标、头像 | [透明 PNG](./blendlib-icon-transparent.png) | [白底 PNG](./blendlib-icon-white.png) | 1254 × 1254 |
| 带字矢量母版 | [透明 SVG](./parametric-wordmark/generated/blendlib-wordmark-master.svg) | — | 109.5u × 36.5u，可无损缩放 |
| 文字组合标、文档页头 | [透明 PNG](./blendlib-wordmark-transparent.png) | [白底 PNG](./blendlib-wordmark-white.png) | 3066 × 1022 |

<img src="./blendlib-icon-white.png" alt="BlendLib 正式参数化 B 图标" width="256">

<img src="./blendlib-wordmark-white.png" alt="BlendLib 文字组合标" width="640">

## 发布页面与分享预览

- 独立 B 头像使用完整的 `blendlib-icon-white.png`；GitHub README 与 CurseForge
  介绍页的文字组合标使用 `blendlib-wordmark-white.png`。
- 本地资源更新不会自动替换公开平台已上传的图标，平台更新需另行发布。
- [GitHub 分享预览](./blendlib-social-preview.png) 为 1774 × 887 的 2:1 白底适配图，
  供仓库 Social preview 使用，避免 3:1 文字标在分享卡片中被裁切。
  由 [正式带字导出脚本](./parametric-wordmark/export_official.py) 从 SVG 渲染，保留完整构图。
  14 px/u 导出时为对齐像素字，整体相对精确居中向右、向下各修正 0.5 px。
- [页面更新与素材生成记录](./publication-profile-2026-09-08.md)

## 使用与同步

- 保持长宽比、完整轮廓和现有留白；不要拉伸、重新上色或添加发光、阴影、网格等装饰。
- 透明版本包含真实 Alpha 通道，适合放在浅色背景上；深色背景优先使用白底版本。
- 模组图标 `blendlib-fabric-client/src/main/resources/assets/blendlib/icon.png` 使用本目录
  `blendlib-icon-white.png` 的原样副本；`fabric.mod.json` 保持既有图标路径。
- `docs/assets/blendlib-logo.png` 使用 `blendlib-wordmark-white.png` 的原样副本，供根 README
  和既有文档路径引用。后续更新时同步这些副本，并核对内容哈希。
- 独立图标的白底与透明 PNG 由同一 SVG 渲染，白底 PNG 为透明 PNG 合成到纯白底，
  几何和抗锯齿边缘共用来源。
- 带字标的透明 PNG 是母版原尺寸渲染的原样副本；白底 PNG 由它合成到纯白底。
  白底、透明和分享预览具有同一几何来源。
- 旧 Logo 保留于 Git 历史；本地 `output/imagegen/` 是设计过程资料，不作为正式资源入口。

## 来源与验收

独立图标的正式规范以 [参数、构造与生成说明](./parametric/README.md) 为准；
[几何构造图](./parametric/generated/construction.svg) 的网格与尺寸线不属于图标。
[正式导出脚本](./parametric/export_official.py) 同步两份 1254 × 1254 PNG、模组资源与校验和，
可用 `python docs/assets/branding/parametric/export_official.py --check` 在仓库根目录核对。

带字标的 [参数与字体规范](./parametric-wordmark/README.md)、[独立几何构造图](./parametric-wordmark/generated/construction.svg)
和 [尺寸比例表](./parametric-wordmark/generated/dimensions.md) 定义单位 u、折面、字距、基线与修正量。
母版仅含填充路径；字体原文件及其 SIL OFL 1.1 许可随生成包保留，SVG 使用时无需安装字体。

安装 [固定依赖](./parametric-wordmark/requirements.txt) 后，在仓库根目录运行：

```powershell
python docs/assets/branding/parametric-wordmark/generate.py
python docs/assets/branding/parametric-wordmark/export_official.py
python docs/assets/branding/parametric/export_official.py
python docs/assets/branding/parametric-wordmark/export_official.py --check
python docs/assets/branding/parametric/export_official.py --check
```

独立 B 导出流程负责刷新四份正式 PNG 的共享校验和；两个导出脚本的 `--check` 都只读。

### 早期 PNG 来源（历史记录）

以下分享预览来源说明仅对应早期 PNG，当前版本已由 SVG 导出流程接替：

  它由内置 image_gen 以正式文字标为参考生成，是发布页面衍生素材，
  后续版本以本页正式导出清单为准。

由内置 image_gen 根据项目所有者选定的折面 B 方案制作；[生成提示词](./generation-prompts.md)
保留过程说明。字样为生成的 MC 风格像素字，不包含提取的 Minecraft 字体文件。
本次未改变仓库既有许可，也不授予额外的 Minecraft 或其他第三方品牌权利。

项目所有者已确认本会话展示的图形和文字方案。透明通道、PNG 尺寸及运行 JAR 内资源
另行检查；客户端实际图标显示属于独立验收，不以构建结果代替。

[独立 B 正式化记录](./adoption-2026-09-13.md) · [带字标正式化记录](./wordmark-adoption-2026-09-13.md) ·
[带字导出清单](./official-wordmark-manifest.json) · [独立 B 导出清单](./official-icon-manifest.json) ·
[早期 PNG 集成记录](./verification.md) · [四份正式 PNG 的 SHA-256](./SHA256SUMS)
