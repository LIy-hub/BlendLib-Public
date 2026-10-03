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
  and loop settings. Descriptor visual markers require the separate opt-in below;
  playback never drives gameplay
- Time uses the monotonic client clock, so GUI animation works without a loaded world.
  Time advances while a stack is not drawn or Minecraft is paused. Explicit playback
  pause freezes it. The next extraction applies endpoint handling

## Opt-in item visual-event callbacks

The existing two-argument `register(binding, defaultAnimation)` does not install a listener.
To count descriptor markers or update client-only presentation, register an
`ItemAnimationVisualEventHandler` before model baking:

```java
BlendLibItemAnimations.register(new BlendLibItemBinding(
    Identifier.parse("example:animated_wand"),
    BlendModelKey.parse("example:wand"),
    Identifier.withDefaultNamespace("item/stick")),
    BlendAnimationKey.parse("example:idle"),
    (stack, info) -> System.out.println(info.animation() + " crossed "
        + info.event().eventKey() + " at " + info.event().timeSeconds()));
```

`onVisualEvent(ItemStack stack, ItemAnimationVisualEvent info)` runs on the client/extraction
thread after successful animated extraction. The stack is the exact object being extracted.
The immutable `info` contains `model()`, `animation()` (the selected descriptor state),
`generation()` and `event()`. Its core `AnimationVisualEvent` contains the declared
`timeSeconds()` and `eventKey()`. It holds no stack, live controller, asset or render handle.
Keep the registration-lifetime handler free of captured stack references. Re-registering the
same binding/default and handler identity is harmless; a different non-null handler is rejected.
Plain registration preserves an already-installed listener and appearance selector.

Delivery follows explicit item playback, independently of descriptor loop, speed and next-state
settings:

- Only automatically advanced crossings in `(start, end]` are delivered: the left endpoint
  is excluded and the right endpoint is included. Reading status or invoking a control does
  not call the handler; delivery requires a later successful extraction
- First success, play/restart, seek, stop, the first success in a new model generation, and
  recovery after missing/unavailable extraction silently establish a baseline. No seek,
  reload or recovery backlog is emitted. Pause/resume and speed changes retain genuine
  advancement; paused wall time contributes nothing
- LOOP can cross markers over a wrap. At an exact boundary, a marker at the previous cycle's
  duration is delivered before one at the next cycle's zero. Both can be delivered; time zero
  is never synthesized for initial playback. ONCE/HOLD do not loop. Duration markers are
  considered before ONCE returns the visible pose to zero or HOLD freezes the final pose
- Catch-up retains at most the last one **clip-time second**, after clamping ONCE/HOLD to the
  endpoint. Speed therefore affects wall-clock coverage. The existing 4,096-cycle and
  16,384-event advance budgets bound work; an over-budget batch is dropped atomically, not
  partially delivered or saved for the next extraction. Numerically unusable extreme times
  are also discarded and silently rebased so ordinary-speed playback can recover
- One retained exact stack shares a cursor across GUI/hand and other render views. Repeating
  extraction at the same playhead cannot replay a marker. Stack copies have independent
  playback and baselines; no per-display-context identity is inferred
- The complete interval is consumed before callback delivery. A callback exception propagates
  without replaying that interval, including its remaining markers. Nested extraction consumes
  silently. Changing that stack's controls or retiring its playback during a callback stops
  the remaining batch

These are presentation-only extraction callbacks, not evidence of submit or visible display.
They provide no world position or display context, perform no network synchronization, and
must not authorize damage, item consumption or other gameplay. Submission still consumes only
captured immutable render data and never invokes these handlers. Fallback extraction emits no
markers. Cursors share the existing weak-identity 256-entry playback retention and cleanup.

A callback proves only that the animated runtime produced a snapshot. It runs before the
optional material-appearance selector/capture hook; a later appearance callback can still
throw and prevent the renderer argument from being returned. Marker delivery is not rolled
back in that case and does not prove submit, display or a successful whole render pipeline.

The [runnable 26.3 consumer](../versions/modern/showcase/README.md#opt-in-wand-visual-marker-counter)
offers a separately enabled exact-stack counter for its real `attack_whoosh` descriptor marker.
Headless runtime and counter checks do not establish visible graphics or sound acceptance.

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
Garbage-collected references are retired on the next mutating registry operation; least-recently
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

## Read-only item observation

`BlendLibItemAnimations.observe(stack)` returns an immutable `Optional<ItemAnimationObservation>`
for the exact already-retained stack object. Call it on the client/extraction thread. Unlike
`playback(stack)`, it does not create playback, read its clock, sample, refresh LRU, purge weak
references or retire caches. Lookup scans at most 256 weak identities; the returned value may
allocate. Normal mutating registry operations still perform weak-reference cleanup.

The current animation key (descriptor state), mode, speed and playing flag are controls.
`storedSeconds` is the raw playhead at its last update, not elapsed wall time projected to now.
After seeking it may exceed clip duration until extraction applies the mode's endpoint rule.
`lastSample` is absent until a successful extraction; otherwise it records that extraction's
animation key, model key, raw clip seconds, duration and generation. It is historical: play,
pause, seek, speed and stop can change controls before another sample. It does not establish
that the frame was submitted or displayed. No display-context claim is made.

Reload keeps controls and historical sample metadata; `sampleCurrentGeneration=false` marks an
old-generation sample until a fresh successful extraction. The flag compares generations only;
even `true` does not mean the sample reflects current controls. A captured observation never
changes. The API retains no stack, loaded asset, render handle or mutable controller in its
return value. Empty/unseen/copied/released/evicted/disconnected stacks return empty; the API
does not guess which absence cause applies. `null` is rejected.

The opt-in runnable consumer exposes `/blendlib_example item status` for the actual main-hand
wand and distinguishes absent playback, unsampled controls and historical/stale sample output.

## Missing animations and reload recovery

A selected state can disappear when a resource pack publishes a replacement descriptor.
Ordinary item extraction now returns its safe fallback instead of throwing for that missing
state. It never silently selects the initial/default animation or reuses an old-generation pose:
rigid models use their static default pose, while skinned/missing models use the missing-model
placeholder. An unavailable animation is not reported as a successful sample.

`BlendLibItemAnimations.extractionStatus(stack)` returns an immutable optional last-attempt
snapshot (`ItemAnimationExtractionStatus`). It includes `requestedAnimation`, model and generation,
`outcome` (`ANIMATED`, `ANIMATION_UNAVAILABLE`, `MODEL_UNAVAILABLE`, or `EXTRACTION_UNAVAILABLE`),
`fallback` (`NONE`, `STATIC_MODEL`, or `MISSING_MODEL`), and `currentGeneration`. Animation unavailable
also covers a loaded model with no animation declaration. Empty means there has not been a retained
attempt. This read does not acquire playback, read a clock, sample, refresh LRU or retire entries.

Keep this distinct from `observe(stack)`: current controls may change after the last attempt, and
`lastSample` still describes only the last successful animated extraction. Generation currency
alone does not make an attempt reflect new controls and neither status proves successful submit.
The example `item status` command presents all three independently.

Unavailable attempts preserve the requested animation, mode, speed, playing flag and stored time.
They do not wrap, clamp or stop playback against a replacement clip. Playing time continues
logically: the next control operation or successful sample includes elapsed time; paused time
never accumulates. When the requested state returns, the same controls resume sampling and normalize
against its restored duration (LOOP wraps, ONCE stops at first pose, HOLD stops at last pose).
Pause, seek, speed, stop and selecting another state remain available during the outage.
Strict `AnimationControllerDefinition.state` and direct controller extraction retain their validation.
