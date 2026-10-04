# CPU morph preview verification

October 4, 2026; `feat/mc26.3-cpu-morph-profile`, cumulative batch 28.
Local preview complete. Publication of the new feature branch and its exact-commit
remote CI are pending explicit approval to publish the generated editable
`test-assets/cpu-morph/source.blend` fixture. No remote CI PASS is claimed for this
implementation. The package's `BUILD_IDENTITY.json` records this pending status,
local commit/tree identity, artifact hashes and local evidence.

## Local verification

- Core: 401 tests pass, including loader/budget/interval proofs, controller events,
  corrected inverse-transpose normal math and the independent Blender oracle
- Client: 927 tests pass, including complete defaults, weight-only clips, full-body
  transitions, atomic frame/cue failures, cadence-held inputs, layer/blendspace
  exclusions, independent actors, topology/materials/bounds, CPU-only provenance,
  production reload, stale generations and lifecycle
- Pure API 354; API consumer 7; common 21; Fabric consumer 8, all pass
- Official Minecraft 26.3: 479 tests pass; build, verifyRuntimeJar and
  verifyRunnableExamples pass, with all previous runnable consumers retained
- Runtime, sources and consumer JARs use `1.0.0-beta.4+26.3-morph-preview`
- Blender 5.1.2 extension validation/package pass; 71 Python tests pass
- 26 real Blender edge cases cover source rejections, Key/Object Action slots,
  fractional FPS, weight-only authoring, explicit approximation fallback, defaults
  restoration, UI state/socket editing and X5 strict exclusion
- Static, rigid, skinned and native-cubic old export GLB/descriptor hashes match
  the baseline byte-for-byte; frozen old schemas and pure API sources are unchanged

The real fixture has Blink, Smile and Breath keys, two material primitives, a
nonzero Breath default and genuine two-joint chest skinning. Nod remains a sparse
three-key native cubic quaternion clip; the other three clips are weight-only.
Repeated exports are deterministic.

The independent Java oracle checks 60 key/off-key samples, 15,840 vertices,
180 weights and 120 bone matrices. Maximum component errors: position and matrix
2.39e-7, normal 1.20e-7, weight 8.95e-8 (tolerance 2e-5). Positions/weights come
from Blender evaluation; normals use separately identified glTF delta/skin math.
FLOAT time quantization at STEP discontinuities is explicitly recorded. Bezier
weight fallback is approximate, with measured maximum weight error about 0.00436
and position error about 0.000211 in the edge fixture.

The root aggregate retains the previously known sandbox-specific
`X7ArtifactVerifierTest.symlinkEscapeFailsClosedWhenTheFilesystemPermitsPortableSymlinks`
diagnostic-reason mismatch and one host-dependent skip. The unsafe artifact is
still rejected. This is not relabeled as a local aggregate pass; exact-commit
remote root CI has not run for the unpublished implementation and must be checked
before public-delivery completion.

## Focused review and corrections

The focused review reproduced and closed three material gaps:

1. Near-affine inverse binds could invalidate the morph interval bound through
   homogeneous division. CPU morph now requires exact affine fourth rows;
   old-profile tolerance is preserved
2. The initial allocation budget omitted palette and some expanded snapshot
   copies. Metadata preflight now includes these in both Java and the exporter
3. Exporter name checks could accept strings rejected by the runtime. Bounded
   UTF-16 lengths and control-character checks now match

The consumer uncovered a duplicate loop event at a floating-point boundary;
matching lower/upper epsilon rules fixes it. The final two-joint normal oracle
also exposed an inherited cofactor-determinant error. CPU float/double paths,
existing X7 mirrors and reference tests now use the correct expansion. Independent
Gaussian-elimination checks on 200 well-conditioned matrices agree (double error
1.12e-16, float error 2.00e-8). These are skinning correctness fixes, not GPU morph
support. New focused regressions pass.

## Reproduce

```sh
bash gradlew check --continue
python -B -m unittest discover -s blender-addon/tests -v
blender --background --python-exit-code 1 --python blender-addon/scripts/create_cpu_morph_fixture.py -- --project-root .
blender --background --python-exit-code 1 --python blender-addon/scripts/verify_cpu_morph_edge_cases.py -- --project-root .
bash gradlew -Pblender_executable=/path/to/blender packageBlenderAddon
bash gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true -Pblendlib_preview=morph-preview build verifyRuntimeJar verifyRunnableExamples
```

Native Minecraft display, visible morph quality and GPU visual acceptance remain
deferred. Animation-free exports are low-level data support; the standard animated
builder requires a real animation declaration. No main merge, tag, release,
CurseForge upload, EULA or security-setting change is included.
