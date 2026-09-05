# X5 Blender UI workflow and evidence boundary

The Blender sidebar panel is **BlendLib X5 Toolchain**. It registers these
operators:

- `blendlib.x5_preflight`
- `blendlib.x5_export`
- `blendlib.x5_batch_export`
- `blendlib.x5_dev_refresh`
- `blendlib.x5_preview`

It exposes authoring-root, batch manifest, optional refresh file/session/
generation, a model-preview control plus five debug controls (bones, sockets,
normals, materials, animation timeline), and bounded status/state text. Batch manifest paths are
project-relative and use the explicit `blendlib-x5-batch-manifest-v1` schema.
Preflight, one-click export, and batch export all consume the same frozen
profile/Armature/bone influence decision. A binding error is displayed by the
panel and returns `CANCELLED`; it cannot fall through to private staging.

`blendlib.x5_preview` now applies reversible Blender authoring state to the
selected export collection:

- model preview unhides/selects export objects, chooses an active mesh, and
  frames the selection in available `VIEW_3D` regions;
- bone debug uses in-front armature display, octahedral bones, and bone names;
- socket debug exposes Empty arrow/name overlays;
- normal debug enables mesh wire/all-edge state and face-normal overlays in
  available 3D views;
- material inspection switches available 3D views to Material Preview and
  activates the first exported material slot;
- animation timeline debug derives the scene frame range/current frame from
  the same bound/NLA Action set used by export.

Before applying a new state, the controller restores its prior object,
selection, hidden, armature, overlay, shading, active-object, and scene timeline
values. Turning all controls off restores the baseline; unregister also
restores it and removes every X5 property/class. No preview state is saved as a
runtime resource.

`verify_x5_toolchain.py` is a deliverable Blender-headless entrypoint. It
registers the X5 UI, verifies the expected Scene properties exist, unregisters
it, and checks those properties do not leak. It is not a test suite and carries
no fixtures or `unittest` code; standard-library X5 tests live under
`test-assets/x5/python-tests`.

## Evidence status

| Evidence | Status |
|---|---|
| module import has no eager `bpy` field | automated |
| Blender headless register/unregister | automated when Blender executable is available |
| headless object/armature/socket/normal/material/timeline state and restoration | automated with real Blender |
| interactive panel layout and visible overlay/preview quality | **WAITING: requires a human interactive Blender session** |

Headless state evidence proves that Blender receives and restores the concrete
viewport/debug controls. It does not establish interactive readability,
framing quality, overlay scale, or artist workflow quality; those remain
WAITING and are not represented as automated visual PASS.
