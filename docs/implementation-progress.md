# BlendLib Implementation Progress

This ledger is authoritative for phase recovery. Read it before dispatching or resuming work.

## Public Beta.1 preparation — 2026-09-06

The owner explicitly authorized GitHub and CurseForge publication, then selected
`1.0.0-beta.1+26.1.2` with unaccepted areas disclosed. The candidate derives from
`08effeab009a7dec9a5ba77e3c8da58ec5ac9fd6`. Fresh clean check/buildRelease, installed packaged-JAR
server smoke, seven artifact hashes, X5 Python tests and Blender registration checks passed;
one Windows symlink test was skipped and is disclosed. Independent release review and exact
artifact/evidence identities are recorded in [Beta.1 verification](./release/beta1-verification-2026-09-06.md).
This scoped public Beta update does not advance any stable, aggregate, visual, reload, compatibility
or performance Gate. The existing private development repository remains private.

## Scoped project branding adoption — 2026-09-05

The owner approved the folded B symbol, Minecraft-inspired pixel wordmark, and transparent/white
variants as the official project logo resources. Canonical PNGs, generation provenance, usage,
SHA-256 hashes and [verification evidence](./assets/branding/verification.md) are stored in
`docs/assets/branding/`. The runtime icon and documentation logo now use their canonical white
variants; README links expose the official assets. Client module build passed (37 suites,
161 tests, zero failures/errors), and packaged-icon hash identity was verified. Native client
startup reached render/audio/atlas initialization after online asset download. The owner then
explicitly accepted this logo integration as PASS and authorized commit/push; no agent UI
inspection is claimed. This scoped branding maintenance does not advance any phase Gate.

## Coordination authorizations and accepted owner decisions — 2026-07-30

The user authorized continued implementation while the separate audit is
handled by them. Implementation may therefore continue on non-conflicting
later work packages while that audit remains pending. This is not a Gate
waiver: no phase may be recorded as PASS, staged as a passed phase, or
committed as a passed phase without its required evidence.

On 2026-07-30, the owner explicitly accepted the then-Proposed decisions:

- ADR-016 preserves the strict current descriptor/material mapping for this
  RC and defers descriptor extension retention, additive support, and custom
  pipeline construction.
- ADR-017 adopts the exact true-in-frustum P7 100/25 layout, camera, FOV, and
  resolution contract recorded in that ADR; source/manifests/tests must still
  implement it before another capture.
- ADR-018 adopts the isolated `p5_fallback_actor` no-sync fixture, leaving the
  normal actor and all P6 payload/sequence/tracking behavior unchanged.
- ADR-019 corrects the P4 exact-0.10 single-sided cutout rows to the already
  verified public `entityCutoutCull` supported path. Its ADR/fixture/test/manual/
  evidence/ledger alignment is required before fresh isolated observations; it
  does not authorize a mapper/backend change or a visual/Gate inference.
- ADR-020 corrects only the P7 local-player teleport spelling to
  `/tp @s 0 67 24 180 0`. After its ADR/source/manifest/golden/manual/evidence/
  ledger alignment, one fresh isolated P7 capture may use that exact command;
  all camera coordinates, target work, and capture thresholds are unchanged.

These are implementation-change authorizations, not Gate waivers: no phase
may be recorded PASS, staged as a passed phase, or committed as a passed phase
without its required automated and real-client evidence.

## Independent audit takeover and repair authorization — 2026-08-01

Independent audit thread `019fb323-b710-7140-8d19-4b42d76bd754` concluded
`FAIL` against branch `Liy/blendlib-v1`, HEAD `7d88c85`, and the stable
489-file snapshot digest
`982148ae0b75a5fb00830e3c993999712ba3786a41f289af5faa90eca9c19935`.
The audit severity labels below are findings, not implementation phase numbers:

- P1: descriptor state `speed` has no safe upper bound and looped visual-event
  advancement has no cycle/event budget.
- P1: synchronized payload speed and descriptor state speed use inconsistent
  correction versus continuous-advance formulas when state speed is not 1.
- P1: a deferred client payload callback can deliver data received in level A
  into replacement level B.
- P2: strict GLB accessor validation is incomplete for `normalized`, `min`,
  and `max` semantics and required uses.
- P2: skin validation is incomplete for joint hierarchy/root, `skeleton`
  ancestry, duplicate non-zero influences, and inverse-bind shape/count rules.
- P2: culling uses rest-pose bounds and can reject rigid or skinned animation
  that moves outside those bounds.

The owner has now accepted ADR-021. Its scope is exactly the client-private
receive-time `ClientLevel` object-identity guard described by that record; it
does not alter the v1 payload, public API, wire format, or version. A recovery
snapshot was completed at `D:\BlendLib-recovery\20260731-004030`; its binary
working-tree patch SHA-256 is
`188a5e2be393a7ddd60381ccd9e3cc5e6e6c6b03429be488e2e29df5a4b3d861`.
The orphaned old-session P8 isolated client chain was identified and stopped
before repair writes; two subsequent observations retained the exact 489-file
audit digest. At takeover, overall status remained `FAIL` until all six repairs
had distinct implementer/reviewer PASS evidence and the existing independent
audit thread explicitly returned PASS. P3--P8 visual, dual-client, Iris/Sodium,
20-reload, and performance Gates remained unchanged and could not be promoted
by code review.

### Audit-repair local Gate — 2026-08-01

All six audit findings now have a completed implementer change and a distinct
local reviewer `PASS`. Finding 2 first failed review on a descriptor-speed-scaled
timeline epsilon mismatch and passed after return to its original implementer.
Finding 4 passed only after three review returns closed unused-accessor bypass,
aggregate-budget/declaration-limit evidence, and a rejected global JSON-array
limit relaxation. Finding 6 first failed only because its recorded XML hashes
were stale and passed after the original implementer refreshed the retained
evidence without rerunning tests. The accepted local results are:

- Finding 1: finite speed/state/event limits and closed-form bounded loop/event
  advancement — local reviewer `PASS`.
- Finding 2: unified `real delta * network speed * descriptor state speed`
  timeline for trigger, correction, replay, and continuous advance — local
  reviewer `PASS` after one repair return.
- Finding 3 / ADR-021: receive-time `ClientLevel` identity guard shared by both
  payload receivers — local reviewer `PASS`.
- Finding 4: strict `normalized`/`min`/`max`, usage, unused-accessor, extrema,
  fixture, and bounded-validation rules — local reviewer `PASS` after three
  repair returns.
- Finding 5: joint hierarchy/common ancestor, `skeleton`, influence, palette,
  and inverse-bind validation — local reviewer `PASS`.
- Finding 6: finite load-time conservative animated bounds for rigid, skinned,
  blend, culling-consumer, and reload-generation paths — local reviewer `PASS`
  after one evidence-only repair return.

The root coordinator then ran the required Java-25 verification and retained
logs/XML outside the repository at
`D:\BlendLib-recovery\20260731-004030\final-verification-20260801-060850`.
`clean check`, the separate `:blendlib-core:test`, `buildRelease`, all four
finding-targeted test groups, and a final forced full `check` exited `0`.
The final forced XML aggregates are API 12, API consumer 1, core 102, common
10, client 140, Fabric consumer 2, and Showcase 59 tests, all with zero
failures, errors, or skips. The complete final XML manifest SHA-256 is
`52b041849601ff888dc1d59d3d88c275bbcbf8e9f263add6d0c6a982f8d3dc3b`.
Exact commands, log hashes, targeted XML manifests, and the remaining Gate
state are recorded in
`docs/evidence/audit-repair-final-verification-2026-08-01.md`.

Independent audit thread `019fb323-b710-7140-8d19-4b42d76bd754` completed its
read-only rereview at 2026-08-01 06:54 +08:00 and explicitly returned overall
`PASS` for the six audit findings. It independently rehashed the 270022-byte
`exact-repair.diff` as
`0e1a3e5a78d902d50383941a7b9ddea452ae75c815040badf2cd8cecdddd258a`,
verified the 57-file set as 49 modified + 8 added + 0 deleted, and found zero
mismatches across the repository, retained current copies, and recovery
baseline. It also verified 87/87 final XML files, 326 tests with zero failures,
errors, or skips, all exit-code sidecars at zero, and no audit-time branch,
HEAD, repair-package, or target-file drift. The independent conclusion states
that no remaining P0/P1/P2 safety, correctness, or architecture issue was found
and all six original findings are closed.

This independent PASS closes only the six-item audit-repair Gate. P3--P8 phase
status is not promoted; all real visual, two-client, Iris/Sodium, 20-reload,
and performance acceptance remains `WAITING`. No phase-PASS commit is
authorized by this audit result.

## Cross-phase isolated server-harness preparation — 2026-07-30

P3, P4, P5, and P7 now each have an opt-in Showcase Loom server task with a
separate run directory, `127.0.0.1` endpoint, temporary world, disabled RCON,
and template-key mismatch refusal: `runP3SmokeServer`/25571,
`runP4SmokeServer`/25572, `runP5SmokeServer`/25573, and
`runP7SmokeServer`/25574. The root-run Java-25 static contract test completed
`4 tests / 0 failures / 0 errors` in 45 seconds and verified all four templates,
run-directory separation, and no-overwrite safety behavior. No new run directory
was created, all four ports were free after the test, and no Minecraft server or
client was started by that static test. Later on 2026-07-30, root executed the
four harnesses serially with bounded heaps: P3/P4/P5/P7 reached
`Done (5.723s/4.945s/2.426s/3.235s)`, accepted console `stop`, saved all
dimensions, returned Gradle exit `0`, and released ports 25571--25574. Exact
hashes are in the four phase-specific smoke evidence files. Those observations
close only the dedicated-server item for each phase; all audit, client, visual,
synchronization, reload, and performance statuses remain unchanged.

The user also authorized a non-public, local-metadata-only temporary
`LicenseRef` if an artifact needs one. It is not a license selection, does not
authorize redistribution or publication, and does not alter `LICENSE-PENDING`.
GPL-3.0-or-later remains limited to `blender-addon/` exactly as ADR-009
records. No push, public release, production deployment, or formal-server/world
operation is authorized.

## P0

Phase: P0
Status: PASS
Owner: root coordinator; p0_implementer completed repository initialization and approved-source copies
Reviewer: p0_reviewer
Commit: chore(p0): freeze BlendLib v1 contract (local root commit; exact hash is recorded by Git history)
Changed files: .gitattributes, AGENTS.md, CHANGELOG.md, LICENSE-PENDING, docs/README.md, docs/architecture.md, docs/design-v1.md, docs/implementation-plan.md, docs/adr/ADR-001.md through ADR-008.md, docs/adr/README.md, docs/api-stability.md, docs/contract-baseline.md, docs/error-codes-v1.md, docs/evidence/P0-gate.md, docs/evidence/P0-precheck.md, docs/glb-profile-v1.md, docs/implementation-progress.md, docs/source-design-provenance.md, schemas/blendlib-model-v1.schema.json, test-assets/descriptor-example-v1.json
Automated tests: PASS - copied-source SHA-256; standard JSON parse; Draft 2020-12 schema and example validation; rigid/skinned positive cases; schema rejection of bad format version, unknown top-level field, and unknown profile; whitespace and git diff checks
Build: Not applicable; P1 owns Gradle scaffolding
Dedicated server: Not applicable; P1 owns first isolated dedicated-server smoke
Client/manual: Not applicable; no visual claim
Performance: Baseline acceptance frozen; benchmark deferred to P7
Known gaps: Wrapper/Loom/26.1.2 client API compatibility is a P1 precondition; no release/legal decision is authorized; no server/client/performance verification is claimed for P0
Next action: Stage only P0 files, create the local P0 commit, then begin P1

## P1

Phase: P1
Status: PASS
Owner: p1_implementer; remediation by p1_implementer; integration by root coordinator
Reviewer: p1_reviewer
Commit: feat(p1): establish modular Fabric skeleton (local focused commit; exact hash is recorded by Git history)
Changed files: .gitignore, Gradle 9.6.0 wrapper and root build settings, blendlib-api, blendlib-core, blendlib-fabric-common, blendlib-fabric-client, blendlib-showcase, blendlib-api-consumer-fixture, docs/evidence/P1-implementation.md, docs/implementation-progress.md
Automated tests: PASS - clean check and targeted core test completed; source-boundary tests execute through check; reviewer remediation adds Showcase compile/runtime dependency-boundary verification
Build: PASS - buildRelease completed with all P1 checks, Showcase dependency-boundary verification, and client/showcase remapJar compatibility tasks
Dedicated server: PASS - post-remediation independent isolated `runServer` reached `Done (0.466s)`, completed all-dimension saves, and had no client class loading errors; stdin was host-closed so the coordinator terminated only the verified isolated child after saving (see docs/evidence/P1-implementation.md)
Client/manual: WAITING - no visual claim for P1
Performance: Not applicable to P1
Known gaps: GLB loading, Blender tooling, assets, rendering, and synchronization are P2+ only; smoke cleanup has no normal Gradle exit code because the host closed stdin after startup
Next action: Stage only P1 files and create the local focused P1 commit, then begin P2

## P2

Phase: P2
Status: PASS
Owner: p2_implementer; GPL remediation by p2_implementer; integration by root coordinator
Reviewer: p2_reviewer
Commit: feat(p2): add deterministic Blender export pipeline, plus the focused local descriptor-contract repair committed with this PASS state (exact hashes are recorded by Git history)
Changed files: blender-addon manifest/sidebar/exporter/CLI/fixture and golden scripts plus scoped GPL-3.0-or-later text and SPDX headers; test-assets static, rigid, and skinned source fixtures, PNGs, expected contracts, export reports, and goldens; Showcase exported descriptor/GLB/PNG resources; LICENSE-PENDING; docs/contract-baseline.md; docs/adr/ADR-009-temporary-extension-license-metadata.md and ADR index; docs/evidence/P2-implementation.md; docs/implementation-progress.md. The first repair makes every generated `base_color` retain the actual `.png` filename and resolve directly to that copied texture. The second repair adds schema-level `textureResourceId` enforcement, a committed suffix-less negative fixture/direct Draft 2020-12 test, and bytecode suppression plus before/after source-tree hygiene assertions for all P2 Blender entrypoints.
Automated tests: PASS - Blender 5.1.2 source-fixture recreation; parser separator isolation; static/rigid/skinned strict post-export validation; explicit CUBICSPLINE rejection; two isolated sequential normalized GLB/descriptor comparisons per fixture; exact `base_color` `.png` suffix and copied-file resolution; exporter extension-less rejection; add-on panel register/unregister; official Blender extension-manifest validation with scoped GPL-3.0-or-later; full source-tree hygiene scan; and `git diff --check`. Direct Draft 2020-12 verification (`P2_DESCRIPTOR_SCHEMA_STRICT_TEXTURE_PATH_CONTROL_SAFE_AND_RUNTIME_TEXTURE_PASS 3`) accepts all three canonical descriptor-to-physical-PNG mappings while rejecting suffix-less, traversal, duplicate-slash, dot-segment, terminal LF/CRLF, leading/embedded whitespace, and embedded-control inputs at `materials.StaticSurface.base_color`. Independent P2 reviewer repeated the checks and also rejected terminal space/tab/CR/U+2028/NUL.
Build: REPAIR PASS - `JAVA_HOME=C:\Program Files\Java\latest\jdk-25`; `./gradlew.bat clean check` succeeded with Showcase resource processing (34 actionable tasks)
Dedicated server: N/A
Client/manual: N/A - no renderer exists yet and no visual claim is made
Performance: N/A - P7 owns performance acceptance
Known gaps: GPL-3.0-or-later is intentionally scoped only to `blender-addon/`; all non-addon components remain license-pending and non-GPL. P8 still owns add-on ZIP packaging, license inventory, and a separate user-authorized final license decision for non-addon components. No P2 Gate gap remains.
Next action: Stage only P2 repair files and create the focused local repair commit, then start P3.

## P3

Phase: P3
Status: REVIEW
Owner: p3_implementer (primary core loader) and p3_fixture_implementer (disjoint malformed-fixture package); integration by root coordinator
Reviewer: p3_reviewer (independent evidence auditor)
Commit: N/A
Changed files: P3-owned core loader/model/diagnostic/test paths, including immutable retention of already-validated default-scene root order and combined-load undeclared-`next` descriptor integrity rejection; P3 malformed and attribution fixtures, additive diagnostic ADR/code-table update, P3 evidence, and this ledger entry only
Automated tests: IMPLEMENTATION PASS - strict descriptor/material defaults, GLB header/chunk/accessor/primitive checks, malformed limits, immutable metadata, hierarchy-depth and animated-scale regressions, fixed-seed fuzz, JAR boundary, performance WARN thresholds, P2 canonical assets, and derived Khronos fixtures all remain green. The root coordinator's latest forced core rerun executed 15 suites / 45 tests with zero failures, including new `ANIM-007` regressions for duplicate `(node,path)` animation targets and matrix-declared animation targets; the latest focused `ModelAssetLoaderTest` also proves immutable exact root order and a decoded-clip/undeclared-`next` `DESC-002` failure. Scoped Khronos derivation verification and `git diff --check` passed
Build: IMPLEMENTATION PASS - `JAVA_HOME=C:\Program Files\Java\latest\jdk-25`; `:blendlib-core:test`, `:blendlib-core:check`, and the latest independent serial full `clean check` succeeded (37 actionable tasks; 24 executed, 13 from cache), followed by `buildRelease` (32 tasks; 4 executed, 28 up-to-date)
Dedicated server: PASS - the current P3 candidate ran only through `:blendlib-showcase:runP3SmokeServer` in `run/p3-smoke-server`, bound `127.0.0.1:25571`, prepared only `blendlib-p3-smoke-world`, and loaded Minecraft 26.1.2/Fabric Loader 0.19.3/Fabric API 0.154.2+26.1.2/Java 25 with BlendLib/Showcase. It reached `Done (5.723s)!`; after that observation root sent `stop` through the same process's standard input, all dimensions saved, Gradle returned `0`, port 25571 released, and no crash-report directory appeared. Exact hashes and checks are in `docs/evidence/P3-isolated-server-smoke-2026-07-30.md`. This closes only P3's dedicated-server item; it does not replace the user-managed audit or promote P3 beyond `REVIEW`.
Client/manual: N/A
Performance: N/A
Known gaps: ADR-010, ADR-011, and ADR-012 are accepted and preserve all P0 code meanings, strict GLB format, and project-license scope. Fixed-revision, provenance-recorded Khronos derived strict-GLB fixtures are now present; raw upstream `.gltf` remains strict-rejection-only. The direct strict-glTF findings from P5 implementation review are remediated with existing `ANIM-007` diagnostics; retaining default-scene roots and rejecting undeclared `next` are complete data/integrity repairs, and ADR-015's active-hierarchy/scene-external skin-joint and event-range semantics were accepted by the user on 2026-07-29. The remaining P3 action is the user-managed separate audit/review handoff; no P3 PASS is claimed here.
Next action: Preserve the complete P3 evidence/diff for the user's separate audit. Per the user's current instruction, non-conflicting P4 implementation may proceed in parallel, but it cannot be presented as a passed P3/P4 Gate or committed as a passed phase before the separate review is resolved.

## P4

Phase: P4
Status: WAITING
Owner: p4_registry_implementer and p4_render_implementer (disjoint file packages); accepted ADR-013/ADR-014 integration by root coordinator
Reviewer: User-managed separate audit pending
Commit: N/A
Changed files: Read-only compatibility preflight and accepted ADR-013/ADR-014 records; public model-key/reload, static/rigid render, client diagnostics, entity adapter/culling, accepted strict material mapping/MAT-004 diagnostics, client-only public marker/wrapper item adapter, Showcase static-rigid entity and item marker/binding, explicit Loom client-JAR variant packages, the reload-to-animation generation-retirement injection, isolated valid/malformed P4 resource-pack fixtures plus listener-level final-resource composition regressions, and reload-local production/development diagnostic reporting. `ClientModelReloadListener.apply` reports only the `ClientModelRegistry.publish` return value to the client-owned retirement callback, so a stale prepare cannot retire the actual active generation's controller/pose cache. The same post-publish path emits one INFO summary per apply and DEBUG-only bounded detail once per final active generation/model; the former render-only deduplicator was moved to reload so resource state does not depend on render state.
Automated tests: IMPLEMENTATION PASS - targeted API/client/Showcase checks plus root-coordinator `clean check`, `:blendlib-core:test`, and `buildRelease` all succeeded; source-boundary verification keeps Showcase main compilation on the pure API. The root coordinator ran `:blendlib-fabric-client:test :blendlib-showcase:test --rerun-tasks` successfully (18 executed tasks), including public marker registration, generation-bound snapshot/no-submit-I/O, programmatic unit-codec, material mode/culling/cutout-threshold rejection, MAT-004 field-pointer/missing-handle, listener integration, and Showcase marker/main-client boundary regressions. A later exact public before-bake regression passed (`:blendlib-fabric-client:test --tests '*BlendLibItemAdapterContractsTest' --rerun-tasks`, 11 executed tasks): it proves that a registered marker becomes `SpecialModelWrapper.Unbaked` with the correct vanilla base ID and empty extra transform, while an unregistered item retains the incoming unbaked model. The valid high-priority descriptor combines with baseline rigid GLB/PNG without opening the lower-priority descriptor, the malformed missing-GLB descriptor publishes exactly one `DESC_002` primary missing handle, and reload summaries/detail diagnostics stay post-publish, structured, bounded, and stale-generation deduplicated.
Build: IMPLEMENTATION PASS - with `JAVA_HOME=C:\Program Files\Java\latest\jdk-25`, the independent runner's current `clean check` succeeded (37 tasks; 21 executed, 16 from cache), `:blendlib-core:test` succeeded, and `buildRelease` succeeded (32 tasks; 4 executed, 28 up-to-date). Client/server runtime variants resolve independently; `git diff --check` had no output.
Dedicated server: PASS - the current P4 candidate ran only through `:blendlib-showcase:runP4SmokeServer` in `run/p4-smoke-server`, bound `127.0.0.1:25572`, prepared only `blendlib-p4-smoke-world`, and loaded Minecraft 26.1.2/Fabric Loader 0.19.3/Fabric API 0.154.2+26.1.2/Java 25 with BlendLib/Showcase. It reached `Done (4.945s)!`; root then sent `stop` through the same process's standard input, all dimensions saved, Gradle returned `0`, port 25572 released, and no crash-report directory appeared. Exact hashes are in `docs/evidence/P4-isolated-server-smoke-2026-07-30.md`. The earlier generic `run/server` record remains historical non-Gate evidence. This closes only P4's dedicated-server item and does not replace audit or remaining material visual evidence.
Client/manual: ISOLATED REAL-CLIENT EVIDENCE PASS / P4 GATE WAITING - on 2026-07-29 the root coordinator used only `D:\BlendLib\blendlib-showcase\run\client` and the disposable `BlendLib Visual RC` local world. Baseline static/rigid entity and first-person item visuals were captured; valid high-priority `valid-override` visibly changed the model from the blue/purple baseline to orange rigid geometry, then F3+T advanced generation `2 -> 3` with `missing=0 diagnostics=0`. After cleanup, baseline F3+T restored the blue/purple model with generation `2`, `missing=0`, and `diagnostic=none`. `malformed-missing-mesh` F3+T advanced `2 -> 3`, showed a missing-model fallback, and reported exactly `BLENDLIB-DESC-002` for `/mesh` / `does_not_exist.glb`; two same-generation diagnostics commands returned the same structured diagnostic while the reload summary remained `missing=1 diagnostics=1`. Fixture copies were removed after each run and the final isolated resourcepack directory is empty. See `docs/evidence/P4-manual-client-2026-07-29.md` for hashes and command excerpts. No formal server/world was touched.
Performance: N/A - P7 owns performance acceptance
Known gaps: The user accepted ADR-013/ADR-014 and, on 2026-07-30, ADR-019. Minecraft 26.1.2/Fabric API 0.154.2+26.1.2 exposes no public registry for the literal `blendlib:model` JSON type; the accepted public marker/wrapper adapter is therefore implemented instead of private mapper/reflection/Fabric impl use. The accepted ADR-019 alignment now classifies exact-0.10 single-sided cutout as supported `entityCutoutCull`; its fixture verifier reports 18 rows / 8 supported / 10 rejected, and all other rejection boundaries remain `BLENDLIB-MAT-004`. The newly supported lit/emissive rows have no fresh isolated real-client observation yet. The later post-ADR focused `MaterialRenderMapperTest` executed successfully (5 tests / 0 failures / 0 errors); its earlier lock/configuration attempts remain historical pre-execution failures, not assertion failures. The static/rigid entity, hand item, valid/malformed override, diagnostic, and F3+T evidence is recorded, but the full supported material culling/texture/emissive matrix is still visually WAITING; the separate P3/P4 audit also remains outstanding. The earlier pre-integration log's two P5 missing handles are obsolete.
Next action: Obtain new isolated one-pack/F3+T/baseline-restore real-client observations for `supported-cutout-single-sided-threshold-010-{lit,emissive}`, showing `missing=false` and `diagnostic=none`, followed by the remaining accepted ADR-014 matrix work. Preserve the P3/P4 diff/evidence for the user-managed audit; never copy fixtures to a formal instance. Until those conditions are resolved, no P4 commit/Gate PASS is authorized.

Supplemental evidence: `docs/manual-client-acceptance-v1.md` now fixes the user-managed P4 evidence order as baseline → valid override → baseline restore → malformed override → baseline restore, with exact command-output, generation, screenshot, and isolated-log retention requirements. It remains a template only, not visual evidence or a Gate PASS.
Material compatibility supplement: ADR-014 was accepted by the user on 2026-07-29 and records the full 26.1.2 mode × `double_sided` matrix. The implementation now rejects `opaque,true`, `translucent,false`, additive, and non-equivalent cutout thresholds at reload with MAT-004; it routes `cutout,false` to the culling path and `cutout,true` to the no-cull path without abusing the outline boolean. Emissive remains independent fullbright vertex lighting. No visual material PASS is claimed.

Real-client material supplement (2026-07-30): `docs/evidence/P4-material-matrix-real-client-2026-07-30.md` records narrowly scoped isolated-client evidence for the six pre-ADR-019 supported material paths: opaque/single-sided, cutout/double-sided/threshold-0.10, and translucent/double-sided lit/emissive pairs. Every path used one junction at a time, explicit F3+T, `missing=false`, and `diagnostic=none`; screenshots remain qualitative samples rather than universal proof of face coverage, alpha edges, transparency ordering, or luminance. Four non-conflicting rejected rows prove missing-model behavior and repeated same-generation `BLENDLIB-MAT-004`: opaque/double-sided lit, opaque/double-sided emissive, and cutout/double-sided threshold-0.25 lit/emissive. Both cutout-threshold rows were isolated at `generation=3`, diagnosed twice at `/materials/RigidSurface/cutout_threshold`, visibly used the missing-model fallback after a disposable-world spawn, restored baseline by F3+T, and exited with all-dimension saves/normal Render-thread stopping; the emissive partner's retained F2 image is `2026-07-30_06.56.57.png` with SHA-256 `B67CF37B885182F5028F9257981BD248FB294D184A64A6005E50F9CE02C20309`. A separate P5 cache-teardown CME occurred after the early cutout observations while returning to title, so that early session is not recorded as a normal exit; after the scoped P5 remediation, later isolated exits completed title-screen return, all-dimension saves, and normal Render-thread stopping. No temporary junction remains active in `run\client\resourcepacks`; the latest exact junction is recoverably quarantined under ignored `build\p4-resourcepack-junction-quarantine` rather than deleting a source fixture, and all source fixtures were rechecked. This is **PARTIAL REAL-CLIENT MATRIX EVIDENCE only**: 6 of 8 currently supported pack paths and four of 10 rejected paths have observations. The accepted ADR-019 exact-0.10/single-sided lit/emissive rows need new observations; no earlier pause/rejection observation is reinterpreted. P4 stays `WAITING` for those rows, the remaining matrix coverage, and user-managed audit/Gate evidence; no material-matrix/Gate PASS is claimed.

ADR-019 accepted source-of-truth alignment (2026-07-30): the local owner accepted the narrow correction after root source inspection established that the fixed-26.1.2 public `entityCutoutCull` path and existing mapper/backend support `cutout`, `double_sided=false`, exact threshold `0.10`. ADR-014, the two renamed lit/emissive fixture rows, matrix verifier/tests, manual, evidence, ADR index, and this ledger now classify those rows as supported; the verifier reports `rows=18 supported=8 rejected=10`. No production mapper/backend change was made, no past rejection observation is reinterpreted, and fresh client evidence remains required for both rows. P4 remains `WAITING`; no format/API/version/Gate change or material-matrix PASS is claimed.

ADR-019/ADR-020 focused regression refresh (2026-07-30): after the empty P6 loopback server saved every dimension and released port 25575, root ran one serial Java-25/no-daemon/one-worker Gradle invocation with a bounded 1 GiB daemon heap: `:blendlib-fabric-client:test --tests '*MaterialRenderMapperTest' :blendlib-showcase:test --tests '*P7ReferenceScenarioPerfTest' --rerun-tasks`. It completed successfully in 38 seconds (19 executed tasks); `MaterialRenderMapperTest` recorded 5 tests / 0 failures / 0 errors and `P7ReferenceScenarioPerfTest` recorded 6 / 0 / 0. This replaces neither the full historic regressions nor any real-client evidence, but closes the two post-ADR focused-test execution gaps. `git diff --check` and the P4 fixture verifier also passed after the source-of-truth alignment.

## P5

Phase: P5
Status: IN_PROGRESS
Owner: p5_implementer (pure core semantics), p5_fixture_implementer (disjoint fixture/golden package), p5_unload_lifecycle_implementer (client lifecycle cleanup), p5_model_identity_implementer, p5_skinned_topology_implementer, p5_rigid_palette_render_implementer, and p5_visual_event_implementer (client-only socket marker/presentation wiring), per current user instruction to advance implementation while audit remains separate
Reviewer: User-managed separate audit pending
Commit: N/A
Changed files: `blendlib-api` semantic animation/instance/model keys and tests; pure core animation controller, pose/palettes, socket query, prepared CPU-skinning boundary and tests; immutable `SkinnedMeshTopology` preserving material-slot/UV0/index topology from preparation through `CpuSkinnedMesh`; the CPU-skinning hot-path regression; canonical P2 runtime goldens; P5 fixture/golden verifier; immutable retention of the strict loader's default-scene root order plus combined-load undeclared-`next` rejection; client instance registry whose required `(instanceKey, modelKey, generation)` binding prevents same-generation model reuse, model-aware pose keys, bounded LRU pose cache, cadence buckets, an explicit caller-supplied `sampleAndCache` controller-to-LRU path with stale state/model/generation rejection, and an INIT/session-scoped entity key plus disconnect/entity-unload/block-entity-unload lifecycle bridge with exact pose-cache cleanup tests; a client-render-private immutable rigid-palette snapshot carrier with model/generation validation and rest-pose fallback; a reproducible skinned Showcase `idle`/`walk`/`attack` Blender source, export wrapper, deterministic goldens, runtime descriptor/GLB/external PNG, and evidence; a reproducible real-Showcase `blendlib_showcase:tip` socket declaration plus two-time model-space regression; a single optional extraction-captured presentation socket transform, client-only `RenderTypes.lines()` RGB marker, CULLED/missing-socket suppression, and Showcase tip-marker configuration; a render-thread/liveness/event-whitelisted Showcase presentation particle consumer; P3 loader ANIM-007 compliance remediation; P5 evidence and this ledger. Changes remain unstaged because no P3/P4/P5 Gate is passed.
Automated tests: IMPLEMENTATION PASS for the previously integrated P5 scope - post-lifecycle root forced `:blendlib-fabric-client:test --rerun-tasks` passed (11 tasks executed), including the exact session+entity-id/unload, block-entity typed equality, pose-cache, INIT rotation, disconnect reset, and entrypoint-event regressions. The identity regression proves same-generation model rebinding replaces the controller and rejects stale pose reads/writes; new topology regression covers defensive UV/index/material copying and output cardinality; render contracts cover palette/handle model-generation mismatch rejection, posed-node selection, rest-pose fallback, immutable carrier state, and no submit I/O/sampling. The latest extraction-only sample-and-cache regression verifies a single sampler invocation on a miss, no resample on a hit while refreshing latest pose, and fail-fast stale state/model/generation rejection before lookup/sampling. Its focused registry/LRU/source-boundary run passed (11 executed tasks; 7 registry, 3 bounded-cache, and 1 source-boundary tests with zero failures). The default-scene-root/undeclared-`next` correction independently passed focused Java-25 `ModelAssetLoaderTest` (6 executed tasks) and the necessary `RenderContractsTest` constructor compatibility run (11 executed tasks). A subsequent independent serial current-worktree Java-25 `clean check` passed (37 tasks; 24 executed, 13 from cache), followed by `buildRelease` (32 tasks; 4 executed, 28 up-to-date), with empty `git diff --check`. The P5 Showcase Blender double-export/golden verifier and `test-assets/p5/verify_p5_fixtures.py` also passed. The newest guarded visual-event and real-Showcase socket JUnit tests were additionally Java-25 compiled and executed through locally cached JUnit Platform 1.11.4/Jupiter Engine 5.11.4 with `2/2` successes each, then re-run by Gradle: the Showcase visual-event/source-boundary task passed `5` tests with zero failures/errors (16 executed tasks), and the client real-resource/socket task passed `2` tests with zero failures/errors (11 executed tasks).
Integration supplement: The entrypoint-owned `SkinnedAnimationRuntime` now receives reload/INIT/disconnect/entity/block-entity lifecycle events; the public extraction-only entity builder binds canonical state selection, cadence, captured CPU-skinned frames, and optional presentation-only event dispatch without moving controller work to submit. Focused client boundary/runtime/extraction/event tests passed (11 tasks); focused Showcase animated-actor/boundary tests passed (12 tests, 16 tasks); a root-owned `:blendlib-fabric-client:test :blendlib-showcase:test --rerun-tasks` passed (18 tasks); and `ShowcaseSkinnedResourcePreparationTest` passed against the committed descriptor/GLB/PNG through strict loading and `SkinnedRenderHandle.prepare` (1 test, 11 tasks).
Latest presentation/socket supplement: Blender 5.1.2 completed both an isolated `--record-golden` export and a second strict golden-read verification for the unique strict-v1 `"blendlib_showcase:tip": {"node": "ShowcaseAnimationRoot/ShowcaseAnimationArmature/ShowcaseRootBone/ShowcaseTipBone"}` declaration. Direct Draft 2020-12 validation passed. A Java 25 JShell probe over compiled pure API/core classes strict-loaded the committed asset, resolved the socket to node `0`, and sampled model-space walk positions `(0.07,0.6,0)` at `7/24s` and `(-0.06999999,0.6,0)` at `19/24s`. The committed source `.blend` SHA-256 remains `3ce143f190a2d8eac25df4392fdd0ce3dc61a4c397fc62ba3f1bf7920c857511`; the corrected descriptor SHA-256 is `d2e044e4e2c251e79df95e6f028ae457df76630fe3cde05aa76abaea81353a94`, and committed descriptor/GLB/PNG hashes match the regenerated golden record. The new real-resource JUnit regression and the guarded Showcase visual-event contract test are added but their newest Gradle execution is queued behind active user-owned external Gradle wrappers.
Supersession of the preceding queue note: the current focused Gradle refresh below completed after it; no P5 focused Gradle regression remains queued.
Manual JUnit supplement: The two newest classes were Java-25 compiled against the current local 26.1.2 classpath and executed with the locally cached JUnit Platform 1.11.4/Jupiter Engine 5.11.4. `ShowcaseSkinnedResourcePreparationTest` reported `total=2 succeeded=2 failed=0`, and `ShowcaseSkinnedAnimationContractsTest` reported `total=2 succeeded=2 failed=0`. This is direct JUnit Platform evidence and is now corroborated by the current focused Gradle runs.
Current focused Gradle refresh: `:blendlib-showcase:test --tests '*ShowcaseSkinnedAnimationContractsTest' --tests '*ShowcaseSourceBoundaryTest' --rerun-tasks --console=plain` succeeded in 37 seconds with 16 executed tasks; its XML results record 2 animation-contract and 3 source-boundary tests, all with zero failures/errors. `:blendlib-fabric-client:test --tests '*ShowcaseSkinnedResourcePreparationTest' --rerun-tasks --console=plain` succeeded in 6 seconds with 11 executed tasks; its XML result records 2 real-resource/socket tests with zero failures/errors.
Post-marker supplement: the renderer consumes only a single extraction-captured socket `Transform`; a new `SkinnedSocketMarkerSubmitter` applies entity-relative `root → unitsToBlocksScale → socket` and submits ordinary `RenderTypes.lines()` geometry. `SkinnedRenderBackendContractsTest` now proves immutable snapshot retention, missing-socket suppression, CULLED suppression, and the 2-units-per-block numerical transform `(10,20,30) + (4,6,8)/2 = (12,23,34)`. Its latest Java-25 Gradle rerun passed after the CULLED regression (11 executed tasks). Independent read-only 26.1.2 API/source review PASS confirmed no submit-time loader, parser, I/O, registry, controller, world, or raw-OpenGL access.
Build: IMPLEMENTATION PASS for the previously integrated P5 scope - serial Java-25 `clean check` and `buildRelease` both passed before the newest guarded visual-event and real-Showcase socket supplement. The newest supplement now also has focused Gradle confirmation. See `docs/evidence/P5-implementation.md`.
Current integration verification: final Java 25 `clean check` passed on the post-marker current worktree (37 tasks; 18 executed, 19 from cache), `:blendlib-core:test` passed (6 up-to-date tasks), and `buildRelease` passed (32 tasks; 4 executed, 28 up-to-date). The post-marker CULLED regression passed its focused Java-25 Gradle rerun (11 executed tasks); the final full check then re-ran all module checks. The prior presentation/socket supplement also passed direct local JUnit Platform execution and focused Gradle re-runs.
Dedicated server: PASS - the current P5 candidate ran only through `:blendlib-showcase:runP5SmokeServer` in `run/p5-smoke-server`, bound `127.0.0.1:25573`, prepared only `blendlib-p5-smoke-world`, and loaded Minecraft 26.1.2/Fabric Loader 0.19.3/Fabric API 0.154.2+26.1.2/Java 25 with BlendLib/Showcase. It reached `Done (2.426s)!`; root then sent `stop` through the same process's standard input, all dimensions saved, Gradle returned `0`, port 25573 released, and no crash-report directory appeared. Exact hashes are in `docs/evidence/P5-isolated-server-smoke-2026-07-30.md`. The earlier generic `run/server` record remains historical non-Gate evidence. This closes only P5's dedicated-server item; P5 remains `IN_PROGRESS` for its audit and remaining real-client evidence.
Client/manual: CURRENT STARTUP SMOKE EVIDENCE / VISUAL WAITING - the current post-marker real isolated 26.1.2 client loaded BlendLib/Showcase, the current lifecycle submodule, Render thread, Indigo/LWJGL/OpenAL and resource reload without a BlendLib crash, crash report, or JVM fatal-error file. Its current reload summary was `candidate_generation=1 active_generation=1 published=true stale=false models=4 missing=0 diagnostics=0`. Mojang/Realms 401 responses came from the offline test credential. The coordinator verified the exact `D:\BlendLib\blendlib-showcase` `-Dfabric.dli.env=client` Java child and stopped only that PID; Loom's `-1` is controlled cleanup only, and no human visual model/animation/F3+T verification is claimed.
Current post-wiring smoke: the isolated client loaded Minecraft 26.1.2/Fabric Loader 0.19.3/Java 25, BlendLib and Showcase, reached the Render thread and client resource reload, and logged `blendlib_reload candidate_generation=1 active_generation=1 published=true stale=false models=4 missing=0 diagnostics=0`. It remained alive in the noninteractive client UI; after exact command-line verification, only that isolated client PID was stopped. Loom reported exit `-1` solely from this controlled cleanup. No BlendLib/Showcase crash report or JVM fatal-error file was present. Offline credential/Realms 401 errors and the known missing `build/resources/client` warning are not BlendLib visual evidence.
Performance: STRUCTURAL EVIDENCE ONLY - pose cache is capacity-bounded with metrics and cadence buckets; CPU skinning now uses explicitly prepared immutable geometry plus topology rather than repeatedly copying `MeshPrimitive` arrays, and the hot loop has a regression assertion against per-vertex/per-influence `Vec3` construction. No P7 benchmark or performance Gate is claimed.
Known gaps: P4 remains neither audited nor Gate PASS; its accepted ADR-013 item adapter and ADR-014 material paths still need user visual/F3+T evidence. ADR-015 is accepted and implemented at the strict combined-load, canonical-palette, runtime and Showcase-binding seams: default-scene roots/reachable descendants are canonical, required scene-external joints/skins are rejected, events beyond decoded clip duration are load errors, and the deterministic Showcase `idle`/`walk`/`attack` asset is now wired to the entrypoint-owned client runtime. The real Showcase socket proof is model-space automatic evidence; the current client-only RGB marker makes the relation observable but still has no human screenshot/video, and it is not a world-space transform claim. The Showcase now has a client-only `attack_whoosh` particle consumer, but it still needs real visual proof. This does not waive P3/P4/P5 Gates, authorize a phase commit, or alter P0–P8 final acceptance ordering. Still missing are user-managed real visual/F3+T evidence, P7 performance evidence, and the separate audit.
Next action: Preserve the P5 focused-test evidence for the user's separate audit and perform the user-managed isolated visual/F3+T acceptance from `docs/manual-client-acceptance-v1.md` when directed. Do not stage or commit P3--P5 as passed before the outstanding audit and visual evidence resolve.

Supplemental evidence: `docs/manual-client-acceptance-v1.md` records the now-implemented P5 binding's remaining visual proof requirements without claiming them. ADR-015 was accepted by the user on 2026-07-29 and is enforced by canonical default-scene hierarchy binding plus combined-load rejection of out-of-range events. The user-managed audit and visual Gate status remain unchanged.

Supplemental rigid vertical slice (2026-07-29): The committed strict `blendlib_showcase:fixtures/rigid_model` now flows through the existing lifecycle-owned instance registry, the extraction-side canonical `NodePalette`, and `ModelRenderSnapshot.rigid(...)` into an immutable generation/key-bound `RigidNodePaletteSnapshot`; no second controller registry was introduced. `ClientRigidExtractionBridge` resides under `animation/extract`, while `render` receives only a prepared handle and canonical transform map, preserving the source boundary that forbids registry/controller/sampling work in the render package. `ShowcaseRigidPulseRuntimeTest` strict-loads the committed descriptor/GLB and proves `rigid_pulse` produces the expected non-identity arm palette before rendering. The focused source-boundary/render-contract run passed 12/12; a forced `:blendlib-fabric-client:test :blendlib-showcase:test --rerun-tasks` passed client 121/121 and Showcase 42/42; root then reran Java-25 `clean check`, `:blendlib-core:test --rerun-tasks`, `buildRelease`, and `git diff --check` successfully. This is automated implementation evidence only: P5 visual/F3+T and audit remain WAITING.

Current real-client incident (2026-07-29): Root replayed the current 26.1.2 client JAR only in `D:\BlendLib\blendlib-showcase\run\client` and disposable `BlendLib Visual RC`. Static baseline, hand item, GUI icon, and rigid pulse were visible; the static fixture is intentionally a single triangle, so an earlier stale-JAR "fragment" interpretation is withdrawn. When `/summon blendlib_showcase:animated_actor` first exercised the socket-marker path, the Render thread crashed with `IllegalStateException: Missing elements in vertex: LineWidth` from `SkinnedSocketMarkerSubmitter.emitAxis`; the exact crash report is `run\client\crash-reports\crash-2026-07-29_22.47.37-client.txt`. P5 is now IN_PROGRESS for the scoped marker vertex-format remediation. This is a real regression, not a visual PASS; no official server/world was touched.

P5 real-client remediation supplement (2026-07-29): The marker fix writes the required 26.1.2 `LineWidth` on every `RenderTypes.lines()` endpoint; its real `BufferBuilder` negative/positive regression and root-owned focused rerun passed. A first current-classpath client replay then confirmed the original LineWidth crash no longer occurred after summoning `blendlib_showcase:animated_actor`, but exposed a distinct teardown defect: after saving/exiting the same disposable world, late entity unload/render extraction called `entityKey` without an active play session (`crash-2026-07-29_23.07.41-client.txt`). The client-only remediation retains normal `entityKey` fail-fast behavior while adding optional active-session lookup for late unload/extraction: active-session cleanup still removes the exact typed key; post-disconnect unload returns zero and extraction emits a missing snapshot without binding state. The independent full client suite was 33 suites / 125 tests / 0 failures / 0 errors / 0 skipped; root independently reran the focused lifecycle/runtime/entity suites successfully. Root then repeated the current-classpath isolated client test with the actor loaded, saved/exited to the title screen, and closed the client normally: `runClient` exit code 0, no new crash-report file (only the two historical reports remain), and no post-retest `LineWidth`, `no active client play connection`, reported-exception, or game-crash log entry. This is real crash-remediation evidence only; P5 stays IN_PROGRESS and visual/F3+T/audit evidence remains WAITING. See `docs/evidence/P5-socket-marker-crash-remediation.md` and `docs/evidence/P5-client-disconnect-crash-remediation.md`.

P5 visual-interpretation supplement (2026-07-30): a read-only asset/render-path probe established that `showcase_actor` is intentionally a 4-vertex, zero-thickness skinned plane with `opaque`/`double_sided:false`; a back or grazing view can therefore show only the small marker/asset edge. The observed blue/orange point is consistent with the actor's two-color texture and does not by itself show a missing skinned-mesh submit. The renderer source still captures/submits the skinned mesh before the socket marker. This is not pixel-level rendering proof and does not change any visual item: P5 remains `IN_PROGRESS` with visual/F3+T/audit evidence `WAITING`.

P5 targeted-test supplement (2026-07-30): after the isolated client exited cleanly, the P5 implementer ran three Java-25/no-daemon forced focused test commands (core controller/pose/palette/CPU-skinning/runtime boundary; client lifecycle/registry/cadence/extraction/socket/render boundary; Showcase actor/state/binding/source boundary). The aggregate XML result was 22 suites / 84 tests / 0 failures / 0 errors / 0 skipped; `git diff --check` was empty and the implementer made no source, staging, or commit change. This confirms automated P5 implementation coverage only, not a visual Gate.

P5 isolated real-client supplement (2026-07-30): root used only `blendlib-showcase/run/client` and its disposable local `BlendLib Visual RC` world. Current Java-25 `:blendlib-showcase:runClient` reached the real Render thread and, after separate `animated_actor` summon commands, produced F2 screenshots `2026-07-30_00.59.56.png`, `2026-07-30_01.01.53.png`, and `2026-07-30_01.02.36.png`; the latter two visibly retain two separately created blue/orange skinned Showcase actors in one real frame. The session saved/returned to title and Gradle exited 0, with no new crash report (the two known historical reports remain). This is partial real-client evidence for visible skinned/textured mesh and simultaneous instances; it does not prove continuous socket tracking, visual-event capture, quaternion continuity, independent fallback clocks, F3+T semantics, or P5 Gate PASS. Exact hashes, commands, log timestamps, scope, and limitations are in `docs/evidence/P5-isolated-real-client-2026-07-30.md`.

P5/P6 provenance supplement (2026-07-30): static cross-phase inspection found that the normal Showcase actor is bound through `synchronizedSkinnedAnimation`; accepted P6 state is retained and takes precedence over the P5 local 132-tick fallback schedule. A normal client video cannot therefore attribute observed timing to the fallback schedule required by the current P5 manual. The owner accepted ADR-018's distinct `blendlib_showcase:p5_fallback_actor` no-sync fixture contract on 2026-07-30; its server/client implementation and contract test are now complete. The isolated 132-tick real-client observation remains outstanding; normal actor/P6 protocol, API, timing thresholds, and manual evidence are unchanged. The schedule-attribution item remains WAITING until that fixture evidence exists; P5 overall remains `IN_PROGRESS`.

P5 cache-teardown remediation supplement (2026-07-30): the P4 material session exposed a distinct real-client `ConcurrentModificationException` at `BoundedPoseCache.removeInstance` while an access-order `LinkedHashMap` cache hit could structurally reorder entries during instance-removal iteration. The scoped client-only fix serializes all `BoundedPoseCache` reads, writes, removals, metrics and reset operations without changing capacity, LRU, typed identity, protocol or public API. A concurrent access-order lookup versus 8,192-revision removal regression and an `onEntityUnload -> removeMatching` lifecycle regression were added; Java-25/no-daemon `BoundedPoseCacheTest`, `ClientAnimationInstanceRegistryTest`, and `ClientAnimationLifecycleBridgeTest` passed 3 suites / 18 tests with zero failures/errors, and `git diff --check` was empty. Root then repeated only the isolated local `BlendLib Visual RC` entry/save-to-title/quit path: `latest.log` records full dimension saves at `03:36:22` and normal `Render thread ... Stopping!` at `03:36:46`, no new crash report, CME, or reported exception. This remediates that exact teardown symptom only; P5 remains `IN_PROGRESS` and its visual/F3+T/schedule/audit conditions remain `WAITING`.

P5 post-CME full refresh (2026-07-30): an independent runner then completed Java-25/no-daemon `clean check` successfully (43 tasks; 23 executed, 20 from cache), `:blendlib-core:test` successfully (6 up-to-date tasks), and `buildRelease` successfully (62 tasks; 30 executed, 32 up-to-date). The refreshed build includes the local Maven blank-consumer, Add-on ZIP, inventory/negative fixtures, and release SHA verification. It is automated/local-artifact evidence only; P5 remains `IN_PROGRESS`.

P5 ADR-018 fixture implementation supplement (2026-07-30): the accepted `blendlib_showcase:p5_fallback_actor` is now a separate `0.60F × 1.80F`, tracking-8/update-3 server-safe host with no P6 facade, payload, trigger, synced animation data, or client dependency. Its client binding selects only the existing `ShowcaseAnimatedActorStateSchedule` through `.skinnedAnimation(...)`, while the normal `animated_actor` remains on `.synchronizedSkinnedAnimation(...)`. The new fixture contract plus existing schedule, normal actor, and P6 trigger contracts passed Java-25/no-daemon `:blendlib-showcase:test --rerun-tasks` with 13 selected tests and zero failures/errors (`BUILD SUCCESSFUL in 22s`); `git diff --check` was clean. The dedicated isolated 132-tick real-client schedule observation remains WAITING, so P5 remains `IN_PROGRESS` and P6 remains `WAITING`.

P5/P6 late-unload NPE remediation supplement (2026-07-30): a fresh `p5_fallback_actor` isolated-client save/exit exposed a Render-thread `connectionSession` NPE at `ClientAnimationSyncStore.onEntityUnload`. Root inspected the actual generated client class with `javap`: its guard and typed-key constructor performed two separate `connectionSession` field reads, matching the `07:33:36` teardown stack. The scoped P6 client-store fix captures one local `activeSession` before the guard and uses it for the typed entity key; it changes no API, payload, runtime, common/server code, sequence or epoch contract. A new disconnect-to-late-unload regression proves no throw, empty store/session/dimension/entity state, and out-of-session command dropping. Java-25 focused Store test passed 3/3; a fresh full `:blendlib-fabric-client:test --rerun-tasks` passed 33 suites / 128 tests / 0 failures / 0 errors and `git diff --check` was empty. Root then used only `blendlib-showcase/run/client` and disposable `BlendLib Visual RC` to enter, save-to-title and quit: player disconnect/all-dimension saves occurred at `07:48:24`, Render-thread normal stop at `07:48:42`, no matching error marker/new crash report, and the one launcher recorded `BUILD SUCCESSFUL`. This is exact crash-remediation evidence only: P5 stays `IN_PROGRESS`, P6 stays `WAITING`, and it does not prove fallback timing, visual event/socket, F3+T, two-client sync, performance or audit/Gate PASS.

P5 fallback-capture supplement (2026-07-30): root ran one further isolated local `BlendLib Visual RC` session using only `blendlib-showcase/run/client`; no formal server/world was touched. The final raw capture `build/manual-p5-fallback-capture-evidence/2026-07-30-082500/22-p5-fallback-final-continuous.mp4` is 180.000 seconds at 15 fps (2,700 frames), SHA-256 `F1C3275AE30D406F61DBE38AB24EADCF6138AE90E2C5C30EE6D3C22703E4CCF3`; it visibly ends after first fallback generation/T0 and **before** the second generation, so it is ineligible for the 132-tick/40-tick-offset/two-instance schedule Gate. The retained `latest.log` records fallback T0 `256971` and T1 `261067` (`4096` ticks apart) and a normal Render-thread stop at `09:13:44`; Gradle recorded `BUILD SUCCESSFUL in 44m 6s` and no new crash report appeared. These detached log facts have no continuous video or entity-ID correlation. Camera/yaw probes left the intentionally single-sided zero-thickness mesh as narrow orange/blue edges, insufficient to judge states or quaternion continuity; that observation does not justify a fixture/material change. The rejected `doDaylightCycle false` command was only an environmental presentation-control failure. Complete command/video/log scope and limitations are in `docs/evidence/P5-isolated-real-client-2026-07-30.md` and the capture directory. P5 stays `IN_PROGRESS`; fallback schedule, socket/event, reload, and all remaining visual items remain `WAITING`.

P5 long front-capture supplement (2026-07-30): a final 600.000-second/9,000-frame/15-fps isolated real-client video was recorded from the verified +Z front of the same intentionally single-sided fixture, SHA-256 `B3F8096AC2BEBCAE8C57484889330B454965D7E2577E8775C7A1DFCE592FC715`. It continuously covers cleanup, daytime setup, first fallback summon at `09:47:43`, gametime `306229`, UUID `[I; 738674508, -689289378, -1440243297, 1358251708]`, second `~2 ~-1 ~-4` fallback summon at `09:51:10`, gametime `310492`, and distinct UUID `[I; 894911640, -49591355, -1247660059, 447083003]`; the retained query values differ by `4263` ticks. Root directly inspected the raw video, a first-cycle 4-fps contact sheet, and selected two-instance frames: the real client visibly renders the orange/navy skinned/external-texture plane, a continuous small-to-large tip swing followed by return-to-upright, and two simultaneously visible fallback instances with different poses. Normal save-to-title/exit completed with all dimensions saved, no new crash report, Render-thread `Stopping!` at `10:07:08`, and Gradle `BUILD SUCCESSFUL in 39m 6s`. This supersedes the earlier short-video *coverage limitation* only; it does not silently promote the P5 Gate. The two gametime observations are after the corresponding births rather than exact birth ticks, and the video has no state-label/age overlay, so full 132-tick state attribution and quaternion-continuity acceptance remain `WAITING`. Socket/event are intentionally not fallback-bound under ADR-018, and reload/cache/cadence remain unobserved. P5 therefore remains `IN_PROGRESS`; the authoritative raw/log/frame paths and actual resource hashes are in `docs/evidence/P5-isolated-real-client-2026-07-30.md`.

P5 presentation/reload supplement (2026-07-30): root ran one more Java-25 isolated `runClient` only in `blendlib-showcase/run/client` and disposable `BlendLib Visual RC`. The current normal `animated_actor` was summoned at `10:38:44`; retained Minecraft F2 `2026-07-30_10.42.32.png` (SHA-256 `199E312DB83F1C0415E88E53439EE5AC5ED8D486B65F6B6E63D8AF3B60FAAB9D`) visibly captures a white `SWEEP_ATTACK` arc beside it. Root inspected the current client binding and exact Java source set: `BlendLibShowcaseClientEntrypoint` emits that custom particle only from the normal actor's guarded `attack_whoosh` visual-event callback, with no second Showcase emitter. Thus the **presentation-only visual-event subitem has a narrow real-client PASS**; it does not prove its exact tick, P6 synchronization, fallback schedule, or gameplay semantics. In the same isolated session, after a real F3+T reload, retained client reporter output advances `active_generation 1 -> 2` with `models=4 missing=0 diagnostics=0` on both sides; the inspected model remained discovered/non-missing/no-diagnostic and F2 `2026-07-30_11.05.53.png` (SHA-256 `06FB33BC25C6971631512A25BA86A6816C305ABC516E1EE3F3D92F5958AA2F5B`) shows the fallback actor still visible. That is a narrow registry/diagnostic/visibility observation only and does not infer resume, snap, restart, preserved pose, or event replay. The attempted concurrent MP4 has SHA-256 `B3791DB2212D5255B2088B29D6C5BCD5FD85791BB8B7A954D7B10F421D8070D8` but ffprobe returns `moov atom not found`; it is excluded from every conclusion. Socket marker still lacks a continuous visual trace, and schedule/quaternion/cache/cadence/P5 Gate stay `WAITING`/`IN_PROGRESS`. The session saved all dimensions, stopped the Render thread normally and recorded `BUILD SUCCESSFUL in 42m 58s`; no formal server/world, push, tag, publication or deployment occurred. Detailed authoritative evidence is `build/manual-p5-presentation-reload-evidence/2026-07-30-102841/result.md` and `docs/evidence/P5-isolated-real-client-2026-07-30.md`.

P5 side-view socket supplement (2026-07-30): root then used another Java-25 isolated `runClient` only in `blendlib-showcase/run/client` and disposable `BlendLib Visual RC`. After fallback cleanup, the normal `animated_actor` was summoned at `12:00:55`; the log records the side-view observer at `(3.500000, 66.000000, 0.500000)` at `12:04:56`. Retained F2 images `2026-07-30_12.05.35.png` (SHA-256 `B8087722F8C0F9024DBC3EDA1FD296FA13EA07237DA97060324342AB4F07E82D`) and `2026-07-30_12.11.17.png` (SHA-256 `3ADAB44B1F508AE6406FF0DD8BF6BD59677FC0EE321C79C8A8E3B9022A804F91`) both visibly show the small RGB socket-axis marker above the crosshair. Root directly reviewed the images and the bound client source: the normal actor configures `skinnedSocketMarker(TIP_SOCKET_KEY)`, and the renderer submits RGB `RenderTypes.lines()` only for an extraction-captured visible skinned socket. Hence the **socket-marker visibility subitem has a narrow real-client PASS**. The two screenshots have no state/entity-age/socket-transform telemetry and essentially the same marker screen location, so they do not prove continuous bone-relative motion, world-space transform, 132-tick schedule, quaternion continuity, P6 sync, F3+T behavior, cache/cadence or the P5 Gate. The local world saved all dimensions and the Render thread stopped normally at `12:16:29`; Gradle recorded `BUILD SUCCESSFUL in 33m 43s`, with no new crash report. Detailed authoritative evidence is `build/manual-p5-socket-side-evidence/2026-07-30-114245/result.md` and `docs/evidence/P5-isolated-real-client-2026-07-30.md`. No formal server/world, push, tag, publication or production deployment occurred.

P5 front-facing double-instance supplement (2026-07-30): root corrected the camera to the explicitly verified `+Z` front `(0,66,4,yaw=180,pitch=28)` for the intentionally single-sided zero-thickness P5 fixture, then retained a valid 15.000-second/225-frame/15-fps H.264 capture `build/manual-p5-schedule-evidence/2026-07-30-123000/60-p5-front-visible-two-instance-15s.mp4` (SHA-256 `E34E8CD936323B12B6805558A78D89220A282DC9C8D417A45FAEBF8553FCA679`) and F2 `90-p5-front-double-instance-2026-07-30_16.20.54.png` (SHA-256 `00B029C4AF8B9B911E9BB79D559B5A29C1F700C9AB3E4E201A82513B09376800`). Direct frame review shows both orange/navy skinned/external-texture instances continuously visible and distinguishable by pose. The isolated client log records first summon/T0 `425886`, second `~2 ~-1 ~-4` summon/T1 `427079`, hence the retained query interval is 1193 ticks (>40); both queries are after births, so this is a narrow real-client PASS for frontal mesh/texture and concurrent independently created instances, not exact 80/120/132 state attribution. Early 10/20/40/50 recordings remain retained-but-excluded for empty/wrong-angle/pre-spawn/launch reasons. The session saved all dimensions, stopped its Render thread normally, completed Gradle successfully, and made no new crash report. P5 stays `IN_PROGRESS`: exact schedule/blend, quaternion, continuous socket, reload semantics and metric evidence remain unresolved; no P3/P4/P5 commit, push, tag, release or deployment was performed.

## P6

Phase: P6
Status: WAITING
Owner: p6_sync_entity_api_implementer, p6_blockentity_showcase_implementer, p6_showcase_consumer_implementer; root coordinator integration
Reviewer: User-managed separate audit pending
Commit: N/A
Changed files: P6 common semantic animation payloads/monotonic server state and cleanup; client executor receiver, session/dimension-scoped bounded target store with a local-session late-unload teardown guard, typed entity and block-entity adapters; Showcase server trigger, actor synchronized binding, animated altar block entity/client binding/resources; API-only consumer fixture and P6 tests. No P6 files are staged.
Automated tests: IMPLEMENTATION PASS - `:blendlib-fabric-common:test :blendlib-fabric-client:test` passed after the payload/state/store integration (14 tasks). Focused entity runtime/public-boundary tests, block-entity contracts, Showcase synchronization/source-boundary tests, consumer-fixture checks, and the root Java-25 `clean check` all passed. The final root refresh was `clean check` (43 actionable tasks; 21 executed, 22 cached), `:blendlib-core:test`, and `buildRelease` (37 tasks; 5 executed, 32 up-to-date), all successful. The fresh post-NPE full Java-25 `:blendlib-fabric-client:test --rerun-tasks` passed 33 suites / 128 tests / 0 failures / 0 errors. `git diff --check` was empty.
Build: IMPLEMENTATION PASS - forced recompilation of `:blendlib-fabric-client:compileClientJava`, `:blendlib-showcase:compileJava`, and `:blendlib-showcase:compileClientJava` succeeded against local Minecraft 26.1.2/Fabric Loader 0.19.3/Fabric API 0.154.2+26.1.2/Java 25. Existing serial warnings and Fabric's public renderer-registry deprecations were non-fatal; no version change occurred.
Dedicated server: P6 HARNESS STARTUP SMOKE PASS / DEFAULT-RUN RECORD NON-GATE - the earlier generic `D:\\BlendLib\\blendlib-showcase\\run\\server` record is not isolated Gate evidence because the current audit found its bind/world defaults are `*:25565` / `world`. Separately, the purpose-built `p6SyncServer` reached `Done (0.469s)!` at `127.0.0.1:25575` in `run/p6-sync-server` with only the P6 temporary world, then saved all dimensions. Its noninteractive stdin was unavailable; after confirming every-dimension saves, root ended only the verified server PID 32104 and independently confirmed port 25575 released. This is valid P6 harness/startup evidence only, not two-client synchronization or a P6 Gate PASS; no official server/world was touched.
Client/manual: STARTUP SMOKE PASS / VISUAL AND TWO-CLIENT SYNC WAITING - a real isolated client loaded Minecraft 26.1.2, Fabric Loader 0.19.3, BlendLib/Showcase, Render thread, Indigo/LWJGL/OpenAL, and resource reload. It logged `blendlib_reload candidate_generation=1 active_generation=1 published=true stale=false models=4 missing=0 diagnostics=0`. A later startup after adding the altar blockstate/model had no `Block{blendlib_showcase:animated_altar}` missing-model warning and no client crash-report directory. The exact isolated client PID 54968 was then stopped. Offline development-credential/Realms 401 messages and Loom's missing `build/resources/client` warning are recorded as unrelated environment warnings. This is not visual proof, multiplayer proof, or a Gate PASS.
Performance: N/A - P7 owns the performance Gate.
Known gaps: P3 remains under the user's separate audit; P4/P5 visual/audit evidence remains outstanding. P6 still needs user-managed real visual confirmation and a two-client/dedicated-server synchronization exercise covering tracking replay, state replacement by sequence, transient expiry, disconnect cleanup, and dimension change. Entity and block-entity payloads intentionally carry no dimension/session identifier under the approved v1 wire contract. A read-only static audit found that the current receivers resolve `context.client().level` only inside their deferred client-executor lambdas, so a held old-level callback can be attributed to a newly replaced level before it enters the runtime/store. This is a deterministic code-path risk, not a reproduced Fabric scheduling defect: a controllable queued-receiver integration test must hold the callback across a level replacement and prove rejection/no-pending-state for entity and block-entity targets. If it applies old semantics, pause and propose an ADR rather than silently changing the payload. The user's implementation-continuation authorization does not promote P3--P6 to PASS or authorize a phase commit.
Next action: Preserve `docs/evidence/P6-implementation.md`, `docs/manual-p6-sync-acceptance-v1.md`, and the unstaged P3--P6 diff for the user's audit. When scheduled, execute the isolated two-client exercise through `p6SyncServer`/`p6ClientA`/`p6ClientB`, retain all specified logs/screenshots/video, and resolve any findings before a P6 Gate/commit decision.

Supplemental isolated-harness evidence (2026-07-29): `p6SyncServer`, `p6ClientA`, and `p6ClientB` now resolve as separate Loom runs under `run/p6-sync-server`, `run/p6-client-a`, and `run/p6-client-b`; their dedicated template requires `127.0.0.1:25575`, offline test identities, disabled RCON, and a P6-only world. `P6IsolatedSyncRunContractsTest` and a full forced Showcase test passed. An initial noninteractive P6 server start reached `Done (3.423s)!` in the dedicated run directory and saved all dimensions; it touched no formal server/world. Minecraft expands its own `server.properties`, so the preparation task was corrected to require every template safety key while preserving extra generated defaults/comments and never overwriting an existing file. A forced no-daemon prepare rerun passed and preserved both `server.properties` and `eula.txt` byte-for-byte. The original shared Gradle daemon emitted a stale `ClosedFileSystemException` after the controlled test process exited; no-daemon reruns rebuilt the disowned Loom cache and passed. This is harness/startup evidence only: no two real clients, tracking replay, sequence, transient, disconnect, dimension, or late-packet result is claimed.

Supplemental real isolated P6 run (2026-07-30): A real `p6ClientA` entered only the P6 loopback server/world, created the Showcase actor and altar, disconnected through the Minecraft UI, and exited Gradle `0`; retained `run/p6-sync-server/logs/debug-1.log.gz` records the server side. The first concurrent `p6ClientB` attempt terminated before server connection in `glfw.dll+0x10fa1` / `GLFW.glfwPollEvents`, with no BlendLib frame and low available physical memory recorded in `run/p6-client-b/hs_err_pid33508.log`; it is a local concurrent-GUI/environment incident, not a protocol attribution. With A fully closed, B independently reached the Render thread, connected to the same isolated server, saved `run/p6-client-b/screenshots/2026-07-30_00.23.58.png`, disconnected cleanly, and exited Gradle `0`; the final server `latest.log` records B join/leave and all-dimension save. This proves independent real-client controls and normal disconnects, but not a simultaneous A/B semantic attack, persistent/transient replay, sequence replacement, dimension change, late-packet behavior, or visual Gate. P6 remains `WAITING`; see `docs/evidence/P6-isolated-manual-run-2026-07-30.md`.

Supplemental P6 concurrent retry (2026-07-30): after the standalone B control, B was allowed to reach title before A launched; A also reached title, yet B again terminated in `glfw.dll+0x10fa1` / `GLFW.glfwPollEvents` before either client connected. `run/p6-client-b/hs_err_pid47212.log` has no BlendLib frame and records 1,650 MiB free physical memory with B at roughly 828 MiB working set/1,126 MiB private commit; the isolated server itself reached `Done (0.545s)` and recorded no A/B join. This establishes a repeatable local concurrent-GUI blocker outside P6 payload/tracking paths. Per the safe retry rule, no further same-machine dual-client retry was started; P6 two-client acceptance remains `WAITING` and no protocol/API/threshold was changed.

Supplemental P6 sequence/transient automatic refresh (2026-07-30): a separate runner made exactly one Java-25/no-daemon, max-worker-1 call to `:blendlib-fabric-client:test --tests '*ClientAnimationSyncStoreTest' --tests '*SkinnedAnimationRuntimeTest' --rerun-tasks --console=plain`, using the existing local Gradle mirror/temp directories and starting no P6 server/client. It exited `0` in 45 seconds (12 tasks); current XML totals are 2 suites / 15 tests / 0 failures / 0 errors (`ClientAnimationSyncStoreTest` 3/3, `SkinnedAnimationRuntimeTest` 12/12). This refresh supports sequence replacement/stale-drop and selected transient/runtime automatic paths only. It cannot substitute for the blocked simultaneous A/B attack, persistent replay, real transient expiry, disconnect/dimension observations or late-packet risk; P6 remains `WAITING`. Detailed result is appended to `docs/evidence/P6-isolated-manual-run-2026-07-30.md`.

Deferred-receiver epoch investigation (2026-07-30): an independent static review confirmed the A-level → queued callback → B-level path in the current receiver/runtime sources conflicts with the accepted no-cross-dimension/session state rule. Root's one correctly parameterized Java-25 targeted JUnit attempt compiled and entered the temporary interleaving harness, but stopped at Minecraft's unbootstrapped registry initialization before either receiver callback; it is not a behavioral reproduction. The global-registry/`Unsafe` harness was removed because it was not a stable regression. ADR-021 is therefore **Proposed**, not accepted: it records a client-private receive-time level-identity guard and deterministic test seam, with no current payload/API/wire/version/production change. P6 remains `WAITING` and this receiver seam is paused pending the local owner decision; see `docs/adr/ADR-021-p6-deferred-receiver-level-epoch-guard.md` and `docs/evidence/P6-deferred-receiver-epoch-investigation.md`.

## P7

Phase: P7
Status: WAITING
Owner: p7_material_implementer (client-internal default resolver/provider), p7_reload_cache_implementer (retention/cache evidence), p7_reference_perf_implementer (isolated reference-scene harness); root coordinator integration
Reviewer: User-managed separate audit pending
Commit: N/A
Changed files: Client-internal default material resolver/layer seam retained inside MaterialRenderMapper; reload-registry ownership metrics and 20-cycle regression; deterministic dormant P7 reference manifest, repaired generated 10k rigid/20k-64-joint skinned assets with explicit mesh/joint identity TRS, explicit isolated P7 host/command/resource-pack/run configuration, opt-in client measurement/JFR/report wiring, an exact full-target guard plus frozen-camera preflight before warm-up, isolated Iris/Sodium startup-smoke task/verification, P7 evidence, accepted ADR-016/ADR-017/ADR-020 records, and this ledger. ADR-020 atomically aligns the source constant, both canonical manifests, golden/source-boundary tests, test-asset README, manual/evidence text, and current operator command to `/tp @s 0 67 24 180 0`; no target-work or measurement change occurred. No P7 files are staged.
Automated tests: IMPLEMENTATION PASS - material equivalence/rejection and submit-boundary tests passed; reload/cache targeted tests passed 29 tests with zero failures/errors, including 20 actual listener prepare/apply cycles, stale non-growth, LRU/generation/disconnect cleanup. Post-repair focused `P7ReferenceAssetGeneratorPerfTest`, `P7ReferenceScenarioPerfTest`, and `P7PerfSourceBoundaryTest` passed; the whole Showcase `*Perf*` rerun passed before and after the camera-preflight correction (17 actionable tasks each), `ClientRenderMeasurementCollectorTest` passed (12 actionable tasks), and `generateP7ReferenceAssets :blendlib-showcase:verifyP7BenchmarkClientRun` passed (17 actionable tasks, 1 executed). The current Java-25 root `clean check` passed (43 actionable tasks; 20 executed, 23 from cache), `:blendlib-core:test` passed (6 up-to-date), and `:blendlib-showcase:verifyP7IrisSodiumSmokeClientRun` passed (19 actionable tasks; 3 executed, 16 up-to-date), including fixed Iris/Sodium SHA-1 and normal-run/classpath isolation checks. An early post-ADR-020 focused Gradle attempt reached no test task because its daemon stopped with a native-memory allocation OOM (`hs_err_pid25596.log`); it supplies neither a passing nor failing assertion result. A later serial combined focused refresh did execute `P7ReferenceScenarioPerfTest` (6 tests / 0 failures / 0 errors), as recorded in the ADR-019/ADR-020 supplement below. These are implementation evidence only; current `git diff --check` passed before this ledger update.
Build: IMPLEMENTATION PASS - no dependency or version changes; post-repair generated P7 fixture hashes are rigid 4656f2c4b058dabff49f84ed8b7ae000dd8d0fde5b11627c3336138ff1c796c9 and skinned 799ea4a009a79f3fdf7665fe29702d6760345f95092d70fbf38e85bb9daffc26. The current root `buildRelease` passed with 62 actionable tasks after the clean-build P8 dependency repair.
Dedicated server: PASS - the current P7 candidate ran only through `:blendlib-showcase:runP7SmokeServer` in `run/p7-smoke-server`, bound `127.0.0.1:25574`, prepared only `blendlib-p7-smoke-world`, and loaded Minecraft 26.1.2/Fabric Loader 0.19.3/Fabric API 0.154.2+26.1.2/Java 25 with BlendLib/Showcase. It reached `Done (3.235s)!`; root then sent `stop` through the same process's standard input, all dimensions saved, Gradle returned `0`, port 25574 released, and no crash-report directory appeared. Exact hashes are in `docs/evidence/P7-isolated-server-smoke-2026-07-30.md`. The earlier generic `run/server` record remains historical non-Gate evidence. This server smoke is independent from `run/p7-benchmark` and closes none of P7's client/visual/JFR/FPS/Iris-Sodium/reload-leak requirements; P7 remains `WAITING`.
Client/manual: BASELINE STARTUP SMOKE PASS / FIRST P7 RUNTIME ATTEMPT FAIL / POST-REPAIR LIMITED NO-CRASH SMOKE / ADR-020 HOST-RELIEF STARTUP BLOCK / IRIS-SODIUM, VISUAL, AND PERFORMANCE WAITING - the prior isolated client reached Render thread, Indigo/LWJGL/OpenAL and clean resource reload. The first isolated `run/p7-benchmark` client loaded the generated pack, generation 2 with six models/zero diagnostics, and explicit 100 rigid plus 25 skinned hosts. At the prescribed camera it emitted `p7-runtime-invalid-1785311742510.json` because the first measured frame submitted rigid=0 and skinned=10 rather than 100/25, then later crashed on `Skin normal transform requires an invertible joint matrix` for a P7 skinned host. A fresh post-repair disposable-world smoke loaded the repaired pack, created all 125 hosts, wrote only `p7-runtime-invalid-1785313497726.json` for its pre-teleport warm-up `rigid=0, skinned=0`, and then the historical literal `/tp 0 67 24 180 0` was rejected by the 26.1.2 parser; it did not move the player or start capture. ADR-020 is accepted and the active protocol is `/tp @s 0 67 24 180 0`. A later single source-aligned isolated 3 GiB-cap launch reached pack/resource reload but recorded NVIDIA `GL_OUT_OF_MEMORY` for the 27-layer 2048x2048 block atlas, then native `Chunk::new` malloc failure for 2,714,840 bytes. It did not reach stable UI/world/host/teleport/JFR/screenshot/report state and is retained in `docs/evidence/P7-adr020-host-relief-startup-failure-2026-07-30.md`; it is not a fresh capture, visual, or performance result. The subsequent controller correction now holds the pre-teleport interval in WAITING. The optional isolated `run/p7-iris-sodium` startup smoke loaded MC 26.1.2/Loader 0.19.3 plus Iris 1.11.2 and Sodium 0.9.1, reached the Render thread/resource reload and had no crash report or JVM fatal-error file before only its verified child PID 63284 was stopped. It used no shaderpack and is startup/no-fatal-render-error evidence only, not visual, shaderpack, all-host, performance, or Gate evidence. Both invalid reports and the historical crash remain under `run/p7-benchmark`; none is visual or performance evidence. Offline credential/Realms 401 messages and the known development classpath warning remain environment observations.
Performance: HARNESS PASS / REAL CAPTURE INVALID / GATE WAITING - the frozen dormant scenario remains 100 x 10,000-triangle rigid plus 25 x 20,000-triangle/64-joint skinned (1,500,000 total triangles), 600 warm-up frames, 1,800 samples, 60-FPS target, nearest-rank p50/p95, allocation provenance, cache/handle observations. The controller correctly rejected partial submissions and did not produce an FPS/allocation/performance result. No valid real client JFR/profiler, 60-FPS, shaderpack/Iris-Sodium visual, or 20-reload human/runtime result is claimed.
Known gaps: P3--P6 remain user-managed audit/visual/two-client WAITING. The fixed 26.1.2 Iris/Sodium artifacts are confined to `run/p7-iris-sodium/mods`; the verifier proves they are absent from normal Showcase run directories, default classpaths, and the Showcase JAR. ADR-016 retains the strict RC material/pipeline boundary. ADR-017's exact true-in-frustum scenario, manifests, tests, client-condition preflight, state-machine invalidation, and report fields are implemented. The historical literal `/tp 0 67 24 180 0` rejection remains retained as a failed, no-movement/no-capture observation only. The owner accepted ADR-020 on 2026-07-30; all active P7 protocol text now uses `/tp @s 0 67 24 180 0`, with no coordinate, visibility, target-work, warm-up, sample, or FPS change. The strict generated-skin repair and existing camera preflight are automated-test covered and limited client smokes have no new crash report, but neither proves every skinned host submitted/rendered. The fresh source-aligned launch adds a host graphics/native-memory/pagefile block: it recorded GPU and JVM OOM before a controllable UI, and C: has zero free bytes. The user's continuation authorization does not waive any prior or P7 Gate.
Next action: Preserve all failed-command/startup logs, but do not start another P7 client from this machine state. A future fresh isolated all-host/JFR capture must use exactly `/tp @s 0 67 24 180 0` only after the user authorizes and completes a safe host-level remedy for C:/pagefile and graphics-resource availability. Do not change P7 counts, geometry totals, warm-up/sample counts, FPS target, or claim a new benchmark result until real-client all-host submission/visual, Iris-Sodium/20-reload/manual evidence exists. Per the user's authorization, no P7 PASS or phase commit is authorized while those conditions remain unresolved.

Supplemental test refresh (2026-07-29): A state-machine refactor left `P7PerfSourceBoundaryTest` searching for the retired controller-local `State.WARMUP` branch. The test was corrected to assert the actual contract: the controller calculates exact `100/25` target submissions before handling `INVALID_SUBMISSIONS`, and `P7BenchmarkCaptureStateMachine.acceptExactSubmission` returns that transition before incrementing `warmupFrames`. No production performance code, counts, thresholds, or ADR state changed. Root reran `:blendlib-fabric-client:test :blendlib-showcase:test --rerun-tasks --console=plain` successfully (19 executed tasks), and `git diff --check` remained empty. This is implementation regression evidence only; P7 remains WAITING.

ADR-017/ADR-020 implementation supplement (2026-07-30): the accepted true-in-frustum contract is implemented in the P7 scenario/scene projection, two canonical manifests, capture-controller preflight, deterministic state-machine invalidation, report serialization, and their focused tests. ADR-020 changes only the accepted operator spelling to `/tp @s 0 67 24 180 0`. The combined contract retains the exact `4 x 25` rigid and `1 x 25` skinned placements, `100/25` hosts, 1,500,000 triangles, 64 joints, 600 warm-up frames, 1,800 samples, 60-FPS target, `1920x1080`/16:9, FOV 90, FOV-effect 0, and render distance >=8. The pre-ADR-020 implementation runner completed 14 focused tests with zero failures/errors plus `compileClientJava`, generation, and P7 benchmark-run verification using Java 25/no daemon. Root independently inspected the source/manifest equality and verified local 26.1.2 bytecode: `Window.getWidth/getHeight` return the physical framebuffer fields, so the controller's client-condition values are not GUI-scale proxies. The later command-alignment Gradle retry is recorded separately as a native-memory OOM before test execution; it cannot refresh the focused result. This is source/automatic evidence only, not real-client, JFR, visual, Iris/Sodium, 20-reload, performance, or Gate PASS.

Fresh ADR-020 isolated-launch record (2026-07-30): after root regenerated the P7 pack from the accepted source and verified its manifest contains exactly `/tp @s 0 67 24 180 0`, one fresh `runP7BenchmarkClient` launch targeted only `run/p7-benchmark`. It reached Java 25/Fabric resource reload with six models, zero missing handles, and zero diagnostics, but its 8,148 MiB ergonomically selected JVM heap hit a 2,119,960-byte native allocation failure before a stable UI, world creation/selection, host spawn, teleport, JFR, screenshot, or P7 runtime report. The retained failure is `run/p7-benchmark/hs_err_pid52148.log` and its Gradle logs/result record are `build/manual-p7-adr020-capture-evidence/2026-07-30-174307`. This is a local host-memory startup failure, not a retryable capture or a teleport/render/performance conclusion; P7 remains `WAITING`. No formal server/world, push, publication, deployment, or release operation occurred.

Bounded-heap P7 startup record (2026-07-30): one distinct environment-controlled retry set only `JAVA_TOOL_OPTIONS=-Xms512m -Xmx3g`, leaving the accepted command, exact 100/25 hosts, 1,500,000 triangles, 600/1,800 frames, and 60-FPS standard intact. The isolated P7 client reached its title screen and the unsubmitted fresh-world configuration UI with the generated pack loaded, but no world was created/selected/changed and no spawn, teleport, JFR, screenshot, or runtime report occurred. It then stopped with another native allocation failure: `hs_err_pid49320.log` records a 3,072 MiB cap, a 3,223,192-byte allocation failure, about 1,117 MiB free physical memory, and only 5 MiB available page file. Logs and result are `build/manual-p7-adr020-capture-evidence/2026-07-30-174825-xmx3g`. This establishes an external concurrent-host commit/pagefile block, not a P7 functional result; no further P7 client launch is authorized from the current memory condition. P7 stays `WAITING`.

Host-relief P7 startup record (2026-07-30): after root stopped only a verified idle BlendLib-owned Gradle daemon, one fresh source-aligned `runP7BenchmarkClient` launch again used only `JAVA_TOOL_OPTIONS=-Xms512m -Xmx3g` and left every P7 workload/Gate condition unchanged. The generated manifest still contained exactly `/tp @s 0 67 24 180 0`. The client reached resource reload, then `logs/latest.log` recorded NVIDIA `GL_OUT_OF_MEMORY` for a 27-layer 2048x2048 block-atlas texture; `hs_err_pid36792.log` records a 2,714,840-byte native `Chunk::new` allocation failure under the explicit 3,072 MiB maximum heap. It never reached stable UI, a local world, host spawn, teleport, JFR/profiling, screenshots, or a runtime report. Evidence and hashes are retained in `docs/evidence/P7-adr020-host-relief-startup-failure-2026-07-30.md`. A read-only check immediately after found C: at zero free bytes; that also prevented creation of a new collaboration helper (Windows error 112). This is a host graphics/native-memory/pagefile startup block, not a capture or product result. P7 remains `WAITING`; do not rerun until the user authorizes a safe host-level remedy.

## P8

Phase: P8
Status: WAITING
Owner: p8_release_wiring_implementer, p8_docs_legal_implementer, p8_v262_adapter_implementer; root coordinator integration
Reviewer: User-managed separate audit pending
Commit: N/A
Changed files: Local-only RC Gradle/publication wiring and metadata; independent `blendlib-local-maven-consumer-fixture`; deterministic staged Add-on packaging; generated archive/host-runtime dependency and license/NOTICE inventories with negative fixtures; P8 evidence and `docs/release/`; isolated `spikes/fabric-26.2/`. The clean-build repair adds the explicit `releaseShowcaseJar` predecessor to `generateReleaseInventories`, so the inventory cannot race the Showcase primary artifact. The later freshness repair adds explicit inputs from each source JAR to its release-copy task and from the staged Add-on directory to its ZIP task, so a stale `build/release` primary artifact cannot be silently up-to-date. No files are staged. Non-Add-on metadata uses only `LicenseRef-BlendLib-Local-Only`; GPL-3.0-or-later remains exclusively under `blender-addon/`.
Automated tests: IMPLEMENTATION EVIDENCE / SHA-256 INTEGRITY PASS / P8 GATE WAITING - the current Java-25 root `clean check` passed (43 actionable tasks; 20 executed, 23 from cache), `:blendlib-core:test` passed, Add-on validation/package verification passed, and the standalone blank consumer `clean check` passed in the local-Maven verification. A clean `buildRelease` initially exposed the missing Showcase predecessor; after the minimal dependency repair, the writing agent's clean `buildRelease` passed (70 actionable tasks) and the root coordinator's follow-up `buildRelease` passed (62 actionable tasks), including `verifyReleaseInventories`, the malformed/duplicate/incomplete negative fixtures, `verifyReleaseSha256`, and `verifyReleaseSha256AtBuildReleaseEnd`. Root independently recomputed all 17 SHA-256 entries and verified 15 primary artifacts, 122 matching dependency/license component records, Add-on helper exclusion, and no authoring assets in the runtime JAR. The root coordinator also reran isolated 26.2 `check --rerun-tasks`: 20 XML files, 63 tests, 0 failures/errors.
Build: ADR-019/ADR-020-ALIGNED LOCAL RC ARTIFACT + SHA-256 INTEGRITY PASS / P8 GATE WAITING - Java-25/no-daemon/one-worker `buildRelease` succeeded in 50 seconds (62 tasks: 25 executed, 1 from cache, 36 up-to-date), including the Local Maven blank consumer `clean check`, Add-on validation/package, inventories, negative fixtures, and end-of-build SHA verifier. Root independently rehashed all 17 current manifest rows with `MISMATCHES=0`. Current release runtime is `31d0f528c8baa58ca62ae86238951a79ae5c9183318565e4228a5d43ddb3950a` (420189 bytes), Showcase is `f013e47fbd350f6e9772fa08fa28157f84c2fbbf126ae118f08e2294e6e01d66` (122545 bytes), Add-on is `e0e2bf71af516b364958bd8b49ec8118254e74e6d7986c81e29805010f931b5b` (36403 bytes), and current `SHA256SUMS` is `5c0ced836a37a4086ddf9ff99c247b080f3b48dc0e4ee0b84299eb9f0d4559c4`. `javap` confirms the rebuilt Showcase embeds `/tp @s 0 67 24 180 0`. Local Maven remains under `D:\BlendLib\build\local-maven`; Maven checksum sidecars are derived rather than duplicate payloads. The runtime POM exposes only same-version pure `blendlib-api` for javac and no core/common/client modules. The 26.2 spike remains separately built and separately hashed (`71cea76d1155a25f4f27c424d12748bf54a3f0bb5b3b3092cb4df08f877ad950`), never a 26.1.2 RC bundle input.
Dedicated server: CURRENT-MANIFEST ISOLATED-HARNESS LIMITED SMOKE PASS / P8 GATE WAITING - the historical default Showcase and Local-Maven `run/server` captures at wildcard port 25565 remain non-Gate development observations. After the new build, PRE/POST 17-row SHA manifests were byte-identical and independently valid. The purpose-built Showcase harness reached `Done (0.474s)` at `127.0.0.1:25585` in its P8 temporary world; the Local Maven coordinate consumer executed `verifyLocalMavenConsumerBoundary`, reached `Done (0.397s)` at `127.0.0.1:25586`, and saved every dimension. Only verified child PIDs 62080 and 33860 were stopped after saves; both ports released. Their outer Loom `-1` results are controlled-cleanup outcomes, not startup failures. No formal server/world was touched; see `docs/evidence/P8-current-manifest-server-consumer-rebind-2026-07-30.md`.
Client/manual: PRE-ADR-019/ADR-020 MANIFEST ISOLATED STARTUP SMOKE PASS / CURRENT-ARTIFACT CLIENT REBIND PENDING / VISUAL WAITING - the prior `:blendlib-showcase:runP8CurrentManifestShowcaseClient` record reached title, Render thread/Indigo/LWJGL/OpenAL and `blendlib_reload candidate_generation=1 active_generation=1 published=true stale=false models=4 missing=0 diagnostics=0`, but it embeds the historical Showcase JAR and cannot be called a current-RC client smoke. The current rebuilt server/consumer result does not substitute for client rebind, static/rigid/skinned visual, interaction, reload, sync, or Iris/Sodium evidence. See `docs/manual-client-acceptance-v1.md`.
Performance: WAITING - P7 real reference-scene/JFR/Iris-Sodium/20-reload performance Gate remains unresolved.
Known gaps: P3--P7 remain WAITING for the user-managed audit and/or real visual, dual-client, Iris/Sodium, reload and performance evidence. The current 26.1.2 RC artifacts and 17-entry manifest are ADR-019/ADR-020 aligned, but P8's current Showcase client startup rebind, real visual evidence, P6 receiver-epoch owner decision, P7 real capture, and remaining P3--P7 Gate evidence remain unresolved. The user has not selected a final non-Add-on license, public name, source policy or publication channel. The 26.2 deliverable is only the separate static adapter spike (`docs/evidence/P8-26.2-spike.md`), not a production 26.2 runtime.
Next action: Preserve the full unstaged diff for the user-managed audit. When host memory permits, perform the current-manifest isolated Showcase client startup rebind, then resume the separately blocked P7 real capture; do not stage/commit as passed, push, publish, tag, deploy, or select a final non-Add-on license until all prior Gate evidence and the P6 ADR-021 decision are resolved.

Supplemental P8 local delivery refresh (2026-07-29): Root reran Java-25 `--no-daemon buildRelease` successfully (62 actionable tasks), including Add-on validation/package, Local Maven blank-consumer `clean check`, inventories, negative fixtures, SHA verifier, and end-of-build SHA verifier. Root independently rehashed every current `SHA256SUMS` entry: `P8_SHA256SUMS_INDEPENDENT_VERIFIED entries=17`. The primary runtime, Showcase, sources, Javadoc, and Add-on identities are recorded in `docs/evidence/P8-current-artifact-rebind.md`; release navigation is now available from root `README.md` and `docs/release/README.md` without implying publication. Root also reran the isolated 26.2 spike with Java 25/no daemon: 20 XML result files, 63 tests, zero failures/errors, and separate SHA-256 `71cea76d1155a25f4f27c424d12748bf54a3f0bb5b3b3092cb4df08f877ad950`. This is local build/document/spike evidence only; P8 remains WAITING.

Supplemental P8 current-manifest isolated smoke harness (2026-07-29): Opt-in Loom entries now reserve `blendlib-showcase/run/p8-current-manifest-server` (`127.0.0.1:25585`), `blendlib-showcase/run/p8-current-manifest-client` (offline `BlendLibP8Smoke`), and the independently built Local Maven consumer `run/p8-current-manifest-server` (`127.0.0.1:25586`). Both server preparations create only their exact EULA/properties or reject any differing required safety key; templates fix loopback, offline mode, RCON disabled, and P8-only temporary world names. `docs/evidence/P8-isolated-smoke-harness.md` requires PRE/POST verification and capture of the full current `SHA256SUMS`, distinguishes Showcase development-classpath smoke from an external Showcase JAR installer, and requires the consumer's actual Local Maven boundary check before its server smoke. New no-launch contract tests passed with Java 25/no daemon: `:blendlib-showcase:test --tests 'com.liy.blendlib.showcase.P8IsolatedSmokeRunContractsTest' --rerun-tasks --console=plain`, focused consumer test, and consumer `check --rerun-tasks` including `verifyLocalMavenConsumerBoundary`. No P8 server/client was launched, no release artifact or `SHA256SUMS` was changed, and this harness is not a smoke/Gate result; P3--P7 and P8 remain WAITING.

Supplemental P8 current-manifest server smoke (2026-07-29): after the current Java-25 `buildRelease`, `D:\BlendLib\build\manual-p8-current-smoke-evidence\2026-07-29-232829` independently verified all 17 `SHA256SUMS` rows before and after the capture and retained byte-identical manifest copies. The isolated Showcase server reached `Done (2.766s)` on `127.0.0.1:25585` and saved overworld/End/Nether; the Local Maven consumer `check` executed its actual boundary proof, then its isolated server reached `Done (2.854s)` on `127.0.0.1:25586` and accepted a graceful console `stop` with all-dimension saves. The successful Showcase non-PTY process had closed stdin, so after exact command-line/port/run-directory validation the coordinator authorized termination of only Java child PID 24184; the evidence records that controlled-cleanup exception and confirmed port release. No client, release rebuild, artifact/SHA change, formal server/world, push, publication or deployment occurred. This is current-manifest server-smoke evidence only, not an external installer, visual client or P8 Gate PASS.

Supplemental P8 current-manifest client smoke (2026-07-29): `D:\BlendLib\build\manual-p8-current-smoke-evidence\2026-07-29-234001-client` independently verified all 17 `SHA256SUMS` rows before and after `runP8CurrentManifestShowcaseClient`, retained byte-identical manifest copies, and copied the isolated client log. The client used only `blendlib-showcase/run/p8-current-manifest-client` with local identity `BlendLibP8Smoke`; it reached the Render thread/Indigo/LWJGL/OpenAL and generation-1 reload with four models, zero missing handles and zero diagnostics, completed its first-run local accessibility welcome screen, reached title, then exited normally with Gradle exit 0. Its isolated crash-report directory is absent. Offline credential/Realms messages and Loom's known missing `build/resources/client` warning are recorded environment noise. This is development-classpath startup proof bound to the current local manifest, not an external installer, in-world visual, interaction, reload, sync, Iris/Sodium, performance or P8 Gate PASS.

Supplemental P8 post-P5-fix local artifact refresh (2026-07-30): Java-25/no-daemon `:blendlib-core:test` completed successfully, followed by `buildRelease` successfully in 32 seconds (62 tasks; 30 executed, 32 up-to-date). The rebuild revalidated the local Maven blank consumer, Add-on ZIP, inventories/negative fixtures, and release SHA verifier. The coordinator independently rehashed all 17 current `SHA256SUMS` rows; the current runtime JAR is `7675f92fc5606ded4cbf6e842eb08960e68cc1471e290d5cb91b668b7151cbec` (420161 bytes). Full primary-artifact identities are in `docs/evidence/P8-current-artifact-rebind.md`. No client/server/formal world was launched or changed, and no push, tag, public release, or production deployment occurred. P8 remains WAITING.

P8 release-copy freshness remediation supplement (2026-07-30): root inspection caught a real local delivery defect: a successful `buildRelease` left the release runtime JAR at 420161 bytes/old double-read bytecode while its source JAR was 420189 bytes. `releaseRuntimeJar` and `releaseShowcaseJar` had output-only Gradle declarations; a parallel static audit found the same missing staged-directory input in `packageBlenderAddon`. The narrow build-script repair adds exactly those three inputs, without changing package names, versions, API, LicenseRef, source exclusion, artifact paths, publication scope or finalizer chain. A fresh Java-25/no-daemon serial `buildRelease --max-workers=1` then succeeded in 25 seconds (62 tasks; 23 executed, 39 up-to-date), actually executing runtime/Showcase copy and Add-on ZIP creation while retaining Local Maven blank-consumer, inventories, negative fixture and final SHA checks. Root independently rehashed all 17 current manifest entries with zero mismatches; source/release/Local-Maven runtime are byte-identical 420189-byte `9d1db91dea9e9535f322637555704844f5fec0191d1d918d00cfd942bd0941c4`, source/release Showcase are byte-identical 122537-byte `0650772884ef6a6ffab08bb4fb030fc3efe6472c934bad119419cbaa94f405eb`, and direct release-JAR `javap` proves `onEntityUnload` uses one local `connectionSession` snapshot. The Add-on ZIP is `e0e2bf71af516b364958bd8b49ec8118254e74e6d7986c81e29805010f931b5b` (36403 bytes), contains one GPL `LICENSE` and excludes the schema helper; current `SHA256SUMS` SHA-256 is `b0de9c2d25d9c16f5e412ae74a77bc6fc4f109c2c2f0b1310a51f694373ca6a1`. This is delivery-integrity evidence only, not a current runtime installation/smoke, visual, performance, audit or P8 Gate PASS; no push/tag/publication/deployment/formal-server operation occurred.

P8 current-manifest isolated smoke rebind (2026-07-30): `D:\BlendLib\build\manual-p8-current-smoke-evidence\2026-07-30-081245` independently recomputed all 17 SHA-256 rows before and after the capture, retained byte-identical PRE/POST manifests, and did not rebuild or modify the RC. The dedicated Showcase server reached `Done (0.439s)` on the fixed loopback P8 port and saved all dimensions; its background launcher had no writable stdin, so only the fully verified server child PID 9452 was ended after saves and its task reports controlled-cleanup failure rather than a startup failure. The real isolated Showcase client reached title, Render thread/Indigo/LWJGL/OpenAL and `blendlib_reload` generation 1 with four models, zero missing/diagnostics, then exited by UI with Gradle `BUILD SUCCESSFUL in 1m 18s`; the isolated crash-report directory is empty and targeted BlendLib crash signatures are absent. The blank Local Maven consumer executed `verifyLocalMavenConsumerBoundary` in a successful `check`, then its isolated server reached `Done (0.398s)` and saved all dimensions before only verified child PID 46432 was ended and its port released. This is current-manifest limited startup/resolution evidence only: it is not an external installer, visual, sync, Iris/Sodium, 20-reload, performance, P3--P7 audit, or P8 Gate PASS. No formal server/world, push, tag, PR, public release, remote publish or deployment was used.

ADR-022 client procedural-rotation supplement (2026-08-02): the strict animated entity path now evaluates one optional client-only modifier after immutable base-pose sampling/caching and before canonical rigid/skinned palettes, socket capture, and CPU skinning. The ordinary consumer callback uses only the outer-adapter `BlendEntityRotation` and immutable `BlendEntityRotationPose` facade; it cannot add/remove nodes or access translation/scale, and package-private conversion applies only sparse normalized rotation overrides to a derived core pose. Cross-invocation facade returns, unknown nodes, nulls, and invalid quaternions are rejected. `ClientAnimationRigView.fromNodes` remains package-private, so the high-level public callback ABI exposes no core construction type. The Local-Maven coordinate consumer now compiles a real `.poseModifier(...)` callback without project/core/common/client-module compile dependencies, while the runtime POM remains pure-API-only. Final Java-25/no-daemon/one-worker `clean check` and `buildRelease` both succeeded; the latter rebuilt the local Maven RC, ran the independent consumer's six passing tests, and revalidated packaging, inventories, and SHA-256. The client adapter records 37 suites / 155 tests with zero failures/errors, including base-cache isolation, rigid/skinned/no-modifier behavior, facade provenance/domain validation, modified-palette socket capture, and the non-public rig factory. A separate Ancient Dragon consumer then completed `clean check build` with 17 tests and compiled its client inertia callback from the published coordinate. Independent read-only review returned final PASS after the public compile boundary and attack-mask continuity findings were repaired. This is a scoped ADR-022 implementation and local delivery result only: no Minecraft client was launched, real bone-axis/mirroring/cumulative-motion/collision-distance visuals remain WAITING, and no P3-P8 Gate, publication, stage, commit, push, tag, or deployment is claimed.

ADR-023 complete-root-rotation supplement (2026-08-02): IMPLEMENTATION PASS / LOCAL DELIVERY ONLY. The strict animated entity builder now accepts one extraction-only complete root quaternion through the outer-adapter `BlendEntityRootRotationSelector`; the ordinary interpolated-yaw path remains unchanged when absent. A configured selector automatically uses an origin-centered rotation-invariant culling sphere. Java-25/no-daemon/one-worker `clean check buildRelease` succeeded (70 actionable tasks), including 37 client suites / 159 tests, the separate Local-Maven consumer compile/test and dependency boundary, packaging, inventories, and SHA verification. Deterministic random tests rotate all corners of asymmetric prepared bounds through 256 root quaternions. The Ancient Dragon consumer independently completed `clean check build` with 8 suites / 47 tests and uses the root selector for synchronized yaw, pitch, roll, inversion, and full maneuvers. A distinct read-only reviewer returned final PASS after verifying the shared client/server staged-pose and rig-aware head/neck path. Real in-world visual and clearance feel remain user acceptance; no P3-P8 Gate, public release, publication, stage, commit, push, tag, or deployment is claimed.
## X7 D2b frozen CPU policy projection (2026-08-30)

Status: IMPLEMENTATION EVIDENCE — targeted verification passed; fresh independent review and all
non-structural gates remain pending.

Base: `agentloop/x7-shared-lifecycle` at `e93443966c9e8700253e860463935503f7383832` before this scoped change.

Scope: The six package-private X7 policy classes are rehomed from `perf.x7` to the reload-private package. A source-bound `ClientGenerationLeaseBinding` now freezes only an exact CPU-route primitive derived from the actual generation, render-handle, and source-view identities. It does not manufacture X6 geometry/material/LOD identities or expose an X7, GPU, owner, or lease type. X4 retains that route together with its one prepared `RenderVisibility`; X6 retains only the CPU-route primitive before its existing ownership transfer, so each compatible later submission retains its own immutable `RenderVisibility` decision.

Excluded / WAITING: GPU B1 allocation/draw, pass or deferred-callback lease completion, full X7 budget/LOD/animation projection, hardware/performance/visual/Iris/Sodium validation, and any public shape change to `ClientModelView`, `ModelRenderSnapshot`, `ClientModelLookup`, or `ClientRenderMeasurementSnapshot`. No client or server was started for this implementation candidate.

Targeted evidence: one Java-25/no-daemon/one-worker `:blendlib-fabric-client:test` invocation
covering the six reload policy suites, D2b composition, API boundary, and selected X4/X6 suites
completed with outer exit `0` / `BUILD SUCCESSFUL in 18s` / 13 executed tasks. XML totals are 11
suites / 93 tests / 0 failures / 0 errors / 0 skipped; command and per-suite SHA-256 values are in
`docs/expansion/x7/test-evidence-policy.md`. No retry, module/root build, benchmark, client, or
server run was performed.

## X7 r1 bounded structural formal integration PASS (2026-08-30)

The D2b status above is superseded only for its review-pending statement: the exact D2b candidate
`73fe3c9ddebb8f0aaebe65d0f76dde945d20bf10` received a fresh independent **PASS
(0C/0H/0M/0L)**. Its final review is
`D:\BlendLib-review-artifacts\x7-cpu-policy-d2b-review-20260830-075452101\FINAL_REVIEW.md`
(SHA-256 `324E6407FC16E149C4145C05ADC079A6A3E7E9C50724A746DB59B0D75E5F69F7`), with
self-excluded manifest SHA-256
`5C198C1A9C9D558AEF37A7600E28923B057F4DEDBB816600FBDC3504D4C840B8`.

The formal integration task started from clean `agentloop/blendlib-expansion` at
`991d5477d67114c0a3b9676a0411771c781ca140`. It fast-forwarded through the exact 14-commit
policy/GPU/D1/D2a/D2b chain to `73fe3c9`, then cherry-picked the independent four-commit benchmark
chain in order. The four stable patch IDs match, all 18 benchmark blobs match, the two cumulative
path sets have zero overlap, and the pre-metadata formal source head/tree is
`f1c2fc0f345ab47c584c183832820c01482e804e` /
`d5d3a2f7f43be5dc0a70ea8b36029df792e68228`.

Exactly one formal module command was run:
`.\gradlew.bat :blendlib-fabric-client:build :blendlib-showcase:build --no-daemon
--max-workers=1 --console=plain`. It exited `0` with `BUILD SUCCESSFUL in 48s`; current XML is
100 suites / 529 tests / 0 failures / 0 errors / 1 host-permission portable-symlink skip. No
focused/root/release/client/server/game/benchmark command was run. Full mapping, isolated review
artifact SHA-256 values, XML digests, JAR hashes, and scope are in
`docs/expansion/integration/x7-r1.md`.

The independent formal review returned **PASS (0C/0H/0M/0L)** for exact candidate
`191a5552f540a2c9689304189931c555d6788ccb`, tree
`df23faa3acbf151d90d43a442ef0d909e56f1363`. Its report SHA-256 is
`66EB0909DB9F90FD3ECC211FBED06E509A1038E02D873F04861077B9434A8613` and its self-excluded
manifest SHA-256 is `5E0A5F0397910B8EC0F1B4BE0FC31795BE54AF7CFED7885EE38563AB2001FBB8`.
This bounded structural PASS is not X7 completion and does not unlock X8. Actual GPU production
allocation/upload/draw/close,
pass/deferred-callback completion, full budget/LOD/animation runtime projection, repeated reload,
real client/server, visual, Iris/Sodium, hardware/JFR and performance acceptance all remain
**WAITING**; existing P0--P8 Gate states are unchanged.

## X7 T1a trusted render-owner and pass-host formal integrated PASS (2026-08-30)

Status: **INDEPENDENT PASS (0C/0H/0M/0L) / FORMAL FAST-FORWARD COMPLETE** for the bounded T1a
scope. Exact base is `191a5552f540a2c9689304189931c555d6788ccb`; reviewed/formal commit is
`8e0e58db3e1b67fb80adc160bd72d3d177a5fab9`, tree
`97f1e612b8687424c52f56bf4ed2a195109975d2`. Review report SHA-256 is
`B13E265658FD325739C01A7D12F5A7E6925F5D502DF2138CF5A9B079D4A155BC`; manifest SHA-256 is
`CEB25826AFC876FA2DD6BB763B8BD97FADB0A33290A0167E84E9E3BE9294A58F`. This does not make X7
complete or unlock X8.

Scope: production `BlendLibClientEntrypoint` now creates its registry through one additive
no-argument trusted 26.1.2 factory. The public `ClientModelRegistry()` and `publish(...)` remain
CPU-only. Its package-private owner is fixed to `Minecraft.getInstance()::execute`,
`RenderSystem.assertOnRenderThread`, and `RenderSystem.queueFencedTask`; no public executor/device
constructor exists. A package-private pass host registers Fabric
`AFTER_SOLID_FEATURES` and `BEFORE_TRANSLUCENT_TERRAIN` exactly once, asserts the render thread, and
runs only a synchronous phase scope. T1a's scope is empty and owns no context, target, encoder,
pass, buffer, allocation, upload, or draw.

D1 remains the single generation resource lifecycle and physical-close truth. No resource set or
empty/dormant callback is attached, and `X7GpuGeneration.retire`/its scheduler are not registered as
D1 callbacks. Existing X4/X6 D1 parents are unchanged. `CLIENT_STOPPING` still begins registry
retirement but does not drain `queueFencedTask`; GPU allocation stays disabled because shutdown
with live resources has no bounded final-fence policy.

Focused evidence: one offline Java-25/no-daemon/one-worker
`:blendlib-fabric-client:test` invocation selected exactly seven suites covering trusted bootstrap,
pass phases/registration/thread scope, D1 close/retry, entrypoint order, and public/adapter
boundaries. It exited `0` with `BUILD SUCCESSFUL in 18s`; fresh XML is 7 suites / 45 tests / 0
failures / 0 errors / 0 skipped. No retry, module/root build, client/server, benchmark, or network
run was performed. Full command and exclusions are in
`docs/expansion/x7/t1a-pass-owner.md`.

Remaining **WAITING** at the T1a boundary: production complete-set construction/attachment; buffer allocation/upload;
target/pipeline; actual `CommandEncoder`/`RenderPass`; buffer binding/draw; pass completion receipt;
shutdown with live resources; repeated reload; real client/server; visual; Iris/Sodium;
hardware/JFR; benchmark eligibility; and every performance claim. Existing P0--P8 Gate states are
unchanged.

## X7 T1b generic D1 attachment isolated candidate

- Exact base: `8e0e58db3e1b67fb80adc160bd72d3d177a5fab9`; isolated branch
  `agentloop/x7-t1b-resource-attach`. Fresh independent review is pending, so this does not promote
  the formal X7 candidate or any P0--P8 Gate.
- A package-private non-empty, already-complete set can transfer caller ownership once into one D1
  lifecycle-record slot for its exact current generation object. Public `ClientModelRegistry()` and
  its public reflection inventory remain unchanged and CPU-only.
- Attachment, retire, supersede, and close use the existing D1 monitor. Rejection leaves caller
  ownership unchanged; retirement freezes the slot; the accepted set closes synchronously as D1's
  first already-fenced callback. Any physical failure remains in D1's existing failed-callback retry
  truth, with successful callbacks skipped on retry.
- One offline focused client-test command passed 6 suites / 50 tests / 0 failures / 0 errors / 0
  skipped with process exit `0` and `BUILD SUCCESSFUL in 18s`. No retry, module/root build,
  client/server, benchmark, or network run occurred. Full evidence is in
  [the T1b ledger](expansion/x7/t1b-attachment.md) and ADR-X7006.
- R1 independent review is immutable **FAIL (0C/0H/1M/0L)** because its attach-versus-supersede
  test accepted either uncontrolled winner. M1 remediation adds only a package-private admission
  barrier/overload with a normal-path NOOP, then uses that barrier and the existing transaction
  claim hook to prove attachment-first and supersede-first orders separately. One remediation run
  passed only `ClientGenerationResourceAttachmentTest`: 1 suite / 11 tests / 0 failures / 0 errors /
  0 skipped, exit `0`, `BUILD SUCCESSFUL in 13s`; no other suite or build/runtime command was run.
  Fresh independent rereview remains pending.
- No entrypoint/pass-host/trusted-owner, reload listener/transaction, X4/X6, or `render.x7gpu` file
  changed. Real resources, production attachment wiring, allocation/upload, source-plan mapping,
  target/pipeline, RenderPass/draw, pass receipt, live-resource shutdown, repeated reload, runtime,
  visual, Iris/Sodium, hardware/JFR, benchmark and performance remain **WAITING**.

## X7 T1c final-present owned-fence shutdown R2 PASS and formal fast-forward

- Exact base: `3914f89f1203ade13e90c4959801b910657eccdf`, tree
  `40f8863b73ccf4ab00b9c7a1b8762cd5448ceb1d`; isolated branch
  `agentloop/x7-t1c-shutdown-fence`. R1 candidate `18d4a96daad26147361c79a80cbd0000a5973252`
  has immutable independent **FAIL (0C/1H/0M/0L)** history. Its scoped repair candidate
  `b3660bb6007ba89847461f5f0cc3c43f4d0d7478`, tree
  `dacf56e6080c50f13cbdbc6cad9e376196c8d9b6`, received independent R2 **PASS
  (0C/0H/0M/0L)** and was formally fast-forwarded into
  `D:\BlendLib-agentloop` / `agentloop/blendlib-expansion` at the exact same object. R1 report SHA-256 is
  `CD43A7DAB790B07F9F70A9518D463D18EBEFA84744E4C48BB4CCBDF8D5C70056`.
- R2 report SHA-256 is
  `FADB6814BD02E499E9DEC5CE367E15EA477F53EC533495B9DE960AFB4FE8438F`; its self-excluded
  validated-manifest SHA-256 is
  `15E3639FCCBD86C25399FBCE434713176EB24463213FD7862C2644FC81C0A369`. The formal branch reflog
  records `merge b3660bb6007ba89847461f5f0cc3c43f4d0d7478: Fast-forward` at
  `2026-08-30 12:20:36 +0800`. This does not promote X7, X8, or any P0--P8 Gate.
- A package-private coordinator closes creator admission at `QUIESCING`, then asks D1 to either
  seal one immutable shutdown-only batch/attempt or retain exact non-closed creator, publication
  transaction, lease, handoff, or existing-normal-fence failure. Publications now hold a D1 permit
  from before claim through record-owned publication/abort cleanup; a nonzero cutoff count is typed
  `OUTSTANDING_TRANSACTIONS`. Normal and final close registration are mutually exclusive; late
  invalidated handoffs cannot queue a new fence or retain a live attempt.
- A package-private Minecraft 26.1.2 adapter accepts only the exact audited Mojang/runtime raw and
  Loom-processed class hashes and owns only one `GpuFence`. Two client-only
  `require=1` injections bracket the unique original `flipFrame` call without redirecting,
  cancelling, or repeating it. Only an AFTER zero-poll signal authorizes D1 synchronous physical
  close. Timeout/error closes only the owned fence object.
- `CLIENT_STOPPING` remains ordered before service bootstrap and is a non-throwing, idempotent
  diagnostic fallback. It does not drain RenderSystem's private FIFO, close buffers on timeout, or
  close the game device. The public trusted factory again returns a fresh isolated registry on
  every call. Only the sealed `BlendLibClientServices` production bootstrap installs the unique
  hook owner; repetition with the same installed registry/backend and unchanged runtime is
  idempotent, while another registry is rejected before hook installation. Public descriptors and
  the CPU-only public constructor remain unchanged; there is still no production
  creator/attachment caller.
- Focused evidence is complete with the failure history retained: the exact eight-class invocation
  ran 65 tests with 63 pass / 2 contract-evidence failures / 0 errors / 0 skipped, exit `1`. After
  fixing only dual-pin/class-entry evidence, an approved contract-only run first recorded 3/4 and
  exit `1`, then the final contract-only run passed 4/4, exit `0`, `BUILD SUCCESSFUL in 9s`.
  At the R1 candidate commit, its final retained XML was 1 file / 4 tests / 0 failures / 0 errors / 0 skipped, SHA-256
  `68D024315076EE56F26D0D546ED02CAE354D15682911887AE00BA98905050802`. The other 61 passing tests
  were not rerun; no green aggregate rerun is claimed. Full commands and first-error evidence are
  in [the T1c ledger](expansion/x7/t1c-shutdown-fence.md). No module/root build, client/server,
  benchmark, performance, or network command ran.
- The combined R1/publication repair ran exactly one further five-class focused command. It passed
  5 suites / 30 tests / 0 failures / 0 errors / 0 skipped, exit `0`, `BUILD SUCCESSFUL in 18s`,
  with 14 tasks (3 executed / 11 up-to-date). The filename-sorted five-XML hash-list SHA-256 is
  `092B2B2AA0478D105FFBC2FCD66A7135A948966DC0FC5BD946D41AD9D0722D1D`; individual hashes and the
  exact command are in the T1c ledger. No rerun or other suite/build/runtime command occurred.
  R2 independently parsed and rehashed those retained five suites, then returned the bounded
  **PASS (0C/0H/0M/0L)** without running Gradle, client, server, benchmark, performance-capture,
  or network commands.
- Real allocation/upload/attachment, source-plan mapping, target/pipeline, actual RenderPass/draw,
  last-use/pass receipt, shutdown with real live resources, repeated reload, real runtime/visual,
  Iris/Sodium, hardware/JFR, benchmark eligibility, operational bound acceptance, performance,
  X7 completion, and X8 unlock remain **WAITING**.

## X7 T3a pass-host, static-pipeline, and ordinary-receipt R1 PASS / formal fast-forward (2026-09-04)

- Formal base was `c76777069ce00845171eeadc3a8f1f26b897aa53`, tree
  `3efc5104b81f7a3bcefb11019dd433cae5e5ee40`. The clean single-parent candidate from
  `agentloop/x7-t3a-pass-host` is `b218aa090b39909b053a8b6645285cafd68af5e6`, tree
  `541b4223fa08f9708c2d66bd354a50e2222ff3f7`, with exact parent `c76777069ce00845171eeadc3a8f1f26b897aa53`.
  Formal `agentloop/blendlib-expansion` advanced by `git merge --ff-only` to that same object;
  no cherry-pick, rewrite, or implementation-semantic change occurred.
- Independent R1 review verdict: **PASS (0C/0H/0M/0L)**. Artifact:
  `D:\BlendLib-review-artifacts\x7-t3a-pass-host-review-r1-20260904-224758168\FINAL_REVIEW.md`, SHA-256
  `5962A5913B964428645F2972AF8045AED7DB457C2DCD3379EEBDF10C5A4AF361`; self-excluded manifest SHA-256
  `1BCF9AD811C334A093CCE07D94D914E6ADC05F3511A8A74DA0071BDCDB6014DF`.
- T3a supplies only a client-private synchronous target scope, static rigid probe-pipeline
  registration surface, and ordinary owned-fence receipt. It does not activate a resource island,
  D1 consumer, real RenderPass/draw, batch/instancing/skinning path, or reuse T1c's shutdown-only
  final-present fence.
- Test evidence is preserved exactly: first supplied run selected 13 test paths = 12 pass / 1
  test-path selection failure, not a 13-green aggregate. Corrected Target pinpoint evidence is
  4/4, XML SHA-256 `D1A312D8B396C29314D2547866C55D0349DE8019C55ABA78CD7E0FC1AD39BE79`. Host and
  Receipt XML hashes are supplied only—`79488C5061784B1ABDFF20EFC667978EF891C1C7EF52EF921865937B5AFEE061` /
  `96A3F96E064503468460A36BF30F3E31F30028D92C94E4B6BBF04851F5851EBE`—and were not rehashed because
  those current XML files are absent.
- This integration deliberately ran no Gradle/module/root build, client, server, runtime, or
  benchmark. Its exact-fast-forward checks were identity, status, tree, scope, `diff --check`, and
  ledger consistency only. The complete module gate is deferred to final X7 formal integration and
  must run once there.
- Real client shader compile/reload, live target/pass/draw, D1 receipt consumption, batching,
  instancing, GPU skinning, real visual/runtime evidence, Iris/Sodium compatibility, hardware/JFR,
  and performance remain **WAITING**. X7 is incomplete; X8 remains **LOCKED / PENDING**. **Agent
  Innovation: None.**

## X7 T2a1 canonical resource island R3 PASS / formal candidate integration (2026-09-05)

- Frozen T2a0 design evidence is
  `D:\BlendLib-review-artifacts\x7-t2a0-design-20260904-212222431\FINAL_REVIEW.md`, SHA-256
  `3F70C35C9E19CC88A186C515AD5DA6603D321F331401C6491C9A7F10787AFD57`. The clean reviewed candidate
  on `agentloop/x7-t2a0-canonical-bridge` is the exact single-parent chain from
  `c76777069ce00845171eeadc3a8f1f26b897aa53` through
  `95b3c25a836b55ab99755f8a563e0a2eb5ff604a`,
  `318fbccd7d237a1d8d25e2ae2ee4b9290496ce80`, and
  `1f52641b67c27d97977b52a608983a8aa6516608`, tree
  `c9c88bd6496dd29ab6c84402958c90eeed9813ae`.
- The prior T3a formal fast-forward remains intact. Starting from its clean formal head
  `063781ac2bbb6c784b3eddfd4e8eb692d20546b0`, formal integration cherry-picked the supplied T2a1
  commits exactly in source order to `d1f92e9a222b8cae0c177521837ba289d90f2e92`,
  `cc95d3c4e3c67f827ef08310022d101487a30069`, and
  `b080869392542a05d0411a81142c349d6b26c24d`. No conflict, rebase, squash, or implementation-semantic
  alteration occurred. Per-commit stable patch IDs match 3/3; the cumulative patch ID and all 45
  changed-path blobs match the reviewed candidate.
- The immutable review history is: R1 **FAIL (0C/1H/3M/0L)**,
  `D:\BlendLib-review-artifacts\x7-t2a1-resource-island-review-r1-20260904-222841158\FINAL_REVIEW.md`,
  SHA-256 `CE57EB18C3C6F1DE67FD0236B7B80B4B1FDD2BC016451344F062AE63A0080549`; R2
  **FAIL (0C/0H/1M/0L)**,
  `D:\BlendLib-review-artifacts\x7-t2a1-resource-island-review-r2-20260904-232114646\FINAL_REVIEW.md`,
  SHA-256 `7CBBC0115FC1C402F89E3858EFD0D5BD78C8C7BEB104C432CB4FFD0F0FEBCE73`; then R3
  **PASS (0C/0H/0M/0L)**,
  `D:\BlendLib-review-artifacts\x7-t2a1-resource-island-review-r3-20260904-235816042\FINAL_REVIEW.md`,
  SHA-256 `15B36DC9115DFCEA215A7F1A68584FFE5FA96180313892B7F22119B719558731`.
- Evidence truth is intentionally narrow: R3 retains one fresh focused command with three XML suites
  totaling **19 green**. The earlier first repair run had 21 tests with one culture-sort failure, and
  only its boundary-class rerun was green; it is not an all-21-green run. This integration ran no
  Gradle/module/root build, client, server, runtime, or benchmark command. The complete module gate
  remains owned by final X7 formal integration and must run only once there.
- This is an **integrated candidate pending fresh gpt-5.6-sol/max formal integration review**, not X7
  completion or a runtime/visual/performance PASS. T2a2 must still establish pre-CAS D1 transaction
  claim/adoption/publication. Live GPU allocation/upload/draw, client/runtime/reload/visual,
  Iris/Sodium, hardware/JFR, and performance gates remain **WAITING**. X7 is incomplete; X8 remains
  **LOCKED / PENDING**. **Agent Innovation: None.**

## X7 final formal integration r1 remediation gate (2026-09-05)

- Formal T3c integration began at 2dc2622b1f0c64dfeb9a17eb436236f8ca2269ab /
  1231eb3be51a0baea9e0812592a2ed2ccdae5fd7 and produced reviewed code result
  785f865af8b1effa1eca6cf146e758a2054f02dc / f9850ec547d4d27b77de9a27f39fdad0ca26470b.
  The canonical mapping, review inventory, gate manifests, and WAITING boundary are in
  [x7-final-r1](expansion/integration/x7-final-r1.md).
- First final-gate evidence is immutable at
  D:\BlendLib-review-artifacts\x7-final-formal-integration-20260905-112537978: 559 tests /
  2 failures. The two failures were stale test contracts: an expected-three command-literal list
  omitted x7, and a source-boundary assertion rejected the intended client-only Blaze3D link.
- Independent Terra repair 7ef959be52ee69971dc70041a430a489e1d0ceac changes only those two test
  paths. Supplied focused evidence is outer exit 0 / 4 green. The one authorized remediation module
  gate then ran .\gradlew.bat :blendlib-fabric-client:test --offline --no-daemon --max-workers=1
  --console=plain once, with outer exit 0 and 97 XML / 559 tests / 0 failures / 0 errors / 0 skipped.
  Its raw transcript and manifest are retained at
  D:\BlendLib-review-artifacts\x7-final-formal-integration-r2-20260905033800958.
- Current code identity is 7ef959be52ee69971dc70041a430a489e1d0ceac /
  fec817aecf6f3a564ae3052ae691eb6a057ad730, clean at gate capture. This is a **bounded formal
  integration candidate / PENDING fresh final Sol review**, not an X7, GPU, runtime, visual, or
  performance PASS. X8 remains **LOCKED**; source-order proof, live producer/resource integration,
  GPU/device/pass/draw/fence, 20 reloads, runtime/visual parity, Iris/Sodium, hardware/JFR, and
  performance remain **WAITING**.

## X1–X9 Alpha initial merge FAIL and F1–F6 directed static closure — 2026-09-05

The initial true merge joins Alpha parent
`2a0dc2456ee9b640d3e7ad329fcc1dae6d4add8b` and complete X1–X9 source parent
`432942b22daf3589242e7471d857bf1aa83e0849`, with common base `7d88c85`.
Its exact initial candidate is
`204a0075da878d23b817b9f2481f865b5a9e5498`, tree
`95036a3f35e779ec42b75ecba2b7879ceba94d24`.

Independent final reviewer `/root/x1_x9_alpha_final_static_review` returned
**FAIL (0C/3H/3M/0L)** for that initial candidate. The alpha audit-repair PASS
and each X track's historical PASS/FAIL/Gate records retain their original
scope; they do not validate the new composite.

| Finding | Initial-composite failure | Recovery state |
|---|---|---|
| F1 (H) | Strict accessor repair and the production symbols still referenced by existing tests were missing. | CLOSED by the directed static closure below |
| F2 (H) | Receive-time level guarding was disconnected from production callers. | CLOSED by the directed static closure below |
| F3 (H) | Detached consumers used historical RC rather than Alpha coordinates. | CLOSED by the directed static closure below |
| F4 (M) | Strict skin validation had regressed. | CLOSED by the directed static closure below |
| F5 (M) | Animated-bounds production use was absent. | CLOSED by the directed static closure below |
| F6 (M) | X8 artifact/license records conflicted with the existing Apache-2.0/`NOTICE` decision. | CLOSED by the directed static closure below |

The initial conflict resolution regressed selected Alpha repairs; therefore it
is not accurate to state that the initial merge retained every earlier Alpha
repair. The focused source/core/metadata/documentation repair chain is
`f498c6c12b7656e43ac5da7d2b01f96710e1ead4` ->
`6c34e67331dc0955dadd6e1bf70f843e7c488bb0` ->
`311e97db322878514a1677f1822c7e031b4750cb`.

Independent reviewer `/root/x1_x9_alpha_static_finding_closure` reviewed the
fixed candidate `311e97db322878514a1677f1822c7e031b4750cb` / tree
`d50c17ff9e15f20833ee472e83c4b4956103ab9e` and returned **PASS
(0C/0H/0M/0L)**. F1–F6 are **CLOSED** for this directed static recovery. The
immutable initial `204a007` **FAIL (0C/3H/3M/0L)** remains historical fact and
is not amended or recast as PASS.

No test, compile, Gradle, build, Blender, client, server, converter,
benchmark, or verification script was run for the repair or closure. This
static result is not an X1–X9, X7, X8, P0–P8, runtime, visual, reload,
Iris/Sodium, NeoForge binding, hardware, performance, release, or user
acceptance PASS. All such existing Gates remain **WAITING**/**PENDING**. The
user authorized a later ordinary non-force Git push, but this ledger entry
does not claim a push receipt.

## Current merged-tree ordinary Showcase visual acceptance — 2026-09-06

Status: **USER VISUAL PASS (bounded ordinary-Showcase scope)**

The merged tree at tested source HEAD `2dc81d56d1bb346cdce007e3fb0b8286ca03c4ed`
completed root `check buildRelease`, started the normal 26.1.2 Loom Showcase client, loaded four
BlendLib models with zero diagnostics, exercised the registered static, rigid-animation, skinned,
P5, and P7 host entity types in an integrated world, saved every dimension, and stopped normally.
After operating and viewing that client, the user explicitly returned `PASS 视觉验证`.

This promotes only the user-owned ordinary Showcase entity visual check observed in that session.
It does not promote the complete P4 material matrix, item/block-entity coverage, external
release-JAR installation, two-client synchronization, P7/X7 benchmark/performance, 20 reloads,
Iris/Sodium, hardware/JFR, NeoForge/26.2, P3–P8 aggregate Gates, X1–X9 completion, or stable release.
The runtime's own X7 result remains `WAITING / NO_COMPLETED_X7_METRICS`. Exact build, log, command,
warning, clean-shutdown, artifact, and scope evidence is retained in
[the merged Showcase user visual record](evidence/merged-showcase-user-visual-pass-2026-09-06.md).
