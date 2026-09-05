# P3/P4/P5/P7 phase-specific isolated dedicated-server harness

## HISTORICAL NON-GATE DEFAULT-RUN RECORD

Earlier P3, P4, P5, and P7 notes that cited `:blendlib-showcase:runServer` or
`D:\BlendLib\blendlib-showcase\run\server` remain retained historical development
observations only. That generic profile uses the default `world` directory and is not a
phase-specific loopback harness; it is **not phase Gate evidence** for P3/P4/P5/P7.
This correction does not delete or reinterpret the recorded startup outcomes.

The contract tests are static and **do not launch Minecraft**. Each real smoke is a
separate dedicated-server observation and must be recorded in the phase ledger/evidence
before any Gate decision.

Current real-execution status:

- P3: completed on 2026-07-30; reached `Done (5.723s)!`, accepted console
  `stop`, saved all dimensions, and returned Gradle exit `0`. See
  `docs/evidence/P3-isolated-server-smoke-2026-07-30.md`.
- P4: completed on 2026-07-30; reached `Done (4.945s)!`, accepted console
  `stop`, saved all dimensions, and returned Gradle exit `0`. See
  `docs/evidence/P4-isolated-server-smoke-2026-07-30.md`.
- P5: completed on 2026-07-30; reached `Done (2.426s)!`, accepted console
  `stop`, saved all dimensions, and returned Gradle exit `0`. See
  `docs/evidence/P5-isolated-server-smoke-2026-07-30.md`.
- P7: completed on 2026-07-30; reached `Done (3.235s)!`, accepted console
  `stop`, saved all dimensions, and returned Gradle exit `0`. See
  `docs/evidence/P7-isolated-server-smoke-2026-07-30.md`.

## Local-only smoke procedure

Each task creates or verifies only its own run directory. It never writes the normal
`run/server` profile, any P6/P8 profile, `D:\MinecraftFabricServer-26.1.2`, or
`D:\MinecraftFabricServer-26.1.2-Fresh`. Run only one harness at a time after confirming
the listed loopback port is free. No formal server or world may be started, stopped,
modified, or deleted.

| Phase | Gradle task | Exact run directory | Loopback endpoint | Temporary world |
|---|---|---|---|---|
| P3 | `:blendlib-showcase:runP3SmokeServer` | `run/p3-smoke-server` | `127.0.0.1:25571` | `blendlib-p3-smoke-world` |
| P4 | `:blendlib-showcase:runP4SmokeServer` | `run/p4-smoke-server` | `127.0.0.1:25572` | `blendlib-p4-smoke-world` |
| P5 | `:blendlib-showcase:runP5SmokeServer` | `run/p5-smoke-server` | `127.0.0.1:25573` | `blendlib-p5-smoke-world` |
| P7 | `:blendlib-showcase:runP7SmokeServer` | `run/p7-smoke-server` | `127.0.0.1:25574` | `blendlib-p7-smoke-world` |

Every template fixes `server-ip=127.0.0.1`, `online-mode=false`,
`enforce-secure-profile=false`, `enable-rcon=false`, and disables whitelist enforcement.
The preparation task creates `eula.txt` only when absent or exact. For an existing
`server.properties`, it accepts Minecraft-generated extra keys but compares every template
key; any mismatch fails before writing and leaves the file unchanged.

Use Java 25 and the requested phase task, for example:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat --no-daemon --max-workers=1 :blendlib-showcase:runP3SmokeServer --console=plain
```

After a real server reaches `Done`, retain the exact isolated log/run-directory evidence,
perform only a verified graceful shutdown of that exact isolated process, and record the
result as a smoke observation. It never proves client visual, synchronization, reload, or
P7 performance requirements by itself.

## Static contract validation

On 2026-07-30, root ran the following Java-25/no-daemon/one-worker command;
it launched neither a Minecraft server nor a client:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 `
  "-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8" `
  :blendlib-showcase:test `
  --tests 'com.liy.blendlib.showcase.PhaseIsolatedServerSmokeRunContractsTest' `
  --rerun-tasks --console=plain
```

It completed successfully in 45 seconds with 17 executed tasks. The XML suite
recorded `4` tests, `0` failures, and `0` errors. Immediately after it completed,
ports `25571`--`25574` were not listening and the four phase run directories did
not exist. This validates the harness configuration only, not a server smoke or
any phase Gate.
