# Native cubic animation preview

Batch 27 adds an explicit runtime profile:

```json
{"format_version":2,"profile":"blendlib:skinned_cubic_v1"}
```

Use the complete [authored example](../test-assets/native-cubic/README.md),
[Blender export instructions](../blender-addon/README.md#explicit-native-cubic-profile-format-2)
and [runnable consumer](../examples/native-cubic-profile/README.md).
This is an opt-in preview, not a release or an expansion of the frozen v1 schema.

## Scope and compatibility

The profile accepts skinned GLB 2.0 geometry, external PNG material slots, positive
uniform scale, existing states/events/sockets, and node translation/rotation/scale
channels using LINEAR, STEP or CUBICSPLINE. It reuses the CPU-skinning route,
controllers, layered poses, 1D/2D blendspaces, dynamic cadence, procedural modifiers,
materials, sockets and immutable snapshots. It introduces no network payload.

Colors, UV2, morph targets/weights, codecs, richer GLB materials and X9 experimental
profiles remain unsupported. GLB materials in this new profile are names, optional
extras and empty extensions; the existing descriptor owns material appearance.
The new schema is `schemas/blendlib-model-skinned-cubic-v1.schema.json`.

Old descriptor schemas, `DescriptorDecoder`, `Interpolation.fromSerializedName`,
`ModelProfile.fromSerializedName`, default `ModelAssetLoader` constructors and v1
export modes keep their strict old acceptance rules. `CubicDescriptorDecoder`
accepts only the new pair. `RuntimeDescriptorDecoder` explicitly dispatches those
contracts. `ModelAssetLoader.runtimeProfiles()` is the opt-in Java loader; production
client resource reload now uses it so both old and new assets coexist. X5's legacy
strict-v1 command remains a v1 validator.

## Cubic representation and sampling

Each key has a separate incoming tangent, value and outgoing tangent vector, exactly
as specified by [glTF cubic interpolation](https://registry.khronos.org/glTF/specs/2.0/glTF-2.0.html#interpolation-cubic).
`AnimationChannel.values()` continues to mean one value per key. Tangent accessors
return defensive copies. Compiled channels share the immutable source arrays;
adding states or instances does not duplicate the animation arrays.

For duration `d = t1-t0`, Hermite tangent terms use `d*out0` and `d*in1`.
The evaluator uses the mathematically equivalent Bezier controls
`p0`, `p0+d*out0/3`, `p1-d*in1/3`, `p1` and double-precision de Casteljau interpolation.
Quaternion components interpolate before normalization. Authored signs and tangent
magnitudes are retained, without slerp or shortest-arc sign repair. New-profile
channels clamp outside their first/last keys and return exact stored keys at key
boundaries; downstream transforms normalize rotation as before. Pose crossfades
and layers still blend completed poses using their existing rules.

## Bounded load-time safety

Cubic samplers require at least two finite, strictly increasing nonnegative times,
with exactly three output vectors per key and correct FLOAT/VEC3 or VEC4 types.
Unreferenced cubic samplers reject because their target-specific safety cannot be
validated. Existing duration, keyframe, geometry and accessor limits still apply.
The inherited per-accessor decoded component limit is normally 4,000,000 floats.
The new profile additionally allows at most 32,000,000 prepared animation float
slots (128 MB float payload), charged before allocation, including repeated channel
uses, original accessor reads, splitting/copying and per-channel times. It is a
conservative cumulative allocation budget, not a promise of total process memory.

Every cubic control component and control-vector norm must fit `Float.MAX_VALUE/4`.
Translation bounds use the greatest control-vector norm, including overshoot outside
all key values. The existing hierarchy/skin convex-envelope proof and 1% outward
roundoff allowance remain in force; overflowing hierarchies reject at load time.

Cubic scale requires exactly matching x/y/z value and tangent coefficients and
all scalar controls at least `1e-6`. Cubic quaternion keys must have norm within
`1e-4` of one. For each segment all four controls must have positive projection on
the first key's unit direction, at least `1e-5 + 1e-12*maximumControlNorm`. This proves
the segment stays away from zero. No authored signs are flipped to pass the proof.

These are sufficient conservative proofs, so some mathematically safe curves will
be rejected. Rejection is a load-time `BLENDLIB-ANIM-007` diagnostic with the channel
location and reason. Nonfinite/accessor or resource-limit errors retain their
existing diagnostic families. The runtime never silently resamples or clamps an
unsafe curve into another motion.

## Blender fidelity and fallback

Blender 5.1.2's opt-in exporter analytically exports a documented eligible subset
of synchronized transform F-curves with linear-in-time Bezier handles, including
bone rest transforms and seconds conversion. It records genuine CUBICSPLINE
channels and key counts. The shipped figure has three three-key channels and a
nonidentity rest-bone orientation, plus the existing state/event/socket authoring.

Connected-child bone location channels and non-CONSTANT F-curve extrapolation also
require fallback: Blender ignores connected translation and can extrapolate beyond
a channel's own key range, unlike the runtime's clamped keys.

Unsupported native curves use the existing frame-baked path and report specific
fallback reasons. Fallback is approximate. Existing rejected constraints, physics
and dynamic modifiers do not become supported. No claim is made that arbitrary
Blender Bezier timing, constraints or rotations are reproduced exactly.

The fixture generator saves raw evaluated Blender matrices and skinned positions
for an independent Java loader/runtime oracle. Synthetic tests cover unequal key
intervals, endpoints, quaternion signs/tangents, translation overshoot, unsafe
scale/quaternion rejection, storage aliasing, old profile rejection and lifecycle.
The preview image is a Blender render. Native Minecraft display/GPU visual
acceptance remains deferred; headless tests do not establish visual acceptance.
