# Asset provenance

No new binary files or third-party assets are added. `prepareRunnableExampleAssets` copies these
existing repository-local files byte-for-byte into generated example resources at build time:

| Source under `blendlib-showcase/src/main/resources/assets/blendlib_showcase/` | Generated destination under `assets/blendlib_runnable_examples/` |
| --- | --- |
| `models3d/showcase_animation/showcase_actor.glb` | `models3d/actor.glb` |
| `textures/blendlib/showcase_animation/showcase_actor__showcaseanimationsurface.png` | `textures/actor.png` |
| `models3d/fixtures/static_model.glb` | `models3d/marker.glb` |
| `textures/blendlib/fixtures_static_model__staticsurface.png` | `textures/marker.png` |

License: repository Apache-2.0 scope, copyright 2026 LIy-hub; see root `LICENSE` and `NOTICE`.
The descriptors adapt only the namespace/paths and the wand's unit scale. The actor's original
`idle`, `walk`, `attack` clips and `ShowcaseTipBone` socket are retained. The item intentionally
reuses the small two-bone actor fixture so the animation path can be reproduced without Blender,
external asset downloads, or opaque generated binaries. It is a technical demo prop, not final art.

## Two-slot material-appearance derivative

`src/main/resources/assets/blendlib_runnable_examples/models3d/appearance_actor.glb` is a
repository-local derivative of the same Showcase actor listed above, under the repository's
Apache-2.0 scope. `tools/generate_appearance_actor.py` deterministically regenerates it and
its descriptor. It preserves the original body primitive, animations, skeleton and sockets,
then authors a distinct side-badge position accessor and `ExampleAccessory` material/primitive.
The badge reuses the original topology, UVs, normals and skin weights, with separate positions
and expanded decoded bounds. Both slots use the already copied `textures/actor.png`; no new
external source or texture is introduced. The build consumes the committed derivative without
requiring Python. Original copied assets remain byte-for-byte unchanged.
