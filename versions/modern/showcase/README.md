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
   while the base layer keeps running
3. A procedural look-at and rotation-limit pipeline bends the tip after the clip layers
4. A small gold-tinted cube follows an offset from the **final** tip socket, including the
   procedural bend. This is a real separately captured model attachment, not just a debug line

The actor stays where summoned. Its fixed gameplay dimensions and damage behavior do not use
the visual bounds or socket. Remove it with the standard command:

```mcfunction
/kill @e[type=blendlib_runnable_examples:layered_actor]
```

The server emits a sequence/tick cue via vanilla entity data every 100 ticks. Client extraction
uses `animationLayerCues` and `BlendEntityLayerCue` to produce a deduplicated upper-controller
command, including elapsed time for late tracking. The library owns command capture, reload
recapture and unload/disconnect cleanup; this consumer needs no identity cache or lifecycle hooks.
This is deliberately consumer-owned synchronization; it does not introduce another BlendLib
network protocol or claim to exercise every library resynchronization path.

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

## Code and resource map

- `ExampleContent` / `LayeredActor`: server-safe item/entity registration and authoritative cue
- `ExampleClient`: public entity renderer, layer descriptors, reusable procedural components,
  final socket and immutable child snapshot attachment; no resource reads on hot paths
- `ExampleItemCommands`: public item playback API on the real current main-hand stack
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

A culled actor's publication can be old: inspection intentionally does not force extraction or
advance its clock. After reload, inspection returns unavailable until a new-generation sample
exists. An unloaded/disconnected actor is unavailable. Look at the actor and retry after it has
rendered. Repeated inspection does not consume events, replay cues or clear diagnostics; only
another extraction replaces the sampled publication.

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
