# CPU shape-key preview

Batch 28 adds an explicit opt-in runtime contract:

```json
{"format_version":2,"profile":"blendlib:skinned_morph_cpu_v1"}
```

This preview inherits [native cubic TRS](native-cubic-profile.md) and adds bounded
dense POSITION and NORMAL morph deltas before CPU skinning. It does not expand
frozen v1, the cubic-only profile, or the validation-only X9 profiles. Native
Minecraft visual acceptance remains deferred.

## Authoring contract

A mesh has at most eight targets. Every material primitive has the same target
count/order and each target has dense FLOAT/VEC3 POSITION and NORMAL accessors
matching its base vertex count. `mesh.extras.targetNames` is mandatory, with exact
cardinality and unique nonblank bounded names. Names are mesh-local; controls bind
to an exact scene-node path plus target name.

The descriptor requires one named control for every morph-bearing node/target:

```json
"morph_controls": {
  "example:smile": {
    "node": "Root/Rig/Face",
    "target": "Smile",
    "min_weight": -1,
    "max_weight": 1
  }
}
```

Every finite interval includes zero and fits inside [-2,2]. Values are independent
signed coefficients, not normalized skin influences. Defaults and animation keys
must fit their declared intervals. The loader and frame controls reject invalid
values instead of clamping. Defaults follow node weights, then mesh weights, then
zero. `MeshPrimitive.weights()` continues to hold only JOINTS_0 skin influences.

Morph channels target nodes with `path: "weights"`; outputs are FLOAT/SCALAR with
keyCount × targetCount entries. LINEAR and STEP are supported. Weight-only clips
have genuine durations, and combined clips may contain native cubic TRS. Sparse
targets, target tangents, cubic weight channels and richer materials are rejected.

## Sampling and frame controls

Generation-owned immutable targets, bindings and channels are shared. Complete
immutable `MorphWeights` are instance snapshots. Every sample starts with defaults,
so changing to a clip without a weight track resets that node's values. The existing
full-body `AnimationController` samples both pose and weights using its own current
and previous clocks and its existing smoothstep blend. There is no second morph
clock or observer-based transition reconstruction.

The adapter's additive entity builder callback captures controls once per frame:

```java
BlendEntityRenderer.<MyActor>builder(context, MODEL)
    .skinnedAnimation((entity, request) -> IDLE)
    .morphControls((entity, request) -> new MorphFrameOverrides(Map.of(
        BlendResourceId.parse("example:smile"), entity.smile())))
    .build();
```

Import `MorphFrameOverrides` from `com.liy.blendlib.core.animation.runtime`.
For animation-free exports, select `.staticMorph()` instead of `.skinnedAnimation(...)`.
The same `.morphControls(...)` callback applies named frame overrides to authored
node/mesh defaults. This mode requires the CPU morph profile but requires no
animation declaration or clip. Rest bone transforms remain fixed; no controller,
clock, animation instance, dummy track or synthetic state is allocated.
Empty/omitted controls return to defaults on the next extraction. Reload, unload
or disconnect during the callback discards the frame. Captured meshes remain
immutable and CPU-only, including all-zero weights. Material appearance, skins,
yaw and conservative model culling use the ordinary builder path. Animation-only
features (layers, events, procedural rotations and attachments) still require the
animated path.

The runnable consumer includes `/summon blendlib_runnable_examples:static_cpu_morph_actor`.
Its separate `static_face_actor.glb` has no animations and its descriptor has no
animation states. The extraction callback drives named blink/smile values from
client time; the body remains in its authored rest pose and omitted breath uses
its nonzero default. This is a visual demonstration, not gameplay or synchronized
state. Native graphics acceptance remains deferred.

On the animated path, An empty batch resumes sampled values next frame. The complete batch is validated
against the current generation before clocks, controller commands or cue capture
advance. Controls apply after clip sampling, then travel with the same immutable
pose snapshot through rotation-only procedural modifiers. Held transform cadence
still captures new controls, and derived CPU output is not reused across frames.

The lower-level runtime has `extractMorph`, `extractMorphClipAt`,
`extractLayeredMorph` and `extractBlendSpaceFrameMorph` entry points. Old signatures
remain available. Bone-only layers and blendspaces can use explicit frame controls;
weight-bearing clips cannot enter their transforms-only state domain. Their initial
states, next-state links and commands fail closed instead of silently dropping
weights. Full-body weight clips and transitions are supported.

## CPU output and safety

The fused path accumulates base position/normal plus weighted deltas in double,
then performs the existing skin point and inverse-transpose normal transforms.
Target NORMAL values are differences and are not normalized separately. Final
normals are normalized after skinning. Exact immutable topology, UVs, indices,
material routing and primitive ordering are retained. The existing zero-normal
result for opposing skin influences is unchanged.

The two-joint oracle also exposed an inherited cofactor-determinant error. The
normal-transform correction applies to ordinary CPU skinning and its existing
X7 mirror calculations as well. It fixes relative normal weighting across rotated
joints; it does not enable GPU morphs or change an asset format. A separate
floating-point loop-event boundary fix prevents duplicate visual markers.

Load-time positive projection onto the base normal proves each source normal stays
nonzero throughout the complete declared interval box. Position and normal envelopes
must be finite. Some mathematically safe assets may fail this conservative proof.

Bounds include every allowed manual weight, not only defaults or keys. Each skin
influence uses the radius of inverseBind × basePosition plus the sum of maximum
absolute allowed weight × norm(linear inverseBind × delta). Translation is never
applied to deltas. CPU morph inverse-bind matrices must have an exactly affine
fourth row [0,0,0,1], because a small projective term can become large across an
allowed morph interval. Old-profile tolerance is unchanged. This feeds the existing joint hierarchy, cubic control-hull and
outward-roundoff bound. Unsafe or overflowing envelopes reject before publication.

Preparation is bounded by eight targets per mesh, 128 morph-bearing nodes, 1024
controls, 1,000,000 expanded vertex-target pairs, and 32,000,000 cumulative float
slots. The metadata preflight counts material-split vertices, node reuse, channel
reuse, source reads, defensive copies, palette preparation and one complete CPU
capture's transients before decoded arrays are allocated. These are resource limits, not performance claims. Existing
cubic-only limits remain unchanged.

Morph generations are explicitly excluded from the skin-only GPU ownership inventory.
Every morph frame uses stable CPU capture without T4/X7 skin-only provenance, even
if every weight is zero. Reload, rebind, unload, item retirement and disconnect use
the existing instance lifetime boundaries; previously retained captures remain immutable.

## Blender evidence and boundaries

Use the exporter's explicit CPU morph profile. It enables morph normals and animation
with `export_apply=False`. The source subset permits relative keys referenced to
Basis and validated armature deformation; drivers, key masks, absolute/chained keys,
unsupported NLA and unproven modifiers fail explicitly. Key datablock Actions are
discovered separately from object and armature Actions. Native eligible TRS curves
remain cubic. Bezier weights use reported sampled approximation, not a native cubic
weight claim. Old export defaults are unchanged.

The [first-party fixture](../test-assets/cpu-morph) contains editable Blink, Smile
and Breath keys, two materials, a nonzero default, cubic nod, and weight-only clips.
Its real Blender preview is labeled accordingly. Independent evaluated Blender
positions/weights and bone matrices are checked at key and non-key times against
Java loading and CPU output. Normals use an independent glTF delta/inverse-transpose
math oracle: interpolated glTF normal deltas need not equal Blender's normals
recomputed from an intermediate deformed surface.

See the [runnable consumer](../examples/cpu-morph-profile/README.md) and
[verification](cpu-morph-profile-verification.md). No network payload, tangent,
texture-pipeline, main merge, release, or GPU morph extension is included.
