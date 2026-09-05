# BlendLib offline model converter

This directory contains the standalone, standard-library Python converter used by the X8
ecosystem materials. It accepts Blockbench project JSON and GeckoLib geometry/model JSON as
offline authoring inputs and emits the only runtime contract BlendLib accepts: a strict,
versioned BlendLib descriptor plus GLB 2.0 and external texture references.

The tool deliberately has no Gradle, Minecraft, Fabric, GeckoLib, Java, network, or runtime
dependency. GeckoLib is an input dialect name only; no GeckoLib class, JAR, loader entrypoint,
or runtime dependency is introduced.

Run the command below from a local checkout with Python 3.11 or newer:

    python tools/model-converter/blendlib_model_converter.py --help

Read [converter guide](../../docs/expansion/x8/converter-guide.md) before converting production
assets. The guide defines supported source constructs, fail-closed errors, output layout,
review steps, and the boundary between offline conversion and BlendLib runtime loading.

The converter is intentionally not invoked by Gradle or any mod entrypoint. It performs no
network access, rejects URI-like texture paths and output traversal, bounds external input count
and cumulative bytes as well as model complexity, writes generated text as UTF-8, and uses
deterministic object ordering. Existing model, descriptor, texture, and report files are refused
by default; `--force` is the explicit replacement opt-in after directory/reparse-point safety
checks and a final publication recheck. Read the guide for the strict field-loss, scale, and
source-unit policy before use.
