# BlendLib legacy Fabric targets

This independent Gradle entrypoint builds the complete public CPU runtime for the eight Minecraft releases
1.21.1 through 1.21.8. It compiles the shared API, core, common synchronization adapter and client runtime
with Java 21, then remaps all Minecraft references to Fabric's intermediary namespace. API/core/common are
inside the outer JAR, so no Java 25 nested dependency can leak into the release.

From the repository root, with Java 25 available for Gradle 9.6 and Java 21 available as the compiler toolchain:

```powershell
./gradlew.bat -p versions/legacy -Pminecraft_version=1.21.8 --no-daemon --max-workers=1 build verifyRuntimeJar
```

The selected version gets its own `build/<minecraft>/` directory. Release JARs and `verification.json` are
under `build/<minecraft>/libs/`. A clean build requires access to the official Mojang, Fabric and Maven Central
repositories. `prefetch_minecraft.py` is an optional download helper; it checks Mojang's declared SHA-1 and
byte length before placing immutable client/server inputs into the Gradle cache. Never run that helper for
a target currently being configured by Loom.

If Fabric's TLS transport fails, `prefetch_intermediary.py <minecraft>...` caches exact mapping POM/JAR
files after checking Fabric's SHA-1. The shared `versions/modern/fetch_fabric_maven.py` can likewise
populate this build's `build/fabric-maven/` directory for the exact Fabric API versions. Both caches are
optional; neither changes dependency versions or disables TLS verification.

## Port architecture

`prepareSources` copies the shared sources to a generated directory and applies the reviewed platform
renames in `build.gradle` and `transforms.gradle`. Explicit replacement adapters live under `overrides/`;
`postprocess.gradle` accounts for the older method signatures. The root production sources are unchanged.

- Static, rigid and captured CPU-skinned geometry, opaque/cutout/translucent materials, emissive light,
  missing-model geometry, animation/controller lifecycle, model reload, host/X6 composition, diagnostics,
  entity/block-entity synchronization and item bindings keep the shared implementation.
- The public legacy submission bridge calls the target version's `MultiBufferSource`, `RenderType` and
  `VertexConsumer` APIs synchronously. It restores the caller's pose stack, and does not retain callbacks.
- 1.21.1 captures a snapshot at the legacy entity-render entry and preserves model-aware frustum bounds.
  Later targets use vanilla's entity render-state extraction. Older block-entity render hooks capture the
  same immutable snapshot immediately before submission.
- 1.21.4 onward uses native special item-model renderers. Fabric 1.21.4 lacks the later item-before-bake
  callback, so a version-pinned `ModelManager.getItemModel` Mixin supplies the same native special wrapper
  for registered markers; each lookup resolves the current generation's base model. 1.21.1–1.21.3 uses
  one version-pinned item-render Mixin, only for explicitly registered marker items. Base-model transforms
  are loaded through Fabric's model-loading API; unrelated items return to vanilla immediately.
- Diagnostic socket lines use the target's standard line width; older vertex formats have no per-vertex
  line-width attribute.

Low-level callers holding a `MultiBufferSource` can submit an already captured snapshot with:

```java
BlendLibClientServices.renderer().submit(snapshot, poseStack, new LegacySubmitNodeCollector(buffers));
```

`LegacySubmitNodeCollector` is `com.liy.blendlib.fabric.client.compat.LegacySubmitNodeCollector`. The entity,
block-entity and item adapters install that bridge automatically. The pure `com.liy.blendlib.api` types
and the snapshot's semantic contract are unchanged. See [API adaptation](API_ADAPTATION.md) for the
version-native callback differences and the item-model lifecycle audit.

## GPU boundary

The 26.1.2 baseline's experimental X7 native submission was already disabled by its source-order admission
gate; its production model reload publishes CPU-only generations. Legacy versions explicitly keep that
same complete CPU path. Their endpoint reports `KEEP_CPU_PIPELINE_UNAVAILABLE` before creating a lease,
queue request, GPU allocation or command; it cannot suppress a CPU draw.

The build excludes exactly the following 20 private native prototypes or their private dependencies, rather
than inventing Minecraft classes or advertising GPU support:

`X7Minecraft2612PassOwnerHost`, `X7Minecraft2612SkinnedPassSubmitter`, `X7Minecraft2612SkinnedPipeline`,
`X7Minecraft2612StaticPipeline`, `X7Minecraft2612SubmissionReceipt`, `X7Minecraft2612TargetScope`,
`X7T3cClientGateway`, `X7T3cReloadGateway`, `X7Minecraft2612GpuDevice`, `StaticDirectDrawExecutor`,
`StaticDirectFenceReceipt`, `StaticDirectGenerationResources`, `StaticDirectPipeline`,
`StaticDirectResourceFactory`, `StaticDirectBatch`, `SkinnedTexelGenerationResources`,
`SkinnedTexelResourceFactory`, `SkinnedTexelUploadStaging`, `X7AfterSolidQueueDriver`,
`Minecraft2612FinalPresentMixin`.

Their six X7 GLSL files are also excluded. Vanilla eagerly preprocesses shader resources even without an
active X7 pipeline, and these 26.1.2 files import `sample_lightmap.glsl`, which the older game does not supply.
The runtime verifier rejects leaked X7 shader resources; normal CPU rendering uses vanilla's own shaders.

The existing CPU registry closes through the client stopping lifecycle. The 26.1.2 bytecode-pinned final
present protocol reports unavailable on these targets. It is not rebound to unverified shutdown bytecode.

## Verification boundary

`build` compiles the complete selected production source sets and runs `LegacySubmissionTest`, which uses
the selected Minecraft's real pose transforms, render types and vertex-consumer interface to check triangle
emission, light/normal submission, culling and pose restoration. `verifyRuntimeJar` checks the actual remapped
JAR's metadata, production entrypoints, aggregate packaging, every class major version and SHA-256.

Server readiness, client startup and visual/gameplay acceptance are separate observations. See `VERIFICATION.md`
for version-specific evidence. A successful build or startup does not establish visual acceptance or X7 GPU
support.
