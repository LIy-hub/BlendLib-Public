# Per-stack item material appearance

Register an optional `BlendLibItemMaterialAppearance` with
`BlendLibItemModelBindings.register(binding, appearance)` before item-model bake. The existing
three-argument `BlendLibItemBinding` and ordinary registration remain unchanged. Animated items
can then call `BlendLibItemAnimations.register(binding, defaultAnimation)` as before; this keeps
the configured appearance selector. Repeating the same callback object is harmless; a different
non-null callback or binding for that item is rejected atomically. There is no unregister or
replacement API. A renderer keeps its bake-time configuration until the next bake/reload.

The callback's `select(ItemStack)` runs once on the client extraction thread for each item
extraction. Return a map from exact authored material-slot names to `MaterialSlotAppearance`
(RGB multiplier and visibility). Empty selection keeps authored appearance. The map is copied,
resolved against the exact prepared handle and generation, and stored in the immutable snapshot.
No stack, callback, resource lookup, animation sampling or slot-name resolution occurs during
submission. The same existing rigid/skinned submission machinery handles entities and items;
geometry is neither copied nor recolored in place.

The optional default `captured(ItemStack, ModelRenderSnapshot)` observer receives the completed
immutable snapshot immediately during extraction. Inspect `unknownMaterialSlots()` here to
report resource-pack incompatibilities independently of historical animation observation or
extraction status. Unknown names are sorted, and any unknown name causes the whole selection to
fall back to authored appearance. Missing-model diagnostic handles skip both callbacks and keep
their diagnostic appearance. Null maps/keys/values, blank names and invalid RGB values are
programming errors, consistent with the entity API; callback exceptions propagate.

Treat supplied stacks and snapshots as read-only. Do not retain stacks in callbacks, mutate
playback, acquire controllers, or perform resource I/O. Selectors are retained for the binding
lifetime. Diagnostics observers should copy only the small values needed, not retain render
snapshots across reloads. Stack copies are separate objects; identical metadata can intentionally
produce identical selections. Changing ordinary name/components can change appearance at the
next extraction without resetting playback. Vanilla component synchronization stays the host's
responsibility; BlendLib adds no persistent identity, components, protocol or network state.

This API does not receive an item display context: one frozen argument can be submitted in
multiple contexts without recomputing appearance. Vanilla display transforms remain outside the
snapshot. Lighting/overlay are applied at submission; authored tint, whole-model tint, alpha,
root transform, palettes and conservative bounds retain their existing behavior. Hidden
accessories do not shrink conservative culling bounds. Textures, alpha controls and emissive
changes are intentionally excluded. Existing weak identity/LRU and read-only animation
observation APIs are unchanged.

See the opt-in `itemAppearance` wand example in `versions/modern/showcase/README.md`. Packaged
checks validate source/asset/capture contracts; native graphics acceptance remains deferred.
