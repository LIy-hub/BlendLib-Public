# P8 本地 Maven 空白消费者：历史 Default-Run Server 记录

状态：**HISTORICAL NON-GATE DEFAULT-RUN**。本文件仅证明空白消费者曾从捕获时的
本地 Maven 坐标解析 BlendLib RC，并在本地 default-run 中到达 `Done`。当时的
`runServer` 绑定 `*:25565` 且使用通用 `world`，不符合阶段专用的隔离契约；它不是
有效 dedicated-server smoke、P8 Gate PASS，也不构成客户端视觉、性能、Iris/Sodium、
P3–P7 审核或发布证据。

## 历史 RC 身份与解析前置核查

捕获时制品哈希的生成权威为 `D:\BlendLib\build\release\SHA256SUMS`。启动前
复核了 release runtime 与 local-Maven runtime 两行均为同一 SHA-256，且
`Get-FileHash -Algorithm SHA256` 重算一致：

```text
fbe65843b9adaf61f96946c6ea87bc4db85e6da21b12f85164782d1aeb54ad57  D:\BlendLib\build\release\blendlib-fabric-1.0.0-rc.1+26.1.2.jar
fbe65843b9adaf61f96946c6ea87bc4db85e6da21b12f85164782d1aeb54ad57  D:\BlendLib\build\local-maven\com\liy\blendlib\blendlib-fabric\1.0.0-rc.1+26.1.2\blendlib-fabric-1.0.0-rc.1+26.1.2.jar
```

> **历史捕获边界。** 后续本地重建已改变当前 runtime 字节 hash。上述两行保留为
> 本次 consumer smoke 的捕获身份，不能证明当前 Local Maven runtime 已启动。参见
> [P8 当前制品 rebind](./P8-current-artifact-rebind.md)：它只核对当前 artifact
> identity，不替代空白消费者构建/服务器运行；重新执行隔离 consumer 验证前，P8
> 仍为 WAITING。

只读核查确认该消费者是独立 Gradle 项目：

- `settings.gradle.kts` 仅通过
  `blendlib_local_maven_repo` 指向 `D:\BlendLib\build\local-maven` 并限制
  `com.liy.blendlib` group；
- `build.gradle.kts` 将
  `blendlib_rc_version` 组成为
  `com.liy.blendlib:blendlib-fabric:1.0.0-rc.1+26.1.2`；
- `runServer` 明确依赖 `prepareLocalMavenConsumerServerRun`，后者只在
  `D:\BlendLib\blendlib-local-maven-consumer-fixture\run\server` 写入隔离
  EULA。没有 `project(...)` 依赖或正式服务器路径。

该 POM 仍只含临时、非公开的
`LicenseRef-BlendLib-Local-Only` metadata；这不是最终许可证选择。

## 唯一一次历史 default-run 服务器运行

使用 Java 25 的唯一实际启动命令为：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
& 'D:\BlendLib\gradlew.bat' '-p' 'D:\BlendLib\blendlib-local-maven-consumer-fixture' `
  '-Pblendlib_local_maven_repo=D:\BlendLib\build\local-maven' `
  '-Pblendlib_rc_version=1.0.0-rc.1+26.1.2' 'runServer' '--console=plain'
```

启动前不存在命令行同时包含
`D:\BlendLib\blendlib-local-maven-consumer-fixture` 和
`-Dfabric.dli.env=server` 的 Java 进程。历史 default-run 日志
`D:\BlendLib\blendlib-local-maven-consumer-fixture\run\server\logs\latest.log`
记录：

```text
[18:20:38] Loading Minecraft 26.1.2 with Fabric Loader 0.19.3
- blendlib 1.0.0-rc.1+26.1.2
- blendlib_local_maven_consumer 1.0.0-rc.1+26.1.2
[18:20:49] Done (0.359s)! For help, type "help"
[18:20:49] ThreadedAnvilChunkStorage: All dimensions are saved
```

`crash-reports` 文件数为 `0`；对 `blendlib`、
`blendlib_local_maven_consumer` 归属的 error/exception/fatal 记录扫描结果为
`0`。该运行目录和其测试世界位于
`D:\BlendLib\blendlib-local-maven-consumer-fixture\run\server`；没有启动、
停止或修改 `D:\MinecraftFabricServer-26.1.2`、
`D:\MinecraftFabricServer-26.1.2-Fresh` 或任何正式世界。尽管如此，
`*:25565` / 通用 `world` 配置使这次历史启动不能作为隔离 Gate 证据。

## 受控清理

在 `Done` 与所有维度保存后，唯一 server child 按完整命令行重新核验为：

```text
PID: 61304
Executable: D:\Program Files\Java\jdk-25.0.2\bin\java.exe
Command scope: D:\BlendLib\blendlib-local-maven-consumer-fixture and -Dfabric.dli.env=server
Result: STOPPED_EXACT_LOCAL_MAVEN_CONSUMER_SERVER_PID=61304 (historical default-run; non-gate)
```

随后确认不存在匹配的 local-Maven consumer Java 进程。外层 Loom task 因
该受控终止报告 `-1` / `NTSTATUS 0xFFFFFFFF`；它不是 server startup
failure 的证据，也不能被报告为 Gradle build 成功。

## 未决项

- 本记录不是真实客户端视觉验证，也没有运行双客户端、Iris/Sodium 或性能基准。
- 它不因此改变 P3–P7 的 WAITING/审核状态，也不宣布 P8 或任何发布完成；必须在
  当前重建制品上使用阶段专用 loopback harness 重新验证。
- 没有 push、PR、tag、公开发布、生产部署或最终非 Add-on 许可证选择。
