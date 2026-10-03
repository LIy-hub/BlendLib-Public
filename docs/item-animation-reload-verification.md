# Item animation reload safety verification

## Behavior and compatibility

The marker item extraction path checks for a declared state before compiling its duration.
Missing states yield unavailable status and the existing renderer fallback, with no broad catch
and no change to strict controller definition lookup. Successful samples remain historical and
are recorded only after extraction succeeds. Missing attempts do not normalize or stop playback.

The public observation record and playback controls keep their existing constructors/descriptors.
The addition is `BlendLibItemAnimations.extractionStatus(ItemStack)` and its immutable record/enums.
Status queries use the non-touching identity lookup. The consumer status command distinguishes
current controls, last successful sample and the latest attempted extraction/fallback.

## Reproducible checks

Java 25; official Minecraft 26.3 pins in `versions/modern/runtime-pins.properties`.

```sh
bash gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true build verifyRuntimeJar verifyRunnableExamples
bash gradlew :blendlib-fabric-client:test --tests '*ItemAnimation*Test' --tests '*BlendLibClientEntrypointLifecycleTest' --tests '*X7ResourceIslandBoundaryTest'
```

Final results and exact-commit CI evidence accompany the delivery package. These checks validate
headless runtime/API behavior and packaged consumer assets, not in-game rendering. The existing
GLX/Vulkan limitation is unchanged; no graphical launch was repeated. No merge, tag or release.

## Regression coverage and review

The ordinary item special renderer is exercised across 48 deterministic published-generation
cases: rigid/skinned × LOOP/ONCE/HOLD × playing/paused seek × removed state/no animation declaration/
missing model/unsupported concrete handle. Each case checks repeated fallback, unchanged requested
controls, stale historical samples, explicit fallback status, and restoration at a changed duration.
A fake clock is installed only in the retained test control; public facade and renderer run unchanged.
Tests also preserve strict definition exceptions and unrelated malformed-asset failures, validate
record invariants and exact exported JVM descriptors, and check non-touching status lookup/LRU.

Focused review found one secondary empty-extraction path could normalize playback before returning
unavailable. A private sample checkpoint now restores seconds, playing and clock anchor on empty
extraction. The reviewer confirmed the fix, and the unsupported-handle matrix cases regress it.
No broad catch was introduced.

The final official 26.3 build, runtime-JAR verification and packaged runnable-example verification
passed locally, with 109 tests and zero failures/errors/skips. The targeted root-module ABI/runtime
run and terminal remote CI results are recorded in the delivery evidence.
