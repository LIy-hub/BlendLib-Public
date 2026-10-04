# Fixed-cycle synchronized 1D blendspaces

`AnimationBlendSpace1D` is an opt-in code API for smoothly mixing neighboring animation
samples. It reuses the existing layered evaluator. The default renderer, discrete resource-pack
locomotion rules, descriptors and synchronization protocol are unchanged.

## Standard entity renderer

Configure one existing independent `ModelAnimationLayers.Layer` per sample. Each member must
have mode `OVERRIDE`, configured weight `1`, the same priority and the same resolved bone mask.
Its initial state must be a positive-duration continuous loop with no `next`. Upper attacks or
other layers may keep their own higher priorities, masks, cues and dynamic weights.

```java
var space = new AnimationBlendSpace1D(List.of(
    new AnimationBlendSpace1D.Sample(0.00, IDLE_LAYER),
    new AnimationBlendSpace1D.Sample(0.06, WALK_LAYER),
    new AnimationBlendSpace1D.Sample(0.14, RUN_LAYER)), 0.8);

builder.skinnedAnimation((entity, request) -> IDLE)
    .animationLayerCues(layers, upperAttackCues)
    .animationLayerWeights(upperFadeWeights)
    .animationBlendSpace1D(space, (entity, request) -> entity.measuredHorizontalSpeed());
```

The callback runs once per successful capture attempt, before weight and command callbacks, and
must return a finite scalar in the same units as sample positions. No gameplay input is inferred.
The adjacent solver clamps below/above endpoints and gives exact sample positions exactly one
active sample. Every other member receives explicit zero, because ordinary missing layer weights
mean one. Unrelated multipliers preserve their existing semantics. Definitions contain 2–16
strictly ordered, finite positions, unique existing layer IDs, and a finite cycle in `(0, 600]`
seconds. The entire layer plan still allows at most 16 controllers including nonmembers.

## Timing and ownership

Each sample uses its own controller, allowing unequal clip lengths. For native duration `D`,
descriptor state rate `S`, and common cycle `C`, initialization sets the local time to `phase*D`
and command rate to `(D/C)/S`. Both command and resulting effective rate must fall within
`[1/64, 64]`. Generation binding rejects unsupported values before touching live clocks.

The first successful extraction establishes phase zero. Normal frames advance every member,
including zero-weight members, and only change weights. They do not create commands, seek,
restart clips or change accepted sequences. The input controls mixture, not cadence: changing
speed does not change the common cycle length. Authored cycle-zero gait phase must align.

The exact renderer source, entity owner, model and definition identify activation. Resource
reload retains the same activation origin while resolving fresh durations, rates and masks.
The first valid new-generation frame initializes at the current cycle with no marker history.
A changed owner/source/definition, entity unload, explicit retirement, disconnect/play-init,
or an intervening non-blendspace extraction establishes a fresh activation next time.

Duplicate times and backward times never rewind or double-advance the group. If the gap exceeds
the existing 600-second advance bound, one initialization-style member discontinuity recovers the
current shared cycle. No skipped marker history is replayed. Other layers keep their existing
bounded advance behavior. This exceptional recovery is not a per-frame phase correction.

The space exclusively owns member commands and multipliers. Explicit member cues/commands,
member dynamic-weight entries (including zero), or member resource-rule ownership are errors.
Nonmember rules/cues remain independent. Validation occurs before live instance clocks advance;
standard cue conflicts are rejected before first-capture cue state is retained. Renderer
callbacks that unload, retire, disconnect or reload abandon the frame rather than rebinding
stale state. Blendspace batches are conservatively limited to 128 commands including initialization/recovery
members, and reject an existing queued backlog before clock mutation. Low-level callers switching
from a previously overloaded ordinary layered owner must first drain it through the ordinary path.
This prevents only some members from initializing this frame. Keep renderer configuration/definition stable; do not allocate one every frame.

## Events and inspection

Existing layer marker semantics apply: initialization, reload/recovery discontinuities and
zero-weight frames consume silently; subsequent actual crossings may emit markers. The original
bounded per-frame event guard remains in force. Two nonzero gait samples can each emit their
own marker with their animation, controller/layer identity and effective weight. There is no
automatic footstep deduplication, leader selection or audio routing.

Use the existing immutable layered snapshot for effective weights and clip playheads. Divide
clip-local time by the current loaded animation duration to inspect normalized phase. Reading
inspection does not advance playback. A culled entity's latest publication may lag.

## Runnable proof and limits

[Runnable example](../versions/modern/showcase/README.md#opt-in-synchronized-continuous-1d-blendspace)
contains a separate model with phase-aligned 2/1/0.5-second clips and non-unit descriptor speeds,
a server-authoritative smooth speed ramp measured after collision resolution, independent upper
attacks, procedural pose, sockets, attachments and read-only inspection. The old discrete mode
is still separate.

Tests cover overflow-safe interpolation, binding/rate rejection, member ownership, monotonic
phase, zero-weight progression, activation/reload/large-gap recovery, event suppression/bounds,
callback fences and retained ABI. Packaged verification loads actual GLB resources and compares
endpoint/intermediate final poses and CPU-skinned vertices to independently sampled clips. It
also checks procedural sockets/attachments and changed-descriptor-rate reloads. Focused runtime
tests separately verify reloaded assets whose raw clip durations change.

These are headless checks. No native graphics or visual gait-quality acceptance is implied.
A separate [bounded directional 2D API](directional-blendspace-2d.md) now reuses this scheduler.
There is no dynamic tempo, stride matching, root motion, foot locking, automatic phase inference,
new Blender schema/editor or network authority in these slices.

## Released baseline and preview packaging

This development branch retains the published Minecraft 26.3 beta.4 baseline at
`33099d05784b1ea6467635833052c4ef06bcb8d9`, including its shader transform,
release guide and both-depth-mode native ShaderC/SPIR-V reflection test. A separate
incremental-resource input prevents old raw shaders surviving an up-to-date build.
The additional blendspace code is not part of that published release.

For explicitly identified development JARs, build with:

```sh
bash gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true -Pblendlib_preview=blendspace-preview build verifyRuntimeJar verifyRunnableExamples
```

Both library and example metadata/filenames use `1.0.0-beta.4+26.3-blendspace-preview`.
Default builds preserve the released beta.4 version expression. Never install both
release and preview copies together. This branch does not publish or replace releases.
