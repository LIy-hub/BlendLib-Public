# P6 implementation evidence

Date: 2026-07-29 (corrected 2026-07-30)  
Status: implementation and limited runtime smoke complete; Gate remains `WAITING`.

## Scope implemented

- Common/server owns semantic-only animation state: key, start game tick,
  monotonic sequence, speed, seed, and persistence. Packets carry no GLB,
  model, matrix, or render state.
- `BlendAnimations` is the public server facade. Its entity and block-entity
  operations retain persistent and transient state separately, replay the
  current state when an entity begins tracking, observe block entities on the
  server tick, and clean state/observers on entity unload, world unload, server
  stop, and player disconnect.
- Client receivers execute on the client executor and write a
  session/dimension-scoped store. Unknown targets are bounded to 256 entries
  with a 20-tick expiry; unload, disconnect, and dimension reset remove state.
- The public entity and block-entity adapters convert synchronized semantic
  state into extraction-time animation input. `submit` still consumes immutable
  snapshots only. No common/server class imports a client class.
- Showcase exercises both paths: its animated actor makes a server-authoritative
  semantic transient attack trigger, and its altar block entity receives a
  persistent idle state. The public consumer fixture compiles using public API
  plus `BlendAnimations` only.

## Automated evidence

With `JAVA_HOME=C:\Program Files\Java\latest\jdk-25`:

```powershell
.\gradlew.bat :blendlib-fabric-common:test :blendlib-fabric-client:test --no-daemon --console=plain
.\gradlew.bat clean check --console=plain
.\gradlew.bat :blendlib-core:test --console=plain
.\gradlew.bat buildRelease --console=plain
.\gradlew.bat :blendlib-fabric-client:compileClientJava :blendlib-showcase:compileJava :blendlib-showcase:compileClientJava --rerun-tasks --no-build-cache --console=plain
git diff --check
```

All commands succeeded. The final full check reported 43 actionable tasks
(21 executed, 22 cached); `buildRelease` reported 37 tasks (5 executed,
32 up-to-date). Focused tests also covered payload bounds/codecs, monotonic
ordering, persistence, tracking replay, unknown-target expiry, unload and
disconnect cleanup, dimension reset, public API/source boundaries, typed block
entity binding, Showcase synchronization, and the API-only consumer fixture.

## Runtime evidence and isolation correction

> **2026-07-30 correction.** The historical `:blendlib-showcase:runServer`
> record below used the default `run/server` configuration, which bound
> `*:25565` and used the generic `world` name. It is a historical local
> default-run observation, not phase-specific isolated dedicated-server Gate
> evidence. It must not be used for a P6 PASS.

- The historical `:blendlib-showcase:runServer` reached `Done (0.498s)!` in
  `D:\BlendLib\blendlib-showcase\run\server` and saved all dimensions. Only
  the exact verified Showcase server JVM (PID 32632) was stopped afterward.
- The purpose-built `:blendlib-showcase:runP6SyncServer` subsequently reached
  `Done` and saved all dimensions in `run/p6-sync-server`, binding only
  `127.0.0.1:25575`. Its exact verified JVM (PID 32104) was stopped after the
  save completed. This is valid limited isolated startup evidence only; it
  does not prove the required two-client synchronization behavior.
- `:blendlib-showcase:runClient` reached the real Render thread, Indigo,
  LWJGL, OpenAL, and resource reload in the isolated Showcase client run
  directory. Reload reported generation 1, four models, zero missing models,
  and zero diagnostics. After the altar blockstate/model repair, its log no
  longer contained the prior animated-altar missing-model warning; no client
  crash-report directory existed. Only the exact verified isolated client JVM
  (PID 54968) was stopped afterward.

The observed offline-account/Realms 401 messages and the Loom development
classpath warning about `build/resources/client` are not BlendLib failures.
They are retained as environment observations only.

## Gate limitation and manual evidence still required

No visual, two-client synchronization, or production-world claim is made.
Before P6 can be considered for a Gate PASS, the user-managed isolated test
must retain screenshots/video and logs proving:

1. server trigger reaches two tracking clients and late tracking gets current
   persistent state;
2. a newer sequence replaces an older one, transient state expires, and state
   clears on disconnect/dimension change;
3. actor and altar render correctly and animate without missing diagnostics;
4. no formal server, formal world, push, publication, or deployment was used.
