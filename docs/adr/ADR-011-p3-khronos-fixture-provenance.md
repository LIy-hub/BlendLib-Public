# ADR-011: P3 Khronos Fixture Provenance and Strict-GLB Derivation

Status: Accepted

## Context

P3 requires compatibility fixtures based on Khronos SimpleSkin and AnimatedCube
while accepting only the frozen BlendLib v1 strict GLB profile. A read-only
audit fixed the upstream reference at glTF-Sample-Assets commit
`5109ab2a499c5a2c784b86e460fa491d52256e25`. At that revision neither model
ships a GLB variant. SimpleSkin is external `.gltf` plus `.bin`, lacks required
`NORMAL` and `TEXCOORD_0`, and therefore cannot be an accepted strict-v1 asset
unchanged. AnimatedCube is external `.gltf` plus `.bin`/PNG, has PBR/image
content and `TANGENT`, and likewise cannot be accepted unchanged.

The upstream model payloads are declared CC0-1.0. Their `LICENSE.md` and
`metadata.json` documentation are CC-BY-4.0, so any copied metadata would
require retained attribution and license information. No upstream file has
been downloaded or added to this repository under this proposal.

## Decision

1. Never treat an upstream external `.gltf`/`.bin` original as an accepted
   BlendLib v1 runtime fixture.
2. If the P3 compatibility-fixture subtask proceeds, import only the required
   CC0 payload from the fixed upstream revision into
   `test-assets/third_party/khronos/glTF-Sample-Assets/5109ab2a499c5a2c784b86e460fa491d52256e25/`.
   Record source URLs, upstream revision, input SHA-256, derived output
   SHA-256, author/owner attribution, license links, and every transformation
   in a local provenance record.
3. Generate separate, deterministic derived strict-GLB fixtures. A SimpleSkin
   derivation may retain only the skin/joint/inverse-bind/LINEAR-animation
   semantics and must add self-authored required normal/UV attributes. An
   AnimatedCube derivation may retain only supported geometry/triangle/UV and
   LINEAR-rotation semantics, must remove `TANGENT` and GLB material/image
   data, and must use the v1 descriptor-managed external-texture model or no
   material assertion at all.
4. Label derived fixtures as derived test data, not as raw Khronos accepted
   models. The original external `.gltf` may only be used as a strict-rejection
   input, never as a runtime asset.
5. Until the provenance/derivation records are present, the Khronos
   compatibility-fixture portion of P3 remains WAITING. It does not relax any
   other P3 loader, malformed-input, or safety Gate.

## Consequences

This preserves the accepted strict-GLB runtime format and avoids silently
claiming compatibility with input that is outside the v1 profile. It adds a
third-party test-data provenance obligation but does not choose or alter the
license of BlendLib itself. The decision is accepted because the approved P3
plan already requires these compatibility fixtures; the derivation records are
the explicit way to satisfy that requirement without accepting non-v1 input.
