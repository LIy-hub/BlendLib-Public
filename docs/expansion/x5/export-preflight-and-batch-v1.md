# X5 preflight, batch, and deterministic publication

## Blocking preflight

X5 runs a pure normalized-scene preflight before the legacy strict-v1 exporter
is allowed to stage output. Stable tooling-only diagnostics use
`BLENDLIB-X5-*`; core strict-loader and P2 codes are not redefined.

| Area | Representative blocking code |
|---|---|
| root/object identity | `BLENDLIB-X5-SCENE-001` through `-004` |
| transforms | `BLENDLIB-X5-TRANSFORM-001`/`-002` |
| triangle/UV/normal | `BLENDLIB-X5-MESH-001` through `-003` |
| armature hierarchy/binding | `BLENDLIB-X5-ARMATURE-001` through `-005` |
| skin weights | `BLENDLIB-X5-WEIGHT-001`/`-002` |
| profile/binding identity | `BLENDLIB-X5-PROFILE-001`/`-002` |
| material/image | `BLENDLIB-X5-MATERIAL-*`, `BLENDLIB-X5-TEXTURE-*` |
| action/events | `BLENDLIB-X5-ACTION-*`, `BLENDLIB-X5-EVENT-*` |
| LOD/collision | `BLENDLIB-X5-LOD-*`, `BLENDLIB-X5-COLLISION-*` |
| units/coordinates | `BLENDLIB-X5-COORD-001`/`-002` |
| bounded metadata | `BLENDLIB-X5-METADATA-*` |
| bounded mappings | `BLENDLIB-X5-MAPPING-001`/`-002` |
| bounded immutable snapshot | `BLENDLIB-X5-SNAPSHOT-001` |
| Windows legacy publication capacity | `BLENDLIB-X5-PATH-005` |

Warnings, including high LOD triangle count, are placed deterministically in
the report's `performance_warnings`; errors stop export before publication.
External PNG source paths must resolve to an existing regular file beneath the
blend-file directory or authorized project root. Traversal and symlink escape
are rejected before the legacy exporter stages bytes. The legacy exporter also
pre-stats and caps raw/final GLB and PNG files; GLB header/chunk reads, PNG
copies, and file hashes all use fixed 8 KiB requests and verify EOF against the
declared stat size. X5 freezes the resolved blend-directory/project-root PNG
allowlist before replacing `project_root` with its private stage, so a shared
project texture outside the `.blend` directory remains accepted by both
preflight and the staged legacy copy.

Action discovery is shared with the strict exporter: only active Actions and
NLA-strip Actions attached to the actual export object set are snapshotted.
Unrelated fake-user Actions are ignored, while bound/NLA clips remain present.
The final strict GLB validation still requires the same sorted clip-name set,
so a sidecar/GLB Action mismatch cannot publish.

Public normalized-scene preflight is single-pass. A `_FrozenSnapshot` is an
already-decided authority artifact and cannot be supplied to
`preflight_snapshot` again, whether it contains WARN or ERROR records. The
entry rejects that type in constant time before snapshot consumption or any
validation check. Sidecar/report construction instead accepts the exact
identity-registered frozen artifact and reconstructs one canonical diagnostic
set, preventing repeated validation from duplicating report diagnostics or the
WARN subset.

For each real Blender mesh, normalization freezes the chosen export profile,
all Armature modifiers, each target's exported/type/bone facts, and each
vertex-group assignment. Rigid profile requires zero Armature modifiers and
does not interpret decorative vertex groups as skin weights. Skinned profile
requires exactly one modifier per mesh targeting an exported Armature whose
ordered bones match the captured target. Weight validation filters assignments
to those target-bone names before enforcing one-to-four finite non-negative
effective influences and a unit sum. Non-bone groups are authoring decoration;
they neither repair a missing bone influence nor alter the runtime sum.
Binding/profile mismatch, a missing/multiple modifier, an unbound/unexported or
wrong target, and bone-group failures are public preflight errors, so one-click,
batch, and UI paths stop before legacy validation, private staging, or
publication. Report `vertex_weight_records` is `0` for rigid and the validated
strict-v1 GLB vertex count for skinned, matching the Java validator's runtime
primitive count even when glTF splits source vertices at seams.

## Atomic bundle contract

One-click and batch publication follow the same sequence:

1. Validate all inputs, deep-freeze the normalized snapshot, complete metadata
   identity checks, and bounded-serialize the canonical sidecar within 512 KiB.
   Carry every sorted ERROR/WARN into that immutable snapshot and reject every
   ERROR at the sidecar boundary. For batch, sort by `(namespace,
   model_id, collection)` and reject duplicate logical IDs.
   The sidecar decision reads an independent immutable field-tuple copy of
   those diagnostics from an exact-identity weak registry. Mutating a public
   diagnostic object cannot downgrade or rewrite the authoritative result.
   The enclosing preflight result is itself registered by exact identity and
   bound to that exact snapshot generation. Unregistered, copied, derived, or
   cross-snapshot results fail closed before path checks, stage creation, or
   legacy-exporter access. The blocking decision reads the registered
   first-ERROR record in constant time.
   Every sorted batch item completes this step before any item enters report
   preparation, project-root creation, private staging, or legacy export.
2. Build one immutable publication plan and complete resolved artifact graph
   before creating a project root or a private stage. The graph uses the
   strict-v1 exporter's shared pure naming helper for the descriptor, GLB, and
   each external PNG, then adds the sidecar, default report, optional explicit
   report, and optional refresh file. It normalizes dot-segment spelling,
   existing symlink/junction aliases, and Windows case identity; it rejects
   equal paths, file/directory ancestor relationships, existing directories or
   non-regular targets, and non-directory parents. The only permitted duplicate
   is an explicit report at the same canonical identity as that same item's
   default report, because the bytes are identical. A report can never replace
   a sidecar, runtime artifact, refresh message, or another batch item's
   artifact. In batch, validate the union of every retained plan before any
   item is prepared, so texture-slug collisions such as `a/b` and `a_b` fail as
   `BLENDLIB-X5-BATCH-002` with both owners and kinds in deterministic order.
   The identity-registered plan owns an immutable copy of options, claims, and
   sidecar bytes; copied, forged, or mutated public plan state is rejected.
   It also binds the existing project root (or its nearest existing ancestor
   plus an exact missing suffix) and every existing output-parent chain by
   physical identity. A same-spelling replacement is not a valid new baseline.
   On Windows, immediately after this valid graph (and after the valid batch
   union graph), X5 performs its no-long-path capacity gate. It accepts only
   ordinary project-root spellings, rejects `\\?\` and `\\.\`, and counts
   UTF-16 units rather than Python code points: file leaves may use at most 259
   visible units and every directory X5 might create may use at most 247. The
   check covers every missing root suffix, physical public parent, private
   legacy/stage/backup root, nested artifact/texture parent, and the
   private `.raw.glb` scratch path. `BLENDLIB-X5-PATH-005` therefore occurs
   before root/stage/legacy side effects and never leaks an absolute host path.
   A graph collision is intentionally reported first as `BLENDLIB-X5-BATCH-002`.
3. Build a conservative report envelope from the known diagnostics, exact
   planned artifact paths, and fixed-size hashes, then bounded-canonicalize it.
   A diagnostics/artifact report that can exceed 512 KiB is rejected before a
   private legacy stage is created.
4. Require the exact-source publication capability before any root creation,
   private stage, or legacy-exporter call. The current implementation supports
   that capability on Windows. POSIX rejects at this boundary and never uses a
   pathname `rename`/`renameat2` fallback.
5. Reopen the approved project-root binding, creating only a plan-approved
   missing suffix through its lease, then export every strict-v1 item only to a
   private project-local staging directory. A batch with an initially missing
   root creates that root once and later items reopen the first item's physical
   binding. The staged runtime file set must exactly equal the already-approved
   descriptor/GLB/PNG set; an unexpected or missing staged file fails before
   publication.
6. Build the final report from the bounded canonical sidecar, sorted JSON, and
   SHA-256 hashes, and enforce the same inclusive cap again.
7. Reopen the prepared root binding and revalidate every physical output-parent
   chain before writing target bytes to a second private staging directory,
   flushing and hashing every leaf through its transaction-lifetime Windows
   content handle. Windows obtains directory/leaf identity only from no-follow
   `FileIdInfo` handles (not Python `st_dev`), and each stage, backup, and
   installed public leaf denies external WRITE and DELETE sharing. Before the
   first public move, it locks every existing public target, hashes those
   authoritative old bytes, then moves them to private backup with the same
   source handles. Staged handles install the approved payloads and remain live
   for their post-install digest checks.
8. If a replacement fails, remove each installed public leaf through its own
   retained handle before restoring the still-open authoritative backup handle
   into the now-empty target. A successful rollback removes both private
   directories. If a content/identity check, sharing conflict, or rollback
   operation itself fails, the error identifies affected project-relative
   targets and the private backup directory is preserved so prior bytes remain
   recoverable. Handles are released only after this outcome is known.

This is staged replacement with best-effort rollback, not a cross-file or
cross-filesystem transaction. The standard-library tests separately prove the
success-cleanup case, successful rollback/restoration case, and an injected
rollback failure that preserves the recoverable backup.

Stage creation, backup creation, target resolution, staging, and replacement
all live under the same cleanup guard. A target-resolution/pre-transaction
failure therefore removes the new stage and empty backup. This does not weaken
the later rollback rule: if restoring old bytes fails, the non-empty backup is
still deliberately retained.

Private legacy preparation has the same lease discipline. X5 passes legacy
code only the stage root and disables its report, refresh, and manifest paths.
On success it removes the known stage leaves/directories while their original
leases remain live. On error it inventories only approved leaves; an unknown,
non-regular, or identity-changed entry is retained rather than rediscovered by
pathname or recursively deleted. The recovery error names only bounded private
stage information. This is a trusted compatibility seam, not a sandbox for
arbitrary legacy code.

`asset-report` is published as deterministic compact canonical UTF-8 JSON and
contains only
project-relative artifact keys, artifact SHA-256 values, counts, sorted
diagnostics, warning subset, model identity, and the canonical semantic sidecar
SHA-256. Artifact-map hashes cover exact file bytes; semantic report/sidecar
hashes first apply sorted-key, plain-finite-decimal canonical JSON. It never
copies the legacy P2 absolute-path export report into X5 output.
An explicit report path publishes the same canonical asset-report bytes as the
default authoring-report path. It does not serialize the command-result envelope
whose format is `blendlib-x5-export-result-v1`.
If the explicit and default report resolve to the same target, the bundle has
one report entry rather than overwriting another artifact; this is the only
deduplication case in the publication graph.
The public `PreflightResult.diagnostics` field and the `diagnostics` report
builder argument are compatibility/presentation surfaces, never authority.
Preflight JSON, asset-report diagnostics, the WARN subset, batch/export errors,
and Blender UI messages are reconstructed from the bound snapshot's canonical
field records. Caller replacement or entry mutation therefore cannot create a
false rejection, suppress a real error, or inject a report row.

The pure-Java validator checks the complete bounded report schema, exact
runtime/sidecar-derived counts, diagnostic object shape, and that
`performance_warnings` exactly equals the ordered WARN subset of `diagnostics`.
Diagnostics themselves must be unique and sorted exactly like Python by
severity (`ERROR`, `WARN`, `INFO`), then Unicode code-point order of code,
location, and message. Reversing both arrays together is invalid, as are
duplicate diagnostics or non-WARN performance entries.

Input is bounded before allocation: report/sidecar and refresh/batch documents
are capped at 512 KiB; descriptor/GLB and external PNG inputs are capped at
64 MiB each. Readers resolve an authorized regular file, stat it, reject a
sparse oversize file, then consume it only through fixed 8 KiB requests plus a
one-byte growth probe. Small JSON payloads may be accumulated dynamically only
after their 512 KiB cap is established; no reader preallocates a caller-claimed
size. GLB chunk reads, PNG copy/validation, and artifact hashing reject
shrink/growth rather than calling an unbounded `readAllBytes`, `read_bytes`, or
`copyfile` first.

Normalized mapping fields may be lists, tuples, generators, or other Python
iterables. Preflight performs the only bounded consumption, then recursively
copies supported mapping/list/scalar values into immutable mapping proxies and
tuples for sidecar/report reuse. A 4,097th item, cycle, unsupported nested
value, depth/item/text budget failure, or iterator failure is therefore
blocking before stage creation; diagnostic text never reflects the value or
iterator exception. Finite-number checks accept only built-in int/float and
Decimal values that convert safely to a finite double; overflow, non-finite
Decimal values, and conversion failure become bounded metadata diagnostics.
Integer-only authoring fields instead require `type(value) is int` and a
non-negative signed-64-bit range. This rule covers refresh generation/clock,
root and topology counts, LOD level/triangle counts, and report index/vertex
counts. Python booleans, `IntEnum`, custom integer subclasses, negatives, and
values above Java `long` are rejected before serialization; Java already keeps
JSON booleans distinct from `JsonNumber` and applies exact non-negative-long
parsing.

Source `.blend` hashing uses a distinct 1 GiB authoring-source cap rather than
the 64 MiB GLB cap. It remains a resolved regular-file read with fixed requests
of at most 8 KiB, declared-size/growth/shrink checks, and an explicit source
root. Descriptor, GLB, PNG, sidecar, report, refresh, and batch limits are not
changed by this compatibility allowance.
