# Resource-pack locomotion rules (26.3)

Opt one standard entity layer into resource-pack idle/walk/run selection with
`animationLocomotionRules(controller, inputs)` after `animationLayers` or
`animationLayerCues`. This drives that actual layer's descriptor controller; the legacy
selector remains independent, as do other layer commands, procedural components and sockets.
Use layer visual-event callbacks for markers from the selected locomotion clips.

```java
builder.skinnedAnimation((entity, request) -> idle)
    .animationLayerCues(layers, actionCues)
    .animationLocomotionRules(baseController, (entity, request) ->
        new LocomotionInputs(
            Map.of("grounded", entity.onGround()),
            Map.of("speed", Math.sqrt(entity.getDeltaMovement().horizontalDistanceSqr()))));
```

Import `LocomotionInputs` from `com.liy.blendlib.core.animation.rules`. The layer list,
controller, idle key and action source above belong to your mod. The [compiled consumer](../blendlib-fabric-client/src/test/java/com/liy/blendlib/fabric/client/entity/consumer/LocomotionRulesConsumerSample.java)
and [runnable scene](../versions/modern/showcase/src/client/java/com/liy/blendlib/examples/runnable/ExampleLocomotionScene.java)
provide complete wiring. Choose input units yourself and use matching thresholds. Extract
presentation state only; rules never decide AI, movement, hits, damage or other gameplay.

## Separate, versioned resource

For model `example:actors/robot`, place the optional sidecar at
`assets/example/blend_animation_rules/actors/robot.json`. Resource-pack priority follows
Minecraft's selected resource. Do not add condition fields to the strict model descriptor.

```json
{
  "schema_version": 1,
  "default": "example:idle",
  "minimum_interval_ticks": 0,
  "rules": [
    {
      "animation": "example:run",
      "conditions": [
        { "input": "grounded", "equals": true },
        { "input": "speed", "enter_min": 0.14, "exit_min": 0.11 }
      ]
    },
    {
      "animation": "example:walk",
      "conditions": [
        { "input": "grounded", "equals": true },
        { "input": "speed", "enter_min": 0.025, "exit_min": 0.015 }
      ]
    }
  ]
}
```

The [JSON schema](../schemas/blendlib-locomotion-rules-v1.schema.json) is an authoring aid.
The reload parser additionally validates threshold relationships, finite numbers, input-type
consistency and actual descriptor targets. Every target, including default, must exist,
loop continuously and have no `next`. One-shot attacks remain sequenced cues on another layer.

Rules are ordered highest priority first. All conditions in a rule must match. A current
rule uses its exit thresholds; other rules use their entry thresholds. The first match wins,
so an earlier rule may preempt a retained lower-priority rule. Otherwise default wins.
`enter_min`/`exit_min` mean >= and require exit <= enter. `enter_max`/`exit_max` mean <= and
require enter <= exit. Boolean equality is exact. All boundaries are inclusive. Empty rules
means default-only; empty conditions means an unconditional rule at that priority.

`minimum_interval_ticks` is optional and defaults to zero. A positive value holds the selected
animation for at least that many observed client game ticks before a different animation can
replace it. It is not candidate debounce. Backward clock movement cannot reduce the stored
clock watermark. Rule identity may change without restarting when both rules name the same
animation. Every animation change receives a new sequence with playhead zero and rate one;
descriptor speed and blend duration still apply. The identical immutable command is replayed
at the same sequence between changes, which deduplicates normally and allows failed extraction
to retry. Never generate your own per-frame restart sequence for this controller.

## Capture, fallback and lifecycle

- One controller per builder may be rule-owned, and it must already exist in its layers.
  Configure the layer/cue source first and rules last; later replacement of layers is rejected.
  Valid rules and explicit commands/cues cannot target that same controller. Other controllers
  remain independent. A conflicting batch fails before rule selection or controller advancement
- Absent or invalid sidecar disables only this optional behavior. The original command source
  is retained in full, and the input callback is not called. Existing models/renderers do not
  require sidecars or input registration
- Reload prepare reads at most 64 KiB plus one oversize-detection byte. Invalid rules produce
  a bounded `LOCOMOTION_001` warning in the generation's diagnostics. It does not mark a loaded
  model missing. Correcting/removing/replacing the sidecar takes effect with normal reload
- Inputs are immutable copied maps of booleans and doubles, at most 32 distinct names. Names
  match `[A-Za-z_][A-Za-z0-9_]{0,63}`. All referenced inputs must be present and correctly typed,
  even for inactive rules. Non-finite/null values or conflicting map types mark a capture
  invalid; current playback is preserved, with at most one warning per model/generation.
  Before any valid selection, the configured initial layer continues. Invalid programmer
  configuration such as oversized maps/bad names or a throwing callback still fails explicitly
- Evaluation is extraction-only and has no resource I/O. The captured result enters the existing
  immutable frame/render pipeline. Recursive rule capture is rejected. Lifecycle mutation from
  a callback abandons its stale capture rather than publishing it into a new lifetime
- State belongs to exact source/entity-object identity, active connection, model and generation.
  Different live entity IDs are independent. Replacing the object/renderer associated with a
  reused numeric entity ID retires that old extraction binding before new commands. Do not
  use one numeric entity ID for simultaneous entities
- Unload, disconnect, repeated play init, generation retirement and explicit runtime retirement
  release selections and sequences. Reload establishes a fresh local locomotion baseline; it
  does not recover a server action history. This is not a new network protocol

Bounds are 32 rules, 8 conditions per rule, 32 referenced input names, 0–200 interval ticks,
and 64 KiB UTF-8 JSON. Unknown or duplicate keys, invalid UTF-8, trailing data and excessive
nesting are rejected. The API never evaluates scripts or arbitrary predicates from resources.

## Runnable consumer and evidence

Enable client startup property `-Dblendlib.examples.locomotionRules=true` and follow the
[runnable guide](../versions/modern/showcase/README.md). The separate locomotion model has an
authored run clip plus its actual packaged sidecar; the tagged actor demonstrates motion
using normal example entity state. Other opt-in modes/default assets remain available.
See [verification](locomotion-rules-verification.md) for scoped headless results. Native graphics,
multiplayer timing and terrain behavior are not implied by headless extraction checks.
