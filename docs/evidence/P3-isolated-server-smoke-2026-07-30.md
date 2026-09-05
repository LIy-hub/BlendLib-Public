# P3 isolated dedicated-server smoke — 2026-07-30

Status: dedicated-server smoke **PASS** for the current P3 candidate. P3 itself
remains `REVIEW`; this evidence does not replace the user-managed audit or
promote the phase Gate.

## Isolation preflight

Before launch, `D:\BlendLib\blendlib-showcase\run\p3-smoke-server` did not
exist and no process listened on TCP port `25571`. The checked-in template
fixed all of the following values:

```text
server-ip=127.0.0.1
server-port=25571
online-mode=false
enforce-secure-profile=false
enable-rcon=false
white-list=false
enforce-whitelist=false
level-name=blendlib-p3-smoke-world
```

One preliminary wrapper invocation was rejected during Gradle task selection
because Windows quoting split an optional JVM argument. It executed no task,
created no run directory, and started no Minecraft process. The corrected
invocation below is the single real P3 server launch.

## Executed command and result

The launch used Java 25, a D:-resident Gradle user home and temporary
directory, one Gradle worker, a 512 MiB Gradle daemon cap, and
`JAVA_TOOL_OPTIONS=-Xms256m -Xmx1024m` for the isolated server. The heap cap is
an environment bound for startup safety; it changes no BlendLib format, API,
diagnostic, or server acceptance condition.

```powershell
.\gradlew.bat --no-daemon --max-workers=1 `
  -Dorg.gradle.jvmargs=-Xmx512m `
  :blendlib-showcase:runP3SmokeServer --console=plain
```

The retained log proves:

- Minecraft `26.1.2`, Fabric Loader `0.19.3`, Fabric API
  `0.154.2+26.1.2`, and Java `25` loaded with `blendlib` and
  `blendlib_showcase`.
- The server bound only `127.0.0.1:25571` and prepared only
  `blendlib-p3-smoke-world`.
- It reached `Done (5.723s)!`.
- After `Done` was observed, the coordinator sent `stop` through the same
  process's standard input. The server saved overworld, Nether, and End, logged
  `All dimensions are saved`, and Gradle exited `0` with
  `BUILD SUCCESSFUL in 53s` (`18` actionable tasks; `2` executed).

The retained `latest.log` contains no `ERROR`, `Exception`,
`NoClassDefFoundError`, or `ClassNotFoundException` marker. No crash-report
directory was created.

## Retained identities and post-run state

| Artifact | SHA-256 |
| --- | --- |
| `blendlib-showcase/run/p3-smoke-server/logs/latest.log` | `A1F486F04261008DAC7ECB34F51D41E1E29ADB132C9BD2395EC3116AA01A3623` |
| `blendlib-showcase/run/p3-smoke-server/server.properties` | `206703A0CEB1998BCE62F4E4448DE3A087E50D3AA88ED71A0EAB23222BAD8AAE` |
| `blendlib-showcase/run/p3-smoke-server/eula.txt` | `EE27072E4A23E088522F740DDAAB0C7C4145C186969E90A86254FAA3A5EC5CE6` |

After shutdown, port `25571` was free and neither wrapper PID remained. No
P3 server process remained active. Neither
`D:\MinecraftFabricServer-26.1.2` nor
`D:\MinecraftFabricServer-26.1.2-Fresh`, any formal world, the default
Showcase `run/server`, or another phase harness was started, stopped, or
modified.

This is server startup/class-boundary evidence only. It is not client visual,
resource-reload, animation, synchronization, performance, publication, or P3
audit evidence.
