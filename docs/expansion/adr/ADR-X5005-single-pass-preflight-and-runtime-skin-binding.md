# ADR-X5005: Keep preflight single-pass and bind weights to runtime skin facts

## Status

Proposed/Experimental implementation candidate. X5's isolated track was independently reviewed by /root/x5_postcommit_review_r3 (gpt-5.6-sol/max), Verdict: PASS with no findings. This local X1/X5/X9 integration candidate still awaits a fresh gpt-5.6-sol/max integration review; this status does not accept the proposal or change runtime scope.

## Context

An X5 frozen snapshot already carries its canonical diagnostic decision. A
second public preflight over that artifact imported the old records and ran all
checks again, producing duplicate diagnostics that the Java asset-report
validator correctly rejects.

Blender vertex groups are also not synonymous with runtime skin influences.
Rigid meshes commonly use decorative groups, while a skinned influence is
meaningful only through the selected profile, one Armature modifier, its actual
target, and a group name matching a target bone. Counting raw source groups can
also disagree with the runtime GLB after Blender splits vertices.

## Decision

- Accept only unfrozen normalized mappings at `preflight_snapshot`. Reject any
  `_FrozenSnapshot` immediately with `BLENDLIB-X5-SNAPSHOT-001`; reuse is
  available only through the identity-bound result, sidecar, and report seams.
- Freeze the selected strict-v1 profile plus per-mesh Armature modifier,
  target/export-membership/type/ordered-bone, and vertex-group assignment facts.
- Require zero Armature modifiers for rigid meshes and exactly one exported,
  bone-consistent Armature target for every skinned mesh.
- Validate only effective assignments whose group name is a target bone.
  Decorative/non-bone groups are ignored; they cannot satisfy a missing runtime
  influence. Effective influences remain bounded to one through four and must
  be finite, non-negative, and normalized.
- Apply that same exported-target bone set in the legacy strict-v1 source
  validator invoked by public preflight and private export. The legacy check
  also requires exactly one bound exported Armature, so it cannot reinterpret
  decorative groups or accept a binding rejected by the frozen X5 authority.
- Stop binding failures in public preflight before legacy validation, private
  staging, sidecar/report publication, batch commit, or UI export.
- Derive report `vertex_weight_records` from the validated runtime result: zero
  for rigid and strict-v1 GLB vertex count for skinned. This is the same rule as
  the pure-Java validator.

## Consequences

- Frozen WARN and ERROR results cannot accumulate a second canonical copy.
- Rigid decorative groups at partial or full weight remain compatible and do
  not create false weight diagnostics or report counts.
- Invalid binding fails earlier with X5-specific locations instead of surfacing
  later as a generic private-stage/GLB failure.
- Snapshot collection and skin checks remain linear in exported modifier,
  vertex, and assignment counts. Frozen re-preflight rejection and report
  profile-to-count selection are constant-time.
- Existing descriptor, GLB, public API/SPI, runtime resources, and strict-v1
  profile identifiers are unchanged.

## Alternatives rejected

- Deduplicate diagnostics after a second preflight: still repeats checks and
  obscures which authority owns the result.
- Treat every vertex group as a skin influence: rejects valid rigid authoring
  data and disagrees with Blender/GLB bone binding.
- Count source weighted vertices: diverges when the exported GLB splits source
  vertices and fails Java report parity.
