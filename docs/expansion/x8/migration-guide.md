# Blockbench and GeckoLib migration guide

This guide maps offline authoring input into BlendLib's strict-v1 runtime contract. It does not add
a runtime reader for Blockbench, GeckoLib, .blend, FBX, or OBJ. The X8 converter is a standalone
Python standard-library tool; GeckoLib is only an input dialect name and is never a runtime
dependency.

## Before migration

1. Preserve the original authoring project outside the runtime pack and record its rightsholder,
   source, and license.
2. Decide whether the target is strict rigid-v1 or skinned-v1. The X8 converter emits rigid-v1
   node-based cuboids; preserve skinned assets through an approved skinned authoring/export route.
3. Flatten or replace unsupported constructs before conversion rather than expecting a runtime
   compatibility shim.
4. Select one external PNG per conversion invocation. The converter has one deterministic material
   output, so split an authoring model when it needs materially distinct texture ownership.
5. Use lower-case namespace and model path names. Keep output below a dedicated pack directory.
6. Keep source JSON within 32 MiB and PNG inputs within 64 MiB with a valid PNG IHDR/dimensions.
   Pass no more than 64 repeated `--animation` inputs and 64 repeated `--texture` inputs; all
   external authoring reads together are bounded to 128 MiB. Budget no more than 4,096 bones,
   10,000 cubes, 256 clips, 250,000 keys per channel, 1,000,000 total samples, 600 seconds per
   clip, and a 64 MiB strict GLB output.
7. Use an output root that cannot collide with a source JSON/PNG path. Existing model, descriptor,
   texture, and report paths are refused by default; use `--force` only when replacing reviewed
   regular files. A directory, symlink, reparse point, or unsafe parent is never forceable. Empty
   animations with no supported channel are a named unsupported construct and fail closed unless
   reviewed lossy mode is intentionally selected.

## Construct mapping

| Authoring construct | Strict BlendLib target | Converter behavior |
| --- | --- | --- |
| Blockbench group / GeckoLib bone | GLB node with named parent hierarchy and TRS | Preserved with a bounded hierarchy |
| Group/bone pivot and Euler rotation | Node translation relative to parent plus quaternion rotation | Preserved deterministically |
| Group/bone scale | GLB node scale | Preserved only if finite, positive, and uniform after GLB FLOAT conversion |
| Blockbench cube / GeckoLib cube | One node-bound triangle primitive contribution | Six faces, positions, normals, UV0, and indices are generated |
| Blockbench face UV rectangle | TEXCOORD_0 | Preserved, including right-angle UV rotation |
| GeckoLib box [u,v] UV | TEXCOORD_0 | Converted with standard Bedrock box-atlas mapping |
| Cube inflate / mirror | Expanded box and triangle winding | Preserved |
| Blockbench position/rotation/scale keyframe | GLB node animation channel | Preserved for numeric linear or step keys |
| GeckoLib bone position/rotation/scale keys | GLB node animation channel | Preserved for numeric linear or step keys |
| External PNG | Descriptor material base_color plus copy/reference manifest | Required by strict output |
| Descriptor profile/material mapping | Strict v1 JSON | Generated with empty extension arrays and rigid-v1 profile |

The output descriptor intentionally contains resource identifiers and material declarations rather
than authoring metadata. A model key names the descriptor, not an arbitrary source filename.

## Source units

The converter's `--source-units-per-block` setting describes how many emitted model units equal
one Minecraft block. It defaults to `16`, the normal Blockbench/GeckoLib cuboid convention. It
does not secretly multiply cuboid coordinates, pivots, group/bone translations, or animation
translation keys: those values are emitted numerically unchanged, while the descriptor receives
the same `units_per_block` value and the runtime applies the reciprocal scale. For an authoring
format already expressed in block units, pass `--source-units-per-block 1` explicitly. The report
records `source_units_per_block`, `target_descriptor_units_per_block`, and the reciprocal for
review.

Scale is different from source units. Frozen strict-v1 scale must be finite, positive, and uniform
for each rest transform and every animation scale key. `--allow-lossy` cannot bypass this core
invariant; bake reflection, zero, or non-uniform scale into cuboid geometry or keys first.

## Blockbench workflow

The converter accepts a Blockbench project JSON shape with elements and/or outliner. Groups become
nodes. Outliner UUID references attach cuboid elements to their nearest group; ungrouped cuboids
attach to an implicit root. Element from/to values create cuboid bounds, while element origin and
rotation create local cube transforms.

For each source element:

- use type cube;
- give all six faces a finite four-number UV rectangle;
- keep UV rotation to multiples of 90 degrees;
- when a face names a texture, make every face name the one selected external PNG;
- use numeric origin, rotation, from, and to values;
- avoid arbitrary mesh elements and expression-driven fields;
- provide an external PNG relative to source-root.

Blockbench animations must have a named bone animator, one numeric data point per keyframe, a
non-negative time, and a position, rotation, or scale channel. Linear and step interpolation map
directly. Bezier, Catmull-Rom, expression, script, sound, particle, effect, and unrecognized
channels have no strict GLB equivalent.

## GeckoLib workflow

The converter accepts common GeckoLib geometry/model JSON shapes containing minecraft:geometry,
geometry.* entries, or a top-level bones array. It accepts animation objects either combined with
the model or supplied through repeated --animation arguments.

For each source bone:

- name must be unique;
- parent must name an existing bone;
- pivot, rotation, and scale must be finite numeric vectors;
- cubes must use origin, size, optional pivot/rotation/inflate/mirror, and either [u,v] or
  supported named face UV data;
- texture dimensions must be positive.

For each animation track:

- target only position, rotation, or scale;
- use a numeric vector or numeric time-keyed object;
- use linear or step interpolation;
- keep target names equal to exported bone names;
- avoid Molang expressions, query-dependent values, timelines, particle/sound effects, controller
  transitions, and any custom event executor.

GeckoLib animation controllers are not BlendLib runtime controllers. Migrate the desired semantic
state names into a strict descriptor animation state map, then publish those names from
server-authoritative gameplay using BlendAnimations. A controller's conditions and game logic
remain in the consuming mod; they must not be recreated as a client-only visual authority.

## What cannot be automatically converted

The following constructs are rejected by default. With --allow-lossy, the tool emits a structured
warning and omits only the named unsupported construct; review the report before accepting output.
Hard input validation and strict-v1 scale failures remain errors even in lossy mode.

| Source construct | Why it cannot map directly | Migration action |
| --- | --- | --- |
| Arbitrary polygon / poly mesh | X8 converter is cuboid-only and strict profile needs controlled triangle data | Rebuild as cuboids or use an approved strict GLB exporter |
| Embedded/data URI image | Strict profile requires an external PNG | Export a local PNG and pass --texture |
| Absolute, network, URI-like, traversal path | Authoring inputs must not escape local roots | Move asset under source-root |
| Multiple textures/material assignments | X8 deterministic route emits one material per conversion | Split model or use an approved multi-material authoring pipeline |
| Unknown source field or named unrepresentable field | The converter cannot prove strict GLB/descriptor semantics for it | Remove/bake it or use an approved mapping; inspect its exact report path |
| Nonlinear/mixed interpolation | Strict GLB accepts only LINEAR or STEP | Bake to linear/step keys before conversion |
| Morph target, vertex color, multiple UV, compression | Strict v1 rejects these runtime features | Remove/bake through an approved profile exporter |
| GeckoLib controller/Molang/query logic | Runtime behavior is not a static GLB channel | Move semantic decision to mod gameplay/extraction code |
| Particle, sound, script, command event | Presentation/action is not an asset execution engine | Use an explicit client presentation listener after accepted X3 wiring |
| Locator/attachment transform as gameplay | Socket transforms are presentation/extraction data | Use an explicit descriptor socket and keep gameplay independent |
| Custom shader/render layer | X6/X7 standard route boundary rejects arbitrary pipelines | Map to standard opaque/cutout/translucent or reject |
| Minecraft model/display transform | BlendLib model TRS is not a vanilla item display transform declaration | Configure ordinary item/base-model behavior separately |

The converter never silently substitutes a nearby visual behavior for an unsupported construct.
Default rejection is the safe migration result.

## Output review

A successful conversion produces a pack-shaped output tree:

    assets/<namespace>/blend_models/<model>.json
    assets/<namespace>/models3d/<model>.glb
    assets/<namespace>/textures/blendlib/<model>__surface.png
    conversion-report.json

When texture mode is reference, the report names the intended external PNG destination but does not
copy it. The descriptor still names that destination, so the pack owner must install the PNG before
runtime use.

Check the report's status, issue list, texture_manifest, and unit_mapping. An `info` issue names
recognized author/editor metadata only; a `warning` is an actual reviewed lossy omission; an
`error` means no strict output was accepted. Keep a source/provenance record along with the
original authoring project. Do not promote a conversion report to an acceptance result: X8
conversion execution, static asset validation, resource reload, animation playback, socket
resolution, and visual outcome are all **WAITING** unless separately evidenced.

## Semantic migration checklist

- Keep entity/block/item gameplay dimensions and collision independent from the migrated mesh.
- Make server gameplay publish only animation meaning, speed, and deterministic seed.
- Make client events presentation-only.
- Keep socket IDs semantic and resolve them only in an allowed extraction stage.
- Keep variant/material/backend selection bounded and preparation-time only.
- Keep a standard CPU path fallback for approved rendering routes.
- Do not mix platform jars: each Minecraft/loader target needs its own artifact and evidence.
- Record external source attribution and repository license status before distribution.
