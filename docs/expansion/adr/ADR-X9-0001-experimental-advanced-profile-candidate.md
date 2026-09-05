# ADR-X9-0001: Isolate advanced GLB profile validation from v1 runtime

**Status:** Proposed/Experimental validation candidate. X9's isolated track was independently reviewed by /root/x9_independent_review_r6 (gpt-5.6-sol/max), Verdict: PASS with no findings. It is now included in the local X1/X5/X9 integration candidate and awaits a fresh gpt-5.6-sol/max integration review. This ADR does not enable a runtime binding, codec, renderer, provider, public API, or v1-profile change.

## Context

The reviewed X0 baseline permits a bounded X9 investigation into advanced
asset-profile/schema/validator/fixture work. It does not authorize a change to
the v1 loader, a renderer, a codec, public API, Fabric metadata, or the
controlled experimental SPI. Advanced content needs a strict way to describe
and test morph targets, CUBICSPLINE animation, vertex colors, a second UV set,
and richer material metadata without silently changing existing assets.

## Decision candidate

Create an isolated version-2 descriptor candidate with two profile identifiers:
`blendlib:skinned_v2` and `blendlib:morph_v1`. Keep its schema under
`schemas/experimental` and its pure-Java implementation under the core
experimental profile package. The entry point validates caller-provided bytes
and returns an immutable summary; it does not resolve paths, create a v1
`ModelAsset`, register a provider, or select a backend.

Use half-open semantic-version capability ranges. Required unavailable
capabilities reject. An unavailable optional capability may warn only if it is
explicitly metadata-only and declares `metadata_ignore`; otherwise it rejects
with an explicit `missing_model` fallback. Record selected capabilities and
warnings in deterministic sorted order. Represent capability declarations as
one resource-ID-keyed object with a schema-expressible total maximum of 32;
this prevents an ID from appearing independently as required and optional.
Treat all experimental public limit values as tightening knobs beneath fixed
schema/contract ceilings, never as permission to expand the accepted language.

Compose the existing strict `GlbReader` and `GlbAccessorReader` in an X9-only
structural validator, then validate advanced feature semantics without copying
v1 runtime asset, material, or animation construction. Require a complete,
bounded, acyclic active scene and real skin graph, validate inverse-bind
matrices as finite affine and numerically non-singular, reject repeated
effective joint influences, and require normalized weights and quaternion
values. Require unit-length base normals and tangent XYZ values with an explicit
squared-length tolerance of `1e-4`, plus exact tangent handedness; morph normal
and tangent values remain unconstrained deltas. Enforce common morph target
counts; corresponding base attributes for finite FLOAT VEC3
`POSITION`/`NORMAL`/`TANGENT` deltas; scalar weights animation cardinality; and
mandatory bounds for base and morph positions plus animation input. Audit the
raw data of every FLOAT accessor, including unused accessors, for finite values.
For every accessor that declares min/max, require the declared values to equal
the actual per-component extrema at storage precision; integer bounds are exact
raw integers and normalization never changes their meaning. Preflight the
aggregate component scan against
`maxIndices + 16*maxVertices + 4*maxKeyframeSamples + 16*maxSkinJoints`, and
bound the aggregate primitive-index scan by `maxIndices`. Require positive
accessor counts, reject sparse accessors and FLOAT/UNSIGNED_INT normalization,
and allow UNSIGNED_INT accessors only when used exclusively by primitive
indices. Primitive indices accept U8/U16/U32 but reject each component type's
maximum value because glTF reserves it for primitive restart; U32 comparison is
unsigned-safe. Preserve optional-field presence so an
explicit empty `node.weights` cannot be mistaken for absence. Reverse-bind
every selected visual capability to an observed GLB postcondition. Reject
extension declarations and nested payloads outside the closed candidate
envelope.

`maxUvSets` is an actual tightening knob over the fixed hard ceiling of two:
one and two are valid configurations. Every primitive must use canonical,
consecutive UV semantics beginning at `TEXCOORD_0`; `TEXCOORD_1` additionally
requires the negotiated `blendlib:multiple-uv` capability. That capability is
optional at the profile level, but when declared it remains reverse-bound to a
second UV set on every primitive. UV suffixes must be canonical ASCII decimal
before parsing or lookup, so signs, leading zeroes, whitespace, non-digits,
and overflow cannot create ignored pseudo-attributes. The public X9 limit
types expose only axes with X9 input semantics. An internal adapter supplies
frozen v1-only reader values without advertising them as effective X9 knobs.

Apply local collection limits before materializing Java lists, and build one
iterative hierarchy entry/exit index for all skin ancestry checks. Avoid an
additional whole-BIN copy in the X9 structural pass; the composed bounded
`GlbAccessorReader` retains its existing working copy. A shared zero-copy
structural extraction would modify the v1 implementation boundary and is not
part of this candidate.

Build the X9 buffer-view rule set from actual accessor use rather than treating
`target` as an isolated enum. Vertex and morph attributes may use a declared
`34962` target, primitive indices a declared `34963` target, and omitted
targets remain legal; animation sampler and inverse-bind views must not
declare a target. Reject a view shared across these semantic classes. Permit
`byteStride` only on vertex/morph views and require its effective value,
including a tightly packed layout, to be 4..252, four-byte,
component-aligned, and element-large; this preserves valid padded
interleaving while rejecting index, animation, and IBM strides. Require the total
buffer offset and the accessor offset to meet their ordinary component-size
alignment rules. For vertex/morph data, including U8/U16, additionally require
the accessor's own offset (not the total buffer offset) to be four-byte
aligned; do not apply that extra rule to indices, animation, or inverse-bind
matrices.

Keep Draco, Meshopt, KTX2, `KHR_draco_mesh_compression`,
`EXT_meshopt_compression`, and `KHR_texture_basisu` disabled. The candidate
contains no accepted evidence for their license, dependency size, security,
performance, or semantic fallback behavior.

## Consequences

- Existing v1 schemas, profiles, decoder, loader, and behavior remain
  untouched and are regression-tested rather than adapted.
- X9 validation can provide reproducible review evidence while remaining pure
  Java and resource-agnostic.
- Full canonical validation summaries and a shared schema/decoder boundary
  corpus make determinism and contract parity reviewable as exact artifacts;
  generated boundary checks accept 32 capabilities and reject 33, while a
  smaller custom Java limit demonstrably tightens acceptance.
- Separate positive fixtures prove both `skinned_v2` and `morph_v1` envelopes;
  neither fixture is a renderer, playback test, or production runtime asset.
- A later, separately reviewed cleanup should extract the common strict GLB
  structural envelope shared by v1 and X9. This candidate deliberately leaves
  shared/v1 code unchanged, so that extraction is future work rather than an
  unreviewed cross-scope refactor.
- A future X1 capability/provider integration may refer to frozen names only
  after a separate review. This ADR does not add or depend on X1 SPI code.
- No runtime or user-visible feature is enabled by this candidate. Independent
  X9 review must decide whether to accept, revise, or discard it.

## Rejected alternatives

1. Extend the v1 schema and loader in place. This would conflate an
   experimental profile with the frozen long-term v1 compatibility contract.
2. Treat unknown optional visual features as implicitly ignored. This risks a
   silent semantic/rendering change and violates fail-closed compatibility.
3. Enable compressed geometry or textures as a convenience. There is no
   accepted evidence package or fallback plan for that change.
