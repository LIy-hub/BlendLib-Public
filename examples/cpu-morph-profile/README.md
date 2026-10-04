# CPU shape-key runnable preview

This opt-in Minecraft 26.3 consumer loads the real, editable Blender 5.1.2 actor in
[`test-assets/cpu-morph`](../../test-assets/cpu-morph). Its descriptor explicitly uses
`format_version: 2` and `blendlib:skinned_morph_cpu_v1`. The separate `cpu_morph_actor`
does not change the existing layered actor, native cubic actor, strict-v1 profile, or
export defaults. This is a feature-branch preview, not a main-branch release.

## Build and run

Use Java 25 from the repository root:

```sh
./gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true \
  -Pblendlib_preview=morph-preview verifyRunnableExamples
./gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true \
  -Pblendlib_preview=morph-preview runRunnableExamplesClient
```

For a separate Fabric installation, install both matching outputs from
`versions/modern/build/26.3/libs/`:

- `blendlib-fabric-1.0.0-beta.4+26.3-morph-preview.jar`
- `blendlib-runnable-examples-1.0.0-beta.4+26.3-morph-preview.jar`

In a Creative test world with commands enabled:

```mcfunction
summon blendlib_runnable_examples:cpu_morph_actor ~ ~ ~
summon blendlib_runnable_examples:cpu_morph_actor ~2 ~ ~
blendlib_example morph list
```

The listing reports numeric IDs of already loaded CPU morph actors within 32 blocks.
Substitute the two reported IDs below. These are client commands, so only this client's
presentation changes; no new network payload, saved state or server authority is involved.

```text
/blendlib_example morph <first-id> blink 1
/blendlib_example morph <first-id> smile -1
/blendlib_example morph <first-id> breath 0.8
/blendlib_example morph <second-id> smile 1
/blendlib_example morph <first-id> status
/blendlib_example morph <first-id> reset
/blendlib_example morph <first-id> clip blink
/blendlib_example morph <second-id> clip breath
```

Available controls and exact signed ranges:

- `blink`: [0, 1]
- `smile`: [-1, 1]
- `breath`: [-0.5, 1]

Values are independent coefficients, not skin influences or percentages that must sum to
one. Out-of-range or non-finite edits are rejected without clamping or partial changes.
The controls bind exact node `MorphRoot/MorphRig/FaceBody` and mesh-local targets `Blink`,
`Smile`, `Breath`. An unchanged override is resubmitted as a fresh immutable frame input.
A command change is visible on the next captured frame, including a held transform frame.

`clip nod|blink|smile|breath` selects the existing full-body animation state and ordinary
controller transition. The nod uses actual sparse cubic bone rotation; Blink is a STEP
weight-only clip, while Smile and Breath use LINEAR weights. Every clip lasts 1.5 seconds.

`reset` clears manual overrides while keeping the selected clip. It resumes sampled
animation weights on the next frame; it does not force zero. On `nod`, omitted weights are
Blink=0, Smile=0 and Breath=0.15. For a complete return to the initial presentation, use
`reset`, then `clip nod`. State belongs to each entity object, is weakly tracked only for
cleanup, and is cleared on unload/disconnect or the first use of a replacement resource
generation. Numeric ID reuse cannot inherit another object's controls.

## Sockets, materials and culling

A gold marker follows the final `cpu_morph:face` bone socket. Shape keys deform vertices;
they do not move the socket by themselves. The cubic nod moves both the skinned body and
the marker. This distinction is intentional and verified.

The model has real `MorphSurface` and `FaceDetails` material primitives. Give an actor the
custom name `morph_amber` to select a warm surface tint, or `morph_hide_details` to hide only
its detail material. Other names use the authored material appearance. Selection is
snapshot-local and cannot alter another actor or a previously captured frame.

Prepared bounds cover the complete allowed signed weight box and cubic motion. A separate
authored [-4,4] block assembly envelope also covers the socket marker. Neither visual
bounds nor the socket influences collision, damage or other server-owned gameplay.

## Public consumer shape

[`ExampleCpuMorphClient.java`](../../versions/modern/showcase/src/client/java/com/liy/blendlib/examples/runnable/ExampleCpuMorphClient.java)
registers the renderer using `skinnedAnimation`, `morphControls`, `onSkinnedVisualEvent`,
`materialAppearance`, `attachments`, and `cullingEnvelope`:

```java
BlendEntityRenderer.<CpuMorphActor>builder(context, ExampleCpuMorphControls.MODEL)
    .skinnedAnimation((entity, request) -> controls(entity).animation())
    .morphControls((entity, request) -> controls(entity).capture())
    // ordinary material, socket and presentation-event callbacks
    .build();
```

`capture()` creates a `MorphFrameOverrides` from the actor's named values. Omitted names
resume the current clip or authored defaults. The library validates the complete batch
against its current immutable generation before advancing clocks or commands. The runtime
fuses morph-before-skin calculations and keeps every morph generation CPU-only, even with
all weights zero. Weight-bearing clips cannot be used in transform-only layers/blendspaces;
bone-only layers can coexist with manual controls through the lower-level API.

## Evidence and remaining acceptance

`verifyRunnableExamples` compiles the actual common/client consumer, checks the two JARs,
and runs `RunnableCpuMorphVerification` directly against resources inside the built example
JAR through production resource reload. It verifies:

- Real cubic and weight-only channels, exact controls, two actual materials and nonzero default
- Actual consumer selection/reset, signed ranges, atomic invalid edits and independent actors
- Controller motion, nod events, final socket attachment, material selectors and bounds at all eight interval corners
- CPU normals, stable primitive topology/material routing, CPU-only snapshots and GPU inventory exclusion, including all-zero controls
- Immutable retained batches/captures, generation fencing, stale reload, unload, numeric ID reuse and disconnect/reconnect
- Byte equality with the final first-party export in both showcase trees, server-safe common classes, and exclusion of example classes/assets from the runtime JAR

See also [runtime contract](../../docs/cpu-morph-profile.md),
[verification](../../docs/cpu-morph-profile-verification.md), and the fixture's
[Blender preview](../../test-assets/cpu-morph/preview-blender.png).
Headless checks and Blender previews do not establish native Minecraft visual acceptance.
In-game screenshots, actual graphics inspection, and performance claims remain deferred.

## Animation-free host examples

The optional runnable mod also reuses the animation-free `cpu_morph:static_face_actor` asset:

- [`static_cpu_morph_block`](../../versions/modern/showcase/README.md#animation-free-deforming-block)
  derives independent cosmetic controls from block position and client presentation time
- [`static_cpu_morph_item`](../../versions/modern/showcase/README.md#animation-free-squeeze-item)
  uses `BlendLibItemMorphs` and ordinary stack counts 1..64 for a cosmetic squeeze, with no clips,
  events, retained playback or gameplay behavior

For the item example, use the walkthrough's matching `static-item-morphs` runtime/example pair.
The existing `morph-preview` package predates this public item API. Both animation-free examples
preserve omitted authored weights and use the same committed static GLB; native graphics
acceptance remains deferred.
