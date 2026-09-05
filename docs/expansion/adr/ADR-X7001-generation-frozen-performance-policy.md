# ADR-X7001: generation-frozen adapter-private performance policy

- Status: Proposed / Experimental repair candidate; R1 fresh review **FAIL** (0C/0H/3M), D2b CPU-subset composition awaits a new independent review
- Date: 2026-08-29
- Scope: X7 foundation only

## Context

X4 supplies an immutable, generation-pinned render snapshot and X6 supplies a prepared
render-plan seam. They do not establish a GPU backend, batching, resource ownership, or a
submit-time policy. X7 needs a conservative place to make performance decisions without
changing those owners or turning an unverified acceleration path into a rendering contract.

The foundation must preserve an available CPU route when optional acceleration is unavailable or
fails during preparation. It also needs deterministic limits, visibility inputs, LOD selection,
animation-work recommendations, and measurement that can be consumed later without committing a
new public API.

R1 found three policy-correctness Medium defects: independent-only budget accounting could admit
overflowed aggregate work; generation/handle binding omitted material and could be forged by a
package peer; and one unscoped culling result could promote bone or primitive evidence into a
broader cull. This repair closes those three defects without claiming a backend implementation.

## Decision

1. The client module contains a pure-Java, package-private reload-private policy foundation. Its
   six owner-policy files keep their value types nested, so no X7 stable API or discovery mechanism
   is created.
2. `X7GenerationPerformancePlan.Binding` freezes exact identity references for generation, handle,
   model, geometry, material route, and LOD before preparation. A future owner must derive those
   references from the one X6 prepared render-plan/material authority it already owns; X7 retains
   identities only and creates no competing X6 resource or mutable plan owner.
3. The outer `X7GenerationPerformancePlan` is the sole factory for `Prepared` and `Published`.
   Their constructors and the capability/failure `DecisionProof` are private, so ordinary
   same-package Java source has no direct way to create a `GPU_CANDIDATE` from a backend enum or a
   value-type constructor. Malicious reflection in the unnamed module is outside this policy's trust
   boundary, and X7 production source is forbidden from using reflection. The outer factory emits a
   candidate only after capability, preparation, and upload observations all pass; each failed
   observation emits CPU before publication with an explicit reason.
4. Publication transfers the same immutable binding and private proof. Submit validates all six
    frozen identities, rejects stale generation or cross-handle/model/geometry/material/LOD reuse,
    and has no backend-selection parameter. Dependent budget evaluation receives the frozen
    `Published` plan rather than a freely supplied `BackendChoice`.
   D2b does not use that full plan: generic X4 has no trustworthy X6 geometry/material/LOD facts.
   Instead, the same outer owner has a separate private-proof CPU-subset publication retaining only
   generation, exact handle, and exact source-view identity. It is fixed to
   `CPU/CAPABILITY_UNAVAILABLE` and cannot publish a GPU candidate or stand in for the full plan.
5. `X7BudgetPolicy` evaluates each added model cohort against immutable previously admitted usage.
   It computes `bones * animatedInstancesPerFrame` and
   `vertices * animatedInstancesPerFrame` with checked multiplication, then computes generation
   and frame totals with checked addition. Any derived or cumulative overflow is an explicit
   non-publishable `REJECT`; it can never become `DEGRADE`. A successful decision alone returns
   the next immutable usage token, so X7 owns no global accumulator or second rendering authority.
6. `X7CullingPolicy` returns separate instance, primitive, and bone decision types. Each retains
   only its own exact generation/handle/(primitive)/(bone) target and can validate only that scope.
   A scope culls only when its own bounds are complete and its own visibility is known hidden.
   `UNKNOWN` and incomplete/dynamic bounds remain drawable at every scope; a hidden bone cannot
   cull a primitive or instance, and a hidden primitive cannot cull its instance.
7. `X7LodPolicy` uses ordered finite thresholds and exact generation/model/material identity to
   scope hysteresis. Invalid distances (NaN, infinity, or negative) and invalid matching history
   reject rather than invent a reusable LOD.
8. `X7AnimationWorkPolicy` accepts only the typed instance decision, never a primitive or bone
   decision, and its only selection entry requires the active generation plus exact active handle.
   It validates that identity before reading the decision/reason or recommending `FULL`,
   `REDUCED`, `PAUSED`, or `REUSE_POSE`; stale/cross-handle decisions throw instead of
   throttling, pausing, or reusing a pose. `X7PolicyMetricsCollector` remains a low-allocation,
   single-owner mutable counter with immutable resettable snapshots.

## CPU fallback invariant

When a future integration owner consumes this foundation, its CPU fallback must continue through
the existing public measurement collector and standard `RenderType` route. For a frozen snapshot,
material, visibility, and generation semantics must be equivalent whether the prepared choice is
CPU or `GPU_CANDIDATE`; a candidate failure must resolve before publication, not alter only part of
the submission. The CPU route must remain available for Iris, Sodium, and incompatible hardware.

This ADR records the required integration invariant only. D2b carries its CPU-route primitive via
the source-bound D1 composite; X4 combines it with the exact snapshot's existing visibility, while
X6 preserves each later compatible snapshot's own visibility. Neither path re-evaluates policy in
submit. `GPU_CANDIDATE` is a tested policy candidate, not a real GPU backend: no X7 code allocates,
uploads, owns, closes, submits, or times a GPU resource.

## Consequences and non-goals

This decision adds no raw GL call, reflection, I/O, provider discovery, thread, Minecraft type,
Fabric type, renderer hook, shared entrypoint, resource lifecycle, batch, or actual culling
backend. The following remain **WAITING**: real GPU/backend implementation; GPU resource
allocation/upload/close; shared mesh/vertex ownership; batching/static instancing; X4/X6/shared
generation-owner beyond the narrow D2b source-bound CPU route; reload and generation retirement
integration beyond existing D2a ownership; real performance and
allocation benchmarks; Iris/Sodium and incompatible-hardware compatibility; client/server
integration; and manual visual verification.

The source and tests are evidence for deterministic repaired policy behavior only. They do not
promote X7, X4, X6, or any original v1 Gate, and R1 is not superseded until a fresh independent
review evaluates this repair.
