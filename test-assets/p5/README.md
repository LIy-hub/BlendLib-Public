# BlendLib P5 Fixture Preparation

This directory binds P5 pure-core tests to the already frozen, BlendLib-owned
P2 `rigid` and `skinned` fixtures. It deliberately contains no copied GLB,
glTF, Blender, image, Khronos, LiyMod, or runtime asset bytes.

`manifest.json` records the canonical source, generated runtime target, P2
golden hash, and directly observed GLB node/skin/clip timing identity.
`golden-scenarios.json` records P5 test inputs and expected relationships.
Neither file is a P5 Gate result.

Run the no-dependency provenance check from the repository root:

```powershell
python -B test-assets/p5/verify_p5_fixtures.py --project-root D:\BlendLib
```

The verifier rejects malformed or duplicate-key JSON, missing references,
hash drift from the P2 golden records, structural drift between the export
report and frozen golden, source-observation drift in the runtime GLB, any
third-party reference, and copied model/image assets in this directory.

## What is ready

- Rigid source identity: `RigidArm` and `RigidBase` are the two mesh-node
  palette targets; `rigid_pulse` has 20 LINEAR translation samples.
- Skinned source identity: `SkinnedBone` is the only frozen skin joint;
  `skinned_wave` has verified STEP translation/scale and LINEAR rotation
  timing.
- Two-instance isolation and generation/cache lifecycle scenarios have
  deterministic contract inputs without conflating immutable asset sharing
  with mutable controller state.

## Explicit source gaps

- The frozen rigid fixture has no attack clip. It must not be relabelled as
  an attack or assigned an invented duration.
- The frozen skinned fixture has exactly one joint, not the two-joint source
  required for a two-joint skinning golden.
- Neither frozen descriptor declares `sockets`, and neither source declares
  visual events.
- P2 does not contain sampled pose matrices or post-skin vertex values.

Those entries are intentionally marked `MISSING_CANONICAL_SOURCE`,
`BLOCKED_CANONICAL_*`, or `PENDING_CORE_SAMPLING`. A later P5 core test may
generate exact output goldens only from a new strict BlendLib-owned source;
it must not infer them from another project or fabricate values.
