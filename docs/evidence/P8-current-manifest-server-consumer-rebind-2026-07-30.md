# P8 当前 manifest：Showcase 与 Local Maven consumer server rebind

状态：**CURRENT-MANIFEST LIMITED SERVER/CONSUMER SMOKE PASS / CLIENT REBIND PENDING / P8 GATE WAITING**。  
捕获目录：`D:\BlendLib\build\manual-p8-current-smoke-evidence\2026-07-30-182559-adr020-rebind`

## Manifest identity boundary

本次开始前和结束后均独立核对
`D:\BlendLib\build\release\SHA256SUMS` 的全部 `17` 项绝对路径及 SHA-256：

```text
entries=17
manifest SHA-256=5c0ced836a37a4086ddf9ff99c247b080f3b48dc0e4ee0b84299eb9f0d4559c4
MISMATCHES=0
PRE/POST SHA256SUMS identical=true
```

该 manifest 的 current runtime 为
`31d0f528c8baa58ca62ae86238951a79ae5c9183318565e4228a5d43ddb3950a`，
current Showcase 为
`f013e47fbd350f6e9772fa08fa28157f84c2fbbf126ae118f08e2294e6e01d66`；
rebuilt Showcase JAR 的 `P7ReferenceScenario.CAPTURE_TELEPORT_COMMAND` 是
`/tp @s 0 67 24 180 0`。这些身份与 ADR-019/ADR-020 对齐。

## Showcase loopback server

仅启动 `:blendlib-showcase:runP8CurrentManifestShowcaseServer`，运行目录为
`D:\BlendLib\blendlib-showcase\run\p8-current-manifest-server`。日志证明：

```text
Loading Minecraft 26.1.2 with Fabric Loader 0.19.3
- blendlib 1.0.0-rc.1+26.1.2
- blendlib_showcase 1.0.0-rc.1+26.1.2
Starting Minecraft server on 127.0.0.1:25585
Preparing level "blendlib-p8-showcase-current-manifest-smoke-world"
Done (0.474s)!
All dimensions are saved
```

启动后以完整命令行和监听端口核验唯一 child JVM 为 PID `62080`，再仅终止该 PID。
随后 PID 不存在且 `25585` 已释放。外层 Loom 任务记录 `NTSTATUS 0xFFFFFFFF`，原因是
已验证 child 被受控终止；这不是 server startup 或 manifest 验证失败。

## Local Maven consumer loopback server

仅启动独立 fixture 的 `runP8CurrentManifestConsumerServer`。该任务先实际执行：

```text
> Task :verifyLocalMavenConsumerBoundary
```

然后仅使用
`D:\BlendLib\blendlib-local-maven-consumer-fixture\run\p8-current-manifest-server`：

```text
Loading Minecraft 26.1.2 with Fabric Loader 0.19.3
- blendlib 1.0.0-rc.1+26.1.2
- blendlib_local_maven_consumer 1.0.0-rc.1+26.1.2
Starting Minecraft server on 127.0.0.1:25586
Preparing level "blendlib-p8-local-maven-current-manifest-smoke-world"
Done (0.397s)!
All dimensions are saved
```

监听端口反查、完整命令行核验后，仅终止 PID `33860`。随后 PID 不存在且 `25586` 已释放；
其外层 Loom `-1` 同样仅来自受控 child cleanup。

## Boundary and remaining work

没有启动默认 `run/server`、`run/client`、正式服务器、正式世界或 P7 benchmark client。没有
执行 push、PR、tag、公开发布、远端 publish、生产部署或最终非 Add-on 许可证选择。

本记录是 workspace development-classpath Showcase server 与 Local Maven coordinate consumer
server 的当前 manifest startup/resolution 证据；它不是 external installer、客户端 visual、
P6 双客户端同步、Iris/Sodium、20 次 reload、性能或任何 P3--P8 Gate PASS。P8 Showcase
client current-manifest startup rebind 仍待执行，P8 保持 `WAITING`。
