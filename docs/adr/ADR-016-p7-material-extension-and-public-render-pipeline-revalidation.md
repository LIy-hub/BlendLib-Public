# ADR-016: P7 material-extension payload and public render-pipeline revalidation

Status: Accepted
Proposed: 2026-07-29 by the root coordinator
Accepted: 2026-07-30 by the local project owner
Decision authority: local project owner

## Context

The approved P7 plan requires a MaterialExtensionDecoder/resolver SPI and
verification of the remaining material intent. The frozen v1 schema accepts a
top-level extensions object, but the strict decoder currently validates it only
as an object and does not retain it in ModelDescriptor or ModelAsset. Any
non-empty extensions_required list is rejected as BLENDLIB-EXT-001.
Consequently, there is no immutable extension payload for a decoder to consume,
and changing that would alter the accepted descriptor format/acceptance set.

Separately, ADR-014 records that RenderType.create is package-private and
therefore prohibits custom construction. Local inspection of the Minecraft
26.1.2 client-only JAR now reports public signatures for
RenderType.create(String, RenderSetup), RenderSetup.builder(...), and
RenderPipeline.Builder. The discrepancy must be resolved before any additive
or custom-pipeline work; the current strict P4 rejection remains active until
then.

## Evidence

- docs/design-v1.md sections 6 and 15 require a material extension direction
  without allowing an extension to change core mesh or animation semantics.
- docs/implementation-plan.md P7 requires the SPI, Iris/Sodium smoke, and
  material validation.
- blendlib-core DescriptorDecoder validates then discards extensions;
  ModelDescriptor has no retained extension payload; non-empty
  extensions_required is rejected.
- schemas/blendlib-model-v1.schema.json defines only a generic extensions
  object and no material-extension envelope or limits.
- ADR-014 is Accepted and currently controls the strict mapping/rejection
  behavior.
- The local resolved Minecraft 26.1.2 JAR must be attached to any acceptance
  decision with exact javap output and an isolated runtime smoke plan.

## Proposal

Before real extension decoding or additive support begins, the owner must make
two independent decisions:

1. Descriptor extension contract. Decide whether v1 may retain a bounded,
   immutable, namespaced material-extension payload; define schema shape,
   size/depth/count limits, required-versus-optional capability negotiation,
   stable diagnostics, and whether unknown optional data is retained or
   rejected. The decision must preserve core mesh/animation semantics and
   expose no mutable JSON object or Minecraft render object through the public
   API.
2. 26.1.2 public-pipeline revalidation. Reconcile ADR-014's package-private
   premise with exact local 26.1.2 bytecode. If custom construction is
   permitted, specify the exact public API surface, lifecycle, fallback
   behavior, Iris/Sodium compatibility smoke, and an adapter-only boundary. If
   it is not permitted, retain ADDITIVE_UNSUPPORTED_IN_P4 and defer additive
   support beyond this RC.

## Decision — accepted 2026-07-30

For this local `1.0.0-rc.1+26.1.2` scope, the owner selects the conservative
RC boundary rather than expanding the descriptor or renderer contract:

1. The decoder continues to validate the generic top-level `extensions`
   object only; it does not retain or expose a material-extension payload in
   `ModelDescriptor`, `ModelAsset`, or public API. A non-empty
   `extensions_required` remains a deterministic `BLENDLIB-EXT-001` rejection.
   No schema shape, size/depth/count limit, or capability-negotiation change is
   introduced by this RC.
2. The current ADR-014 material mapping remains active. In particular,
   unsupported additive/custom-pipeline intent remains a missing-model result
   with `BLENDLIB-MAT-004`; no custom `RenderType`/`RenderPipeline`
   construction, reflection, raw OpenGL, or private Minecraft/Fabric API is
   accepted for this RC.
3. The observed public-looking 26.1.2 bytecode signatures do not by themselves
   authorize a render-pipeline change. Any later expansion must begin with a
   new ADR that attaches exact local bytecode evidence, a bounded adapter-only
   API/lifecycle design, fallback behavior, and an isolated Iris/Sodium
   compatibility plan.

P7 may therefore complete only client-internal default-resolver,
generation/cache, reference-scene, and measurement work that preserves these
rules. This decision does not supply visual, Iris/Sodium, security, or
performance evidence and does not make P7 PASS.

## Preserved guardrails

- No schema, descriptor, core, or public API change.
- No acceptance of extensions_required.
- No additive/custom RenderType/pipeline construction, reflection, raw
  OpenGL, or private Minecraft/Fabric API use.
- No relaxation of current MAT-004 diagnostics or P4 rejection behavior.
- No P7/Iris/Sodium/visual/performance Gate PASS.

## Safe work that may continue

Client-internal default resolver/provider refactoring that preserves all
current material results; generation/cache-retention tests; deterministic
reference-scene/measurement harness work; and documentation of evidence gaps
may proceed without deciding this ADR.
