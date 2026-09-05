# P5 socket marker：26.1.2 `LineWidth` 崩溃修复

状态：**AUTOMATED REMEDIATION PASS / REAL-CLIENT RECHECK REQUIRED / P5 GATE 仍为 WAITING**。

本记录只修复并验证一个客户端渲染顶点格式缺失问题。它没有启动真实客户端、
服务器或正式世界；没有改变 socket 的 client-only、render-thread-only 或无 gameplay
语义边界，也不替代 P5 的动画、混合、双实例、断线清理、性能和真实视觉验收。

## 根因与本地 26.1.2 API 证据

崩溃报告：

```text
D:\BlendLib\blendlib-showcase\run\client\crash-reports\crash-2026-07-29_22.47.37-client.txt
```

报告在 Render thread 中记录：

```text
java.lang.IllegalStateException: Missing elements in vertex: LineWidth
...
SkinnedSocketMarkerSubmitter.emitAxis(SkinnedSocketMarkerSubmitter.java:98)
```

使用当前本地 26.1.2 merged jar：

```text
D:\BlendLib\.gradle\loom-cache\minecraftMaven\net\minecraft\minecraft-merged-043a8b3edf\26.1.2\minecraft-merged-043a8b3edf-26.1.2.jar
```

对其 `javap -p/-c` 的核对结果是：

- `VertexConsumer` 公开提供 `setLineWidth(float)`；
- `RenderTypes.lines()` 返回 `RenderTypes.LINES`；
- `RenderTypes.LINES` 绑定 `RenderPipelines.LINES`；
- `RenderPipelines.LINES_SNIPPET` 以 `DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH`
  和 `VertexFormat.Mode.LINES` 创建。

旧 marker 对每个 endpoint 只写了 Position、Color、Normal。第二个
`addVertex(...)` 会结束前一个 vertex，故正好触发报告中的 `LineWidth` 缺失异常。

## 最小修复

`SkinnedSocketMarkerSubmitter.emitAxis(...)` 的两个 endpoint 现在都在原有
`setColor(...).setNormal(...)` 后追加：

```java
.setLineWidth(MARKER_LINE_WIDTH)
```

其中 `MARKER_LINE_WIDTH` 为 `1.0F`。渲染类型、RGB 轴、变换顺序、socket capture
和 custom-geometry 交付路径均未改变；未使用 raw OpenGL。

## 回归覆盖与自动验证

`SkinnedRenderBackendContractsTest` 新增两条真实 26.1.2 `BufferBuilder` 覆盖：

1. `minecraft2612LinesRejectsVertexWithoutLineWidth` 以
   `RenderTypes.lines().format()` 写 Position/Color/Normal、故意省略 LineWidth；下一次
   `addVertex` 必须抛出 `IllegalStateException`，且消息含 `LineWidth`。
2. `presentationSocketMarkerCompletesEveryMinecraft2612LineVertex` 经
   `SubmitNodeStorage -> CustomFeatureRenderer.renderTranslucent` 执行生产 marker，使用
   同一真实 `BufferBuilder` 完成 mesh；`buildOrThrow()` 成功，结果保留 26.1.2 lines
   format/mode，draw state 为 12 个 line-expanded vertices。

执行环境：Java 25，`JAVA_HOME=C:\Program Files\Java\latest\jdk-25`。
首次默认 daemon 配置曾因已有 Loom cache lock 的前所有者异常退出报告
`ClosedFileSystemException`；未修改缓存、未终止任何未知进程，改用单次
`--no-daemon` 后完成验证。

```powershell
.\gradlew.bat --no-daemon :blendlib-fabric-client:test `
  --tests com.liy.blendlib.fabric.client.render.SkinnedRenderBackendContractsTest `
  --rerun-tasks --console=plain
# BUILD SUCCESSFUL；该类 7 tests, 0 failures, 0 errors

.\gradlew.bat --no-daemon :blendlib-fabric-client:test --rerun-tasks --console=plain
# BUILD SUCCESSFUL

git diff --check
# no output
```

focused XML 证据：

```text
D:\BlendLib\blendlib-fabric-client\build\test-results\test\TEST-com.liy.blendlib.fabric.client.render.SkinnedRenderBackendContractsTest.xml
```

## 必须由协调端执行的真实客户端复测

在重建到当前 client classpath 后，仅在隔离 Showcase client 运行目录执行：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat --no-daemon :blendlib-showcase:runClient --console=plain
```

在该隔离客户端的临时本地世界中运行：

```mcfunction
/summon blendlib_showcase:animated_actor ~ ~ ~
```

需要保留以下可审计证据：

- summon 后 Render thread 不再产生 `Missing elements in vertex: LineWidth`，且无新的
  crash report；
- RGB socket marker 可见，并随指定骨骼/动画正确移动；
- 该 actor 的 idle、walk、attack，以及远距离 culling 后恢复仍无崩溃；
- 截图或短视频、`latest.log` 的时间窗口和所用当前 artifact/classpath identity。

不得为本复测启动、停止或修改 `D:\MinecraftFabricServer-26.1.2`、
`D:\MinecraftFabricServer-26.1.2-Fresh`、任何正式服务器或正式世界。成功的
自动测试不等同于真实客户端视觉证据，故本修复不将 P5 或任何后续 Gate 标为 PASS。
