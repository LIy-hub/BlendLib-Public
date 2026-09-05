# BlendLib v1 Contract Baseline

Status: frozen for P0 implementation except for later user-authorized ADRs. Changes require an ADR proposal when they alter an accepted architecture, format, API, version, or acceptance criterion.

## Identity

| Field | Frozen value |
|---|---|
| Mod ID | blendlib |
| Java package root | com.liy.blendlib |
| Local Maven coordinate placeholder | com.liy.blendlib:blendlib-fabric |
| Initial adapter target | Minecraft 26.1.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.154.2+26.1.2 |
| Java | 25 |
| Implementation branch | Liy/blendlib-v1 |

The Maven coordinate is a local build placeholder only. It does not imply repository publication, ownership verification, or a public distribution commitment.

## Version rules

BlendLib has three independent version dimensions:

1. Asset schema uses integer format_version; v1 is 1.
2. BlendLib API uses semantic versions.
3. Fabric adapter target is build metadata, for example +26.1.2.

The controlled Experimental provider SPI is a separately versioned fourth compatibility axis rather than a change to those frozen v1 dimensions. Its current host contract is `1.1.0`; historical `1.0.0` capability metadata remains non-stable and does not alter the v1 schema, API SemVer, adapter target, descriptor behavior, or stable error-code contract.

The P7 development and P8 RC strings, including
`1.0.0-rc.1+26.1.2`, are historical candidate evidence only. The current Alpha
identity is `1.0.0-alpha.1+26.1.2`; local `publishPublicAlpha` consumer
coordinates use that Alpha version. This records an existing version decision and
does not assert that a JAR was built or published.

`1.0.0` is not produced or represented as released by this project until the
required release checks pass and the user separately authorizes publication.

## Compatibility and runtime boundary

- Schema v1 is intended to remain stable across the 26.1.2 adapter and a future 26.2 adapter.
- 26.1.2 and 26.2 use distinct runtime JARs and may use different Minecraft rendering implementations.
- Core fixtures and public-API compile fixtures are shared across adapters; adapter rendering code is not.
- The runtime supports the strict GLB v1 archive only. .blend, FBX, OBJ, external .gltf + .bin, and remote model downloads are outside the runtime contract.

## Repository and scoped Blender Add-on license

ADR-009 records the user's explicit, directory-limited authorization for
GPL-3.0-or-later on `blender-addon/` only. That scope includes the Blender
exporter source, manifest, scripts, README, and `blender-addon/LICENSE`; it
does not license or change the legal status of any non-addon component.

The existing repository-root `LICENSE` is Apache License 2.0 and the root
`NOTICE` records `Copyright 2026 LIy-hub`. Accordingly, `blendlib-api`,
`blendlib-core`, Fabric adapters, Showcase, test assets, runtime artifacts,
root project files, and other non-Add-on BlendLib code/resources are in the
existing Apache-2.0 repository scope; they are not relicensed as GPL by
ADR-009.

Earlier `LICENSE-PENDING` and `LicenseRef-PENDING` statements describe the
pre-Alpha/X8 local-candidate history and are superseded as current repository
license descriptions. This existing repository decision does not itself settle
third-party dependency or asset attribution, redistribution, notice, or
publication requirements.

## Deferred user decisions

The following are deliberately unresolved release/legal decisions, not hidden implementation work:

- final public product name;
- whether source is made public;
- publication channels and publication timing.

No remote publication, public tag, or production deployment is authorized by
this baseline.
