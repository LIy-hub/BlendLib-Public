# Locomotion rule verification

Scope: optional versioned sidecar parsing, reload preparation/publication, typed extraction inputs,
standard entity-layer controller integration and a packaged runnable idle/walk/run consumer.
This is a cumulative development branch, not a release or native graphics acceptance.

## Behavior covered

- Strict schema/UTF-8/duplicate and unknown fields, bytes/depth/collection bounds, target/default
  existence, continuous-loop/no-next requirement, finite thresholds and input-type consistency
- Ordered priority, inclusive entry/exit thresholds, min/max hysteresis, jitter, default and
  unconditional rules, optional selected-state hold, backwards clocks, same-target rule changes
- Immutable typed snapshots; incomplete, wrong-type, null and non-finite captures preserve
  the last command/initial layer with one warning per model/generation
- Stable immutable command/sequence replay with continued actual playback, independent upper
  commands/cues, sequence exclusivity and unmodified fallback when sidecars are absent/invalid
- Real resource-manager priority, exact bounded reads, immutable same-generation publication,
  stale prepare/apply, invalidation/removal/recovery and successful-backend filtering
- Exact owner/source identity, multiple entities, reused IDs, model/generation/connection changes,
  unload, explicit retirement, repeated play init and disconnect; callbacks never recreate a
  stale frame after lifecycle mutation and recursive capture fails descriptively
- Additive public builder/input/runtime signatures and retained overloads, plus a compiled
  ordinary consumer sample. Existing strict-v1 descriptor and X6 prepare-time rules are unchanged

## Actual packaged consumer

The optional consumer ships its own locomotion descriptor, sidecar and GLB with a genuinely
new authored run clip. Existing clips/default assets are preserved. The explicitly tagged
example actor moves through a small collision-resolved idle/walk/run sequence; the extraction
callback reads measured horizontal speed and grounded state carried by ordinary entity data.
Rules only select visual state. The existing independent upper-layer action remains a cue.

`verifyRunnableExamples` reads the JAR's actual resources with a real `ResourceManager`, runs
reload prepare/apply, then feeds captured rule commands into `SkinnedAnimationRuntime.extractLayered`.
It compares actual root poses and CPU-skinned vertices for idle/walk/run, proves stable input
keeps the playhead advancing, exercises thresholds/hold and independent upper sequences/owners,
and checks there is no resource access during extraction. Real resource replacement disables,
removes and restores rules without breaking the base loaded model. Unload, retirement and
reconnect are exercised. All earlier packaged consumer verifications also remain selected.

## Independent review and regression fixes

The focused review found three lifetime edge cases, all repaired and regression-covered:

1. Returning empty commands after a callback disconnected/reloaded could still let the entity
   factory extract a stale frame. A generation-aware lifecycle fence now abandons the whole
   frame before any controller rebind, and tests assert no clock/instance resurrection
2. Rebinding an owner/source before command-conflict validation could erase prior playback even
   though the new request failed. Replacement is now staged until both callbacks and checks pass
3. Staged replacement could remove an upper cue that had just been captured. Cleanup now retains
   the exact new immutable cue command while pruning old bindings, preserving repeated-sequence
   identity and independent upper playback. Legitimate replacement does not drop its first frame

The reviewer rechecked all repairs and an independent actual cue-cache identity/cleanup probe.
No remaining blocking correctness issue was identified in that focused pass. This does not
claim exhaustive security, concurrency, hardware or in-game visual review.

## Local test results

- Official Minecraft 26.3 full suite: 345 tests passed, zero failures/errors/skips
- Focused locomotion suite: 57 tests passed, zero failures/errors/skips
- Official 26.3 build, runtime-JAR validation and all packaged runnable consumer checks passed
- Root client: 804 tests passed; core: 346 passed. Only the inherited showcase Linux symlink
  assertion fails in the root aggregate (one additional inherited showcase test is skipped)
- The retained ABI manifest stays byte-for-byte unchanged; new descriptors are explicitly
  pinned and removed from only its comparison view before exact old-surface verification

## Commands and evidence

```sh
bash gradlew -p versions/modern -Pminecraft_version=26.3 test --tests '*Locomotion*'
bash gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true \
  build verifyRuntimeJar verifyRunnableExamples
bash gradlew check --continue
```

Exact final counts, source tree, content-equivalent local/remote commits, JAR hashes and
workflow/job outcomes are in the cumulative package's `BUILD_IDENTITY.json` and `evidence/`.
The official 26.3 suite explicitly selects the new parser/reload/runtime/factory/ABI tests and
consumer source. All 15 compatibility matrix targets, normal Build, and focused 26.3 workflow
must finish successfully on the implementation commit; plan-only CI is not substituted.

Local sandbox execution uses the inherited selector-provider shim and quiet test-JVM wrapper;
GitHub CI uses normal JDK/Gradle/Loom. The inherited root Linux showcase symlink-policy assertion
is retained and reported separately; it is not weakened or represented as a feature success.

## Remaining boundaries

No native graphics/world/window result, multiplayer timing, terrain movement acceptance,
hardware performance, Iris/Sodium, experimental GPU or release claim. No general expression
language, server-authoritative rules, arbitrary action engine, new animation packet, Blender
UI/export tooling, merge, tag, release, CurseForge upload, EULA or security changes.
