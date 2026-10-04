# Explicit Blender animation authoring plan

Stack on the locomotion rules tree `51be0023b79510abad0e14b524cf26e1deb35540`.

- Explicit CLI `--runtime-authoring-text` or sidebar toggle plus Text datablock picker.
  No opt-in means the historical automatic Action state mapping. No legacy X5 metadata
  is reinterpreted. The new Text JSON has its own `schema_version: 1` contract.
- Author descriptor-compatible `animation.initial_state` and named `states` with exact
  Action `clip`, `loop`, `speed`, optional `next` and `blend_seconds`. Events name Action
  pose markers and resource IDs; export resolves frames at effective scene FPS to clip
  seconds, validates against actual GLB sample bounds and rejects missing/ambiguous markers.
- Optional sockets specify resource ID keys and exact full exported node paths. No fuzzy
  names or silently inferred transforms. Optional locomotion uses the existing strict-v1
  schema in a separate `blend_animation_rules/<model-id>.json` resource, never descriptor fields.
- Reject duplicate/unknown JSON fields, versions, bad types, non-finite values, invalid
  targets, limits and invalid hysteresis. Locomotion only targets continuous authored loops.
  Keep the GPL boundary in blender-addon and add no runtime schema/API changes.
- Minimal sidebar and documented Text example; pure Python adversarial contract tests;
  genuine verified official Blender 5.1+ export twice and actual Java loader/controller,
  event, socket and locomotion tests on the exported asset. Older Blender and mocked bpy
  cannot satisfy the genuine export gate.
- Run existing exporter/X5 regressions, applicable root and full official 26.3 checks,
  one independent critical review; push only this branch and verify exact-head CI.
  Update the cumulative Library bundle with source, JARs and evidence. Graphics deferred;
  no merge, tag, release, CurseForge, EULA or security setting changes.
