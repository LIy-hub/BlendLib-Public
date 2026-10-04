# Asset provenance

No third-party assets are added. `prepareRunnableExampleAssets` copies these existing
repository-local files byte-for-byte into generated example resources at build time; committed
mesh derivatives and the two original named-skin PNGs are documented below:

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

## Authored two-slot item derivative

`models3d/appearance_wand.glb` is generated deterministically by
`tools/generate_appearance_wand.py` from the original demo wand's repository-local source
`showcase_animation/showcase_actor.glb`. No separate wand GLB existed previously: `wand.json`
used that technical four-vertex fixture. The new script authors a narrow, three-dimensional
shaft (12 triangles, `WandBody`) and a separate tip crystal (8 triangles, `WandAccessory`).
It preserves the original skeleton, inverse binds, node hierarchy and idle/walk/attack clips.
The shaft uses root-to-tip skin weights, and the crystal follows the tip joint. Both reuse
the existing actor texture and repository license; no external assets are used.

`blend_models/appearance_wand.json` retains the original wand animation states, socket and
`units_per_block = 2.5`. The build consumes the committed derivative without requiring Python.
Only the opt-in item registration uses this new mesh. The original `wand.json`, source fixture
and attachment usages remain unchanged. Both appearance stacks share one prepared handle;
there is no per-stack mesh copy or runtime asset-generation step.

## Original named-skin pixel textures

`textures/skins/ember.png` and `textures/skins/frost.png` are original, hand-authored 8×8 RGBA
pixel patterns committed specifically for the named-skin consumer. They have three colors each
and fully opaque pixels. `tools/generate_named_skin_textures.py` reproduces both PNGs from the
explicit authored rows and palettes using only Python's standard library. They are covered by
the repository's Apache-2.0 license; there are no external images, downloads or attribution
requirements beyond the existing repository notices. The build consumes the committed files
and does not run this authoring script. Both actual model definitions reuse these two textures
in opposite body/accessory combinations; original copied assets and GLB geometry are unchanged.

## Original standard-IK mechanical arm and markers

`tools/generate_mechanical_arm.py` deterministically authors all of these committed resources
using only Python's standard library, with no downloaded source, Blender dependency or build-time
generation:

- `models3d/mechanical_arm.glb`: volumetric blue upper link, orange lower link, steel joint
  housings and end fork, rigidly weighted to exactly three joints. The `ArmMount` ancestor has
  an authored translated/rotated/uniformly scaled bind transform; its four-second idle clip
  changes rotation and positive uniform scale. Inverse binds are derived from that actual rig
- `models3d/ik_target_marker.glb`: twelve narrow box edges forming an open target cage
- `models3d/ik_end_marker.glb`: a small eight-triangle endpoint diamond
- Corresponding strict v1 descriptors in `blend_models/`, with the arm's end, origin and mount
  sockets declared by complete node path
- `textures/mechanical_arm.png`: original 16×4 opaque RGBA palette atlas with steel, blue,
  orange and white bands. White marker geometry receives the captured cyan/gold tint

These original assets are covered by the repository's Apache-2.0 scope and existing notices.
The normal runtime JAR excludes them. The opt-in example build verifies that packaged bytes
exactly match the committed assets; its headless check loads the same packaged resources.
Regenerate from the repository root with `python3 versions/modern/showcase/tools/generate_mechanical_arm.py`.

## Locomotion animation derivative

`models3d/locomotion_actor.glb` is a deterministic derivative of the committed two-slot
`appearance_actor.glb`, generated by `tools/generate_locomotion_actor.py`. Original geometry,
materials, skeleton, inverse binds and idle/walk/attack clips are preserved. The script adds an
original half-second `run` clip with root translation and tip rotation, plus a continuous-loop
run state in `blend_models/locomotion_actor.json`. Its root translation radius stays below the
original 0.07-unit actor/attachment envelope assumption. The original models are not rewritten.
The build uses the committed derivative without Python or Blender. All inputs and authored motion
remain within the repository's Apache-2.0 scope; no external asset is introduced.

The hand-authored `blend_animation_rules/locomotion_actor.json` sidecar supplies speed/grounded
rules only to the opt-in renderer. It does not modify the strict-v1 descriptor schema.

## Phase-aligned continuous-blendspace derivative

`models3d/blendspace_actor.glb` and `blend_models/blendspace_actor.json` are generated by
`tools/generate_blendspace_actor.py` using only Python's standard library and the committed
`appearance_actor.glb`/descriptor. Geometry, both material slots, skeleton, inverse binds and
the original upper attack are preserved. Only this dedicated derivative replaces idle/walk
and adds run. The three original authored loops have raw durations 2, 1 and 0.5 seconds and
non-unit descriptor speeds 0.5, 1.5 and 2. All 33-key channels use the same normalized bob and
rotation phase, with exactly repeating endpoint values. Root translation stays within the
existing 0.07-unit actor/attachment envelope budget. No external asset, Blender export, gait
inference or third-party motion-capture data is involved.

The member states are continuous loops without next-state edges or footstep markers. The
original attack marker and tip socket are preserved. This is an ordinary strict-v1 descriptor;
there is no new schema field or discrete locomotion-rules sidecar. The consumer's 0.8-second
cycle and sample positions are declared in Java. Source assets and the existing discrete
`locomotion_actor` derivative are not rewritten. All authored content remains under the
repository's Apache-2.0 scope and existing notices.

Regenerate from the repository root with:

```sh
python3 versions/modern/showcase/tools/generate_blendspace_actor.py
```

The build consumes committed resources without running Python; packaged verification checks
that they match and evaluates their actual decoded poses and CPU-skinned vertices headlessly.
