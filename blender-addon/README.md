# BlendLib Blender 5.x Exporter

## License scope

Every file in this `blender-addon/` directory is licensed under
GPL-3.0-or-later. The canonical license text is [LICENSE](LICENSE), and Python
sources carry `SPDX-License-Identifier: GPL-3.0-or-later` headers.

This scope is deliberately directory-limited. It does **not** license the
BlendLib root project, `blendlib-*` modules, Showcase, test assets, runtime
artifacts, or any file outside `blender-addon/`; the root project and Java
modules are separately licensed under Apache-2.0.

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
export from Blender. Its post-export validator checks the strict GLB 2.0 shape
before a descriptor is written.
