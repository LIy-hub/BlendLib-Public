# Audit Finding 6 — conservative animated bounds remediation

Status: implementation and automated evidence only. Independent review is still
required. Real-client visual, two-client, Iris/Sodium, 20-reload, and performance
acceptance remain **WAITING**.

## Root cause

`ModelAssetLoader` calculated one AABB from primitive local bounds transformed by
rest-pose node transforms. `StaticRigidRenderHandle` and `SkinnedRenderHandle`
copied that value, `BlendEntityRenderer.getBoundingBoxForCulling(...)` used it for
the real entity frustum box, and entity/block-entity snapshot factories copied it
into `CullingMetadata`. A rigid node or skin joint could therefore move visible
vertices outside the rest AABB while vanilla still rejected the host.

## Implemented proof and bounded algorithm

`ConservativeAnimatedBounds` is a pure-core, load-time preparation step enforced
by every `ModelAsset` construction. For each node it takes the maximum rest/all-clip
translation norm `T` and positive uniform scale `S`. LINEAR and STEP samples stay
inside those extrema; cross-fade translation/scale is a convex interpolation of
two samples; unit quaternion rotation/slerp preserves vector norm. The canonical
hierarchy recurrence for a local point of radius `r` is therefore:

```text
A_child = A_parent + B_parent * T_child
B_child = B_parent * S_child
|point_world| <= A_child + B_child * r
```

Rigid geometry uses the maximum actual vertex radius. For skinning, each positive
influence first applies its affine inverse-bind matrix and uses the corresponding
joint recurrence. Strict weights are non-negative with positive normalized total;
the CPU-skinned output is consequently a convex combination bounded by the largest
influence result. Skinned primitives take this path even when no clip exists, so an
affine inverse bind cannot move the rest palette outside culling bounds. This covers
all decoded clips, descriptor states that reference them, and state blends without
temporal sampling.

Before conversion, the real-arithmetic radius receives a 1% relative plus `1e-4`
absolute runtime roundoff margin. Binary32 unit roundoff is `2^-24`; even a loose
100 rounded operations for every one of the maximum 256 hierarchy levels, plus
palette construction and four-weight accumulation, has the standard gamma bound
below 0.002. The retained 0.01 margin is therefore conservative. The double radius
is then converted to float with outward rounding whenever a normal cast would round
down, and unioned with the exact rest AABB. Rigid assets without clips return the
exact original AABB. Animated and skinned assets retain finite asset-local
culling rather than disabling culling. Work is linear in nodes, decoded key values,
and four influences per vertex, under the existing 4,096-node, 1,000,000-keyframe,
1,000,000-vertex, and 512-joint limits. Every recurrence, inverse-bind point, and
float conversion rejects non-finite or out-of-range values. Production loading
reports `BLENDLIB-LIMIT-001` at `/animations`, or `/skins` for a clip-free skin
envelope, before publication.

## Consumption and generation isolation

Both render-handle profiles convert the `ModelAsset` envelope only by the validated
`units_per_block` uniform scale. Entity culling unions that current handle bounds
with vanilla entity bounds. Static/rigid and synchronized animated entity/block-
entity snapshot factories carry that same handle bounds in immutable
`CullingMetadata`. Registry replacement binds the newly prepared handle and its
bounds atomically; a late stale generation cannot replace or mutate the active
generation envelope.

## Automated evidence

Java 25, `--no-daemon`, one worker:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :blendlib-core:test `
  --tests '*ConservativeAnimatedBoundsTest' --tests '*ModelAssetLoaderTest' `
  --rerun-tasks --console=plain
.\gradlew.bat --no-daemon --max-workers=1 :blendlib-fabric-client:test `
  --tests '*RenderContractsTest' --tests '*ProfiledRenderHandleReloadTest' `
  --tests '*EntityAdapterContractsTest' --tests '*BlendBlockEntityAdapterContractsTest' `
  --rerun-tasks --console=plain
```

Both focused commands completed with `BUILD SUCCESSFUL`. Coverage includes:

- rest-only exact AABB control;
- rigid motion beyond rest bounds across two clips and a blended pose;
- skinned inverse-bind/joint motion beyond rest bounds;
- skinned inverse-bind rest-palette motion when no clip exists;
- load-time overflow rejection with exact `LIMIT-001 /animations` diagnosis;
- prepared handle unit scaling without per-time handle sampling;
- entity frustum consumption and block-entity snapshot metadata consumption;
- new-generation envelope replacement and rejection of a late stale generation.

The full forced core/client suites and final project verification are recorded only
after they complete; no real client or server was started for this remediation.

## Final implementer verification

All commands used `JAVA_HOME=C:\Program Files\Java\latest\jdk-25`,
`--no-daemon`, and `--max-workers=1` where Gradle work was requested:

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :blendlib-core:test --rerun-tasks --console=plain
.\gradlew.bat --no-daemon --max-workers=1 :blendlib-fabric-client:test --rerun-tasks --console=plain
.\gradlew.bat --no-daemon --max-workers=1 :blendlib-showcase:test --rerun-tasks --console=plain
.\gradlew.bat --no-daemon --max-workers=1 check buildRelease --console=plain
git diff --check
```

Every command exited `0`. The forced XML aggregates were core 22 suites / 102
tests, client 33 suites / 140 tests, and Showcase 20 suites / 59 tests, with zero
failures, errors, or skips. The following SHA-256 values identify the XML files
left by the root coordinator's final forced full-project check after all six
findings reached local review PASS:

- `ConservativeAnimatedBoundsTest`: 5 tests,
  `e30cc24b603cf58da20ce6dc7354d94988e093213315264818912a164599eca7`.
- `ModelAssetLoaderTest`: 9 tests,
  `2234652b50b15cbc15634ed0fc3119e06d5c8c1c81a32f2eb4d0f0d0694627f9`.
- `ProfiledRenderHandleReloadTest`: 3 tests,
  `089a41051e7a4c6e492e0aea54a5e7ea403f48fa5d720f5f474648fb15f88273`.
- `EntityAdapterContractsTest`: 7 tests,
  `ca2630c216714c535fc2217059fa5cecad686bd241efdc5d8d3764183286a345`.
- `BlendBlockEntityAdapterContractsTest`: 4 tests,
  `0836f6f88263ced61c85f75cea9e78268aa668af5e3734ca452319e7c9e46660`.
- `RenderContractsTest`: 11 tests,
  `9f943726841bdad520c605429ac40da1a62807bb489be16eac8775d5fbc0b5f2`.

Project-level `check buildRelease` completed 62 actionable tasks (25 executed,
37 up-to-date). It performed only build/test/local packaging work; no Minecraft
client, server, official world, push, public publication, deployment, staging,
or commit was performed. Independent reviewer PASS/FAIL is not claimed here.
