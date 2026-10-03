# Ordinary entity material appearance

`BlendEntityRendererBuilder.materialAppearance` selects per-instance RGB multipliers and
visibility by **exact authored material-slot name**. It works with ordinary static, animated
rigid, and CPU-skinned entity snapshots; it does not require an Experimental X6 provider.

```java
import com.liy.blendlib.fabric.client.render.MaterialSlotAppearance;
import java.util.Map;

BlendEntityRenderer.<MyEntity>builder(context, MODEL)
        .staticRestPose()
        .materialAppearance((entity, request) -> Map.of(
                "Body", new MaterialSlotAppearance(0xff8844, true),
                "Accessory", new MaterialSlotAppearance(0xffffff, entity.showAccessory())))
        .build();
```

`Body` and `Accessory` above are illustrative names. Use names actually assigned to GLB
primitives and declared in the descriptor's `materials` map. They are case-sensitive strings,
not texture identifiers, node names, wildcards, or numeric primitive indexes. The runnable
example below uses `ShowcaseAnimationSurface` and `ExampleAccessory`.

## Contract

- `rgbTint` is an unsigned 24-bit `0xRRGGBB` value (`0x000000` through `0xffffff`).
  White is the identity multiplier. Black makes RGB black; it does not hide geometry.
  ARGB values, including `0xffffffff`, are invalid for this value type.
- The multiplier composes with existing whole-model/authored tint. It is not a replacement
  texture or a promise of a particular final on-screen color. Alpha is unchanged.
- `visible=false` omits every submitted primitive belonging to that slot. All primitives
  sharing one slot receive the same appearance. Animation, skinning, sockets, attachments,
  gameplay collision and conservative culling bounds are not resized or disabled.
- Unspecified slots stay authored. Return `Map.of()` to leave the complete model unchanged.
  Leaving the builder option unset retains existing behavior.
- The selector runs during extraction around the completed snapshot factory, after the
  static/rigid/skinned animation result is available. Names are resolved against that exact
  prepared handle; submission consumes captured primitive-indexed values and never calls
  the entity or selector. Do not do resource loading, GLB parsing, or rendering in the selector.
- The selection is defensively copied into immutable capture metadata. Snapshot lighting,
  socket and attachment copies preserve the capture. Instances can share one prepared
  geometry handle while retaining independent appearance.
- Unknown names invalidate the **entire selection**, leaving all primitives authored.
  `ModelRenderSnapshot.unknownMaterialSlots()` reports the unknown names, sorted and immutable.
  This avoids partial recoloring after an incompatible resource-pack change. It is a
  per-snapshot extraction diagnostic, not proof of submission or a global event stream.
- Missing-model diagnostic handles ignore selections and keep their error-model appearance.
  Null maps, null entries, blank slot names and invalid RGB values are programming errors.
- Resolve names again for each newly extracted/reloaded handle. Do not cache a render handle
  or primitive ordinal across reloads.

This API changes only ordinary entity material RGB/visibility. It adds no opacity, texture
substitution, emissive/shader control, networking, item API, attachment inheritance, or
material-provider registry. Existing X6 variant selection remains separate.

## Runnable 26.3 example

The opt-in mod in `versions/modern/showcase` uses the same helper in its live renderer and
executable verification: `ExampleMaterialAppearance.forName`. The host's vanilla synchronized
custom name selects one of four appearances, so no new network protocol is needed:

| Custom name | Body RGB multiplier | Side accessory |
| --- | --- | --- |
| `Orange` | `0xff8844` | visible, white multiplier |
| `Orange bare` | `0xff8844` | hidden |
| `Blue` | `0x4488ff` | visible, white multiplier |
| `Blue bare` | `0x4488ff` | hidden |
| absent or any other name | unchanged | authored |

From the repository root with the supported Java 25 toolchain:

```sh
./gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true verifyRunnableExamples
./gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true runRunnableExamplesClient
```

In a test Creative world with commands enabled:

```mcfunction
/summon blendlib_runnable_examples:layered_actor ~-1 ~ ~3 {CustomName:{text:"Orange"}}
/summon blendlib_runnable_examples:layered_actor ~1 ~ ~3 {CustomName:{text:"Blue bare"}}
```

The actors share the prepared `appearance_actor` model but have different body tints. Orange
shows a small skinned badge to its side; Blue bare omits it. The existing gold socket-following
cube is a **separate attachment** and stays visible for both. Layers, timed cues, procedural tip
motion and visual-event counters continue to run. Use `Orange bare` and `Blue` to reverse
accessory visibility without changing body color; summon an unnamed actor for authored colors.
A resource reload should retain each actor's selected appearance after fresh extraction.

The new descriptor and GLB contain two real primitives with those exact slots. The side badge
has its own position accessor and triangles and follows the existing skin. Original actor,
wand and marker assets remain unchanged. `versions/modern/showcase/tools/generate_appearance_actor.py`
reproduces the committed derivative from the repository-local Showcase actor; running Python
is **not** required to build or run the example.

## Verification boundary

`verifyRunnableExamples` checks consumer packaging and strict-loads the actual packaged GLB.
Its executable probe calls the exact live selector, checks both actor colors and both accessory
visibility choices, unchanged/default behavior, immutable maps, real accessory triangles,
conservative asset bounds, exact prepared slot ordering, CPU-skinned snapshot capture and
unknown-name diagnostics. Backend contract tests separately exercise actual rigid/skinned
submission and fallback, so the example does not substitute string matching for render tests.

A passing headless build/probe does not establish native-window or visual GPU acceptance.
Use the manual scene above for that check; no client launch, world creation, server startup or
terms acceptance is implied by running verification. See the showcase README for full install,
entity-layer, item-control and cleanup instructions.
