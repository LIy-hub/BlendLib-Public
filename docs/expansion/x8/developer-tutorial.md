# BlendLib developer tutorial

This tutorial is a contract-first guide for a downstream Fabric consumer. It uses only current
public surfaces for ordinary code and explicitly marked Experimental SPI for provider/adapter work.
Where X3, X4, X6, or X7 still lacks accepted shared production wiring, the guide gives a safe
handoff boundary and marks runtime evidence **WAITING** rather than inventing an integration.

## 1. Install the published consumer coordinate

Start with either examples/blendlib-ecosystem-example for a full mod shape or
examples/independent-consumer for the smallest Maven-only consumer. Both are separate Gradle
builds, not root subprojects.

The build needs a local Maven repository property named blendlib_local_maven_repo and one
published coordinate:

    implementation("com.liy.blendlib:blendlib-fabric:1.0.0-rc.1+26.1.2")

Use Java 25 and the exact Minecraft/Fabric versions in that project's gradle.properties. Do not
replace this with a project dependency, implementation package import, shaded copy, GeckoLib
runtime dependency, or a loader-specific class in common/server code.

The example projects include Fabric entrypoint metadata for a main and client source set. Their
dynamic compilation and launch are **WAITING** in X8; perform them only through the repository's
approved integration process.

## 2. Lay out a strict runtime asset

A rigid model key such as example:machines/altar resolves only to:

    assets/example/blend_models/machines/altar.json
    assets/example/models3d/machines/altar.glb
    assets/example/textures/blendlib/machines_altar__surface.png

The descriptor is a UTF-8 JSON object with:

    {
      "format_version": 1,
      "profile": "blendlib:rigid_v1",
      "mesh": "example:models3d/machines/altar.glb",
      "materials": {
        "Surface": {
          "base_color": "example:textures/blendlib/machines_altar__surface.png",
          "mode": "opaque",
          "double_sided": false,
          "emissive": false
        }
      },
      "extensions": {},
      "extensions_used": [],
      "extensions_required": [],
      "units_per_block": 1.0
    }

The GLB must be version 2, contain triangle primitives with POSITION, NORMAL, TEXCOORD_0 and
U16/U32 indices, and use valid node TRS/matrix and allowed LINEAR or STEP animation interpolation.
External PNG is referenced by the descriptor; do not embed it into GLB. Required unknown
extensions fail closed, as do unsupported mesh compression, sparse accessors, morph targets,
multiple UV sets, vertex colors, or cubic-spline animation.

The reusable layout and an actual strict asset are under templates/model-pack. Its sidecar
intents are intentionally separate from the runtime descriptor. Do not add sidecar keys to the
descriptor's extension arrays.

## 3. Author and generate assets

Use the offline converter for Blockbench or GeckoLib JSON input:

    python tools/model-converter/blendlib_model_converter.py --input authoring/model.bbmodel --texture authoring/model.png --out generated-pack --namespace example --model machines/altar

The converter is an authoring tool, not a Gradle task. It is never called by a production client,
server, data generator, reload listener, renderer, submit, animation advance, socket query, or
provider. Read [converter guide](converter-guide.md) for full options and loss behavior.

### Datagen boundary

`blendlib-datagen` is a root-included pure-Java candidate module. Its public
`BlendLibDatagen.builder()` surface emits a frozen strict descriptor plus deterministic sidecars
before runtime; it has no Fabric, Minecraft, NeoForge, authoring-reader, renderer, or provider
discovery dependency. A downstream data generator may emit or copy the same strict descriptor and
external assets into its generated resource output, but it must preserve:

- exact resource IDs and UTF-8 descriptor bytes;
- strict GLB and PNG files as already validated authoring output;
- no authoring format reader, provider discovery, or client class on common/server datagen paths;
- a source/provenance record for every generated asset.

Do not use datagen to reinterpret source animations, resolve a GLB, or dynamically choose a
backend. X8 claims no Fabric 26.1.2/26.2 datagen hook or invocation; module compilation, output
filesystem exercise, and target-platform integration are **WAITING**. Any future platform hook
must retain the same strict runtime contract.

## 4. Build stable semantic registrations

Ordinary consumers create a semantic key and an immutable registration specification:

    BlendModelKey model = BlendModelKey.parse("example:machines/altar");
    BlendAnimationKey idle = BlendAnimationKey.parse("example:idle");
    HostRegistrationSpec<MyHost> specification = BlendLib.entity(myHost)
            .model(model)
            .animation(AnimationRequest.loop(idle))
            .build();

Use the matching BlendLib.blockEntity or BlendLib.item builder for the other host kinds. Item
registrations must use looping animation because stable v1 has no persistent per-ItemStack
identity. Keys, builders, requests, and specifications are pure semantic values: construction
does not load a model, parse JSON, perform I/O, discover a provider, create a snapshot, or touch
graphics state.

The builder register method delegates to the one controlled platform adapter. A normal mod must
not install or uninstall that global adapter. If no accepted version-specific adapter exists,
retain the built specification and let the platform owner decide when it can submit registrations.
The X8 example deliberately demonstrates build-only stable specifications for this reason.

## 5. Publish semantic animation, not render state

Server-authoritative gameplay may choose to emit a semantic animation after it has validated the
gameplay action:

    BlendAnimations.entity(entity).trigger(attackKey, 1.0F, deterministicSeed);
    BlendAnimations.blockEntity(blockEntity).setPersistent(idleKey);

The payload is limited to animation meaning, speed, and seed. It does not contain a GLB,
descriptor, socket transform, render snapshot, material, collision shape, hit decision, or client
controller object. Never let an animation event decide damage, loot, collision, teleport, or
other authoritative gameplay behavior.

Entity and block-entity hosts should retain fixed gameplay dimensions and ordinary persistence.
The example mod shows this separation in ExampleAnimatedEntity and
ExampleAnimatedAltarBlockEntity.

## 6. Register public Fabric client facades

Client-only code may use the public 26.1.2 renderer facade after the client source set begins:

    BlendEntityRenderers.register(entityType, context ->
            BlendEntityRenderer.<MyEntity>builder(context, actorModel)
                    .skinnedAnimation((entity, request) -> idleKey)
                    .skinnedSocketMarker(tipSocket)
                    .onSkinnedVisualEvent((entity, event) -> presentOnly(event))
                    .shadowRadius(0.45F)
                    .build());

The corresponding block-entity path uses BlendBlockEntityRenderers and
BlendBlockEntityRenderer. Marker items use BlendLibItemModelBindings with an ordinary vanilla
base model. These are client-only facades; do not import them from common/server code.

The facade owns extraction-to-snapshot flow. Consumer code must not parse or load a resource in
submit, create a renderer/backend during submit, call raw GL, use private reflection, or resolve a
socket at submit time. CPU/standard public rendering remains the required safe fallback.

## 7. Sockets and visual events

A descriptor may declare a named socket that points at a validated node path. A SocketQuery is a
pure semantic request containing a pinned model-instance identity and a socket ID. Resolving it is
an extraction-stage concern, never a submit-time lookup.

For the standard skinned facade, skinnedSocketMarker selects one presentation marker captured with
the current extraction snapshot. The example actor pairs:

- blendlib_ecosystem_example:tip with the descriptor socket;
- blendlib_ecosystem_example:attack_whoosh with a visual animation event;
- a log-only client callback as a harmless presentation action.

Do not turn a socket location into a server hitbox, attachment authority, world mutation, or
network coordinate. Do not send arbitrary socket matrices across the network.

## 8. X3 presentation event handoff

X3 events are visual cues only. A visual listener may create a particle, sound, or other client
presentation once an accepted client integration path exists. It must neither rerun gameplay
logic nor decide an outcome. The current X8 example uses the public renderer callback to make that
separation readable, but does not claim an X3 production dispatcher/reload integration is
accepted. That platform route is **WAITING**.

## 9. X4 host adapter handoff

Host adapters are explicit Experimental SPI. A version-specific platform bootstrap is responsible
for:

1. constructing its adapter;
2. verifying one controlled global owner;
3. installing it during the appropriate client/platform lifecycle;
4. accepting immutable HostRegistrationSpec values;
5. retiring with the exact lifecycle receipt and, for the Fabric 26.2 candidate, calling
   `PlatformAdapterControl.uninstallIfSame` with the exact installed adapter object. This retains
   a same-ID replacement installed by a newer owner; ordinary `uninstall()` behavior is unchanged.

Normal consumers and common/server entrypoints must not install PlatformAdapterControl. The
examples/third-party-providers/host-adapter package returns only stable RegistrationReceipt data
and has no Fabric/Minecraft/renderer/internal dependency. See
[third-party provider guide](third-party-provider-guide.md).

For a client host that is already owned by that lifecycle, the ecosystem example's
`ExampleX4GuiPreviewHost.create(models, renderer, generationSession, identity)` demonstrates the
public X4 GUI-preview builder: it freezes a model key, scoped identity, and bounded immutable
configuration, but deliberately does not call it from the normal client initializer. The external
owner still performs `freeze`, `prepare`/`extract`, `submit`, `retire`, lease drain, and `close`.

## 10. X6 variants and materials

Variant and material inputs are preparation-time data, not descriptor extensions and not
per-frame discovery. The model template provides separate authoring intent files to illustrate
the boundary. A future accepted X6 owner must:

- validate exact model/generation and bounded rules;
- select deterministic variants before publish;
- map only approved opaque, cutout, or translucent standard routes;
- reject custom shader, additive, and PBR requests where no approved route exists;
- freeze selected material/backend data before submit;
- retain a CPU/standard path fallback.

X6 integration, reload, and renderer wiring remain **WAITING**. Do not claim the sidecar itself
is loaded by strict v1 runtime.

## 11. X7 backend and CPU fallback

RenderBackendProvider is Experimental control-plane metadata. Providers offer a capability ID,
current protocol version, and bounded priority; a request can optionally name a semantic-equivalent
fallback. The provider example declares a public-standard backend and a CPU standard fallback.

The fallback is not permission to substitute a custom shader, raw GL call, or unrelated renderer.
The host proves equivalence before publishing. Provider metadata/lifecycle work cannot occur in
submit, animation advance, or socket query. X7 hardware, performance, and compatibility evidence
remain **WAITING**.

## 12. Reload and diagnostics

Reload is owned by the platform/resource integration layer, not a consumer callback. Consumers
should keep:

- semantic registration and resource identities immutable;
- provider metadata snapshot-safe;
- a prior known-good generation until a complete replacement is accepted;
- diagnostic codes/messages bounded and actionable;
- resource, instance, and immutable snapshot ownership separate.

Required unknown data, malformed paths, unsupported profiles, missing material maps, stale
generations, unknown required capabilities, and invalid provider output must fail closed. An
optional capability can fall back only when a declared fallback is explicitly safe and a platform
owner verifies it. Dynamic reload proof is **WAITING** for X8.

## 13. Package for a consumer

Before publishing a consumer artifact or resource pack:

1. Include only strict GLB, descriptor, external PNG, and ordinary mod/resource metadata.
2. Exclude .blend, FBX, OBJ, Blockbench JSON, GeckoLib JSON, GeckoLib JARs, authoring tool code,
   raw model sources, and generated temporary reports unless intentionally documented outside
   runtime.
3. Confirm every descriptor resource ID and every material name maps exactly to the GLB/PNG set.
4. Keep third-party source/license/attribution data with the distribution.
5. Keep platform/version artifacts separate; do not mix a 26.1.2 Fabric adapter into a 26.2 or
   NeoForge artifact.
6. Record build, static review, runtime, and visual evidence separately. X8 currently records
   only implementation and local static reading; all dynamic proof is **WAITING**.

For provenance and license boundaries, read [licenses and sources](licenses-and-sources.md).
