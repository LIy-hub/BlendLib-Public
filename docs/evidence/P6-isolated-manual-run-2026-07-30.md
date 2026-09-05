# P6 isolated manual-run evidence — 2026-07-30

Status: partial real-client evidence; P6 Gate remains `WAITING`.

## Scope and isolation

- Server: `D:\BlendLib\blendlib-showcase\run\p6-sync-server`, bound only to `127.0.0.1:25575`.
- Clients: `run\p6-client-a` (`BlendLibP6A`) and `run\p6-client-b` (`BlendLibP6B`).
- The P6-only world was used; no `D:\MinecraftFabricServer-26.1.2`, `D:\MinecraftFabricServer-26.1.2-Fresh`, formal server, or formal world was started or modified.
- All client processes were closed through their Minecraft UI. Both successful isolated client runs returned Gradle exit code `0`.

## Initial concurrent-client attempt

`p6ClientA` connected to the local server. The independently launched `p6ClientB` terminated before it reached the server with:

```text
EXCEPTION_ACCESS_VIOLATION ... glfw.dll+0x10fa1
Render thread: GLFW.glfwPollEvents -> RenderSystem.pollEvents -> Minecraft.run
```

Evidence: `run\p6-client-b\hs_err_pid33508.log`. Its server log has no `BlendLibP6B` login for that attempt, and the error log contains no `com.liy.blendlib` frame. The error report also records low available physical memory at crash time. This is retained as a local concurrent-GUI/environment incident, not attributed to the P6 protocol and not used to waive a Gate.

## One controlled concurrent retry

After the successful standalone B control, B was allowed to reach its title screen before A was launched. A also reached its title screen, but B then again terminated before either client connected to the P6 server:

```text
EXCEPTION_ACCESS_VIOLATION ... glfw.dll+0x10fa1
Render thread: GLFW.glfwPollEvents -> RenderSystem.pollEvents -> Minecraft.run
```

Evidence: `run\p6-client-b\hs_err_pid47212.log`. The second report records 1,650 MiB free physical memory at crash time, B working set about 828 MiB and private commit about 1,126 MiB. The isolated server had reached `Done (0.545s)!` but logged no A/B join. This reproduces the local concurrent-GUI condition while excluding a P6 server connection, payload, or tracking path. No further same-machine dual-client retry was performed.

## Successful isolated single-client controls

### Client A

- The retained `run\p6-sync-server\logs\debug-1.log.gz` server trace reached `Done (0.437s)!` and accepted `BlendLibP6A`.
- In the local P6 world, A successfully executed `/summon blendlib_showcase:animated_actor ~ ~ ~` and `/setblock ~2 ~ ~ blendlib_showcase:animated_altar`; the retained `debug-1.log.gz` trace records both server-side acknowledgements.
- The same retained trace records A's clean disconnect. Its `runP6ClientA` Gradle process then logged `Stopping!` and exited `0`.

### Client B alone

- With A fully closed, `p6ClientB` started, reached the Render thread/resource reload, and then connected to `127.0.0.1:25575`.
- The final `run\p6-sync-server\logs\latest.log` records B's login/join and later clean disconnect. The client then reached world play, and saved `run\p6-client-b\screenshots\2026-07-30_00.23.58.png`.
- B logged `Stopping!`, and its Gradle process exited `0`. No P6-B `crash-reports` directory was created in this successful standalone control.

The screenshot is an observation of a loaded tracked scene only. The Showcase actor is a deliberately thin, single-sided plane, so this one angle cannot establish mesh, socket, animation, or material visual acceptance.

## What this proves

- The current isolated P6 dedicated server can start, accept each real local client identity in separate controls, and save all dimensions after a normal `stop`.
- A real client can create the Showcase animated entity and altar in the P6-only world, disconnect cleanly, and exit normally.
- B can start and connect normally when it is the only Minecraft GUI process, so the first B failure is not a general B-launch failure.
- The second controlled retry reproduces B's GLFW-native failure only after another real Minecraft GUI is present; it remains an environment blocker rather than a BlendLib protocol finding.

## Still required for the P6 Gate

- Two stable simultaneous clients observing the same semantic attack start, with A/B time-stamped evidence.
- Persistent tracking replay and non-replay of expired transient state with the prescribed observations.
- Sequence replacement, unknown-target/late-packet, disconnect cleanup, entity-ID reuse, and dimension-change evidence.
- Real client visual proof; screenshots above do not prove animation timing, mesh front/back behavior, or socket motion.

Do not infer a P6 PASS from these controls.

## Fresh sequence/transient automatic refresh (2026-07-30)

在未启动 P6 server/A/B 或其他 Minecraft 实例的情况下，独立 runner 使用 Java 25 运行一次：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
$env:GRADLE_USER_HOME='D:\BlendLib\build\p4-gradle-user-home-mirror'
$env:TEMP='D:\BlendLib\build\p5-gradle-temp'
$env:TMP='D:\BlendLib\build\p5-gradle-temp'
.\gradlew.bat --no-daemon --max-workers=1 :blendlib-fabric-client:test --tests '*ClientAnimationSyncStoreTest' --tests '*SkinnedAnimationRuntimeTest' --rerun-tasks --console=plain
```

该唯一测试调用退出 `0`，`BUILD SUCCESSFUL in 45s`，执行 12 个任务。当前 XML 为
`ClientAnimationSyncStoreTest` 的 `3/3` 和 `SkinnedAnimationRuntimeTest` 的 `12/12`，合计
`2 suites / 15 tests / 0 failures / 0 errors`。它是 sequence replacement/stale-drop 与选定
transient/runtime 路径的当前自动化证据；不替代两个 tracker 同一 attack 起点、persistent replay、
真实 transient 非重放、断线/换维度或 late-packet 风险观察。P6 Gate 仍为 `WAITING`。
