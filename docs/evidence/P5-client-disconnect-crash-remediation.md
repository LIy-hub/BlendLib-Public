# P5 客户端断线渲染提取崩溃：自动化修复证据

状态：**AUTOMATED REMEDIATION PASS / ISOLATED REAL-CLIENT DISCONNECT RECHECK PASS / P5 GATE WAITING**。

本记录仅说明 26.1.2 client teardown 路径的源码与自动化回归修复。它不是新的真实客户端、
视觉、性能、P5/P6 Gate 或发布证据；没有在本次修复中启动 Minecraft client/server。

## 触发证据与根因

隔离 Showcase 客户端的崩溃报告：

```text
D:\BlendLib\blendlib-showcase\run\client\crash-reports\crash-2026-07-29_23.07.41-client.txt
```

该报告在 render thread 的 `Extracting render state for an entity in world` 路径中记录
`IllegalStateException: no active client play connection`。调用链为：

```text
ClientAnimationLifecycleBridge.entityKey
SkinnedAnimationRuntime.entityKey
SkinnedAnimationEntitySnapshotFactory.create
BlendEntityRenderer.extractRenderState
```

断线清理已将 active connection session 置空，但实体卸载/渲染提取仍可能在该 teardown
窗口抵达。此时不得由裸 entity id 重建跨连接 identity，也不能让渲染提取抛出异常。

## 修复语义

- `ClientAnimationLifecycleBridge.entityKey(...)` 保留正常调用的 fail-fast 行为：没有 active
  play epoch 时仍抛出原有异常；它不是 teardown 入口。
- 新的 client-only `activeEntityKey(...)` 在 active epoch 中返回同一个 typed
  `session + entity id` key，在断线后返回 empty，不创建任何裸 ID 身份。
- `onEntityUnload(...)` 通过这个 optional lookup 在断线后的 late callback 返回 `0`；active
  connection 中仍按完整 typed key 精确删除实例。
- `SkinnedAnimationRuntime` 公开同一 client-only、带 entity-id 校验的 optional lookup。
  `SkinnedAnimationEntitySnapshotFactory.create(...)` 在 empty 时返回已有 missing snapshot，
  不调用 `extract`、不绑定 controller/clock，也不派发视觉事件。
- 未修改 LineWidth 修复、socket marker、public API/wire format、server/common/core、raw OpenGL
  或资源/渲染热路径边界。

## 自动化回归覆盖

新增/强化覆盖包括：

- `ClientAnimationLifecycleBridgeTest`：active epoch 的 optional key 与原 typed key 相同；
  disconnect 后 late entity unload 为零、registry 保持清空，普通 `entityKey(...)` 仍 fail-fast。
- `SkinnedAnimationRuntimeTest`：已经提取的实例经历 disconnect 后，late unload 不重建 key，
  registry、clock 与 prepared asset 计数继续为零。
- `EntityAdapterContractsTest`：实体 snapshot factory 必须调用 `activeEntityKey(...)`，在 empty
  时返回 missing snapshot，且不再使用 throwing `entityKey(...)` 提取路径。

使用 Java 25 与 `--no-daemon` 运行：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat --no-daemon :blendlib-fabric-client:test `
  --tests 'com.liy.blendlib.fabric.client.animation.ClientAnimationLifecycleBridgeTest' `
  --tests 'com.liy.blendlib.fabric.client.animation.runtime.SkinnedAnimationRuntimeTest' `
  --tests 'com.liy.blendlib.fabric.client.entity.EntityAdapterContractsTest' `
  --rerun-tasks --console=plain

.\gradlew.bat --no-daemon :blendlib-fabric-client:test --rerun-tasks --console=plain
```

两次命令均成功。完整 client suite 的 XML 汇总为 **33 suites / 125 tests / 0 failures /
0 errors / 0 skipped**；三组相关 suite 分别为 4、12、6 tests，均零失败/错误。

## 未决与下一步

自动化覆盖证明两个 teardown call path 不再产生该异常，并保留 active-session cleanup。
隔离真实客户端断线复测已于 2026-07-29 完成，详情见下节；它只消除了本记录所述
teardown 崩溃，仍不得把本修复报告为完整视觉或 P5/P6 Gate PASS。P6 的
session/dimension/payload 契约未改变。

## 隔离真实客户端断线复测（2026-07-29）

协调端以 Java 25 执行：

```powershell
.\gradlew.bat --no-daemon :blendlib-showcase:runClient --console=plain
```

仅进入 `D:\BlendLib\blendlib-showcase\run\client` 下的可丢弃本地世界
`BlendLib Visual RC`；未启动、停止或修改任何正式服务器或世界。当前实例加载了先前
崩溃报告中同一位置的 `blendlib_showcase:animated_actor`（`0.50, 200.00, 2.50`），
然后通过游戏的“保存并退出到标题界面”正常断开并退出客户端。

- 新的 `runClient` 进程以 **exit code 0** 结束，日志在 `23:20:54` 完成玩家断开和所有
  维度保存，在 `23:21:44` 记录正常 `Render thread ... Stopping!`；
- 客户端确实返回标题界面后才关闭；
- `run\client\crash-reports` 仍只有修复前的
  `crash-2026-07-29_22.47.37-client.txt`（LineWidth）和
  `crash-2026-07-29_23.07.41-client.txt`（断线生命周期）两个文件，未生成新的报告；
- 本次时间窗的 `latest.log` 不含 `no active client play connection`、
  `Missing elements in vertex: LineWidth`、`Reported exception thrown` 或 `Game crashed`。

离线开发凭据触发的 Mojang/Realms 认证错误以及 Loom 的缺失
`build/resources/client` 提示均为已知环境噪声，与 BlendLib teardown 无关。该真实复测
证明本次断线崩溃已修复，但不替代完整动画、socket 跟随、F3+T、双客户端同步、性能或
用户管理的视觉验收。

## 补充：access-order pose-cache CME 修复与真实复验（2026-07-30）

在后续、同样仅使用
`D:\BlendLib\blendlib-showcase\run\client` 和可丢弃 `BlendLib Visual RC`
世界的 P4 资源包取证结束时，保存并返回标题触发了一个不同的 teardown 缺陷。日志
`latest.log` 在 `03:22:53` 记录：

```text
Caused by: java.util.ConcurrentModificationException
  at ...LinkedHashMap$LinkedEntryIterator.next(...)
  at com.liy.blendlib.fabric.client.animation.BoundedPoseCache.removeInstance(BoundedPoseCache.java:56)
  at com.liy.blendlib.fabric.client.animation.ClientAnimationInstanceRegistry.removeMatching(...)
```

根因是 access-order `LinkedHashMap#get(...)` 会重排链表；读取与
`removeInstance(...)` 迭代移除能在 entity-unload 路径重叠。最小修复将
`BoundedPoseCache` 的查找、写入、按实例/代际移除、指标读取和清空置于同一监视器内，
不改变 LRU、容量、typed instance identity 或 public API。

新增/强化回归：

- `BoundedPoseCacheTest.serializesAccessOrderLookupsWithRemovalOfAllCachedRevisionsForOneInstance`
  让 access-order cache-hit 与 8,192 个 revision 的 instance removal 并发交错；
- `ClientAnimationLifecycleBridgeTest` 覆盖真实
  `onEntityUnload -> removeMatching` 多 revision 清理。

Java 25/no-daemon 的定向验证为：

```powershell
.\gradlew.bat --no-daemon :blendlib-fabric-client:test --rerun-tasks --console=plain `
  --tests 'com.liy.blendlib.fabric.client.animation.BoundedPoseCacheTest' `
  --tests 'com.liy.blendlib.fabric.client.animation.ClientAnimationInstanceRegistryTest' `
  --tests 'com.liy.blendlib.fabric.client.animation.ClientAnimationLifecycleBridgeTest'
```

命令 exit `0`，XML 汇总为 **3 suites / 18 tests / 0 failures / 0 errors / 0 skipped**；
`git diff --check` 无输出，未暂存或提交。

随后以当前修复后的 Java 25 `:blendlib-showcase:runClient` 重入同一隔离世界，按游戏 UI
执行“保存并返回到标题界面”，确认实际到达标题界面后再点击“退出游戏”。日志从
`03:36:22` 记录玩家断开、三维度保存完成，到 `03:36:46` 记录正常
`Render thread ... Stopping!`；本次窗口消失、Gradle/client 子进程退出，
`run\client\crash-reports` 仍只有两个 2026-07-29 历史文件。该时间窗未出现
`ConcurrentModificationException`、`ReportedException` 或新的 crash report。

这仅为该特定 cache-teardown 症状的真实隔离复验通过。P5 仍为 `IN_PROGRESS`，P6 的
同步/双客户端项、P5 fallback-schedule provenance、视觉、F3+T、性能和用户单独审核
均不因本修复而变为 PASS。

## 补充：同步状态存储的 late-unload NPE 修复与真实复测（2026-07-30）

在随后一次仅用于 `p5_fallback_actor` 观察的同一隔离客户端会话中，用户通过游戏 UI
选择“保存并返回到标题界面”后，`latest.log` 于 `07:33:36` 记录了新的 Render-thread
`ReportedException`。根因栈为
`ClientAnimationSyncStore.onEntityUnload(ClientAnimationSyncStore.java:74)` 向
`BlendInstanceKey.Entity` 传入了空 `connectionSession`。

当前实际加载 class 的 `javap -c -p` 检查确认旧实现先读取一次
`connectionSession` 进行判空、再读取一次供 `new BlendInstanceKey.Entity(...)` 使用；
因此 disconnect teardown 可在两次字段读取之间使第二次读取为 `null`。这不是旧 JAR
误加载的推断。最小 client-only 修复只在 `onEntityUnload` 中先捕获局部
`activeSession`，随后只用该不可变局部值构造 typed entity key。它不修改 API、payload、
sequence、session/dimension 语义、runtime entrypoint 或 common/server 代码。

新增的 `ClientAnimationSyncStoreTest`
`lateEntityUnloadAfterDisconnectIsNoOpAndDoesNotRecreateSessionState` 覆盖
`disconnect() -> onEntityUnload()`：late unload 不抛出、store/session/dimension/entity
state 保持空，且断线后新命令仍为 `OUT_OF_SESSION_DROPPED`。Java 25/no-daemon 的定向
test 成功（3 tests，零 failures/errors）；随后完整
`:blendlib-fabric-client:test --rerun-tasks` 成功，XML 汇总 33 suites / 128 tests / 0
failures / 0 errors，`git diff --check` 无输出。

协调端随即只启动一次当前 Java 25 `:blendlib-showcase:runClient`，仅进入
`D:\BlendLib\blendlib-showcase\run\client` 的可丢弃 `BlendLib Visual RC` 世界，使用游戏
UI 保存并返回标题、再退出。该次日志记录玩家进入 `07:47:39`、暂停保存 `07:48:13`、
玩家断开及全部维度保存 `07:48:24`、正常 `Render thread ... Stopping!` `07:48:42`；
该时间窗没有 `connectionSession`、`NullPointerException`、`ReportedException`、
`Error executing task on Client` 或 `Game crashed`，crash-reports 仍只有两份 2026-07-29
历史文件。Gradle 启动器记录 `BUILD SUCCESSFUL`（19 actionable tasks）。

这只消除了本节的 typed-session late-unload 症状。P5 仍为 `IN_PROGRESS`，P6 仍为
`WAITING`；fallback 132-tick 动画、socket/visual-event、F3+T、双客户端同步、性能和
用户单独审核均未因此获得 PASS。
