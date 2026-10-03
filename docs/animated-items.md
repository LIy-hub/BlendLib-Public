# Animated marker items

`BlendLibItemAnimations` adds client-only playback to the existing public marker-item
special renderer. Register before item-model baking:

```java
BlendLibItemAnimations.register(new BlendLibItemBinding(
    Identifier.parse("example:animated_wand"),
    BlendModelKey.parse("example:wand"),
    Identifier.withDefaultNamespace("item/stick")),
    BlendAnimationKey.parse("example:idle"));
```

The descriptor state selects the clip in a loaded rigid or skinned model. Call controls
on the client/extraction thread with the **actual stack object passed to rendering**:

```java
BlendLibItemAnimations.playback(stack)
    .play(BlendAnimationKey.parse("example:activate"), ItemAnimationPlayback.Mode.HOLD)
    .speed(1.5).seek(0.1);
BlendLibItemAnimations.playback(stack).pause();
BlendLibItemAnimations.playback(stack).resume();
BlendLibItemAnimations.playback(stack).stop();
```

## Playback semantics

- Registration defaults to LOOP. `play` restarts at zero and selects LOOP, ONCE or HOLD
- LOOP wraps at the clip duration. ONCE stops and returns to the first pose; HOLD
  stops at the exact final pose, including for a descriptor marked as looping
- `stop` selects the first pose and pauses. `resume` continues from the current time
- `seek` uses non-negative finite raw clip seconds. Extraction wraps LOOP seeks and
  applies the end behavior of ONCE/HOLD seeks at or beyond the clip duration
- `speed` accepts finite non-negative multipliers, including zero. Reverse playback
  is not supported. Explicit item playback overrides descriptor speed, next-state,
  and loop settings; it does not emit descriptor events or drive gameplay
- Time uses the monotonic client clock, so GUI animation works without a loaded world.
  Time advances while a stack is not drawn or Minecraft is paused. Explicit playback
  pause freezes it. The next extraction applies endpoint handling

## Identity, contexts and retention

Identity is Java stack-object identity, never the item type, components, equality,
slot number or item count. Two equal stacks have independent controls. All views of
the same object share playback. Vanilla's existing special-model wrapper applies the
GUI, first/third-person, dropped, and fixed-display transforms; no separate renderer
registration or draw call is required for these contexts.

`SpecialModelRenderer.extractArgument(ItemStack)` does not provide a display context,
so this API deliberately does not promise independent per-context playback. Vanilla
or another mod may copy stacks between contexts: a copy is a new playback instance
starting the registered default. This API does not persist state in components, sync
it over the network, or automatically transfer it on copy, splitting or merging.

The registry holds weak stack references and at most 256 entries across all types.
Garbage-collected references are retired on the next registry operation; least-recently
used entries are retired immediately at capacity. Eviction releases runtime clocks,
controllers and cached poses. An evicted stack restarts at its next extraction.
`release(stack)` explicitly retires it. Play initialization and disconnect clear all
entries; model reload retires generation-bound runtime data while retaining the
stack's playback selection and clock. Old control objects become detached after
release, eviction or disconnect: reacquire through `playback(stack)`.

Sampling, palette construction and CPU skinning happen during extraction. Submit
receives only the immutable model-generation snapshot and substitutes vanilla light
and overlay while preserving the captured animation palette. There is no GPU work,
resource loading or live stack/controller access in submit. Missing animated assets
use the existing missing-model fallback.

The compiled `AnimatedItemConsumerExample` and item contract tests cover registration
and API usage, independent identities, end modes, controls and bounded retirement.
In-game visual checks for GUI/hand/dropped transforms remain a separate manual check.
