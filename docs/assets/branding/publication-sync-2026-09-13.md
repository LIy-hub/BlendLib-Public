# Branding publication sync / 品牌资源远端同步

Verified on 2026-09-13 (Asia/Shanghai). The project owner approved the parametric
folded B icon and the outlined wordmark. The emblem supplies the first **B**;
the provided Minecraft font supplies **lendLib**, together reading **BlendLib**.

## Published surfaces / 已同步位置

| Surface / 位置 | Result / 结果 |
| --- | --- |
| [Public repository](https://github.com/LIy-hub/BlendLib-Public) | Formal assets pushed to `main`; README loads the 3066 × 1022 wordmark. |
| GitHub social preview | Uploaded the formal 1774 × 887 image. GitHub reports a custom preview; the downloaded image matches the source pixels and SHA-256. |
| [Beta.3 release](https://github.com/LIy-hub/BlendLib-Public/releases/tag/v1.0.0-beta.3) | Added the wordmark above the existing notes. The remaining body and all 32 release asset names/digests were verified unchanged. |
| [CurseForge](https://www.curseforge.com/minecraft/mc-mods/blendlib) | Uploaded the formal square icon using the full canvas; updated the description's wordmark URL. Both visibly render on the public project page. |
| [Modrinth](https://modrinth.com/mod/blendlib) | Added one wordmark at the top of the existing description through native image hosting. Saved and visually checked. The owner handles the project icon; the project still displayed **Under review**. |

The source branch `codex/multiversion-modern` was pushed to the private repository.
Source branding commits `1131673` and `a734764` were mirrored as `7ddfd28` and
`772567ad99a324ae6e4d87a6d26d24c0a6df2cd1` in the public repository. Existing local
changes were excluded from these branding commits.

## Image references / 图片地址

- GitHub release and CurseForge description use the
  [wordmark at the published asset commit](https://raw.githubusercontent.com/LIy-hub/BlendLib-Public/772567ad99a324ae6e4d87a6d26d24c0a6df2cd1/docs/assets/branding/blendlib-wordmark-white.png).
- Modrinth uses its [native uploaded wordmark](https://cdn.modrinth.com/data/cached_images/91303c040200959a2d3e134fe84c0bc1114a5049.png).
  It is byte-identical to `blendlib-wordmark-white.png`, SHA-256
  `8d58edab32274b95c973cb680fabdf1a070869fa9de2b8f3cb0ae89fc7e8aa94`.
- CurseForge stores the [uploaded square icon](https://media.forgecdn.net/avatars/2034/251/639244429386226236.png)
  at 400 × 400 after platform resizing. Its bytes differ from the 1254 × 1254
  source; the full symbol and canvas were checked visually.
- GitHub stores the [custom social preview](https://repository-images.githubusercontent.com/1322246300/42eecc2d-1abd-48f2-a1ee-305c274c8a5d).
  SHA-256: `074601c4aad27b310120842a5b1fd2e9c713229a227f4eb7210498d58ec157f8`.

## Verification / 验证

Both formal export checks passed in the isolated public checkout. For public
asset commit `772567ad99a324ae6e4d87a6d26d24c0a6df2cd1`, GitHub
[Build](https://github.com/LIy-hub/BlendLib-Public/actions/runs/34736573811) and
[Fabric version matrix](https://github.com/LIy-hub/BlendLib-Public/actions/runs/34736573809)
completed successfully. Documentation-only follow-up commits do not change
runtime artifacts. These checks do not establish in-game visual acceptance.

已推送正式图标与带字标志，并核对 GitHub、CurseForge、Modrinth 对应页面。
Modrinth 简介采用原生托管图片，保留原有正文；项目图标由项目所有者管理。
其“审核中”状态与简介保存成功分别记录，不视为审核通过。本次未替换已发布的 JAR。

The vector masters, geometry, parameters, font provenance and reproducible scripts
remain in the [wordmark package](parametric-wordmark/README.md) and the
[icon adoption record](adoption-2026-09-13.md).
