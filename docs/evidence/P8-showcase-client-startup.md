# P8 RC Showcase Client Startup Smoke

Date: 2026-07-29 (historical RC client smoke: 18:18--18:19 local time)

## Scope

This is one controlled startup smoke for the local
`1.0.0-rc.1+26.1.2` Showcase client.  It proves only that the isolated
development client reached the render and initial resource-reload path.  It
is not visual acceptance, a binary-installer test, performance evidence, an
Iris/Sodium smoke, synchronization evidence, or a P8 Gate decision.

## RC identity and hash reference

At capture time, the generated authority for this local RC was
`D:\BlendLib\build\release\SHA256SUMS`.  Immediately before and after this
smoke, its two relevant entries were independently recomputed with
`Get-FileHash -Algorithm SHA256`:

```text
fbe65843b9adaf61f96946c6ea87bc4db85e6da21b12f85164782d1aeb54ad57  D:\BlendLib\build\release\blendlib-fabric-1.0.0-rc.1+26.1.2.jar
caa45a63e580c8f698a7fcf611b2e003d78953d6715d24572ba83447d4e43fd2  D:\BlendLib\build\release\blendlib-showcase-1.0.0-rc.1+26.1.2.jar
```

At capture time, `runClient` launched the workspace development classpath which
produced those RC artifacts; it does not claim that a packaged
Showcase JAR was installed by an external launcher. The separate Local Maven
consumer smoke covers packaged-RC resolution at its own capture time.

> **Historical capture boundary.** Subsequent local builds changed the current
> artifact hashes. The values above remain preserved as this client smoke's
> identity; they do not validate the newer binaries. See
> [P8 current artifact rebind](./P8-current-artifact-rebind.md) for the current
> local identity. That rebind is byte-integrity evidence only, not visual or
> client-startup proof; a new isolated client run is required before any current
> runtime claim or P8 Gate decision.

## Preflight and invocation

Before launch, no Java process whose command line contained both
`D:\BlendLib\blendlib-showcase` and `-Dfabric.dli.env=client` was present.
The one actual launch used Java 25 and the required isolated Gradle task:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat :blendlib-showcase:runClient --console=plain
```

Its runtime directory was:

```text
D:\BlendLib\blendlib-showcase\run\client
```

An attempted PTY launch was rejected by Windows before it created a Gradle or
Java process.  It was verified as a no-process preflight and is not counted as
an extra client launch.  The non-PTY invocation above is the sole actual
startup run.

## Observed startup evidence

The isolated `run\client\logs\latest.log` recorded:

```text
[18:18:58] [main/INFO] (FabricLoader/GameProvider) Loading Minecraft 26.1.2 with Fabric Loader 0.19.3
- blendlib 1.0.0-rc.1+26.1.2
- blendlib_showcase 1.0.0-rc.1+26.1.2
[18:19:07] [Render thread/INFO] (Minecraft) Environment: ...
[18:19:07] [Render thread/INFO] (Indigo) [Indigo] Registering Indigo renderer!
[18:19:09] [Render thread/INFO] (Minecraft) Reloading ResourceManager: vanilla, blendlib_showcase, ...
[18:19:11] [Render thread/INFO] (Minecraft) Created: ... minecraft:textures/atlas/items.png-atlas
blendlib_reload candidate_generation=1 active_generation=1 published=true stale=false models=4 missing=0 diagnostics=0
```

The last line was emitted by BlendLib's reload diagnostics reporter after the
resource reload.  A targeted log scan found no error, exception, or fatal
record attributed to `blendlib` or `blendlib_showcase`; the isolated
`run\client\crash-reports` directory contained `0` reports at inspection.

The following environment observations are retained rather than treated as
success evidence:

- Loom warned that the generated classpath referenced the absent
  `D:\BlendLib\blendlib-showcase\build\resources\client` directory.
- Offline/placeholder credentials produced Mojang user-properties and Realms
  authentication `401` errors.  They do not prove network health and occurred
  alongside, but did not prevent, the Render-thread and reload observations
  above.

## Controlled cleanup

After the reload evidence, the exact spawned Java child was revalidated:

```text
PID: 55348
Executable: D:\Program Files\Java\jdk-25.0.2\bin\java.exe
Command scope: D:\BlendLib\blendlib-showcase and -Dfabric.dli.env=client
Result: STOPPED_EXACT_ISOLATED_SHOWCASE_CLIENT_PID=55348
```

A post-stop query found no matching isolated Showcase client Java process.
The outer Loom task then exited with `-1` / `NTSTATUS 0xFFFFFFFF`, which is
expected because the validated Java child was deliberately stopped after the
limited startup observation.  It is not reported as a Gradle-build success or
as a client crash.  No formal server, formal world, remote target,
publication, or deployment was touched.

## Result and remaining work

Limited RC Showcase client startup smoke: **PASS**.

The following remain **WAITING** for real user-operated client evidence:

- static, rigid, and skinned model appearance;
- item/entity/block-entity interaction and transforms;
- animation, socket, resource-pack reload, and synchronization observations;
- Iris/Sodium visual compatibility and the P7 reference-scene performance
  result.

This record does not change P3--P7 audit/visual gates and does not declare P8
or a release PASS.
