# X5 authoring sidecar v1

The X5 sidecar is written under a project-relative authoring root (default
`build/blendlib-authoring`). It is not written below `assets/<namespace>` and
is never a strict loader input. The configured authoring root, explicit report,
and refresh message must all resolve outside every actual runtime resource
tree. Python and Java use the same bounded default set: the configured output
resource root, `src/main/resources`, and `build/resources/main`. Normalized
paths and existing symlink or junction aliases are checked by resolved
identity. The sidecar declares this boundary itself:

```json
{
  "format": "blendlib-x5-authoring-sidecar-v1",
  "runtime_boundary": {
    "collision_references_are_authoring_only": true,
    "descriptor_extensions_are_not_used": true,
    "runtime_reads_blend_fbx_obj": false,
    "visual_events_are_presentation_only": true
  },
  "schema_version": "1.0.0"
}
```

Publication validates the full artifact graph before it creates a private
legacy stage: descriptor, GLB, all strict-v1 external PNGs, sidecar, default
report, optional explicit report, and optional refresh are compared by resolved
Windows filesystem identity. Case variants, dot-segment spellings, and existing
symlink/junction aliases cannot bypass the comparison. A report or refresh
target that collides with this sidecar (or any runtime artifact) is rejected
without replacing prior bytes. The only intentional duplicate is an explicit
report resolving to its own default report target, because both payloads are
the same canonical asset-report bytes.

## Semantic mapping

| Blender source | Sidecar mapping | Runtime implication |
|---|---|---|
| Empty | `mapping.empty_sockets` | authoring label only; strict descriptor remains the runtime authority |
| Collection | `collection_groups_variants` | group/variant authoring annotation |
| Bound/NLA Action on exported objects | `action_animation_clips` | same discovered Action set drives sidecar and strict GLB validation |
| `blendlib_*` custom property | `authoring_metadata` | scalar, bounded annotation only |
| Material | `material_definitions` | records source name/mode without changing descriptor material schema |
| `LOD_<n>` Collection | `lod_levels` | authoring LOD record; no runtime LOD selection is introduced |
| `Collision`/`Collider` Collection | `collision_references` | explicitly `read_only`, `authoring_only`, and `runtime_authority: never` |
| `event:<id>` marker | `timeline_visual_events` | presentation-only timeline note |

Custom properties are only collected when their key begins `blendlib_`. At most
64 are accepted per object and 4,096 overall. Serialized keys use the
unambiguous portable grammar `object.<normalized-object>.<normalized-key>`;
object components are lower-case `[a-z0-9_-]+`, while metadata components use
lower-case `[a-z0-9._/-]+`, each capped at 128 characters without `..` or `//`.
Before preflight can succeed or sidecar bytes can be built, the exporter keeps
a source-object-to-normalized-object identity map and a canonical-entry
provenance map. Different source objects such as `Obj.A` and `Obj/A` may not
collapse to the same normalized object, and no repeated source object, raw
property spelling, or normalized property spelling may produce an already
claimed canonical entry. These collisions are blocking and identify only the
source object location; property values are never echoed. The 4,096-entry
aggregate limit is enforced by this same collector before publication begins.
Strings are capped at 256 characters and values are finite scalar
booleans/numbers/text. Keys and text containing password/passwd, secret, token,
credential, API-key, or private-key markers are rejected. Diagnostics use a
bounded entry index and never echo the rejected key or value. Unsupported or
unbounded input is a blocking `BLENDLIB-X5-METADATA-*` diagnostic.

Objects, collections, bound/NLA Actions, materials, and markers accept any
Python iterable and are consumed exactly once into one frozen preflight
snapshot. Consumption stops after probing the 4,097th item: that item is a
blocking `BLENDLIB-X5-MAPPING-001`, while iterator acquisition or mid-stream
failure is the stable, non-reflective `BLENDLIB-X5-MAPPING-003`. The exception
text and yielded content are not copied into that diagnostic. The sidecar uses
the returned frozen snapshot, so a one-shot source is never consumed twice.
The freeze is deep rather than tuple-only: every supported mapping is copied
behind an immutable mapping proxy, every list/tuple is copied to an immutable
tuple, and scalar text/numbers/booleans/null are retained only within the
32-level, 131,072-value, and 4,096-character snapshot limits. Cycles,
unsupported values, failed nested iteration, or budget exhaustion produce the
stable non-reflective `BLENDLIB-X5-SNAPSHOT-001`; no source container remains
aliased into the preflight result. The frozen snapshot carries the complete
sorted preflight diagnostic tuple. Sidecar construction fails closed on every
ERROR family, including scene, transform, material/texture, metadata, path,
profile, and coordinate errors; deterministic WARN-only snapshots remain
usable.
Only the final internal preflight factory can register a reusable frozen
artifact. A private constructor token is necessary but not sufficient:
sidecar reuse also requires an identity-bound registry entry whose authoritative
immutable values and exposed diagnostic container are the exact objects still
held by the artifact. Direct construction, a copied/pickled artifact,
caller-supplied or reordered diagnostics, and post-construction slot
replacement are rejected as `BLENDLIB-X5-SNAPSHOT-001`. This preserves
one-shot reuse without trusting a
Python class identity or self-reported diagnostics.
Registry lookup uses `id(artifact)` only as an index, then requires both the
exact `_FrozenSnapshot` type and `weakref() is artifact`; Python equality and
hash implementations are never invoked. Each entry carries a unique
generation token. Its weakref finalizer removes the entry only when identity,
generation, and reference all still match, so stale cleanup cannot delete a
replacement after identity reuse. Collection releases registry entries through
weak references and does not retain the artifact.

The registry's authoritative diagnostic state is a separately copied tuple of
exact-string `(severity, code, location, message, remediation)` tuples.
Caller-visible `ToolingDiagnostic` objects are presentation copies rather than
authority. Replacing the snapshot's exposed container rejects the artifact;
mutating any
exposed entry or an aliased preflight-result diagnostic cannot remove, reorder,
or rewrite the canonical ERROR/WARN state used at the sidecar boundary. The
registered first-ERROR record makes the provenance and blocking check
constant-time for a valid reusable artifact.
`PreflightResult` has a second exact-identity weak registration that binds one
result generation to one snapshot generation. Its public `diagnostics` tuple
and entries are presentation copies: replacing or mutating them does not change
`ok`, `report()`, batch/export gating, report diagnostics, or UI messages.
Replacing the bound `snapshot`, copying the result, constructing a result
directly, deriving a result subclass, or pairing diagnostics with another
snapshot loses authority and fails closed as `BLENDLIB-X5-SNAPSHOT-001` before
stage creation or legacy-exporter access. Both registrations use guarded weak
finalizers, so authority does not retain otherwise unreachable results.
Each emitted mapping array is capped at 4,096 and the seven mapping arrays
together are capped at 4,096; this is separate from the metadata limit above.
No mapping failure creates a sidecar list, private export stage, or legacy
exporter. Global/fake-user Actions that are not bound to an exported object or
one of its NLA strips are excluded. The strict exporter validates its final GLB
against that same Action discovery, including valid NLA clips, before
publication.

The emitted sidecar is canonical UTF-8 JSON and is capped at exactly 512 KiB,
matching the pure-Java reader. Canonical serialization is incremental and
stops before appending a fragment that would cross the cap. Exactly 524,288
bytes are accepted; 524,289 are rejected with
`BLENDLIB-X5-SIDECAR-002` before a private export stage or legacy exporter is
created.

The sidecar has no absolute source path, URI, host environment value, secret,
or descriptor extension payload. It can be retained by authoring tooling but
must not be mounted as a Minecraft runtime asset resource.

The pure-Java validator requires the complete mapping schema and all four
runtime-boundary fields. Action clip names and material name/mode mappings must
exactly match the strict runtime GLB/descriptor; a sidecar cannot claim zero
clips when the runtime asset contains one. It also rejects a resolved sidecar
or report beneath any configured runtime root. The default request covers the
explicit resource root, `src/main/resources`, and `build/resources/main`;
resolved symlink/junction aliases are treated as the same tree while the
ordinary `build/blendlib-authoring` root remains authoring-only.

Canonical semantic JSON uses sorted object keys, UTF-8 strings, and finite
numbers rendered as plain minimal decimals: negative zero is `0`, `1e-7` is
`0.0000001`, and `1e20` is `100000000000000000000`. NaN and infinities are
rejected. `sidecar_sha256` hashes these canonical semantic bytes. Artifact-map
SHA-256 entries instead hash the exact bytes of each file on disk; the two hash
meanings are deliberately distinct.

Integer-only source fields use exact built-in Python `int` values, never
`bool`, `IntEnum`, or custom `int` subclasses. Counts and derived LOD levels
are non-negative and bounded to the Java signed-`long` maximum before they can
enter the sidecar/report contract.
