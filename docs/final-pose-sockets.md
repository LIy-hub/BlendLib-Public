# Final-pose sockets and prepared attachments

The entity renderer's `sockets` callback receives an immutable `BlendEntitySockets` after animation sampling and all configured pose modifiers. Socket values belong to that frame and asset generation; keeping a value does not make it live. Missing models do not call the handler. Unknown socket keys return `Optional.empty()`.

```java
.sockets((entity, request, sockets) -> sockets.socket(HAND).ifPresent(socket -> {
    var position = socket.worldSpace();
    // Client-only effects can use position.x(), y(), z() and rotation().
}))
.attachments((entity, request, sockets) -> sockets.socket(HAND)
    .map(socket -> List.of(BlendEntityAttachment.at(socket, preparedChildSnapshot)))
    .orElseGet(List::of))
```

Prepare or extract the child `ModelRenderSnapshot` inside the attachment provider (or supply an already prepared immutable snapshot). It can have its own model, generation, rigid or skinned palette, lighting, tint and root. Never defer child resolution to render submit. The provider's returned list is copied into the parent snapshot.

## Coordinate conventions

- `modelSpace()` is the final socket TRS in canonical authored model units, including its animated node hierarchy
- `entitySpace()` is `renderRoot × unitsToBlocks × socket`, relative to the entity dispatcher origin
- `worldSpace()` adds the request's interpolated world x/y/z to entity-space position using doubles. It includes the selected render-root orientation; do not apply entity yaw again
- Quaternion components use x/y/z/w order. Scale is positive and uniform, matching the strict asset transform rules

`BlendEntityAttachment.at` copies the socket's entity-relative position and rotation. It inherits authored root/socket scale but removes the parent's model-unit conversion from the scale, because the child backend applies its own model-unit conversion. Thus a model authored with 16 units per block can hold a model authored with 8 units per block without shrinking it a second time. The optional `BlendEntitySocketPose` offset is in socket-oriented blocks and inherits the socket's authored scale. Child root transforms apply after that offset.

Submit reads only captured placement, offset and child snapshot. It does not access the entity, world, socket table, animation controller or resource registry. Attachments follow parent visibility and child visibility. Children beyond the parent's bounds require a sufficiently conservative parent culling envelope; this API does not automatically expand the entity frustum bounds. Callbacks are per extraction, not once per game tick, and should not perform authoritative gameplay damage or hit detection.

This first implementation draws up to 64 direct attachments per snapshot. Nested child attachment
lists are not traversed. Existing `withLighting` and marker-copy operations preserve captured
attachments; no mutable entity or provider is retained by the render snapshot.
