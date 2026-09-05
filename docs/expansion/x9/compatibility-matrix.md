# X9 Compatibility Matrix

**Status:** Proposed/Experimental validation candidate. X9's isolated track was independently reviewed by /root/x9_independent_review_r6 (gpt-5.6-sol/max), Verdict: PASS with no findings. It is now included in the local X1/X5/X9 integration candidate and awaits a fresh gpt-5.6-sol/max integration review. Draco, Meshopt, KTX2, runtime binding, and all v1 profile mutations remain out of scope.

| Compatibility axis | Existing v1 (`rigid_v1`, `skinned_v1`) | X9 candidate (`skinned_v2`, `morph_v1`) | Migration / rejection rule |
| --- | --- | --- | --- |
| Descriptor format | Exact `format_version: 1` | Exact `format_version: 2` | Cross-version input is rejected; there is no auto-upgrade or downgrade. |
| Profile namespace | Existing `ModelProfile` values only | Separate `ExperimentalProfile` values only | The v1 enum and decoder are unchanged. |
| Loader output | Existing immutable `ModelAsset` | Immutable validation summary only | No X9 renderable/runtime asset is constructed. |
| GLB content | Approved v1 profile envelope | Active skin graph with affine non-singular inverse-bind matrices, unique effective joint influences, normalized weights/quaternions and base normal/tangent vectors, compatible animations, color, optional capability-gated UV1, richer material data; bounded common-count `POSITION`/`NORMAL`/`TANGENT` morph targets only for `morph_v1`, each paired with its base attribute | Morph vectors remain deltas; declared features are reverse-bound to concrete GLB postconditions before any future runtime integration. |
| Accessor evidence | Existing v1 strict envelope | Every count is positive; every FLOAT value is finite even in unused accessors; every declared min/max pair exactly equals raw per-component extrema with integer/normalization semantics preserved; sparse rejected; FLOAT/U32 normalization rejected; U32 accessor usage restricted to primitive indices; U8/U16/U32 restart maxima rejected; a per-buffer-view actual-use graph binds vertex/morph, index, animation, and IBM roles to target/stride/offset semantics | Vertex/morph target hints may only be 34962 and index hints only 34963, while omitted hints remain legal and animation/IBM cannot hint a target. Mixed role views, including animation with IBM, reject deterministically. Stride is vertex-only: its effective value (including tightly packed layouts) is 4..252, four-byte/component/element aligned; all accessors retain component-size alignment, and only a vertex/morph accessor's own `byteOffset` must additionally be four-byte aligned. |
| Required extensions | Existing v1 strict rejection behavior | Unknown/disabled/version-mismatched capability or GLB extension is rejected with `BLENDLIB-EXT-001` | Fail closed. |
| Optional extensions | Existing v1 behavior remains unchanged | Only unknown metadata-only plus `metadata_ignore` warns; every other unavailable option is `missing_model` fail-closed | Warning/fallback is recorded deterministically; never silent. |
| Draco/Meshopt/KTX2 | Not enabled by this work | Disabled in descriptor declarations, root extension lists, and nested extension payloads | No codec enablement without separate accepted evidence. |
| Resource/path safety | Existing resource-ID and GLB limits | Same safe resource model plus X9 descriptor/count bounds | No filesystem/network path, `.blend`, FBX, or OBJ input. |
| Public API/SPI | Frozen v1 API and controlled X1 SPI remain outside this branch | No public API, provider, lifecycle, renderer, or registration code | Any future link is a separately reviewed integration proposal. |
| Determinism | Existing v1 behavior remains byte/behavior unchanged | Bounded canonical JSON for the full descriptor, counts, negotiated capabilities, and diagnostics | Same inputs equal both byte-identical golden files or produce the same first controlled failure. |
| Schema/decoder parity | Existing v1 contract remains unchanged | One resource-ID-keyed capability object, unique keys, fixed total maximum 32, matched resource/material grammars, bounded semantic-version components, and strict text/number types | Schema and Java both accept exactly 32 and reject 33; caller limits may only tighten fixed ceilings, and a smaller Java limit is tested independently. |
| UV limit | v1 profile-specific behavior | `maxUvSets` accepts 1..2; every primitive uses canonical consecutive `TEXCOORD_n` numbering from zero, and `blendlib:multiple-uv` is optional but exactly reverse-bound to `TEXCOORD_1` | A one-set configuration accepts UV0-only content and rejects UV1; signs, leading zeroes, whitespace, empty/non-numeric, and overflowing suffixes are rejected before lookup; the default/hard ceiling remains two. |
| X9 public limits | Existing v1 reader limits | `ExperimentalProfileLimits` and `ExperimentalGlbLimits` expose only X9 input axes; every field is consumed at its validation boundary | v1-only axes are fixed behind the X9 internal reader adapter, not presented as no-op X9 configuration. |

## Migration policy

1. Keep the v1 descriptor and schema as the authoritative long-term path for
   `rigid_v1` and `skinned_v1`.
2. Author an explicit version-2 descriptor only for an independently reviewed
   X9 candidate; do not relabel a v1 descriptor in place.
3. Validate the candidate against the X9 fixture suite before any proposed
   provider or runtime bridge is considered.
4. Retain v1 regression coverage when X9 changes. A successful X9 validation
   does not imply a renderer, provider, package, or release decision.

## Capability range semantics

The candidate uses the half-open interval `[min_version, max_version)`. The
minimum is accepted, the maximum is rejected, and equal bounds are malformed.
This prevents an implicit overlap at the upper boundary and keeps selection
deterministic across future capability catalog revisions.
