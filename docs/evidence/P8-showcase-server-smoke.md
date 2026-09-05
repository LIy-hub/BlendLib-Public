# P8 RC Showcase Historical Default-Run Server Record

Date: 2026-07-29 (historical RC server smoke: 17:58 local time)

## Scope and RC identity

This is a historical local default-run startup record for the
`1.0.0-rc.1+26.1.2` Showcase wiring. Although its run directory was local,
the then-default `runServer` configuration bound `*:25565` and used generic
`world`; it does **not** satisfy the required phase-specific isolation
contract. It is not a valid dedicated-server smoke, client-visual evidence,
performance result, release decision, or P8 Gate decision.

The generated identity authority is `D:\BlendLib\build\release\SHA256SUMS`.
For the RC used by this smoke it records, and an independent `Get-FileHash`
recomputed:

```text
fbe65843b9adaf61f96946c6ea87bc4db85e6da21b12f85164782d1aeb54ad57  D:\BlendLib\build\release\blendlib-fabric-1.0.0-rc.1+26.1.2.jar
caa45a63e580c8f698a7fcf611b2e003d78953d6715d24572ba83447d4e43fd2  D:\BlendLib\build\release\blendlib-showcase-1.0.0-rc.1+26.1.2.jar
```

The Gradle development run used the workspace wiring that produced those
artifacts at capture time. The separate local-Maven consumer evidence covers
resolution of a packaged RC runtime at its own capture time.

> **Historical capture boundary.** Subsequent local builds changed the current
> artifact hashes. The values above remain preserved as this smoke's identity;
> they do not validate the newer binaries. See
> [P8 current artifact rebind](./P8-current-artifact-rebind.md) for the current
> local identity. That rebind is byte-integrity evidence only, not a replacement
> server smoke; a new isolated server run is still required before any current
> runtime claim or P8 Gate decision.

## Historical invocation

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat :blendlib-showcase:runServer --console=plain
```

The local default run directory was:

```text
D:\BlendLib\blendlib-showcase\run\server
```

No other `D:\BlendLib\blendlib-showcase` server Java process was present at
preflight.

## Observed startup evidence

`logs\latest.log` recorded:

```text
[17:58:27] [main/INFO] (FabricLoader/GameProvider) Loading Minecraft 26.1.2 with Fabric Loader 0.19.3
- blendlib 1.0.0-rc.1+26.1.2
- blendlib_showcase 1.0.0-rc.1+26.1.2
[17:58:38] [Server thread/INFO] (Minecraft) Done (0.429s)! For help, type "help"
[17:58:38] [Server thread/INFO] (Minecraft) ThreadedAnvilChunkStorage: All dimensions are saved
```

The server logged saves for overworld, nether, and end immediately after
`Done`.  The local `crash-reports` directory contained `0` reports, and a
targeted scan found no error, exception, or fatal record attributed to
`blendlib` or `blendlib_showcase`.

## Controlled cleanup

After `Done`, the exact child was revalidated before termination:

```text
PID: 61072
Executable: D:\Program Files\Java\jdk-25.0.2\bin\java.exe
Command scope: D:\BlendLib\blendlib-showcase and -Dfabric.dli.env=server
Result: STOPPED_EXACT_SHOWCASE_SERVER_PID=61072 (historical default-run; non-gate)
```

The outer Loom task subsequently reported exit `-1` / `NTSTATUS 0xFFFFFFFF`.
That is expected because the validated Java child was deliberately stopped
after successful startup; it is not a startup failure or a successful Gradle
build result.  No official server, formal world, remote target, publication,
or deployment was touched.

## Result and remaining work

Historical default-run startup reached `Done`: **NON-GATE / not isolated**.

P8 remains pending a fresh phase-specific isolated server run for the current
rebuilt artifacts, complete release checks, the user-managed audit, and
real-client evidence. This record does not claim a visual, Iris/Sodium,
performance, synchronization, or final release Gate pass.
