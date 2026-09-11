# Changelog

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
