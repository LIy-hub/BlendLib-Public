# ADR-X5001: Keep Blender-only semantics in a separate X5 authoring sidecar

## Status

Proposed/Experimental implementation candidate. X5's isolated track was independently reviewed by /root/x5_postcommit_review_r3 (gpt-5.6-sol/max), Verdict: PASS with no findings. This local X1/X5/X9 integration candidate still awaits a fresh gpt-5.6-sol/max integration review; this status does not accept the proposal or change runtime scope.

## Context

Blender artists need socket, variant, LOD, collision, timeline-event, and
bounded custom-property annotations. The strict-v1 descriptor and loader have
an intentionally narrow runtime contract and reserve no general extension
channel for this purpose.

## Decision

Emit a versioned authoring sidecar below a project-relative authoring root that
resolves outside the configured output root, `src/main/resources`, and
`build/resources/main`, with realpath-aware alias checks. Map Blender
semantics there and declare all four positive runtime-boundary fields: no
descriptor extensions, no `.blend`/FBX/OBJ runtime read, authoring-only
collision references, and presentation-only visual events. The pure-Java
validator parses the full bounded mapping/metadata schema only as local tooling
input, verifies Action clips and material modes against the strict runtime
asset, and never supplies the sidecar to `ModelAssetLoader`.

Metadata is grouped by an unambiguous normalized object key, capped at 64
entries per object and 4,096 overall, and rejects secret-bearing key/value
markers without echoing them in diagnostics. One shared preflight/sidecar
collector records both source-object identity and complete canonical-entry
provenance, so different names that normalize alike and every duplicate entry
fail closed before publication rather than overwriting a prior value.
Canonical sidecar UTF-8 bytes are generated incrementally under the same
512 KiB inclusive cap enforced by Java; oversize generation fails before any
private export stage or legacy exporter invocation.

All mapping source categories and emitted mapping arrays are capped at 4,096,
and the emitted mapping aggregate is independently capped at 4,096 before list
construction. Arbitrary Python iterables are consumed once into the preflight's
frozen snapshot; the 4,097th item and iterator acquisition/mid-stream failure
are stable non-reflective mapping diagnostics. Sidecar construction reuses that
snapshot rather than touching a one-shot source again. Action mappings come
only from active/NLA Actions on the exact export object set. The strict exporter
and X5 snapshot share that discovery, and final GLB validation cross-checks the
same clip names; global fake-user Actions cannot enter the sidecar alone.

The snapshot freeze recursively copies supported mappings into immutable
mapping proxies and lists/tuples into tuples under explicit depth, total-value,
and text limits. Cyclic, unsupported, or failed nested input produces one
bounded non-reflective snapshot diagnostic. The snapshot owns the complete
sorted preflight diagnostic tuple, and sidecar construction rejects every
ERROR family whether called with a raw input or an already-frozen result;
WARN-only results remain deterministic and usable. A final snapshot is trusted
only when its exact values and diagnostic tuple identities are registered by
the private preflight factory; authoritative diagnostic fields are copied
separately. The constructor token alone is not authority:
directly constructed, copied, pickled, unregistered, or slot-tampered snapshot
objects fail closed. A valid final artifact retains constant-time reuse without
re-reading or re-freezing authoring input.

Implement that provenance as an identity-indexed weak registry whose lookup
requires the exact snapshot type and `weakref() is snapshot`; it never invokes
snapshot equality or hashing. A per-entry generation token plus reference
identity guards finalizer cleanup against stale callbacks and object-ID reuse,
while ordinary collection releases the entry. Store authoritative diagnostics
as separately copied immutable tuples of exact string fields. Public diagnostic
objects remain presentation values: field mutation cannot change the ERROR/WARN
decision, and replacing the snapshot's exposed container invalidates snapshot
provenance.

Bind each factory-created `PreflightResult` by exact identity to the exact
registered snapshot generation through a separate weak registry. Treat the
result's public diagnostic tuple and entries as presentation only: replacement
or mutation cannot affect `ok`, preflight JSON, reports, batch/export gates, or
UI diagnostics. Reject direct construction, copying, subclasses, snapshot
replacement, and cross-snapshot pairing as `BLENDLIB-X5-SNAPSHOT-001`. The
result registry uses the same generation-checked weak-finalizer discipline as
the snapshot registry, and the registered first-ERROR record keeps the
blocking judgment constant-time.

Every integer-only authoring field requires the exact built-in Python `int`
type, is non-negative, and fits a signed Java `long`. Booleans, `IntEnum`
members, custom `int` subclasses, negatives, and values at or above `2^63`
are rejected so Python publication and pure-Java validation have one numeric
contract.

## Consequences

- Existing strict-v1 runtime assets and P2 behavior remain compatible.
- Sidecar use requires an explicit authoring/tooling consumer.
- Sidecar contents cannot silently alter material, loader, collision, or
  renderer authority.

## Alternatives rejected

- Descriptor `extensions`: conflicts with the existing strict extension
  boundary and risks accidental runtime interpretation.
- Embed Blender data in GLB extras: expands runtime asset semantics and makes
  compatibility/audit harder.
- Read `.blend` at runtime: violates the source-art/runtime boundary.
