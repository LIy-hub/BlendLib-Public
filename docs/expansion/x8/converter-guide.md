# Offline converter guide

The X8 converter is tools/model-converter/blendlib_model_converter.py. It is a directly runnable
Python 3.11+ standard-library CLI for offline authoring work. It has no package installation,
Gradle, Fabric, Minecraft, GeckoLib, Java, network, or runtime dependency.

Use its built-in usage text for the exact current argument syntax:

    python tools/model-converter/blendlib_model_converter.py --help

X8 intentionally did not execute this command or any conversion. The command below is a documented
usage example, not recorded execution evidence.

## Basic command

    python tools/model-converter/blendlib_model_converter.py ^
      --source-root authoring ^
      --input models/clockwork.bbmodel ^
      --texture textures/clockwork.png ^
      --out generated-pack ^
      --namespace example ^
      --model machines/clockwork ^
      --source-units-per-block 16

On PowerShell, use the local line continuation convention or put the command on one line. All
input, animation, texture, output-report, namespace, and model values should be reviewed before
running it in a trusted local authoring directory.

## Arguments

| Argument | Required | Meaning |
| --- | --- | --- |
| --input path | Yes | Primary Blockbench or GeckoLib model JSON relative to --source-root |
| --animation path | No, repeatable | Separate Blockbench or GeckoLib animation JSON relative to --source-root; embedded geometry is named as omitted and fails closed unless lossy mode is selected |
| --texture path | No, repeatable | Explicit external PNG override relative to --source-root |
| --source-root path | No | Existing local root for all authoring input paths; defaults to current directory |
| --out path | Yes | Relative output root below the current directory |
| --namespace id | Yes | Lowercase resource namespace |
| --model path | Yes | Lowercase extension-free resource path |
| --source-units-per-block number | No | Positive source model units per Minecraft block; defaults to `16` for the usual Blockbench/GeckoLib cuboid convention |
| --texture-mode copy or reference | No | Copy PNG atomically by default, or emit a manifest for an externally installed PNG |
| --report path | No | Relative report file below --out; defaults to conversion-report.json |
| --allow-lossy | No | Turn named unsupported omissions into warnings; default behavior is fail closed |
| --force | No | Explicitly replace existing regular model, descriptor, texture, and report files after safety checks; default refuses all existing targets |

The tool detects Blockbench from project elements/outliner or animation-list shapes. It detects
GeckoLib geometry from minecraft:geometry, geometry.* or bones shapes, and GeckoLib animation from
an animation-object shape. It does not decide a dialect from a file extension or download an input.

## Strict outputs

For namespace example and model machines/clockwork, a successful output contains:

    generated-pack/assets/example/blend_models/machines/clockwork.json
    generated-pack/assets/example/models3d/machines/clockwork.glb
    generated-pack/assets/example/textures/blendlib/machines_clockwork__surface.png
    generated-pack/conversion-report.json

The descriptor uses format_version 1, blendlib:rigid_v1, one Surface material, empty extension
maps/arrays, the requested `units_per_block`, and a resource ID pointing at the generated GLB.
The generated GLB is version 2 and contains only supported data: node TRS, node-bound triangle
primitives, POSITION, NORMAL, TEXCOORD_0, U16/U32 indices, and LINEAR/STEP animation channels.

The converter generates cube faces itself, including normal and UV0 data. It creates one GLB mesh
per named bone with cube geometry and attaches that mesh to its node. The descriptor's external
PNG reference owns texture linkage; the GLB does not embed an image.

## Units and strict scale

`units_per_block` means **model units represented by one Minecraft block**; the runtime applies
its reciprocal. Blockbench and GeckoLib cuboid exports conventionally use 16 model units per
block, so the CLI defaults to `--source-units-per-block 16`. The converter leaves source cuboid
coordinates, pivots, node translations, and animation translation keys numerically unchanged and
writes the same positive value into the descriptor. Thus a source value of 16 becomes one game
block without a hidden coordinate, axis, or animation compensation. Use an explicit positive
override only when the source authoring convention is different. The conversion report records
both source and descriptor values plus `minecraft_blocks_per_emitted_unit` for audit.

Frozen strict v1 accepts only finite, positive, uniform node scale. The converter checks rest
scale and every animated scale key after GLB FLOAT conversion with the same uniform tolerance as
the core. Negative, zero, non-finite, or non-uniform scale is a hard conversion error even with
`--allow-lossy`; bake it into cuboid geometry or animation before converting.

## Blockbench mapping

- outliner groups become named GLB nodes;
- outliner UUID references attach elements to a group, otherwise an implicit root is used;
- cube from/to/origin/rotation form local cube geometry;
- face UV rectangles become TEXCOORD_0;
- native face texture references are preserved only when they all name the one external source PNG;
- position, rotation, and scale keyframes become node animation channels;
- linear and step keys remain their declared interpolation.

Only cuboid elements are accepted. A face without a valid UV rectangle, face texture reference
that cannot name the sole source PNG, zero-size cube, duplicate bone, invalid numeric value,
unknown parent, hierarchy cycle, or malformed animation fails closed.

## GeckoLib mapping

- bones become named GLB nodes with parent/pivot/rotation/scale;
- cubes become six cuboid faces;
- a [u,v] box UV uses deterministic Bedrock atlas mapping;
- named face UV rectangles are accepted where present;
- position, rotation, and scale tracks become node animation channels;
- external PNG texture candidates are read only as local relative paths.

The tool does not import GeckoLib, invoke a GeckoLib controller, execute Molang/query expression,
or produce a GeckoLib runtime artifact.

## Loss policy

Each accepted dialect object is checked field-by-field: GeckoLib root, geometry, description,
bone, cube, UV face, animation, animation-bone, track keyframe; and Blockbench root/meta,
outliner group, element, face, texture, animation, animator, keyframe, and data point. A field is
either emitted faithfully, recognized as non-runtime author/editor metadata (recorded as an
informational issue), or reported by its exact source path as known-unrepresentable or unknown.
The converter does not treat a whole dialect as rejected merely because that dialect has optional
extensions.

By default, unsupported inputs add an error and the command exits with status 2 after writing a
structured failure report only when that report path is safe and non-replacing. With
`--allow-lossy`, those same named omissions are warnings and a successful report uses status
`converted-with-lossy-omissions`. Invalid source shape/numbers/paths and strict-v1-invalid scale
remain hard errors: lossy mode never writes a GLB the frozen core would reject. A Gecko cube
`mirror` value must be a JSON boolean; a string/number/object is also a hard error rather than a
truthy coercion that could change winding semantics.

Examples of loss-rejected constructs include arbitrary meshes, embedded images, multiple textures,
nonlinear/mixed interpolation, locator/attachment data, animation controller/loop behavior,
sound/particle/timeline/custom events, unsupported animation channel, arbitrary script/event,
network URI, unsafe path, invalid numbers, unsupported profile data, or a model with no
cuboid/texture. The report records severity, code, source location, and message. A warning is not
permission to ignore a semantic change; review and sign off on each warning before distributing an
output asset.

## Safety envelope

The CLI protects authoring and output paths as follows:

- source input must stay under the explicit local --source-root;
- --input, --animation, --texture, --out, --report, and --model reject path traversal, dot
  components, backslashes, URI schemes, network locations, and absolute output escape;
- PNG inputs must be regular `.png` files with a valid PNG signature/IHDR, bounded 64 MiB read/copy,
  and dimensions in 1..65,536;
- source JSON is read from one opened regular file, must be UTF-8, and is bounded to 32 MiB;
- repeated `--animation` and `--texture` arguments are limited to 64 each; primary JSON, all
  external animations, and all selected external textures are preflighted and their unique planned
  size plus actual reads are limited to 128 MiB;
- model complexity is bounded to 4,096 bones, 10,000 cubes, 1,000,000 generated vertices,
  256 clips, 250,000 animation keys per channel, 1,000,000 total keyframe samples, a 600-second
  clip duration, 128 hierarchy levels, and a 64 MiB generated GLB;
- NaN, infinity, numeric magnitudes above 1,000,000,000, texture dimensions outside 1..65,536,
  non-positive or non-uniform rest/animated scale, invalid parent, duplicate bone, empty animation
  with no supported channels, and non-monotonic animation timing are rejected;
- JSON ordering, node/channel iteration, descriptor output, and report output are deterministic;
- every emitted GLB, descriptor, copied PNG, and report is rejected if its target already exists
  unless `--force` was supplied; existing directories, symlinks/reparse points, and unsafe parent
  directory components are never valid targets;
- publication writes a sibling temporary file, fsyncs it, then rechecks the target and every parent
  component. No-force publication uses an atomic create-only link; force publication may replace
  only a rechecked regular file. No output/report/descriptor/GLB/texture path may collide with an
  input path or another output path. When any target is unsafe or replacement-protected, the tool
  keeps the diagnostic on stderr and does not overwrite the report to describe that rejection.

`--force` applies per output file and never deletes an output root. A later failure can leave
earlier files from the same forced run, so use a reviewed dedicated output directory and inspect
the report and all existing generated files before rerunning.

## Texture copy/reference manifest

The default copy mode copies the first external PNG into the exact destination named by the
descriptor. Reference mode emits this same mapping in conversion-report.json but does not copy the
PNG; install the file at that destination yourself before runtime use.

A conversion accepts one material/texture mapping. More than one candidate texture is a
fail-closed unsupported condition unless explicit lossy mode is selected, in which case only the
first texture is retained and the report records the omission. Split source models where distinct
material ownership matters.

## Operational boundary

Never add this command to a mod initializer, Fabric resource reload, renderer, server command,
datagen hot path, submit callback, animation advance, socket query, or provider discovery path.
It is intentionally offline. It cannot prove that an output loads or renders: static asset
validation, dynamic resource reload, client playback, socket/event behavior, platform compatibility,
and visual output remain **WAITING** until separately exercised and recorded.
