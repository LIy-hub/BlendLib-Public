# Named texture skins verification

## Local final-source results

- Official Minecraft 26.3: 246 tests pass; runtime JAR and runnable-example verification pass
- Focused skin/appearance/ABI coverage: 50 tests pass
- Root aggregate: 1,533 tests; 737 client and 324 core tests pass; one known inherited Linux symlink diagnostic assertion failure in `X7ArtifactVerifierTest`, plus one existing skip
- Exact retained public ABI pins and additive new-surface signatures pass
- Independent critical source review: no unresolved findings after fixing catalog rejection of unused but valid authored material slots
- `git diff --check` passes

The packaged example probe checks the actual Ember/Frost PNG assets and descriptor/GLB slots, entity/wand selectors, shared CPU-skinned capture, RGB/visibility composition, reload mapping and authored fallback. Texture existence checks in production do not claim decoding validation; the tiny authored example PNGs are separately verified by the packaged probe.

## Commands

```sh
bash gradlew -p versions/modern -Pminecraft_version=26.3 -Prunnable_examples=true \
  --no-daemon --max-workers=2 build verifyRuntimeJar verifyRunnableExamples
bash gradlew check --continue
```

The local restricted execution environment uses its inherited Java 25/no-Unix-selector startup shim and a quiet test JVM init script. Remote GitHub workflows use official tooling without that local shim. Exact-commit remote status and per-job results are shipped separately in the delivery evidence after all workflows become terminal.

## Scope and limits

No dependency, descriptor, schema, wire-protocol, material-route, shader or texture-manager ownership change. No X6 provider/lease path or new geometry primitive ceiling. Old render-handle identities and public constructors remain intact. Skins freeze startup registration, validate resources at prepare, compile generation catalogs and capture extraction-time names only.

Native graphics/window/world acceptance remains deferred by the user and is not claimed. No merge, tag, release or CurseForge upload was performed.

Compatibility follow-up: the first remote matrix identified stale legacy reload/item templates. Those templates now preserve startup snapshots and both selectors, with all-port registration/constructor regressions. Local legacy source preparation succeeded; full local Java 21 compilation was unavailable because this environment contains only a Java 21 runtime, so exact-version legacy compile/JAR/test acceptance is established by the final remote matrix evidence.
