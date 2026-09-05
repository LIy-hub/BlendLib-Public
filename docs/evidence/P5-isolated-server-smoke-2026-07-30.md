# P5 isolated dedicated-server smoke — 2026-07-30

Status: dedicated-server smoke **PASS** for the current P5 candidate. P5
remains `IN_PROGRESS` for its audit and outstanding real-client schedule,
blend, socket-motion, reload, cache, and cadence evidence.

Before launch, `run/p5-smoke-server` did not exist and port `25573` was free.
The phase template fixed `127.0.0.1:25573`, offline mode, disabled RCON and
whitelist enforcement, and the temporary world `blendlib-p5-smoke-world`.
The run used Java 25, one Gradle worker, D:-resident Gradle/temp paths, a
512 MiB Gradle daemon cap, and `-Xms256m -Xmx1024m` for the isolated server.

```powershell
.\gradlew.bat --no-daemon --max-workers=1 `
  -Dorg.gradle.jvmargs=-Xmx512m `
  :blendlib-showcase:runP5SmokeServer --console=plain
```

Minecraft `26.1.2`, Fabric Loader `0.19.3`, Fabric API
`0.154.2+26.1.2`, Java `25`, BlendLib, and Showcase loaded. The server bound
only `127.0.0.1:25573`, prepared only `blendlib-p5-smoke-world`, and reached
`Done (2.426s)!`. Root then sent `stop` through the same process's standard
input. All dimensions saved and Gradle returned `0` with
`BUILD SUCCESSFUL in 30s` (`18` actionable tasks; `2` executed).

| Artifact | SHA-256 |
| --- | --- |
| `blendlib-showcase/run/p5-smoke-server/logs/latest.log` | `7C930AA6A141FBBED81786F89906A8379891BEC83560BDE09A54CEA948B36238` |
| `blendlib-showcase/run/p5-smoke-server/server.properties` | `477A76FFE141C6E8DC8DF1B85FEEB0E41AF7AE8C6B12DCA5838D790EFDB71E66` |
| `blendlib-showcase/run/p5-smoke-server/eula.txt` | `EE27072E4A23E088522F740DDAAB0C7C4145C186969E90A86254FAA3A5EC5CE6` |

The log contains no `ERROR`, `Exception`, `NoClassDefFoundError`, or
`ClassNotFoundException` marker; no crash-report directory appeared. Port
`25573` was free and the wrapper had exited after shutdown. No formal
server/world, default Showcase run, other phase harness, client, push,
publication, or deployment was touched.

This closes only P5's dedicated-server smoke item. It does not prove any
client rendering, animation schedule/blend, quaternion continuity, socket
tracking, visual event, reload behavior, cache/cadence metric, synchronization,
performance, or audit acceptance.
