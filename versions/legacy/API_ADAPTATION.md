# Legacy API and item lifecycle audit

The 203 shared API/core Java source files were compared against the generated Java 21 sources and are
text-identical after normalizing line endings. No shared model, parser, animation or synchronization
contract was replaced. These releases preserve the builders, factories, binding registration and snapshot
semantics, while Minecraft-specific signatures follow the actual target game.

| Surface | Legacy adaptation |
| --- | --- |
| Pure `api` and `core` packages | All shared sources retained without semantic substitutions. |
| Minecraft resource identifiers | `Identifier` becomes the target's `ResourceLocation`; namespace/path and serialized string semantics stay unchanged. |
| `BlendRenderer.submit` | Its collector parameter becomes `LegacySubmitNodeCollector`, which emits directly to the native `MultiBufferSource`. Snapshot ownership and submission remain unchanged. |
| Entity renderer | Builders, keys and snapshot factories remain. 1.21.2–1.21.8 use native render-state extraction followed by `render`; 1.21.1 captures immediately at its native entity `render` callback. There is no `CameraRenderState`/native submit-node API in these targets. |
| Block-entity renderer | Builders, keys, snapshot factories and snapshot state remain. The native `render` callback captures world/dimension/position/light/animation data before immediate snapshot submission. 1.21.1–1.21.4 read the camera from the client because their native callback has no camera parameter. |
| Item binding registry | Public `register`, `find`, `bindings` and `installModelLoadingPlugin` methods remain; conflicting registrations still fail and exact repeats remain harmless. |
| Native X7 frame/pipeline internals | Explicitly unavailable. Their 26.1.2 GPU texture/view and final-present contracts do not exist on all legacy versions. They cannot admit a GPU draw or suppress the CPU path. |

Minecraft-specific adapter classes are source adapters for the selected target, not binary replacements
for classes compiled against the 26.1.2 Minecraft ABI. Consumers compile against the matching release.

## Item resource and submission stages

1.21.1–1.21.4 register each distinct explicit base model with Fabric `ModelLoadingPlugin.Context.addModels`
before baking. Fabric's version-matching `FabricBakedModelManager.getModel(ResourceLocation)` reads the
extra baked-model map; an absent model returns vanilla's missing model. Its official 1.21.4 implementation
publishes that map during the model manager's upload/apply stage. BlendLib does not parse or bake model
resources during item drawing.

In 1.21.4, a `ModelManager.getItemModel(ResourceLocation)` injection handles only registered item model IDs.
It resolves the current baked base and constructs vanilla `SpecialModelWrapper`. Vanilla's `update` captures
the argument in `ItemStackRenderState`; its layer later applies that base's display transform, left-hand
handling and centering translation before calling the special renderer. Each future lookup obtains the
current generation, while an already extracted state retains the base and immutable BlendLib render handle
it captured. No baked model cache survives a resource reload inside the binding registry.

The two 1.21.4 `LegacyItemModelTest` tests check current-generation resolution, unregistered-item passthrough,
old-state retention, and the real native special layer's left/right-hand transforms and pose restoration.
They use actual Minecraft model/transform interfaces; they do not claim a rendered image or resource-pack
visual acceptance.

In 1.21.1–1.21.3, the pinned `ItemRenderer.render` injection runs before vanilla modifies the pose. It resolves
the explicit already baked base, captures the BlendLib argument, applies vanilla's base display transform
and centering, submits the snapshot, and restores the pose in `finally`. The actual named 1.21.3 bytecode
confirms `renderStatic` calls this exact eight-parameter method. JAR annotation remapping verifies the
mandatory target; version-specific packaged client startup results are recorded in `VERIFICATION.md`.

1.21.5–1.21.8 use Fabric's public item-before-bake hook and native `SpecialModelWrapper.Unbaked`. The private
special renderer's method name/signature follows each target's rendering API; the public binding semantics
are shared.
