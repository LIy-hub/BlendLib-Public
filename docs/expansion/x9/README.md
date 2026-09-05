# X9 Advanced Asset Profile Candidate

**Status:** Proposed/Experimental validation candidate. X9's isolated track was independently reviewed by /root/x9_independent_review_r6 (gpt-5.6-sol/max), Verdict: PASS with no findings. The original local X1/X5/X9 integration r1 later received Verdict: FAIL; its remediation awaits a fresh gpt-5.6-sol/max integration review. It does not enable a runtime binding, codec, renderer, provider, or public API.

This directory describes a validation-only candidate for advanced GLB asset
profiles. It is deliberately isolated from the approved v1 loader and from the
controlled experimental SPI. It does not register a provider, select a
renderer, create a `ModelAsset`, alter a Fabric entrypoint, or introduce a
public API.

## Candidate boundary

The candidate accepts only a new descriptor shape with `format_version: 2` and
one of the following profile identifiers:

| Profile | Required advanced content |
| --- | --- |
| `blendlib:skinned_v2` | skin attributes, CUBICSPLINE animation, `COLOR_0`, and richer material metadata; `TEXCOORD_1` is optional and capability-gated |
| `blendlib:morph_v1` | everything in `skinned_v2`, plus bounded `POSITION` morph targets and mesh weights |

The schema is at `schemas/experimental/blendlib-model-x9.schema.json`. The
candidate implementation is in
`com.liy.blendlib.core.profile.experimental`; it consumes already-resolved
descriptor and GLB bytes and produces only an immutable validation summary.
It does not reuse or extend `ModelProfile`, `ModelDescriptor`,
`DescriptorDecoder`, or `ModelAssetLoader`.

`rigid_v1` and `skinned_v1` stay owned by their existing descriptor/schema and
loader paths. The X9 decoder rejects every descriptor other than version 2,
while the v1 decoder continues to reject version 2. This is intentional,
bidirectional migration gating rather than an implicit upgrade.

## Capability envelope

Every capability has a strict `min_version` inclusive / `max_version`
exclusive range (`[min_version, max_version)`). Capability selection and all
warning diagnostics are sorted before they enter the immutable validation
summary. The descriptor stores capability requirements in one object keyed by
resource ID. JSON key uniqueness makes required/optional duplication
unrepresentable, and `maxProperties: 32` expresses the total capability bound
directly in the schema as well as in the decoder. The public experimental
limits object can only tighten this and the other fixed candidate ceilings; it
cannot expand them. Consequently 32 capability entries are valid under the
default limit, 33 are invalid, and a caller-supplied limit below 32 is enforced
without creating schema/decoder disagreement.

| Capability | X9 state | Validation meaning |
| --- | --- | --- |
| `blendlib:morph-targets` | Proposed validation only | Required by `morph_v1`; validates bounded target deltas and weights. |
| `blendlib:cubic-spline` | Proposed validation only | Declares and validates CUBICSPLINE sampler cardinality. |
| `blendlib:vertex-color` | Proposed validation only | Permits validated `COLOR_0` attributes. |
| `blendlib:multiple-uv` | Proposed validation only | Permits validated `TEXCOORD_1` attributes. |
| `blendlib:richer-material-metadata` | Proposed validation only | Declares constrained PBR and texture metadata in the descriptor. |
| `blendlib:metadata-hints` | Proposed validation only | A metadata-only candidate capability. |
| `blendlib:draco`, `blendlib:meshopt`, `blendlib:ktx2` | Disabled | Always rejected. |

Unknown, disabled, malformed, or version-incompatible **required** capabilities
are rejected with `BLENDLIB-EXT-001`. An unknown optional capability produces a
warning only when both conditions are met: its resource path is explicitly
metadata-only (`metadata-hints` or `metadata/...`) and its declared fallback is
`metadata_ignore`. That warning is `BLENDLIB-X9-EXT-002` and is retained in the
result. All other optional capability cases fail closed with
`BLENDLIB-X9-EXT-003` and the standard `missing_model` fallback; no visual
behavior is silently changed.

The GLB transport extensions `KHR_draco_mesh_compression`,
`EXT_meshopt_compression`, and `KHR_texture_basisu` are rejected from
`extensionsUsed`, `extensionsRequired`, and every nested `extensions` payload.
Every other extension payload is also rejected by this closed candidate
envelope. No license, dependency-size, security, performance, and
semantic-fallback evidence has been accepted for enabling them.

## Strict validation rules

The validator first bounds descriptor bytes and parses strict JSON with unknown
fields rejected. A structural pass then composes the existing `GlbReader` and
`GlbAccessorReader` for container, buffer, accessor-layout, and typed-read
safety. It additionally closes the version-2 structural envelope: exact glTF
2.0 version/min-version rules, one embedded buffer, complete references,
bounded and acyclic reachable node hierarchy, finite transforms, active scene,
material indices, and full nested-extension scanning.

Every candidate mesh must be bound to one active node and a real skin. Skins
must name unique active joints, a valid skeleton root, and one finite FLOAT
MAT4 inverse-bind matrix per joint. Each matrix must be affine and numerically
non-singular. JOINTS and WEIGHTS are type-, range-, and normalization-checked;
weights sum to one per vertex and repeated non-zero influences for one joint
are rejected. Morph primitives in a mesh have a common target count;
`POSITION`, optional `NORMAL`, and optional `TANGENT` deltas are finite FLOAT
VEC3 streams with the base vertex count. Each target attribute requires the
same base primitive attribute, and every target `POSITION` accessor carries
exact decoded min/max evidence. Base `NORMAL` vectors and base `TANGENT` XYZ
vectors are unit length using `abs(lengthSquared - 1) <= 1e-4`; tangent W is
exactly `-1` or `1`. Morph `NORMAL` and `TANGENT` values are deltas and are not
unit-normalized. Mesh/node weights match the common target count, and an
explicit `node.weights` array must be non-empty. Animation channels target
active nodes and compatible paths;
weights outputs are FLOAT SCALAR streams whose cardinality includes target
count and CUBICSPLINE triplets. Static rotations and animated quaternion values
must be normalized; CUBICSPLINE tangents are not treated as quaternion values.
Declared capabilities are reverse-bound to observed GLB postconditions, so
removing CUBICSPLINE, `COLOR_0`, a declared `TEXCOORD_1`, morph content, or richer material
metadata is a controlled failure rather than a successful zero count.

Accessor metadata is semantic evidence, not decoration. Every accessor has
`count >= 1`, and every FLOAT component is scanned for NaN or infinity even if
the accessor is never referenced by a mesh, skin, or animation. Every accessor
declaring one bound must declare both; the arrays have exact component
cardinality and exactly equal the actual raw per-component extrema. FLOAT
bounds compare at storage precision. Unsigned integer bounds must be integer
JSON numbers in the component-type range and compare to raw integer values;
`normalized` does not transform bounds. `POSITION` and animation-input bounds
remain mandatory. The audit honors buffer-view stride plus accessor offset, and
sparse accessors remain outside the candidate envelope.

The whole-document accessor audit is preflighted before reading data. Its
component budget is `maxIndices + 16*maxVertices + 4*maxKeyframeSamples +
16*maxSkinJoints`; primitive-index scans have a separate aggregate
`maxIndices` budget. `normalized: true` is invalid for FLOAT and UNSIGNED_INT
accessors. UNSIGNED_INT is accepted only when the accessor is used exclusively
by `mesh.primitive.indices`; unused or non-index UNSIGNED_INT accessors are
rejected by a bounded usage pass. Primitive indices may be U8, U16, or U32,
but must not contain `255`, `65535`, or `4294967295` respectively because glTF
reserves those maximum values for primitive restart. U32 values are compared
as unsigned values before the independent vertex-range check. Normalized index
accessors are outside the candidate envelope.

UV semantics are canonical and consecutive per primitive, beginning with
`TEXCOORD_0`. A suffix is accepted only when it is canonical ASCII decimal:
the validator rejects signs, leading zeroes, whitespace, empty suffixes,
non-digits, and values that cannot be represented as an integer before it
uses the suffix for counting or accessor lookup. `maxUvSets` accepts one or
two and is enforced on every primitive: with one, UV0-only content passes and
UV1 fails; the default and hard ceiling are two. `blendlib:multiple-uv` is
profile-optional, permits `TEXCOORD_1`, and when declared is reverse-bound to
UV1 on every primitive.
Optional accepted GLB fields such as `name` and `bufferView.target` are still
type- and enum-checked.

The X9 profile then builds one actual-use graph from primitive attributes and
morph targets, primitive indices, animation sampler input/output, and inverse
bind matrices. A declared target is accepted only as `34962` for a vertex or
morph view and `34963` for an index view; omitting the hint remains legal, and
animation/IBM views may not declare it. One buffer view cannot mix those role
classes. `byteStride` is allowed only for vertex/morph views, where its
effective value must be 4..252, a multiple of four, large enough for each
element, and aligned for every component type; a tightly packed vertex layout
therefore needs explicit padding when its element size is not four-byte
aligned, and shared vertex attributes require an explicit stride. All accessors
use the standard component-size alignment for both `accessor.byteOffset` and its
total buffer offset. The additional vertex/morph rule is instead that the
accessor's own `byteOffset` (not the total buffer offset) is a multiple of
four, including U8/U16 streams. It is deliberately not applied to index,
animation, or inverse-bind accessors.

Local arrays are bounded before they are copied into Java collections. The
active hierarchy is indexed once for constant-time skin ancestry checks rather
than allocating a descendant set per skin. The structural pass reads the
bounded binary length without creating an extra whole-BIN copy; the shared
`GlbAccessorReader` retains its single bounded working copy. Extracting a
shared zero-copy structural envelope would cross the X9/v1 boundary and remains
future work.

Every X9-specific public limit is consumed at its validation boundary:
`ExperimentalProfileLimits` covers descriptor and feature axes, while
`ExperimentalGlbLimits` covers GLB bytes, vertices, indices, nodes, hierarchy
depth, skin joints, and keyframe samples. The latter has an internal adapter
for the unchanged strict v1 readers, with every v1-only axis fixed internally;
those unrelated axes are not exposed as misleading X9 configuration knobs.
Morph, UV, sampler, animation, and clip-duration limits remain enforced in
their feature passes.

The GLB feature check remains narrower than a second loader: it verifies the
X9 structure and feature envelope but constructs no runtime asset or material
and performs no animation playback. There is no X9 draw, reload, codec, or
asset conversion path.

## Fixtures and review evidence

The test fixtures live under `blendlib-core/src/test/resources/x9` and
`test-assets/x9`. Separate positive fixtures exercise `skinned_v2` with a real
skin graph and TRS CUBICSPLINE animation, and `morph_v1` with a real skin graph,
a morph target, and correctly shaped scalar weights animation. Negative
fixtures cover reference, hierarchy, payload, accessor, all-FLOAT finiteness,
raw min/max across FLOAT/integer/normalized/stride/offset cases, primitive
restart and vertex-range boundaries, UV-limit/numbering/multi-primitive cases, quaternion,
inverse-bind, morph attribute, material, cardinality, capability-postcondition,
and base-limit failures. A shared boundary corpus is checked independently by
the JSON Schema and Java decoder, including duplicate keys, Unicode/control
characters, numeric types and bounds, and the total capability limit. The
schema and Java tests exercise the exact 32-valid/33-invalid capability
boundary, and Java additionally proves that a smaller caller limit only
tightens acceptance. The legal morph GLB includes matching base/target
attributes, exact target POSITION bounds, unit base normals/tangents, and a
valid non-unit morph normal/tangent delta. The
validation summary is serialized in one bounded canonical form and compared
for exact equality against byte-identical core and external golden files.

The remediation reran the schema corpus (5 valid, 10 invalid),
`ExperimentalProfileValidatorTest` (54 tests), and
`KhronosDerivedFixtureTest` (3 tests over 8 direct unique payloads). The last
result is a repository-derived fixture check only. No external Khronos glTF
Validator executable or runnable corpus integration was available, so this
document claims no external-validator result.

The current remediation is an input to a fresh integration review, not evidence
that X9 is accepted or release-ready.

## Future integration proposal (non-binding)

If X9 is independently approved, a later integration change may evaluate the
frozen X1 naming surface (`com.liy.blendlib.api` and
`com.liy.blendlib.spi.experimental` 1.0.0) without importing this candidate as
a competing API. That later work must establish capability/provider lifecycle,
fallback, render, performance, license, and migration evidence separately.
This X9 branch neither depends on nor implements X1 SPI code.
