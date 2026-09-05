# ADR-017: P7 reference-scene visibility semantics and spatial layout

Status: Accepted
Proposed: 2026-07-29 by the root coordinator
Accepted: 2026-07-30 by the local project owner
Decision authority: local project owner

## Context

The P7 canonical manifest requires all 125 target instances to be visible before
sampling. The current deterministic implementation places the 100 rigid hosts
at `y=1..28` and the 25 skinned hosts at `y=34..58`, with the prescribed
camera at `(0, 28, -40, yaw=0, pitch=0)`.

The first real isolated-client attempt used that generated pack, the exact
host count, and the prescribed camera. The controller observed all 125 hosts
and loaded both model keys, but its first measured render frame contained only
`rigid=0` and `skinned=10` submissions. It therefore wrote an
`INVALID_OR_INCOMPLETE` report rather than a performance result. This is
evidence that the current implementation does not yet establish the stated
all-target visibility condition; it is not evidence of 60 FPS or of a visual
Gate result.

The same client process later crashed on an independent generated-skin normal
matrix defect. That defect can be repaired without deciding this ADR, but a
successful skin repair alone does not decide how the reference scene must
prove its all-target visibility condition.

## Evidence

- `test-assets/p7-performance/reference-scene.json` declares
  `all_target_instances_must_be_visible_before_sampling: true`.
- `P7ReferenceScenario.createStandard()` fixes the current anchors and camera.
- `D:\BlendLib\blendlib-showcase\run\p7-benchmark\benchmark-results\p7-runtime-invalid-1785311742510.json`
  records `rigid=0, skinned=10, expected=100/25` with `gate: WAITING`.
- `docs/evidence/P7-performance.md` preserves the isolated-client outcome and
  separate crash path.

## Proposal

Before another P7 performance capture, the owner must select one reproducible
interpretation and update the scenario source, manifest, golden verification,
and manual checklist together:

1. **True in-frustum scene.** Retain the counts, triangle total, joint count,
   600-frame warm-up, 1,800 samples, and target FPS, but deterministically
   arrange the existing hosts and/or prescribe an explicit camera/FOV so every
   target is actually in the render frustum without hiding one target behind
   another. The evidence must record resolution and FOV.
2. **Forced-submission benchmark.** Retain the exact target work but make an
   opt-in P7-only renderer path explicitly bypass ordinary host frustum culling
   for the benchmark. Its evidence must call this a forced-submission
   performance workload, never a visual-all-visible proof, and a separate
   human visual check remains required.
3. **A different reproducible layout/camera contract.** Define its exact
   coordinates, FOV, resolution, and proof that all 125 host submissions occur
   on every warm-up and measurement frame.

## Decision — accepted 2026-07-30

The owner selects proposal 1, **true in-frustum scene**, with the following
frozen replacement contract. It keeps all target work and measurement counts
unchanged; it is an implementation contract, not a completed visual or
performance result.

| Item | Accepted contract |
|---|---|
| Rigid placements | For `r=0..3`, `c=0..24`, ordinal `25*r+c`: `(-18 + 1.5*c, 64 + 2*r, 0)` — exactly 100 hosts. |
| Skinned placements | For `c=0..24`, ordinal `c`: `(-18 + 1.5*c, 72, 0)` — exactly 25 hosts. |
| Capture camera | Command target `/tp @s 0 67 24 180 0`; the player-centre check is `(0.5, 67, 24.5, yaw=180, pitch=0)`. |
| Fixed client conditions | `1920x1080`, 16:9, FOV setting `90`, dynamic FOV disabled, render distance at least 8 chunks. The first valid capture must retain evidence of those values. |
| Unchanged target | 100 rigid × 10,000 triangles; 25 skinned × 20,000 triangles/64 joints; 1,500,000 total triangles; 600 warm-up frames; 1,800 samples; 60-FPS target. |

The arrangement faces the single-sided opaque generated meshes from `+Z` with
`yaw=180`. Its conservative entity-culling envelope is
`x=[-18.375,18.997]`, `y=[64,73.8]`, `z=[-0.375,0.375]`; relative to the
player centre, the worst horizontal and vertical angles are approximately
`38.0°` and `15.8°`. They fit within a 90-degree 16:9 view even under the
narrower interpretation of that setting. Horizontal/vertical spacing keeps
the culling bounds distinct. This is a static geometry proof only: the actual
client must still record exactly `100/25` submissions for every warm-up and
sample frame before it may produce a valid result.

Before another capture, one P7 implementation package must atomically update
`P7ReferenceScenario`, both canonical manifests, layout/golden tests, the
camera preflight, and the report/evidence fields that retain actual FOV and
framebuffer dimensions. It must not reduce work, bypass host culling, or
reinterpret a partial-submission report as a result.

## Preserved guardrails

- No reduction in target instances, triangles, joints, warm-up frames,
  sample frames, or target FPS.
- No reinterpretation of an invalid partial-submission capture as a result.
- No change to core GLB safety, skin normal-matrix validation, public API,
  Minecraft/Fabric version, raw-OpenGL rule, or visual Gate requirement.
- No new P7 performance capture that claims a valid reference result under the
  current unresolved visibility semantics.

## Required follow-up

The controller may reject partial submissions during warm-up as well as during
sampling; the generated-skin crash may be repaired with strict invertibility
coverage; and isolated evidence, reload/cache tests, documentation, and other
non-conflicting P7 work may continue.
