# Start here: BlendLib for Minecraft 26.3

This is the current **cumulative development-source** entry point. The code includes the
26.3 port and subsequent animation, appearance and attachment work. It is not a new public
release: the published Beta.3 release still covers its original 15 targets. A `+26.3` filename
alone does not identify which development checkpoint it contains; keep the package's commit,
source-tree identity and SHA-256 checksums with your integration.

## 1. Choose the exact environment and JARs

- Minecraft **26.3**, **Java 25**, Fabric Loader **0.19.5+**
- Fabric API **0.161.0+26.3** is the pinned build/test dependency; use the exact game target
- Build with the checked-in **Gradle 9.6.0** wrapper; the modern build pins Loom **1.17.21**
- Install exactly one `blendlib-fabric-1.0.0-beta.3+26.3.jar` in your instance's `mods` folder
  alongside Fabric API and your content mod. It includes BlendLib API, core, common and client
  modules; do not install these modules separately
- `blendlib-fabric-1.0.0-beta.3+26.3-sources.jar` is for IDE/source browsing, not `mods`
- Only for the demo, also install `blendlib-runnable-examples-1.0.0-beta.3+26.3.jar`.
  It owns the demo entities/items/assets/commands; the library alone adds no gameplay content

For multiplayer demos install the example mod and its dependencies on both sides. A content
mod's own installation instructions determine its deployment. Never mix different Minecraft
runtime JARs. The separate Blender exporter needs **Blender 5.1+**; see the
[export checklist](release/blender-export-checklist-v1.md) and [asset template](../templates/model-pack/README.md).

## 2. Build, verify and launch the smallest useful demo

From this checkout's root, with Java 25 selected:

```sh
bash gradlew -p versions/modern -Pminecraft_version=26.3 build verifyRuntimeJar
bash gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true verifyRunnableExamples
bash gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true runRunnableExamplesClient
```

Windows: replace `bash gradlew` with `.\gradlew.bat`. Build outputs are in
`versions/modern/build/26.3/libs/`. `build` includes the modern test suite;
`verifyRunnableExamples` checks separate packaging and real packaged model/animation assets
headlessly. To run just tests and compile the public client consumer fixture:

```sh
bash gradlew -p versions/modern -Pminecraft_version=26.3 test
```

The demo client uses `versions/modern/run/26.3/runnable-examples-client`. Create a separate
Creative test world with commands enabled, then:

```mcfunction
/summon blendlib_runnable_examples:layered_actor ~ ~ ~3
/give @s blendlib_runnable_examples:animated_wand
/blendlib_example inspect
/blendlib_example item attack
/blendlib_example item status
```

Hold the actual wand and close chat after `attack` so extraction can advance. The actor combines
independent base/upper animation, dynamic layer weights, procedural motion, final sockets and
actor → weapon → ornament capture. See the [runnable guide](../versions/modern/showcase/README.md)
for full commands, expected behavior, lifecycle and manual acceptance steps. Launch-task exit
success does not prove a window or rendered world succeeded.

### Optional demo modes

These are **client-startup JVM properties**, not Gradle `-P` properties. All are disabled by
default; `-Prunnable_examples=true` separately opts the build into the demo mod.

| JVM argument | Try in the test world |
| --- | --- |
| `-Dblendlib.examples.namedSkins=true` | `/function blendlib_runnable_examples:named_skins` creates Ember/Frost actors and wands |
| `-Dblendlib.examples.itemAppearance=true` | `/function blendlib_runnable_examples:item_appearance` gives Orange and Blue bare wands |
| `-Dblendlib.examples.itemVisualEvents=true` | Hold a wand, run `item attack`, then `/blendlib_example item events` |
| `-Dblendlib.examples.twoBoneIk=true` | Summon an actor to use the [mechanical-arm scene](../versions/modern/showcase/README.md) with moving target/final socket markers |
| `-Dblendlib.examples.extendedAttachments=true` | Summon an actor; the extended assembly exercises explicit conservative culling bounds |

For example, in a POSIX shell:

```sh
JAVA_TOOL_OPTIONS="-Dblendlib.examples.namedSkins=true -Dblendlib.examples.itemAppearance=true -Dblendlib.examples.itemVisualEvents=true" bash gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true runRunnableExamplesClient
```

For a packaged install put the same `-D...` options in the launcher's Java/JVM arguments and
restart. These flags do not enable features in another mod automatically. No graphical,
multiplayer, sound or particle acceptance is implied by their headless checks.

## 3. Integrate one model in your own mod

Use a 26.3 Fabric/Loom project. For the unobfuscated 26.x target, put the runtime in `libs/`
and add this Gradle Kotlin dependency (the older remapped 1.21.x setup differs):

```kotlin
dependencies {
    implementation(files("libs/blendlib-fabric-1.0.0-beta.3+26.3.jar"))
}
```

Declare `blendlib` in your mod's `fabric.mod.json` dependencies, retain your exact Minecraft,
Loader and Fabric API requirements, and install the runtime in the development/test instance.
This is a local-file dependency, not a claim that a Maven version has been published. See the
[dependency guide](developer-handbook.md#dependencies) for split client source-set wiring.

Copy the [static asset template](../templates/model-pack/README.md), rename its namespace to
`example`, and keep its descriptor/GLB/texture references consistent. Call this registration
from your **client initializer** with an `EntityType` your mod already registers:

```java
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.fabric.client.entity.BlendEntityRenderer;
import com.liy.blendlib.fabric.client.entity.BlendEntityRenderers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

public final class ExampleBlendRegistration {
    public static <E extends Entity> void register(EntityType<E> type) {
        BlendEntityRenderers.register(type, context ->
            BlendEntityRenderer.<E>builder(context, BlendModelKey.parse("example:starter_rigid"))
                .staticRestPose().shadowRadius(0.45F).build());
    }
}
```

Check `/blendlib inspect example:starter_rigid` before adding animation. This registers a
renderer, not an entity, spawn egg, AI or server hitbox. Keep client render classes out of
common/server initializers. For animation, switch deliberately to the matching animated builder
path; do not layer animation on a static-rest-pose configuration.

The smallest named-skin addition, during client initialization **before the first reload**, is:

```java
import com.liy.blendlib.api.BlendModelKey;
import com.liy.blendlib.api.BlendResourceId;
import com.liy.blendlib.fabric.client.entity.BlendEntityRendererBuilder;
import com.liy.blendlib.fabric.client.render.BlendLibModelSkins;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.entity.Entity;

public final class ExampleSkinRegistration {
    public static <E extends Entity> void configure(BlendModelKey model,
            BlendEntityRendererBuilder<E> builder) {
        var winter = BlendResourceId.parse("example:winter");
        BlendLibModelSkins.register(model, Map.of(winter, Map.of(
            "Body", BlendResourceId.parse("example:textures/entity/winter.png"))));
        builder.skin((entity, request) -> Optional.of(winter));
    }
}
```

Here `Body` must be an exact authored slot in **your** model and that PNG must exist in your
resources; these are integration placeholders, not additional template assets. The builder must
use the same model key. For complete compiled public entity/item overloads and capture observers,
use the [consumer fixture](../blendlib-fabric-consumer-fixture/README.md). For a self-contained
live example use the demo's real slots/textures instead of inventing assets.

## 4. Add only the capability you need

| Need | Current ordinary consumer path and guide |
| --- | --- |
| Descriptor clips and server-driven animation | [Handbook](developer-handbook.md#synchronization), [synchronized visual events](synchronized-visual-events.md), [recovery](animation-recovery-runnable-examples.md) |
| Independent entity controllers, masks and tick-based cues | [Layered animation](layered-animation.md), including dynamic weights and per-layer callbacks |
| Standard three-joint two-bone IK, model-space target/pole, diagnostics | [Two-bone IK](standard-two-bone-ik.md) |
| Look-at, rotation limits, springs, weighted/masked procedural stages | [Procedural components](procedural-components.md) |
| Final-pose sockets and real attached models | [Sockets](final-pose-sockets.md), [nested attachments](nested-entity-attachments.md), [explicit culling envelopes](entity-culling-envelope.md) |
| Per-stack LOOP/ONCE/HOLD, play/pause/resume/seek/speed and callbacks | [Animated items](animated-items.md), including observational status and reload behavior |
| Per-instance slot RGB and visibility | [Entity appearance](material-appearance.md), [item appearance](item-material-appearance.md) |
| Named texture replacement by authored slot | [Named skins](named-texture-skins.md) |
| Read-only diagnostics | `/blendlib inspect`, demo `inspect` / `item status`; [runtime inspection evidence](runtime-inspection-verification.md), [item observation](item-observation-verification.md) |

### Ordinary APIs versus advanced X6

The ordinary builder/item APIs above do not require an X6 provider or lease. Named skins share
an internal texture-only transformation with X6 but do not adopt its provider registry or
synthetic-part limits. The historical [X6 expansion](expansion/x6/README.md) and
[experimental/SPI handbook section](developer-handbook.md#experimental) describe a separate
advanced path, not prerequisites for these examples. Experimental backend/material capability
presence is not a production support or stable ABI promise.

## 5. Contracts that matter before shipping a consumer

- **Identity/lifecycle:** entity cue history uses exact object/source/session/model/generation
  identity. Do not substitute reused numeric entity IDs. Own and reset procedural state and
  independently captured attachment identities as their guides require. Item playback uses
  the actual stack object; equal stacks and copies have independent state, while display
  contexts of one stack share it. Retention/cleanup are bounded, not persistent saved gameplay
- **Events:** callbacks observe extraction, not frame presentation or server-authoritative
  actions. Initial capture, reload, seek/restart and recovery establish silent baselines;
  skipped history is not unbounded replay. Catch-up and budgets are bounded and path-specific:
  read the synchronized, layer and item contracts rather than assuming identical cursors
- **Attachments:** at most **64 visited attachment occurrences** and **8 edges below the root**.
  Snapshots do not automatically enlarge root culling to fit children. Configure sufficient
  entity-local bounds for all child poses/scales and resource-pack overrides; these never
  change gameplay collision or disable ordinary distance/visibility decisions
- **Skins:** register before the first model reload freezes the registry. Selection and
  diagnostics are captured with the generation; invalid selections fall back atomically to
  authored materials. A retained requested name does not mean it applied successfully.
  Texture-resource existence does not prove PNG decoding or historical texture-byte isolation
- **Scope:** strict GLB profiles, CPU skinning and rotation-only procedural components remain
  the ordinary path. Standard block-entity animation still requires skinned models. There
  is no universal pause/seek facade across every host, reverse item playback, automatic
  multi-controller network replication, gameplay-authoritative animation, or generic glTF/PBR support

## Migration, evidence and the remaining backlog

These additions are opt-in. Existing unselected/static registrations keep their old behavior;
plain item binding remains static unless animation is registered. Existing descriptors need no
schema change for named skins. Migrate one consumer at a time against the matching target JAR,
register before reload/bake, and verify fallback, copy identity and reconnect behavior.
Beta status still means no blanket stable API/ABI guarantee; the older
[API design rules](api-stability.md) are design intent, not a new Beta compatibility promise.

The prior named-skin checkpoint `dbc4bffc90075db02bfe335eac0c0f6c120d8ae0` has passing
[26.3 build/example verification](https://github.com/LIy-hub/BlendLib-Public/actions/runs/37147435646)
and [version-matrix CI](https://github.com/LIy-hub/BlendLib-Public/actions/runs/37147435588).
The current additive feature is [standard two-bone IK](standard-two-bone-ik.md); see its
[verification summary](standard-two-bone-ik-verification.md). The cumulative delivery manifest
binds the current source/JARs and exact-commit CI. Older batch reports, including the
[named-skin verification](named-texture-skins-verification.md), establish only their own
checkpoint. The earlier developer-entry consolidation changed documentation only; this IK
branch contains new runtime, consumer and tests, without creating a public release.

Remaining work is acceptance or an explicitly chosen future feature, not a claim of missing
implementation for the APIs above:

1. Obtain one real mod's integration feedback: target host, asset, required behavior, and a
   minimal reproducible failure or friction point. Prefer a bounded fix over another feature batch
2. When a suitable graphics environment is available and testing is requested, complete the
   runnable guide's native world/frustum/reload/tracking/disconnect checks. Earlier cloud client
   startup failed before a world because graphics support was unavailable; no visual pass exists
3. Keep hardware/performance, Iris/Sodium, experimental GPU and broad multiplayer acceptance
   separate until actually tested. Existing format/platform limits remain intentional unless
   a concrete consumer requirement justifies expanding them

No merge, tag, GitHub Release or CurseForge upload is part of this development handoff.
