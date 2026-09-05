# X5 Blender-native authoring toolchain

X5 adds authoring-time assistance around the existing strict-v1 Blender export.
It does not add a Minecraft runtime format, a new public API/SPI/provider, a
network service, or a `.blend`/FBX/OBJ runtime path.

## Deliverables and ownership

| Surface | X5 deliverable | Runtime status |
|---|---|---|
| Blender | preflight, one-click/batch export, panel/operators, reversible viewport preview/debug, headless verifier | authoring only |
| Disk | strict-v1 GLB/descriptor/external PNG plus separate authoring sidecar/report | runtime reads only the existing strict-v1 assets |
| Pure Java core | local validation service and deterministic CLI | caller-owned local tooling only |
| Dev preview | session-bound filesystem message protocol and caller-polled adapter seam | proposal only; no client binding or listener is shipped |

The module is deliberately additive to P2. `blendlib_exporter.py` remains the
strict-v1 exporter, and `x5_export_blendlib.py` is an explicit opt-in entrypoint.
No X5 fact is inserted into a descriptor's `extensions`, `extensions_used`, or
`extensions_required` fields.

## Versioned X5 files

- Sidecar: `blendlib-x5-authoring-sidecar-v1`, schema `1.0.0`.
- Report: `blendlib-x5-asset-report-v1`, schema `1.0.0`.
- Dev refresh: `blendlib-x5-dev-refresh-v1`.
- Batch manifest: `blendlib-x5-batch-manifest-v1`.

Consumers fail closed on an unknown format/schema or unknown protocol field.
`1.x` changes must preserve current required fields and meanings; a removal,
reinterpretation, or runtime-boundary change requires a new format identifier.

All semantic mapping inputs and the aggregate emitted mapping are bounded at
4,096 items before sidecar construction. Bound and NLA Actions on the actual
export object set are the shared animation source for preflight, sidecar, and
strict GLB validation; unrelated global/fake-user Actions are not mappings.
The preflight result owns a deep immutable, non-aliased snapshot carrying every
sorted diagnostic. Its caller-visible diagnostics are presentation copies;
all gates, reports, and UI output use the snapshot's identity-bound canonical
records. Sidecar/report/refresh publication uses compact canonical
UTF-8 with an inclusive 512 KiB writer cap; known report growth is budgeted
before private legacy staging and all final documents are checked again before
target replacement.
Reusable frozen artifacts also require internal identity-bound provenance;
class identity and caller-provided diagnostics are not trusted. Integer-only
fields use exact built-in integers within the non-negative Java `long` range,
so Python boolean and integer-subclass coercion cannot change wire meaning.
The provenance registry is keyed by object identity and verifies its weak
reference points to that exact artifact; equality and hashing are never part
of lookup. Authoritative diagnostics are independent immutable field tuples,
not caller-visible diagnostic objects.
The result is also registered by exact identity and snapshot generation.
Direct, copied, derived, or cross-snapshot results fail closed before export
side effects, while a valid result's ERROR decision is a constant-time lookup.
The public preflight entry accepts only an unfrozen normalized mapping. Passing
any frozen snapshot back into it is rejected in constant time before freezing,
checks, paths, staging, or legacy exporter access; consumers reuse the snapshot
only through its bound result, sidecar, and report seams.

One-click and batch export next create an identity-registered immutable
publication plan before any project-root/stage mutation. The plan's complete
artifact graph is derived from the strict-v1 exporter's shared pure naming
helper and contains every descriptor, GLB, external PNG, sidecar, report, and
optional refresh target. Resolved symlink/junction aliases, Windows case
identity, duplicate paths, and file/directory conflicts fail before legacy
export. The only legal duplicate is one explicit report equal to that item's
default report because the canonical bytes are the same; all other collisions
preserve old files and block the entire batch before staging.

On Windows, X5 intentionally uses ordinary, non-extended Win32 paths. It
rejects `\\?\`/`\\.\` roots and performs a `BLENDLIB-X5-PATH-005` preflight
before any root/stage/legacy side effect when a file leaf would exceed 259
visible UTF-16 units or a directory it may create would exceed 247. The budget
includes the resolved public tree, private legacy/stage/backup trees, nested
texture parents, and the strict-v1 `.raw.glb` scratch path; users should choose
a shorter project root or model ID rather than relying on long-path support.

The plan also carries physical publication approval: X5 binds the existing
project root (or its approval-time ancestor plus an exact missing suffix) and
each existing output-parent chain. Prepare and writer reopen those bindings,
so a directory removed and recreated at the same spelling is rejected. A batch
may create an initially absent root once and then reuses that exact binding.
The current exact-source move backend is Windows-only. It obtains Windows
directory and leaf identity from no-follow `FileIdInfo` handles rather than
Python `st_dev`, keeps each staged/backup/installed leaf on one handle that
denies external write/delete sharing, and rechecks its approved SHA-256 through
that same handle across moves and rollback. Existing public leaves are all
locked before the first replacement; an incompatible writer fails closed while
old bytes are still public. POSIX X5 publication fails before
root/stage/legacy side effects instead of falling back to pathname rename
semantics. Legacy strict-v1 export remains a trusted private-stage
compatibility seam, with report/refresh/manifest writes disabled until X5
publishes approved bytes.

Every Blender mesh snapshot also binds the selected strict-v1 profile to the
actual Armature modifier set, modifier target/export membership, target bone
order, and per-vertex group assignments. Rigid meshes may retain decorative
vertex groups because they do not become runtime skin data. Skinned meshes
require exactly one exported Armature target, and only assignments whose group
name is a target bone are effective runtime influences.

Read the focused contracts in:

- [authoring-sidecar-v1.md](authoring-sidecar-v1.md)
- [export-preflight-and-batch-v1.md](export-preflight-and-batch-v1.md)
- [dev-preview-protocol-v1.md](dev-preview-protocol-v1.md)
- [cli-gradle-integration.md](cli-gradle-integration.md)
- [gui-workflow-and-evidence.md](gui-workflow-and-evidence.md)
- [compatibility-migration.md](compatibility-migration.md)
- [ADR-X5005](../adr/ADR-X5005-single-pass-preflight-and-runtime-skin-binding.md)
