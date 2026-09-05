# P7 isolated dedicated-server smoke — 2026-07-30

Status: dedicated-server smoke **PASS** for the current P7 candidate. P7
remains `WAITING` for its real client, all-host/JFR performance, visual,
Iris/Sodium, and reload-leak acceptance evidence.

This server smoke is separate from `run/p7-benchmark` and does not enable or
reduce the frozen P7 performance scene. Before launch,
`run/p7-smoke-server` did not exist and port `25574` was free. The template
fixed `127.0.0.1:25574`, offline mode, disabled RCON and whitelist
enforcement, and the temporary world `blendlib-p7-smoke-world`. The run used
Java 25, one Gradle worker, D:-resident Gradle/temp paths, a 512 MiB Gradle
daemon cap, and `-Xms256m -Xmx1024m` for the isolated server.

```powershell
.\gradlew.bat --no-daemon --max-workers=1 `
  -Dorg.gradle.jvmargs=-Xmx512m `
  :blendlib-showcase:runP7SmokeServer --console=plain
```

Minecraft `26.1.2`, Fabric Loader `0.19.3`, Fabric API
`0.154.2+26.1.2`, Java `25`, BlendLib, and Showcase loaded. The server bound
only `127.0.0.1:25574`, prepared only `blendlib-p7-smoke-world`, and reached
`Done (3.235s)!`. Root then sent `stop` through the same process's standard
input. All dimensions saved and Gradle returned `0` with
`BUILD SUCCESSFUL in 29s` (`18` actionable tasks; `2` executed).

| Artifact | SHA-256 |
| --- | --- |
| `blendlib-showcase/run/p7-smoke-server/logs/latest.log` | `4578A65235412426F826D066E07F56051DE5A0C39E3AC7D0FF233DF23E799738` |
| `blendlib-showcase/run/p7-smoke-server/server.properties` | `5E4DBDE636F0CAC6C9DBEDD45882971A2857665381C3A80BEF7C5A304AED3D5C` |
| `blendlib-showcase/run/p7-smoke-server/eula.txt` | `EE27072E4A23E088522F740DDAAB0C7C4145C186969E90A86254FAA3A5EC5CE6` |

The log contains no `ERROR`, `Exception`, `NoClassDefFoundError`, or
`ClassNotFoundException` marker; no crash-report directory appeared. Port
`25574` was free and the wrapper had exited after shutdown. No P7 client,
benchmark pack, JFR, formal server/world, default Showcase run, other phase
harness, push, publication, or deployment was touched.

This closes only P7's dedicated-server smoke item. It does not prove the
100/25-host scene, all-host submission, rendering, 600/1,800-frame capture,
60-FPS target, allocation/cache metrics, Iris/Sodium visual behavior, 20-reload
leak result, or audit acceptance.
