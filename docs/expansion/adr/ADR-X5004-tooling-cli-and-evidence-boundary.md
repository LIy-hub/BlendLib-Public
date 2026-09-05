# ADR-X5004: Keep X5 validation pure Java and distinguish headless from visual evidence

## Status

Proposed/Experimental implementation candidate. X5's isolated track was independently reviewed by /root/x5_postcommit_review_r3 (gpt-5.6-sol/max), Verdict: PASS with no findings. This local X1/X5/X9 integration candidate still awaits a fresh gpt-5.6-sol/max integration review; this status does not accept the proposal or change runtime scope.

## Context

The Blender exporter is Python/Blender-bound, but projects also need a
repeatable local validator that exercises the existing strict loader. A
headless registration check can prove addon state, while it cannot prove
interactive viewport layout or visual overlays.

## Decision

Add a no-dependency Java service/CLI below
`com.liy.blendlib.core.tooling`. It uses `ModelAssetLoader` for strict runtime
semantics and validates report/sidecar/PNG/hash contracts using local paths.
It validates the complete bounded sidecar mapping/runtime boundary and exact
runtime/sidecar-derived report counts, diagnostics, and warning subset. It
emits deterministic text or canonical JSON with stable exit codes; usage
failures use a fixed message and never echo an invalid argument or host path.
Ship an opt-in X5-owned Gradle script plugin that registers the real `JavaExec`
validator task without editing shared Gradle files; root integration is one
`apply(from = ...)` line owned by the integration task. Ship Blender-headless
registration and reversible preview-state verification; label interactive
visual proof as WAITING until a human session provides evidence.

The Gradle task supplies CLI arguments through typed task inputs and a
configuration-cache-safe argument provider. A standalone action retains the
empty-classpath diagnostic without capturing the applied script object. The
isolated fixture must prove cache storage/reuse for valid execution and cache
reuse for the validator's expected invalid exit on Windows classpaths.

The validator resolves regular files beneath authorized roots, stats and caps
authoring/runtime/PNG files before reading, and never allocates the declared
file size up front. All stream requests are at most 8 KiB; bounded JSON may be
accumulated dynamically, while PNG validation and artifact hashing remain
streaming and reject shrink/growth. A bounded request-level runtime-root list
defaults to the explicit resource root, `src/main/resources`, and
`build/resources/main`; realpath comparison rejects report/sidecar placement
through runtime-tree symlink or junction aliases. Report diagnostics must also
be unique and in the exact Python canonical sort order.

Python publishes the same compact canonical report bytes that Java hashes and
validates, with the inclusive 512 KiB cap enforced by bounded serialization.
Human-readable displays may format an already-bounded parsed document, but the
published file never uses extra pretty-print whitespace outside the cap.
Python also applies Java-compatible integer semantics to serialized counts:
exact non-negative integers within the signed `long` range, with booleans and
integer subclasses rejected before publication.

## Consequences

- Tooling remains platform-neutral and testable without Blender/Minecraft.
- Strict loader error codes are preserved rather than replaced by guessed X5
  equivalents.
- The isolated Gradle fixture executes the actual validator; root task exposure
  remains blocked only on the integration-owned apply line.
- No false PASS claim is made for UI visual quality.

## Alternatives rejected

- Put validation in the Fabric client: violates pure-core/tooling isolation.
- Use a third-party JSON CLI dependency: adds avoidable packaging surface.
- Treat headless registration as visual QA: it does not establish viewport
  behavior.
