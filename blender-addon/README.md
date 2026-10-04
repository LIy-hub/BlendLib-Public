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
