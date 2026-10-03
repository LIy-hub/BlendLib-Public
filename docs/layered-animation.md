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

## Tick-based cues without a consumer cache

For entity action state carrying a sequence and start tick, prefer the additive
`animationLayerCues(layers, cues)` convenience:

```java
builder.skinnedAnimation((entity, request) -> walk)
    .animationLayerCues(layers, (entity, request) ->
        entity.cueSequence() == 0 ? List.of() : List.of(new BlendEntityLayerCue(
            upper, attack, entity.cueSequence(), entity.cueTick(), 1.0)));
```

The entity methods here are consumer-defined state. The real, compiled
[`ExampleClient`](../versions/modern/showcase/src/client/java/com/liy/blendlib/examples/runnable/ExampleClient.java)
uses this exact pattern. For an existing command-source registration, use
`BlendEntityLayerCommands.fromCues(cues)` once and retain the returned source.
The original `animationLayers` command API remains available unchanged.

`startTick` must use the same client game-tick epoch as the snapshot request. Convert a
server clock yourself if your synchronization uses another epoch. At the first observation,
BlendLib computes `max(0, clientTick + partialTick - startTick) / 20 * playbackSpeed * descriptorStateSpeed`, then
freezes that immutable command for the sequence. Future ticks start immediately at zero;
this is clip-local catch-up, not a scheduler. As with an explicit absolute command, a completed
one-shot clamps to its endpoint and follows `next` on a subsequent positive advance; this
adapter does not reconstruct historical transition chains or blends. The first capture wins: changing animation, start tick or
speed requires a new increasing sequence. Older sequences are ignored without replacing
the latest frozen command. An empty list keeps playback and the sequence watermark.
Sequences are independent per controller, zero is valid, and a batch must contain at most
one cue per controller. The example reserves zero only as its own “no cue yet” convention.

Capture state is scoped by exact entity object identity, source identity, full active
connection key, model and resource generation. Equal/reused entity IDs do not share captured
commands. BlendLib's existing unload, reload, disconnect, repeated play-init and explicit
runtime retirement paths release it, so consumers register no lifecycle hooks and retain no
entity maps. A still-current cue is recaptured with current elapsed time after reload or
retracking; it is not restarted at zero. Extraction after disconnect returns no commands.
Only extraction sees the cue source; rendering retains immutable snapshots as before.

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


## Frame-local dynamic clip-layer weights

Keep layer declarations fixed and add `animationLayerWeights` after `animationLayers` or
`animationLayerCues` to vary visual influence per entity:

```java
builder.animationLayerCues(layers, cues)
    .animationLayerWeights((entity, request) -> new AnimationV2LayerWeights(Map.of(
        new AnimationV2LayerWeights.Key(upper, upper), 0.5F)));
```

`AnimationV2LayerWeights` copies the map and rejects non-finite/out-of-range values. Each
value is a multiplier in [0,1]; missing pairs mean one. For descriptor-backed entities the
controller and layer IDs are both the declared `Layer.id()`. Lower-level v2 plans use the
actual controller/layer pair, so equal layer IDs in different controllers stay independent.
Unknown pairs are errors. These are complete per-frame values, not persistent setters.

The callback receives the entity and extraction request once per extraction. It runs before
render publication; later submission consumes only the immutable final pose. The same captured
value can be passed directly to the additive runtime overload:
`extractLayered(input, layers, commands, weights, modifier)`.
Core-only consumers use `advanceWeightedAtFrame(delta, commands, weights)`; a further overload
accepts explicit sequence rejections before the weights argument. Original overloads retain
configured weights.

Effective composition weight is configured layer weight × frame multiplier × bone-mask weight.
Zero-weight controllers still advance time, transitions, automatic next states and cue sequences.
Weight changes do not rebuild cached plans or restart clips. Existing override semantics remain:
a positive higher-priority contribution suppresses lower priorities on that bone and blends with
rest; it does not blend through to lower-priority animation. At zero it no longer owns that bone.
Same-priority normalization and additive order are unchanged.

`AnimationV2EvaluationSnapshot.effectiveLayerWeights()` captures configured × frame multiplier
for every declared pair, before bone masks and priority resolution. It is not final bone influence.
Compatibility-created and initial revision-zero snapshots have no sampled weights. Retained
snapshots remain immutable; reload, unload and fresh instances cannot inherit frame-local values.
The runnable example shows per-actor fades and separates configured and effective weights in its
inspection command. This feature changes no network payload and adds no graphics acceptance claim.
