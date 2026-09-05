# P8 本地 RC 制品证据

状态：**LOCAL ARTIFACT ASSEMBLY + HASH/INVENTORY VERIFICATION COMPLETE / P8 GATE WAITING**。
本文件描述可重复的本地构建与证据位置；它不选择最终非 Add-on 许可证、不会公开发布，且
不代替用户管理的审核、视觉或性能 Gate。

## 权威的当前哈希来源

RC 输出会随正常源码、Javadoc、Add-on 或 Local Maven 元数据重建而变化。因此本证据**不
手工固定任何一次构建的 SHA-256 值**。当前权威是最后一次成功 `buildRelease` 写出的：

```text
D:\BlendLib\build\release\SHA256SUMS
```

`writeReleaseSha256` 以精确的 primary release/Local Maven artifact providers 和两个生成
inventory 文件为输入；`verifyReleaseSha256` 解析该文件并核对路径、格式、去重和每个当前
字节哈希；`buildRelease` 结束后还会运行 `verifyReleaseSha256AtBuildReleaseEnd`。没有
“固定 13 项”之类的验收假设：集合由当前声明的 primary artifacts 派生。

## 当前制品 rebind 与历史 smoke 边界

[P8 当前制品 rebind](./P8-current-artifact-rebind.md) 单独记录了正常本地重建后的
当前 `SHA256SUMS` 身份与只读 primary-artifact hash 复核。先前的 Showcase server、
Showcase client 和 Local Maven consumer smoke 都保留其**捕获当时**的 hash；它们不会因
rebind 自动变成当前二进制的运行证据。rebind 本身也不是 server/client smoke，P8 Gate
继续保持 WAITING，直到当前制品被重新安装/启动并留下新的隔离证据。

## 可重复构建与复核

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat verifyReleaseInventories --console=plain
.\gradlew.bat verifyReleaseInventoryNegativeFixtures --console=plain
.\gradlew.bat buildRelease --console=plain
.\gradlew.bat verifyReleaseSha256AtBuildReleaseEnd --console=plain

$sums = Get-Content .\build\release\SHA256SUMS | Where-Object { $_.Trim() }
$seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
foreach ($line in $sums) {
    if ($line -notmatch '^([0-9a-f]{64})  (.+)$') { throw "Malformed SHA256SUMS entry: $line" }
    $expected, $path = $matches[1], $matches[2]
    if (-not $seen.Add($path)) { throw "Duplicate SHA256SUMS path: $path" }
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing artifact: $path" }
    $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $expected) { throw "SHA-256 mismatch: $path" }
}
"P8_SHA256SUMS_INDEPENDENT_VERIFIED entries=$($sums.Count)"
```

上述命令的最后一个条目数来自实际 manifest，不是文档预设。若任何一次正常重建改变了制品，
必须重新运行这些命令，并以重新生成的 `SHA256SUMS` 作为唯一当前值。

## 26.1.2 本地 RC / Local Maven 制品范围

`dependency-inventory.txt` 会列出以下 primary artifact 的 root-relative 路径，并验证没有
遗漏或额外的 Local Maven 主制品：

| 范围 | 产物 |
|---|---|
| `build/release/` | `blendlib-fabric-1.0.0-rc.1+26.1.2.jar`、Showcase JAR、aggregate sources JAR、aggregate Javadoc JAR、GPL-scoped Blender Add-on ZIP |
| `build/local-maven/.../blendlib-fabric/` | runtime JAR、sources、Javadoc、POM 与 `maven-metadata.xml` |
| `build/local-maven/.../blendlib-api/` | API JAR、sources、POM、Gradle `.module` 与 `maven-metadata.xml` |
| `build/release/` inventory | `dependency-inventory.txt`、`license-inventory.txt`；二者也进入 `SHA256SUMS` |

`.md5`、`.sha1`、`.sha256`、`.sha512` 只作为 Maven 从属 checksum sidecar：校验器确认每一
个 sidecar 都对应一项主制品，且没有未解释的额外文件；它们不重复作为独立 release payload
写入主 SHA manifest。

runtime archive 校验还要求 Fabric metadata、本地 `LicenseRef-BlendLib-Local-Only` 和三个
本项目 nested JAR，且拒绝 `.blend`、FBX、OBJ。Showcase JAR 不允许隐藏 nested JAR。Add-on
ZIP 由 staged Add-on 构建，保留唯一 GPL `LICENSE`，并排除仅用于源码 fixture、依赖外装
`jsonschema` 的 `scripts/verify_p2_descriptor_schema.py`。

## 依赖/许可证/NOTICE 证据

`build/release/license-inventory.txt` 的 v2 每条记录包含坐标、版本、来源、许可证、许可证
证据、NOTICE 可用性/证据和有界缺失说明。它明确分开：

- 实际打包的本项目 runtime、Showcase、Add-on 与 Javadoc legal assets；
- Fabric/Minecraft 启动环境提供、但未打包到 RC JAR 的 host runtime component；
- Local Maven primary artifacts 与 Maven checksum sidecar。

Aggregate Javadoc 里的 `legal/LICENSE`、`legal/COPYRIGHT`、jQuery 3.7.1、jQuery UI 1.14.1
及 DejaVu Fonts 2.37 legal records 都由生成任务检查并登记。未知或未提供的本机 metadata
不会被猜测为某个许可证：它必须显示为 `ABSENT` 并包含限定为“未打包、只扫描本机 POM/解析
artifact”的原因。详见 [第三方与许可证清单](../release/third-party-license-inventory.md)。

`verifyReleaseInventoryNegativeFixtures` 还会证明 malformed、duplicate、incomplete 和
dependency-malformed inventory 副本被拒绝；这是对 verifier 覆盖范围的自动化负例证据，而不
是一次法律结论。

## 独立的 26.2 adapter spike

26.2 spike 有独立运行 JAR 和独立哈希，刻意不作为 26.1.2 RC bundle 或其 `SHA256SUMS` 输入。
它的路径、当前构建结果和 SHA-256 仅以
[P8 26.2 spike 证据](P8-26.2-spike.md) 为准。该隔离防止 26.2 适配代码、依赖或制品污染
26.1.2 release candidate。

## 未决项

- P3--P7 的用户管理审核、真实视觉/双客户端/Iris-Sodium/reload/性能证据仍未完成。
- 最终公开名称、非 Add-on 许可证、源码策略和发布渠道仍须用户决定。
- 所有制品保持本地；没有 push、PR、公开 tag、remote publish、公开发布或生产部署。
