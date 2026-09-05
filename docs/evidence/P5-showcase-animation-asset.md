# P5 Showcase Animation Asset Preparation

Status: implementation evidence only. This record does not claim a P5 Gate,
runtime wiring, or visual acceptance. ADR-015 is accepted, but this asset
record alone does not implement its runtime policy.

## Scope

This package adds a BlendLib-owned, reproducible authoring source for the P5
Showcase `idle`/`walk`/`attack` demonstration. It does not alter Java, Gradle,
the existing P2 exporter, schemas, loader semantics, the phase ledger, or any
ADR.

The new runtime-facing files are limited to:

```text
blendlib-showcase/src/main/resources/assets/blendlib_showcase/
├─ blend_models/showcase_animation/showcase_actor.json
├─ models3d/showcase_animation/showcase_actor.glb
└─ textures/blendlib/showcase_animation/showcase_actor__showcaseanimationsurface.png
```

The tracked authoring source, its deterministic source generator, export
wrapper, verification script, export report, and golden records live only in
`test-assets/showcase-animation/`.

## Asset contract

- Profile: `blendlib:skinned_v1`.
- Active default-scene root: exactly `ShowcaseAnimationRoot`.
- Skeleton: `ShowcaseRootBone` with child `ShowcaseTipBone`.
- Mesh: one four-vertex, six-index skinned primitive with `JOINTS_0` and
  `WEIGHTS_0`.
- GLB animation names: `attack`, `idle`, `walk`.
- All exported sampler interpolation is `LINEAR` or `STEP`; no CUBICSPLINE is
  present.
- The GLB contains no `images`, `textures`, or `samplers` entries. The
  descriptor names the copied external PNG directly.
- The descriptor declares exactly one strict-v1 socket:
  `"blendlib_showcase:tip": {"node":
  "ShowcaseAnimationRoot/ShowcaseAnimationArmature/ShowcaseRootBone/ShowcaseTipBone"}`.
  The value is the frozen descriptor object form, not a shorthand string.

The descriptor maps each state to the correspondingly named exported clip. It
uses `idle` as its initial state, loops `idle`/`walk`, and configures the
non-looping `attack` clip to return to `idle`; its 0.25-second event is within
the sampled one-second attack clip. This is an asset-local truthful state map,
not a runtime binding or a change to P5 semantics.

## Reproduction and validation

Executed with Blender 5.1.2 at `D:\Program Files\Blender\blender.exe`:

```powershell
$env:PYTHONDONTWRITEBYTECODE = '1'
& 'D:\Program Files\Blender\blender.exe' --background `
  --python test-assets\showcase-animation\create_showcase_animation_asset.py -- `
  --project-root D:\BlendLib

& 'D:\Program Files\Blender\blender.exe' --background `
  --python test-assets\showcase-animation\export_showcase_animation_asset.py -- `
  --source-project-root D:\BlendLib `
  --output-project-root D:\BlendLib\blendlib-showcase `
  --report test-assets\showcase-animation\export-report.json

& 'D:\Program Files\Blender\blender.exe' --background `
  --python test-assets\showcase-animation\verify_showcase_animation_asset.py -- `
  --project-root D:\BlendLib --record-golden

& 'D:\Program Files\Blender\blender.exe' --background `
  --python test-assets\showcase-animation\verify_showcase_animation_asset.py -- `
  --project-root D:\BlendLib

& 'D:\Program Files\Blender\blender.exe' --background `
  --python blender-addon\scripts\verify_p2_fixtures.py -- `
  --project-root D:\BlendLib

python -B blender-addon\scripts\verify_p2_descriptor_schema.py `
  --project-root D:\BlendLib
```

Results:

```text
BLENDLIB_P5_SHOWCASE_ANIMATION_SOURCE_CREATED idle walk attack
BLENDLIB_P5_SHOWCASE_ANIMATION_EXPORT_OK animations=[attack, idle, walk]
BLENDLIB_P5_SHOWCASE_ANIMATION_DETERMINISM_PASS idle walk attack
BLENDLIB_P2_DETERMINISM_PASS static rigid skinned
P2_DESCRIPTOR_SCHEMA_STRICT_TEXTURE_PATH_CONTROL_SAFE_AND_RUNTIME_TEXTURE_PASS 3
P5_SHOWCASE_DESCRIPTOR_SCHEMA_AND_EXTERNAL_PNG_OK
P5_SHOWCASE_SOCKET_DRAFT202012_SCHEMA_PASS
P5_SHOWCASE_SOCKET_STRICT_LOADER_PASS node=0
P5_SHOWCASE_SOCKET_TWO_TIME_SAMPLE_PASS positive=(0.07,0.6,0) negative=(-0.06999999,0.6,0)
P5_SHOWCASE_SOCKET_JUNIT_JAVAC_PASS
P5_SHOWCASE_SOCKET_JUNIT_EXECUTION total=2 succeeded=2 failed=0
P5_SHOWCASE_VISUAL_CONTRACT_JUNIT_JAVAC_PASS
P5_SHOWCASE_VISUAL_CONTRACT_JUNIT_EXECUTION total=2 succeeded=2 failed=0
P5_SHOWCASE_SOCKET_GRADLE_TEST_PASS tests=2 failures=0 errors=0 tasks=11
P5_SHOWCASE_VISUAL_CONTRACT_AND_BOUNDARY_GRADLE_TEST_PASS tests=5 failures=0 errors=0 tasks=16
```

The verifier runs the same source through the existing P2 `run_cli` export path
twice into independent `build/p5-showcase-animation-determinism/{first,second}`
outputs. It validates both strict GLB outputs, external PNG resolution,
profile, active default-scene root, non-alias channel data, descriptor state
references, and exact normalized structure/SHA-256 equality. It then compares
the committed Showcase resources to that isolated output and to `golden/`.

## Write-boundary safety

The asset generator accepts only this checked-in `D:\BlendLib` project root.
The export wrapper accepts only `D:\BlendLib\blendlib-showcase` or the two
exact deterministic verification output roots under
`D:\BlendLib\build\p5-showcase-animation-determinism`; it also constrains
texture relocation to that output's `assets/textures` subtree. The verifier's
only recursive cleanup target is the same exact deterministic output root.

The following negative guards were rechecked with Blender 5.1.2 before the
post-hardening deterministic export:

```text
BLENDLIB_P5_SHOWCASE_OUTPUT_ROOT_GUARD_PASS
BLENDLIB_P5_SHOWCASE_SOURCE_ROOT_GUARD_PASS
BLENDLIB_P5_SHOWCASE_ANIMATION_DETERMINISM_PASS idle walk attack
```

## Recorded hashes

| Item | SHA-256 |
|---|---|
| Source `source.blend` | `3ce143f190a2d8eac25df4392fdd0ce3dc61a4c397fc62ba3f1bf7920c857511` |
| Source / external runtime PNG | `1110b7c256a8f1100fc659934bfe360340694f98dde0243d37d6fc8dcdba8362` |
| Runtime GLB | `ef6dcd3426341f984367a8598fa7fc159d85da17b8f6709aa3b7f4d183615d53` |
| Runtime descriptor | `d2e044e4e2c251e79df95e6f028ae457df76630fe3cde05aa76abaea81353a94` |
| Normalized descriptor/GLB structure | `30f3fe54146c2305793e489652c4743a6faef9ffbe97a648a512735928ab1d32` |

Distinct data-bearing clip fingerprints:

| Clip | SHA-256 |
|---|---|
| `attack` | `a53dba36dfaefd4ab0b57bac35161a470675881857a6658f7cfd3216f2f54126` |
| `idle` | `afe863ee33f0548e3d6f3945044e398259f5adff35af2eb3cdd0317a69bb1b9d` |
| `walk` | `72a90b8d07bbfbc0c34cbc5daa7d4beeca006b892f675858a8ae8c933f42203b` |

## Known limits

No Java renderer/controller/Showcase registration was changed. Therefore this
evidence supplies no claim that the asset is currently visible, playable, or
connected to entity movement. Human visual checks, F3+T reload acceptance,
performance testing, P4 review, and P5 Gate acceptance remain separately
pending.
