# P5 Showcase Animation Asset

This directory is a BlendLib-owned authoring fixture for the later P5 Showcase
animation wiring. It is not a runtime input: Minecraft receives only the
generated strict GLB, descriptor, and external PNG below
`blendlib-showcase/src/main/resources/assets/blendlib_showcase/`.

The source has one active default-scene root, `ShowcaseAnimationRoot`, a small
two-bone skinned mesh, and three intentionally different Actions/NLA clips:

- `idle` — tip-bone sway;
- `walk` — root-bone lateral cadence;
- `attack` — non-symmetric tip swing.

Regenerate the source, then its runtime asset, with Blender 5.1.2:

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
```

The export wrapper delegates GLB generation and strict validation to the
existing P2 exporter. It then writes this asset's explicit state mapping:
`idle` and `walk` loop; `attack` is non-looping, returns to `idle`, and has a
presentation-only `blendlib_showcase:attack_whoosh` event at 0.25 seconds.

Run the two-isolated-output verification (without modifying goldens) with:

```powershell
$env:PYTHONDONTWRITEBYTECODE = '1'
& 'D:\Program Files\Blender\blender.exe' --background `
  --python test-assets\showcase-animation\verify_showcase_animation_asset.py -- `
  --project-root D:\BlendLib
```

`golden/` records the normalized descriptor/GLB structure, SHA-256 values, and
data-bearing animation-channel fingerprints. The verifier rejects aliases that
only relabel the same clip data, embedded runtime texture state, unsupported
interpolation, missing skin attributes, a non-unique active default-scene root,
or descriptor references that do not name the exported clips.
