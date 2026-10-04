# Native cubic profile verification

October 4, 2026; `feat/mc26.3-native-cubic-profile`, cumulative batch 27.
Build identity, exact remote head, source tree, artifact hashes and terminal CI are
recorded in the delivered package's `BUILD_IDENTITY.json` and `evidence/`.

## Verified locally

- Core: 383 tests pass, including 14 new native cubic tests
- Client: 909 tests pass, including 7 new cubic runtime/reload cases
- Pure API: 354 tests; API consumer: 7; common: 21; Fabric consumer: 8, all pass
- Official Minecraft 26.3: 478 tests pass; `build verifyRuntimeJar verifyRunnableExamples` passes
- Separate runtime, sources and runnable-example JARs all identify as `1.0.0-beta.4+26.3-cubic-preview`
- Blender add-on extension validation and ZIP packaging pass; 51 Python tests pass
- Genuine Blender 5.1.2 sparse native export: three CUBICSPLINE channels, three source keys each
- Blender independent scene-to-export oracle: 14 key/non-key times, fractional FPS,
  LINEAR/STEP, source/cardinality, fallback reasons and deterministic repeated exports
- Independent Blender-to-Java test: 28 world bone matrices and 1,008 CPU-skinned vertices;
  maximum matrix component error 2.98e-7, vertex component error 5.96e-7 (tolerance 2e-5)
- Legacy default static/rigid/skinned GLB and descriptor byte comparisons pass;
  frozen v1 schema and pure public API sources remain unchanged
- Packaged consumer exercises native cubic, state transitions, events, sockets,
  appearance, independent actors, frozen captures and stale-generation/lifecycle rules

The root aggregate still has the previously known sandbox-specific
`X7ArtifactVerifierTest.symlinkEscapeFailsClosedWhenTheFilesystemPermitsPortableSymlinks`
diagnostic-reason mismatch (the unsafe artifact is rejected) and one host-dependent
skip. It is not weakened or relabeled a local aggregate pass. Remote root CI is
checked independently on the exact published commit.

## Focused review and fixes

One critical review found two exporter eligibility bugs: connected-child location
is ignored by Blender, and nonconstant extrapolation can extend outside a channel's
keys. Both now use explicit baked fallback. The durable real-Blender edge-case
script verifies the reasons and evaluated motion; maximum subframe baked errors
are about 0.0026 and 0.0025 respectively, with no exactness claim. Pure tests cover
both eligibility gates. The reviewed allocation, quaternion, scale, bounds and
strict-v1 branches had no additional material blocker.

## Commands

```sh
bash gradlew check --continue
python -B -m unittest discover -s blender-addon/tests -v
blender --background --python-exit-code 1 --python blender-addon/scripts/create_native_cubic_fixture.py -- --project-root .
blender --background --python-exit-code 1 --python blender-addon/scripts/verify_native_cubic_edge_cases.py -- --project-root .
bash gradlew -Pblender_executable=/path/to/blender packageBlenderAddon
bash gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true -Pblendlib_preview=cubic-preview build verifyRuntimeJar verifyRunnableExamples
```

Native Minecraft screenshots, visible motion/GPU acceptance and author judgment
remain deferred. No main merge, tag, release, CurseForge upload, EULA or security
settings change is part of this batch. X9 and morph targets remain deferred.
