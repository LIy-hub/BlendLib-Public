# BlendLib Beta 发布与集成文档

当前公开版本为 **Beta.2**，为 15 个 Minecraft 游戏版本分别提供 Fabric 运行库。
从安装、导出或集成文档开始，再按需查阅对应版本的验证记录。

## 当前版本与入门

- [安装与项目简介](../../README.md)
- [模组开发者手册](../developer-handbook.md)（22 章：依赖、资源、实体/方块实体/物品、动画同步、姿态、诊断和 API 参考；完整 Java 示例以 26.1.2 为基线）
- [Beta.2 发布说明与依赖表](./beta2-release-notes.md)
- [Beta.2 版本验证与发布记录](./multiversion-progress.md)
- [CurseForge 当前项目描述](./curseforge-description-beta2.md)
- [Blender 导出清单](./blender-export-checklist-v1.md)（Blender 5.1+，严格 GLB 资源要求）
- [Fabric 26.1.2 开发者教程](./developer-tutorial-26.1.2.md)（Alpha API 基线示例；其他版本须核对原生回调）
- [诊断与排错](./diagnostic-troubleshooting-v1.md)
- [项目图标与文字标](../assets/branding/README.md)

Beta.2 的构建入口位于 `versions/legacy` 与 `versions/modern`；请使用精确游戏版本的 JAR。
以下资料保留 Beta.1 / Alpha 基线与历史实现证据，不代替当前版本的依赖表或验收结论。

## Beta.1 与历史基线

- [Beta.1 发布核验](./beta1-verification-2026-09-06.md)
- [Beta 发布说明](./beta-release-notes.md)
- [CurseForge 历史项目描述](./curseforge-project-description.md)
- [Schema 与公共 API Alpha 冻结记录](./schema-and-api-freeze-v1.md)
- [许可证与发布元数据](./local-license-metadata.md)
- [第三方许可证库存](./third-party-license-inventory.md)
- [X1–X9 alpha 合并记录](../expansion/integration/x1-x9-alpha-merge.md)
- [X8 平台与生态候选](../expansion/x8/README.md)
- [P8 当前本地制品 rebind（历史 evidence）](../evidence/P8-current-artifact-rebind.md)

## Alpha 与本地制品边界

根目录的历史构建仍以 Minecraft 26.1.2、Fabric Loader 0.19.3、Fabric API
`0.154.2+26.1.2` 与 Java 25 为基线。Beta.2 的 15 版本范围由上方发布说明单独声明。
项目仍处于 Beta，API 与行为可能变化；请先在独立实例验证集成。

26.1.2 runtime、sources、Javadoc、Showcase、Blender Add-on ZIP、Local Maven 与库存文件由
`buildRelease`/Alpha 本地发布任务生成。aggregate Javadoc、sources、runtime、Showcase 和
Add-on ZIP 都是构建输出，不是本目录的 checked-in 文档；每次构建后的身份只能由当次
`build/release/SHA256SUMS` 和对应 evidence 确认。

`publishPublicAlpha` 只准备 `build/local-maven/` 中的 Alpha runtime 与纯 API 坐标，供
独立消费者验证；它不是远端 Maven 发布。X8 的
`x8AssembleLocalCandidate` 保持为独立本地候选入口，不能把 Fabric 26.2 或 NeoForge
内容嵌入 26.1.2 runtime JAR，也不能单独构成发布、兼容性或 Gate 结论。

## 保留的验收边界

- X1–X9 源内容被纳入合并候选，不等于所有轨道、P0–P8、动态验证、性能或视觉通过。
- X7 的正式集成记录仍要求新独立静态审查；X8 的 standalone candidate、NeoForge binding、
  runtime、visual、hardware、Iris/Sodium 和 release 条件保持各自的 `WAITING`/`PENDING` 状态。
- 历史 smoke、RC 文字和旧制品 rebind 仅是其产生时的 evidence，不能被本文当作当前 Beta
  安装、运行、视觉或发布证明。
