# Authored native cubic skinned sample

First-party Apache-2.0 sample for the explicit `blendlib:skinned_cubic_v1` profile
and descriptor `format_version: 2`. The Blender add-on/scripts are separately GPL-3.0-or-later.

- `source.blend`: editable Blender 5.1.2 scene; open the existing BlendLib sidebar
- `albedo.png`: external material texture
- `preview.png`: real Blender render of the source at frame 19
- `runtime-authoring.json`: the same states, event and socket Text stored in the source
- `exported/assets/native_cubic/`: complete runtime-ready descriptor, GLB and PNG
- `oracle.json`: reproducible test evidence, source/render version and numerical errors
- `oracle-samples.json`: independent Blender world-bone matrices and world-skinned vertices,
  indexed in each exported primitive's POSITION order, for the Java runtime oracle

`native_cubic:eased_actor` is a three-piece blue skinned figure. Its `EaseWave`
Action is 1.5 seconds with authored keys at frames 10, 22 and 46 (24 FPS).
Translation and uniform scale ease the base while an independently oriented
rest bone rotates the head and arm. All three exported channels are CUBICSPLINE
with exactly three keys and nonzero authored tangents. Bezier handle times use
one-third intervals; source key values and quaternion signs are preserved.

States `native_cubic:wave` (loop) and `native_cubic:once` (returns to wave), event
`native_cubic:apex` at 0.5 seconds, and socket `native_cubic:tip` at
`CubicRoot/CubicRig/Base/Tip` exercise existing runtime authoring.

Run from the repository root:

```sh
blender --background --python blender-addon/scripts/create_native_cubic_fixture.py -- --project-root .
```

The generated oracle compares the exported animation, world bone matrices and
skinned vertices against Blender's evaluated scene at 14 key/non-key times.
It also tests effective fractional FPS, LINEAR/STEP channels, byte-identical repeat
exports, existing strict-v1 output, the sidebar operator and reported fallback
for nonlinear-time Bezier handles. Eligibility tests cover modifiers, unequal
keys, bone constraints, nonstandard inheritance, unsafe scale/quaternion controls
and component-linear quaternion interpolation. Object constraints still reject.

The source contains no cameras or lights. The generator adds temporary preview
lighting after saving and export. The preview is generated from the actual scene.
No arbitrary-Bezier or arbitrary-Blender exactness is claimed; see
[the export contract](../../blender-addon/README.md#explicit-native-cubic-profile-format-2).
