# ADR-015: P5 Active-Scene and Animation Runtime Semantics

Status: Accepted
Accepted: 2026-07-29 by the local project owner

## Purpose and status

This ADR is **Accepted**. It deliberately separates three completed,
source-grounded integrity repairs from the two accepted runtime semantics that
canonical P5 asset binding must implement.

It does not change the v1 descriptor schema, strict GLB profile, public API,
Minecraft/Fabric version baseline, or stable diagnostic-code allocation. It
does not approve P3, P4, or P5 Gate PASS.

## Completed integrity repairs (not proposed policy)

The following are already implemented and tested. They are consequences of
the approved strict profile or preservation of data the loader has already
validated; they do not require a new runtime-policy decision.

1. **Strict glTF animation validity.** The P3 loader rejects both duplicate
   `(node, path)` targets in one animation and a channel targeting a source
   node declared with `matrix`, using the existing `ANIM-007` diagnostic. The
   glTF 2.0 specification forbids both inputs. This is strict-profile
   conformance, not an ordering or pose-selection choice.
2. **Default-scene-root retention.** Immutable `ModelAsset` now preserves the
   exact ordered root-node indices already validated from the selected default
   glTF scene. Retention alone does not decide which nodes a P5 palette treats
   as active and does not reject any scene-external joint or skin.
3. **Undeclared `next` integrity rejection.** After the combined
   descriptor/GLB load has decoded clips, it rejects a descriptor state whose
   `next` resource ID is absent from that descriptor's state map with the
   existing `DESC-002` diagnostic. This moves an otherwise generic controller
   construction failure to the resource-load boundary; it does not establish
   an animation-event range policy.

Local evidence:

- `blendlib-core/src/main/java/com/liy/blendlib/core/loader/ModelAssetLoader.java`
- `blendlib-core/src/test/java/com/liy/blendlib/core/loader/StrictLoaderProductionSafetyTest.java`
- `blendlib-core/src/test/java/com/liy/blendlib/core/loader/ModelAssetLoaderTest.java`
- `docs/evidence/P3-implementation.md`
- `docs/evidence/P5-implementation.md`

## Accepted runtime decisions

### A. Canonical active hierarchy and scene-external dependencies

**Accepted decision:**

- The retained selected default-scene roots, and only their reachable
  descendants, define the canonical active hierarchy for node palettes,
  sockets, and skin palettes.
- If an animation target joint or a skin required by an active primitive lies
  outside that hierarchy, strict combined loading rejects the asset. It does
  not silently promote a structural parentless node to an active root.

The approved documents did not previously state whether scene-external
animated joints or required skins are valid runtime dependencies. This accepted
rule resolves that ambiguity and gives canonical controller, node-palette,
skin-palette, and socket code one deterministic hierarchy without an implicit
fallback. The eventual strict rejection must use an existing or subsequently
approved stable diagnostic assignment; this ADR does not allocate one.

### B. Event times beyond the referenced clip duration

**Accepted decision:**

- An event whose `time_seconds` is greater than its referenced decoded clip
  duration is a load error.
- There is no silent clamp, wrap, or ignore behavior for such an event.

The v1 design declares presentation-only animation events but did not define
their relationship to a clip's decoded duration. This accepted rule resolves
that ambiguity: combined loading validates event times before a canonical
controller is constructed, and an asset-bound runtime must not clamp, wrap, or
silently ignore an out-of-range event. This ADR does not allocate a new
diagnostic code for the rejection.

## Consequences of acceptance

Pure, synthetic controller, pose, palette, CPU-skinning, cache, and lifecycle
tests remain valid. Canonical controller/palette/socket/snapshot integration,
combined-load validation, and the Showcase idle/walk/attack binding are now
authorized to implement these decisions.

This ADR does not authorize a format change, unapproved public-API change, a
new diagnostic-code assignment, P3/P4/P5 Gate PASS, release, push,
publication, or production deployment.
