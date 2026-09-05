# ADR-X8004: Keep Blockbench and GeckoLib conversion strictly offline

## Status

Proposed X8 implementation candidate. The converter source and documentation are present, but independent
static review, converter execution, generated-asset validation, reload, and visual evidence are
**WAITING**. This ADR changes no strict-v1 runtime loader or expansion progress status.

## Context

Artists commonly author models in Blockbench and GeckoLib-compatible JSON formats. These formats
contain authoring hierarchy, cuboids, UVs, animation data, controller/event conventions, and
application-specific extensions. BlendLib v1 runtime intentionally accepts only strict versioned
descriptor/Profile/GLB data plus external PNG references. Its hot paths are forbidden from I/O,
JSON/GLB parsing, provider discovery, or source-format adaptation.

Making an authoring format available at runtime would expand the asset surface, couple a platform
artifact to third-party implementation code, blur source/provenance boundaries, and make
reload/submit behavior unbounded. A GeckoLib runtime dependency would also violate the
independent strict-v1 contract.

## Decision

X8 supplies a standalone Python standard-library CLI below tools/model-converter. It detects
supported Blockbench and GeckoLib geometry/model/animation JSON shapes only as local offline
inputs, produces strict rigid-v1 descriptor/GLB/external-PNG output, and is never called from a
runtime module, Gradle production task, mod entrypoint, data-generator hot path, reload listener,
renderer, submit, animation advance, socket query, or provider discovery.

The converter:

- permits only local UTF-8 JSON and external PNG paths under an explicit source root;
- rejects URI-like, network, absolute-output, traversal, NUL, and backslash paths;
- bounds one-open-stream JSON reads, repeated external animation/texture input counts, cumulative
  authoring read bytes, PNG signature/IHDR and copy reads, hierarchy, cube, vertex, clip, sample,
  duration, and generated-GLB sizes;
- creates deterministic nodes, cuboid faces, UV0, normals, indices, TRS, and supported animation;
- recognizes supported GeckoLib and Blockbench objects field-by-field. Each field is either
  faithfully emitted, recognized as non-runtime author/editor metadata, or reported with its exact
  source path as known-unrepresentable or unknown; locators, controllers, event/timeline,
  particle, sound, and custom behavior are not silently dropped;
- emits only strict GLB 2.0 data allowed by the existing profile, including finite, positive,
  uniform rest and animated scale after FLOAT conversion. Lossy mode cannot weaken this core rule;
- uses the declared source cuboid-units-per-block value (default 16 for Blockbench/GeckoLib) as
  descriptor `units_per_block`, leaves source coordinates/pivots/translations numerically intact,
  and records the source-to-runtime unit mapping in the report;
- requires explicit output namespace/model identity;
- emits a structured report for errors and explicit lossy omissions;
- refuses every existing GLB, descriptor, copied PNG, and report target by default. `--force` is
  the explicit replacement opt-in only for a rechecked regular file; directories and
  symlink/reparse-point targets or parent components are never published through;
- writes each generated file through a sibling temporary path, then rechecks target/parent safety
  immediately before atomic create-only publication or forced replacement;
- rejects output/input and output/output collisions before an input can be overwritten, avoids
  overwriting a report to describe replacement rejection, and names an empty animation with no
  supported channel as an explicit unsupported omission;
- imports no GeckoLib/Blockbench code, library, JAR, class, or network resource.

Unsupported input fails closed by default. Explicit --allow-lossy is a reviewable authoring choice;
it records every omission and never makes the output a runtime compatibility claim.

## Consequences

- Strict runtime loader, descriptor semantics, Profile, public API, common/server boundary, and
  provider protocol remain unchanged.
- Downstream authors get a deterministic import route without a new runtime dependency.
- Original authoring source remains outside the runtime pack and needs independent provenance and
  license records.
- Cuboid/one-material and linear-or-step constraints are visible before runtime.
- Generated assets still require separate static/runtime/visual acceptance evidence.

## Alternatives rejected

- Read Blockbench/GeckoLib JSON during runtime reload: expands the runtime asset contract and
  creates unchecked parser/lifecycle work.
- Add GeckoLib as a runtime dependency: violates the strict independent Profile boundary.
- Automatically run conversion from a mod entrypoint or renderer: violates hot-path and
  authoring/runtime separation.
- Silently approximate unsupported source features: hides semantic loss and weakens fail-closed
  diagnostics.
- Store source-specific fields in descriptor extensions: conflicts with strict extension handling.
