# Animation capabilities: 26.3 verification

Baseline: `61620626a0c33cd7142bcb7a8e356854290a4eb5` (`mc/26.3`).
Branch: `feature/mc26.3-animation-capabilities`. Verified 2026-10-03.
This is a local review build; no remote push, release or merge was performed.

## Passed

- Minecraft 26.3 main/client compilation against official Minecraft/Fabric dependencies
- Target `check`: **75 tests**, zero failures/errors/skips, including **25** extraction-runtime tests
- Target `verifyRuntimeJar`: correct packaged runtime, version pins and required class checks
- Separate compilation of `LayeredAnimationConsumerSample` and `AnimatedItemConsumerExample`
  against the packaged JAR and dependency JARs only; no module classes directories on the classpath
- Full pure-Java core suite: **294 tests**, zero failures/errors/skips, compiled/run with Java 25
- `git diff --check`

The 75 target tests and 294 core tests overlap; these are not 369 distinct tests.
Core checks include exact-wrap arithmetic, native STEP/LINEAR sampling, sparse node mapping,
mask validation and synchronized event boundaries. Target checks cover layered final socket poses,
additive/override masking, transitions, duplicate extraction, instance/generation/unload isolation,
procedural behavior, attachments, item controls/retention, and 6,000 controlled clip switches.
The switch regression ensures time-zero events cannot accumulate and overflow during item playback.

Runtime JAR: `blendlib-fabric-1.0.0-beta.3+26.3.jar`

SHA-256: `4ca1d0b6e86bf21ee06b897dee96f44239b7fcdebadab5c0f0d49d654028d8b6`

## Reproduce in a normal Java 25 environment

```sh
bash gradlew -p versions/modern -Pminecraft_version=26.3 --no-daemon --max-workers=2 check verifyRuntimeJar
```

The selected feature tests and compiled examples are now included in the 26.3 test source set.
The older root multi-module build targets 26.1.2 and is not the shipped 26.3 artifact route.
26.3 aggregates API/core/common/client classes directly in its outer JAR, so the new public
client signatures are available to packaged-JAR consumers. No older nested-publication guarantee
is made for these new version-scoped APIs.

## Execution-environment notes

The cloud runner required its current per-execution HTTP proxy, the installed CA trust store,
and an environment-only Java selector provider that reports unavailable Unix sockets as
unsupported. It delegates supported networking unchanged; it does not bypass the sandbox,
weaken TLS, alter product sources, or alter Minecraft/Loom version pins. Normal environments
with Unix-domain sockets do not need this fallback. Early CPU-only tests used temporary fail-fast
Minecraft type stubs; those are superseded by the final official-classpath target run above and
are not included in the source or artifact.

## Not run / remaining visual acceptance

No native Minecraft client session or GPU visual acceptance was run. Before using this review
build in a production mod, check a real animated asset in-game:

1. Walk/fly plus a masked attack; ensure transitions and unaffected bones look correct
2. Look-at, chain-follow, spring and limits through pause, teleport, unload and resource reload
3. Socket orientation and attached model under root yaw/pitch/roll and differing authored units
4. Synced sound/particle markers during repeats, lag/culling gaps, reconnect and new action sequences
5. Animated item in GUI, both hands, third person and dropped form; verify stack-copy identity behavior

Only 26.3 is covered by this rollout. The new APIs remain opt-in; static registrations preserve
existing behavior. Layer-specific event tracks/network replication, persistent item identity,
nested attachment rendering, automatic attachment culling expansion and advanced foot IK remain
outside this first implementation, as documented in the feature guides.
