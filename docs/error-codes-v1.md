# Diagnostics, Error Codes, and Safety Limits v1

Status: P0 baseline frozen; later assignments require an Accepted ADR.

## Stable diagnostic shape

Every diagnostic records these fields:

| Field | Meaning |
|---|---|
| severity | INFO, WARN, or ERROR |
| code | Stable BlendLib code |
| model_key | Logical model key when known |
| resource_id | Descriptor, GLB, or referenced resource |
| json_pointer_or_gltf_index | Location within descriptor or GLB data |
| message | Human-readable explanation |
| cause_summary | Bounded underlying-cause summary |

Codes use the stable form BLENDLIB-FAMILY-NNN. New code assignments must preserve existing meaning.

| Code | Stable meaning |
|---|---|
| BLENDLIB-DESC-001 | Unsupported schema version |
| BLENDLIB-DESC-002 | Descriptor object or resource-reference shape is invalid |
| BLENDLIB-GLB-001 | Invalid header or declared length |
| BLENDLIB-GLB-002 | GLB chunk or JSON layout is invalid |
| BLENDLIB-GLB-014 | Accessor outside allowed bounds |
| BLENDLIB-GLB-015 | Primitive, index, or non-finite accessor data is invalid |
| BLENDLIB-SCENE-004 | Node hierarchy cycle |
| BLENDLIB-SCENE-005 | Scene reference or transform is invalid or non-finite |
| BLENDLIB-SCENE-006 | Camera or light node is ignored by the v1 profile |
| BLENDLIB-SKIN-001 | Skin data is invalid |
| BLENDLIB-ANIM-006 | Animation time is non-monotonic |
| BLENDLIB-ANIM-007 | Animation sampler or interpolation is unsupported or invalid |
| BLENDLIB-MAT-003 | GLB material slot has no descriptor mapping |
| BLENDLIB-MAT-004 | 26.1.2 adapter cannot represent descriptor material intent with an exact public render path |
| BLENDLIB-LIMIT-001 | Asset exceeds a hard limit |
| BLENDLIB-PERF-001 | Asset exceeds a non-fatal performance-warning threshold |
| BLENDLIB-EXT-001 | Required extension is unsupported |

`BLENDLIB-MAT-004` was assigned by accepted ADR-014 on 2026-07-29. The P4
implementation now declares `BlendDiagnosticCodes.MAT_004` and emits this
diagnostic during reload when the 26.1.2 adapter cannot represent a descriptor
material intent through an exact verified public render path. The result is a
missing-model handle; it is not a silent material fallback. P7 may add a
verified extension/backend path, but must not change this code's stable meaning.

## Default hard limits

| Item | Hard limit |
|---|---:|
| One GLB file | 64 MiB |
| Vertices in one model | 1,000,000 |
| Indices in one model | 3,000,000 |
| Nodes | 4,096 |
| Rigid animated nodes | 4,096 |
| Skin joints | 512 |
| Hierarchy depth | 256 |
| Clips | 256 |
| Total keyframe samples | 1,000,000 |
| Duration of one clip | 600 seconds |
| Material slots | 256 |
| Sockets | 512 |
| Animation states in one descriptor | 256 |
| Descriptor or synchronized-trigger speed multiplier | 64 |
| Visual events on one state | 4,096 |
| Visual events in one descriptor | 16,384 |
| Loop cycles crossed by one `advance` | 4,096 |
| Automatic state transitions in one `advance` | 4,096 |
| Visual events returned by one `advance` | 16,384 |

Descriptor/state-count and declaration-event violations use `BLENDLIB-LIMIT-001`
with the exact descriptor field pointer. Runtime `advance` validates loop-cycle,
automatic-transition, and emitted-event counts with closed-form preflight
arithmetic before it mutates controller state or allocates the event result. A
call beyond those budgets fails explicitly; it never truncates or silently
drops visual events.

The loader should issue performance warnings before hard failure where useful, including above 100,000 vertices or 128 skin joints. Warnings never relax a hard limit.

## Resource-reference restrictions

The descriptor and GLB resolver reject:

- file URI references;
- absolute paths;
- parent-directory traversal;
- network URIs;
- references to files outside the permitted resource directory.

## Failure containment

- A failed asset becomes a stable missing model with bounded geometry and obvious magenta/black material.
- Missing-model fallback never hides schema or security diagnostics.
- Production logging emits one reload summary. Per-asset primary errors are de-duplicated for a generation; detailed diagnostics remain queryable in development.
- Expected client commands are /blendlib assets, /blendlib inspect <model-id>, and /blendlib diagnostics [model-id].
