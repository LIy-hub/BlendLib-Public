# P5 canonical runtime golden tests

Status: implementation evidence only. This document does not mark P5, P4, or P3 as passed.

## Scope

`CanonicalP2RuntimeGoldenTest` loads the original BlendLib-owned P2 runtime
outputs through `ModelAssetLoader`, then exercises the P5 pure-core runtime:

- `rigid_model`: fixed-time `rigid_pulse` sampling, local pose and rigid node
  palette output.
- `skinned_model`: fixed-time `skinned_wave` STEP/LINEAR/STEP sampling, local
  pose, one-joint skin palette, prepared geometry, and CPU skinning output.
- Both paths assert finite transforms, matrices, positions, and normals.

The checked-in `canonical-runtime-golden.properties` records expected sampled
values and SHA-256 values only. It contains no copied GLB, texture, Blender,
FBX, or OBJ data. The test reads the canonical runtime descriptor and GLB from
`blendlib-showcase/src/main/resources/assets/blendlib_showcase`.

## Provenance

| Fixture | Descriptor SHA-256 | GLB SHA-256 | P2 record |
|---|---|---|---|
| rigid | `d5ef584999ed54ce17b798e85d0e31b62b85db89bc4ee5f820c46806a5a8852d` | `a2b7f063c8806f3e3eceec2533ed8fe84c91f967ac71c25ca59faf4349a21764` | `test-assets/rigid/golden/sha256.json` |
| skinned | `1a2b5c3b38218df1d5f95e1d65efe90ec89edccfe399fd6ce5ac2415fc42d012` | `fe780ca51db01961bf492279ccd46cebc55688cc1ad64e67ce98a87596948db9` | `test-assets/skinned/golden/sha256.json` |

The test recomputes both file hashes and compares them against the independent
P2 records before loading either asset.

## Fixed-time assertions

- Rigid: at `0.5 s`, `RigidArm` (node `0`) has the exported translation
  `[0.1499440, 1.0, 0.0]` in the channel, local pose, and known canonical
  palette hierarchy.
- Skinned: at `0.5 s`, `SkinnedBone` (node `0`) has the exported rotation
  `[0.0, 0.0, 0.1196677, 0.9928140]`. The one-joint palette and three-vertex
  CPU-skinned output are compared with a `1e-5` tolerance.

These assertions apply only to the two known P2 default-scene fixtures. They
do not themselves implement ADR-015's accepted canonical active-hierarchy or
combined-load rejection policy.

## Validation

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat :blendlib-core:test --console=plain
git diff --check
```

Result: both commands completed successfully on 2026-07-29. The core test
suite includes the three canonical runtime golden assertions.
