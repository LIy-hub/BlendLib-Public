# ADR-014: P4 Material Culling and Emissive Mapping on Minecraft 26.1.2

Status: Accepted
Accepted: 2026-07-29 by the local project owner
Amended: 2026-07-30 by accepted ADR-019 (P4 cutout single-sided public-cull correction)

## Context

The accepted v1 descriptor expresses material *intent* through `mode`,
`emissive`, `double_sided`, and, for `cutout`, `cutout_threshold`.
`design-v1.md` section 6 requires a fullbright adapter path for `emissive` and
standard Minecraft RenderType/RenderPipeline paths for the remaining intent.

The current P4 mapper validates `mode`, `double_sided`, and applicable
`cutout_threshold` before the backend selects a fixed public render type; only
descriptor-equivalent combinations reach that backend. An earlier implementation
idea was to pass `double_sided` as the boolean argument to
`RenderTypes.entityCutout(Identifier, boolean)` or
`RenderTypes.entityTranslucent(Identifier, boolean)`. That is false on the
fixed 26.1.2 client runtime: the boolean controls outline participation, not
face culling. Neither the current mapping nor this ADR may silently reinterpret
descriptor material intent as an implementation convenience.

## Evidence

The locally resolved Minecraft 26.1.2 client-only JAR exposes these relevant
public methods:

- `RenderTypes.entitySolid(Identifier)`;
- `RenderTypes.entityCutoutCull(Identifier)`;
- `RenderTypes.entityCutout(Identifier, boolean)`;
- `RenderTypes.entityTranslucent(Identifier, boolean)`;
- `RenderTypes.entityTranslucentEmissive(Identifier, boolean)`;
- `RenderTypes.entityTranslucentCullItemTarget(Identifier)`.

Local bytecode confirms that the boolean overloads select outline/upload
behavior rather than face culling. `entityCutoutCull` and `entityCutout` are
separate cull/no-cull paths. The ordinary standard cutout and translucent
pipelines have a fixed `0.1` alpha cutoff; they do not accept the descriptor's
`cutout_threshold` value. The only public translucent cull-named helper targets
the item render target, so it is not an equivalent ordinary entity/world path.
`RenderType.create` is package-private; custom construction, reflection, or
private implementation calls are prohibited by the frozen public-adapter
boundary.

The current descriptor decoder preserves `cutout_threshold`, and P4
preparation consumes it only to enforce exact equality with the verified public
`0.1` cutoff. The backend receives the already-validated cutout intent rather
than an arbitrary threshold value; every non-exact value remains an explicit
compatibility conflict and is rejected. Current additive handling is likewise
explicit: it is rejected during P4 preparation rather than rendered through a
different blend mode.

## Current 26.1.2 Mapping Matrix

`E` below is the descriptor's `emissive` bit. For every row that currently
reaches a render type, `E=0` uses the ordinary submitted light and `E=1` uses
the independently selected fullbright vertex light. `E` does not change the
culling result in any row.

| `mode` | `double_sided` intent | Current P4 public mapping | Current culling result | `E=0` / `E=1` | Semantic status |
|---|---:|---|---|---|---|
| `opaque` | `false` | `entitySolid` | culls back faces | ordinary light / fullbright | Correct for this culling intent. |
| `opaque` | `true` | `entitySolid` | culls back faces | ordinary light / fullbright | **Wrong:** silently culls a double-sided material. |
| `cutout` | `false` | `entityCutoutCull` | culls back faces | ordinary light / fullbright | Correct for this culling intent when the descriptor threshold is exactly `0.10`. |
| `cutout` | `true` | `entityCutout` | does not cull back faces | ordinary light / fullbright | Correct for this culling intent, subject to the unresolved threshold conflict below. |
| `translucent` | `false` | `entityTranslucent` | does not cull back faces | ordinary light / fullbright | **Wrong:** silently renders a single-sided material as no-cull. |
| `translucent` | `true` | `entityTranslucent` | does not cull back faces | ordinary light / fullbright | Correct for this culling intent. |
| `additive` | either | rejected as `ADDITIVE_UNSUPPORTED_IN_P4` | no render path | not applicable | Explicitly rejected; it is not remapped. |

For `cutout`, the table describes culling only. A row is not a complete
descriptor-equivalent material mapping while its `cutout_threshold` is merely
preserved and the selected public pipeline still fixes alpha cutoff at `0.1`.
The descriptor only permits that field for `cutout`. Under the accepted exact
public-path policy below, P4 accepts it only when the fixed public `0.1` cutoff
is an exact descriptor-equivalent; every other value is rejected rather than
silently reinterpreted.

## Decision

For the 26.1.2 adapter:

1. Select only an exact public standard path. A material combination may be
   accepted only when its culling, output target, and applicable alpha-cutoff
   semantics are all represented by the verified public path. The boolean
   overload remains independent of descriptor material intent because it is an
   outline control, not a culling control.
2. Reject every combination lacking that exact public path at reload. It would
   receive the missing-model fallback and the stable
   `BLENDLIB-MAT-004` adapter-material diagnostic; there is no cull/no-cull,
   output-target, or threshold fallback. `additive` remains explicitly
   rejected.
3. Keep `emissive` independent: `E=1` uses fullbright vertex light and does
   not alter the culling decision. A translucent-emissive standard type may be
   selected only if its ordinary-world semantics are otherwise equivalent.
4. Do not implement a threshold equivalence rule in P4 merely because the
   standard path fixes alpha cutoff at `0.1`. A cutout threshold is accepted
   only when that fixed value is descriptor-equivalent; otherwise it is
   rejected under rule 2.
5. `BLENDLIB-MAT-004` is assigned by this accepted ADR. It is not yet present
   in the current source or `docs/error-codes-v1.md`; the implementation must
   add the code consistently before emitting it.
6. P7 owns any expanded material backend needed to support the remaining valid
   material combinations, including Iris/Sodium validation. It must use a
   verified public API or an explicitly approved version-adapter change;
   package-private construction and reflection remain out of bounds.

## Alternatives Rejected

- Passing `double_sided` to the boolean overload: it changes outline behavior,
  not culling.
- Using `entityTranslucentCullItemTarget` for ordinary entities/world objects:
  it changes the output target.
- Calling package-private RenderType construction, using reflection, or relying
  on Minecraft/Fabric implementation internals: each violates the frozen
  public-adapter boundary.
- Quietly treating every material as no-cull or cull, or quietly treating the
  fixed `0.1` cutoff as an arbitrary descriptor threshold: each changes v1
  descriptor semantics without a diagnostic.

## Consequences

This accepted decision preserves the descriptor format while making the P4
standard subset and its rejection boundary explicit. As corrected by accepted
ADR-019, the exact `cutout`, `double_sided=false`, `cutout_threshold=0.10`
combination uses the public `entityCutoutCull` path; its `double_sided=true`
counterpart uses the public no-cull `entityCutout` path. Every other rejection
boundary in this ADR remains unchanged, including opaque double-sided,
translucent single-sided, additive, and every non-exact cutout threshold.
It authorizes the accepted reload-time rejection and material mapping
implementation, but does not itself constitute a complete material
implementation, visual proof, or P4 Gate PASS.
