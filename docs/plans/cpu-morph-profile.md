# Batch 28: bounded CPU morph profile

Implementation plan, October 4, 2026. Stacked on the native-cubic preview, on a
separate feature branch. No main merge, tag, release, or Minecraft visual PASS.

## Contract

Add only `format_version: 2` / `blendlib:skinned_morph_cpu_v1`. Keep frozen v1 and
cubic defaults, schemas, constructors and export modes strict; exact allowlists
must prevent newly added enum values from entering the v1 parser. X9 remains
validation-only. Inherit approved native cubic TRS; morph weights use LINEAR or
STEP. No sparse targets, tangents, GPU morphs, cubic weight curves or new materials.

Each material-split primitive carries dense POSITION and NORMAL deltas, up to
eight mesh-local targets with validated stable `extras.targetNames`. Node defaults
override mesh defaults, otherwise zero. Required named `morph_controls` bind exact
node paths and target names, one declaration for each node/target. Declared finite
signed intervals include zero and fit [-2,2]; defaults, keys and manual values fit
their interval without clamping. Keep ModelNode's shape and skin weights unchanged.

Generation-owned immutable target/binding/channel sidecars and complete per-instance
MorphWeights are additive. Weight-only clips have real durations and no fake TRS
tracks. The existing controller samples pose and complete weights with its own
current/previous clocks and transition amount. Missing tracks reset to defaults.
Frame overrides are batch-validated before state mutation, applied after sampling,
and forgotten next frame. Layer/blendspace plans reject weight-bearing clips;
bone-only layers may coexist with manual controls. Never reconstruct transitions
from observer state or silently drop channels.

## Safety and rendering

Fuse morph-before-skin accumulation in double, share exact immutable topology,
indices, UVs, materials and primitive order, and normalize final skin normals.
Require a conservative source-normal nondegeneracy proof throughout all declared
weight intervals. Bounds use the full intervals and inverse-bind linear delta
transforms, combined with existing cubic/hierarchy bounds and outward margin.
Morph generations and snapshots remain explicitly CPU-only even at zero weights;
never attach T4/X7 skin-only provenance to deformed bytes.

Preflight checked aggregate limits before allocations: eight targets per mesh,
128 morph nodes, 1024 controls, 1,000,000 vertex-target pairs including expanded node
uses/material splits, and 32,000,000 cumulative float slots including copies and
transients. Preserve the existing cubic-only budget unchanged.

## Authoring and evidence

Blender 5.1.2 gets a new opt-in mode with export_apply=False and morph flags enabled.
Discover Key datablock Actions and slots. Initially accept only relative-to-Basis
keys and proven armature behavior; reject drivers, masks, absolute/chained keys and
unproven topology-changing modifiers. Retain native cubic TRS conversion. Bezier
weights use explicit sampled fallback with reported reason/cadence.

Ship an editable first-party Blink/Smile/Breath actor with two materials, nonzero
default, cubic nod and weight-only clips. Compare exported ordering against actual
Blender position/weight samples at keys and off-key times. Validate normals with an
independent glTF delta/skin math oracle. Include a real Blender preview, clearly
distinct from deferred native Minecraft visual acceptance.

Provide a runnable public consumer with independent actors, frame controls/reset,
and supported states, transitions, sockets, appearances and bounds. Verify strict
old contracts, adversarial/budget cases, same-clock transitions, defaults, atomic
failure, cadence-held manual updates, immutability, lifecycle, layer rejection and
GPU exclusion. Run targeted and full core/client/Python/official-26.3 JAR tests,
one focused review, and terminal exact-commit CI. Publish a distinct beta4 morph
preview and update the existing cumulative delivery only after evidence is ready.
