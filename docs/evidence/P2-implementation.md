# P2 Gate Evidence — Blender Asset Toolchain

Status: PASS — P2 was first reopened after generated descriptor `base_color`
IDs omitted `.png`, then independently failed again because the frozen schema
still allowed that invalid form and a source-tree bytecode artifact remained.
Further reviews found unsafe path segments and then a terminal-newline bypass
in the initial strict regex. The final contract is repaired, directly tested,
and independently re-reviewed as PASS.

## 2026-07-29 descriptor external-PNG repair

- The exporter now preserves the concrete copied filename in every
  `materials.*.base_color` resource ID, for example
  `blendlib_showcase:textures/blendlib/fixtures_static_model__staticsurface.png`.
- Export reports and `validate_descriptor` now resolve that resource path
  directly. `validate_descriptor` rejects extension-less `base_color` IDs with
  `BLENDLIB-DESC-001`; no P4 adapter inference was added.
- The canonical static, rigid, and skinned source fixtures were rebuilt with
  Blender 5.1.2, then re-exported. Their checked-in descriptors, reports,
  normalized structures, and SHA-256 golden records now contain the full PNG
  resource path.
- The two-run deterministic Blender verification now asserts, for every
  material, that `base_color` ends in `.png` and resolves exactly to the copied
  file named in the export report.

## 2026-07-29 frozen-schema and source-tree-hygiene repair

- `schemas/blendlib-model-v1.schema.json` now defines
  `textureResourceId`: it composes the unchanged generic `resourceId` with a
  `textures/**.png` constraint. Only `materials.*.base_color` uses the new
  definition; `mesh` and other resource fields remain generic IDs.
- The tracked negative fixture
  `test-assets/descriptor-invalid-base-color-without-png.json` is identical to
  the static descriptor except for its missing suffix. The dedicated
  `verify_p2_descriptor_schema.py` invokes Draft 2020-12 directly and asserts
  validation fails precisely at `materials/StaticSurface/base_color`.
- Every BlendLib add-on entrypoint sets `sys.dont_write_bytecode = True` before
  importing the exporter. The documented headless invocations additionally set
  `PYTHONDONTWRITEBYTECODE=1`. `verify_p2_fixtures.py` scans both before and
  after its exports for `*.pyc` and `*.blend1` under `blender-addon/` and
  `test-assets/`.
- The one exact pre-existing source artifact
  `blender-addon/__pycache__/blendlib_exporter.cpython-313.pyc` was hash-checked
  and moved (not deleted) to the external, recoverable quarantine
  `D:\BlendLib-p2-bytecode-quarantine\p2-source-bytecode-20260729-002518`.
- Blender 5.1.2 then generated fixtures and completed the two-run determinism
  check in two fresh external roots,
  `D:\BlendLib-p2-isolated\run-20260729-002603` and
  `D:\BlendLib-p2-isolated\repeat-20260729-002619`. Both ended with no
  source-tree `*.pyc` or `*.blend1`; the real repository-root verification
  repeated the same hygiene assertion successfully.

## 2026-07-29 strict texture-path schema repair

- The initial PNG suffix regex was still too broad: its generic path segment
  class accepted `.`/`..` and an empty segment. `textureResourceId` now permits
  only `namespace:textures/<non-empty non-dot segments>.png`; generic
  `resourceId` remains unchanged for `mesh` and other non-texture fields.
- The direct Draft 2020-12 verifier now checks four tracked negative
  descriptors, all failing exactly at `materials/StaticSurface/base_color`:
  a missing suffix, `textures/../escaped.png`,
  `textures//double-slash.png`, and `textures/./dot.png`.
- This repair changes only the frozen schema, its test fixtures, verifier, and
  evidence. Canonical GLB/descriptor bytes and SHA-256 goldens do not change;
  a targeted Blender 5.1.2 two-run verification confirmed deterministic output
  remains unchanged.

## 2026-07-29 terminal-control-character schema repair

- The strict path expression's `$` anchor could match before a final LF under
  the actual Python Draft 2020-12 validator. It now adds an end-of-input
  negative lookahead, so a resource cannot stop at `.png` while leaving any
  character unconsumed.
- The direct schema verifier now rejects terminal LF, terminal CRLF, leading
  whitespace, embedded whitespace, and an embedded control character in
  addition to the earlier four path negatives. Every negative fixture must
  fail precisely at `materials/StaticSurface/base_color`.
- Canonical descriptors remain concrete `textures/**.png` paths and need no
  regeneration; targeted Blender 5.1.2 determinism and Gradle verification
  remain clean.

## Implemented scope

- Blender 5.x extension manifest and `BlendLib` View3D sidebar panel.
- Headless CLI that consumes only arguments after Blender's `--` separator.
- Strict source validation: selected collection, exactly one export root, UV0,
  finite normals, named material slots, duplicate names, skin influences,
  scale, physics/particles/unsupported dynamic modifiers, and unsafe output
  paths.
- Canonical one-time coordinate conversion through Blender glTF `export_yup`:
  `X=X`, `Y=Z`, `Z=-Y`.
- Action and NLA discovery; forced sampled output and strict rejection of an
  exported interpolation other than `LINEAR` or `STEP`.
- GLB-only output, named material slots, external copied PNG textures whose
  descriptor resource IDs include the concrete `.png` filename, v1 descriptor
  generation, runtime texture stripping, and post-export GLB
  header/chunk/accessor/index/animation/bounds/node/skin validation.
- Three source fixtures: static, rigid node animation, and skinned animation;
  each has a normalized structure summary and SHA-256 golden record.

## Commands and observed results

Blender executable: `D:\Program Files\Blender\blender.exe` — `Blender 5.1.2`.

```powershell
$env:PYTHONDONTWRITEBYTECODE = '1'
& 'D:\Program Files\Blender\blender.exe' --background `
  --python blender-addon\scripts\create_canonical_fixtures.py -- `
  --project-root D:\BlendLib
```

Result: `BLENDLIB_FIXTURES_CREATED static rigid skinned` (exit 0).

Each canonical resource was then exported with the required headless form,
using `--output-resource-root blendlib-showcase/src/main/resources`; the three
commands completed with `BLENDLIB_EXPORT_OK` and wrote the descriptor, GLB,
external PNG, and `test-assets/<fixture>/export-report.json`.

```powershell
$env:PYTHONDONTWRITEBYTECODE = '1'
& 'D:\Program Files\Blender\blender.exe' --background `
  --python blender-addon\scripts\verify_p2_fixtures.py -- `
  --project-root D:\BlendLib
```

Result: `BLENDLIB_P2_DETERMINISM_PASS static rigid skinned` (exit 0). The
script intentionally parses a fake pre-separator `--blend` argument, verifies
it is ignored, exports every source `.blend` twice to two isolated output
directories, and compares normalized GLB/descriptor structures and hashes. It
also rewrites the rigid fixture's sampler to `CUBICSPLINE` and asserts that the
post-export validator rejects it with `BLENDLIB-ANIM-006`. During the 2026-07-29
repair rerun it also asserted every descriptor `base_color` ends in `.png` and
exactly resolves to its copied external PNG.

The isolated repeated runs additionally emitted
`P2_ISOLATED_BLENDER_HYGIENE_PASS` and
`P2_ISOLATED_BLENDER_HYGIENE_REPEAT_PASS`; the repository-root run emitted
`P2_REPOSITORY_BLENDER_HYGIENE_PASS`.

```powershell
python -B blender-addon\scripts\verify_p2_descriptor_schema.py `
  --project-root D:\BlendLib
```

Result: `P2_DESCRIPTOR_SCHEMA_STRICT_TEXTURE_PATH_CONTROL_SAFE_AND_RUNTIME_TEXTURE_PASS 3`.

Final repair checks also passed:

```powershell
git diff --check
git status --short --branch
```

The source-tree scan emitted `P2_FULL_PROJECT_SOURCE_HYGIENE_PASS`; no
`*.pyc` or `*.blend1` exists outside ignored build/tool directories.

## Independent Gate review — PASS

The independent P2 reviewer repeated the schema verification with the actual
Draft 2020-12 validator. It accepted the three canonical descriptors and
rejected every tracked invalid descriptor at
`materials.StaticSurface.base_color`: missing suffix, parent/dot/empty path
segments, terminal LF/CRLF, leading/embedded whitespace, and an embedded
control character. It additionally rejected terminal space, tab, CR, U+2028,
and NUL. The reviewer also confirmed that the `$(?![\\s\\S])` end-of-input
guard closes Python's pre-terminal-newline `$` behavior, every canonical
texture resolves to a physical external PNG, the GLBs contain no
`images`/`textures`/`samplers`, hygiene and `git diff --check` pass, and the
complete diff remains P2-only.

```powershell
& 'D:\Program Files\Blender\blender.exe' --background --python-expr `
  "import sys; sys.path.insert(0, r'D:\BlendLib\blender-addon'); import blendlib_exporter as exporter; exporter.register(); import bpy; assert hasattr(bpy.types.Scene, 'blendlib_namespace'); exporter.unregister(); print('BLENDLIB_ADDON_PANEL_REGISTER_PASS')"

$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat clean check
```

Results: `BLENDLIB_ADDON_PANEL_REGISTER_PASS` and `BUILD SUCCESSFUL` (34
actionable Gradle tasks; the Showcase resources were processed successfully).
The repaired direct Draft 2020-12 check emitted
`P2_DESCRIPTOR_SCHEMA_STRICT_TEXTURE_PATH_CONTROL_SAFE_AND_RUNTIME_TEXTURE_PASS
3`: all three canonical descriptors resolve to physical external PNGs, their
GLBs contain no `images`, `textures`, or `samplers`, and all nine negative
fixtures (missing suffix, parent segment, empty segment, dot segment, terminal
LF/CRLF, leading whitespace, embedded whitespace/control) are rejected at the
expected field. The exporter contract still rejects the missing-suffix form with
`BLENDLIB-DESC-001`.

## Fixture validation summary

| Fixture | Profile | Nodes | Vertices / indices | Animation | Canonical bounds |
|---|---|---:|---:|---|---|
| static | `blendlib:rigid_v1` | 2 | 3 / 3 | none | min `[-0.5, 0, 0]`, max `[0.5, 1, 2]` |
| rigid | `blendlib:rigid_v1` | 3 | 6 / 6 | `rigid_pulse`, `LINEAR` | min `[-0.5, 0, 0]`, max `[0.5, 1.6, 1]` |
| skinned | `blendlib:skinned_v1` | 4 | 3 / 3 | `skinned_wave`, `LINEAR`/`STEP` | min `[-0.4, 0, 0]`, max `[0.4, 1, 1]` |

All validators reported `embedded_runtime_images: false`; runtime PNG hashes
equal the authored external PNG hashes. The repaired descriptor-to-file
mappings are:

| Fixture | Descriptor `base_color` | External file |
|---|---|---|
| static | `blendlib_showcase:textures/blendlib/fixtures_static_model__staticsurface.png` | `assets/blendlib_showcase/textures/blendlib/fixtures_static_model__staticsurface.png` |
| rigid | `blendlib_showcase:textures/blendlib/fixtures_rigid_model__rigidsurface.png` | `assets/blendlib_showcase/textures/blendlib/fixtures_rigid_model__rigidsurface.png` |
| skinned | `blendlib_showcase:textures/blendlib/fixtures_skinned_model__skinnedsurface.png` | `assets/blendlib_showcase/textures/blendlib/fixtures_skinned_model__skinnedsurface.png` |

Exact tracked records are at
`test-assets/<fixture>/golden/structure.json` and `sha256.json`.

## P2 boundaries and remaining work

- The runtime does not read `.blend`, FBX, OBJ, or external `.gltf` files.
- No P3 loader, Minecraft rendering, server networking, or production server
  action was introduced.
- This phase does not constitute client visual verification; visual gates begin
  only when a rendering path exists in later phases.

## Formal extension-manifest validation — scoped GPL Add-on authorization

The user explicitly authorized GPL-3.0-or-later for `blender-addon/` only.
The manifest therefore declares `SPDX:GPL-3.0-or-later`, the directory contains
the canonical GPL text at `blender-addon/LICENSE`, and its Python sources carry
GPL SPDX headers. ADR-009 and `LICENSE-PENDING` explicitly retain
license-pending, non-GPL status for every file outside this directory.

The scoped LICENSE was copied byte-for-byte from:

```text
D:\Program Files\Blender\license\spdx\GPL-3.0-or-later.txt
SHA-256: 0b383d5a63da644f628d99c33976ea6487ed89aaa59f0b3257992deac1171e6b
```

The copied `D:\BlendLib\blender-addon\LICENSE` has the same SHA-256.

The Blender 5.1.2 official extension command was then run once after this
scoped GPL replacement:

```powershell
& 'D:\Program Files\Blender\blender.exe' --command extension validate `
  --valid-tags='' D:\BlendLib\blender-addon
```

Observed result: `Success parsing TOML in "D:\BlendLib\blender-addon"` and
`BLENDLIB_GPL_EXTENSION_VALIDATE_EXIT=0`.

No ZIP was built, installed, distributed, published, pushed, tagged, or
deployed. P8 still owns actual add-on artifact packaging, third-party license
inventory, and final license decisions for non-addon components.
