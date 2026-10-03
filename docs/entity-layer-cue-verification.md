# Entity-layer cue adapter verification — Minecraft 26.3

## Scope

Additive `BlendEntityLayerCue`, `BlendEntityLayerCues`,
`BlendEntityLayerCommands.fromCues` and `animationLayerCues` builder entry point.
The runnable consumer now supplies sequence/start tick/speed and delegates immutable
command capture plus lifecycle cleanup to the existing animation runtime.
The explicit command API, wire protocol and library/example JAR separation are unchanged.

## Verified locally

Command (Java 25, Minecraft 26.3, Fabric API 0.161.0+26.3, Loader 0.19.5):

```sh
bash gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true \
  --no-daemon --max-workers=2 build verifyRuntimeJar verifyRunnableExamples
```

Result: **PASS, 92 tests, zero failures/errors/skips**. Runtime and opt-in example JAR checks
and strict packaged actor/wand/marker asset composition checks passed.

New regressions cover:

- Immutable same-sequence repeats and first-capture-wins cue semantics
- Stale sequence suppression without losing the latest watermark; empty-list continuation
- Client partial tick, command speed and descriptor-state speed in clip-local catch-up
- Independent controller sequences, owner object identity and source identity
- Reload with changed descriptor speed and same-sequence recapture into a fresh evaluator
- Unload/retracking, exact-key retirement, disconnect, repeated init and late teardown
- Invalid sequence/rate/clock and duplicate-controller batch rejection

An independent read-only integration review found and closed two issues before final verification:
descriptor-state speed was missing from initial catch-up, and packaged-example inventory still
required the removed private helper. Final review: **no remaining blocker**.

## Boundaries

The cue contract freezes the first command for a sequence; change the sequence when changing
timing, animation or speed. The lower-level explicit command API retains its conflict diagnostics.
Catch-up is clip-local, not historical next-state/blend replay, and future ticks clamp to zero.
No new network synchronization protocol is claimed.

Native in-game visual verification was not repeated. The prior batch established that the cloud
machine lacks a supported GLX/Vulkan presentation backend. Compilation, extraction tests and
packaged asset checks do not constitute native visual acceptance. No EULA or system-security
settings were changed. This branch is not a release, merge, tag or CurseForge publication.
