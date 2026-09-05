# X8 platform implementation candidate

Status: **the predecessor independent static-review FAIL is preserved; the same independent
reviewer issued static PASS (0C/0H/0M/0L) for source HEAD `148a2c9`, with no Gate promotion**

This document records an isolated X8 implementation plus shared local-candidate wiring. It does
**not** alter X1-X7, P0-P8, or the dependency ledger. The final independent static review passed
for the exact source identity recorded in [static-review-closure.md](static-review-closure.md), but
X8 remains unaccepted until upstream gates, authorized build/start evidence, and user visual
acceptance exist. No build, compile, check, test, client, server, benchmark, or visual run was
performed for this implementation.

Access date for external sources: **2026-09-05**.

## Independent artifacts

| Project | Artifact identity | Target | State |
| --- | --- | --- | --- |
| `platforms/fabric-26.2` | `com.liy.blendlib:blendlib-fabric-26.2:1.0.0-x8-candidate+26.2` | Fabric / Minecraft 26.2 / Java 25 | production candidate; unverified |
| `platforms/neoforge-26.2` | `com.liy.blendlib:blendlib-neoforge-26.2-waiting:1.0.0-x8-waiting+26.2` | NeoForge / Minecraft 26.2 / Java 25 | **WAITING official binding** |
| `blendlib-datagen` | `com.liy.blendlib:blendlib-datagen:1.0.0-x8-candidate` | pure Java 25 | candidate; unverified |

Fabric and NeoForge are independently rooted Gradle projects with their own artifact name and
target manifest. Their standalone settings use explicit composite substitution for the root
`blendlib-api` and `blendlib-core` artifacts instead of copying either source tree into a platform
JAR. Fabric's standalone build also declares Loom `include` dependencies for those exact two
artifacts, so a future remapped Fabric JAR has an explicit nested runtime closure rather than
requiring a mods-directory classpath coincidence. `blendlib-datagen` is instead a root-included pure-Java subproject with an explicit
`blendlib-api` project dependency, so its JAR does not copy API source classes. None depends on the
26.1.2 adapter or the old `spikes/fabric-26.2` artifact. This source implementation begins at
`8df1b90f9ebca03de6a1b78bb0b184e6529aaa5e`, which is provenance only, not an artifact checksum.
Because no JAR was built, all final release SHA-256 values are **NOT COMPUTED**.

## Fabric 26.2 production candidate

`platforms/fabric-26.2` is a new independent artifact, not a renamed P8 spike.

- Its mod id is `blendlib_fabric_262`; it has independent common and client entrypoints. The common
  entrypoint is deliberately free of client/render references, so no client class is reached from
  the common/server side.
- `Fabric262ClientRuntime` installs the controlled experimental `PlatformAdapter`, returns an opaque
  epoch/identity `Fabric262InstallationReceipt`, and the client entrypoint retains that exact object
  for public `CLIENT_STOPPING`. Close first retires the coordinator, then uses
  `PlatformAdapterControl.uninstallIfSame` with the exact adapter object; the resulting adapter close
  drains dispatcher-held generation pins. A same-ID replacement is therefore retained rather than
  being removed by a stale receipt. A failed cleanup stays in an exact retryable `closing` state;
  only a fully successful retire/drain/uninstall sequence publishes terminal/closed state. Ordinary
  `uninstall()` semantics are unchanged and remain unavailable to normal consumers. Fabric's public
  resource API has no listener removal, so a successfully closed runtime is terminal rather than
  pretending it can restart.
- The runtime registers a public `ResourceManagerReloadListener` through Fabric `ResourceLoader` for
  `PackType.CLIENT_RESOURCES`. `Fabric262MinecraftResourceAccess` deterministically discovers
  `assets/<namespace>/blend_models/*.json`; it converts `Identifier` only at the platform boundary
  and supplies immutable bytes to the pure strict descriptor/GLB loader.
- `Fabric262ResourceReloadCoordinator` separates reload preparation from atomic generation apply,
  rejects stale plans, and retires the prior public generation before publication. Published handles
  are copied/immutable, and `Fabric262RenderSnapshot` pins exactly one generation until close.
  Submit accepts only a prepared snapshot, frame values, `PoseStack`, and
  `SubmitNodeCollector`; it has no I/O, JSON, GLB parsing, resource/provider discovery, reflection,
  or raw OpenGL.
- The implemented-in-code standard path currently accepts strict `rigid_v1` assets with opaque,
  single-sided, non-emissive material intent. Unsupported rendering profiles/materials become a
  named diagnostic-backed fallback handle, not a partial renderer. `Fabric262PreparedModelHandle`
  retains each primitive's `nodeIndex`, a model-key/generation-bound immutable node world-transform
  palette, and `1 / units_per_block`; every primitive applies its own frozen transform and unit
  conversion before standard collector emission.
- `Fabric262HostRegistrationTranslator` verifies ordinary `EntityType`, `BlockEntityType`, and
  `Item` tokens and reduces their native identities to semantic host bindings.
  `Fabric262HostRenderDispatcher` commits a binding only after it calls the corresponding public
  native API: `EntityRendererRegistry`, `BlockEntityRendererRegistry`, or the public
  `ModelLoadingPlugin`/special-item-renderer hook. Entity and block-entity extraction acquire a
  generation-pinned snapshot, evaluate the retained typed `HostRegistrationSpec` source exactly as
  `animationFor(host())`, and advance an actual-host-private controller before freezing a
  generation-bound rigid pose palette. Entity/block-entity instances are weakly retained and
  bounded; items use a transient `Item.STATELESS` LOOP sample and retain no stack/world state.
  Submit callbacks pass only the raw snapshot, frame, and frozen palette to
  `Fabric262RenderSubmitter`, then release in `finally`; the item special renderer follows the
  same snapshot-to-submit-to-close path. Request failures or unknown clips produce a bounded named
  diagnostic and strict rest-pose fallback rather than fake success. The optional
  `Fabric262HostRenderCarrier` remains a no-X4-dependency carrier and is not the production
  dispatcher. Fabric exposes no general registry unregistration, so dispatcher close makes
  registered callbacks inert, preserves any failed exact lease in its owner inventory for retry,
  removes item bindings only after draining succeeds, and clears local binding metadata last.

### Fabric WAITING boundaries

1. No Java/Gradle compilation, resource callback delivery, JAR packaging, game start, or visual
   behavior has evidence under this task's static-only rule.
2. Public entity, block-entity, and item dispatcher source is now present and intentionally does
   not reuse X4's 26.1.2 types. Exact Fabric API signature resolution, compilation, callback
   delivery, loader startup, reload behavior, and visual acceptance remain WAITING.
3. `skinned_v1`, non-opaque material paths, custom pass/shader work, fallback texture availability,
   and visual acceptance remain WAITING. Rigid-animation source evaluation/controller advancement/
   frozen-pose construction is implemented source only; its API compilation, callback delivery,
   runtime behavior, and visual result remain WAITING. No internal API or raw OpenGL bypass was
   introduced.
4. No network or authoritative gameplay logic was added. Visual events remain non-authoritative.

### Official Fabric source record

| Official source | Exact version or branch | Purpose |
| --- | --- | --- |
| [Fabric Maven artifact](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.153.0%2B26.2/) | `0.153.0+26.2` | published target Fabric API coordinate |
| [Fabric API build](https://raw.githubusercontent.com/FabricMC/fabric-api/26.2/build.gradle) | `26.2`, Loom `1.16.2`, Java 25 | standalone candidate build baseline |
| [ResourceLoader](https://raw.githubusercontent.com/FabricMC/fabric-api/26.2/fabric-resource-loader-v1/src/main/java/net/fabricmc/fabric/api/resource/v1/ResourceLoader.java) | `26.2` | public resource listener registration |
| [SimpleReloadListener](https://raw.githubusercontent.com/FabricMC/fabric-api/26.2/fabric-resource-loader-v1/src/main/java/net/fabricmc/fabric/api/resource/v1/reloader/SimpleReloadListener.java) | `26.2` | public prepare/apply lifecycle model |
| [Entity renderer registry](https://raw.githubusercontent.com/FabricMC/fabric-api/26.2/fabric-rendering-v1/src/client/java/net/fabricmc/fabric/api/client/rendering/v1/EntityRendererRegistry.java) and [block-entity registry](https://raw.githubusercontent.com/FabricMC/fabric-api/26.2/fabric-rendering-v1/src/client/java/net/fabricmc/fabric/api/client/rendering/v1/BlockEntityRendererRegistry.java) | `26.2` source | public entity/block-entity registration signatures used by the dispatcher; frozen-coordinate compilation remains WAITING |
| [ModelLoadingPlugin](https://raw.githubusercontent.com/FabricMC/fabric-api/26.2/fabric-model-loading-api-v1/src/client/java/net/fabricmc/fabric/api/client/model/loading/v1/ModelLoadingPlugin.java) and [ClientLifecycleEvents](https://raw.githubusercontent.com/FabricMC/fabric-api/26.2/fabric-lifecycle-events-v1/src/client/java/net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientLifecycleEvents.java) | `26.2` source | public item before-bake and client-stopping hooks; frozen-coordinate compilation remains WAITING |

All sources above were accessed on 2026-09-05. The old spike's `>=0.19.3` loader baseline remains a
metadata lower bound only and must be re-confirmed when compilation is authorized.

## NeoForge 26.2 WAITING bridge

No production NeoForge binding was fabricated. On the access date, official getting-started material
was documented for 26.1, while the official 26.2 migration primer described vanilla migration but
did not establish the exact ModDevGradle coordinate, loader metadata range, mappings, event-bus
constructor, client reload, and client renderer API set needed to make a truthful 26.2 runtime.

- `platforms/neoforge-26.2` declares a standalone pure-Java bridge with no Fabric or guessed
  NeoForge dependency; its actual dependency resolution and build evidence are WAITING.
- `NeoForge262PlatformBridge` implements strict descriptor/GLB preparation, monotonic immutable
  generation apply that rejects stale generations, ready-or-no-render fallback selection, semantic
  host identity resolution, and diagnostics. Each prepared generation carries a private bridge
  issuer token: only its owning bridge can create or single-use apply it, every asset must retain
  the same generation, and fabricated/foreign or `Long.MAX_VALUE` plans are rejected.
  It retains no path, resource manager, native identifier, or renderer object.
- `NeoForge262EntrypointSkeleton` is intentionally not loader-annotated.
  `META-INF/neoforge.mods.toml.template` is intentionally not `neoforge.mods.toml`; this artifact
  must not be mistaken for a loadable NeoForge mod.
- The bridge imports neither Fabric nor GeckoLib. Unknown/required future capabilities remain
  unavailable and fail closed; there is no synthetic native renderer fallback.

Official source record, accessed 2026-09-05:

- [NeoForge getting started](https://docs.neoforged.net/docs/gettingstarted/)
- [Official NeoForged 26.2 migration primer](https://github.com/neoforged/.github/blob/main/primers/26.2/index.md)

The future replacement is explicit: add verified official 26.2 ModDevGradle/dependencies, replace
the metadata template with genuine metadata, bind the bridge source to public client reload APIs,
reduce native hosts via public registries, and connect ready selections to a standard public
renderer. That work needs compile/start/visual evidence and a revision to this document/ADR-X8002.

## Pure-Java datagen

`blendlib-datagen` exposes `BlendLibDatagen.builder()` and `BlendLibDatagenBuilder`. It emits:

- strict v1 descriptor JSON in `assets/<namespace>/blend_models/<path>.json`;
- materials, animation graphs/states/events, and sockets within the frozen descriptor schema;
- deterministic variants in `blendlib_variants` and capabilities in `blendlib_capabilities` as
  experimental sidecars, never illegal descriptor fields;
- a `pack.mcmeta` skeleton with an explicit owner-selected pack format and description.

The tool sorts canonical keys, emits UTF-8/LF text, normalizes every target under one supplied
root, and rejects traversal. Each file uses a staged `ATOMIC_MOVE`; a filesystem without atomic
move support fails with `BLENDLIB-DATAGEN-004` instead of silently writing non-atomically. It reads
no `.blend`, FBX, or OBJ; imports no platform type; and is not a runtime submit-path dependency.
Its immutable request/spec construction enforces the frozen descriptor ceilings: at most 256
material slots, 512 sockets, 256 animation states, 4,096 events per state, and 16,384 events per
descriptor. Before its first atomic write it also checks descriptor/output cardinality, each UTF-8
payload, and cumulative UTF-8 output bytes, so datagen cannot publish a strict-v1 payload beyond
the declared authoring limits.

## Shared integration now wired

| Integrated boundary | Shared file(s) | Current fact and remaining evidence |
| --- | --- | --- |
| Root pure-Java datagen inclusion | root `settings.gradle.kts`; `blendlib-datagen` | Root includes the module; it depends on `:blendlib-api` rather than embedding API sources. Build/Javadoc output is WAITING. |
| Standalone API/core consumption | `platforms/fabric-26.2` and `platforms/neoforge-26.2` settings/build files | Both standalone artifacts consume root API/core through explicit composite substitution, not copied source trees or a 26.1.2 runtime JAR; Fabric additionally declares Loom `include` for both exact pure artifacts so its future remapped JAR has a static nested dependency closure. Dependency resolution/package evidence is WAITING. |
| Local-only aggregate and inventory | root `build.gradle.kts`, root `gradle.properties` | `x8AssembleLocalCandidate` declares separate root/datagen, Fabric 26.2, NeoForge bridge, examples/providers, converter/template ZIP, exact inventory and SHA tasks. It is not attached to `buildRelease`, does not publish remotely, and has not run. |
| Artifact identity and metadata | target build files/metadata | Fabric 26.2 and NeoForge bridge retain distinct versions/JAR names; NeoForge metadata is a template, and `LicenseRef-PENDING` is not a license choice. Actual JAR/SHA values are NOT COMPUTED. |
| Detached local-Maven consumers | X8 example/provider Gradle files | Each detached build explicitly depends on the same-checkout `build/local-maven` prerequisite. The candidate ZIP is not a standalone Maven closure. |
| Fabric native host dispatch | `platforms/fabric-26.2` dispatcher/render-state/item files | Entity, block-entity, and item registration-to-public-dispatcher-to-pinned-snapshot-to-extraction-frozen-pose-to-submit source is implemented; typed source tokens are never cast to live hosts, and retryable close retains unfinished exact leases. API compilation, loader/reload, close behavior, and user visual evidence are WAITING. |
| Replace NeoForge WAITING boundary | future NeoForge build/metadata/entrypoint | Still WAITING: official 26.2 coordinates/mappings/APIs plus compile/start/visual evidence. |
| Advance ledgers only if justified | `docs/expansion-progress.md`, `docs/implementation-progress.md` | The reviewed predecessor's FAIL is preserved; the same reviewer closed all nine findings with static PASS at source `148a2c9`. This changes no Gate. |

## Static-only verification

This task used source/contract reading, path inspection, and static diff/boundary checks only. It
ran no test, build, compile, client/server launch, benchmark, or dynamic validation. The same
independent reviewer subsequently accepted reviewed source `148a2c9` by static PASS; this later
documentation bookkeeping is outside that reviewed source identity. Every missing dynamic evidence
item remains WAITING rather than treated as proof.

Related decisions: [ADR-X8001](../adr/ADR-X8001-platform-artifact-boundary.md),
[ADR-X8002](../adr/ADR-X8002-neoforge-waiting-boundary.md), and
[ADR-X8003](../adr/ADR-X8003-datagen-contract.md).
