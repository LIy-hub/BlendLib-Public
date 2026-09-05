# ADR-X8001: X8 platform artifact boundary

Status: **Proposed — isolated X8 implementation candidate, no Gate promotion**

## Context

BlendLib API/core must stay pure Java while every Minecraft version/platform adapter is a separate
artifact. The P8 Fabric 26.2 spike is evidence-only static compatibility work and cannot become a
production artifact merely by renaming it. X8 needs a production-shaped Fabric 26.2 path without
linking the 26.1.2 adapter or altering any existing Gate conclusion.

## Decision

Create `platforms/fabric-26.2` as an independent Gradle artifact with target metadata, common and
client entrypoints, and its own `fabric.mod.json`. Consume only independent pure
`blendlib-api`/`blendlib-core` artifacts through explicit composite substitution and Loom `include`
dependencies; do not copy their sources into the platform JAR, require an ambient mods-directory
classpath for them, or declare a 26.1.2 adapter dependency. The client runtime owns
controlled experimental `PlatformAdapter` installation, exact close receipt, public resource reload registration, strict
descriptor/GLB preparation, immutable generation/snapshot lifecycle, standard collector submit,
diagnostic fallback, and ordinary entity/block-entity/item translation. A successful ordinary-host
registration installs one real public Fabric path: `EntityRendererRegistry`,
`BlockEntityRendererRegistry`, or `ModelLoadingPlugin` plus a special-item renderer. The private
installation retains the full typed `HostRegistrationSpec` and evaluates its source exactly as
`animationFor(host())`, never by casting the token to a live entity/block entity. Extraction gives
each live entity/block entity an isolated bounded weak-reference controller, uses a transient
`Item.STATELESS` LOOP controller for items, and freezes a generation-bound rigid pose palette.
Submit consumes only that palette plus its pinned snapshot/frame; it performs no source callback,
advance, parsing, discovery, or I/O. Request/clip failure produces a named diagnostic and strict
rest-pose fallback. Its callback holds a generation-pinned snapshot only through standard
collector submit and exact close.

The X4 carrier seam records only immutable host-binding/snapshot data and imports no X4 package;
it is optional and not the Fabric 26.2 production dispatcher. The exact client receipt is retained
by the client lifecycle owner and public client stopping attempts close. Close first rejects new
snapshots by retiring the coordinator, then exact-uninstalls/drains dispatcher state; failures stay
retryable in `closing` and terminal state is published only after cleanup succeeds. A snapshot or
dispatcher cleanup failure stays in its exact owner inventory and is removed only after a later
successful release; completed cleanup phases are not repeated.
`blendlib-datagen` is root-included as a pure-Java `:blendlib-api` dependent subproject, while
`platforms/neoforge-26.2` remains a separate artifact root. The Fabric close receipt calls
`PlatformAdapterControl.uninstallIfSame` with the exact installed adapter object, so a stale
receipt cannot remove a newer same-ID replacement; ordinary `uninstall()` behavior is unchanged.
The local candidate aggregate declares separate artifact/source/Javadoc inventory and SHA tasks,
but is outside `buildRelease`, remote publication, and any release decision.

## Consequences

- No cross-version or cross-loader universal runtime JAR is introduced.
- Minecraft `Identifier` stays in Fabric platform source; API/core keep pure canonical keys.
- Common/server code has no client implementation reference; no network or authority path is added.
- Unsupported material/profile work selects a named fallback, never a partial renderer.
- The public dispatcher source received same-reviewer static closure PASS at source `148a2c9`, but
  that is not compile/loader/runtime/visual proof. Exact Fabric API resolution, build/package/
  start/reload/close/visual evidence remain WAITING. This ADR cannot promote P0-P8 or X1-X7
  outcomes.

## Official sources

Accessed 2026-09-05:

- <https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.153.0%2B26.2/>
- <https://raw.githubusercontent.com/FabricMC/fabric-api/26.2/build.gradle>
- <https://raw.githubusercontent.com/FabricMC/fabric-api/26.2/fabric-resource-loader-v1/src/main/java/net/fabricmc/fabric/api/resource/v1/ResourceLoader.java>
