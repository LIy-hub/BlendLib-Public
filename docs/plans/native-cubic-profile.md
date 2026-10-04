# Batch 27: explicit native cubic profile

Status: implementation plan, October 4, 2026. Separate feature branch; no main merge or release.

Add `format_version: 2`, `profile: blendlib:skinned_cubic_v1` as a narrow opt-in runtime profile. Existing v1 schema, descriptor decoder, loader defaults, rejection corpus and exporter defaults remain strict. Experimental X9 profiles remain validation-only. Reuse v1 geometry, materials, sockets, state authoring and CPU skinning; no morph, colors, UV2, codecs or rich materials.

The separate runtime descriptor dispatcher selects old strict-v1 or the new cubic descriptor decoder before resolution. A runtime-capable loader is explicit. Cubic channels retain one value per key, with separate immutable tangent storage. Runtime compiled channels reference generation-owned immutable data rather than copying arrays for states or instances.

Cubic sampling is glTF component Hermite with tangent derivatives in seconds; equivalent Bezier/de Casteljau evaluation gives numerically stable convex interpolation. Preserve quaternion signs and tangents, normalize only the sampled quaternion, clamp new-profile endpoints and preserve exact keys. No slerp within a cubic segment.

Validate at load: finite times and tangents, at least two cubic keys, checked triplet cardinality, bounded decoded components and peak transient/storage estimate before allocation. Convert each segment to controls P0, P0+dt*M0/3, P1-dt*M1/3, P1. Translation uses the maximum control norm for the existing conservative hierarchy bound. Scale requires identical component values/tangents, and all scalar controls above a numerical floor. Quaternion requires every control to have a sufficiently positive projection on the first key's unit direction. This bounded sufficient proof may reject valid curves; it does not repair signs, clamp curves, sample them to infer safety or silently rebake at runtime. Overflow/unprovable curves receive a preparation diagnostic.

Blender 5.1.2 export explicitly selects native-curve mode for the new profile. Preserve eligible curves; report sampled fallbacks and reasons. Real authored eased skinned example plus non-key Blender-to-Java oracle, overshoot and quaternion tests. Existing state/event/socket tools continue to work. Do not promise exact preservation of arbitrary Bezier timing, rotations or constraints.

Verification: old v1 suite and public ABI; core mathematical/budget/rejection tests; CPU skinning, controller/layers/blendspaces/cadence/events/sockets and lifecycle integration; actual Blender export; official 26.3 JAR/consumer assembly and terminal CI. Distinct beta4 cubic-preview artifacts. Native Minecraft visual acceptance remains deferred.
