# Licenses and third-party sources

This document is a source inventory and boundary record, not a new license grant. It does not
choose a license for any repository file, downstream user asset, or external dependency.

## Repository license status

The adopted repository root `LICENSE` is Apache License 2.0 and `NOTICE` records
`Copyright 2026 LIy-hub`. The complete `blender-addon/` directory remains separately
GPL-3.0-or-later under `blender-addon/LICENSE`; that limited GPL scope does not replace the
root Apache-2.0 scope outside the Add-on.

Every X8 file is outside `blender-addon/`. Its repository-local contribution is therefore within
the existing non-Add-on Apache-2.0 scope. This is not a new license grant for downstream assets or
dependencies, and it does not remove their independent attribution, notice, redistribution, or
publication conditions.

The X8 local aggregate remains workspace-local. It prepares detached consumer coordinates through
`publishPublicAlpha` and retains its local-only publication guard; it is not a remote publication
workflow, a redistributable artifact claim, or a standalone Maven dependency closure. Historical
`LICENSE-PENDING`/LicenseRef wording in the pre-alpha X8 candidate is preserved only as historical
review context and is not the current repository license description.

## X8 original contribution inventory

| Path family | Nature | Copied third-party code/assets | License status |
| --- | --- | --- | --- |
| tools/model-converter | Python 3 standard-library offline tool | None | Apache-2.0 repository scope |
| examples/blendlib-ecosystem-example Java/Gradle/metadata | Consumer example source | None | Apache-2.0 repository scope |
| examples/independent-consumer | Minimal consumer source | None | Apache-2.0 repository scope |
| examples/third-party-providers | Pure Java Experimental SPI example source | None | Apache-2.0 repository scope |
| templates/model-pack text/sidecars | Resource-pack layout and documentation | None | Apache-2.0 repository scope |
| docs/expansion/x8 and ADR-X8004/X8005 | Documentation | None | Apache-2.0 repository scope |

The converter references the names Blockbench and GeckoLib only as external input dialect names.
It contains no Blockbench source, GeckoLib source, GeckoLib JAR, GeckoLib class, GeckoLib runtime
loader integration, or third-party conversion library.

## Repository-local binary asset copies

X8 copies already present strict-v1 demonstration assets into consumer/template locations. They
are not represented as new third-party artwork and do not create distribution rights.

| X8 destination | Exact repository-local source | Change |
| --- | --- | --- |
| templates/model-pack/assets/blendlib_template/models3d/starter_rigid.glb | blendlib-showcase/src/main/resources/assets/blendlib_showcase/models3d/fixtures/static_model.glb | Byte-for-byte asset copy |
| templates/model-pack/assets/blendlib_template/textures/blendlib/starter_rigid__staticsurface.png | blendlib-showcase/src/main/resources/assets/blendlib_showcase/textures/blendlib/fixtures_static_model__staticsurface.png | Byte-for-byte asset copy |
| examples/blendlib-ecosystem-example/src/main/resources/assets/blendlib_ecosystem_example/models3d/actors/example_actor.glb | blendlib-showcase/src/main/resources/assets/blendlib_showcase/models3d/showcase_animation/showcase_actor.glb | Byte-for-byte asset copy |
| examples/blendlib-ecosystem-example/src/main/resources/assets/blendlib_ecosystem_example/textures/blendlib/actors/example_actor__surface.png | blendlib-showcase/src/main/resources/assets/blendlib_showcase/textures/blendlib/showcase_animation/showcase_actor__showcaseanimationsurface.png | Byte-for-byte asset copy |
| examples/blendlib-ecosystem-example/src/main/resources/assets/blendlib_ecosystem_example/models3d/items/example_item.glb | blendlib-showcase/src/main/resources/assets/blendlib_showcase/models3d/fixtures/static_model.glb | Byte-for-byte asset copy |
| examples/blendlib-ecosystem-example/src/main/resources/assets/blendlib_ecosystem_example/textures/blendlib/items/example_item__surface.png | blendlib-showcase/src/main/resources/assets/blendlib_showcase/textures/blendlib/fixtures_static_model__staticsurface.png | Byte-for-byte asset copy |

The associated X8 descriptors change only resource identifiers to their local template/example
namespace. Each copied asset remains subject to the same repository-local Apache-2.0 scope as its
listed source; this does not establish rights for unrelated third-party artwork.

## New build-time and development references

The independent example projects declare the following build/development coordinates. This list
records provenance for review; it does not assert a license grant or redistribute any dependency.

| Component | Version/pin in X8 example | Purpose | Included in X8 source tree |
| --- | --- | --- | --- |
| BlendLib Fabric coordinate | com.liy.blendlib:blendlib-fabric:1.0.0-alpha.1+26.1.2 | Consumer compile/runtime coordinate from local Maven | No binary included |
| BlendLib API coordinate | com.liy.blendlib:blendlib-api:1.0.0-alpha.1+26.1.2 | Pure Java SPI example coordinate from local Maven | No binary included |
| Fabric Loom Gradle plugin | 1.15.5 | Standalone consumer build plugin | No binary included |
| Minecraft | com.mojang:minecraft:26.1.2 | Target development coordinate | No binary included |
| Fabric Loader | 0.19.3 | Target loader coordinate | No binary included |
| Fabric API | 0.154.2+26.1.2 | Target development API coordinate | No binary included |
| Python | 3.11 or newer standard library | Converter interpreter/runtime | No vendored library |
| Java | 25 | Example source toolchain | No runtime bundled |

Before a public release, the publisher must independently collect the exact license, notice,
redistribution, and source obligations for every resolved dependency and for the target Minecraft
distribution. X8 does not copy their license text and does not claim their terms.

## Formats and names

| Format/name | How X8 uses it | Code or content copied |
| --- | --- | --- |
| GLB 2.0 / glTF 2.0 | Strict runtime output/container profile | No specification text or implementation copied |
| PNG | External texture format | Only repository-local Showcase PNG copies listed above |
| JSON | Descriptor, sidecar, and authoring input encoding | No external JSON schema/library copied |
| Blockbench | Offline JSON dialect name | No code, asset, or dependency copied |
| GeckoLib | Offline geometry/model/animation dialect name | No code, JAR, class, dependency, or runtime integration copied |
| Fabric/Minecraft names | Build target identifiers | No binary/license text copied by X8 |

## Required source record for downstream assets

A downstream author must keep a complete record before placing an asset into a pack:

| Required field | Reason |
| --- | --- |
| Exact output path and source path/URL | Traceability |
| Named author/rightsholder | Attribution and permission audit |
| License identifier or written permission | Redistribution decision |
| Acquisition date and version | Reproducibility |
| Transformation/conversion details | Derivative-work and quality audit |
| Required notice/attribution text | Distribution compliance |
| Whether source is embedded in a released runtime pack | Runtime boundary audit |

Do not use a blank or generic statement in place of actual rightsholder/license details. Do not add
third-party source code or artwork to a pack merely because a converter accepts a format.

## Distribution gate

No X8 artifact is ready for publication solely on the basis of this inventory. The existing
Apache-2.0 repository decision does not itself settle external dependency/asset terms, required
notices, distribution channel policy, or any X8 build/package/runtime/visual evidence. NeoForge
binding and the remaining X8 validation are **WAITING** and must be recorded separately.
