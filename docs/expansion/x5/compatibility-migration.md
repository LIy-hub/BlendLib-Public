# X5 compatibility and migration notes

P2 runtime assets and entrypoints remain compatible. The strict-v1 source
validator is aligned with the X5 binding authority as a validation repair:

- `blender-addon/scripts/export_blendlib.py` continues to invoke P2
  `run_cli`/`export_open_blend` and produces its existing report shape.
- The original Blender `BlendLib v1 Export` panel still performs its existing
  strict-v1 export.
- Descriptor schema, profile names, material fields, and descriptor extension
  rules are not modified.
- Skinned source validation requires one bound exported Armature and calculates
  effective weights only from groups named by that target's bones. Positive or
  zero-weight decorative non-bone groups remain authoring-only.

To opt into X5, run `x5_export_blendlib.py` after Blender's `--` separator or
use the X5 panel. The X5 exporter invokes the same strict-v1 exporter only in a
private staging directory, then atomically publishes its outputs and separate
authoring files. A failed preflight, duplicate batch ID, unsafe path, or failed
replace leaves prior published targets restored; it never falls back to writing
new runtime semantics into `extensions`.

The current exact-source publication backend is supported on Windows. On POSIX
the X5 entrypoint fails before project-root creation, private staging, or legacy
export; it does not downgrade to pathname `rename` or `renameat2`. Projects
that need X5 publication on POSIX must wait for a separately reviewed backend,
while their existing P2 export path remains unchanged.

Windows X5 publication does not opt into `\\?\`/`\\.\` extended or device
paths. Keep the project root and model IDs short enough for ordinary Win32
paths: final file leaves are capped at 259 visible UTF-16 units and every
directory X5 may create is capped at 247. The preflight accounts for public
parents, private legacy/stage/backup trees, nested texture directories, and
the legacy `.raw.glb` scratch leaf; a too-long plan fails as
`BLENDLIB-X5-PATH-005` before it creates the project root or any private stage
or calls the legacy exporter. This is an X5 authoring constraint only; it does
not alter existing P2 exports or runtime asset formats.

X5 binds the approved project root and existing output-parent directories by
physical identity from planning through publication. Renaming and recreating a
directory at the same spelling therefore blocks the run rather than publishing
into the replacement tree. A batch may create a previously absent project root
once; each later item reopens that exact created root. The legacy strict-v1
exporter remains a trusted compatibility seam: it receives only a private stage
root, never a public report/refresh/manifest path, and X5 retains uncertain
private stage content for recovery instead of recursively deleting it.

On the supported Windows backend, this physical identity is the no-follow
Win32 `FileIdInfo` identity (64-bit volume serial plus 128-bit file ID), not a
CPython- or Blender-Python-specific `st_dev` value. Each generic publication
stage, authoritative old/backup, and installed public leaf keeps one content
handle that disallows external write/delete sharing. X5 hashes that exact handle
before and after moves, locks all existing public targets before the first
replacement, and restores only from the still-open authoritative backup handle.
An incompatible pre-existing writer fails before public replacement; a later
content, identity, or rollback uncertainty retains recovery backup rather than
claiming that foreign bytes were restored.

Existing P2 assets work without an X5 sidecar. X5 consumers must treat a
missing sidecar as "no authoring annotation," never as an invalid strict-v1
runtime asset. Conversely, X5 authoring reports/sidecars are not runtime
resources and no runtime loader may begin reading them as a compatibility
shortcut.

Existing projects may keep external PNGs in a shared directory anywhere below
the project root, even when it is outside the `.blend` directory. X5 resolves
and freezes that original allowlist before private staging; no migration into
the source blend directory is required. Authoring outputs that previously
targeted `build/resources/main` must move to `build/blendlib-authoring` (or
another non-runtime project-relative directory).

Large authoring sources are no longer implicitly constrained by the GLB
64 MiB limit. Existing `.blend` files up to the independent 1 GiB authoring
source cap retain fixed-buffer hashing; runtime GLB/descriptor/PNG limits stay
unchanged.

Rigid assets do not need to delete decorative Blender vertex groups. X5 records
them as authoring facts but does not validate or report them as runtime skin
weights unless the mesh uses the skinned profile with exactly one exported
Armature target and the group name matches one of that target's bones. Projects
whose skinned meshes relied on missing/multiple modifiers, unexported targets,
or non-bone groups as effective weights must repair the binding before X5 will
create a private export stage.
