# Animated item observation verification (26.3)

## Scope

Additive `BlendLibItemAnimations.observe(ItemStack)` and immutable `ItemAnimationObservation`
with historical `Sample` metadata. Existing controls and weak 256-entry identity/LRU semantics
remain intact. The opt-in runnable consumer adds `/blendlib_example item status`; production
registers no new command. No persistent item identity, protocol, or playback rewrite is added.

The registry's observation path is a bounded weak-identity scan: using `LinkedHashMap.get`
would refresh access order, and calling the existing playback API would create retained state.
It deliberately neither purges collected keys nor invokes retirement callbacks. Successful
extraction records only semantic model/state keys, generation, clip seconds and duration.
Queries allocate immutable return values but do not read the animation clock or drive playback.

## Reproducible verification

Use Java 25 and the official runtime pins from `versions/modern/runtime-pins.properties`:

```sh
bash gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true build verifyRuntimeJar verifyRunnableExamples
bash gradlew :blendlib-fabric-client:test --tests '*X7ResourceIslandBoundaryTest'
```

The dedicated item tests cover no clock reads or LRU refresh, missing identities without
creation, exact-object sharing versus equal copies, bounded eviction and release/clear,
paused seeks beyond duration, current controls versus last successful sample, repeated
sampling, immutable observations and generation staleness. The official-26.3 integration uses
real ItemStacks and the renderer extraction path, including registry publication before runtime
cleanup, missing-model empty extraction, copied stacks, public 256-entry LRU eviction and the
production disconnect operations. The subsequent item-animation-reload safety slice replaces the previous undeclared-state
exception with explicit unavailable extraction status and the ordinary safe renderer fallback;
see item-animation-reload-plan.md and item-animation-reload-verification.md. The packaged formatter verifier
covers absent/unsampled/stale states, locale-stable numbers, repeated reads and immutable lines.
The ABI checks pin the additive public surface while retaining prior descriptor assertions.

A focused review found no functional issue. Its eviction wording correction was applied:
observation does not restart an evicted stack; only extraction/playback acquisition does.

## Verification boundary

Headless compilation/tests and JAR checks establish executable API and packaged consumer
contracts, not an in-game visual or command UI pass. No graphical run was repeated: the known
GLX/Vulkan limitation of this host is unchanged. No main merge, tag or release is part of this
slice. Exact commit mapping, test logs and terminal CI results accompany the delivery bundle.
