# Runtime inspection utility (Minecraft 26.3)

## Plan and existing surfaces

Asset diagnostics already provide `/blendlib assets`, `inspect` and `diagnostics`, but no
instance playhead view. `SkinnedAnimationRuntime.layeredSnapshot` already provides immutable
controller state, time, sequence, transition and bounded-work diagnostics. The separate
runnable example already has real item playback controls and extraction-owned final sockets
and procedural components. Replacing these APIs or adding another retained state registry would
be redundant.

This slice adds bounded nearby-actor ID discovery, an explicitly targeted example-actor
inspection command and a copyable pure
formatter. It reuses the existing layered publication and example layer declarations. There is
no reflection, new mutable control surface, automatic logging, new cache, or production command.
Configured weight/mask is distinguished from sampled state and from final procedural influence.

## Lifecycle correction

The existing layered snapshot getter now checks its clock generation against the currently
published model registry. During the interval between reload publication and the next extraction
cleanup, it returns empty rather than returning retired-generation data. It still performs no
cleanup, instance allocation, advancement, or event dispatch. Its JVM descriptor is unchanged;
all existing ABI assertions remain in place with no allowlist expansion.

## Verification

Run with Java 25:

```sh
./gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true check verifyRunnableExamples
```

- Runtime regression: repeated inspection preserves object identity and metrics; unknown keys
  do not allocate; registry reload before extraction cleanup hides old data; new extraction
  restores availability; unload/disconnect clear it; previously captured snapshots stay immutable
- Packaged consumer verification: empty/sorted/unique/bounded actor discovery output; configured full/named masks, sampled clip-local time and
  accepted sequence, previous state/transition, locale-independent output, immutable returned
  lines, absent playheads, visible and capped diagnostics
- Actual 26.3 compilation verifies the Fabric client command and Minecraft target accessors;
  JAR checks require both new consumer classes and preserve production/example separation

See [runnable example instructions](../versions/modern/showcase/README.md#inspect-a-sampled-entity-without-changing-playback).
No native graphical launch was repeated for this slice; the previously recorded graphics
limitation remains. Builds and headless tests do not establish manual command or visual PASS.
