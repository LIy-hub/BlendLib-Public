# BlendLib Blender 5.x Exporter

## License scope

Every file in this `blender-addon/` directory is licensed under
GPL-3.0-or-later. The canonical license text is [LICENSE](LICENSE), and Python
sources carry `SPDX-License-Identifier: GPL-3.0-or-later` headers.

This scope is deliberately directory-limited. It does **not** license the
BlendLib root project, `blendlib-*` modules, Showcase, test assets, runtime
artifacts, or any file outside `blender-addon/`. Non-Add-on project code and first-party
assets use the root Apache-2.0 LICENSE and NOTICE; third-party material retains its own terms.

This local Blender 5.x add-on exports the strict BlendLib v1 runtime asset set:

- a GLB 2.0 mesh at `assets/<namespace>/models3d/<model-id>.glb`;
- a v1 descriptor at `assets/<namespace>/blend_models/<model-id>.json`; and
- external PNG textures at `assets/<namespace>/textures/blendlib/`.

The exporter accepts only Blender CLI arguments after `--`. Example:

```powershell
& 'D:\Program Files\Blender\blender.exe' --background `
  --python blender-addon\scripts\export_blendlib.py -- `
  --blend test-assets\static\source.blend `
  --project-root build\p2-output `
  --namespace blendlib_showcase `
  --model-id fixtures/static_model `
  --profile blendlib:rigid_v1 `
  --collection BlendLibExport
```

The exporter deliberately filters cameras/lights, rejects physics and unsafe
resource paths, preserves named material slots, and requests no runtime image
export from Blender. Its post-export validator checks the strict P2 GLB shape
before a descriptor is written.

## Opt-in runtime authoring

Enable **Runtime Animation Authoring** and select a Blender Text datablock, or pass
`--runtime-authoring-text BlendLib.runtime.json` to the strict CLI. The versioned Text
maps Actions to states and pose-marker visual events, exact node-path sockets and an optional
separate locomotion-rule resource. Disabled export retains automatic loop states.
The **Runtime State / Event Editor** offers explicit draft/apply controls for state-to-Action
mappings and Action-pose-marker events. Other fields stay in the same Text.
See [authoring guide and working example](../docs/blender-runtime-authoring.md).
Legacy X5 authoring metadata is not silently reinterpreted.

### Exact exported socket selection

The Runtime State / Event / Socket Editor also supports **Add Socket** and **Load
Socket**. In Object Mode it privately exports the current collection, lists typed
object/bone choices and shows their exact GLB paths. Apply deliberately updates
only the selected canonical Text socket. Scene identity/path or Text conflicts
fail without writing. For offsets/orientation, create and transform an ordinary
exported Empty first, then select it; strict-v1 sockets remain node-only.
See [socket workflow](../docs/blender-runtime-authoring.md#socket-editor-and-local-offsets).

Structured locomotion authoring is available through **Load / Add Locomotion Rules**
in **Runtime Authoring Editor**. Pick a default loop, order rule priorities, add
Boolean or numeric min/max enter/exit conditions and explicitly Apply to canonical
Text. See [the workflow](../docs/blender-runtime-authoring.md#locomotion-rule-editor)
and [idle/walk/run fixture](../test-assets/blender-rules/README.md).

## Explicit native-cubic profile (format 2)

The existing **Rigid v1** / **Skinned v1** options remain the default sampled
strict-v1 path. Select **Skinned native cubic v1 (format 2)** or explicitly pass
`--profile blendlib:skinned_cubic_v1` for the separate format-2 descriptor. This
keeps the same mesh, material, four-weight skinning, positive uniform scale,
state/event, transition, socket, and locomotion authoring surfaces. X5's frozen
strict-v1 publishing tool remains strict-v1-only.

Native export is verified with Blender **5.1.2**. Each transform component must
have at least two synchronized keys, with one interpolation kind (`LINEAR`,
`CONSTANT`, or `BEZIER`). Bezier time handles must lie at exactly one third of
the adjoining interval (within float-coordinate tolerance). Location and uniform
scale can use all three kinds; quaternion rotation can use Bezier or constant.
Euler/axis-angle curves and component-linear quaternion curves require baking.
Bone inheritance must be ordinary full-scale/local-location inheritance.
Multiple Action layers/slots, FCurve modifiers, drivers, constraints on bones,
delta transforms, and other unproved conversions also require baking. Object
constraints, physics and dynamic modifiers retain the existing hard rejection.

Eligible curves use Blender's unsampled glTF scene export and direct analytical
conversion of the authored keys and derivatives. Bone rest transforms and Y-up
conversion are included. Tangents are derivatives per second using effective
FPS (`fps / fps_base`); quaternion tangents are neither normalized nor sign-flipped.
This deliberately replaces Blender 5.1.2's approximate quaternion-tangent
conversion. Keys and Bezier controls must satisfy the runtime's bounded-hull,
positive-uniform-scale and common-positive-quaternion-hemisphere safety rules.
It is **not** an exact export claim for arbitrary Bezier handles or Blender scenes.

If any channel is ineligible, the entire asset uses the existing frame-sampled
LINEAR/STEP path. The export report's `native_cubic.mode` is
`baked_linear_fallback`, with action/node-specific `fallback_reasons`; this is an
approximation at scene-frame cadence. The sidebar displays a warning. Native
success reports `native_fcurves` and the source key counts. Other safety failures
can still reject the export, and the runtime independently validates the result.

### Reproducible authored sample

[The native cubic sample](../test-assets/native-cubic/README.md) includes the
editable `.blend`, texture, generated descriptor/GLB, a rendered preview and a
numerical Blender depsgraph/skinning oracle. Recreate it with:

```sh
blender --background --python blender-addon/scripts/create_native_cubic_fixture.py -- --project-root .
python -m unittest discover -s blender-addon/tests -v
```

The verifier checks non-key samples, rotated rest bones, uneven segment lengths,
nonzero quaternion/scale/translation tangents, fractional FPS, native LINEAR/STEP,
strict-v1 preservation, declared fallback, and the existing sidebar authoring flow.

Native cubic eligibility also rejects connected-child bone location channels and
non-CONSTANT F-curve extrapolation. Both use explicit baked fallback; this prevents
ignored Blender bone translations or extrapolated motion from being mislabeled as exact.
Run `blender --background --python-exit-code 1 --python blender-addon/scripts/verify_native_cubic_edge_cases.py -- --project-root .` for these regressions.
