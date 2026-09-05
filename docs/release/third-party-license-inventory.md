# 第三方与许可证库存（Alpha）

状态：此文档说明 Alpha 制品的库存契约与第三方边界；不是法律意见、完整可再分发 notice
bundle、当前构建/发布证明或新的许可证选择。本次合并没有执行库存、构建或哈希命令。

## 机器可读记录

- `build/release/dependency-inventory.txt`
- `build/release/license-inventory.txt`
- `build/release/SHA256SUMS`

`generateReleaseInventories`、`verifyReleaseInventories` 与
`verifyReleaseInventoryNegativeFixtures` 负责覆盖、重复、格式、archive 范围和缺失说明。
本文不复制易过期的哈希或依赖行；当次生成的记录才是当次制品的证据。

## 项目自身的许可证边界

| 范围 | 当前状态 | 证据 |
|---|---|---|
| 根目录、`blendlib-*`、Showcase、非 Add-on 源码/资源、Java/runtime 工件 | Apache-2.0 | 根 `LICENSE` 与 `NOTICE` |
| `blender-addon/` 全目录 | GPL-3.0-or-later，且仅限该目录 | `blender-addon/blender_manifest.toml`、`blender-addon/LICENSE`、[ADR-009](../adr/ADR-009-temporary-extension-license-metadata.md) |

GPL-3.0-or-later 不因 Add-on 目录存在而扩展到其他目录。X8 父系中的
`LICENSE-PENDING`/LicenseRef 描述是其历史候选边界，不能覆盖当前 Alpha 的 Apache-2.0
决定；它们同样不替代任何第三方资产或依赖的独立条款。

## 生成库存的可验证契约

`license-inventory.txt` 使用 `blendlib-release-license-inventory-v2`。每一条 component
记录都包含：

- `scope`、坐标、版本与 `inclusion`，区分实际打包内容和仅由宿主提供、未打包的组件；
- 来源、许可证声明及其证据、NOTICE 可用性及其证据；
- 当本地 POM、解析 JAR 或 archive 没有相应许可证/NOTICE 时的 `bounded_absence`。

`dependency-inventory.txt` 使用 `blendlib-release-dependency-inventory-v2`，列出所有
主制品的 root-relative 路径以及与许可证库存相同的 component key。校验器拒绝缺失、重复或
格式错误的行、两份库存覆盖不一致、未列出/多出的 Local Maven 主制品，以及没有有界原因的
许可证或 NOTICE 缺失。

负例任务只在 `build/tmp/release-inventory-negative-fixtures/` 写入临时副本并证明解析器
拒绝 malformed、duplicate、incomplete 和 dependency-malformed 记录；不改动权威生成文件。

## archive 与 Local Maven 边界

库存不会把 Gradle 开发 classpath 误报为 JAR 内嵌内容：

| 制品范围 | 记录与边界 |
|---|---|
| 26.1.2 runtime 与三个 nested BlendLib JAR | Alpha 的 Apache-2.0/NOTICE 范围；各 JAR 应携带 `META-INF/LICENSE` 与 `META-INF/NOTICE`。没有外部 third-party nested JAR 时，库存应明确其为未打包。 |
| Showcase JAR | Apache-2.0/NOTICE 范围；不得隐藏 `META-INF/jars/` 中的第三方 nested JAR。 |
| Aggregate sources 与 Javadoc JAR | 项目 LICENSE/NOTICE；Javadoc 自带的 Oracle、jQuery、jQuery UI、DejaVu legal 文件单独登记。 |
| Blender Add-on ZIP | 仅 Add-on payload 与 GPL `LICENSE`；排除只供源码 fixture 验证且依赖外装 `jsonschema` 的 helper。 |
| Fabric/Minecraft/JDK 宿主 | 以 `not-bundled` host runtime 记录；不描述为 BlendLib JAR 内嵌或再分发内容。 |

`publishPublicAlpha` 准备 `build/local-maven/` 的 Alpha runtime/API 坐标。可解析主制品为
runtime/API JAR、sources、Javadoc（如适用）、POM、Gradle `.module`（如生成）和两个
`maven-metadata.xml`；checksum sidecar 只视为其派生文件，不是独立 payload。

## 第三方测试资产与 X8 候选

`test-assets/third_party/khronos/glTF-Sample-Assets/`
`5109ab2a499c5a2c784b86e460fa491d52256e25/` 是测试数据，不是 runtime 回退资源。
本地 `PROVENANCE.md` 记录：SimpleSkin 与 AnimatedCube 的模型 payload 为 CC0-1.0；上游
`LICENSE.md` / `metadata.json` 是 CC-BY-4.0，但这些文档没有被复制。任何将其纳入
可分发包的未来工作都必须另行进行 notice/attribution 审核。

X8 的 26.2、NeoForge、datagen、examples、converter 和模板仍保留独立的来源、许可、分发、
runtime/visual 和 Gate 条件。它们纳入此 Alpha 合并候选不构成远端发布、许可证扩张、NeoForge
binding、性能或视觉 PASS。

## 本地核对参考

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat verifyReleaseInventories --console=plain
.\gradlew.bat verifyReleaseInventoryNegativeFixtures --console=plain
.\gradlew.bat buildRelease --console=plain
Get-FileHash .\build\release\dependency-inventory.txt -Algorithm SHA256
Get-FileHash .\build\release\license-inventory.txt -Algorithm SHA256
Get-Content .\build\release\SHA256SUMS
```

这些是未来本地核对入口。以最后一次实际命令生成的文件为制品和哈希证据；不得用本文替代
最终公开许可、发布渠道、源代码策略或用户的法律/发布决定。
