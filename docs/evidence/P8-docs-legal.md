# P8 文档与法律准备证据

状态：IMPLEMENTATION DOCUMENTATION + LOCAL INVENTORY AUTOMATION COMPLETE / P8 GATE WAITING。

## 范围

本证据覆盖本地文档、公共 API 使用指引、Blender 导出清单、诊断索引，以及本地 RC 的
依赖/许可证/NOTICE 库存自动化。库存任务会检查实际 release archive、Local Maven 主制品、
Javadoc legal 内容和未打包 host runtime 图；它不选择最终非 Add-on 许可证、不会公开发布，
也不声明视觉、性能、26.2 或 P8 Gate PASS。

## 交付文件

- `docs/release/schema-and-api-freeze-v1.md`
- `docs/release/developer-tutorial-26.1.2.md`
- `docs/release/blender-export-checklist-v1.md`
- `docs/release/diagnostic-troubleshooting-v1.md`
- `docs/release/third-party-license-inventory.md`
- `docs/release/local-license-metadata.md`
- `docs/release/README.md` 与 `docs/README.md` 的入口链接
- `docs/error-codes-v1.md` 的 `BLENDLIB-MAT-004` 时态修正
- 根 `build.gradle.kts` 中的 `generateReleaseInventories`、
  `verifyReleaseInventories` 与 `verifyReleaseInventoryNegativeFixtures`
- 生成的 `build/release/dependency-inventory.txt`、`license-inventory.txt` 与
  `SHA256SUMS`（每次 `buildRelease` 重建；不在本文硬编码某次哈希）

## 交叉核对基线

- Schema/profile/limits：`schemas/blendlib-model-v1.schema.json`、
  `docs/design-v1.md`、`docs/glb-profile-v1.md` 与 `docs/error-codes-v1.md`。
- 纯 API：`blendlib-api/src/main/java/com/liy/blendlib/api/`。
- 服务端语义 facade：
  `blendlib-fabric-common/src/main/java/com/liy/blendlib/fabric/common/animation/BlendAnimations.java`。
- client entity/block-entity/item 入口：
  `blendlib-fabric-client/src/client/java/com/liy/blendlib/fabric/client/{entity,blockentity,item}/`。
- `BLENDLIB-MAT-004` 当前实现：
  `BlendDiagnosticCodes.MAT_004`、`ClientModelReloadListener`、
  `MaterialReloadDiagnosticTest`、`ProfiledRenderHandleReloadTest` 和 ADR-014。
- 许可证范围：`LICENSE-PENDING`、`blender-addon/blender_manifest.toml`、
  `blender-addon/LICENSE`、ADR-009，以及用户授权的进度账本记录。
- archive/host 分层：outer runtime、Showcase、Add-on、aggregate Javadoc、Local Maven
  primary artifacts 和 `blendlib-fabric-client:runtimeClasspath` 的实时扫描。

## 本地验证

- 只读 `rg` 交叉核对通过：17 个本文列出的公共 API symbol、11 个教程使用的
  method/registration contract、17 个稳定 diagnostic code，以及 schema 的
  `format_version`、两个 profile 和 `textureResourceId` 均在当前本地 source/schema
  中找到。
- 本机没有 `markdown-link-check`、`lychee` 或 `markdownlint`；替代的只读相对
  Markdown link verifier 检查了本目录、`docs/README.md` 和本 evidence 共 9 个
  文档，全部路径存在。
- `verifyReleaseInventories` 成功：对 v2 dependency/license inventory 进行精确 component
  key、主制品路径、重复、空字段、`ABSENT` 有界原因和 archive 内容核对。
- `verifyReleaseInventoryNegativeFixtures` 成功：实际生成 malformed、duplicate、incomplete
  和 dependency-malformed 临时副本，确认 verifier 均拒绝。
- Add-on 构建在 `build/staged-blender-addon/` 中排除仅供源 fixture 的、外部依赖
  `jsonschema` 的验证脚本；ZIP verifier 断言其不在包内，同时保留 Add-on 的唯一 GPL
  范围和 `LICENSE`。
- Aggregate Javadoc 的 `legal/LICENSE`、`legal/COPYRIGHT`、`legal/jquery.md`、
  `legal/jqueryUI.md`、`legal/dejavufonts.md` 被逐项检查并登记。

## 未决项

- 不能据此选择非 Add-on 最终许可证、公开名称、源码策略或发布渠道。
- P3–P7 的审核、视觉、同步、Iris/Sodium、真实 reload 和性能等待状态不被改变。
- `buildRelease` 仍须在任何最终审计前重新生成实际制品、Javadoc、sources、SHA-256、
  inventory 和 local-Maven blank consumer 证据；当前值应只从生成的 `SHA256SUMS` 读取。
- 26.2 spike 仍必须使用独立 adapter JAR 并保留核心 API/schema 隔离。

无 push、PR、公开 tag、公开发布、生产服务器/世界操作或许可证扩展发生。
