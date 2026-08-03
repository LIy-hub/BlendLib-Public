# Strict-loader fixture package

This package is self-authored test support. It does not copy or mutate canonical GLB,
PNG, descriptor, source-blend, or golden files. Binary fixtures are generated
deterministically by the core test fixture catalog, which returns a fresh bounded
byte array for each request.

| GLB fixture | Expected diagnostic family |
|---|---|
| `VALID_TRIANGLE` | `NONE` |
| `INVALID_HEADER`, `DECLARED_LENGTH_MISMATCH` | `BLENDLIB-GLB-001` |
| `CHUNK_OUT_OF_BOUNDS`, `INVALID_INDEX`, `NONFINITE_TRANSFORM` | `BLENDLIB-GLB` |
| `ACCESSOR_OUT_OF_BOUNDS` | `BLENDLIB-GLB-014` |
| `REQUIRED_EXTENSION` | `BLENDLIB-EXT-001` |
| `NODE_CYCLE` | `BLENDLIB-SCENE-004` |
| `NONMONOTONIC_ANIMATION` | `BLENDLIB-ANIM-006` |
| `LIMIT_NODE_COUNT`, `LIMIT_SKIN_JOINTS`, `LIMIT_CLIP_COUNT`, `LIMIT_HIERARCHY_DEPTH` | `BLENDLIB-LIMIT-001` |

The descriptor JSON files in `descriptors/` are intentionally textual inputs.
Their expected diagnostic values are `BLENDLIB-DESC` family errors unless the
frozen contract reserves the exact `BLENDLIB-DESC-001` version error.
