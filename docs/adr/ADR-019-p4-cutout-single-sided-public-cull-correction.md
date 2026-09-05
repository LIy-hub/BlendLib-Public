# ADR-019: P4 Cutout Single-Sided Public-Cull Correction

Status: Accepted
Accepted: 2026-07-30 by the local project owner
Owner decision: accept the narrow ADR-014 matrix correction; no production mapper or backend change is required.

## Context

ADR-014 is accepted and correctly prohibits using the boolean argument of
`RenderTypes.entityCutout(Identifier, boolean)` as a face-culling switch.
Before this correction, its published 26.1.2 mapping table and the P4
material-matrix fixtures classified `cutout`, `double_sided=false`, and
`cutout_threshold=0.10` as a `BLENDLIB-MAT-004` rejection. That classification
was contradicted by the currently resolved public 26.1.2 API and by the checked P4 implementation:
`RenderTypes.entityCutoutCull(Identifier)` is the ordinary public culling path.

This accepted correction fixes the accepted ADR's stale table/fixture classification.
It does not alter the descriptor format, public API, Minecraft/Fabric/Loader
versions, archive profile, alpha threshold, or P4 acceptance standard.

## Evidence

- The local 26.1.2 client JAR exposes `RenderTypes.entityCutoutCull(Identifier)`
  as a public method. It is distinct from `entityCutout(Identifier, boolean)`;
  the latter boolean still controls outline participation rather than culling.
- `MaterialRenderMapper` selects a supported result for `CUTOUT` with an exact
  `0.10` threshold for either `doubleSided` value, rejecting only a non-exact
  threshold (`CUTOUT_THRESHOLD_UNSUPPORTED`):
  `blendlib-fabric-client/src/client/java/com/liy/blendlib/fabric/client/render/MaterialRenderMapper.java`.
- `Minecraft2612StaticRigidRenderBackend` maps `CUTOUT,false` to
  `RenderTypes.entityCutoutCull`, and maps `CUTOUT,true` to the ordinary
  no-cull `entityCutout` path:
  `blendlib-fabric-client/src/client/java/com/liy/blendlib/fabric/client/render/Minecraft2612StaticRigidRenderBackend.java`.
- `MaterialRenderMapperTest` explicitly verifies both exact-0.10 cutout paths
  as supported, including the `CUTOUT,false` cull backend selection:
  `blendlib-fabric-client/src/test/java/com/liy/blendlib/fabric/client/render/MaterialRenderMapperTest.java`.
- In contrast, the pre-correction ADR-014 mapping table and two exact-0.10
  single-sided material-matrix rows required missing-model plus
  `BLENDLIB-MAT-004`. That expectation cannot be honestly treated as a passing
  rejection observation while the verified public cull path exists and is used.

## Decision

For the fixed Minecraft 26.1.2 adapter, amend the active P4 material matrix as
follows:

| `mode` | `double_sided` | `cutout_threshold` | Public mapping | P4 status |
|---|---:|---:|---|---|
| `cutout` | `false` | exactly `0.10` | `entityCutoutCull` | Supported: ordinary culling path. |
| `cutout` | `true` | exactly `0.10` | `entityCutout(..., false)` | Supported: ordinary no-cull path. |
| `cutout` | either | any value other than exactly `0.10` | none | Rejected with `BLENDLIB-MAT-004` / `CUTOUT_THRESHOLD_UNSUPPORTED`. |

The owner decision requires the following source-of-truth alignment atomically:

1. Correct ADR-014's `cutout,false` table row and its consequences text to
   name `entityCutoutCull` as the exact public culling path.
2. Reclassify the lit and emissive exact-0.10 single-sided cutout fixture rows
   from rejected to supported. Rename the two resource-pack directories and
   their matrix/document references so their names do not falsely promise a
   `MAT-004` rejection.
3. Update P4 fixture/verifier tests and manual matrix instructions to expect
   `missing=false` and no diagnostic for those two rows, then obtain fresh
   isolated real-client evidence under the existing one-pack/F3+T/baseline
   protocol.
4. Preserve every other ADR-014 rejection boundary unchanged: opaque
   double-sided, translucent single-sided, additive, and non-0.10 cutout
   thresholds remain missing-model `BLENDLIB-MAT-004` cases.

No production mapper or backend code change is required: the verified current
implementation already follows the accepted exact public-path behavior.

## Consequences and Gate Handling

The local project owner accepted this correction on 2026-07-30. The two
affected rows are now supported fixture inputs, but this decision creates no
visual evidence: fresh isolated real-client observations must still use the
one-pack, F3+T, baseline-restore protocol and prove `missing=false` with no
diagnostic for each lit/emissive row. No production mapper or backend change is
required because the checked implementation already uses the exact public path.

P4 remains `WAITING`. Existing material evidence is not reinterpreted as an
observation of the two newly supported rows, and neither this acceptance nor
fixture/test alignment makes a material-matrix or P4 Gate PASS. The other
ADR-014 rejection boundaries remain explicit missing-model
`BLENDLIB-MAT-004` cases.

This accepted decision is intentionally narrow: it corrects an accepted
ADR/fixture classification to match a verified public 26.1.2 capability. It
neither authorizes a silent implementation deviation nor weakens diagnostics,
performance, security, visual, or Gate requirements.
