# BlendLib X5 test assets

These assets cover authoring-only X5 contracts.  They are not packaged as
Minecraft runtime resources and are never used by the strict-v1 loader.

- `python-tests/valid_snapshot.json` is a machine-independent source snapshot
  for deterministic preflight, sidecar, and report tests.
- `python-tests/test_x5_toolchain.py` uses only the Python standard library;
  it imports the package-worthy add-on module through a source-path seam.
- `static-batch-manifest-v1.json` deliberately orders two static-fixture
  outputs backwards; batch planning proves canonical model-id ordering before
  one atomic publication bundle.

The Blender registration check remains `blender-addon/scripts/verify_x5_toolchain.py`
because it must be executed by Blender itself.  It verifies register/unregister
state only; interactive viewport evidence is intentionally not implied.

`blender-tests/verify_action_and_preview_contracts.py` is the real-Blender
contract matrix for excluding unrelated fake-user Actions, retaining bound and
NLA Actions, and applying/restoring concrete model/bone/socket/normal/material/
timeline debug state. `gradle-validator-fixture` applies the X5-owned script
plugin as an isolated build; passing `blendlibValidatorClasspath` lets it run
the actual pure-Java CLI without modifying the shared root build. Its
`verify-configuration-cache.ps1` runner uses a fresh project cache and checks
two valid plus two expected-invalid executions, including configuration-cache
reuse and a Windows path-separator classpath.

`blender-tests/verify_skin_binding_contracts.py` uses Blender 5.1.2 scenes to
prove rigid decorative groups remain non-runtime, legal skinned binding/report
counts match the strict GLB, positive/zero decorative groups remain ignored,
multiple meshes may share one exported Armature, and missing/multiple/wrong/
profile/bone-count/non-normalized bindings block sidecar, one-click, batch, and
UI paths without creating a stage or publication root. The generated rigid and
skinned bundles are also inputs for the pure-Java CLI parity gate.

`blender-tests/verify_report_and_batch_contracts.py` proves that default and
explicit report paths contain identical `blendlib-x5-asset-report-v1` bytes,
late-invalid sorted batches call no exporter and create no project root, valid
batches are order-independent, and a staged export failure publishes nothing
while preserving prior project bytes.
