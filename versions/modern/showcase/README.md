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
