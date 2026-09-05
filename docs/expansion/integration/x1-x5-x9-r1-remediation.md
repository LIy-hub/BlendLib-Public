# X1/X5/X9 formal remediation integration r1

## Formal integration state

The official formal branch `agentloop/blendlib-expansion` started from
`181f00c8752bfd0bd336232083c6acad9c0efa4e`. At reviewed head
`19fc6572f6c5f2c1e08dd2a38fe51509bda0909c`, it contains the 34 historical
X1/X5/X9 source picks plus the three reviewed remediation cherry-picks recorded
below. Fresh formal reviewer `/root/formal_x1_x5_x9_review_r2`
(gpt-5.6-sol/max) returned `Verdict: PASS` for that formal tree's X1/X5/X9
integration and remediation only: no Critical/High/Medium findings, and one
Low documentation contradiction in the X5 root-task wording. The preceding
metadata-only commit corrected that Low. Fresh metadata reviewer
`/root/review_x1_x5_x9_metadata_closeout_r2` (gpt-5.6-sol/max) then returned
`Verdict: FAIL` with 0 Critical/High and one history-severity Medium: the
original r1 H/M/L mapping was misstated. This corrective commit fixes that
mapping and must itself receive a fresh gpt-5.6-sol/max metadata-only review
before the coordinator may formally unlock X2, X4, or X6. It is not an X0--X9
acceptance, a P3--P8 Gate result, or a release-stability claim.

## Original review disposition

The formal candidate `181f00c8752bfd0bd336232083c6acad9c0efa4e` received
`Verdict: FAIL` from `/root/integration_x1_x5_x9_review_r1`. This record
preserves that H/M/L remediation history. The later independent
`/root/formal_x1_x5_x9_review_r2` review returned the narrowly scoped formal
`PASS` described above; it does not erase the earlier verdict or promote any
unrelated Gate.

| Review category | Finding | Remediation state |
| --- | --- | --- |
| High | The protected Minecraft server had one attributable untracked Gradle `build/` tree from the earlier wrong-cwd command. | Contained by the one explicitly authorized, hash-preserving quarantine move recorded below. This was not a delete, overwrite, retry, server launch, or tracked-file change. |
| Medium | The X5 configuration-cache runner could print its four-case success sentinel while its process inherited the final expected-invalid Gradle exit code. Windows PowerShell 5.1 could also promote expected native stderr under `ErrorActionPreference = Stop`. | Fixed in `c00be375745e5c3c4e69536a7792a7aaa119a624`. The runner captures native stderr with `Continue` only for expected-invalid cases, restores the preference, asserts every expected exit/status/cache result, restores the native preference, and explicitly leaves `LASTEXITCODE=0` only after the success sentinel. |
| Low | The integration ledger and fixture documentation retained stale review/evidence wording: the repaired runner was still described as an unresolved process failure; X1 ADR status still said its independent review was pending; X9 fixture wording implied a local Khronos Validator result while the external-validator gap remained. | Updated in the listed X1/X5/X9 ledger, ADR, X9 fixture, and integration documents. X1 r10 final, X5, and X9 r6 remain single-track PASS; the later formal r2 review returned the bounded integration PASS, while this new metadata-only closeout still needs its own fresh review. |

## Fresh repair review r1

`/root/integration_x1_x5_x9_repair_review_r1` returned `Verdict: FAIL` after
the first remediation. Its only Medium is that `docs/expansion/README.md` and
`docs/expansion/module-ownership.md` still described the six-item audit FAIL
as current source state rather than X0 capture-time/history. The realtime
`D:\BlendLib\docs\implementation-progress.md:71-124` records a later
independent audit-repair overall PASS and says the current expansion baseline
includes those six repairs. This targeted documentation update corrects that
Medium only. It does not promote P3 REVIEW, P4 WAITING, P5 IN_PROGRESS, or
P6/P7/P8 WAITING; it is not a v1 or whole-project PASS. At that historical
point the candidate awaited a new reviewer; the later formal r2 review supplied
the bounded integration PASS now recorded above.

## Fresh repair review r2

The supplied fresh repair review r2 returned `Verdict: PASS` with no High or
Medium findings. It expressly retained the historical automatic `D:\b`
worktree removal as a Low process deviation. That repair-review PASS neither
erased the original r1/r1-repair history nor by itself made the formal
integration PASS; the later fresh formal r2 review independently supplied that
bounded PASS.

## Formal cherry-pick record

| Reviewed repair source | Formal commit | Patch ID | Result |
| --- | --- | --- | --- |
| `c00be375745e5c3c4e69536a7792a7aaa119a624` | `c20b71f519203bca42dca85401d7bd265d9ba004` | `711b6747e536e559fbc4a1befe2f60117cfd37b1` | equal; clean cherry-pick |
| `d94e4ac5270eaf10897989c5d8170acaf031af34` | `ca4ec77ba5ea1137eff2cd11f5ee362dcb411787` | `ed9817621f038d9596b9e95784dc374fc5be4244` | equal; clean cherry-pick |
| `fcd33bf2e92602eeaef98866bc463435118664ae` | `ed5e7eea7f34f925ea11917e566f7ab55015e87a` | `da3ca1a343fe1a15650d998bf374d2ea8c893b62` | equal; clean cherry-pick |

The three operations had no conflicts and introduced no merge commits. They
brought the reviewed remediation into the formal tree; they did not themselves
convert the original review r1 FAIL, repair review r1 FAIL, or historical Low
deviation into a formal PASS. The later independent formal r2 review produced
the bounded PASS without rewriting that history.

## Re-executed evidence

- Formal reviewer preflight found the formal tree clean at
  `19fc6572f6c5f2c1e08dd2a38fe51509bda0909c`. From X0 base
  `4749b2dd7f1efbeafb2b4f056c70d65161cea1e6` to that head it counted 39 linear
  commits and 0 merges; all 34 original source commits and all 3 remediation
  commits had equal patch-ids (37/37). Pre-amend `9555dce` is not an ancestor.
- Java 25 `clean check --rerun-tasks` exited 0. Independent XML tally: 98
  suite files, 699 tests, 0 failures, 0 errors, 0 skipped.
- Java 25 `--no-daemon --max-workers=1 buildRelease --rerun-tasks` with
  Blender 5.1.2 exited 0 in 1m32s with 62/62 actionable tasks executed.
  Independent recomputation found 17 unique `SHA256SUMS` entries and 0
  mismatches. All five primary archives and all three nested Fabric runtime
  JARs had 0 case-insensitive duplicate entries, 0 unsafe paths, and 0
  authoring entries; each nested JAR had metadata.
- X1: `:blendlib-api:test`, `:blendlib-api-consumer-fixture:check`, and
  `:blendlib-fabric-consumer-fixture:verifyFabricConsumerFixtureDependencyBoundary`
  exited 0. Java 25 `jdeps -s` reported only `java.base` for the API JAR, and
  the API main-source forbidden-reference scan found 0 matches.
- X5: system Python and Blender's Python each ran the standard-library suite
  as 97/97. The real-Blender registration/action/preview/skin/report/batch
  matrix passed all 10 cases with `--python-exit-code 1`. The repaired cache
  runner passed in both direct and caller modes under pwsh 7 and Windows
  PowerShell 5.1 (four runs, each exit 0 with one sentinel). A wrong
  expected-valid model-key injection failed closed in the same four modes
  (each exit 1 with zero sentinels).
- X9: `verify-schema-corpus.py` passed 5 valid and 10 invalid cases, including
  the generated 32/33 capability boundary. `ExperimentalProfileValidatorTest`
  passed 54/0/0/0; `KhronosDerivedFixtureTest` passed 3/0/0/0 and directly
  reads 8 unique payloads.
- The no-local formal-review clone `D:\BFR2-20260803-024543153` remains
  retained preservation evidence; this metadata-only work does not delete it.

## Retained boundaries and gaps

- The external Khronos glTF Validator executable/runnable corpus remains
  unavailable. The 3-test, 8-payload derived-fixture result is not an
  external-validator result.
- X1 has no real platform adapter or reload/lifecycle integration; its SPI is
  still Experimental. X5 interactive viewport quality and Minecraft
  listener/client behavior remain WAITING. X9 remains validation-only: no
  runtime binding, renderer/provider, or Draco/Meshopt/KTX2 codec enablement.
- Existing phase states are unchanged: P3 REVIEW, P4 WAITING, P5 IN_PROGRESS,
  P6/P7/P8 WAITING. No visual, synchronization, reload, dual-client,
  Iris/Sodium, performance, publication, license, or P3--P8 Gate is promoted.

## Protected-tree containment

Preflight established that `D:\MinecraftFabricServer-26.1.2\build` was a
non-reparse tree of 3 directories and one untracked file,
`build\reports\problems\problems-report.html` (147739 bytes, SHA-256
`0B778F8ED7304F6AD589CACC253635986BA3B2CE9AF6C433B5652A2553A22918`,
creation `2026-08-02T23:30:48.0601636+08:00`, mtime
`2026-08-02T23:30:48.0550533+08:00`). The server HEAD was
`aec9f885877146e79ff7a8fefd0594226a41f05b`; its index/tracked diff and the
615 unrelated ordinary-status paths were unchanged before the move.

After parent approval, the only server mutation was one PowerShell
`Move-Item -LiteralPath` from that exact source to:

`D:\BlendLib-review-temp\quarantine-minecraft-server-gradle-incident-20260803-20260803-003009842`

It ran from `2026-08-03T00:32:05.6209889+08:00` to
`2026-08-03T00:32:05.6273598+08:00`. Post-checks found the source absent, the
quarantine tree still 3 directories/one file with the identical size/hash and
times, server `build` untracked count 1 to 0, and no server HEAD/index/tracked
or unrelated-status change. The quarantine remains preservation material, not
a deletion.

The repair worktree later generated two untracked Blender Python cache files.
After an explicit parent-approved preflight (one non-reparse directory, two
untracked `.pyc`, zero tracked files), one `Move-Item -LiteralPath` moved them
without deletion to:

`D:\BlendLib-review-temp\quarantine-integration-repair-r1-pycache-20260803-0058565220238`

The move ran `2026-08-03T00:58:56.5619813+08:00` to
`2026-08-03T00:58:56.5665695+08:00`; both files retained their preflight
SHA-256, size, creation time, and mtime, and the repair worktree had zero
tracked or untracked `__pycache__` paths afterward.

Formal-tree real-Blender validation later created exactly two untracked cache
files under `D:\BlendLib-agentloop\blender-addon\__pycache__`:
`blendlib_exporter.cpython-313.pyc` (SHA-256
`C43B85CD3D955792CEC27C6564C290DAA857D7F3480293C4B9258929DAAE049B`)
and `blendlib_x5_toolchain.cpython-313.pyc` (SHA-256
`779DDCB0FA8F50FFB8F7DFFCDAA36297815E959E85335A63F6C928C46A986650`).
After an approved non-reparse/two-untracked-file preflight, one
`Move-Item -LiteralPath` preserved them at
`D:\BlendLib-review-temp\quarantine-formal-x1-x5-x9-remediation-pycache-20260803-0216409000879`.
The source is absent and the two quarantined files retain their preflight
hashes, sizes, creation times, and mtimes; this was a containment move, not a
deletion.

## Historical Low process deviation

The GOAL forbids automatic worktree/clone deletion. During this remediation,
a detached short-path test worktree was created with:

`git worktree add --detach D:\b 181f00c8752bfd0bd336232083c6acad9c0efa4e`

It was used only to run the real-Blender X5 matrix with
`--python-exit-code 1`, because the long repair-worktree path correctly
triggered X5's Windows legacy-path budget. Before removal it had HEAD
`181f00c8752bfd0bd336232083c6acad9c0efa4e`, empty `git status --porcelain`,
and only ignored generated `build/` content (`git clean -ndX` reported
`Would remove build/`). It was then automatically removed with:

`git -C D:\BlendLib-agentloop worktree remove --force D:\b`

No durable creation/removal timestamp was retained, so none is claimed. This
is the retained nonblocking Low process deviation in
`/root/integration_x1_x5_x9_repair_review_r1`; no user data was lost, and the
path and worktree metadata have no residual entry. It remains disclosed rather
than reconstructed or concealed.

Agent Innovation: none. This remediation has not self-reviewed.
