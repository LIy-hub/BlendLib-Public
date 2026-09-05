# P8 当前本地制品 rebind

状态：**CURRENT LOCAL ARTIFACT IDENTITY REBOUND / HISTORICAL SMOKES NOT REBOUND / P8 GATE WAITING**。

本记录只解决正常本地重建后“当前制品是哪一组字节”的可追溯性。它不重写或
删除任何历史 smoke，不运行 Gradle、server 或 client，也不证明当前 runtime 已被
安装、启动或视觉验收。

## 当前权威与只读核对

当前权威仍是：

```text
D:\BlendLib\build\release\SHA256SUMS
```

本次 rebind 读取到该 manifest 有 **17** 个非空条目，并对下列五个
`build/release/` primary artifact 执行只读 `Get-FileHash -Algorithm SHA256`；每个
路径存在且与 manifest 匹配：

| 制品 | 当前 SHA-256 |
|---|---|
| `blendlib-fabric-1.0.0-rc.1+26.1.2.jar` | `31d0f528c8baa58ca62ae86238951a79ae5c9183318565e4228a5d43ddb3950a` |
| `blendlib-showcase-1.0.0-rc.1+26.1.2.jar` | `f013e47fbd350f6e9772fa08fa28157f84c2fbbf126ae118f08e2294e6e01d66` |
| `blendlib-fabric-1.0.0-rc.1+26.1.2-sources.jar` | `bbce8deb4665ac2e69fe569b08370e460baf244bc2b735d442929f3d5e0e3090` |
| `blendlib-fabric-1.0.0-rc.1+26.1.2-javadoc.jar` | `e2be91529c5e67a46be62b3a8094655792b6d4483035ff4269cc9396e2b1426b` |
| `blendlib-exporter-1.0.0.zip` | `e0e2bf71af516b364958bd8b49ec8118254e74e6d7986c81e29805010f931b5b` |

同一 current manifest 还把 Local Maven runtime JAR 记录为与当前 release runtime
相同的 `31d0f528c8baa58ca62ae86238951a79ae5c9183318565e4228a5d43ddb3950a`。
完整 Local Maven/API/POM/metadata 和两个 inventory 的身份仍以全部 17 项 manifest
为准；本记录不把 Maven sidecar 当作独立 release payload。

## 历史 smoke 与当前二进制的边界

下列记录保留为历史捕获证据，其原有 hash **不得**被改写：

- [Showcase dedicated-server smoke](./P8-showcase-server-smoke.md) 使用 runtime
  `fbe65843b9adaf61f96946c6ea87bc4db85e6da21b12f85164782d1aeb54ad57` 和 Showcase
  `caa45a63e580c8f698a7fcf611b2e003d78953d6715d24572ba83447d4e43fd2`。
- [Showcase client startup smoke](./P8-showcase-client-startup.md) 使用同一组历史
  runtime/Showcase hash。
- [Local Maven consumer server smoke](./P8-local-maven-consumer-server-smoke.md) 使用历史
  release/local-Maven runtime hash `fbe65843b9adaf61f96946c6ea87bc4db85e6da21b12f85164782d1aeb54ad57`。

因此，历史 server/client/consumer 记录只证明各自在捕获时的隔离 smoke。当前
`31d0f…` runtime 和当前 Local Maven runtime 必须分别重新经过相应隔离验证，才能形成
新的当前运行证据。本 rebind 不是视觉、服务器、空白消费者、Iris/Sodium、性能或任何
P3--P8 Gate 的 PASS 证据。

## 范围与后续

所有制品仍只位于本地。非 Add-on metadata 仍只能使用
[`LicenseRef-BlendLib-Local-Only`](../release/local-license-metadata.md)，它不是最终
许可证或发布授权；GPL-3.0-or-later 仍只适用于
[`blender-addon/`](../adr/ADR-009-temporary-extension-license-metadata.md)。本记录没有
选择最终非 Add-on 许可证、公开名称、源码策略或发布渠道。

在任一后续正常本地重建后，应重新读取 `SHA256SUMS`，重新核对对应 artifact，并把新的
隔离 server/client/consumer smoke 明确绑定到该 manifest。此前 P8 始终保持 WAITING，
不执行 push、tag、PR、公开发布或生产部署。

## P5 cache-teardown 修复后的本地 RC 刷新（2026-07-30，历史记录）

在 `BoundedPoseCache` 的断开清理并发修复之后，协调端的独立构建执行者以 Java 25
完成下列本地命令，二者均以退出码 `0` 完成：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat --no-daemon :blendlib-core:test --console=plain
.\gradlew.bat --no-daemon buildRelease --console=plain
```

前者在 9 秒内成功；后者在 32 秒内成功（62 个任务，30 executed、32 up-to-date）。
`buildRelease` 已重新完成本地 Maven 空白消费者校验、Add-on ZIP 打包和校验、inventory
负向校验以及 release SHA-256 校验。协调端随后只读逐项复算当前 `SHA256SUMS` 的 17
个条目，结果为：

```text
P8_CURRENT_MANIFEST_ALL_ROWS_VERIFIED entries=17
```

当时的五个 primary artifact 为：

| 制品 | SHA-256 | 字节数 |
|---|---|---:|
| `blendlib-fabric-1.0.0-rc.1+26.1.2.jar` | `7675f92fc5606ded4cbf6e842eb08960e68cc1471e290d5cb91b668b7151cbec` | 420161 |
| `blendlib-showcase-1.0.0-rc.1+26.1.2.jar` | `6410c899dba7a9d37b3e58abdf943d4e226d83731b718cbe7a81c5c5ca3cc111` | 115082 |
| `blendlib-fabric-1.0.0-rc.1+26.1.2-sources.jar` | `902e71b5359e6a2189e53197f2d2417a33bd623d2495217a551499a38fd4a2d4` | 208730 |
| `blendlib-fabric-1.0.0-rc.1+26.1.2-javadoc.jar` | `e2be91529c5e67a46be62b3a8094655792b6d4483035ff4269cc9396e2b1426b` | 5004243 |
| `blendlib-exporter-1.0.0.zip` | `e0e2bf71af516b364958bd8b49ec8118254e74e6d7986c81e29805010f931b5b` | 36403 |

这是一份当时的本地二进制身份与自动化构建证据，不重写上文历史 smoke 的 hash，也不替代
新的隔离 server/client/consumer runtime 复验。未启动客户端或服务器，未触碰正式世界，
未执行 push、tag、公开发布或生产部署；P8 仍为 WAITING。

## 协调端最终本地重建复核（2026-07-29）

本记录写入后，协调端以 Java 25 执行了
`.\gradlew.bat --no-daemon buildRelease --console=plain`。构建成功并重新运行 inventory、
negative fixture、Local Maven consumer、Add-on packaging 和 SHA verification。随后协调端
按本仓库 RC artifact 文档中的 PowerShell 循环逐条重新散列当前 manifest，结果为：

```text
P8_SHA256SUMS_INDEPENDENT_VERIFIED entries=17
```

上表的当时 primary artifact hash 在该次重建后保持不变。后续的 release-copy 新鲜度
修复已生成新的 current manifest，见下节。该历史复核仍只证明当时本地字节身份，不把历史
smoke 提升为当前 runtime、Showcase 或 Local Maven consumer 的运行证据；P8 继续保持
WAITING。

## P5/P6 late-unload 与 P8 release-copy 新鲜度修复后的当前本地 RC（2026-07-30）

在 `ClientAnimationSyncStore` 的 late-unload NPE 修复后，协调端发现一次成功的
`buildRelease` 曾保留旧的 `build/release` runtime JAR：source runtime 为 420189 bytes，
而 release copy 为 420161 bytes，后者的 `javap` 仍显示对 `connectionSession` 的二次字段
读取。根因是 `releaseRuntimeJar` 和 `releaseShowcaseJar` 只声明 destination output、未把
各自 source archive 声明为 Gradle input；独立静态检查还发现 `packageBlenderAddon` 未把
staged Add-on 目录声明为 ZIP input。

最小 Gradle 修复只增加三个精确输入：runtime source JAR、Showcase source JAR，以及
`stagedBlenderAddonDirectory`。没有改变版本、API、LicenseRef、打包路径、Blender 命令、
排除规则、清单/哈希链或任何发布权限。随后以 Java 25、D: 临时/Gradle 目录执行一次串行
`buildRelease --no-daemon --max-workers=1`，结果为 `BUILD SUCCESSFUL in 25s`（62 tasks，
23 executed、39 up-to-date）；runtime/Showcase copy 与 Add-on package 都实际执行，Local
Maven blank-consumer、inventories、negative fixtures 与最终 SHA verification 均通过。

协调端随后独立复算 17/17 个 `SHA256SUMS` 条目，`MISMATCHES=0`，并验证：

- source runtime、release runtime 与 Local Maven runtime 都为 420189 bytes，且 SHA-256
  都是 `9d1db91dea9e9535f322637555704844f5fec0191d1d918d00cfd942bd0941c4`；
- source 与 release Showcase 都为 122537 bytes，且 SHA-256 都是
  `0650772884ef6a6ffab08bb4fb030fc3efe6472c934bad119419cbaa94f405eb`；
- release runtime 的 `ClientAnimationSyncStore.onEntityUnload` bytecode 将
  `connectionSession` 一次读取到 local slot，再用该 slot 构造 `BlendInstanceKey.Entity`；
- GPL Add-on ZIP 为 36403 bytes、SHA-256
  `e0e2bf71af516b364958bd8b49ec8118254e74e6d7986c81e29805010f931b5b`，含唯一 `LICENSE`，
  且不含 `scripts/verify_p2_descriptor_schema.py`；
- 当前 `SHA256SUMS` 本身 SHA-256 为
  `b0de9c2d25d9c16f5e412ae74a77bc6fc4f109c2c2f0b1310a51f694373ca6a1`。

这消除了“成功构建但 release copy 过期”的本地交付缺陷，但不是 runtime/Showcase/consumer
重新安装或启动证据，不能重绑历史 smoke，也不使 P8 或更早任何 Gate 变为 PASS。所有制品
仍只在本地；未执行 push、tag、公开发布、远端 publish 或生产部署。

## 当前 manifest 的隔离 smoke rebind（2026-07-30）

在未改源码、未重建 RC、未切换 Local Maven repository 的前提下，协调端对当前
`SHA256SUMS` 做了新的隔离 smoke。完整、不可变的捕获目录为
`D:\BlendLib\build\manual-p8-current-smoke-evidence\2026-07-30-081245`：PRE 与 POST
均独立复算 **17** 个条目，且两个 `SHA256SUMS` 副本逐字相同。

- Showcase server 仅使用 `blendlib-showcase/run/p8-current-manifest-server`，在
  `127.0.0.1:25585`、临时世界
  `blendlib-p8-showcase-current-manifest-smoke-world` 达到 `Done (0.439s)`，并保存三个
  维度。该后台启动器没有可写 stdin；在完整命令行、端口和 runDir 都确认后，只结束了
  精确 server child PID 9452。端口随后释放；因此 Gradle 的 task `FAILED` 是受控清理结果，
  不是启动失败。
- Showcase client 仅使用 `blendlib-showcase/run/p8-current-manifest-client`，本地身份
  `BlendLibP8Smoke` 到达标题界面、Render thread、Indigo、LWJGL、OpenAL 和
  `blendlib_reload ... models=4 missing=0 diagnostics=0`，再通过 UI 正常退出；启动器为
  `BUILD SUCCESSFUL in 1m 18s`。隔离 crash-report 目录为空，日志也没有
  `ReportedException`、`connectionSession`、`NullPointerException` 或 `Game crashed`。
  离线开发身份导致的 Realms/credential 401 仅为环境噪声，不构成 BlendLib 崩溃。
- 空白 Local Maven consumer 首先运行 `check`，实际执行
  `verifyLocalMavenConsumerBoundary` 并成功；随后只使用其 `run/p8-current-manifest-server`
  在 `127.0.0.1:25586`、临时世界
  `blendlib-p8-local-maven-current-manifest-smoke-world` 达到 `Done (0.398s)`，保存三个
  维度。确认后只结束精确 server child PID 46432，端口已释放；其 Gradle task 的 `FAILED`
  同样只表示无 stdin 后的受控 cleanup。

上述只是与当前 local manifest 绑定的 development-classpath/server 和 Local Maven
resolution 有限证据；它不是外部安装器或公开分发验证，不能替代 static/rigid/skinned
在世界内的视觉、网络同步、Iris/Sodium、20 次 reload、性能或 P3--P7 审核。P8 继续为
WAITING；没有执行 push、tag、PR、公开发布、远端 publish、生产部署或正式服务器/世界操作。

## ADR-019/ADR-020 对齐后的当前本地 RC 重建（2026-07-30）

在 ADR-019 fixture/source 对齐、ADR-020 的 P7 命令改为
`/tp @s 0 67 24 180 0` 后，协调端以 Java 25、no-daemon、单 worker 执行一次串行
`buildRelease`。它在 50 秒内成功（62 tasks：25 executed、1 from cache、36 up-to-date），
并实际通过 Local Maven 空白消费者的独立 `clean check`、Add-on 校验/打包、inventory、
negative fixture 与 end-of-build SHA verifier。

协调端随后对当前 `SHA256SUMS` 全部 **17** 个条目逐项 `Get-FileHash`，结果为
`MISMATCHES=0`。当前 primary release artifact 是：

| 制品 | SHA-256 | 字节数 |
|---|---|---:|
| `blendlib-fabric-1.0.0-rc.1+26.1.2.jar` | `31d0f528c8baa58ca62ae86238951a79ae5c9183318565e4228a5d43ddb3950a` | 420189 |
| `blendlib-showcase-1.0.0-rc.1+26.1.2.jar` | `f013e47fbd350f6e9772fa08fa28157f84c2fbbf126ae118f08e2294e6e01d66` | 122545 |
| `blendlib-fabric-1.0.0-rc.1+26.1.2-sources.jar` | `bbce8deb4665ac2e69fe569b08370e460baf244bc2b735d442929f3d5e0e3090` | 208746 |
| `blendlib-fabric-1.0.0-rc.1+26.1.2-javadoc.jar` | `e2be91529c5e67a46be62b3a8094655792b6d4483035ff4269cc9396e2b1426b` | 5004243 |
| `blendlib-exporter-1.0.0.zip` | `e0e2bf71af516b364958bd8b49ec8118254e74e6d7986c81e29805010f931b5b` | 36403 |
| `SHA256SUMS` | `5c0ced836a37a4086ddf9ff99c247b080f3b48dc0e4ee0b84299eb9f0d4559c4` | 2682 |

本地 `javap -constants` 对 rebuilt Showcase JAR 的
`P7ReferenceScenario.CAPTURE_TELEPORT_COMMAND` 读取为
`"/tp @s 0 67 24 180 0"`。因此这是 ADR-020 对齐后的当前字节身份，不再是旧命令
快照；此前的 server/client/consumer smoke 仍保持历史捕获，必须重新绑定到此 manifest
之后才能成为当前 runtime 证据。此次构建未启动 server/client，未触碰正式服务器/世界，
也未执行 push、tag、公开发布、远端 publish 或生产部署；P8 继续为 `WAITING`。

随后，Showcase loopback server 与 Local Maven consumer loopback server 已分别绑定到该
current manifest；完整的 PRE/POST manifest、日志与精确 cleanup 记录见
[P8 current-manifest server/consumer rebind](./P8-current-manifest-server-consumer-rebind-2026-07-30.md)。
Showcase client rebind、客户端视觉与性能证据仍未执行，P8 继续为 `WAITING`。
