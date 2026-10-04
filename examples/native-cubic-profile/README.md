# Native cubic skinned preview

This separate summonable entity uses the actual Blender 5.1.2 export at
[`test-assets/native-cubic`](../../test-assets/native-cubic). Its descriptor opts into
`format_version: 2`, `profile: blendlib:skinned_cubic_v1`; it does not alter the strict-v1
loader/export defaults or any existing example entity. This is an unreleased feature-branch
preview, not a main-branch release.

## Build and summon on Minecraft 26.3

From the repository root, using Java 25:

```sh
./gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true \
  -Pblendlib_preview=cubic-preview verifyRunnableExamples
./gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true \
  -Pblendlib_preview=cubic-preview runRunnableExamplesClient
```

Install matching `blendlib-fabric` and `blendlib-runnable-examples` preview JARs together.
In a Creative test world with commands enabled:

```mcfunction
summon blendlib_runnable_examples:native_cubic_actor ~ ~ ~
summon blendlib_runnable_examples:native_cubic_actor ~2 ~ ~
```

The dedicated renderer always selects `native_cubic:eased_actor`. No JVM scene switch is
needed. The pre-existing `layered_actor` and all its optional scene switches still behave
as before. The actor cycles through the authored `wave` and one-shot `once` state through
ordinary layer cues, with a gold marker attached to `native_cubic:tip`. The wave clip emits
`native_cubic:apex` through the normal layer visual-event callback. Each actor has its own
playback and event state; the prepared model/curve data is shared.

The visible deformation comes from sparse cubic translation, uniform scale, and quaternion
channels. Their tangent derivatives are stored in seconds and evaluated directly by the
same CPU skinning path as the existing skinned profile. The exporter documents unsupported
curve/constraint fallbacks instead of claiming arbitrary Blender preservation.

## Verification and boundaries

- `NativeCubicRuntimeIntegrationTest`: exact off-key overshoot vertices/sockets/bounds,
  ordinary controller/crossfade, masked additive layers, dynamic appearance, 1D/2D blendspaces
  with cadence, visual events, independent instances, frozen captures, reload and lifecycle
- `NativeCubicShowcaseReloadTest`: production resource reload of the real export alongside an
  unchanged strict-v1 model, byte-identical showcase resources, and stale-generation rejection
- Blender oracle/source/preview: `test-assets/native-cubic/oracle.json`, `source.blend`, `preview.png`

Headless asset, CPU geometry, and package checks do not establish native Minecraft visual
acceptance. In-game screenshot/visual QA is still deferred. Socket markers and visual events
are presentation-only; collision, damage and other gameplay remain server-owned.
