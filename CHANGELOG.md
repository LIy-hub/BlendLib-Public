# Changelog

## Unreleased: standard two-bone IK

- Add a stateless ordinary pose component with immutable model-space target/pole snapshots and optional per-invocation diagnostics
- Preserve translations/scales/end rotation and existing X3 provenance; support posed ancestors, bounded reach clamping and deterministic singularity handling
- Reuse existing weighting/masking/lifecycle and add a real opt-in 26.3 mechanical arm with final socket/target verification; native graphics acceptance remains deferred

## Unreleased: named entity and item texture skins

- Register bounded, model-scoped named slot-to-texture definitions at client startup and prepare immutable per-generation catalogs during reload
- Add extraction-only entity and item skin selectors, keeping geometry shared and independent per-instance selection frozen for submission
- Compose selected textures with existing RGB/visibility; keep authored materials and captured diagnostics on invalid selections
- Add opt-in runnable Ember/Frost actors and wands with two original PNGs, public consumer compile coverage and packaged asset/selection verification; graphics acceptance remains deferred

## Unreleased: opt-in item visual events

- Add an optional exact-stack extraction-thread handler with immutable model, animation, generation and descriptor-marker metadata
- Honor explicit item LOOP/ONCE/HOLD crossings, silent control/reload/recovery baselines and bounded one-clip-second catch-up without changing gameplay, networking or submission
- Consume before callback delivery; suppress replay, nested delivery and remaining callbacks after control mutation or retirement
- Add a separately opt-in runnable wand counter, read-only held-item event inspection and packaged descriptor/counter verification; graphics acceptance remains deferred

## Unreleased: explicit assembly culling envelope

- Add an immutable, opt-in entity-local envelope to the ordinary entity renderer builder
- Union conservative rotation-invariant assembly bounds with vanilla and current root bounds before extraction
- Preserve default behavior and old ABI; reject malformed configuration without animation or attachment callbacks during culling
- Exercise real beyond-root children in an optional runnable mode, with transformed-geometry, reload and hidden-subtree checks

## Unreleased: bounded nested entity attachments

- Compose prepared character, weapon and ornament snapshots during extraction; submit consumes a flat immutable list
- Bound aggregate occurrences to 64 and attachment depth to eight; preserve finite same-model children and shared snapshots
- Skip stale-generation child subtrees with captured diagnostics while retaining valid siblings and missing-model placeholders
- Preserve child-owned lighting, material appearance, final socket transforms and existing direct-child APIs
- Reuse bounded topology validation without changing procedural graph publication or retirement; require a conservative pre-extraction culling envelope
- Add real rigid/skinned post-modifier composition, submitted geometry, reload, bounds, ABI and executable consumer coverage

## Unreleased: ordinary entity material appearance

- Add exact authored material-slot RGB tint and visibility selectors to ordinary static and animated entity rendering
- Freeze immutable per-primitive appearance against the extraction frame's exact prepared handle; reuse geometry and animation snapshots
- Preserve authored/missing-model diagnostics and conservative bounds; unknown slots expose a sorted diagnostic and atomically retain authored appearance
- Demonstrate independently colored actors and accessory visibility in the runnable 26.3 example
- Preserve retained API descriptors and add rigid/skinned submission, reload, isolation and snapshot-copy regression coverage


## Unreleased: opt-in layer visual events

- Dispatch descriptor markers from immutable v2 controller traversal with explicit layer provenance
- Suppress stale, replayed, zero-weight and over-budget visual callbacks without changing server gameplay or legacy callbacks
- Add bounded per-actor event counters to the runnable 26.3 example

## Unreleased: frame-local clip-layer weights

- Add immutable dynamic animation-v2 multipliers and entity extraction callbacks without restarting playback
- Capture effective layer weights alongside immutable pose/playhead observations
- Exercise per-actor fades in the opt-in 26.3 cue example; retain existing APIs and network behavior

## Unreleased - weighted procedural poses

- Add dynamic weighted and named-node-masked composition for procedural components and pipelines, retaining zero-weight spring progression and rotation-only validation
- Demonstrate time-varying aim fades in the runnable showcase and executable consumer probe

## Unreleased - item animation reload safety

- Preserve item playback controls when a selected descriptor animation disappears after reload; use explicit unavailable status and safe static/missing-model fallback
- Add immutable last-extraction status alongside historical sample observation and expose it in the opt-in item status command

## 1.0.0-beta.3 - 2026-09-11

- Update all 15 Fabric builds to Loader 0.19.5.
- Update Fabric API to 0.155.3+26.1.2 and 0.160.0+26.2; other targets already use their latest matching API.
- Accept newer matching Fabric dependencies instead of requiring exact Loader/API versions.
- See [release notes](docs/release/beta3-release-notes.md) for installation and verification boundaries.

All notable changes to BlendLib are documented here.

## [1.0.0-beta.1+26.1.2] - 2026-09-06

This Beta update retains the existing public-alpha line while bringing in the complete
X1–X9 extension source history. It adds the expansion API/SPI, animation, procedural,
host-adapter, authoring, material/variant, advanced-rendering, platform/ecosystem, and experimental
profile work documented under `docs/expansion/`.

- The separate 26.2 Fabric candidate, NeoForge bridge, datagen, examples, converter, templates,
  inventories, and SHA wiring remain separately scoped X8 content. They are not embedded in the
  26.1.2 runtime JAR and do not constitute a release, dynamic-validation result, or Gate promotion.
- Restores strict asset validation, receive-time world guards, animated bounds, and merged build
  contracts; isolates the client Mixin package and adopts the official folded-B project branding.
- Ordinary Showcase entity visuals received bounded user acceptance. Full material/item/block-entity
  visuals, two-client synchronization, 20-reload leak checks, Iris/Sodium, and hardware performance
  remain unaccepted. This update does not claim stable API/ABI or aggregate phase completion.
- Phase-only gameplay artifacts have been retired: the X7 client diagnostic literal, the P7
  Showcase scene commands, and all Showcase summonable entity registrations are no longer exposed
  in game. The normal asset, inspection, and diagnostic commands remain available.

## [1.0.0-alpha.1+26.1.2] - 2026-08-04

First public-alpha source and packaging metadata for the dedicated Minecraft 26.1.2 adapter.

### Added

- Strict GLB 2.0 resource, animation, diagnostics, Fabric adapter, and Showcase surfaces.
- Aggregate runtime, sources, Javadoc, local Maven, checksum, and license-inventory build wiring.
- Apache-2.0 licensing for all non-Add-on code, with LICENSE and NOTICE embedded in Java artifacts.
- GPL-3.0-or-later Blender exporter Add-on package with separate license scope.
- GitHub source/security/contribution metadata and CurseForge release-page copy.

### Alpha notice

- This is an early test version. APIs and behavior may change before a stable release.
- Do not use it in critical production environments.
- Only the declared Minecraft 26.1.2, Fabric, and Java environment is supported.

No public tag, upload, or publication is asserted by this changelog entry.

## Unreleased: item material appearance

- Add opt-in exact-stack authored material-slot RGB/visibility selection using immutable shared render snapshots
- Keep item playback/status and weak identity/LRU unchanged; expose extraction-time unknown-slot diagnostics
- Add opt-in two-wand authoring example and packaged contract verification; native graphics acceptance remains deferred
