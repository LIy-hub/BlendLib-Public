# P7 reference performance scene

This directory records the frozen target, not a benchmark result:

- 100 visible rigid instances, each with 10,000 triangles;
- 25 visible skinned instances, each with 20,000 triangles and 64 joints;
- 600 warm-up frames and 1,800 recorded frames;
- JFR or a profiler must record p50/p95 frame time, animation preparation,
  submit CPU, live allocation, cache observations, and generation handle observations.

The binary GLB fixtures are intentionally not checked into ordinary Showcase resources. Generate
the deterministic isolated resource-pack bundle with:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-25'
.\gradlew.bat :blendlib-showcase:generateP7ReferenceAssets
```

The task writes only
`D:\BlendLib\blendlib-showcase\build\generated\p7-reference-assets`. Copy that explicit
bundle into an isolated client run's resource-pack location, add the matching benchmark-only
entity integration, and verify all 125 target instances are visible at the frozen camera before
sampling. It must not be copied to a formal world or server, and this directory does not make a
visual or performance Gate pass.

## Accepted capture preflight

For the fixed 26.1.2 P7 protocol, the active operator command is:

```text
/tp @s 0 67 24 180 0
```

It addresses the invoking player and must yield the checked player centre
`(0.5, 67.0, 24.5, yaw=180, pitch=0)`. Before the controller can begin its
unchanged 600-frame warm-up, use a `1920x1080` 16:9 framebuffer/render target,
FOV `90`, dynamic FOV disabled, and an effective render distance of at least
eight chunks. These preflight steps do not constitute a capture; P7 remains
`WAITING` until a fresh isolated run records every required 100/25 submission
and the separate performance and visual evidence.
