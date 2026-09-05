# P8 当前 manifest：隔离 smoke harness

状态：**CURRENT-MANIFEST SERVER + LOCAL MAVEN CONSUMER SMOKES RECORDED / CLIENT REBIND PENDING / P8 GATE WAITING**。

本文件只定义后续重新捕获当前本地 RC 运行证据的安全入口、目录和
`SHA256SUMS` 前后绑定步骤。它不是一次 server/client/consumer smoke 记录，
不替代历史证据，也不把 P3--P7 的审核、视觉、同步、Iris/Sodium、重载或性能
Gate 提升为 PASS。

## 严格隔离范围

| 入口 | Gradle 任务 | 运行目录 | 监听/身份 | 临时世界 |
|---|---|---|---|---|
| Showcase server | `runP8CurrentManifestShowcaseServer` | `D:\BlendLib\blendlib-showcase\run\p8-current-manifest-server` | `127.0.0.1:25585`、`online-mode=false`、RCON 关闭 | `blendlib-p8-showcase-current-manifest-smoke-world` |
| Showcase client | `runP8CurrentManifestShowcaseClient` | `D:\BlendLib\blendlib-showcase\run\p8-current-manifest-client` | 本地开发身份 `BlendLibP8Smoke` | 不适用 |
| Local Maven consumer server | `runP8CurrentManifestConsumerServer` | `D:\BlendLib\blendlib-local-maven-consumer-fixture\run\p8-current-manifest-server` | `127.0.0.1:25586`、`online-mode=false`、RCON 关闭 | `blendlib-p8-local-maven-current-manifest-smoke-world` |

两个 server 模板还固定 `enforce-secure-profile=false`、`white-list=false` 与
`enforce-whitelist=false`，并由准备任务逐个核对全部模板安全键。首次运行只在对应
目录创建 `eula.txt` 和 `server.properties`；若现有文件不完全匹配所需安全设置，任务
会拒绝覆盖，执行者只能检查并处理那个精确的 P8 runDir，不能改用普通 profile。

不得启动、停止、修改、复制到或从下列位置复制任何世界、配置、日志或资源包：

- `D:\MinecraftFabricServer-26.1.2`
- `D:\MinecraftFabricServer-26.1.2-Fresh`
- 任意正式服务器、正式世界、`blendlib-showcase\run\server`、
  `blendlib-showcase\run\client`，或 consumer 的普通 `run\server`

这两个端口已固定；若 preflight 显示被占用，停止本次捕获并仅处理冲突的本地进程，
不得终止未知或正式服务器进程，也不得静默改变模板端口。

## 当前 SHA256SUMS 的前后绑定

开始前必须已有完整、成功的本地 `buildRelease` 输出。权威仍是：

```text
D:\BlendLib\build\release\SHA256SUMS
```

不要将本文中的历史 hash 或固定条目数当作当前身份。下列函数复核 manifest 中每一条
绝对路径与 SHA-256；它同时覆盖 release runtime、Showcase、Local Maven runtime、
POM/metadata 与 inventory 的当前声明集合。

```powershell
function Test-P8CurrentManifest {
    param([Parameter(Mandatory)][string]$Label)

    $manifest = 'D:\BlendLib\build\release\SHA256SUMS'
    $rows = Get-Content -LiteralPath $manifest | Where-Object { $_.Trim() }
    if ($rows.Count -eq 0) { throw "Empty SHA256SUMS: $manifest" }
    $seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($row in $rows) {
        if ($row -notmatch '^([0-9a-f]{64})  (.+)$') {
            throw "Malformed SHA256SUMS entry: $row"
        }
        $expected, $artifact = $Matches[1], $Matches[2]
        if (-not $seen.Add($artifact)) { throw "Duplicate SHA256SUMS path: $artifact" }
        if (-not (Test-Path -LiteralPath $artifact)) { throw "Missing artifact: $artifact" }
        $actual = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -ne $expected) { throw "SHA-256 mismatch: $artifact" }
    }
    "$Label P8_SHA256SUMS_INDEPENDENT_VERIFIED entries=$($rows.Count)"
}

$timestamp = Get-Date -Format 'yyyy-MM-dd-HHmmss'
$evidence = "D:\BlendLib\build\manual-p8-current-smoke-evidence\$timestamp"
New-Item -ItemType Directory -Path $evidence -ErrorAction Stop | Out-Null
Test-P8CurrentManifest -Label PRE | Tee-Object -FilePath "$evidence\00-pre-manifest-verify.txt"
Copy-Item -LiteralPath 'D:\BlendLib\build\release\SHA256SUMS' -Destination "$evidence\01-pre-SHA256SUMS"
```

在 `PRE` 与 `POST` 之间不得修改源码、重建 RC 或更换 Local Maven repository。任何
manifest 内容、路径、条目数或 hash 的变化都使本次捕获失效；应保存现有日志、重新完成
build/rebind，再以新的 manifest 重新开始一次隔离 smoke。完成全部观察后运行：

```powershell
Test-P8CurrentManifest -Label POST | Tee-Object -FilePath "$evidence\90-post-manifest-verify.txt"
Copy-Item -LiteralPath 'D:\BlendLib\build\release\SHA256SUMS' -Destination "$evidence\91-post-SHA256SUMS"
if (Compare-Object (Get-Content "$evidence\01-pre-SHA256SUMS") (Get-Content "$evidence\91-post-SHA256SUMS")) {
    throw 'SHA256SUMS changed during smoke; do not bind this capture to either manifest.'
}
```

该过程证明命名的当前本地 artifacts 在捕获前后保持 manifest 一致；它不把 workspace
development classpath 变成外部安装包验证。

## 执行顺序

先做只读端口 preflight，确认本次不会碰到已有的本地监听：

```powershell
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $_.LocalPort -in 25585, 25586 }
```

在 `D:\BlendLib` 设置 Java 25。每个长运行任务只启动一次；不要使用普通
`runServer` 或 `runClient`。

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat :blendlib-showcase:runP8CurrentManifestShowcaseServer --console=plain
.\gradlew.bat :blendlib-showcase:runP8CurrentManifestShowcaseClient --console=plain
```

先保存 Showcase server `Done` 与所有维度保存行到
`10-showcase-server-start.txt`。随后在另一个受控窗口启动 client，保存其启动、Render
thread、资源重载、crash-report 检查和精确 child-process 清理结果到
`20-showcase-client-startup.txt`。如有截图，只能作为 limited startup 观察；它不是视觉
验收。

Showcase server/client 任务使用 workspace **development classpath**。它们可证明捕获时
的开发接线和隔离启动，但**not an external Showcase JAR installer**：不得报告为“外部
Showcase JAR 已安装/已验证”，也不能代替 packaged-runtime 的 Local Maven consumer 解析。

然后先运行独立 consumer 的 `check`，使 `verifyLocalMavenConsumerBoundary` 实际解析
`com.liy.blendlib:blendlib-fabric` 并拒绝 project dependency；P8 consumer server 任务也
直接依赖该 boundary task，避免单独启动时绕过检查。再启动它的独立 server：

```powershell
& 'D:\BlendLib\gradlew.bat' '-p' 'D:\BlendLib\blendlib-local-maven-consumer-fixture' `
  '-Pblendlib_local_maven_repo=D:\BlendLib\build\local-maven' `
  '-Pblendlib_rc_version=1.0.0-rc.1+26.1.2' 'check' '--console=plain'

& 'D:\BlendLib\gradlew.bat' '-p' 'D:\BlendLib\blendlib-local-maven-consumer-fixture' `
  '-Pblendlib_local_maven_repo=D:\BlendLib\build\local-maven' `
  '-Pblendlib_rc_version=1.0.0-rc.1+26.1.2' 'runP8CurrentManifestConsumerServer' '--console=plain'
```

保存 consumer `check` 的 resolution-boundary 输出与 server `Done`/所有维度保存行到
`30-local-maven-consumer-check.txt` 和 `31-local-maven-consumer-server-start.txt`。该路径
验证空白消费者由 Local Maven coordinate 解析当前 runtime；它仍不是公开分发、远程仓库、
外部安装器或 P8 Gate。

每次启动都只能停止已核实完整命令行同时指向上述精确 runDir 和相应
`-Dfabric.dli.env=server`/`client` 的 Java child。保留以下日志的复制摘录，不能只引用会
变化的 `latest.log`：

```text
D:\BlendLib\blendlib-showcase\run\p8-current-manifest-server\logs\latest.log
D:\BlendLib\blendlib-showcase\run\p8-current-manifest-client\logs\latest.log
D:\BlendLib\blendlib-local-maven-consumer-fixture\run\p8-current-manifest-server\logs\latest.log
```

建议 evidence 目录至少保留 `00-pre-manifest-verify.txt`、`01-pre-SHA256SUMS`、
`10-showcase-server-start.txt`、`20-showcase-client-startup.txt`、
`30-local-maven-consumer-check.txt`、`31-local-maven-consumer-server-start.txt`、三份日志
摘录、`90-post-manifest-verify.txt`、`91-post-SHA256SUMS` 和 `result.md`。`result.md` 必须
逐项区分 manifest integrity、Showcase server startup、Showcase client limited startup、
Local Maven resolution、consumer server startup、visual、performance 与 P3--P7/P8 Gate；
不得由一个 `Done` 或一条 hash 记录推导其他项目 PASS。

## 不改变的结论

2026-07-30 的 current-manifest Showcase server 与 Local Maven consumer server rebind 已记录在
[`P8-current-manifest-server-consumer-rebind-2026-07-30.md`](./P8-current-manifest-server-consumer-rebind-2026-07-30.md)，
并保持 client rebind 为 pending。该 capture 未改写 `SHA256SUMS` 或 release artifacts，未
push、建 PR、公开发布、部署生产服务器/世界，且没有选择最终非 Add-on 许可证。P8 与所有
P3--P7 未决 Gate 继续保持 WAITING，直到用户管理的审核和真实验收完成。
