# Bounded CPU morph actor

First-party Apache-2.0 fixture for descriptor format 2 and
`blendlib:skinned_morph_cpu_v1`. Add-on implementation/generator files are
separately GPL-3.0-or-later under `blender-addon/`.

- `source.blend`: editable Blender 5.1.2 actor, normalized two-joint skin, Basis,
  Blink, Smile and Breath shape keys, stored Key Actions and runtime-authoring Text
- `surface.png`, `detail.png`: first-party external material textures
- `exported/assets/cpu_morph/`: complete `cpu_morph:face_actor` descriptor, GLB and PNGs
- `runtime-authoring.json`: the canonical Text also embedded in the source
- `preview-blender.png`: actual Blender Cycles source render, explicitly labeled;
  native Minecraft visual acceptance remains pending
- `oracle-samples.json`: actual evaluated Blender weights, bone matrices and
  skinned positions, plus the independent glTF normal oracle described below
- `oracle.json`: source version, repeat-export determinism and measured errors
- `edge-cases.json`: real Blender source rejection, fallback, slot, UI and manual-only evidence
- `legacy-byte-preservation.json`: baseline/current SHA-256 equality for old profiles
- `source-probes.json`: verified Blender exporter source paths, hashes and relevant behavior

The initial state is `cpu_morph:nod`; all states loop for 1.5 seconds at 24 FPS.
`Nod` contains one native cubic quaternion channel with three authored keys.
`Blink` is STEP, while `Smile` and `Breath` are LINEAR. These three Key Actions
are genuinely weight-only, have four keys each, and are stored in muted NLA
tracks used only to associate their Action slots. No strip mixing is exported.
The authored default Breath weight is 0.15; Blink and Smile default to zero.

Controls are `cpu_morph:blink` `[0,1]`, `cpu_morph:smile` `[-1,1]`, and
`cpu_morph:breath` `[-0.5,1]`. All target the mesh node
`MorphRoot/MorphRig/FaceBody`. The face socket is `cpu_morph:face` on
`MorphRoot/MorphRig/Base/Face`; `cpu_morph:nod_apex` fires at 0.5 seconds.
Two materials produce two stable primitives and 264 exported vertices in total.
Upper chest vertices blend 0.8 Base / 0.2 Face weights, exercising nonzero default
morphing before multi-joint skinning during Nod. Smile and Breath supply nonzero
normal deltas, rather than testing only constant normals.

## Independent numerical evidence

The generator evaluates the actual Blender dependency graph for every clip at
15 authored-key and off-key probes, totaling 60 samples. Source vertices are
matched to exported Basis POSITION coordinates once, then the evaluated Blender
positions are emitted in the exact exported primitive POSITION order. The oracle
is not produced by calling Java or its implementation.

`clips[]` contains `clip` and `samples[]`. Each sample contains `frame`, `seconds`,
`source_seconds`, complete weights by exact mesh-node path, row-major world bone
matrices, and `meshes.FaceBody[]` entries with primitive index, positions and
normals. Root `meshes.FaceBody[]` provides source vertex index maps and counts.
Coordinates are glTF Y-up world space. `seconds` is FLOAT-representable; the
unrounded Blender instant is preserved as `source_seconds`. At a STEP key this
compares the authored key with its representable glTF time, avoiding the opposite
side of a quantized discontinuity. This is a time-quantization disclosure, not a
claim of identical unrounded discontinuity times.

Normals deliberately use a separately identified independent oracle: dense
normal deltas are weighted before inverse-transpose skin transforms, the skin
contributions are combined, and the result is normalized. Blender's evaluated
geometric normals interpolate differently, so they are not asserted to equal
glTF normal-delta semantics. The main position maximum error is below 3e-7.
Reported Bezier/TRS fallback errors remain explicitly approximate.

## Reproduce

From the repository root with Blender 5.1.2:

```sh
blender --background --python-exit-code 1 --python blender-addon/scripts/create_cpu_morph_fixture.py -- --project-root .
blender --background --python-exit-code 1 --python blender-addon/scripts/verify_cpu_morph_edge_cases.py -- --project-root .
python -m unittest discover -s blender-addon/tests -v
```

The generator saves the editable source before adding preview-only camera, lights
and labels. Repeated GLB and descriptor exports must match byte-for-byte.
`edge-cases.json` additionally checks fractional FPS, separate Key/Object slots,
Bezier weight baking, native-TRS fallback without lost weights, state-editor and
socket discovery, rejected source semantics, and manual-only assets without a
fabricated clip. That manual-only case verifies data export, not the high-level
animated renderer: animation-free exports currently need explicit lower-level
CPU consumption. X5 still rejects this profile.

For the byte-preservation comparison, archive the `baseline_commit` recorded in
`legacy-byte-preservation.json` with `git archive <commit> blender-addon`, then
run `blender-addon/scripts/verify_cpu_morph_legacy_bytes.py` twice, selecting that
archive or the current add-on with `--addon-root` and distinct `--output` paths.
Compare the resulting `hashes.json` files. The comparison covers GLB, descriptor
and normalized structure for static, rigid, skinned and native-cubic fixtures.
