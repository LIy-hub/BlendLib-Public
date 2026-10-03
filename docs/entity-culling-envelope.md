# Explicit whole-assembly entity culling

Vanilla frustum testing happens before BlendLib extracts animation and attachments. The default
ordinary entity renderer uses vanilla bounds unioned with the current root model's prepared
bounds. It cannot discover an attachment which was never extracted. Configure a fixed conservative
envelope when children, animation, or custom snapshot transforms extend beyond those bounds:

```java
BlendEntityRenderer.<MyEntity>builder(context, CHARACTER)
    .skinnedAnimation((entity, request) -> IDLE)
    .attachments((entity, request, sockets) -> captureAttachments(entity, request, sockets))
    .cullingEnvelope(new BlendEntityCullingEnvelope(-4, -2, -3, 4, 5, 3))
    .build();
```

The option is also available with `staticRestPose()` and custom `snapshotFactory(...)` paths.
Use `com.liy.blendlib.fabric.client.entity.BlendEntityCullingEnvelope`.

## Coordinate and transform contract

- Endpoints are **Minecraft blocks relative to the entity origin, before render-root rotation**.
  They are not raw GLB units, socket-local coordinates, or world coordinates.
- First account for each model's units-to-blocks conversion, authored hierarchy, final animation
  pose, socket offset, and every attachment scale. Include every allowed configuration and pose.
- BlendLib uses the maximum corner distance from the origin to enclose the configured box in a
  rotation-invariant cube. Ordinary interpolated yaw and arbitrary selected pitch/roll therefore
  cannot shrink this envelope. It does not call the root-rotation selector during culling.
- The cube is translated by the entity's current world position, then unioned with vanilla bounds
  and the current-generation root envelope. Distinct instances use their own world positions.
- Custom snapshot translations/scales or extra consumer transforms must be covered by the
  configured envelope. This API does not inspect custom snapshots, change model units, expand
  vanilla distance eligibility, collision boxes or tracking range, or force hidden geometry visible.
  Vanilla's normal frustum padding remains in effect.

## Lifecycle and malformed inputs

The immutable value is captured when `build()` creates a renderer. Later builder changes cannot
change an already built renderer. No entity callbacks, animation sampling, graph traversal,
resource loading or submit-time lookup are added. The ordinary prepared root handle lookup still
runs during culling so reload changes to root bounds are observed immediately.

The configured envelope survives entity unload, reconnect and resource reload with its renderer;
it stores neither entity identities nor generation state. A resource pack that increases the
assembly's extent needs a larger consumer configuration or renderer reconstruction. This is an
explicit declaration, not automatic child-bounds aggregation.

Null configuration is rejected by the builder. Non-finite or reversed endpoints and an
unrepresentable origin-centered radius fail immediately with `IllegalArgumentException`.
If invalid world coordinates or translation overflow prevent a finite box, the optional envelope
falls back to the ordinary finite union (or the existing zero-origin finite fallback). It never
injects NaN/infinity or disables culling. Such malformed coordinates cannot guarantee visibility.
Very large valid envelopes are allowed but reduce culling efficiency; choose a justified bound.

Omitting the option preserves the existing renderer behavior and public method descriptors.
Existing constructor descriptors remain present. The optional runnable example's extended mode
exercises real children beyond the root envelope; see [showcase instructions](../versions/modern/showcase/README.md).
Headless verification does not establish native graphics or frustum-edge visual acceptance.
