# Runnable animation consumers for Minecraft 26.3

This is an **opt-in, separate Fabric mod**, not a registration path in the BlendLib library.
It compiles against the actual 26.3 port. Normal builds and the normal BlendLib JAR have no
example entity, item, commands, resources, or entrypoints. Enabling the property adds the consumer
source sets and launch profile; it does not put the consumer classes into the library JAR.

## Build and run

From the repository root, with Java 25 installed:

```sh
./gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true verifyRunnableExamples
./gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true runRunnableExamplesClient
```

Windows: use `gradlew.bat` instead of `./gradlew`. The opt-in is supported only for 26.3.
The isolated client working directory is `versions/modern/run/26.3/runnable-examples-client`.
No task accepts an EULA, creates a server, joins a world, or changes normal run profiles.
Use a new Creative world with commands enabled. If the game asks you to accept terms, decide
that yourself. This example is a development demo, not a benchmark or a gameplay feature.

The verification command builds both separate files under `versions/modern/build/26.3/libs/`:

- `blendlib-fabric-1.0.0-beta.3+26.3.jar`
- `blendlib-runnable-examples-1.0.0-beta.3+26.3.jar`

For a packaged install, put both JARs alongside Fabric API 0.161.0+26.3 in a Minecraft 26.3
Fabric Loader 0.19.5 profile. Install the example on the server too if using multiplayer; its
common entrypoint is server-safe. No other Showcase module or downloaded asset pack is needed.
Removing the example JAR removes its content; do not remove it from a world whose example
entities/items you want to preserve.

## Demonstrate the entity

```mcfunction
/summon blendlib_runnable_examples:layered_actor ~ ~ ~3
```

Watch for at least ten seconds:

1. The full-body `walk` layer continuously bobs the fixture
2. The independently masked upper controller plays `attack` every five seconds, then `idle`,
   while the base layer keeps running; its clip-layer contribution fades on a four-second
   per-entity cycle without restarting either controller
3. A procedural look-at and rotation-limit pipeline bends the tip after the clip layers
4. A small gold rigid fixture (the weapon) follows the **final** tip socket, including the
   procedural bend. Its own authored mount carries a cyan, independently walking skinned
   ornament: actor → weapon → ornament. Both children are real captured models, not debug lines

The actor stays where summoned. Its fixed gameplay dimensions and damage behavior do not use
the visual bounds or socket. Remove it with the standard command:

```mcfunction
/kill @e[type=blendlib_runnable_examples:layered_actor]
```

The server emits a sequence/tick cue via vanilla entity data every 100 ticks. Client extraction
uses `animationLayerCues` and `BlendEntityLayerCue` to produce a deduplicated upper-controller
command, including elapsed time for late tracking. The library owns command capture, reload
recapture and unload/disconnect cleanup for the actor. The optional ornament uses a separate
consumer-owned ephemeral identity per loaded actor object, with explicit unload/disconnect
retirement through the same entrypoint-owned runtime. It never reuses the actor controller key.
This is deliberately consumer-owned synchronization; it does not introduce another BlendLib
network protocol or claim to exercise every library resynchronization path.

The descriptor also contains real `walk_step` and `attack_whoosh` visual markers at 0.25
clip-local seconds. `onAnimationLayerVisualEvent` records callbacks in a bounded counter on
each client actor. Use `/blendlib_example inspect` to find the actor's ID, then
`/blendlib_example inspect <entity-id>` to measure callback totals for its independent base
and upper controllers. This consumer counts callbacks; it does not play sounds or particles.
Server gameplay and authoritative cues never depend on those counts.

## Opt-in standard two-bone IK mechanical arm

The existing layered actor remains the default. To use the three-joint mechanical arm instead,
enable this **client JVM property**, then summon the same registered actor:

```sh
JAVA_TOOL_OPTIONS="-Dblendlib.examples.twoBoneIk=true" ./gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true runRunnableExamplesClient
```

```mcfunction
/summon blendlib_runnable_examples:layered_actor ~ ~ ~3
```

For a packaged install, put both JARs from the build section in the 26.3 Fabric profile, add
`-Dblendlib.examples.twoBoneIk=true` to the launcher's Java arguments, and restart. This is
not `-PtwoBoneIk=true`. Clear the property or set it to `false` and restart to restore the
original layered actor. Item examples are unaffected. While enabled, the actor renderer uses
this IK scene instead of its layered/appearance/nested-attachment scene, so those actor-only
opt-ins and layer inspection do not demonstrate their original behavior at the same time.

Watch for a twelve-second target cycle:

1. The cyan open cage is the moving target in the arm's model space
2. The small gold diamond is attached to the **actual final extracted end socket**; it should
   remain at the cage's center while the upper and lower mechanical links bend
3. The authored idle clip independently rotates and uniformly scales the chain's ancestor
4. Two summoned actors have separate runtime owners; unloading one must not affect the other
5. Reload resources with F3+T and check that the arm and both markers resume together

`ExampleTwoBoneIkScene` installs `TwoBoneIkPoseComponent` through ordinary `poseComponents`,
wrapped by the existing `WeightedPoseComponent` at weight 1. The direct joint chain is
`ArmShoulder -> ArmElbow -> ArmEnd`; its target/pole callback uses only captured client ticks.
The target cage and end diamond are separately authored, packaged rigid models. Their immutable
attachment snapshots use the same captured clock and final `BlendEntitySockets`. The cage's
model-space target is converted through the inverse captured origin transform and descriptor
units, so translated/rotated/scaled resource-pack origins do not apply the transform twice. No X3 rig,
manual analytic-only drawing, new playback engine, terrain sampling or gameplay IK is involved.
The server actor's gameplay dimensions and existing cues stay independent of these visuals.

Full weight and this reachable trajectory make the socket coincide with the target. Reducing
weight, masking out a joint or applying a later rotation modifier may move it away. The demo
preserves the sampled pose and logs one explicit warning per rejected resource generation if a
resource pack removes, duplicates or reparents the chain names. Other numerical/programming
errors are not swallowed. Missing marker resources/sockets omit the markers safely.

`verifyRunnableExamples` loads these actual resources from the built example JAR, decodes the
three-joint rig with the strict loader, invokes `SkinnedAnimationRuntime.extract` with the same
pipeline, and checks final socket reach over a whole target cycle, animated ancestor rotation
and scale, a valid resource-pack GLB with translated/rotated/scaled origin and non-unit descriptor
units, nontrivial renderer root/world transforms, final marker composition, actual CPU
vertices, two owners, resource reload, unload/disconnect and incompatible-chain diagnostics.
This is headless integration evidence. Native graphical rendering and the visual checks above
are explicitly deferred; passing verification is not a claim that a client display was viewed.

## Demonstrate the animated handheld item

```mcfunction
/give @s blendlib_runnable_examples:animated_wand
```

Select the wand in your main hand. Its default idle clip animates in first person, third person,
and inventory. The marker item JSON uses a vanilla model fallback; the public item binding
replaces it during model bake. Try these client-only commands on the actual held stack:

```mcfunction
/blendlib_example item attack
/blendlib_example item idle
/blendlib_example item pause
/blendlib_example item resume
/blendlib_example item fast
/blendlib_example item normal
/blendlib_example item stop
```

`attack` restarts the one-shot and holds its final pose. `idle` restarts a loop; `fast` sets 2x
speed and `normal` restores 1x. `stop` freezes at the selected clip's first pose. These controls
are visual only and do not alter server gameplay or another player's item. Stack copies have
independent playback. The adapter owns bounded instance retention and disconnect cleanup.

### Opt-in wand visual-marker counter

The item handler is disabled by default, independently of the example mod and material-appearance
opt-ins. Enable it once at client startup:

```sh
JAVA_TOOL_OPTIONS="-Dblendlib.examples.itemVisualEvents=true" ./gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true runRunnableExamplesClient
```

For a packaged install, add `-Dblendlib.examples.itemVisualEvents=true` to the launcher's
Java/JVM arguments and restart. This is a JVM property, not a Gradle project property.
It can be combined with `-Dblendlib.examples.itemAppearance=true`; both packaged wand
descriptors already contain `blendlib_runnable_examples:attack_whoosh` at 0.25 clip-local
seconds. The idle clip has no marker, so idle playback alone does not increase the counter.
The property changes only callback registration; the default playback and existing commands
are unchanged.

Hold the actual wand, close chat after starting the attack so it can be extracted, and wait
for the marker crossing before inspecting:

```mcfunction
/blendlib_example item attack
/blendlib_example item events
/blendlib_example item status
```

`events` reads callback evidence only. `status` also prints the same evidence separately from
controls, the historical successful sample, and the last extraction attempt. The output shows
the retained count and last marker's model, animation state, generation, key and declared time.
Neither command acquires playback, advances a clock, consumes markers or refreshes the counter
LRU. A disabled handler reports the required startup property; no retained callback evidence
is reported as absent, rather than as proof that a frame was displayed.

`ExampleClient` actually registers `ITEM_VISUAL_EVENTS::accept` through the public three-argument
`BlendLibItemAnimations.register` overload. The counter retains at most 256 weak exact-stack
identities and one immutable last callback per identity; counts saturate instead of overflowing.
New callbacks refresh its own least-recently-used order. Equal/copy stacks are independent.
Collected identities are purged on the next callback, and disconnect clears all counters.
This example-owned callback history is separate from the library's playback retention: a
playback release/eviction does not itself erase a still-retained counter entry. Reload retains
counts for the same stack object, and the last callback remains explicitly historical.
Counter eviction means the next callback on that stack starts a new count.

First successful extraction after play/restart, seek, stop, reload or missing-state recovery
silently establishes the marker baseline. Keep the wand rendering across 0.25 seconds to
observe a callback; a first sample after that point deliberately does not backfill it.
Replaying attack can then add a callback, while repeated inspection or a held final pose
cannot. Callbacks count successful animation extraction, not submit, display, sound, server
gameplay or a display-context identity. The example makes no sound, particle, network or
world-position calls. See [animated item callback semantics](../../../docs/animated-items.md#opt-in-item-visual-event-callbacks)
for endpoints, explicit LOOP/ONCE/HOLD behavior, bounded catch-up and callback failure/reentrancy.

The packaged headless verifier loads both real wand descriptors and clips, crosses their
marker using the production item playback/cursor, and exercises the same live counter helper.
It checks exact-identity isolation, repeated sampling, retrigger/reload baselines, immutable
inspection, locale-independent output, bounded read-only LRU behavior and disconnect cleanup.
Real stack extraction is also covered by the library's 26.3 integration tests. These checks
do not launch Minecraft or establish visible graphics or sound acceptance.

When graphical validation is resumed, compare two held wands, repeat the status query, pause
and resume across a marker, retrigger attack, reload with F3+T, and disconnect/rejoin. Confirm
there is no replay burst after reload/recovery and that the default-disabled launch still
reports callbacks disabled. Those manual checks remain deferred until explicitly requested.

## Code and resource map

- `ExampleContent` / `LayeredActor`: server-safe item/entity registration and authoritative cue
- `ExampleClient`: public entity renderer, layer descriptors, reusable procedural components,
  final socket and immutable nested attachment capture; no resource reads on hot paths
- `ExampleAttachmentScene` / `ExampleAttachmentOwners`: rigid weapon mount, independently
  animated skinned ornament, generation checks, optional-child fallback and lifecycle retirement
- `ExampleItemCommands`: public item playback API on the real current main-hand stack
- `ExampleItemVisualEvents`: separately opt-in bounded weak exact-stack callback measurements
- `ExampleLayerVisualEvents`: actor-owned, bounded measurement of real layer-event callbacks
- `blend_models/actor.json`, `wand.json`, `marker.json`: ordinary strict-v1 model descriptors
- `prepareRunnableExampleAssets`: deterministic build-time copy of four tracked repository assets

The client uses public version-specific adapter signatures, including the core value types those
signatures expose. It does not instantiate an internal renderer or asset loader. BlendLib's normal
resource reload discovers the descriptors, validates the GLBs and prepares the model handles.
The attachment resolves a current-generation prepared handle during extraction and returns an
empty list if the socket/model is absent. Submit only sees captured snapshots.

## Verification and manual acceptance

`verifyRunnableExamples` compiles both example source sets, checks common classes for client
references, verifies mod metadata, descriptor resource references, exact fixture bytes, and
that the example and library JARs are separate. Its `verifyRunnableExampleAssets` step feeds the
packaged descriptors/GLBs through the real strict loader and evaluates the live scene
configuration: masks, duplicate/retrigger/reload cues, procedural rotation, and final socket
placement. The cue regression uses the same capture helper as live entity extraction.
The layer-event regression consumes the real packaged descriptor's v2 traversal and invokes
the same callback counter as the live renderer. It checks independent controller events,
repeat consumption, immutable inspection, weight suppression without backfill, and bounded
pair retention.
It does **not** launch Minecraft or establish
visual acceptance. The following still needs a real client session:

- Both features render without missing-model diagnostics (`/blendlib diagnostics`)
- Base bob, repeated upper action, procedural tip and attached marker are simultaneously visible
- Two summoned actors remain independent and cues resume after leaving/re-entering tracking range
- Item pause/resume/retrigger works on the held stack in first and third person
- F3+T reload keeps the examples visible, with no old-generation socket/attachment artifacts
- Disconnect/rejoin and world reload remain clean

Record game version, both JAR hashes, commands used and screenshots/logs when making any visual
acceptance claim. A successful build alone is not evidence for rendering, timing or GPU behavior.

## Inspect a sampled entity without changing playback

With the separate example mod installed, list nearby loaded actors and their numeric IDs:

```mcfunction
/blendlib_example inspect
```

Then choose an actor explicitly using one of the listed commands:

```mcfunction
/blendlib_example inspect 42
```

The no-argument list queries a 16-block box around the player and shows at most eight actor IDs,
sorted by ID. It does not select one automatically or depend on vanilla crosshair targeting
(the example actor is intentionally not pickable). The command never loads an entity, starts an animation,
creates an animation instance, or changes gameplay. It only accepts this example's actor type.
The original `/blendlib inspect <model-id>` and `/blendlib diagnostics [model-id]` remain the
right tools for asset loading problems. This new command answers a different question: what
was the actor's most recent layered animation evaluation?

Output separates:

- Received entity cue sequence/start tick, which may be newer than the last evaluated cue
- Last sampled revision, controller state, clip-local seconds, accepted sequence, previous
  state and transition progress (`-1` accepted sequence means no command has been accepted yet)
- **Configured** layer priority, mode, weight and per-bone mask weights; these are not measured
  final bone influence or a claim about final blended/procedural transforms
- Diagnostics from that sampled evaluation, with at most eight diagnostic lines in chat
- Real layer visual-event callback totals for this client actor's lifetime, independently of
  the latest pose sample; each retained controller/layer pair shows its count and last marker's
  animation, clip-local time, loop epoch, occurrence and sampled effective weight

A culled actor's publication can be old: inspection intentionally does not force extraction or
advance its clock. After reload, inspection returns unavailable until a new-generation sample
exists. An unloaded/disconnected actor is unavailable. Look at the actor and retry after it has
rendered. Repeated inspection does not consume events, replay cues or clear diagnostics; only
another extraction replaces the sampled publication.

Callback totals survive F3+T for the same client actor object and are printed even if there
is no current-generation layer sample. New objects after unload/retracking start at zero.
The counter keeps at most eight pairs and one last immutable event per pair; numeric counts
saturate rather than overflow. Events from additional pairs still increment a separate
untracked-pair count and the total. The live scene has only two pairs. No global entity map,
saved entity field or network field holds these measurements.

Callbacks represent exact automatically crossed `(start, end]` descriptor markers. New cues,
first observation, reload and retracking silently establish a baseline; repeated extraction
does not replay markers, and time-zero events are not synthesized. Muted upper-layer markers
are consumed without delivery and are not backfilled when the layer becomes nonzero. The
reported effective weight is sampled configured × dynamic weight, before masks and priority
resolution; it is not historical marker-time weight or final visible bone influence. See
[`layered-animation.md`](../../../docs/layered-animation.md#per-layer-visual-event-callbacks)
for catch-up, traversal-truncation, event-budget and callback-failure semantics.

`ExampleLayerInspection.format(layers, snapshot)` is a small Minecraft-free formatter you can
copy into your own development consumer. Obtain the immutable snapshot using the existing
`SkinnedAnimationRuntime.layeredSnapshot(instanceKey)` on its extraction owner thread. It does
not retain the snapshot or expose mutable controllers. The utility stays in the separate
example JAR, and no new public library API is required. Socket transforms and procedural results
remain available through their existing extraction callbacks; this command does not retain or
pretend to measure them. Animated items already have the playback controls documented above.

Manual command checks still required in a working graphical client: nearby-ID discovery and
explicit-ID targeting, wrong/missing target feedback, two actors with different cues, repeated
reads, F3+T then reinspection, tracking unload, and disconnect/rejoin. The packaged headless
verification covers formatter output and immutable runtime lifecycle behavior, not command UI
or visual rendering acceptance.

For event acceptance, watch an actor across several `walk` loops and an upper cue, then
inspect its per-pair counts and last keys. Compare two actors, repeatedly inspect without
forcing extraction, reload, and leave/re-enter tracking range. Expect no burst of old upper
events on a new cue/reload/retracking, and no event backlog after zero-weight samples. Record
these checks in the client; headless callback evidence alone is not graphics acceptance.

### Inspect the held item without creating playback

Hold the example wand and run `/blendlib_example item status`. This reads the exact main-hand
stack object; it never calls `playback`. Current controls and stored raw playhead are printed
separately from the last successful extraction's clip time, duration and generation. Seek/play/
stop can change controls before extraction. After resource reload, old sample metadata is
explicitly stale until re-extraction. Unseen, copied, evicted, released and disconnected stack
identities have no retained status; inspection does not recreate them. Repeated status queries
do not advance animation or keep the stack in the LRU. Samples describe extraction, not proof
that a frame was submitted. As with other controls, the command is client-only and opt-in.

The item status command also prints the last extraction attempt independently: unavailable state,
static/missing-model fallback, requested animation and attempt generation. Reload-stale successful
samples remain labeled historical. If a resource pack removes the selected animation, controls
remain intact; restoring it resumes with the requested LOOP/ONCE/HOLD behavior. Playing time
includes the outage; paused time does not. No fallback is represented as a successful animation.
It also prints the separately opt-in [wand callback counter](#opt-in-wand-visual-marker-counter);
this history is distinct from both playback observations and extraction-attempt status.

## Dynamic clip-layer weights

`animationLayerWeights` captures `ExampleAnimationScene.clipLayerWeights` once per extraction.
The upper-layer multiplier cycles 0 → 1 → 0 independently for each actor; the base is omitted
and therefore keeps its configured weight. This is separate from procedural look-at weighting.
The inspection command displays configured weight and sampled effective weight (before mask and
priority resolution). Zero weight still advances the upper cue. A positive higher-priority override
keeps owning masked bones and blends against rest; this is not a crossfade back to lower-priority
walking. At exactly zero the lower-priority contribution becomes eligible again.

## Independently colored actors and a hideable material slot

The actor renderer now uses a separate `appearance_actor` derivative with the original body
and a real skinned side-badge primitive. Its exact authored slots are
`ShowcaseAnimationSurface` and `ExampleAccessory`. The original actor asset, the wand and the
socket marker remain unchanged.

```mcfunction
/summon blendlib_runnable_examples:layered_actor ~-1 ~ ~3 {CustomName:{text:"Orange"}}
/summon blendlib_runnable_examples:layered_actor ~1 ~ ~3 {CustomName:{text:"Blue bare"}}
```

Orange has an orange RGB body multiplier and visible badge; Blue bare has a blue multiplier
and hidden badge. `Orange bare` and `Blue` demonstrate the other combinations. An unnamed
actor retains authored colors and visibility. Custom names are synchronized by vanilla entity
data; each extraction captures its own immutable selection against a shared prepared model.
The gold socket cube is a separate attachment, so hiding the badge does not hide that cube.

The same `ExampleMaterialAppearance.forName` helper is called by the live renderer and the
executable asset verification. The verifier checks actual packaged slot geometry and bounds,
selector independence, CPU capture and unknown-name diagnostics. See
[`docs/material-appearance.md`](../../../docs/material-appearance.md) for the API, atomic
unknown-slot fallback and the manual visual check. Headless verification does not claim
native-window/GPU visual acceptance.


## Three-level assembly, reload and culling

The registered actor uses `ExampleAttachmentScene.capture` during extraction. It reuses
`marker.glb` as the rigid weapon and the existing `wand` skinned model as its cyan ornament.
The weapon's authored mount is `(0, 0.5, 0)` in its static model coordinates; it is captured
with the weapon snapshot's actual root and units. The callback returns a nested graph; the
standard renderer captures and submits its flattened immutable form exactly once.
The ornament has its own walking clock, appearance, light and overlay. Its clock cannot
replace or reset the actor's layered animation. Both modes reuse the same model assets.
The optional extended mode below uses the ordinary renderer's explicit assembly-envelope API.

Missing actor tip or unavailable/stale weapon omits both children. An unavailable/stale ornament
or absent walk state omits only that ornament and retires its clock, leaving the weapon visible.
Current handles are resolved each extraction; no generation-bound handle is cached. Resource
reload uses the existing runtime's generation retirement and creates current-generation frames.
Entity unload, object replacement and disconnect cannot transfer the child clock to another
actor. Spawn two actors, remove one, reload resources (F3+T), and reconnect to exercise these flows
when performing the later native visual check.

**There is no automatic aggregate attachment culling.** Vanilla culls the root before the
attachment callback runs. The **default mode** keeps these exact authored assets and transforms
inside the existing root envelope and does not configure an extra envelope. Changing child
`CullingMetadata` cannot enlarge the root's pre-extraction culling envelope.
The root prepared bounds are the cube with half-extent `1.320986986160` blocks. The authored
root sway is at most `0.070000000298`, and the tip translation is `0.600000023842`. All scales
in the clips are one; procedural components change rotation only.

- With fixed identity weapon root/offset rotation, offset `(0,.20,0)` and scale `.25`, its actual
  vertices have combined local radius at most `sqrt(.125² + .20² + .50²)`. Adding the root/tip
  translation bound gives `1.222833633827`; a 1% plus `.0001` float allowance gives
  `1.235161970166`, below the root half-extent
- The wand's all-pose unpadded authored radius is `1.307808796846`. With mount y `.5`, ornament
  scale `1.5`, wand units-per-block `2.5`, and inherited weapon scale `.25`, its total radius is
  at most `.670000024140 + .20 + .25 * (.5 + 1.5 / 2.5 * 1.307808796846)` = `1.191171343667`;
  the same float allowance gives `1.203183057104`, also below the root half-extent

These radial bounds cover arbitrary parent/socket rotations and current override-layer blends.
They do **not** authorize independent weapon-root rotation, larger offsets/scales, additive
translations, a different mount, or resource-pack geometry/animations. Such changes need a
new conservative root/host envelope and re-verification. Server collision dimensions remain
unchanged. Asset verification checks the fixed source assumptions; native frustum-edge and
visual acceptance are deliberately deferred, with no native visual PASS claimed.


### Optional extended assembly

Launch the same example with this **client JVM system property**:

```text
-Dblendlib.examples.extendedAttachments=true
```

For the Gradle development client on a POSIX shell:

```sh
JAVA_TOOL_OPTIONS="-Dblendlib.examples.extendedAttachments=true" ./gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true runRunnableExamplesClient
```

For a packaged installation, add the `-D` argument to the launcher's Java/JVM arguments.
It is not a Minecraft chat command or a Gradle `-P` project property. Restart the client to
switch modes. The example reads it once at client initialization, uses that same immutable
selection for attachment extraction and renderer configuration, and never reads a property
during culling or submit. Omit it (or use `false`) for the unchanged compact showcase.

Extended mode changes the real gold weapon offset from `(0, .20, 0)` to `(3, .20, 0)` in
final-tip local block coordinates, retaining its `.25` scale and the independently animated
cyan ornament. This visibly separates the weapon/ornament from the body, outside the old
root-only bounds. No dummy mesh, asset-bound padding, render-distance override or collision
change is used. `ExampleClient` opts in through:

```java
builder.cullingEnvelope(ExampleAttachmentScene.EXTENDED_ENVELOPE);
```

The constant is `new BlendEntityCullingEnvelope(-4.1, -4.1, -4.1, 4.1, 4.1, 4.1)`.
These are **entity-local blocks before root rotation**, after descriptor-unit conversions,
all child offsets and scales, and every possible authored/procedural pose. The real wand's
`units_per_block = 2.5` is included; its inherited `.25` weapon scale and `1.5` own offset scale
are not omitted. The all-pose influence-radius proof gives the following conservative radii:

- Weapon: root/tip translation radius plus the largest actual translated/scaled marker vertex
- Ornament: `.670000024140 + sqrt(3² + .20²) + .25 * (.5 + 1.5 / 2.5 * 1.307808796846)`

Both remain below `4.1` blocks even after a 1% plus `.0001` allowance. Verification computes
these values from the packaged geometry, animation extrema, skin weights and inverse binds;
it fails if those source assumptions or the configured offsets change.

The library rotates the explicit box conservatively using an origin-centered radius, so this
symmetric example produces an approximately `7.1014`-block half-extent cube, unioned with the
current root model and vanilla entity bounds. It deliberately admits false positives near the
frustum edge; it does not disable frustum, distance or hidden-frame decisions. The configured
box is immutable for the renderer lifetime and survives F3+T; a resource pack with larger
geometry/translation/scale needs a newly sufficient configured envelope and re-verification.
Child snapshot culling metadata alone cannot supply it. See the
[entity assembly contract](../../../docs/nested-entity-attachments.md) for consumer responsibilities.

`verifyRunnableExamples` runs both modes regardless of the launch property. It feeds actual
prepared rigid triangles and captured CPU-skinned vertices through the same root/unit/node
transform order and flattened attachment placements. The extended regression verifies that
geometry really exceeds old root-only bounds, then checks the ordinary culling-entry helper's
union across identity/yaw/arbitrary quaternion root rotations, two independent actor/world
positions and resource generations. It also retains an old snapshot through reload, verifies
separate ornament owners, rejects distant regions, and checks hidden root/weapon subtree
suppression. These are headless geometry/lifecycle checks, not a GPU rendering claim.

Native frustum-edge acceptance remains **unverified**. With extended mode enabled:

1. Summon two actors at separate positions and yaw angles, for example:
   `/summon blendlib_runnable_examples:layered_actor ~ ~ ~5 {Rotation:[0f,0f]}` and
   `/summon blendlib_runnable_examples:layered_actor ~5 ~ ~5 {Rotation:[90f,0f]}`
2. Walk around them and rotate the camera until the body is offscreen but its remote weapon or
   cyan ornament remains onscreen. Check that the visible descendant does not disappear
3. Repeat after F3+T, tracking unload/re-entry and disconnect/rejoin. Both actors should retain
   independent animation, and the optional children should recover with the current generation
4. Use F3+B to confirm gameplay dimensions stay unchanged. Move the complete conservative box
   out of view; the larger box may keep extraction alive longer, but never unconditionally
5. Restart without the property and confirm the original compact assembly still renders

Record both JAR hashes, game version, property, commands and screenshots/logs before reporting
native visual acceptance. The automated build does not establish these results.

## Opt-in two-wand material appearance

Enable the item selector once at client startup:

```sh
JAVA_TOOL_OPTIONS="-Dblendlib.examples.itemAppearance=true" ./gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true runRunnableExamplesClient
```

For a packaged installation, add `-Dblendlib.examples.itemAppearance=true` to the launcher's
Java/JVM arguments and install both JARs described above. Restart to switch modes. This is a
JVM property, not a Gradle project property. Without it, the original one-slot animated wand
registration and compact attachment geometry are unchanged.

In a Creative world with commands enabled, run this function as a player:

```mcfunction
/function blendlib_runnable_examples:item_appearance
```

The function is packaged inside the example mod and gives exactly two named, non-stacking
wand items. No external datapack or runtime mesh-generation script is required. Equivalent
individual commands are:

```mcfunction
/give @s blendlib_runnable_examples:animated_wand[minecraft:custom_name={text:"Orange"}] 1
/give @s blendlib_runnable_examples:animated_wand[minecraft:custom_name={text:"Blue bare"}] 1
```

`ExampleItemMaterialAppearance.register` registers the live item appearance callback before
registering the idle animation. On extraction, `select(ItemStack)` reads only the stack's
custom-name component and calls `forName`, the same selection helper checked by packaged
verification. The Orange stack requests body RGB `0xff8844` and a visible tip crystal; Blue bare
requests RGB `0x4488ff` and a hidden crystal. These are multiplicative RGB tints, not replacement
texture colors. Both stacks use the same cached prepared model handle, body, skeleton, animation,
and authored `WandAccessory` primitive. No geometry is copied or generated per stack.
The opt-in `appearance_wand` descriptor uses the committed `appearance_wand.glb`: a slender
3D shaft (`WandBody`) and an eight-triangle tip crystal (`WandAccessory`), authored by
`tools/generate_appearance_wand.py`. It preserves the original demo wand's rig, clips and
`units_per_block = 2.5`. The original demo wand used a technical quad from `actor.glb`, not
an existing sculpted wand; the new geometry is explicitly authored at build-source time.
The `wand` descriptor used by attached ornaments remains unchanged.

Unknown or absent custom names return an empty appearance map and preserve authored colors
and visibility. `Orange bare` and `Blue` also work, allowing color and visibility to be changed
independently. The selector affects client presentation only; it does not edit stack data or
synchronize state. Normal vanilla custom-name synchronization supplies the selection input.

`verifyRunnableExamples` loads the packaged appearance-wand descriptor and actual GLB, checks
both authored material slots and the distinct crystal triangles, executes the live name helper,
and verifies CPU-skinned snapshot capture, independent RGB/visibility, shared handles,
retained-snapshot isolation, restoration to authored defaults and unknown-slot diagnostics.
It also checks that the helper and command function are packaged only in the optional example
mod. These are headless data/lifecycle assertions, not GPU or native visual acceptance.

Manual acceptance remains **unverified**:

1. Keep Orange and Blue bare in separate hotbar slots and inspect both in the inventory GUI
2. Alternate the main hand and put the other in the offhand; verify independent color and crystal
3. Drop both items or place them in item frames; verify their appearance remains stack-specific
4. Use `/blendlib_example item attack`, `pause`, `resume` and `status` on each held wand;
   appearance must coexist with the existing per-stack animation controls
5. Press F3+T, then repeat GUI/held/dropped views and disconnect/rejoin. Previously captured
   snapshots must not acquire another stack's appearance; new extractions should use new resources
6. Rename a wand to an unconfigured name and check authored defaults, then restore its name
7. Restart without the JVM property to confirm the original wand registration still works

Record the two JAR hashes, game/loader versions, property, commands and screenshots/logs
before claiming native visual acceptance.

## Opt-in named texture skins

Named skins are disabled by default. Enable them once at client startup, independently of
item RGB/visibility and visual events:

```sh
JAVA_TOOL_OPTIONS="-Dblendlib.examples.namedSkins=true" ./gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true runRunnableExamplesClient
```

For a packaged install, add `-Dblendlib.examples.namedSkins=true` to the launcher's Java/JVM
arguments and restart. This is a JVM property, not a Gradle project property. Both the entity
and wand consumers use it; the server needs no matching JVM property. The optional example
mod must still be installed on both sides when playing multiplayer.

In a Creative test world with commands enabled, create two actors and two wands together:

```mcfunction
/function blendlib_runnable_examples:named_skins
```

Or create them separately:

```mcfunction
/summon blendlib_runnable_examples:layered_actor ~-1 ~ ~3 {CustomName:{text:"Ember"}}
/summon blendlib_runnable_examples:layered_actor ~1 ~ ~3 {CustomName:{text:"Frost"}}
/give @s blendlib_runnable_examples:animated_wand[minecraft:custom_name={text:"Ember"}]
/give @s blendlib_runnable_examples:animated_wand[minecraft:custom_name={text:"Frost"}]
```

`Ember` uses the original warm pixel pattern on the body and the cool pattern on the accessory;
`Frost` reverses them. These are two real, opaque 8×8 PNG assets under `textures/skins/`, not RGB
multipliers or per-instance mesh copies. Both actors share `appearance_actor`; both wands share
`appearance_wand`. The wand skin opt-in uses that real two-slot shaft/crystal mesh even when
`itemAppearance` is false. The separate weapon and ornament attachments retain their own
materials. Unnamed and unrecognized names retain authored textures. Rename an existing actor
or wand using vanilla synchronized custom-name data to change its next extracted skin.

`ExampleNamedSkins.register()` runs during client initialization, before the first model reload.
It registers the same model-scoped `ember` and `frost` names for the two actual model keys using
`BlendLibModelSkins.register`. Actor slots are `ShowcaseAnimationSurface` and `ExampleAccessory`;
wand slots are `WandBody` and `WandAccessory`. The entity builder calls `.skin(...)`; items use
`registerWithSkin(binding, selector)` or `registerWithSkin(binding, appearance, selector)` before
ordinary animation registration. Definition maps are immutable. Selection reads only the current
custom name during extraction; there is no resource lookup, GLB parsing or texture decoding in
that callback or during submission. No registration takes place from render callbacks.

The optional suffix after a skin name selects the existing RGB/visibility behavior. For example,
`Frost Blue bare` keeps Frost textures, multiplies body RGB by blue, and hides the accessory.
Actor material appearance is already enabled. To apply the same suffix to items, launch with
both properties:

```sh
JAVA_TOOL_OPTIONS="-Dblendlib.examples.namedSkins=true -Dblendlib.examples.itemAppearance=true -Dblendlib.examples.itemVisualEvents=true" ./gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true runRunnableExamplesClient
```

```mcfunction
/give @s blendlib_runnable_examples:animated_wand[minecraft:custom_name={text:"Frost Blue bare"}]
```

Appearance multiplies the selected texture color, so its final color need not equal the RGB
multiplier. Plain `Orange`/`Blue bare` names preserve the previous appearance example. A bare
skin name leaves tint and visibility authored. Disabling `namedSkins` leaves the original
registration and name behavior unchanged. Item playback commands and the optional visual-event
counter continue to use the same stack and animation binding.

`verifyRunnableExamples` checks both committed PNGs are packaged unchanged and decodes them,
loads the actual actor/wand GLBs, verifies exact slot matches, exercises the live name selection,
proves shared handle and captured CPU-skinned geometry, tests appearance/copy preservation, and
checks unknown-skin authored fallback and immutable definitions. The public client compile
fixture covers both item overloads and observing `selectedSkin()` / `skinDiagnostic()` from
`BlendLibItemSkinSelector.captured`. Requested skin identity can remain present on a failed
selection; inspect its diagnostic too. These are extraction observations, not evidence that a
frame was submitted or displayed. Reload validation and backend routing have separate library
tests. The probe deliberately does not mutate or freeze the global startup registry.

Manual graphics acceptance is still deferred. Compare both actors and both held wands, change a
name, use a combined RGB/hidden-accessory name, exercise attack and status, then press F3+T and
confirm the same choices survive fresh extraction. A resource pack may replace these PNGs at
the same resource IDs; after reload the new resources should be used. Removing a replacement
resource or changing an authored slot should produce atomic authored fallback and a diagnostic,
not a partially replaced skin. Also check a launch without the opt-in. No client session or
visual/GPU acceptance is implied by a passing headless probe.
