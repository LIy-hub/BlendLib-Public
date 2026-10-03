# Layered animation in the standard entity renderer (26.3)

The `BlendEntityRendererBuilder.animationLayers(layers, commands)` opt-in connects the
existing v2 evaluator to standard strict-v1 model extraction. Configure `skinnedAnimation`
(or its synchronized variant) first. The old path remains unchanged unless opted in.

Each `ModelAnimationLayers.Layer` is an independently controlled descriptor-state machine.
Its resource ID is both controller and layer ID. Priority ascending, then canonical ID,
determines composition order. It accepts `OVERRIDE` or `ADDITIVE`, weight in [0,1], and
named per-bone weights. An empty bone declaration means the entire model; use explicit
zero weights for an intentionally empty mask. Duplicate/ambiguous node names fail clearly.
All descriptor states, speeds, `blend_seconds`, loops and `next` transitions are retained.
GLB STEP and quaternion interpolation are sampled natively, never baked to linear keyframes.

```java
var base = BlendResourceId.parse("example:locomotion");
var upper = BlendResourceId.parse("example:upper_body");
var walk = BlendAnimationKey.parse("example:walk");
var idle = BlendAnimationKey.parse("example:idle");
var attack = BlendAnimationKey.parse("example:attack");
var layers = List.of(
    new ModelAnimationLayers.Layer(base, 0, AnimationV2LayerMode.OVERRIDE,
        1F, List.of(), walk),
    new ModelAnimationLayers.Layer(upper, 10, AnimationV2LayerMode.OVERRIDE,
        1F, List.of(new BoneMask.NamedWeight("Arm", 1F)), idle));

builder.skinnedAnimation((entity, request) -> walk)
    .animationLayers(layers, (entity, request) -> {
        // Read your own immutable action state here; never increment on every render.
        // Return no commands to keep current playback. A new action gets a new sequence.
        return actionFor(entity).map(action -> List.of(new AnimationV2Command(
            upper, attack, action.sequence(), action.elapsedSeconds(), 1D)))
            .orElseGet(List::of);
    })
    .poseComponents(components)
    .sockets((entity, request, sockets) -> {
        // This sees the final layered + procedural pose from the same extraction.
    });
```

`actionFor` above is the consumer's own action-state lookup, not a BlendLib API. The compiled
`LayeredAnimationConsumerSample` test source provides the same wiring with an injected
command source and no invented helper.

Commands are immutable absolute requests. Sequence numbers must increase independently
for each controller; repeated sequence numbers are deduplicated. `requestedPlayheadSeconds`
and `playbackSpeed` follow the existing v2 controller contract. A new sequence for the same
state is a restart/correction; do not emit one every frame. Controllers without a command
continue on the client extraction clock. To synchronize a layer, derive the command's
absolute time and sequence from your accepted server semantic state. This API does not
introduce a second network packet or imply automatic multi-controller server replication.

The original selector/controller still supplies legacy event and diagnostic semantics.
Keep it aligned with your full-body layer if using its visual-event callback. Layer-specific
visual event tracks are not yet dispatched by this opt-in composition API.

## Order and lifetime

Loaded asset → v1 base identity/clock → v2 layer composition → entity pose modifier →
procedural components → palettes and sockets → immutable render/attachment snapshots.

The composed pose does not mutate the cached base. Instances retain separate evaluators.
Unload, model rebind, generation change, disconnect and explicit `retire` discard their
layer state. Repeated extraction at the same time does not advance playback twice.
A backward client clock holds layer time until it catches up; use lifecycle/reset on a
world change. Models with no animated loaded handle retain the ordinary missing-model path.

Native visual testing is distinct from extraction and core tests; see the verification
report for the checks actually run.

`BlendLibClientServices.skinnedAnimationRuntime().layeredSnapshot(instanceKey)` exposes the latest
immutable v2 publication and its bounded-work/sequence diagnostics. Native descriptor plans are
shared in a bounded generation-scoped cache; mutable controller evaluators remain per instance.
