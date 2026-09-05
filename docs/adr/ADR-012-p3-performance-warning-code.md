# ADR-012: P3 Non-fatal Performance Warning Code

Status: Accepted

## Context

The frozen v1 diagnostics document requires the loader to issue useful
performance warnings before a hard-limit failure, including above 100,000
vertices or 128 skin joints. `BLENDLIB-LIMIT-001` is reserved for a hard-limit
violation and cannot truthfully describe a non-fatal warning. No existing
stable code covers this meaning.

## Decision

Add the following stable v1 diagnostic assignment:

| Code | Stable meaning |
|---|---|
| `BLENDLIB-PERF-001` | Asset exceeds a non-fatal performance-warning threshold |

The loader emits this code with severity `WARN` when a supported asset exceeds
the documented vertex or skin-joint warning threshold. It does not relax,
replace, or suppress any hard-limit validation; a later hard-limit failure
still uses `BLENDLIB-LIMIT-001`.

## Consequences

Performance-sensitive assets remain loadable under their hard limits while
diagnostics retain a stable, truthful category. All prior P0 and ADR-010 code
meanings remain unchanged.
