# P4 isolated dedicated-server smoke — 2026-07-30

Status: dedicated-server smoke **PASS** for the current P4 candidate. P4
remains `WAITING` for its user-managed audit and remaining real-client material
evidence; this does not promote the phase Gate.

Before launch, `run/p4-smoke-server` did not exist and port `25572` was free.
The phase template fixed `127.0.0.1:25572`, offline mode, disabled RCON and
whitelist enforcement, and the temporary world
`blendlib-p4-smoke-world`. The run used Java 25, one Gradle worker, D:-resident
Gradle/temp paths, a 512 MiB Gradle daemon cap, and an isolated server cap of
`-Xms256m -Xmx1024m`.

```powershell
.\gradlew.bat --no-daemon --max-workers=1 `
  -Dorg.gradle.jvmargs=-Xmx512m `
  :blendlib-showcase:runP4SmokeServer --console=plain
```

Minecraft `26.1.2`, Fabric Loader `0.19.3`, Fabric API
`0.154.2+26.1.2`, Java `25`, BlendLib, and Showcase loaded. The server bound
only `127.0.0.1:25572`, prepared only `blendlib-p4-smoke-world`, and reached
`Done (4.945s)!`. After observing `Done`, root sent `stop` through the same
process's standard input. All dimensions saved and Gradle returned `0` with
`BUILD SUCCESSFUL in 40s` (`18` actionable tasks; `2` executed).

| Artifact | SHA-256 |
| --- | --- |
| `blendlib-showcase/run/p4-smoke-server/logs/latest.log` | `0B62D0ACD16602292B09BE8FC181AA20B40E90AC477B3CEC97326E0BEE1AF653` |
| `blendlib-showcase/run/p4-smoke-server/server.properties` | `643BEAAEBAFD91A8F1F43877D1208EF627BE1585BDC8E5C58C28EFD268FC5609` |
| `blendlib-showcase/run/p4-smoke-server/eula.txt` | `EE27072E4A23E088522F740DDAAB0C7C4145C186969E90A86254FAA3A5EC5CE6` |

The log has no `ERROR`, `Exception`, `NoClassDefFoundError`, or
`ClassNotFoundException` marker; no crash-report directory appeared. Port
`25572` was free and the wrapper had exited after shutdown. No formal
server/world, default Showcase run, other phase harness, client, push,
publication, or deployment was touched.

This closes only P4's dedicated-server smoke item. It does not prove client
rendering, resource-pack priority/reload, material visuals, animation,
synchronization, performance, or audit acceptance.
