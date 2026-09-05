# Beta.1 publication verification — 2026-09-06

Target: `1.0.0-beta.1+26.1.2`, Minecraft 26.1.2 Fabric. The owner explicitly chose a public
Beta.1 update with unaccepted areas disclosed; stable `1.0.0` is not the release target.
Publication destinations are the existing `LIy-hub/BlendLib-Public` repository and CurseForge
project `1638315`. The private development repository remains private.

## Source and review

- Integration baseline: `08effeab009a7dec9a5ba77e3c8da58ec5ac9fd6`.
- Coordinator changes: release version/consumer metadata, public copy, existing-license corrections,
  exporter 1.0.1 identity, and restoration of the public repository's existing Oracle/OpenJDK
  Javadoc legal-layout handling from `aa9a239` / `1aef1d4`.
- Independent reviewer: Arendt (`01a072bf-174d-7c02-902b-07ae148fcbfe`), read-only and separate
  from implementation. The first bounded source review found no new runtime blocker. It required
  correction of stale license descriptions and a private source URL; those were corrected.
- Final release-diff/artifact review: **PASS, no unclosed release blockers**, bound to staged tree
  `2a29691add82afa64d887e5c1b6dbf95192a0d0a` (21 staged paths, zero unstaged changes).
  The independent reviewer authorized this result-only record update without another build and
  confirmed GitHub prerelease + CurseForge Beta publication with the disclosed acceptance limits.

## Fresh verification

`JAVA_HOME=C:\Program Files\Java\latest\jdk-25`;
`gradlew.bat --no-daemon --max-workers=1 clean check buildRelease --console=plain` exited 0.
The build completed in 1m11s with 75 tasks (54 executed, 21 from cache).
The detached local-Maven consumer completed its own compile, test and dependency-boundary check.

- Java XML: 184 suites, 1,337 tests, 0 failures, 0 errors, **1 skipped**. The skipped
  `X7ArtifactVerifierTest` case could not create a symlink on this Windows host; that case
  is not claimed as passed.
- X5 Python unit tests: 97 tests, all passing. The Beta channel change did not alter Add-on source;
  this test result and the registration result below are retained from the same-source preparation run.
- Blender 5.1.2 with factory startup: `BLENDLIB_X5_HEADLESS_OK register_unregister`, exit 0.
- Release archive, local Maven, source/Javadoc, Add-on, inventory and negative-fixture verifiers passed.
- An independent script rehashed all seven manifest entries and checked outer/nested version and
  license metadata, exact canonical icon bytes, Showcase dependency, Add-on 1.0.1/GPL identity,
  and exclusion of runtime authoring files. All checks passed.
- A known-credential-signature scan of 1,360 tracked source files found no matches. This is a
  bounded scan, not an exhaustive security guarantee.

The first incremental check failed because three historical API JAR versions remained in
`build/libs`, violating the fixture's exact-one-JAR assumption. Its evidence was retained;
the subsequent native clean build removed that ambiguity and passed without weakening the test.

## Installed packaged-JAR server smoke

The exact release runtime plus Fabric API were installed in a new isolated directory, with an
independent Fabric 0.19.3 launcher and Minecraft 26.1.2. Only these two mods were installed.
The server bound to `127.0.0.1:25589`, with offline mode, RCON/query disabled, a new temporary
flat world and low view/simulation distances. It reached `Done (0.542s)`, accepted console
`stop`, saved all dimensions and exited 0. The complete process took 11.19s and released the port.
No production mods, configs or world data were used or modified.

This proves installed runtime/server class loading and startup/shutdown for this artifact.
It does not prove an installed client, network synchronization or rendering.

## Artifact identities

| File | SHA-256 |
| --- | --- |
| blendlib-fabric-1.0.0-beta.1+26.1.2.jar | `9521c0b56688dc14bf9840cbde45c76fd66df3fdd60d14d28ff8c21e439779ed` |
| blendlib-showcase-1.0.0-beta.1+26.1.2.jar | `09832ede2583c05242560dc544cca06806f444ab50d899100b63e85bbb6b433d` |
| blendlib-fabric-1.0.0-beta.1+26.1.2-sources.jar | `90353ef2e59f7827fcee5c4439c07b5e02d3177678dfca7577df8f665fabaefc` |
| blendlib-fabric-1.0.0-beta.1+26.1.2-javadoc.jar | `bcf56a444bc926ba5c2c00b90ee735a8a3c9e55f66ec58a3cdef51ff19e9bf5f` |
| blendlib-exporter-1.0.1.zip | `cc741e7c418cea4cba69e44844eded12bdc292892300fa549ed0917c961b4e79` |
| dependency-inventory.txt | `b7c780996e6acfd2c6f4fa7926921dc41ee77971eafe34b0689489cbe7bb507a` |
| license-inventory.txt | `0f7b2ae996a7057c08cfc164d831971392e17e5a70f9c5a1ee47231c63d36075` |

Runtime size: 2,574,879 bytes. `SHA256SUMS` itself:
`c7d827ee4164f2441720e70a2dba9ed4e6b8e1260751071a404f7bd8842d81b2`.

## Retained evidence

Local evidence directory: `D:\BlendLib-release-evidence\20260906-beta1`.

| Evidence | SHA-256 |
| --- | --- |
| clean-check-buildRelease.log | `83870941d2674221f8b49a0c981252645748d0c07b33c7fb4874817b15a1b8a0` |
| packaged-server-console.log | `6f45c8c3eca2d19b3e463268ff9f832b687051954cbd69126b0c27e3c9d7d684` |
| x5-python-tests.log | `5f308cc91b0542cc04103d3c2f9fff9dbe22f4851816c495362d9491f632689e` |
| blender-registration.log | `cac74c723102ec433b13c32b3d8676f6dbd925035996c175e0fdb1ae52cd0115` |

## Unaccepted areas

The prior owner [Showcase visual PASS](../evidence/merged-showcase-user-visual-pass-2026-09-06.md)
is bounded to that earlier ordinary entity session. Full material/item/block-entity visuals,
an externally installed release-client session, two-client synchronization, 20-reload leak checks,
Iris/Sodium, hardware/GPU performance, 26.2/NeoForge and aggregate P3–P8/X1–X9 acceptance remain
unaccepted. Public Beta publication does not promote these statuses or promise stable API/ABI.
