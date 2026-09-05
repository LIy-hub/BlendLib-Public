# ADR-013: P4 Item Special-Model Registration on Minecraft 26.1.2

Status: Accepted
Accepted: 2026-07-29 by the local project owner

## Context

The approved P4 item example uses an item-model JSON special type:

```json
{ "model": { "type": "blendlib:model", "model": "example:models3d/dragon.glb" } }
```

On the fixed Minecraft 26.1.2 Mojang mappings, `SpecialModelRenderer` and
`SpecialModelRenderer.Unbaked` are public, but `SpecialModelRenderers` exposes
only `CODEC` and `bootstrap()`. Its type mapper is private and bootstrap
registers only vanilla types. Fabric API `0.154.2+26.1.2` has no public special
model-renderer type registry. Reflection, private-field access, and Fabric
`impl` hooks are prohibited by the project boundary.

## Evidence

- `SpecialModelRenderers.ID_MAPPER` is private in the local 26.1.2 client JAR.
- Fabric API aggregate/nested modules contain no public `SpecialModel*`
  registration hook.
- A public alternative exists: `ModelLoadingPlugin.register`, then
  `Context.modifyItemModelBeforeBake()` can identify a controlled marker item
  with `BeforeBakeItem.Context.itemId()` and replace its unbaked model with
  `new SpecialModelWrapper.Unbaked(Identifier, Optional<Transformation>,
  SpecialModelRenderer.Unbaked<?>)`. `ItemModel.BakingContext` publicly
  implements `SpecialModelRenderer.BakingContext`.

## Decision

For the 26.1.2 adapter only, replace the unregistrable literal
`"type":"blendlib:model"` item-model JSON contract with a public,
programmatic marker/wrapper adapter:

1. A consumer supplies a valid vanilla marker item model plus an explicit
   BlendLib item binding/key through the public adapter API.
2. Client-only `ModelLoadingPlugin.modifyItemModelBeforeBake` recognizes only
   registered marker items and replaces their unbaked model with a
   `SpecialModelWrapper.Unbaked` using BlendLib's public custom renderer.
3. The strict descriptor/GLB resource contract remains unchanged. No raw GLB,
   custom codec reflection, Fabric implementation package, or 26.2 runtime JAR
   is used.
4. Documentation and the empty-consumer example must describe this 26.1.2
   adapter surface explicitly; they must not claim the literal JSON special
   type is registered.

## Alternatives rejected

- Registering `blendlib:model` into the private vanilla mapper or Fabric impl:
  violates the public-API boundary.
- Copying a 26.2/LiyMod renderer registration path: not verified for 26.1.2.
- Silently accepting the JSON while it remains unregistrable: would make the
  public contract false.

## Consequences

This accepted decision replaces the unregistrable literal JSON item-model
contract on the 26.1.2 adapter. P4 item implementation may now use the public
programmatic marker/wrapper path described above; it must not add private
registration, reflection, or a 26.2 runtime dependency. Entity, block-entity,
reload, generation, missing-model, and static backend work remain independently
feasible, but P4 cannot pass until the accepted item path and its evidence are
implemented.
