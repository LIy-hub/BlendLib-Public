# X5 pure-Java validator and Gradle integration

Status: X5's isolated track was independently reviewed by /root/x5_postcommit_review_r3 (gpt-5.6-sol/max), Verdict: PASS with no findings. Fresh formal reviewer /root/formal_x1_x5_x9_review_r2 (gpt-5.6-sol/max) returned Verdict: PASS for the formal tree's X1/X5/X9 integration and remediation only, with no Critical/High/Medium findings; its one Low was the contradictory root-task wording corrected by this metadata-only commit. In `build.gradle.kts:24`, the root build applies this script exactly once and `validateBlendlibAsset` is present and callable. It remains opt-in and is not a Blender UI, viewport, Minecraft client, or visual acceptance claim. This metadata-only commit itself still requires a fresh gpt-5.6-sol/max review and does not unlock downstream tracks.

`com.liy.blendlib.core.tooling.AssetValidatorService` is pure Java and uses the
existing `ModelAssetLoader` for descriptor/GLB semantics. It validates external
PNG resources, X5 sidecar/report formats, canonical sidecar/report hashes, and
project-relative artifact hash entries. It does not read a `.blend`, use
Minecraft/Fabric classes, or implement a runtime reload hook.

All local inputs have pre-allocation limits: authoring JSON is 512 KiB and each
descriptor/GLB/PNG is 64 MiB. Runtime and PNG hashes are computed with bounded
streaming buffers; unknown report artifact keys are rejected without opening
their target. Every read request is at most 8 KiB and is checked against both
the pre-stat size and cap; bounded JSON accumulation is dynamic rather than a
declared-size allocation.

`AssetValidationRequest` also owns a bounded list of runtime roots. Both its
five-argument compatibility constructor and six-argument explicit-root form
merge the explicit resource root, `src/main/resources`, and
`build/resources/main` with caller roots, then deduplicate and enforce the
16-root cap. Supplying a custom list therefore cannot remove defaults. Before parsing
or hashing, resolved report and sidecar files are rejected if they are beneath
any root, including through a symlink/junction alias. The ordinary CLI uses
these safe defaults without adding a host-path option.

The command-line main class is
`com.liy.blendlib.core.tooling.AssetValidatorCli`:

```text
--project-root <path> --model-key <namespace:path>
[--resource-root <relative>]
[--authoring-root <relative>]
[--report <relative>] [--sidecar <relative>]
[--format text|json]
```

Exit code `0` is valid, `2` is an invalid bundle, `64` is CLI usage failure,
and `70` is an unexpected bounded tooling failure. JSON output is canonical
sorted-key one-line UTF-8 text and never echoes the host project path. Usage
failures also emit one fixed bounded message plus the fixed usage synopsis;
invalid `Path.of` input, unknown options, and secret-bearing argument values
are never reflected into stderr.

## Executable Gradle integration

X5 owns the applied script plugin at
`gradle/blendlib-x5-asset-validator.gradle.kts`. It registers the real
`validateBlendlibAsset` `JavaExec` task, uses the `:blendlib-core` project
classpath when present, depends on core classes, and forwards only explicit
Gradle properties to `AssetValidatorCli`. Its typed command-line argument
provider and standalone classpath check hold no Gradle script-object reference,
so valid and validator-invalid executions support Gradle configuration-cache
storage and reuse. The isolated fixture under
`test-assets/x5/gradle-validator-fixture` can instead receive
`-PblendlibValidatorClasspath` and has executed the real CLI against a Blender
export bundle. Its `verify-configuration-cache.ps1` runner uses a fresh
project-cache directory and proves two valid runs plus two expected-invalid
runs; the latter retain CLI exit-2 semantics while reusing configuration.

The integration-owned shared change is exactly one root-build line, applied exactly once by the formal tree at `build.gradle.kts:24`:

```kotlin
apply(from = "gradle/blendlib-x5-asset-validator.gradle.kts")
```

Then invoke, for example:

```text
gradlew validateBlendlibAsset \
  -PblendlibAssetProjectRoot=D:/project \
  -PblendlibAssetModelKey=example:model
```

Optional properties mirror the CLI: `blendlibAssetFormat`,
`blendlibAssetResourceRoot`, `blendlibAssetAuthoringRoot`,
`blendlibAssetReport`, and `blendlibAssetSidecar`. The root
`validateBlendlibAsset` task is present and callable, but remains opt-in: it is
not wired into `check` or `buildRelease`. This is a CLI/Gradle validation
connection only; it does not imply Blender UI/viewport, Minecraft
listener/client, or visual acceptance.
