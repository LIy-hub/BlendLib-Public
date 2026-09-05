# ADR-010: P3 Diagnostic Code Assignments

Status: Accepted

## Context

P0 froze the diagnostic shape and the meanings of its initial error-code set.
P3 adds strict descriptor, GLB layout, scene, skin, and animation validation.
Those validation failures need stable, family-specific codes without changing
the meaning of any P0 assignment.

## Decision

The following additive assignments are accepted for v1:

| Code | Stable meaning |
|---|---|
| `BLENDLIB-DESC-002` | Descriptor object or resource-reference shape is invalid |
| `BLENDLIB-GLB-002` | GLB chunk or JSON layout is invalid |
| `BLENDLIB-GLB-015` | Primitive, index, or non-finite accessor data is invalid |
| `BLENDLIB-SCENE-005` | Scene reference or transform is invalid or non-finite |
| `BLENDLIB-SCENE-006` | Camera or light node is ignored by the v1 profile |
| `BLENDLIB-SKIN-001` | Skin data is invalid |
| `BLENDLIB-ANIM-007` | Animation sampler or interpolation is unsupported or invalid |

P0 assignments remain unchanged. In particular, `GLB-001` remains limited to
the header and declared length, `GLB-014` remains limited to accessor bounds,
`SCENE-004` remains limited to hierarchy cycles, and `ANIM-006` remains limited
to non-monotonic animation time.

## Consequences

The strict core loader can return controlled diagnostics for malformed input
without making code meaning depend on message text. Future diagnostics must be
added through another explicit ADR before use.
