# BlendLib 带字标正式采用记录

日期：2026-09-13。项目所有者明确要求将本次参数化重构结果作为正式图标提交。
采用组合为**折面 B 作为首字母，后接 Minecraft.otf 排版的 `lendLib`**，整体读作 `BlendLib`。

## 正式来源与同步

| 资源 | 正式路径 | 关系 |
| --- | --- | --- |
| SVG 母版 | [blendlib-wordmark-master.svg](./parametric-wordmark/generated/blendlib-wordmark-master.svg) | 透明背景；3 个折面图形路径、7 个真实字体轮廓路径 |
| 透明 PNG | [blendlib-wordmark-transparent.png](./blendlib-wordmark-transparent.png) | 3066 × 1022；母版 PNG 原样副本 |
| 白底 PNG | [blendlib-wordmark-white.png](./blendlib-wordmark-white.png) | 3066 × 1022；透明 PNG 合成到纯白底 |
| 文档兼容副本 | [blendlib-logo.png](../blendlib-logo.png) | 与正式白底 PNG 逐字节相同 |
| 分享预览 | [blendlib-social-preview.png](./blendlib-social-preview.png) | 1774 × 887；同一 SVG 适配到 2:1 白底画布 |

根 README 保持既有资源路径，因此直接引用更新后的正式白底图。独立 B 的两份正式 PNG
及 Fabric 模组内的方形图标哈希保持不变。两套母版分别保留各自参考原图的灰阶；
带字标使用 `#292D31` 与 `#87898D`。

[参数与字体说明](./parametric-wordmark/README.md)、[构造图](./parametric-wordmark/generated/construction.svg)
和 [尺寸比例表](./parametric-wordmark/generated/dimensions.md) 随母版提交。
字体原文件与 SIL OFL 1.1 许可保存在资产包内；SVG 本身不依赖安装字体。
重构参考图按采用前的版本 `1131673` 归档，后续复现使用该快照。

分享预览以 14 px/u 渲染完整母版，母版画布在其中占 1533 × 511 px。
为使文字边界和基线落在整数像素上，相对精确居中向右、向下各修正 **0.5 px**，
最终母版画布原点为 `(121, 188.5) px`。该适配不改变 SVG 母版或字形比例。
正式图中不包含规范图的网格、尺寸线或注释。

## 验证结果

- 使用 Python 3.11.15 和固定依赖，在新的输出目录重建全部 14 份母版输出，逐字节相同。
- 带字正式导出与独立 B 正式导出的 `--check` 均通过；两个正式清单及共享 SHA-256 全部匹配。
- 母版只含 10 个填充路径，没有活文字、位图、字体引用、描边或辅助线。
- 原尺寸透明 PNG 为 RGBA；背景及 B 的两个三角孔保持透明；白底图确由透明图合成。
- 原字体通过 FreeType 独立渲染，与原尺寸 SVG 字体 Alpha 比较为 **0 个差异像素**。
- 另将原字体按分享预览尺寸直接排版，与正式分享图的文字比较为 **0 个差异像素**。
  原尺寸与分享预览的文字 Alpha 都只含 0、255，像素边缘清晰。
- 已查看正式白底图与分享预览，确认完整构图、平面配色、留白与像素字形。
- 原字体仍与用户提供文件完全一致，原图快照仍与 `1131673` 中的原图完全一致。

机器可读证据：[采用检查](./wordmark-adoption-checks.json) ·
[带字正式清单](./official-wordmark-manifest.json) · [四份正式 PNG 校验和](./SHA256SUMS)。
母版参数变化、异地复现和无效输入检查另见 [母版验证记录](./parametric-wordmark/VERIFICATION.md)。

## 后续生成

按 [固定依赖](./parametric-wordmark/requirements.txt) 配置环境，在仓库根目录运行：

```powershell
python docs/assets/branding/parametric-wordmark/generate.py
python docs/assets/branding/parametric-wordmark/export_official.py
python docs/assets/branding/parametric/export_official.py
python docs/assets/branding/parametric-wordmark/export_official.py --check
python docs/assets/branding/parametric/export_official.py --check
```

最后一个生成命令通过既有独立 B 流程刷新四份 PNG 的共享校验和及其清单。
本次仅涉及本地品牌资源、规范和生成流程，没有改变游戏代码或游戏资源，因此未运行
Gradle 构建或 Minecraft 客户端。Git 提交不代表远端推送、平台素材上传或新模组版本发布。
