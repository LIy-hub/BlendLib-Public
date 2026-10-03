# Nested entity attachments: character, weapon, ornament

The standard entity path accepts nested, already-prepared `ModelRenderSnapshot` attachments.
Compose them during extraction. The renderer captures a bounded flat list and submit consumes
only immutable snapshots. This API does not prepare a child's animation or resolve its resources
for you.

## Before composing

- Prepare character, weapon, and ornament through their supported extraction paths for the same
  resource generation. Finish pose modifiers before capturing sockets.
- Retain the character's final hand socket and the weapon's own final ornament-mount socket.
  A weapon socket must come from the exact final pose/root used for the weapon snapshot.
- **Provide a conservative root-model culling envelope covering the entire assembly.** Entity
  culling happens before extraction. Nested capture neither aggregates child bounds nor enlarges
  that envelope. Include all allowed offsets, animation poses, scales, and rotations.

The concrete public-client helper is
[`ExampleNestedEntityAttachments.java`](../examples/nested-entity-attachments/src/main/java/com/liy/blendlib/examples/attachments/ExampleNestedEntityAttachments.java).
Its example socket keys are `example:hand` and `example:ornament_mount`; adapt those keys to your
asset declarations. It builds an ornament offset in socket-oriented blocks, attaches the
ornament to the weapon, and then attaches that decorated weapon to the character. Existing
attachments are preserved. It uses no internal runtime or procedural graph imports.

```java
// During extraction: all five arguments are already captured for this frame.
ModelRenderSnapshot root = ExampleNestedEntityAttachments.compose(
        characterSnapshot, characterFinalSockets,
        weaponSnapshot, weaponFinalSockets,
        ornamentSnapshot);
```

Return that composed root from your custom snapshot factory when using the standard entity
renderer. On the standard animated path, an attachment-provider callback can return a decorated
weapon attachment using the callback's character sockets; prepare and capture the weapon's own
sockets through its existing supported extraction owner first. Do not create a parallel runtime
or re-sample a child from the submit callback.

## Custom-host capture

A custom extraction host may capture the same root directly:

```java
BlendEntityAttachmentComposition composition = BlendEntityAttachmentComposition.capture(root);
// Retain root and composition in immutable render state.
// Observe/report composition.diagnostics() from extraction, outside submit.
```

In the custom host's submit method, with an entity-relative pose stack:

```java
renderer.submit(root, poseStack, collector);
BlendEntityAttachmentSubmitter.submit(composition.attachments(), renderer, poseStack, collector);
```

Submit the captured list only once. The low-level renderer submits a single snapshot; the
standard entity renderer already owns attachment capture/submission. Do not separately submit
attachments again when using it. A custom host must retain the capture result rather than call
`capture(root)` on every submit. No child socket resolution is needed at submit.

## Why the ornament follows the weapon correctly

The child's final socket attachment placement already includes the child's render root.
For a character hand placement `P`, weapon offset `Ow`, weapon socket placement `Q`, and ornament
offset `Oo`, the composed ornament placement is `P × Ow × Q × Oo`. Do not insert another weapon
root before `Q`. The ornament geometry receives its own root and asset-unit conversion once,
through ordinary snapshot submission. Offsets use blocks in the socket's orientation;
`attachmentPlacement()` removes only the socket owner's unit conversion from scale.

Each child keeps its own light, overlay, tint, material appearance, and captured animation.
Hiding the weapon through `RenderVisibility.CULLED` suppresses its ornament too. Hiding only a
weapon material slot does not suppress the ornament. Invisible same-generation descendants are
still validated and count toward the capture budget.

## Limits, reloads, and diagnostics

- At most 64 visited attachment occurrences, excluding the root, and eight edges below the root
- Mounting a shared snapshot twice counts both occurrences and their visited descendants;
  finite same-model nesting is supported and cycles are checked by snapshot identity
- Over-budget/deep graphs and invalid composed transforms throw `IllegalArgumentException`
  during capture; validate configurable assemblies before making them available
- A stale-generation child and its subtree are skipped; a `STALE_GENERATION` diagnostic records
  the model key, expected generation, and actual generation. The stale entry counts toward the
  budget but its descendants are not visited
- A missing-model child retains its usual diagnostic placeholder and gets a `MISSING_MODEL`
  diagnostic. Visibility still applies; capture does not reload or replace that model
- The helper deliberately fails fast for missing required example sockets or socket/snapshot
  generation mismatch. For optional equipment, handle `sockets.socket(key)` being empty in
  extraction and omit that attachment

Keep these generation checks separate from lifecycle ownership: the composition is an immutable
frame handoff and does not grant permission to reuse a retired procedural graph or stale runtime.

## Example status

The isolated helper remains an extraction integration example. The separate opt-in 26.3
[registered runnable actor](../versions/modern/showcase/README.md) now also demonstrates a
real skinned actor → rigid weapon → independently animated skinned ornament using the
existing authored assets. Its guide documents lifecycle, reload and the exact conservative
culling proof.
The isolated source fixture is compiled and exercised by the client test suite; it does not
change the historical ecosystem project's dependency defaults. Use the newly built 26.3 artifact
and matching Minecraft/Fabric settings when incorporating it into a mod. Automated composition checks do not establish native visual or frustum-edge acceptance.
See the [design and verification plan](plans/26.3-nested-entity-attachments.md) for the contract.
