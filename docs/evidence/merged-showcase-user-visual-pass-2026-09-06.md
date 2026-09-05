# Merged Showcase user visual acceptance — 2026-09-06

Status: **USER VISUAL PASS (bounded ordinary-Showcase scope)**

## Tested identity

- Source worktree: `D:\BlendLib-agentloop`
- Branch: `agentloop/blendlib-expansion`
- Tested source HEAD: `2dc81d56d1bb346cdce007e3fb0b8286ca03c4ed`
- Runtime: Minecraft `26.1.2`, Fabric Loader `0.19.3`, Java `25`
- Launch entry: `./gradlew.bat :blendlib-showcase:runClient --no-daemon --max-workers=1 --console=plain`
- Successful session: `2026-09-06 00:53:13` through normal Render-thread stop at `01:14:07`
- Session log: `blendlib-showcase/run/client/logs/latest.log`, 23,662 bytes,
  SHA-256 `17268c9c493ebc90f40e27a36e458af26d107f61005f5059f0419e7ef7180948`

The launched window was Loom's Showcase development client for the tested source tree. It exercised
the same compiled implementation and resources, but it was not an external Fabric installation of
the separately packaged release JAR.

## Build and startup evidence

Before the successful launch, the root `check buildRelease` invocation completed with
`BUILD SUCCESSFUL in 1m 10s` and 66 actionable tasks. Its runtime artifact was:

- `build/release/blendlib-fabric-1.0.0-alpha.1+26.1.2.jar`
- 2,591,967 bytes
- SHA-256 `c6f27a21ec767d4f8d123d40a096e51e925ed86c492abf7a0d240ee2454ab1a3`

The successful client log records all of the following:

- Minecraft 26.1.2 with Fabric Loader 0.19.3 and 55 loaded mods;
- BlendLib and Showcase `1.0.0-alpha.1+26.1.2`;
- LWJGL 3.4.1-snapshot, the optional OpenGL extensions, resource reload, and OpenAL startup;
- a later client command result of `generation=2 models=4 diagnostics=0`;
- `BlendLib diagnostics: none`;
- normal integrated-server save, all-dimension save completion, and Render-thread `Stopping!`.

The user exercised registered Showcase hosts during the same world session. The log records
summons for `p5_fallback_actor`, `static_rigid`, `rigid_pulse`, `animated_actor`,
`p7_benchmark_rigid`, and `p7_benchmark_skinned`, including a second `rigid_pulse` summon.

## User-owned visual result

After operating the client and reviewing the visible result, the user explicitly returned:

> PASS 视觉验证

That statement is the visual authority for this bounded result. It closes the ordinary merged-tree
Showcase entity visual check exercised in this session. Logs prove runtime identity, commands,
resource state, and clean shutdown; they do not substitute for the user's visual judgment.

## Retained warnings and boundaries

An earlier `00:46:59` launch failed before visual testing because the Mixin configuration claimed
the whole normal `client.reload` package. Commit `2dc81d5` isolated the Mixin package; the successful
session contains no Mixin transformation failure, `IllegalClassLoadError`, FATAL entry, or new crash
report. The older crash report remains historical failure evidence and is not represented as part of
the successful session.

The successful log contains three Mojang/Realms authentication or connectivity ERROR lines from the
offline Fabric development identity, plus a missing empty client-resource output-directory warning.
They did not interrupt local world operation, BlendLib reload, visual exercise, saving, or shutdown.

This PASS does **not** establish any of the following:

- an externally installed release-JAR client smoke;
- item or animated-block-entity visual acceptance not evidenced by this session's commands;
- P4's complete material matrix;
- two-client synchronization, disconnect/reconnect, or server-authoritative networking acceptance;
- P7/X7 benchmark validity, completed X7 metrics, GPU performance, JFR, or allocation targets;
- 20-reload leak evidence, Iris/Sodium compatibility, NeoForge, or Minecraft 26.2 acceptance;
- X1–X9 aggregate completion, P3–P8 aggregate Gate PASS, or a stable `1.0.0` release.

The session itself reported `blendlib x7 status=WAITING reason=NO_COMPLETED_X7_METRICS`; this remains
truthful and is not changed by the bounded visual PASS.
